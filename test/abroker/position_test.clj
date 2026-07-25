(ns abroker.position-test
  (:require [clojure.test :refer :all]
            [abroker.position :as pos]))

(deftest position-key-test
  (is (= ["U1234" "AAPL" :stock nil]
         (pos/position-key {:account "U1234" :symbol "AAPL" :type :stock :subtype nil})))
  (testing "options include subtype"
    (is (= ["U1234" "SPY" :option :call]
           (pos/position-key {:account "U1234" :symbol "SPY" :type :option :subtype :call})))))

(deftest ghost-test
  (testing "zero quantity"
    (is (pos/ghost? {:quantity 0 :avg-cost 150.0}))
    (is (pos/ghost? {:quantity 0M :avg-cost 150.0})))
  (testing "non-positive avg-cost"
    (is (pos/ghost? {:quantity 100 :avg-cost 0}))
    (is (pos/ghost? {:quantity 100 :avg-cost 0.0}))
    (is (pos/ghost? {:quantity 100 :avg-cost -1.0})))
  (testing "valid positions are not ghosts"
    (is (not (pos/ghost? {:quantity 100 :avg-cost 150.0})))
    (is (not (pos/ghost? {:quantity -50 :avg-cost 25.0})))))

(deftest make-event-test
  (let [event (pos/make-event {:account "U1234" :symbol "AAPL" :type :stock
                               :subtype nil :quantity 100M :avg-cost 150.25}
                              {:source :sync :prev-quantity 75M})]
    (testing "structure"
      (is (uuid? (:uuid event)))
      (is (string? (:observed-at event))))
    (testing "position data"
      (is (= "U1234" (:account event)))
      (is (= "AAPL" (:symbol event)))
      (is (= :stock (:type event)))
      (is (= 100M (:quantity event)))
      (is (= 150.25 (:avg-cost event))))
    (testing "delta from prev-quantity"
      (is (= 25M (:delta event))))
    (testing "source"
      (is (= :sync (:source event))))
    (testing "no order-uuid when not provided"
      (is (nil? (:order-uuid event)))))

  (testing "fill-sourced event includes order-uuid"
    (let [oid   (random-uuid)
          event (pos/make-event {:account "U1234" :symbol "AAPL" :type :stock
                                 :subtype nil :quantity 100M :avg-cost 150.0}
                                {:source :fill :order-uuid oid :prev-quantity 0M})]
      (is (= :fill (:source event)))
      (is (= oid (:order-uuid event)))
      (is (= 100M (:delta event)))))

  (testing "prev-quantity defaults to 0"
    (let [event (pos/make-event {:account "U1234" :symbol "AAPL" :type :stock
                                 :subtype nil :quantity 50M :avg-cost 100.0}
                                {:source :sync})]
      (is (= 50M (:delta event))))))

(deftest diff-snapshots-test
  (testing "new position"
    (let [events (pos/diff-snapshots
                  {}
                  [{:account "U1234" :symbol "AAPL" :type :stock :subtype nil
                    :quantity 100M :avg-cost 150.0}])]
      (is (= 1 (count events)))
      (is (= 100M (:quantity (first events))))
      (is (= 100M (:delta (first events))))))

  (testing "changed quantity"
    (let [known  {["U1234" "AAPL" :stock nil]
                  {:account "U1234" :symbol "AAPL" :type :stock :subtype nil
                   :quantity 100M :avg-cost 150.0}}
          events (pos/diff-snapshots
                  known
                  [{:account "U1234" :symbol "AAPL" :type :stock :subtype nil
                    :quantity 75M :avg-cost 150.0}])]
      (is (= 1 (count events)))
      (is (= 75M (:quantity (first events))))
      (is (= -25M (:delta (first events))))))

  (testing "disappeared position"
    (let [known  {["U1234" "AAPL" :stock nil]
                  {:account "U1234" :symbol "AAPL" :type :stock :subtype nil
                   :quantity 100M :avg-cost 150.0}}
          events (pos/diff-snapshots known [])]
      (is (= 1 (count events)))
      (is (= 0M (:quantity (first events))))
      (is (= -100M (:delta (first events))))))

  (testing "no change produces no events"
    (let [known {["U1234" "AAPL" :stock nil]
                 {:account "U1234" :symbol "AAPL" :type :stock :subtype nil
                  :quantity 100M :avg-cost 150.0}}]
      (is (empty? (pos/diff-snapshots
                   known
                   [{:account "U1234" :symbol "AAPL" :type :stock :subtype nil
                     :quantity 100M :avg-cost 150.0}])))))

  (testing "avg-cost change without quantity change"
    (let [known  {["U1234" "AAPL" :stock nil]
                  {:account "U1234" :symbol "AAPL" :type :stock :subtype nil
                   :quantity 100M :avg-cost 150.0}}
          events (pos/diff-snapshots
                  known
                  [{:account "U1234" :symbol "AAPL" :type :stock :subtype nil
                    :quantity 100M :avg-cost 155.0}])]
      (is (= 1 (count events)))
      (is (= 0M (:delta (first events))))
      (is (= 155.0 (:avg-cost (first events))))))

  (testing "multiple positions mixed changes"
    (let [known  {["U1234" "AAPL" :stock nil]
                  {:account "U1234" :symbol "AAPL" :type :stock :subtype nil
                   :quantity 100M :avg-cost 150.0}
                  ["U1234" "MSFT" :stock nil]
                  {:account "U1234" :symbol "MSFT" :type :stock :subtype nil
                   :quantity 50M :avg-cost 280.0}}
          events (pos/diff-snapshots
                  known
                  [{:account "U1234" :symbol "AAPL" :type :stock :subtype nil
                    :quantity 100M :avg-cost 150.0}   ; unchanged
                   {:account "U1234" :symbol "GOOG" :type :stock :subtype nil
                    :quantity 25M :avg-cost 170.0}])] ; new; MSFT disappeared
      (is (= 2 (count events)))
      (let [by-sym (into {} (map (juxt :symbol identity)) events)]
        (is (= 0M (:quantity (by-sym "MSFT"))))
        (is (= 25M (:quantity (by-sym "GOOG"))))))))
