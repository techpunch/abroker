(ns abroker.ibkr.data-test
  (:import [com.ib.client ContractDetails])
  (:require [clojure.test :refer :all]
            [abroker.data :as data]
            [abroker.ibkr.data :as ibdata]
            [abroker.screen :as screen]))

(defn mk-raw-test-position [acct instrument qty avg-cost]
  {:account acct
   :contract (ibdata/contract instrument)
   :pos (ibdata/as-decimal qty)
   :avg-cost avg-cost})

(deftest position-fn
  (testing "stock position"
    (let [pos (mk-raw-test-position "A" (data/stock "XYZ") 1 10.0)]
      (is (= {:account "A" :symbol "XYZ" :type :stock :quantity 1M :avg-cost 10.0}
             (ibdata/position pos)))))
  (testing "option position"
    (let [pos (mk-raw-test-position "B" (data/option "ZZZ" :put) 2 100.0)]
      (is (= {:account "B" :symbol "ZZZ" :type :option :subtype :put :quantity 2M :avg-cost 100.0}
             (ibdata/position pos))))))

(deftest contract-fn
  (testing "defaults to SMART routing"
    (let [c (ibdata/contract (data/stock "nvda"))]
      (is (= "NVDA" (.symbol c)))
      (is (= "STK" (.getSecType c)))
      (is (= "SMART" (.exchange c)))
      (is (= 0 (.conid c)))))
  (testing "con-id and primary exchange from a scan row"
    (let [c (ibdata/contract {:type :stock :symbol "NVDA" :con-id 4815747
                              :primary-exchange "NASDAQ"})]
      (is (= 4815747 (.conid c)))
      (is (= "NASDAQ" (.primaryExch c)))
      (is (= "SMART" (.exchange c))))))


;; SCREENER

(deftest scan-code-str-fn
  (testing "canonical codes are mapped, not transliterated"
    (is (= "TOP_PERC_GAIN" (ibdata/scan-code-str :top-gainers)))
    (is (= "MOST_ACTIVE_USD" (ibdata/scan-code-str :most-active))))
  (testing "ibkr's own vocabulary converts mechanically"
    (is (= "TOP_PERC_GAIN" (ibdata/scan-code-str :top-perc-gain)))
    (is (= "HIGH_VS_52W_HL" (ibdata/scan-code-str :high-vs-52w-hl))))
  (testing "raw broker codes pass through untouched"
    (is (= "SCAN_not_ours" (ibdata/scan-code-str "SCAN_not_ours")))))

(deftest scanner-subscription-fn
  (testing "screen defaults"
    (let [sub (ibdata/scanner-subscription (screen/screen :top-perc-gain))]
      (is (= "TOP_PERC_GAIN" (.scanCode sub)))
      (is (= "STK" (.instrument sub)))
      (is (= "STK.US.MAJOR" (.locationCode sub)))
      (is (= screen/max-rows (.numberOfRows sub)))
      (is (= 5.0 (.abovePrice sub)))
      (testing "unset filters keep ibkr's 'no value' sentinels"
        (is (= Integer/MAX_VALUE (.aboveVolume sub)))
        (is (= Double/MAX_VALUE (.marketCapAbove sub)))
        (is (nil? (.stockTypeFilter sub))))))
  (testing "every typed filter"
    (let [sub (ibdata/scanner-subscription (-> (screen/screen :most-active)
                                               (screen/location "STK.US")
                                               (screen/stock-type :etf)
                                               (screen/rows 10)
                                               (screen/price-above 20)
                                               (screen/price-below 100)
                                               (screen/volume-above 1e6)
                                               (screen/option-volume-above 500)
                                               (screen/market-cap-above 1e9)
                                               (screen/market-cap-below 1e11)))]
      (is (= "STK.US" (.locationCode sub)))
      (is (= "ETF" (.stockTypeFilter sub)))
      (is (= 10 (.numberOfRows sub)))
      (is (= 20.0 (.abovePrice sub)))
      (is (= 100.0 (.belowPrice sub)))
      (is (= 1000000 (.aboveVolume sub)))
      (is (= 500 (.averageOptionVolumeAbove sub)))
      (is (= 1e9 (.marketCapAbove sub)))
      (is (= 1e11 (.marketCapBelow sub)))))
  (testing "an unknown code keyword fails loudly instead of scanning nothing"
    (is (thrown? IllegalArgumentException
                 (ibdata/scanner-subscription (screen/location (screen/screen :most-active)
                                                               :mars)))))
  (testing "a missing location fails loudly too"
    (is (thrown? IllegalArgumentException
                 (ibdata/scanner-subscription (-> (screen/screen :most-active)
                                                  (screen/instrument :future)))))))

(deftest scan-filters-fn
  (testing "string and keyword tags, values stringified"
    (let [[a b] (sort-by #(.-m_tag %)
                         (ibdata/scan-filters {"changePercAbove" 5 :hasOptionsIs true}))]
      (is (= ["changePercAbove" "5"] [(.-m_tag a) (.-m_value a)]))
      (is (= ["hasOptionsIs" "true"] [(.-m_tag b) (.-m_value b)]))))
  (testing "no filters"
    (is (= [] (ibdata/scan-filters nil)))))

(defn mk-raw-scan-event [rank symbol con-id long-name]
  {:rank rank
   :contract-details (doto (ContractDetails.)
                       (.contract (ibdata/contract {:type :stock :symbol symbol
                                                    :con-id con-id
                                                    :exchange "NASDAQ"
                                                    :primary-exchange "NASDAQ"}))
                       (.longName long-name))
   :distance ""
   :benchmark ""
   :projection ""})

(deftest scan-row-fn
  (testing "instrument-shaped row, empty scan metadata dropped"
    (is (= {:rank 0 :symbol "NVDA" :type :stock :con-id 4815747
            :exchange "NASDAQ" :primary-exchange "NASDAQ" :currency "USD"
            :name "NVIDIA CORP"}
           (ibdata/scan-row (mk-raw-scan-event 0 "NVDA" 4815747 "NVIDIA CORP")))))
  (testing "row round-trips into a contract"
    (let [row (ibdata/scan-row (mk-raw-scan-event 1 "AAPL" 265598 "APPLE INC"))
          c (ibdata/contract (screen/row-instrument row))]
      (is (= 265598 (.conid c)))
      (is (= "SMART" (.exchange c)))))
  (testing "absent con-id and name are omitted"
    (is (= {:rank 2 :symbol "XYZ" :type :stock :exchange "NASDAQ"
            :primary-exchange "NASDAQ" :currency "USD"}
           (ibdata/scan-row (mk-raw-scan-event 2 "XYZ" 0 nil))))))
