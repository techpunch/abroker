(ns abroker.testutil
  (:require [clojure.test :refer :all]
            [abroker.data :refer :all :as data]
            [techpunch.test :refer :all]))

(def test-acct {:account "U1234567"})
(def test-group {:alloc-group "TestGroup" :min-lot-size 2})

(def broker-config
  {:allocations {:test-acct test-acct
                 :test-group test-group}
   :risk-mgmt {:max-order-amt 200000}})

(defmacro with-test-config [& body]
  `(with-redefs [data/config #(get-in broker-config %&)]
     ~@body))

(defn normalize [order]
  (-> (dissoc order :uuid :stop-orders)
      (vec)
      (->> (sort-by first))))

(defn is-order-having
  "Asserts that all entries in expected are in actual, ignores any keys in actual that
   aren't in expected."
  [expected actual]
  (let [actual (select-keys actual (keys expected))
        expected-stops (:stop-orders expected)
        actual-stops (:stop-orders actual)]
    (is (each? = (normalize expected) (normalize actual)))
    (is (= (count expected-stops) (count actual-stops)))
    (doseq [[expected-stop actual-stop] (map vector expected-stops actual-stops)]
      (is-order-having expected-stop actual-stop))))
