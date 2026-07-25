(ns abroker.ibkr.data-test
  (:require [clojure.test :refer :all]
            [abroker.data :as data]
            [abroker.ibkr.data :as ibdata]))

(defn mk-raw-test-position [acct instrument qty avg-cost]
  {:account acct
   :contract (ibdata/contract instrument)
   :pos (ibdata/as-decimal qty)
   :avg-cost avg-cost})

(deftest scanner-code-fn
  (is (= "TOP_PERC_GAIN" (ibdata/scanner-code :top-perc-gain)))
  (is (= "STK.US.MAJOR" (ibdata/scanner-code :stk.us.major)))
  (is (= "STK" (ibdata/scanner-code "stk"))))

(deftest tag-values-fn
  (let [[tv :as tvs] (ibdata/tag-values {:changePercAbove 3})]
    (is (= 1 (count tvs)))
    (is (= "changePercAbove" (.m_tag tv)))
    (is (= "3" (.m_value tv))))
  (is (empty? (ibdata/tag-values nil))))

(deftest scanner-subscription-fn
  (let [sub (ibdata/scanner-subscription {:instrument :stk
                                          :location :stk.us.major
                                          :scan-code :top-perc-gain
                                          :num-rows 10
                                          :above-price 5.0
                                          :above-volume 500000
                                          :market-cap-above 500e6})]
    (is (= "STK" (.instrument sub)))
    (is (= "STK.US.MAJOR" (.locationCode sub)))
    (is (= "TOP_PERC_GAIN" (.scanCode sub)))
    (is (= 10 (.numberOfRows sub)))
    (is (= 5.0 (.abovePrice sub)))
    (is (= 500000 (.aboveVolume sub)))
    (is (= 500e6 (.marketCapAbove sub)))))

(deftest scan-result-fn
  (let [cd (doto (com.ib.client.ContractDetails.)
             (.contract (ibdata/contract (data/stock "NVDA"))))]
    (testing "blank legacy fields are omitted"
      (is (= {:rank 0 :symbol "NVDA" :type :stock :exchange "SMART"
              :currency "USD" :con-id 0}
             (ibdata/scan-result {:req-id 1 :rank 0 :contract-details cd
                                  :distance "" :benchmark "" :projection ""}))))
    (testing "non-blank legacy fields are included"
      (is (= "-3.2% below high"
             (:distance (ibdata/scan-result {:req-id 1 :rank 4 :contract-details cd
                                             :distance "-3.2% below high"})))))))

(deftest position-fn
  (testing "stock position"
    (let [pos (mk-raw-test-position "A" (data/stock "XYZ") 1 10.0)]
      (is (= {:account "A" :symbol "XYZ" :type :stock :quantity 1M :avg-cost 10.0}
             (ibdata/position pos)))))
  (testing "option position"
    (let [pos (mk-raw-test-position "B" (data/option "ZZZ" :put) 2 100.0)]
      (is (= {:account "B" :symbol "ZZZ" :type :option :subtype :put :quantity 2M :avg-cost 100.0}
             (ibdata/position pos))))))
