(ns abroker.order-test
  (:require [clojure.test :refer :all]
            [abroker.order :as order]))

(def now (java.time.Instant/now))

(defn cmd
  ([type] (cmd type {}))
  ([type payload]
   {:uuid       (random-uuid)
    :order-uuid (random-uuid)
    :type       type
    :origin     :broker
    :payload    payload
    :timestamp  now}))

(defn make-test-order
  ([] (make-test-order :pending))
  ([status]
   {:uuid       (random-uuid)
    :allocation {:account "U1234"}
    :instrument {:type :stock :symbol "AAPL"}
    :action     :buy
    :quantity   100
    :status     status
    :fills      []
    :created-at now
    :updated-at now}))


;; ── Transition table ────────────────────────────────────────────────

(deftest valid-transition-test
  (testing "happy path transitions"
    (is (order/valid-transition? :pending :accepted))
    (is (order/valid-transition? :pending :active))
    (is (order/valid-transition? :pending :rejected))
    (is (order/valid-transition? :pending :filled))
    (is (order/valid-transition? :accepted :active))
    (is (order/valid-transition? :accepted :canceled))
    (is (order/valid-transition? :active :partially-filled))
    (is (order/valid-transition? :active :filled))
    (is (order/valid-transition? :active :pending-cancel))
    (is (order/valid-transition? :partially-filled :filled))
    (is (order/valid-transition? :pending-cancel :canceled))
    (is (order/valid-transition? :pending-cancel :filled))
    (is (order/valid-transition? :pending-modify :active)))

  (testing "invalid transitions"
    (is (not (order/valid-transition? :pending :canceled)))
    (is (not (order/valid-transition? :pending :partially-filled)))
    (is (not (order/valid-transition? :filled :active)))
    (is (not (order/valid-transition? :canceled :active)))
    (is (not (order/valid-transition? :rejected :active))))

  (testing "any non-terminal can go to unknown"
    (doseq [s [:pending :accepted :active :partially-filled :pending-cancel :pending-modify]]
      (is (order/valid-transition? s :unknown) (str s " -> :unknown"))))

  (testing "unknown can go to any state"
    (doseq [s [:pending :accepted :active :partially-filled :filled
                :pending-cancel :pending-modify :canceled :rejected]]
      (is (order/valid-transition? :unknown s) (str ":unknown -> " s)))))


(deftest terminal-test
  (is (order/terminal? :filled))
  (is (order/terminal? :canceled))
  (is (order/terminal? :rejected))
  (is (not (order/terminal? :active)))
  (is (not (order/terminal? :pending))))


;; ── make-order ──────────────────────────────────────────────────────

(deftest make-order-test
  (let [o (order/make-order {:allocation {:account "U1234"}
                             :instrument {:type :stock :symbol "AAPL"}
                             :action     :buy
                             :quantity   100
                             :type       :lmt
                             :limit-price 150.0})]
    (is (uuid? (:uuid o)))
    (is (= :pending (:status o)))
    (is (= :lmt (:type o)))
    (is (= 150.0 (:limit-price o)))
    (is (= [] (:fills o)))
    (is (inst? (:created-at o)))))


;; ── apply-event ─────────────────────────────────────────────────────

(deftest submit-order-test
  (let [o (make-test-order :pending)
        result (order/apply-event o (cmd :submit-order))]
    (is (= :pending (:status result)))
    (is (not (:error result))))

  (testing "submit on non-pending is error"
    (let [o (make-test-order :active)
          result (order/apply-event o (cmd :submit-order))]
      (is (= :invalid-transition (:error result))))))


(deftest order-accepted-test
  (let [o (make-test-order :pending)
        result (order/apply-event o (cmd :order-accepted {:broker-id 42 :perm-id 999}))]
    (is (= :accepted (:status result)))
    (is (= 42 (:broker-id result)))
    (is (= 999 (:perm-id result))))

  (testing "accepted from active is invalid"
    (let [o (make-test-order :active)
          result (order/apply-event o (cmd :order-accepted {:broker-id 42}))]
      (is (= :invalid-transition (:error result))))))


(deftest order-active-test
  (let [o (make-test-order :accepted)
        result (order/apply-event o (cmd :order-active))]
    (is (= :active (:status result)))))


(deftest partial-fill-test
  (let [fill {:order-uuid (random-uuid) :quantity 50 :price 150.0
              :broker-exec-id "exec1" :timestamp now}
        o (make-test-order :active)
        result (order/apply-event o (cmd :partial-fill {:fill fill}))]
    (is (= :partially-filled (:status result)))
    (is (= 1 (count (:fills result))))
    (is (= 50 (:quantity (first (:fills result)))))))


(deftest fill-test
  (let [fill {:order-uuid (random-uuid) :quantity 100 :price 150.0
              :broker-exec-id "exec1" :timestamp now}
        o (make-test-order :active)
        result (order/apply-event o (cmd :fill {:fill fill}))]
    (is (= :filled (:status result)))
    (is (= 1 (count (:fills result))))
    (is (some? (:closed-at result)))))


(deftest order-rejected-test
  (let [o (make-test-order :pending)
        result (order/apply-event o (cmd :order-rejected {:reason "Insufficient margin"}))]
    (is (= :rejected (:status result)))
    (is (= "Insufficient margin" (:reject-reason result)))))


(deftest order-canceled-test
  (let [o (make-test-order :pending-cancel)
        result (order/apply-event o (cmd :order-canceled))]
    (is (= :canceled (:status result))))

  (testing "broker can cancel during pending-modify"
    (let [o (make-test-order :pending-modify)
          result (order/apply-event o (cmd :order-canceled))]
      (is (= :canceled (:status result))))))


(deftest cancel-order-test
  (let [o (make-test-order :active)
        result (order/apply-event o (cmd :cancel-order))]
    (is (= :pending-cancel (:status result))))

  (testing "can't cancel filled order"
    (let [o (make-test-order :filled)
          result (order/apply-event o (cmd :cancel-order))]
      (is (= :invalid-transition (:error result)))))

  (testing "can't cancel an already-pending-cancel order"
    (let [o (make-test-order :pending-cancel)
          result (order/apply-event o (cmd :cancel-order))]
      (is (= :invalid-transition (:error result))))))


(deftest modify-order-test
  (let [o (assoc (make-test-order :active) :limit-price 100.0)
        result (order/apply-event o (cmd :modify-order {:changes {:limit-price 105.0}}))]
    (is (= :pending-modify (:status result)))
    (is (= 105.0 (:limit-price result))))

  (testing "can modify a partially-filled order"
    (let [o (assoc (make-test-order :partially-filled) :limit-price 100.0)
          result (order/apply-event o (cmd :modify-order {:changes {:limit-price 102.0}}))]
      (is (= :pending-modify (:status result)))
      (is (= 102.0 (:limit-price result))))))


(deftest status-change-test
  (let [o (make-test-order :pending)
        result (order/apply-event o (cmd :status-change {:from :pending :to :unknown}))]
    (is (= :unknown (:status result))))

  (testing "invalid status-change returns error"
    (let [o (make-test-order :filled)
          result (order/apply-event o (cmd :status-change {:from :filled :to :active}))]
      (is (= :invalid-transition (:error result))))))


(deftest unknown-event-type-test
  (let [o (make-test-order :pending)
        result (order/apply-event o (cmd :bogus-event))]
    (is (= :unknown-event-type (:error result)))))


;; ── Full lifecycle paths ────────────────────────────────────────────

(deftest happy-path-market-order
  (let [fill {:order-uuid (random-uuid) :quantity 100 :price 150.25
              :broker-exec-id "exec1" :timestamp now}
        o (make-test-order :pending)
        o (order/apply-event o (cmd :order-accepted {:broker-id 42}))
        o (order/apply-event o (cmd :order-active))
        o (order/apply-event o (cmd :fill {:fill fill}))]
    (is (= :filled (:status o)))
    (is (= 42 (:broker-id o)))
    (is (= 1 (count (:fills o))))
    (is (order/terminal? (:status o)))))


(deftest partial-fill-then-cancel
  (let [fill1 {:order-uuid (random-uuid) :quantity 50 :price 150.0
               :broker-exec-id "exec1" :timestamp now}
        o (make-test-order :pending)
        o (order/apply-event o (cmd :order-accepted {:broker-id 42}))
        o (order/apply-event o (cmd :order-active))
        o (order/apply-event o (cmd :partial-fill {:fill fill1}))
        _ (is (= :partially-filled (:status o)))
        o (order/apply-event o (cmd :cancel-order))
        _ (is (= :pending-cancel (:status o)))
        o (order/apply-event o (cmd :order-canceled))]
    (is (= :canceled (:status o)))
    (is (= 1 (count (:fills o))))))


(deftest fill-during-pending-cancel
  (let [fill {:order-uuid (random-uuid) :quantity 100 :price 150.0
              :broker-exec-id "exec1" :timestamp now}
        o (make-test-order :pending)
        o (order/apply-event o (cmd :order-accepted {:broker-id 42}))
        o (order/apply-event o (cmd :order-active))
        o (order/apply-event o (cmd :cancel-order))
        _ (is (= :pending-cancel (:status o)))
        o (order/apply-event o (cmd :fill {:fill fill}))]
    (is (= :filled (:status o)))
    (is (= 1 (count (:fills o))))))


(deftest unknown-recovery
  (let [o (make-test-order :pending)
        o (order/apply-event o (cmd :status-change {:to :unknown}))
        _ (is (= :unknown (:status o)))
        o (order/apply-event o (cmd :order-active))]
    (is (= :active (:status o)))))
