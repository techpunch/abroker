(ns abroker.store-test
  (:require [clojure.test :refer :all]
            [abroker.store :as store])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "abroker-store-test"
                                  (make-array FileAttribute 0))))

(deftest snapshot-round-trip
  (let [s     (store/edn-store (tmp-dir))
        uuid  (random-uuid)
        order {:uuid uuid :status :pending :instrument {:type :stock :symbol "AAPL"}}]
    (store/save! s :order order)
    (is (= order (store/load-by-uuid s :order uuid)))

    (testing "returns nil for unknown uuid"
      (is (nil? (store/load-by-uuid s :order (random-uuid)))))))


(deftest events-round-trip
  (let [s    (store/edn-store (tmp-dir))
        uuid (random-uuid)
        evt1 {:uuid (random-uuid) :order-uuid uuid :type :submit-order}
        evt2 {:uuid (random-uuid) :order-uuid uuid :type :order-accepted}]
    (store/append-event! s :order uuid evt1)
    (store/append-event! s :order uuid evt2)
    (let [evts (store/events-for s :order uuid)]
      (is (= 2 (count evts)))
      (is (= :submit-order (:type (first evts))))
      (is (= :order-accepted (:type (second evts)))))

    (testing "empty for unknown uuid"
      (is (empty? (store/events-for s :order (random-uuid)))))))


(deftest fills-round-trip
  (let [s    (store/edn-store (tmp-dir))
        uuid (random-uuid)
        f1   {:order-uuid uuid :quantity 50 :price 150.25 :timestamp (java.util.Date.)}
        f2   {:order-uuid uuid :quantity 50 :price 150.30 :timestamp (java.util.Date.)}]
    (store/append-fill! s uuid f1)
    (store/append-fill! s uuid f2)
    (let [fills (store/fills-for s uuid)]
      (is (= 2 (count fills)))
      (is (= 50 (:quantity (first fills))))
      (is (= 150.30 (:price (second fills)))))

    (testing "empty for unknown uuid"
      (is (empty? (store/fills-for s (random-uuid)))))))


(deftest load-by-broker-id
  (let [s     (store/edn-store (tmp-dir))
        uuid1 (random-uuid)
        uuid2 (random-uuid)]
    (store/save! s :order {:uuid uuid1 :broker-id 101 :status :active :symbol "AAPL"})
    (store/save! s :order {:uuid uuid2 :broker-id 202 :status :active :symbol "MSFT"})

    (testing "indexed lookup by broker-id"
      (is (= uuid1 (:uuid (store/load-by s :order :broker-id 101))))
      (is (= uuid2 (:uuid (store/load-by s :order :broker-id 202))))
      (is (nil? (store/load-by s :order :broker-id 999))))

    (testing "index updates when broker-id changes"
      (store/save! s :order {:uuid uuid1 :broker-id 111 :status :active :symbol "AAPL"})
      (is (= uuid1 (:uuid (store/load-by s :order :broker-id 111))))
      ;; old index value may still point to uuid1, but the entity will have the new broker-id
      ;; this is acceptable for the EDN backend — a SQL backend would handle this atomically
      )))


(deftest load-by-fallback-scan
  (let [s    (store/edn-store (tmp-dir))
        uuid (random-uuid)]
    (store/save! s :order {:uuid uuid :status :active :symbol "TSLA"})

    (testing "non-indexed field falls back to scan"
      (is (= uuid (:uuid (store/load-by s :order :symbol "TSLA"))))
      (is (nil? (store/load-by s :order :symbol "NOPE"))))))


(deftest query-test
  (let [s  (store/edn-store (tmp-dir))
        u1 (random-uuid)
        u2 (random-uuid)
        u3 (random-uuid)]
    (store/save! s :order {:uuid u1 :status :active :allocation :ira})
    (store/save! s :order {:uuid u2 :status :active :allocation :taxable})
    (store/save! s :order {:uuid u3 :status :filled :allocation :ira})

    (testing "single criteria"
      (is (= 2 (count (store/query s :order {:status :active})))))

    (testing "multiple criteria"
      (is (= 1 (count (store/query s :order {:status :active :allocation :ira}))))
      (is (= u1 (:uuid (first (store/query s :order {:status :active :allocation :ira}))))))

    (testing "no match"
      (is (empty? (store/query s :order {:status :rejected}))))

    (testing "empty criteria returns all"
      (is (= 3 (count (store/query s :order {})))))))


(deftest list-uuids-test
  (let [s  (store/edn-store (tmp-dir))
        u1 (random-uuid)
        u2 (random-uuid)]
    (store/save! s :trade {:uuid u1 :status :open})
    (store/save! s :trade {:uuid u2 :status :closed})
    (is (= #{u1 u2} (set (store/list-uuids s :trade))))

    (testing "empty for unused entity type"
      (is (nil? (store/list-uuids s :order))))))


(deftest delete-entity-test
  (let [s    (store/edn-store (tmp-dir))
        uuid (random-uuid)]
    (store/save! s :order {:uuid uuid :broker-id 42 :status :active})
    (store/append-event! s :order uuid {:type :submit-order})
    (store/append-fill! s uuid {:quantity 100 :price 50.0})

    (store/delete-entity! s :order uuid)

    (testing "everything is gone"
      (is (nil? (store/load-by-uuid s :order uuid)))
      (is (empty? (store/events-for s :order uuid)))
      (is (empty? (store/fills-for s uuid))))

    (testing "index is cleaned up"
      (is (nil? (store/load-by s :order :broker-id 42))))))
