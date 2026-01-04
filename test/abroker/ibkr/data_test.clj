(ns abroker.ibkr.data-test
  (:require [clojure.test :refer :all]
            [abroker.data :as data]
            [abroker.ibkr.data :as ibdata]))

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
