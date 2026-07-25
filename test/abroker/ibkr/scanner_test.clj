(ns abroker.ibkr.scanner-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :refer [chan <!! alts!! timeout]]
            [abroker.ibkr.client :as client]
            [abroker.ibkr.data :as ibdata])
  (:import [com.ib.client Contract ContractDetails ScannerSubscription TagValue]))

; TWS reads these sentinels as "no filter"; a field we never set must keep them
(def ^:private unset-double Double/MAX_VALUE)
(def ^:private unset-int Integer/MAX_VALUE)

(deftest scanner-subscription-defaults
  (testing "defaults scan major US exchange stocks over $5 for the day's top gainers"
    (let [s (ibdata/scanner-subscription {})]
      (is (= "STK" (.instrument s)))
      (is (= "STK.US.MAJOR" (.locationCode s)))
      (is (= "TOP_PERC_GAIN" (.scanCode s)))
      (is (= 25 (.numberOfRows s)))
      (is (= 5.0 (.abovePrice s)))))
  (testing "filters we weren't given are left unset, not zeroed"
    (let [s (ibdata/scanner-subscription {})]
      (is (= unset-double (.belowPrice s)))
      (is (= unset-int (.aboveVolume s)))
      (is (= unset-double (.marketCapAbove s)))
      (is (nil? (.stockTypeFilter s)))))
  (testing "an explicit nil clears a default rather than being ignored"
    (is (= unset-double (.abovePrice (ibdata/scanner-subscription {:above-price nil}))))))

(deftest scanner-subscription-opts
  (testing "friendly keywords resolve and numeric filters apply"
    (let [s (ibdata/scanner-subscription {:scan-code :most-active-usd
                                          :location :nasdaq
                                          :instrument :stock
                                          :stock-type-filter :etf
                                          :rows 10
                                          :above-price 10
                                          :above-volume 100000
                                          :market-cap-above 2e9})]
      (is (= "MOST_ACTIVE_USD" (.scanCode s)))
      (is (= "STK.NASDAQ" (.locationCode s)))
      (is (= "ETF" (.stockTypeFilter s)))
      (is (= 10 (.numberOfRows s)))
      (is (= 10.0 (.abovePrice s)) "ints are coerced to the double the setter wants")
      (is (= 100000 (.aboveVolume s)))
      (is (= 2e9 (.marketCapAbove s)))))
  (testing "raw IBKR strings pass through for codes the aliases don't cover"
    (let [s (ibdata/scanner-subscription {:scan-code "STK_HALTED"
                                          :location "STK.HK.SEHK"
                                          :instrument "STOCK.HK"})]
      (is (= "STK_HALTED" (.scanCode s)))
      (is (= "STK.HK.SEHK" (.locationCode s)))
      (is (= "STOCK.HK" (.instrument s)))))
  (testing "a mistyped alias fails here rather than as a TWS rejection later"
    (is (thrown-with-msg? IllegalArgumentException #"Unknown scanner scan-code"
                          (ibdata/scanner-subscription {:scan-code :top-gainerz})))
    (is (thrown-with-msg? IllegalArgumentException #"Unknown scanner location"
                          (ibdata/scanner-subscription {:location :us-mjaor}))))
  (testing "a mistyped opt key fails rather than silently not filtering"
    (is (thrown-with-msg? IllegalArgumentException #"Unknown scanner opts:.*:above-vol"
                          (ibdata/scanner-subscription {:above-vol 100000})))))

(deftest scan-filters-fn
  (testing "no filters means nil, which is what TWS wants"
    (is (nil? (ibdata/scan-filters nil)))
    (is (nil? (ibdata/scan-filters {}))))
  (testing "tags and values are stringified"
    (let [[^TagValue a ^TagValue b] (ibdata/scan-filters {"avgVolumeAbove" 500000
                                                          :changePercAbove 3})]
      (is (= "avgVolumeAbove" (.-m_tag a)))
      (is (= "500000" (.-m_value a)))
      (is (= "changePercAbove" (.-m_tag b)))
      (is (= "3" (.-m_value b))))))

(defn- scan-event
  ([req-id rank symbol] (scan-event req-id rank symbol "NVIDIA CORP" "Technology"))
  ([req-id rank symbol long-name industry]
   (let [c (doto (Contract.)
             (.symbol symbol)
             (.secType "STK")
             (.currency "USD")
             (.primaryExch "NASDAQ")
             (.conid 4815162))]
     {:type :scanner-data
      :req-id req-id
      :rank rank
      :distance "" :benchmark "" :projection "" :legs-str ""
      :contract-details (doto (ContractDetails.)
                          (.contract c)
                          (.longName long-name)
                          (.industry industry))})))

(deftest scan-result-fn
  (testing "a result is an instrument plus the metadata that makes it worth reading"
    (is (= {:rank 0
            :symbol "NVDA"
            :type :stock
            :currency "USD"
            :con-id 4815162
            :primary-exchange "NASDAQ"
            :name "NVIDIA CORP"
            :industry "Technology"
            :category nil}
           (ibdata/scan-result (scan-event 1 0 "NVDA")))))
  (testing "the listing venue is :primary-exchange so it can't hijack order routing"
    (is (nil? (:exchange (ibdata/scan-result (scan-event 1 0 "NVDA"))))))
  (testing "IBKR's empty strings normalize to nil"
    (let [res (ibdata/scan-result (scan-event 1 3 "FOO" "" ""))]
      (is (nil? (:name res)))
      (is (nil? (:industry res)))))
  (testing "fields only some scan codes populate are dropped when empty"
    (let [res (ibdata/scan-result (scan-event 1 0 "NVDA"))]
      (is (not (contains? res :distance)))
      (is (not (contains? res :legs)))))
  (testing "and kept when a scan code does populate them"
    (let [res (ibdata/scan-result (assoc (scan-event 1 0 "NVDA") :distance "12.5"))]
      (is (= "12.5" (:distance res))))))


;; Client-side accumulate/deliver flow. Driving handle-event directly is the only way
;; to cover this without a live TWS; the scan registry is private but its shape is the
;; thing under test.

(def ^:private scans @#'client/scans)

(deftest scan-event-flow
  (reset! scans {})
  (let [out (chan 1)]
    (swap! scans assoc 7 {:out out :results []})
    (testing "rows accumulate for a live scan and strays are dropped"
      (client/handle-event (scan-event 7 1 "BBB"))
      (client/handle-event (scan-event 7 0 "AAA"))
      (client/handle-event (scan-event 99 0 "STRAY"))
      (is (nil? (@scans 99)) "a stray row must not create a scan we never started")
      (is (= 2 (count (get-in @scans [7 :results])))))
    (testing "end of snapshot delivers sorted by rank, then closes"
      (client/handle-event {:type :scanner-data-end :req-id 7})
      (is (= ["AAA" "BBB"] (mapv :symbol (<!! out))))
      (is (nil? (<!! out)) "chan closes after delivering")
      (is (empty? @scans) "the scan is done and forgotten"))
    (testing "a repeat end for the same scan is a no-op, not a second delivery"
      (client/handle-event {:type :scanner-data-end :req-id 7})
      (is (empty? @scans)))))

(defn- settled
  "What a scan's chan settled on - a delivered value, nil for closed, or ::waiting if
  it's still open. A failing scan is torn down from a go block, so a test has to wait on
  the chan rather than take from it and assume."
  [out]
  (let [[v port] (alts!! [out (timeout 250)] :priority true)]
    (if (= port out) v ::waiting)))

; ^ SEVERE error log messages are normal for this test - it feeds errors to handle-event
(deftest scan-error-fails-fast
  (reset! scans {})
  (let [out (chan 1)]
    (swap! scans assoc 10 {:out out :results []})
    (#'client/watch-scan! 10)
    (testing "a warning against the scan's req-id leaves it running"
      ; 2150 "Invalid position trade derived value" is the kind of notice that turns up
      ; mid-request and means nothing to a scan
      (client/handle-event {:type :error :req-id 10 :error-code 2150 :error-msg "warning"})
      (is (= ::waiting (settled out)) "a warning must not kill a working scan")
      (is (contains? @scans 10)))
    (testing "a real error closes the chan without delivering"
      (client/handle-event {:type :error :req-id 10 :error-code 162
                            :error-msg "Historical Market Data Service error"})
      (is (nil? (settled out)) "closed, not delivered - [] would claim the scan ran")
      (is (empty? @scans) "and the scan is torn down, freeing its TWS slot"))))

(deftest scan-cancel
  (reset! scans {})
  (let [out (chan 1)]
    (swap! scans assoc 8 {:out out :results []})
    (client/cancel-scan 8)
    (is (nil? (<!! out)) "an abandoned scan closes without delivering")
    (is (empty? @scans))))

(deftest empty-scan-delivers-empty
  (reset! scans {})
  (let [out (chan 1)]
    (swap! scans assoc 9 {:out out :results []})
    (client/handle-event {:type :scanner-data-end :req-id 9})
    (is (= [] (<!! out)) "a scan that matched nothing is not the same as a failed scan")))
