(ns abroker.ibkr.client
  (:require [clojure.core.async :as async :refer [chan go go-loop <! >! close! put!]]
            [clojure.tools.logging :as log]
            [abroker.async-ctx :as ctx]
            [abroker.data :as d]
            [abroker.ibkr.data :as ibdata]
            [abroker.ibkr.ewrapper :as ewrapper]
            [abroker.price :as price]
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

(def ^:dynamic *allow-chatty-errors?* false)


; some errors from tws are general, and some are indicated for a specific req-id
; (in ibkr api req-id == order-id most often); tap-errors allows our wrapped api
; logic like req-historical-bars to listen for errors for its request only

(defonce ^:private error-chan-by-req-id (atom {}))

(defn- tap-errors [req-id]
  (let [c (chan 1)]
    ; TODO WIP - finish implementing & testing
    (->> (swap! error-chan-by-req-id assoc req-id c)
         (count)
         (log/debug "num taps for errors"))
    c))

(defn- untap-errors [req-id]
  (let [[old _] (swap-vals! error-chan-by-req-id dissoc req-id)]
    (close! (old req-id))))


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
  (d/risk-check order)
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

(defmethod handle-event :position [{:keys [account contract pos avg-cost]}]
  (let [position {:account account
                  :symbol (.symbol contract)
                  :pos (ibdata/as-long pos)
                  :avgCost avg-cost}]
    (swap! position-ctx update :positions conj position)))

(defmethod handle-event :position-end [_]
  (when-let [ctx (ctx/mark-done! position-ctx)]
    (go
      (>! (ctx/out-chan ctx) (:positions ctx))
      (ctx/dispose! ctx))
    (.cancelPositions (client))))

(defn req-positions
  "Returns chan that positions will be delivered to when all have been received."
  []
  (ctx/tap! position-ctx
            (fn [_]
              (.reqPositions (client)))))
