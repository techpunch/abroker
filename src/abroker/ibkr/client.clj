(ns abroker.ibkr.client
  (:require [clojure.core.async :as async :refer [chan go go-loop <! >! alts! close! put! timeout]]
            [clojure.tools.logging :as log]
            [abroker.async-ctx :as ctx]
            [abroker.data :as d]
            [abroker.ibkr.data :as ibdata]
            [abroker.ibkr.ewrapper :as ewrapper]
            [abroker.price :as price]
            [abroker.risk :as risk]
            [techpunch.java :as j]
            [techpunch.util :as u])
  (:import [com.ib.client EClientSocket EReader Bar]))


; IBKR API PACING LIMITATIONS NOTES:
; Max API calls/sec = Max Market Data Lines / 2 = 50 for most users
; Clients have a minimum of 100 mkt data lines, but can have more, depending on
; commissions & equity.
; https://www.interactivebrokers.com/campus/ibkr-api-page/twsapi-doc/#requests-limitations
; Also, no more than 1 *regulatory* snapshot/sec, and historical data has other rules listed
; in the req-historical-data fn below


;; Event handler multimethod - IBKR's EReader calls our impl of their EWrapper class, which
;; turns those calls into event maps and puts them onto an event chan, which processed in a
;; go loop (see start-event-worker below), which dispatches them to this multimethod

(defmulti handle-event :type)

(defmethod handle-event :default [_]) ; default catch-all - do nothing

;; ERROR HANDLING

(def chatty-error?
  (zipmap [2103 2104 2105 2106 2107 2108 2119 2157 2158] (repeat true)))

(defn warning-code?
  "IBKR reserves 2100-2200 for warnings — delayed data notices and the like. Anything
  below that range is a real failure. Worth checking before letting an error tear down
  a request: a 'displaying delayed market data' notice arrives the same way a rejection
  does. https://www.interactivebrokers.com/campus/ibkr-api-page/twsapi-doc/#error-codes"
  [error-code]
  (<= 2100 error-code 2200))

(def ^:dynamic *allow-chatty-errors?* false)


; some errors from tws are general, and some are indicated for a specific req-id
; (in ibkr api req-id == order-id most often); tap-errors allows our wrapped api
; logic like req-historical-bars to listen for errors for its request only

(defonce ^:private error-chan-by-req-id (atom {}))

(defn- tap-errors [req-id]
  (let [c (chan 1)
        ; the swap! has to happen outside log/debug: the macro only evaluates its args
        ; when debug logging is enabled, which made this whole registration a no-op
        taps (swap! error-chan-by-req-id assoc req-id c)]
    (log/debug "num taps for errors" (count taps))
    c))

(defn- untap-errors [req-id]
  (let [[old _] (swap-vals! error-chan-by-req-id dissoc req-id)]
    (when-let [c (old req-id)]
      (close! c))))


(defmethod handle-event :error
  [{:keys [req-id error-code error-msg advanced-order-reject-json]
    :as error-event}]
  (when (or *allow-chatty-errors?*
            (not (chatty-error? error-code)))
    (cond
       ; TODO impl 1100 conn lost, wait a while to see if we don't get 1102 restored
       ; before alerting user
      (#{1100 1102} error-code) nil
      :else (do
              (log/error req-id error-code error-msg advanced-order-reject-json)
              (when-let [c (and req-id (@error-chan-by-req-id req-id))]
                (put! c error-event))))))


;; CONNECTION STUFF

; connection atom is a map with keys:
; client, client-id, events-chan, req-id (an atom), reconnect-fn
(defonce ^:private connection (atom nil))

(defonce reconnecting-fut (atom nil))
(def max-reconnect-sleep-ms 60000)

(declare connected-client)

(defmethod handle-event :next-valid-id [event]
  (reset! (:req-id @connection)
          (dec (:order-id event))))

(defmethod handle-event :connection-closed [_]
  (log/info "IB connection closed")
  (when-let [reconnect (:reconnect-fn @connection)]
    (when (compare-and-set! reconnecting-fut nil :starting)
      (reset! reconnecting-fut
              (future
                (log/info "Reconnect worker started")
                (try
                  (loop [sleep-ms 2000]
                    (Thread/sleep sleep-ms)
                    (reconnect)
                    (when-not (connected-client)
                      (log/debug "Reconnect worker retrying soon")
                      (recur (min (* sleep-ms 2) max-reconnect-sleep-ms))))
                  (catch InterruptedException _
                    (log/debug "Reconnect worker interrupted")
                    (.interrupt (Thread/currentThread)))
                  (finally
                    (log/info "Reconnect worker ending")
                    (reset! reconnecting-fut nil))))))))


(defn- ctx-call!
  "Ensures a connected client before calling ctx/tap!"
  [ctx-atom on-new-ctx-f]
  (if (connected-client)
    (ctx/tap! ctx-atom on-new-ctx-f)
    (log/error "Not Connected")))


(defn client []
  (:client @connection))

(defn connected-client []
  (when-let [c (client)]
    (when (.isConnected c)
      c)))

(defn disconnect! [disable-reconnect?]
  ; disable reconnect since we're explicitly disconnecting
  (swap! connection dissoc :reconnect-fn)
  (when-let [c (connected-client)]
    (.eDisconnect c))
  (when-let [c (:events-chan @connection)]
    (close! c))
  (when-let [fut (and disable-reconnect? @reconnecting-fut)]
    (future-cancel fut)))


(defn client-id []
  (:client-id @connection))

(defn last-req-id []
  @(:req-id @connection))

(defn next-req-id []
  (swap! (:req-id @connection) inc))


(def shutdown-hook
  (delay
    (log/debug "Registering IB jvm shutdown hook")
    (j/add-shutdown-hook
     (fn []
       (log/debug "IB jvm shutdown hook started")
       (disconnect! true)
       (log/debug "IB jvm shutdown hook done")))))

(defn start-socket-worker [client reader signal]
  (log/debug "SockRead worker launching")
  (future
    (try
      (while (.isConnected client)
        (do
          (log/trace "SockRead waiting for signal")
          (.waitForSignal signal)
          (log/trace "SockRead signal received, processing messages")
          (.processMsgs reader)
          (log/trace "SockRead messages processed")))
      (log/debug "SockRead worker ending - client disconnected")
      (catch Exception e
        (log/error "SockRead worker ending - ex" e)))))

(defn start-event-worker [events-chan]
  (log/debug "EventRead worker launching")
  (go-loop []
    (log/trace "EventRead waiting for next event")
    (if-some [event (<! events-chan)]
      (do (try
            (handle-event event)
            (catch Exception e
              (log/error "EventRead error" e)))
          (recur))
      (log/debug "EventRead worker ending"))))


(defn connect! [& {:keys [client-id reconnecting?] :or {client-id 0} :as args}]
  (try
    (disconnect! false)
    (let [{:keys [name host port]} (d/config :brokers :ibkr)
          events-chan (chan 1024)
          signal (com.ib.client.EJavaSignal.)
          client (-> (ewrapper/create events-chan)
                     (EClientSocket. signal))
          user-msg #(if reconnecting?
                      (log/debug %)
                      (log/info %))]

      (reset! connection {:client client
                          :client-id client-id
                          :events-chan events-chan
                          :req-id (atom 0)
                          :reconnect-fn #(apply connect! :reconnecting? true args)})

      (user-msg (str "IB connecting using profile " name))
      (.eConnect client host port client-id)
      (Thread/sleep 300) ; TODO see if we can remove and still get acct events consistently

      (if (connected-client)
        (let [reader (EReader. client signal)]
          @shutdown-hook
          (log/trace "Starting EReader")
          (.start reader)
          (log/debug "EReader started")
          (start-socket-worker client reader signal)
          (start-event-worker events-chan)
          (log/info "IB connected"))
        (user-msg "Couldn't connect")))
    (catch Exception e
      (log/error e "Couldn't connect"))))


;; ORDERS

(defmethod handle-event :open-order [{:keys [contract order order-state]}])

(defmethod handle-event :open-order-end [_])

(defn send-order!
  "Accepts our regular instrument & order, translates them to ibkr objs as necessary,
  and sends the order and any child orders. Returns the parent order id."
  [instrument order]
  (risk/check order)
  (if-let [conn (client)]
    (let [ib-contract (ibdata/contract (cond-> instrument
                                         (:overnight? order) (assoc :exchange "OVERNIGHT"
                                                                    :primaryExchange "NASDAQ")))
          do-send (fn [order parent-id]
                    (let [order-id (or (:order-id order) (next-req-id))
                          ib-order (ibdata/order (client-id) order-id parent-id order)]
                      (log/trace "sending order id" order-id)
                      (.placeOrder conn order-id ib-contract ib-order)
                      order-id))
          parent-id (do-send order 0)]
      (doseq [child (:stop-orders order)]
        (do-send child parent-id))
      parent-id)
    (u/throw-rte "Not Connected")))

(defn req-open-orders []
  (.reqOpenOrders (client)))

(defn req-all-open-orders []
  (.reqAllOpenOrders (client)))

(defn req-auto-open-orders []
  (.reqAutoOpenOrders (client) true))

(defn req-completed-orders []
  (.reqCompletedOrders (client) false))

(defn req-executions []
  (.reqExecutions (client) (next-req-id) (com.ib.client.ExecutionFilter.)))


;; MARKET DATA - Real Time & Historical
; Notes From Nov 2025:
; reqMktData seems to resume ok after disconnecting network or sleeping computer,
; but reqHistoricalData puts out error 10182 and doesn't resume in both cases,
; need to finish implementing graceful handling


(defn- price-bar [{:keys [req-id ^Bar bar]}]
  (log/info
   (price/bar (.time bar) ; TODO fix -> (ibdata/ib-local-datetime (.time bar))
              (ibdata/as-long (.volume bar))
              (.open bar) (.high bar) (.low bar) (.close bar))))

; TODO handle error 10182 (id: 2673) for historical bars: Failed to request live updates (disconnected). disconnects in
; for data interrupts, need to be able to restart


(defmethod handle-event :historical-data [event]
  (price-bar event))

(defmethod handle-event :historical-data-update [event]
  (price-bar event))

(defmethod handle-event :historical-data-end [_])

(defmethod handle-event :tick-field [{:keys [req-id field price size value attrib]}]
  )

(defmethod handle-event :tick-snapshot-end [{:keys [req-id]}])


(defn req-mkt-data [instrument stream?]
  (let [req-id (next-req-id)]
    (.reqMktData (client) req-id
                 (ibdata/contract (d/resolve-instrument instrument))
                 nil (not stream?) false nil)
    req-id))

(defn cancel-mkt-data
  ([]
   (cancel-mkt-data (last-req-id)))
  ([req-id]
   (.cancelMktData (client) req-id)))

(comment
  (req-mkt-data (d/crypto :btc) true)
  (cancel-mkt-data))

(defn req-real-time-bars
  "IBKR Pacing Limitations:
  No more than 60 *new* requests for real time bars can be made in 10 minutes, pretty sure
  they share the allocation with historical bars. See req-historical-bars fn.
  Only 5 second real time bars are allowed as of Oct2025 according to:
  https://www.interactivebrokers.com/campus/ibkr-api-page/twsapi-doc/#request-live-bars"
  [instrument rth-only?]
  (let [req-id (next-req-id)]
    (.reqRealTimeBars (client) req-id
                      (ibdata/contract (d/resolve-instrument instrument))
                      5 "TRADES" rth-only? [])
    req-id))

(defn cancel-real-time-bars
  ([]
   (cancel-real-time-bars (last-req-id)))
  ([req-id]
   (.cancelRealTimeBars (client) req-id)))

(comment
  (req-real-time-bars (d/crypto :btc) false)
  (cancel-real-time-bars))


(defn req-historical-data
  "IBKR Pacining Limitations:
  - Making identical historical data requests within 15 seconds.
  - Making six or more historical data requests for the same Contract, Exchange and Tick Type within two seconds.
  - Making more than 60 (new? - says new in real time section) requests within any ten minute period.
  - Note that when BID_ASK historical data is requested, each request is counted twice
  https://www.interactivebrokers.com/campus/ibkr-api-page/twsapi-doc/#historical-pacing-limitations"
  [instrument duration bar-size rth-only? stream?]
  (let [req-id (next-req-id)]
    (.reqHistoricalData (client) req-id
                        (ibdata/contract (d/resolve-instrument instrument))
                        "" ; TODO impl end time if we find a need
                        duration (ibdata/bar-size bar-size)
                        "TRADES" (if rth-only? 1 0)
                        1 ; https://www.interactivebrokers.com/campus/ibkr-api-page/twsapi-doc/#hist-format-date
                        stream? [])
    req-id))

(defn cancel-historical-data
  ([]
   (cancel-historical-data (last-req-id)))
  ([req-id]
   (.cancelHistoricalData (client) req-id)))

(comment
  (req-historical-data :nvda "1 D" :1d true false)
  (req-historical-data (d/crypto :btc) "1 D" :1m false true)
  (cancel-historical-data))


;; ACCOUNT & POSITIONS

(def position-ctx
  (ctx/ctx-atom #(assoc % :positions [])))

(defmethod handle-event :position [position]
  (->> position
       (ibdata/position)
       (swap! position-ctx update :positions conj)))

(defmethod handle-event :position-end [_]
  (when-let [ctx (ctx/mark-done! position-ctx)]
    (go
      (log/trace "position-end count" (count (:positions @position-ctx)))
      (>! (ctx/out-chan ctx) (:positions ctx))
      (ctx/dispose! ctx))
    (.cancelPositions (client))))

(defn req-positions
  "Returns chan that positions will be delivered to when all have been received. See
  fn abroker.ibkr.data/position for data details."
  []
  (ctx-call! position-ctx (fn [_]
                            (.reqPositions (client)))))


;; MARKET SCANNER (aka Screener)
; A scanner subscription streams: TWS sends one :scanner-data callback per row, then
; :scanner-data-end, and then keeps pushing fresh snapshots until it's canceled. We
; treat a scan as one-shot - take that first snapshot, deliver it, cancel - because an
; open subscription burns one of the few slots TWS allows and keeps re-ranking behind
; your back.
;
; Deliberately no async-ctx here, unlike req-positions: scanner callbacks carry a
; req-id, so concurrent scans already can't be confused for one another, and two
; callers asking for two different screens is normal rather than something to
; deduplicate.

(defonce ^:private scans (atom {})) ; req-id -> {:out chan, :results [scan-result]}

(defn- take-scan!
  "Atomically removes and returns the in-flight scan for req-id, nil if it already
  finished. Whoever gets the entry owns finishing it, so a scan can't be delivered
  twice when an error and the end-of-snapshot race each other."
  [req-id]
  (u/swap-get-prev! scans #(get % req-id) dissoc req-id))

(declare cancel-scan)

(def scan-timeout-ms
  "Safety net, not a knob - callers time themselves out with tools/req-single!. This is
  how long a scan can go without an end or an error before we tear it down, so that a
  snapshot TWS never sends can't strand one of its few subscription slots forever."
  30000)

(defn- watch-scan!
  "Ends the scan for req-id when TWS reports a real error against it - bad scan code, no
  market data permission, too many subscriptions - or when the snapshot simply never
  arrives. The caller gets a closed chan instead of a wait for results that aren't
  coming. Warnings are waited out rather than acted on, since a delayed-data notice
  arrives the same way a rejection does and shouldn't kill a working scan. Exits quietly
  when the scan finishes normally and closes its error tap."
  [req-id]
  (let [errors (tap-errors req-id)
        deadline (timeout scan-timeout-ms)] ; absolute: a warning must not extend it
    (go-loop []
      (let [[err port] (alts! [errors deadline])]
        (cond
          (= port deadline) (do (log/warn "scan" req-id "timed out")
                                (cancel-scan req-id))
          (nil? err) nil ; tap closed, the scan already finished
          (some-> (:error-code err) warning-code?) (recur)
          :else (do (log/warn "scan" req-id "failed:"
                              (:error-code err) (:error-msg err))
                    (cancel-scan req-id)))))))

(defmethod handle-event :scanner-data [{:keys [req-id] :as event}]
  (let [result (ibdata/scan-result event)]
    ; guard against a straggler from a scan we already delivered re-creating its entry
    (swap! scans (fn [m]
                   (cond-> m
                     (m req-id) (update-in [req-id :results] conj result))))))

(defmethod handle-event :scanner-data-end [{:keys [req-id]}]
  (when-let [{:keys [out results]} (take-scan! req-id)]
    (untap-errors req-id)
    (put! out (vec (sort-by :rank results)))
    (close! out) ; buffered results survive the close
    (cancel-scan req-id)))

(defn req-scan
  "Runs an IBKR market scanner (screener) and returns a chan that gets a vector of scan
  results sorted by :rank, then closes; the subscription is canceled once that first
  snapshot arrives. A scan that fails or finds nothing is distinguishable: an empty scan
  delivers [], a failed one closes without delivering anything.

  With no args it runs `abroker.ibkr.data/default-scan` - the day's biggest gainers among
  major-exchange US stocks over $5. opts override those defaults (see fn
  abroker.ibkr.data/scanner-subscription), and an optional :filters map adds the finer
  TWS screener filters (see fn abroker.ibkr.data/scan-filters). Each result is a usable
  instrument, see fn abroker.ibkr.data/scan-result.

  Pair it with abroker.ibkr.tools/req-single! for a timeout. Returns nil when there's no
  connection, like the other req- fns here."
  ([] (req-scan {}))
  ([opts]
   (if (connected-client)
     (let [subscription (ibdata/scanner-subscription opts) ; validates opts before we ask
           req-id (next-req-id)
           out (chan 1)]
       (swap! scans assoc req-id {:out out :results []})
       (watch-scan! req-id)
       ; 3rd arg is TWS-internal options, always nil for us; filters go in the 4th
       (.reqScannerSubscription (client) req-id subscription nil
                                (ibdata/scan-filters (:filters opts)))
       out)
     (log/error "Not Connected"))))

(defn cancel-scan
  "Cancels a scanner subscription and closes its chan without delivering. req-scan does
  this for you when the snapshot lands; call it directly to abandon a scan early."
  ([]
   (cancel-scan (last-req-id)))
  ([req-id]
   (when-let [{:keys [out]} (take-scan! req-id)]
     (untap-errors req-id)
     (close! out))
   ; TWS drops subscriptions on disconnect, so there's nothing to cancel and no live
   ; socket to cancel it on
   (when (connected-client)
     (.cancelScannerSubscription (client) req-id))))


;; SCANNER PARAMETERS
; The XML doc of every scan code, location and filter tag the account can use. It's the
; only way to discover what's valid beyond the aliases in ibkr.codes, and it's several MB.

(defonce ^:private scanner-params-chan (atom nil))

(defmethod handle-event :scanner-parameters [{:keys [xml]}]
  (when-let [c (first (reset-vals! scanner-params-chan nil))]
    (put! c xml)
    (close! c)))

(defn req-scanner-params
  "Requests the scanner parameters XML and returns a chan that gets it as a string. The
  scannerParameters callback carries no req-id, so only one request can be in flight -
  a superseded caller gets a closed chan rather than someone else's answer. Allow a
  generous timeout, it's a big document."
  []
  (if (connected-client)
    (let [c (chan 1)
          [old _] (reset-vals! scanner-params-chan c)]
      (when old (close! old))
      (.reqScannerParameters (client))
      c)
    (log/error "Not Connected")))

(comment
  (require '[abroker.ibkr.tools :as tools])
  (require '[clojure.core.async :refer [<!!]])

  ; the defaults: today's biggest gainers among major-exchange US stocks over $5
  (<!! (tools/req-single! req-scan))

  ; most active by dollar volume, which is a better liquidity read than share count
  (<!! (tools/req-single! #(req-scan {:scan-code :most-active-usd :rows 10})))

  ; unusual volume in liquid mid caps - the avgVolumeAbove filter is the right way to
  ; put a floor under liquidity (:above-volume is today's volume and would hide
  ; everything early in the session)
  (<!! (tools/req-single! #(req-scan {:scan-code :hot-by-volume
                                      :above-price 10
                                      :market-cap-above 2e9
                                      :filters {"avgVolumeAbove" 500000}})))

  ; breakouts: near 52 week highs, up at least 3% today, no price floor
  (<!! (tools/req-single! #(req-scan {:scan-code :near-52w-high
                                      :above-price nil
                                      :filters {"changePercAbove" 3}})))

  ; a raw IBKR scan code and location, for anything the aliases don't cover
  (<!! (tools/req-single! #(req-scan {:scan-code "STK_HALTED" :location "STK.HK.SEHK"})))

  ; discover what your account can actually scan
  (spit "scanner-params.xml" (<!! (tools/req-single! req-scanner-params :timeout-ms 20000)))
  ,)
