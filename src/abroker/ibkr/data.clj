(ns abroker.ibkr.data
  (:import [com.ib.client Contract ContractDetails Order Decimal ScannerSubscription TagValue]
           [java.time Instant LocalDate ZonedDateTime])
  (:require [clojure.string :as str]
            [clojure.tools.logging :as log]
            [java-time.api :as jt]
            [abroker.data :as data]
            [abroker.ibkr.codes :as codes]
            [techpunch.util :as u]))


; Conversions to/from IB's custom Decimal class

(defprotocol IBDecimalConvert
  "Convert to & from ibrk's Decimal type"
  (^Decimal as-decimal [x] "Coerce to an ibkr Decimal")
  (^BigDecimal as-bigdec [x] "Coerce to a java BigDecimal")
  (^double as-double [x] "Coerce to double")
  (^long as-long [x] "Coerce to long"))

(extend-protocol IBDecimalConvert
  nil
  (as-decimal [_] nil)
  (as-bigdec [_]  nil)
  (as-double [_] nil)
  (as-long [_] nil)

  Decimal
  (as-decimal [d] d)
  (as-bigdec [d] (.stripTrailingZeros (.value d)))
  (as-double [d] (.doubleValue (.value d)))
  (as-long [d] (.longValue d))

  Long
  (as-decimal [l] (Decimal/get l))
  (as-bigdec [l] (bigdec l))
  (as-double [l] (.doubleValue l))
  (as-long [l] l)

  Double
  (as-decimal [d] (Decimal/get d))
  (as-bigdec [d] (bigdec d))
  (as-double [d] d)
  (as-long [d] (.doubleValue d)))


; IB Date/Time Stuff

(def date-fmt-in
  (jt/formatter "yyyyMMdd"))

(def date-time-fmt-in
  (jt/formatter "yyyyMMdd HH:mm:ss VV")) ; 20251118 09:13:26 America/Denver

(def date-time-fmt-out
  (jt/formatter "yyyyMMdd-HH:mm:ss"))

(def ib-tz
  (jt/zone-id "UTC"))

(def ny-tz
  (jt/zone-id "America/New_York"))

; TODO finish investigating various date/times from ibkr and implement
(defn ib-local-datetime
  [ib-datetime-str]
  (if (= 8 (count ib-datetime-str))
    (-> ib-datetime-str
        (LocalDate/parse date-fmt-in)
        (.atStartOfDay ny-tz))
    (ZonedDateTime/parse ib-datetime-str date-time-fmt-in)))

(defprotocol DateTimeConvert
  (^String ib-datetime-str [x]))

(extend-protocol DateTimeConvert
  nil
  (ib-datetime-str [_] nil)
  String
  (ib-datetime-str [s] s)
  Long
  (ib-datetime-str [l] (ib-datetime-str (Instant/ofEpochMilli l)))
  Instant
  (ib-datetime-str [i] (ib-datetime-str (ZonedDateTime/ofInstant i ib-tz)))
  ZonedDateTime
  (ib-datetime-str [z] (-> (.withZoneSameInstant z ib-tz)
                           (.format date-time-fmt-out))))

; Other types

(def oca-types
  {nil 3 ; my preferred default
   :default 3
   :cancel-others-on-partial 1
   :reduce-partial-disallow-others 2
   :reduce-partial-allow-others 3})

(def trigger-methods
  {nil 0 ; default
   :default 0
   :double-bid-ask 1
   :last 2
   :double-last 3
   :bid-ask 4
   :last-or-bid-ask 7
   :midpoint 8})

(def bar-size
  ; not to be confused with Duration
  ; see https://www.interactivebrokers.com/campus/ibkr-api-page/twsapi-doc/#hist-bar-size
  {:1s   "1 secs"
   :5s   "5 secs"
   :10s  "10 secs"
   :15s  "15 secs"
   :30s  "30 secs"
   :1m   "1 min"
   :2m   "2 mins"
   :3m   "3 mins"
   :5m   "5 mins"
   :10m  "10 mins"
   :15m  "15 mins"
   :20m  "20 mins"
   :30m  "30 mins"
   :1h   "1 hour"
   :2h   "2 hours"
   :3h   "3 hours"
   :4h   "4 hours"
   :8h   "8 hours"
   :1d   "1 day"
   :1w   "1 week"
   :1M   "1 month"})

(defn api-str
  "x is a string, symbol, or keyword. Returns the ibkr api friendly version
  of x."
  [x]
  (-> (name x)
      (str/upper-case)
      (str/replace #"[\-\.]" " ")))

(defn contract
  [{:keys [type subtype symbol exchange currency]
    :or {currency "USD"}}]
  (let [exchange (or exchange (case type
                                :crypto "PAXOS"
                                "SMART"))
        c (doto (Contract.)
            (.symbol (api-str symbol))
            (.secType (codes/sec-type type))
            (.exchange exchange)
            (.currency currency))]
    (when (= :option type)
      (.right c (codes/option-right subtype)))
    c))

(defn order
  [client-id order-id parent-id
   {:keys [uuid allocation action type quantity
           tif good-till transmit? oca-group oca-type eth?
           limit-price stop-price touch-price trigger-method]}]
  (let [{:keys [alloc-group account]} allocation
        o (doto (Order.)
            (.orderRef (str uuid))
            (.clientId client-id)
            (.orderId order-id)
            (.parentId parent-id)
            (.action (str/upper-case (name action)))
            (.totalQuantity (as-decimal quantity))
            (.orderType (api-str type))
            (.ocaType (oca-types oca-type)))]
    (when alloc-group (.faGroup o alloc-group))
    (when account (.account o account))
    (when tif (.tif o (api-str tif)))
    (when good-till (.goodTillDate o (ib-datetime-str good-till)))
    (when oca-group (.ocaGroup o oca-group))
    (when (some? transmit?) (.transmit o transmit?))
    (when (some? eth?) (.outsideRth o eth?))
    (when limit-price (.lmtPrice o limit-price))
    (when stop-price (.auxPrice o stop-price))
    (when touch-price (.auxPrice o touch-price))
    (when trigger-method (.triggerMethod o (trigger-methods trigger-method)))
    o))

(defn position
  "Creates a position object from a raw IBKR response event. Returns a map with:
  {:account _ :type _ :symbol _ :quantity _ :avg-cost _}. Type :option will also have
  :subtype for :put or :call."
  [{:keys [account contract pos avg-cost]}]
  (let [type (codes/instrument-type (.getSecType contract))]
    (log/trace "position: " (.symbol contract) (as-bigdec pos))
    (cond-> {:account account
             :symbol (.symbol contract)
             :type type
             :quantity (as-bigdec pos)
             :avg-cost (data/round-price avg-cost)}
      (= :option type) (assoc :subtype
                              (codes/option-subtype (.getRight contract))))))


;; Market Scanner (aka Screener)

(def default-scan
  "Aimed at what's actually tradeable: the day's biggest percent gainers among stocks on
  the major US exchanges, above the price where penny stock noise lives. 25 rows because
  a screen you can't read in one glance isn't a screen (IBKR caps a scan at 50 anyway).
  See fn scanner-subscription for why there's deliberately no volume floor."
  {:instrument  :stock
   :location    :us-major
   :scan-code   :top-gainers
   :rows        25
   :above-price 5.0})

(def scan-opt-keys
  "Every key fn scanner-subscription understands. Anything else is rejected rather than
  ignored — a filter that silently doesn't apply is the worst way to learn about a typo."
  #{:instrument :location :scan-code :rows :stock-type-filter
    :above-price :below-price :above-volume :market-cap-above :market-cap-below
    :avg-option-volume-above :exclude-convertible? :filters})

(defn- scan-str
  "Resolves x to the IBKR string for scan field `what`: raw strings pass through, known
  keywords resolve via aliases, and anything else throws here rather than coming back
  from TWS later as a generic rejection."
  [what aliases x]
  (cond
    (string? x)  x
    (aliases x)  (aliases x)
    :else (u/throw-illegal-arg (str "Unknown scanner " what ":") x
                               "- known aliases:" (vec (sort (keys aliases))))))

(defn scanner-subscription
  "Builds an IBKR ScannerSubscription from opts merged over `default-scan`. :instrument,
  :location and :scan-code take a friendly keyword (see abroker.ibkr.codes) or a raw IBKR
  string; :stock-type-filter takes :all, :stock or :etf; the rest are numbers. An explicit
  nil clears a default, so {:above-price nil} scans with no price floor at all.

  Careful with :above-volume — it's *today's* volume, not average volume, so as a
  liquidity floor it hides the entire market for the first minutes of the session. Use
  {:filters {\"avgVolumeAbove\" 500000}} instead (see fn scan-filters)."
  [opts]
  (when-let [unknown (seq (remove scan-opt-keys (keys opts)))]
    (u/throw-illegal-arg "Unknown scanner opts:" (vec unknown)))
  (let [{:keys [instrument location scan-code rows stock-type-filter
                above-price below-price above-volume market-cap-above market-cap-below
                avg-option-volume-above exclude-convertible?]}
        (merge default-scan opts)
        s (doto (ScannerSubscription.)
            (.instrument (scan-str "instrument" codes/scan-instruments instrument))
            (.locationCode (scan-str "location" codes/scan-locations location))
            (.scanCode (scan-str "scan-code" codes/scan-codes scan-code)))]
    ; an unset numeric field holds a MAX_VALUE sentinel that TWS reads as "no filter",
    ; so only touch the fields we were actually given
    (when rows (.numberOfRows s (int rows)))
    (when stock-type-filter (.stockTypeFilter s (api-str stock-type-filter)))
    (when above-price (.abovePrice s (double above-price)))
    (when below-price (.belowPrice s (double below-price)))
    (when above-volume (.aboveVolume s (int above-volume)))
    (when market-cap-above (.marketCapAbove s (double market-cap-above)))
    (when market-cap-below (.marketCapBelow s (double market-cap-below)))
    (when avg-option-volume-above (.averageOptionVolumeAbove s (int avg-option-volume-above)))
    (when (some? exclude-convertible?) (.excludeConvertible s exclude-convertible?))
    s))

(defn scan-filters
  "Builds the TagValue list for a scan's :filters map, e.g.
  {\"avgVolumeAbove\" 500000 \"changePercAbove\" 3}. These are the filters TWS's screener
  UI shows beyond the handful ScannerSubscription models directly; tag names are
  account-specific and listed in the XML from client/req-scanner-params. Tags and values
  are stringified. Returns nil when there are no filters, which is what TWS wants."
  [filters]
  (when (seq filters)
    (mapv (fn [[tag value]] (TagValue. (name tag) (str value))) filters)))

(defn scan-result
  "Converts a raw :scanner-data event into a result map. :type/:symbol/:currency make it
  a usable instrument, so a result can go straight into req-mkt-data or send-order!.
  Deliberately reports the listing venue as :primary-exchange, not :exchange — :exchange
  on an instrument is the route an order takes, and pinning that to the listing venue
  would quietly bypass SMART routing. Empty strings from IBKR become nil, and fields only
  some scan codes populate (:distance, :benchmark, :projection, :legs) are dropped when
  they're empty."
  [{:keys [rank distance benchmark projection legs-str] :as event}]
  (let [^ContractDetails details (:contract-details event)
        c (.contract details)]
    (into {:rank             rank
           :symbol           (.symbol c)
           :type             (codes/instrument-type (.getSecType c))
           :currency         (.currency c)
           :con-id           (.conid c)
           :primary-exchange (not-empty (.primaryExch c))
           :name             (not-empty (.longName details))
           :industry         (not-empty (.industry details))
           :category         (not-empty (.category details))}
          (remove (comp str/blank? val))
          {:distance distance :benchmark benchmark
           :projection projection :legs legs-str})))
