(ns abroker.ibkr.orders
  "Imperative shell for order tracking. Wires IBKR events to the pure
   order FSM via store persistence. The only mutable state is the
   orderId/permId -> UUID index atom."
  (:require [clojure.tools.logging :as log]
            [abroker.order :as order]
            [abroker.ibkr.client :as ib]
            [abroker.ibkr.data :as ibdata]
            [abroker.store :as store]))


;; ── Index: orderId/permId -> UUID ───────────────────────────────────

(defonce ^:private order-index (atom {:by-order-id {} :by-perm-id {}}))

(defn index-order
  "Pure fn — adds mappings for an order. For use with swap!"
  [idx uuid order-id perm-id]
  (cond-> idx
    order-id (assoc-in [:by-order-id order-id] uuid)
    perm-id  (assoc-in [:by-perm-id perm-id] uuid)))

(defn deindex-order
  "Pure fn — removes mappings for an order. For use with swap!"
  [idx order-id perm-id]
  (cond-> idx
    order-id (update :by-order-id dissoc order-id)
    perm-id  (update :by-perm-id dissoc perm-id)))

(defn resolve-uuid
  "Resolves an order UUID from orderId or permId. Tries order-id first."
  [idx order-id perm-id]
  (or (get-in idx [:by-order-id order-id])
      (get-in idx [:by-perm-id perm-id])))


;; ── Store reference ─────────────────────────────────────────────────

(defonce ^:private store-ref (atom nil))


;; ── Helpers ─────────────────────────────────────────────────────────

(defn- make-event [type origin order-uuid payload]
  {:uuid       (random-uuid)
   :order-uuid order-uuid
   :type       type
   :origin     origin
   :payload    payload
   :timestamp  (java.time.Instant/now)})

(defn- apply-and-persist!
  "Applies an event to an order, persists results. On invalid transition,
   logs a warning and transitions to :unknown instead."
  [s order event]
  (let [result (order/apply-event order event)]
    (if (:error result)
      (do
        (log/warn "Invalid transition" (:error result)
                  "from" (:from result) "to" (:to result)
                  "for order" (:order-uuid result)
                  "- transitioning to :unknown")
        (let [fallback-evt (make-event :status-change :broker (:uuid order)
                                       {:from (:status order) :to :unknown})
              unknown-order (order/apply-event order fallback-evt)]
          (store/append-event! s :order (:uuid order) event)
          (store/append-event! s :order (:uuid order) fallback-evt)
          (store/save! s :order unknown-order)
          unknown-order))
      (do
        (store/append-event! s :order (:uuid order) event)
        (store/save! s :order result)
        result))))

(defn- maybe-deindex! [order]
  (when (order/terminal? (:status order))
    (swap! order-index deindex-order (:broker-id order) (:perm-id order))))


;; ── Event Handlers ──────────────────────────────────────────────────

(defmethod ib/handle-event :order-status
  [{:keys [order-id status filled remaining avg-fill-price
           perm-id parent-id last-fill-price client-id why-held mkt-cap-price]}]
  (when-let [s @store-ref]
    (let [canonical (ibdata/ibkr-status status)
          uuid      (resolve-uuid @order-index order-id perm-id)]
      (when uuid
        (when-let [order (store/load-by-uuid s :order uuid)]
          ;; Skip if status unchanged (IBKR sends duplicates)
          (when (not= canonical (:status order))
            (let [cmd-type (case canonical
                             :accepted      :order-accepted
                             :active        :order-active
                             :filled        :fill
                             :rejected      :order-rejected
                             :canceled      :order-canceled
                             :pending-cancel :cancel-order
                             :pending       :submit-order
                             :status-change)
                  payload  (cond-> {}
                             (= cmd-type :order-accepted)
                             (assoc :broker-id order-id :perm-id perm-id)

                             (= cmd-type :order-rejected)
                             (assoc :reason why-held)

                             (= cmd-type :status-change)
                             (assoc :from (:status order) :to canonical))
                  ;; For :fill from order-status, we don't have fill details
                  ;; exec-details is the authoritative fill source
                  ;; So skip fill commands from order-status; just track the status
                  cmd-type (if (and (= cmd-type :fill)
                                    ;; Only use order-status for fill if no fills
                                    ;; have been recorded yet via exec-details
                                    (seq (:fills order)))
                             :status-change
                             cmd-type)
                  payload  (if (= cmd-type :status-change)
                             {:from (:status order) :to canonical}
                             payload)
                  event    (make-event cmd-type :broker uuid payload)]
              (let [updated (apply-and-persist! s order event)]
                (maybe-deindex! updated)
                ;; Register perm-id mapping if newly seen
                (when (and perm-id (not (get-in @order-index [:by-perm-id perm-id])))
                  (swap! order-index assoc-in [:by-perm-id perm-id] uuid))))))))))


(defmethod ib/handle-event :exec-details
  [{:keys [req-id contract execution]}]
  (when-let [s @store-ref]
    (let [exec-id   (.execId execution)
          order-id  (.orderId execution)
          perm-id   (.permId execution)
          uuid      (resolve-uuid @order-index order-id perm-id)]
      (when uuid
        (when-let [order (store/load-by-uuid s :order uuid)]
          ;; Dedup: check if we already have this fill
          (let [existing-fills (store/fills-for s uuid)]
            (when-not (some #(= exec-id (:broker-exec-id %)) existing-fills)
              (let [fill       (ibdata/execution->fill execution uuid)
                    cum-qty    (+ (ibdata/as-long (.cumQty execution))  0)
                    total-qty  (:quantity order)
                    complete?  (>= cum-qty total-qty)
                    cmd-type   (if complete? :fill :partial-fill)
                    event      (make-event cmd-type :broker uuid {:fill fill})
                    updated    (apply-and-persist! s order event)]
                (store/append-fill! s uuid fill)
                (maybe-deindex! updated)))))))))


(defmethod ib/handle-event :open-order
  [{:keys [order-id contract order order-state]}]
  ;; Reconnect recovery: re-establish index mappings from open orders
  (when-let [uuid-str (.orderRef order)]
    (when-let [uuid (parse-uuid uuid-str)]
      (let [perm-id (.permId order)]
        (swap! order-index index-order uuid order-id perm-id)
        (log/debug "Re-indexed open order" uuid "order-id" order-id "perm-id" perm-id)))))


(defmethod ib/handle-event :order-bound
  [{:keys [perm-id client-id order-id]}]
  ;; Maps permId -> orderId
  (when-let [uuid (get-in @order-index [:by-perm-id perm-id])]
    (swap! order-index assoc-in [:by-order-id order-id] uuid)))


;; ── Init ────────────────────────────────────────────────────────────

(defn init-order-tracking!
  "Initialize order tracking. Call once at startup with a store instance.
   Loads non-terminal orders and populates the index."
  [s]
  (reset! store-ref s)
  (let [all-orders  (store/query s :order {})
        active      (remove #(order/terminal? (:status %)) all-orders)]
    (reset! order-index {:by-order-id {} :by-perm-id {}})
    (doseq [order active]
      (swap! order-index index-order
             (:uuid order)
             (:broker-id order)
             (:perm-id order)))
    (log/info "Order tracking initialized with" (count active) "active orders")))


;; ── Public API for trading.clj ──────────────────────────────────────

(defn index-new-order!
  "Index a newly submitted order by its broker-assigned order-id."
  [uuid order-id]
  (swap! order-index index-order uuid order-id nil))

(defn get-store []
  @store-ref)
