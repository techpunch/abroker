(ns abroker.risk-test
  (:require [clojure.test :refer :all]
            [abroker.risk :refer [check calc]]
            [abroker.testutil :refer [with-empty-config with-test-config]]))

(deftest risk-checking
  (testing "risk check fails without config param"
    (with-empty-config
      (is (thrown? RuntimeException (check {:quantity 2 :limit-price 1})))))
  (testing "risk check exercises"
    (with-test-config
      (is (check {:quantity 199999 :limit-price 1}))
      (is (thrown? RuntimeException (check {:quantity 200001 :limit-price 1})))
      (is (= 1 (calc :buy 100 99)))
      (is (= 1 (calc :sell 99 100)))
      (is (thrown? IllegalArgumentException (calc :buy 99 100)))
      (is (thrown? IllegalArgumentException (calc :sell 100 99))))))
