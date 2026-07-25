(ns abroker.position
  "Pure functional core for position tracking.
   No IO, no atoms, no broker imports."
  (:require [clojure.set :as set]))

(defn position-key
  "Returns the identity tuple for a position: [account symbol type subtype]."
  [{:keys [account symbol type subtype]}]
  [account symbol type subtype])

(defn ghost?
  "True if this position is a ghost — zero quantity or non-positive avg-cost.
   IBKR reports these for FA group allocations on recently-closed positions."
  [{:keys [quantity avg-cost]}]
  (or (zero? quantity)
      (<= avg-cost 0)))

(defn make-event
  "Creates a position event from a flat position map and options.
   opts: {:source :sync|:fill, :order-uuid uuid?, :prev-quantity bigdec?}"
  [pos {:keys [source order-uuid prev-quantity]}]
  (-> (select-keys pos [:account :symbol :type :subtype :quantity :avg-cost])
      (assoc :uuid        (random-uuid)
             :delta       (- (:quantity pos) (or prev-quantity 0M))
             :source      source
             :observed-at (str (java.time.Instant/now)))
      (cond-> order-uuid (assoc :order-uuid order-uuid))))

(defn diff-snapshots
  "Compares known positions against a broker snapshot, returns position events
   for all changes. known is {position-key -> position-map}. broker-positions
   is a seq of flat position maps (ghosts already filtered)."
  [known broker-positions]
  (let [broker-by-key (into {} (map (juxt position-key identity)) broker-positions)
        all-keys      (set/union (set (keys known)) (set (keys broker-by-key)))]
    (->> all-keys
         (keep
          (fn [k]
            (let [old-pos  (get known k)
                  new-pos  (get broker-by-key k)
                  old-qty  (or (:quantity old-pos) 0M)
                  new-qty  (or (:quantity new-pos) 0M)
                  old-cost (or (:avg-cost old-pos) 0.0)
                  new-cost (or (:avg-cost new-pos) 0.0)]
              (when (or (not (== old-qty new-qty))
                        (not (== old-cost new-cost)))
                (make-event
                 (or new-pos
                     {:account (k 0) :symbol (k 1) :type (k 2) :subtype (k 3)
                      :quantity 0M :avg-cost 0.0})
                 {:source :sync :prev-quantity old-qty})))))
         vec)))
