(ns abroker.ibkr.data
  (:import [com.ib.client Contract Order Decimal]
           [java.time Instant LocalDate ZonedDateTime])
  (:require [clojure.string :as str]
            [java-time.api :as jt]
            [abroker.ibkr.codes :as codes]))


; Conversions to/from IB's custom Decimal class

(defprotocol IBDecimalConvert
  "Convert to & from ibrk's Decimal type"
  (^Decimal as-decimal [x] "Coerce to an ibkr Decimal")
  (^double as-double [x] "Coerce to double")
  (^long as-long [x] "Coerce to long"))

(extend-protocol IBDecimalConvert
  nil
  (as-decimal [_] nil)
  (as-double [_] nil)
  (as-long [_] nil)

  Decimal
  (as-decimal [d] d)
  (as-double [d] (.doubleValue (.value d)))
  (as-long [d] (.longValue d))

  Long
  (as-decimal [l] (Decimal/get l))
  (as-double [l] (.doubleValue l))
  (as-long [l] l)

  Double
  (as-decimal [d] (Decimal/get d))
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
    (cond-> {:account account
             :symbol (.symbol contract)
             :type type
             :quantity (as-double pos)
             :avg-cost avg-cost}
      (= :option type) (assoc :subtype
                              (codes/option-subtype (.getRight contract))))))
