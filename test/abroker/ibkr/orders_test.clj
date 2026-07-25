(ns abroker.ibkr.orders-test
  (:require [clojure.test :refer :all]
            [abroker.ibkr.orders :as orders]
            [abroker.order :as order]
            [abroker.store :as store])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn- tmp-dir []
  (str (Files/createTempDirectory "abroker-orders-test"
                                  (make-array FileAttribute 0))))

;; ── Index pure functions ────────────────────────────────────────────

(deftest index-order-test
  (let [uuid (random-uuid)
        idx  (orders/index-order {:by-order-id {} :by-perm-id {}}
                                  uuid 42 999)]
    (is (= uuid (get-in idx [:by-order-id 42])))
    (is (= uuid (get-in idx [:by-perm-id 999]))))

  (testing "nil values are skipped"
    (let [uuid (random-uuid)
          idx  (orders/index-order {:by-order-id {} :by-perm-id {}}
                                    uuid nil 999)]
      (is (empty? (:by-order-id idx)))
      (is (= uuid (get-in idx [:by-perm-id 999]))))))


(deftest deindex-order-test
  (let [uuid (random-uuid)
        idx  (-> {:by-order-id {42 uuid} :by-perm-id {999 uuid}}
                 (orders/deindex-order 42 999))]
    (is (empty? (:by-order-id idx)))
    (is (empty? (:by-perm-id idx)))))


(deftest resolve-uuid-test
  (let [uuid (random-uuid)
        idx  {:by-order-id {42 uuid} :by-perm-id {999 uuid}}]
    (testing "resolves by order-id first"
      (is (= uuid (orders/resolve-uuid idx 42 999))))
    (testing "falls back to perm-id"
      (is (= uuid (orders/resolve-uuid idx nil 999))))
    (testing "returns nil when not found"
      (is (nil? (orders/resolve-uuid idx nil nil))))))


;; ── Init ────────────────────────────────────────────────────────────

(deftest init-order-tracking-test
  (let [s    (store/edn-store (tmp-dir))
        uuid (random-uuid)]
    ;; Save an active order
    (store/save! s :order {:uuid uuid :status :active :broker-id 42 :perm-id 999})
    ;; Save a terminal order (should not be indexed)
    (store/save! s :order {:uuid (random-uuid) :status :filled :broker-id 100 :perm-id 200})

    (orders/init-order-tracking! s)

    (testing "active order is indexed"
      (is (= uuid (orders/resolve-uuid @@#'orders/order-index 42 nil)))
      (is (= uuid (orders/resolve-uuid @@#'orders/order-index nil 999))))))
