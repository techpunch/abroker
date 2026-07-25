(ns abroker.trading
  "High-level trading interface. Persists orders and tracks lifecycle."
  (:require [abroker.order :as order]
            [abroker.ibkr.client :as ib]
            [abroker.ibkr.orders :as ib-orders]
            [abroker.risk :as risk]
            [abroker.store :as store]))

(defn send-order!
  "Build order, validate risk, persist with :pending status, send to IBKR,
   and index for tracking. Returns the order map."
  [instrument order-params]
  (let [o       (order/make-order (assoc order-params :instrument instrument))
        s       (ib-orders/get-store)
        _       (risk/check o)
        evt     {:uuid       (random-uuid)
                 :order-uuid (:uuid o)
                 :type       :submit-order
                 :origin     :user
                 :payload    {}
                 :timestamp  (:created-at o)}
        _       (store/save! s :order o)
        _       (store/append-event! s :order (:uuid o) evt)
        ;; Send to IBKR — returns broker-assigned order-id
        oid     (ib/send-order! instrument order-params)
        ;; Update order with broker-id and re-persist
        o       (assoc o :broker-id oid)
        _       (store/save! s :order o)]
    (ib-orders/index-new-order! (:uuid o) oid)
    o))

(defn cancel-order!
  "Cancel an order by UUID."
  [order-uuid]
  (let [s     (ib-orders/get-store)
        order (store/load-by-uuid s :order order-uuid)]
    (when-not order
      (throw (ex-info "Order not found" {:uuid order-uuid})))
    (when (order/terminal? (:status order))
      (throw (ex-info "Cannot cancel terminal order" {:uuid order-uuid :status (:status order)})))
    (when (= :pending-cancel (:status order))
      (throw (ex-info "Cancel already pending"
                      {:uuid  order-uuid
                       :since (:updated-at order)
                       :cause :already-pending-cancel})))
    (let [evt {:uuid       (random-uuid)
               :order-uuid order-uuid
               :type       :cancel-order
               :origin     :user
               :payload    {}
               :timestamp  (java.time.Instant/now)}
          updated (order/apply-event order evt)]
      (if (:error updated)
        (throw (ex-info "Invalid cancel" updated))
        (do
          (store/append-event! s :order order-uuid evt)
          (store/save! s :order updated)
          (.cancelOrder (ib/client) (:broker-id order) "" false)
          updated)))))

(defn order-status
  "Load current order state by UUID."
  [order-uuid]
  (store/load-by-uuid (ib-orders/get-store) :order order-uuid))

(defn active-orders
  "Returns all non-terminal orders."
  []
  (let [all (store/query (ib-orders/get-store) :order {})]
    (remove #(order/terminal? (:status %)) all)))
