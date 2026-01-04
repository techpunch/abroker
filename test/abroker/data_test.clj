(ns abroker.data-test
  (:require [clojure.test :refer :all]
            [abroker.data :as d :refer :all]
            [abroker.testutil :as tu :refer [with-test-config]]))

(defn mk-test-order []
  (with-test-config
    (-> (order :test-group :buy 10)
        (mkt)
        (add-stop (-> (order :test-group :sell 10)
                      (lmt 100.00))))))

(deftest create
  (let [stock1 (stock :aapl)
        {:keys [action allocation stop-orders]} (mk-test-order)
        [child1] stop-orders]
    (is (= "AAPL" (:symbol stock1)))
    (is (= :buy action))
    (is (= "TestGroup" (:alloc-group allocation)))
    (is (= 1 (count stop-orders)))
    (let [{:keys [action limit-price]} child1]
      (is (= :sell action))
      (is (= 100.00 limit-price)))))

(deftest round
  (is (= 100 (round-shares {} 100)))
  (is (= 101.0 (round-shares {} 100.2)))
  (is (= 120.0 (round-shares {:min-lot-size 20} 100.2))))
