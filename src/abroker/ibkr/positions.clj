(ns abroker.ibkr.positions
  "Imperative shell for position tracking. Wires IBKR position data to the
   pure position core via store persistence."
  (:require [clojure.core.async :refer [<!!]]
            [clojure.tools.logging :as log]
            [abroker.position :as pos]
            [abroker.ibkr.client :as ib]
            [abroker.store :as store]))

(defonce ^:private store-ref (atom nil))
(defonce ^:private known-positions (atom {})) ; {position-key -> position-snapshot}

(defn- apply-event!
  "Persists a position event and updates the snapshot. Returns updated known map."
  [s event known]
  (let [k    (pos/position-key event)
        prev (get known k)
        uuid (or (:uuid prev) (random-uuid))]
    (store/append-event! s :position uuid event)
    (if (zero? (:quantity event))
      (do (store/delete-entity! s :position uuid)
          (dissoc known k))
      (let [instrument (-> (select-keys event [:type :symbol :subtype])
                          (cond-> (nil? (:subtype event)) (dissoc :subtype)))
            snapshot   (-> (select-keys event [:account :symbol :type :subtype :quantity :avg-cost])
                           (assoc :uuid uuid
                                  :instrument instrument
                                  :updated-at (:observed-at event)))]
        (store/save! s :position snapshot)
        (assoc known k snapshot)))))

(defn init-position-tracking!
  "Initialize position tracking. Call once at startup with a store instance."
  [s]
  (reset! store-ref s)
  (let [existing (store/query s :position {})
        by-key   (into {} (map (juxt pos/position-key identity)) existing)]
    (reset! known-positions by-key)
    (log/info "Position tracking initialized with" (count by-key) "known positions")))

(defn sync-positions!
  "Fetches all positions from IBKR, diffs against local state, persists events
   and updated snapshots. Returns the list of current positions."
  []
  (let [s  @store-ref
        _  (assert s "Position tracking not initialized")
        ch (ib/req-positions)
        raw (<!! ch)]
    (if (nil? raw)
      (log/warn "sync-positions! timed out or received nil")
      (let [live   (remove pos/ghost? raw)
            events (pos/diff-snapshots @known-positions live)
            known  (reduce (fn [acc event] (apply-event! s event acc))
                           @known-positions
                           events)]
        (reset! known-positions known)
        (log/info "Synced positions:" (count events) "changes")
        (vals known)))))

(defn local-positions
  "Returns all positions from the local store (no broker call)."
  []
  (store/query @store-ref :position {}))

(defn positions-for-account
  "Returns positions for a specific account from the local store."
  [account]
  (store/query @store-ref :position {:account account}))
