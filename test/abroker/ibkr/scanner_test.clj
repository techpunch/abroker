(ns abroker.ibkr.scanner-test
  (:require [clojure.test :refer :all]
            [abroker.ibkr.data :as ibdata])
  (:import [com.ib.client Contract ContractDetails TagValue]))

(deftest scanner-subscription-defaults
  (testing "defaults scan the top gainers among major US stocks"
    (let [s (ibdata/scanner-subscription {})]
      (is (= "STK" (.instrument s)))
      (is (= "STK.US.MAJOR" (.locationCode s)))
      (is (= "TOP_PERC_GAIN" (.scanCode s)))
      (is (= 25 (.numberOfRows s)))
      (is (= "ALL" (.stockTypeFilter s))))))

(deftest scanner-subscription-overrides
  (testing "friendly keywords resolve and numeric filters apply"
    (let [s (ibdata/scanner-subscription {:scan-code :most-active
                                          :location :us-minor
                                          :rows 10
                                          :above-price 10
                                          :market-cap-above 1e9})]
      (is (= "MOST_ACTIVE" (.scanCode s)))
      (is (= "STK.US.MINOR" (.locationCode s)))
      (is (= 10 (.numberOfRows s)))
      (is (= 10.0 (.abovePrice s)))
      (is (= 1e9 (.marketCapAbove s)))))
  (testing "raw code and location strings pass through unchanged"
    (let [s (ibdata/scanner-subscription {:scan-code "HALTED"
                                          :location "STK.HK.SEHK"})]
      (is (= "HALTED" (.scanCode s)))
      (is (= "STK.HK.SEHK" (.locationCode s)))))
  (testing "a typo'd keyword fails fast with a clear message, not a ClassCastException"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown scanner scan-code"
                          (ibdata/scanner-subscription {:scan-code :top-gainerz})))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Unknown scanner location"
                          (ibdata/scanner-subscription {:location :us-mjaor})))))

(deftest scan-filters-fn
  (testing "nil/empty filters produce nil"
    (is (nil? (ibdata/scan-filters nil)))
    (is (nil? (ibdata/scan-filters {}))))
  (testing "filters become stringified TagValues"
    (let [[^TagValue a ^TagValue b] (ibdata/scan-filters {"changePercAbove" 5 :priceAbove 10})]
      (is (= "changePercAbove" (.-m_tag a)))
      (is (= "5" (.-m_value a)))
      (is (= "priceAbove" (.-m_tag b)))
      (is (= "10" (.-m_value b))))))

(defn mk-contract-details [symbol sec-type long-name industry]
  (let [c (doto (Contract.)
            (.symbol symbol)
            (.secType ^String sec-type)
            (.currency "USD")
            (.primaryExch "NASDAQ")
            (.conid 42))]
    (doto (ContractDetails.)
      (.contract c)
      (.longName long-name)
      (.industry industry))))

(deftest scan-result-fn
  (testing "converts a scanner-data event into a usable instrument+metadata map"
    (let [event {:rank 0
                 :contract-details (mk-contract-details "NVDA" "STK" "NVIDIA CORP" "Technology")}]
      (is (= {:rank 0
              :symbol "NVDA"
              :con-id 42
              :type :stock
              :currency "USD"
              :exchange "NASDAQ"
              :name "NVIDIA CORP"
              :industry "Technology"
              :category nil}
             (ibdata/scan-result event)))))
  (testing "empty string fields normalize to nil"
    (let [event {:rank 3
                 :contract-details (mk-contract-details "FOO" "STK" "" "")}]
      (is (nil? (:name (ibdata/scan-result event))))
      (is (nil? (:industry (ibdata/scan-result event)))))))
