(ns abroker.ibkr.positions-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [chan >!! close!]]
            [abroker.ibkr.positions :as positions]
            [abroker.position :as pos]
            [abroker.store :as store])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "abroker-positions-test"
                                  (make-array FileAttribute 0))))

(def sample-positions
  [{:account "U1234" :symbol "AAPL" :type :stock :subtype nil
    :quantity 100M :avg-cost 150.25}
   {:account "U1234" :symbol "MSFT" :type :stock :subtype nil
    :quantity 50M :avg-cost 280.0}
   {:account "U5678" :symbol "AAPL" :type :stock :subtype nil
    :quantity 200M :avg-cost 148.50}])

(def ghost-position
  {:account "U1234" :symbol "GOOG" :type :stock :subtype nil
   :quantity 0M :avg-cost 0.0})

(defn- mock-req-positions [positions]
  (fn []
    (let [ch (chan 1)]
      (>!! ch positions)
      (close! ch)
      ch)))

(deftest init-position-tracking-test
  (let [s  (store/edn-store (tmp-dir))
        p1 {:uuid (random-uuid) :account "U1234" :symbol "AAPL" :type :stock
            :subtype nil :quantity 100M :avg-cost 150.0}
        p2 {:uuid (random-uuid) :account "U1234" :symbol "MSFT" :type :stock
            :subtype nil :quantity 50M :avg-cost 280.0}]
    (store/save! s :position p1)
    (store/save! s :position p2)
    (positions/init-position-tracking! s)
    (testing "known-positions populated from store"
      (is (= 2 (count @@#'positions/known-positions))))))

(deftest sync-positions-test
  (let [s (store/edn-store (tmp-dir))]
    (positions/init-position-tracking! s)
    (with-redefs [abroker.ibkr.client/req-positions
                  (mock-req-positions (conj sample-positions ghost-position))]
      (let [result (positions/sync-positions!)]
        (testing "returns non-ghost positions"
          (is (= 3 (count result))))
        (testing "positions persisted to store"
          (is (= 3 (count (store/query s :position {})))))
        (testing "ghost not persisted"
          (is (empty? (store/query s :position {:symbol "GOOG"}))))
        (testing "can query by account"
          (is (= 2 (count (positions/positions-for-account "U1234"))))
          (is (= 1 (count (positions/positions-for-account "U5678")))))))))

(deftest sync-positions-reconciliation-test
  (let [s (store/edn-store (tmp-dir))]
    (positions/init-position-tracking! s)
    ;; First sync: AAPL + MSFT
    (with-redefs [abroker.ibkr.client/req-positions
                  (mock-req-positions (vec (take 2 sample-positions)))]
      (positions/sync-positions!))
    (is (= 2 (count (positions/local-positions))))
    ;; Second sync: only AAPL (MSFT closed)
    (with-redefs [abroker.ibkr.client/req-positions
                  (mock-req-positions [(first sample-positions)])]
      (positions/sync-positions!))
    (testing "stale MSFT position removed"
      (is (= 1 (count (positions/local-positions))))
      (is (= "AAPL" (:symbol (first (positions/local-positions))))))))

(deftest sync-positions-events-persisted-test
  (let [s (store/edn-store (tmp-dir))]
    (positions/init-position-tracking! s)
    (with-redefs [abroker.ibkr.client/req-positions
                  (mock-req-positions [(first sample-positions)])]
      (positions/sync-positions!))
    (testing "event log exists for persisted position"
      (let [snapshot (first (store/query s :position {}))
            events   (store/events-for s :position (:uuid snapshot))]
        (is (= 1 (count events)))
        (is (= :sync (:source (first events))))
        (is (= 100M (:delta (first events))))))))

(deftest local-positions-test
  (let [s (store/edn-store (tmp-dir))]
    (positions/init-position-tracking! s)
    (testing "empty store"
      (is (empty? (positions/local-positions))))))
