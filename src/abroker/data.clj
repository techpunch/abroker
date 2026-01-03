(ns abroker.data
  (:import [java.time ZonedDateTime])
  (:require [clojure.math :as m]
            [clojure.string :as str]
            [techpunch.config :as conf]
            [techpunch.num :as num]
            [techpunch.util :as u :refer [valid-arg]]))

(defn config
  "Gets a value inside the :broker-config element of the app config,"
  [& key-path]
  (apply conf/config :broker-config key-path))


;; Order Allocations

(defn allocation [alloc-or-key]
  (cond
    (keyword? alloc-or-key) (alloc-or-key (config :allocations))
    (map? alloc-or-key) alloc-or-key
    :else (u/throw-illegal-arg (str "Expected keyword or map: " alloc-or-key))))

(defn group [alloc]
  (:alloc-group alloc))

(defn account [alloc]
  (:account alloc))

(defn alloc-type [alloc]
  (cond
    (:alloc-group alloc) :group
    (:account alloc) :account))


;; General share & price utils

(defn round-shares
  "Round shares up to near min-lot-size or whole number."
  [alloc shares]
  (let [sz (or (:min-lot-size alloc) 1)
        remainder (mod shares sz)]
    (if (zero? remainder)
      shares
      (+ shares (- sz remainder)))))

(defn shares
  "Returns number of shares for a given allocation and risk target."
  [alloc total-risk risk-per-share]
  (->> (/ (double total-risk) (double risk-per-share))
       (round-shares alloc)
       (long)))

(defn calc-risk
  "Returns the dollar risk amount, throwing an exception if the args
  don't make sense."
  [buy-or-sell entry-price stop-price]
  (let [neg-risk? (case buy-or-sell :buy <= :sell >=)]
    (when (neg-risk? entry-price stop-price)
      (u/throw-illegal-arg "negative risk calc" entry-price stop-price)))
  (abs (- entry-price stop-price)))

(defn round-price [p]
  (let [scale (if (< p 1.0) 4 2)]
    (num/round-double scale p)))

(defn flip [buy-or-sell]
  (case buy-or-sell
    :buy :sell
    :sell :buy
    (u/throw-illegal-arg "must be :buy or :sell" buy-or-sell)))

(defn +-
  "Returns a fn: + for :buy, - for :sell."
  [buy-or-sell]
  (case buy-or-sell
    :buy +
    :sell -
    (u/throw-illegal-arg "must be :buy or :sell" buy-or-sell)))

; Stuff for getting a good limit price for stop orders. The lower a stock's price,
; the closer the limit price needs to be to the stop price or else the order may get
; rejected. Fixed percentage won't suffice. My research says these are generalized
; safe guidelines across major exchanges:
; US Stocks	Within 0.5% – 2% of stop price
; Futures (CME)	Within 5–10 ticks
; Forex (IDEALPRO) Within 0.001–0.005
; Options	Often very tight (1–2 ticks)

(def ^:private dist-min-pct 0.005)
(def ^:private dist-max-pct 0.02)
(def ^:private dist-log-min (m/log 5.0))
(def ^:private dist-log-max (m/log 500.0))

(defn prob-stop-fill
  "For stocks only at this point. Return best guess of a good limit price for
  stp-lmt orders."
  [buy-or-sell price]
  (let [delta-fn (+- buy-or-sell)
        logp (m/log price)
        ratio (-> (/ (- logp dist-log-min) (- dist-log-max dist-log-min))
                  (max 0.0)
                  (min 1.0))
        pct-dist (+ (* (- 1 ratio) (- dist-max-pct dist-min-pct)) dist-min-pct)
        delta (* price pct-dist)]
    (-> (delta-fn price delta)
        (round-price))))

(defn prob-touch-fill
  "Returns an approximate limit price to use for LIT orders given a touch-price so
  that we have good odds of filling. Based on my trading expereince."
  [buy-or-sell touch-price]
  (let [price+- #((+- buy-or-sell) touch-price %)]
    (-> (condp > touch-price
          1.5 touch-price
          5   (price+- 0.01)
          10  (price+- 0.02)
          20  (price+- 0.03)
          30  (price+- 0.04)
          40  (price+- 0.05)
          60  (price+- 0.06)
          80  (price+- 0.07)
          100 (price+- 0.08)
          200 (price+- 0.09)
          300 (price+- 0.10)
          400 (price+- 0.11)
          (price+- 0.12))
        (round-price))))


;; Order Types

(defn mkt [order]
  (assoc order :type :mkt))

(defn lmt [order limit-price]
  (assoc order :type :lmt :limit-price limit-price))

(defn stp [order stop-price]
  (assoc order :type :stp :stop-price stop-price))

(defn stp-lmt [order stop-price limit-price]
  (assoc order :type :stp-lmt :stop-price stop-price :limit-price limit-price))

(defn mit [order touch-price]
  (assoc order :type :mit :touch-price touch-price))

(defn lit [order touch-price limit-price]
  (assoc order :type :lit :limit-price limit-price :touch-price touch-price))


;; Order TIF Types

(defn day [order]
  (assoc order :tif :day))

(defn gtc [order]
  (assoc order :tif :gtc))

(defn- gtd-delta-to-zdt
  "Example gtd-deltas: :15m '15m' :75m :2h :2d"
  [gtd-delta]
  (let [now (ZonedDateTime/now)
        s (name gtd-delta)
        delta (Integer/parseInt (subs s 0 (dec (count s))))
        unit (last s)]
    (case unit
      \m (.plusMinutes now delta)
      \h (.plusHours now delta)
      \d (.plusDays now delta)
      \w (.plusWeeks now delta)
      now)))

(defn gtd
  "good-till can be a ZonedDateTime or a gtc-delta, see fn gtd-delta-to-zdt"
  [order good-till]
  (let [val (if (instance? ZonedDateTime good-till)
              good-till
              (gtd-delta-to-zdt good-till))]
    (assoc order :tif :gtd :good-till val)))

(defn overnight [order]
  (assoc order :overnight? true))


;; Other order options

(def trigger-methods
  #{:default :double-bid-ask :last :double-last :bid-ask :last-or-bid-ask :midpoint})

(defn trigger [order trigger-method]
  (when-not (trigger-methods trigger-method)
    (u/throw-illegal-arg (str "Unknown trigger method " trigger-method)))
  (assoc order :trigger-method trigger-method))

(defn oca-group
  ([order]
   (oca-group order (str "oca" (System/currentTimeMillis))))
  ([order group-name]
   (assoc order :oca-group group-name)))

(defn eth
  "Enable/disable extended trading hours"
  ([order]
   (eth order true))
  ([order enabled?]
   (assoc order :eth? enabled?)))

(defn transmit?
  "When false, saves the order with broker without trasmitting to market."
  [order xmit?]
  (assoc order :transmit? xmit?))


;; Instruments - Tickers, Stocks, Crypto, etc. - aka a Contract in IBKR speak or
;; Asset in Alpaca speak or Instrument in Schwab speak

; Naming note: 'symbol' is the term used by alpaca, ibkr, and schwab instead of
; 'ticker', so we'll roll with it in our data maps

(defn ticker-str [ticker]
  (-> (name ticker)
      (str/upper-case)
      (str/replace #"[\-\/\.\s]" ".")))

(defn instrument [type symbol]
  {:type type
   :symbol (ticker-str symbol)})

(defn stock [symbol]
  (instrument :stock symbol))

(defn crypto [symbol]
  (instrument :crypto symbol))

(defn option [symbol put-call-subtype] ; TODO impl expiration/strike
  (assoc (instrument :option symbol)
         :subtype put-call-subtype))

(defn resolve-instrument
  "If x is a str, we create a stock instrument from it, otherwise we return x, assuming
  x is already an instrument map. In the future I may extend this to parse a string kind
  of like alpaca uses for stocks vs. crypto etc."
  [x]
  (if (string? x) (stock x) x))


;; Orders

(defn order
  "alloc-or-key is either an allocation map or the key of one in the app config.
  action is :buy or :sell"
  [alloc-or-key action quantity]
  (let [alloc (allocation alloc-or-key)]
    (valid-arg alloc "allocation not found in config:" alloc-or-key)
    (valid-arg (pos? quantity) "quantity must be positive")
    (when-let [min-lot-size (:min-lot-size alloc)]
      (valid-arg (zero? (mod quantity min-lot-size))
                 "Order qty must be divisible by alloc group's min lot size:" min-lot-size))
    {:uuid (random-uuid)
     :allocation alloc
     :action action
     :quantity quantity}))

(defn add-stop [parent-order stop-order]
  (-> parent-order
      (update :stop-orders (fnil conj []) stop-order)))

(defn risk-check
  [{:keys [quantity limit-price stop-price touch-price] :as order}]
  (let [price (or limit-price stop-price touch-price)
        max-amt (config :risk-mgmt :max-order-amt)]
    (when (and price (> (* price quantity) max-amt))
      (u/throw-rte "Order exceeds max dollar amount allowed" quantity price max-amt))
    order))

(comment
  (-> (order :tax20 :buy 20)
      (mkt)
      (add-stop (-> (order :tax20 :sell 20)
                    (lmt 100.00)
                    (gtc)))))
