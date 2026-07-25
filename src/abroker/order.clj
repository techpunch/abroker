(ns abroker.order
  "Pure functional core for order lifecycle management.
   No IO, no atoms, no broker imports. All functions take data, return data.")

;; ── Order State Machine ─────────────────────────────────────────────

(def transitions
  "Valid state transitions. From OrderModel.md."
  {:pending          #{:accepted :active :rejected :filled :pending-cancel :pending-modify :unknown}
   :accepted         #{:active :canceled :rejected :filled :pending-cancel :pending-modify :unknown}
   :active           #{:partially-filled :filled :pending-cancel :pending-modify :canceled :unknown}
   :partially-filled #{:filled :pending-cancel :pending-modify :canceled :unknown}
   :pending-cancel   #{:canceled :filled :active :partially-filled :unknown}
   :pending-modify   #{:active :filled :partially-filled :canceled :unknown}
   :unknown          #{:pending :accepted :active :partially-filled :filled
                        :pending-cancel :pending-modify :canceled :rejected}})

(def terminal? #{:filled :canceled :rejected})

(defn valid-transition? [from to]
  (contains? (get transitions from) to))


;; ── Make Order ──────────────────────────────────────────────────────

(defn make-order
  "Creates an initial order map with status :pending."
  [{:keys [uuid] :as params}]
  (let [now (java.time.Instant/now)]
    (-> (select-keys params [:allocation :instrument :action :quantity :type :tif
                             :good-till :limit-price :stop-price :touch-price
                             :oca-group :eth? :overnight? :transmit? :trigger-method
                             :stop-orders :exchange :currency])
        (assoc :uuid       (or uuid (random-uuid))
               :status     :pending
               :fills      []
               :created-at now
               :updated-at now))))


;; ── Apply Command ───────────────────────────────────────────────────

(defn- transition-error [order event from to]
  {:error     :invalid-transition
   :from      from
   :to        to
   :event     event
   :order-uuid (:uuid order)})

(defn- try-transition
  "Attempts a state transition. Returns updated order or error map."
  [order event new-status]
  (let [current (:status order)]
    (if (valid-transition? current new-status)
      (assoc order :status new-status :updated-at (:timestamp event))
      (transition-error order event current new-status))))

(defmulti apply-event
  "Pure function: (order, event) -> order' or {:error ...}.
   Dispatches on event :type."
  (fn [_order event] (:type event)))

(defmethod apply-event :submit-order [order event]
  (if (= :pending (:status order))
    (assoc order :updated-at (:timestamp event))
    (transition-error order event (:status order) :pending)))

(defmethod apply-event :order-accepted [order event]
  (let [result (try-transition order event :accepted)]
    (if (:error result)
      result
      (merge result (select-keys (:payload event) [:broker-id :perm-id])))))

(defmethod apply-event :order-active [order event]
  (try-transition order event :active))

(defmethod apply-event :partial-fill [order event]
  (let [result (try-transition order event :partially-filled)]
    (if (:error result)
      result
      (update result :fills conj (get-in event [:payload :fill])))))

(defmethod apply-event :fill [order event]
  (let [result (try-transition order event :filled)]
    (if (:error result)
      result
      (-> result
          (update :fills conj (get-in event [:payload :fill]))
          (assoc :closed-at (:timestamp event))))))

(defmethod apply-event :order-rejected [order event]
  (let [result (try-transition order event :rejected)]
    (if (:error result)
      result
      (cond-> result
        (get-in event [:payload :reason])
        (assoc :reject-reason (get-in event [:payload :reason]))))))

(defmethod apply-event :order-canceled [order event]
  (try-transition order event :canceled))

(defmethod apply-event :cancel-order [order event]
  (try-transition order event :pending-cancel))

(defmethod apply-event :modify-order [order event]
  (let [result (try-transition order event :pending-modify)]
    (if (:error result)
      result
      (merge result (get-in event [:payload :changes])))))

(defmethod apply-event :status-change [order event]
  (let [new-status (get-in event [:payload :to])]
    (try-transition order event new-status)))

(defmethod apply-event :default [order event]
  {:error :unknown-event-type
   :event-type (:type event)
   :order-uuid (:uuid order)})
