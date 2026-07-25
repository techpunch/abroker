(ns abroker.store
  "Persistence layer — reads, writes, and queries for orders, trades,
   fills, and events. Backend is swappable via the Store protocol."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [techpunch.io :as tio])
  (:import (java.nio.file Files OpenOption StandardOpenOption)))


;; ─── Protocol ───────────────────────────────────────────────────────

(defprotocol Store
  ;; Events (append-only log)
  (append-event! [store entity-type entity-uuid event]
    "Append an event to an entity's log.")
  (events-for [store entity-type entity-uuid]
    "Returns ordered seq of all events for an entity.")

  ;; Entity snapshots (orders, trades)
  (save! [store entity-type entity]
    "Persist an entity. Must have :uuid.")
  (load-by-uuid [store entity-type uuid]
    "Load an entity by uuid. Returns nil if not found.")
  (load-by [store entity-type field value]
    "Load the first entity where field = value.
     e.g. (load-by store :order :broker-id 42)")
  (query [store entity-type criteria]
    "Find all entities matching criteria map.
     e.g. (query store :order {:status :active, :allocation :ira})")
  (list-uuids [store entity-type]
    "Returns seq of all entity uuids of the given type.")

  ;; Fills
  (append-fill! [store order-uuid fill]
    "Append a fill to an order's fill log.")
  (fills-for [store order-uuid]
    "Returns ordered seq of all fills for an order.")

  ;; Cleanup
  (delete-entity! [store entity-type uuid]
    "Remove all data for an entity (commands + snapshot + fills)."))


;; ─── EDN File Backend ───────────────────────────────────────────────
;;
;; Layout:
;;   <base-path>/
;;     orders/
;;       <uuid>/
;;         snapshot.edn    <- latest materialized state
;;         events.edn      <- append-only, one event per line
;;         fills.edn       <- append-only, one fill per line
;;     trades/
;;       <uuid>/
;;         snapshot.edn
;;         events.edn
;;
;; Indexes (for load-by and query):
;;   <base-path>/
;;     index/
;;       orders/
;;         broker-id/
;;           <value>.edn   <- contains the entity uuid

(def ^:private ^"[Ljava.nio.file.OpenOption;" append-opts
  (into-array OpenOption [StandardOpenOption/CREATE
                          StandardOpenOption/WRITE
                          StandardOpenOption/APPEND]))

(defn- entity-dir [base-path entity-type uuid]
  (tio/path (str base-path) (name entity-type) (str uuid)))

(defn- entity-file [base-path entity-type uuid filename]
  (.resolve (entity-dir base-path entity-type uuid) filename))

(defn- index-path [base-path entity-type field value]
  (tio/path (str base-path) "index" (name entity-type) (name field) (str value ".edn")))

(defn- append-edn!
  "Append a single EDN form as one line to a file."
  [path form]
  (let [path  (tio/as-path path)
        line  (str (pr-str form) "\n")
        bytes (.getBytes line "UTF-8")]
    (tio/create-dirs (.getParent path))
    (Files/write path bytes append-opts)))

(defn- write-edn!
  "Atomically write an EDN form to a file (write to .tmp, then move)."
  [path form]
  (let [path (tio/as-path path)
        tmp  (.resolveSibling path (str (.getFileName path) ".tmp"))
        bytes (.getBytes (pr-str form) "UTF-8")]
    (tio/create-dirs (.getParent path))
    (tio/write-path tmp bytes)
    (tio/move tmp path)))

(defn- read-edn-lines
  "Read a file containing one EDN form per line. Returns a vector."
  [path]
  (let [path (tio/as-path path)]
    (if (tio/exists? path)
      (with-open [rdr (io/reader (.toFile path))]
        (into []
              (comp (map str/trim)
                    (remove empty?)
                    (map edn/read-string))
              (line-seq rdr)))
      [])))

(defn- read-edn-file
  "Read a single EDN form from a file. Returns nil if not found."
  [path]
  (when (tio/exists? (tio/as-path path))
    (tio/read-edn-file (str path))))

(defn- delete-dir!
  "Delete a directory and its contents."
  [path]
  (let [dir (io/file (str path))]
    (when (.exists dir)
      (doseq [f (reverse (file-seq dir))]
        (.delete f)))))

;; Index management — simple file-per-value approach.
;; Each index file stores the uuid of the entity that owns that value.

(def ^:private indexed-fields
  "Fields that get indexed for fast lookup. Per entity-type."
  {:order    #{:broker-id :perm-id}
   :position #{:account}})

(defn- write-index! [base-path entity-type field value uuid]
  (when value
    (write-edn! (index-path base-path entity-type field value) uuid)))

(defn- delete-index! [base-path entity-type field value]
  (when value
    (let [f (io/file (str (index-path base-path entity-type field value)))]
      (.delete f))))

(defn- update-indexes!
  "Write index entries for all indexed fields on an entity."
  [base-path entity-type entity]
  (doseq [field (get indexed-fields entity-type)]
    (when-let [v (get entity field)]
      (write-index! base-path entity-type field v (:uuid entity)))))

(defn- remove-indexes!
  "Remove index entries for an entity."
  [base-path entity-type entity]
  (doseq [field (get indexed-fields entity-type)]
    (when-let [v (get entity field)]
      (delete-index! base-path entity-type field v))))

(defn- lookup-index [base-path entity-type field value]
  (read-edn-file (index-path base-path entity-type field value)))

(defn- scan-snapshots
  "Load all snapshots of a given entity-type. For query when no index exists."
  [base-path entity-type]
  (let [dir (io/file (str base-path) (name entity-type))]
    (when (.exists dir)
      (->> (.listFiles dir)
           (filter #(.isDirectory %))
           (keep (fn [d]
                   (when-let [uuid (parse-uuid (.getName d))]
                     (read-edn-file (entity-file base-path entity-type uuid "snapshot.edn")))))))))


(defrecord EdnFileStore [base-path]
  Store

  (append-event! [_ entity-type entity-uuid event]
    (append-edn! (entity-file base-path entity-type entity-uuid "events.edn") event)
    event)

  (events-for [_ entity-type entity-uuid]
    (read-edn-lines (entity-file base-path entity-type entity-uuid "events.edn")))

  (save! [_ entity-type entity]
    (let [uuid (:uuid entity)]
      (assert uuid "Entity must have :uuid")
      (write-edn! (entity-file base-path entity-type uuid "snapshot.edn") entity)
      (update-indexes! base-path entity-type entity)
      entity))

  (load-by-uuid [_ entity-type uuid]
    (read-edn-file (entity-file base-path entity-type uuid "snapshot.edn")))

  (load-by [this entity-type field value]
    (if-let [uuid (lookup-index base-path entity-type field value)]
      (load-by-uuid this entity-type uuid)
      ;; Fallback: scan if field isn't indexed (slow, but correct)
      (first (filter #(= value (get % field)) (scan-snapshots base-path entity-type)))))

  (query [_ entity-type criteria]
    (let [all (scan-snapshots base-path entity-type)]
      (if (empty? criteria)
        (vec all)
        (into [] (filter (fn [entity]
                           (every? (fn [[k v]] (= v (get entity k)))
                                   criteria)))
              all))))

  (list-uuids [_ entity-type]
    (let [dir (io/file (str base-path) (name entity-type))]
      (when (.exists dir)
        (->> (.listFiles dir)
             (filter #(.isDirectory %))
             (map #(parse-uuid (.getName %)))
             (remove nil?)))))

  (append-fill! [_ order-uuid fill]
    (append-edn! (entity-file base-path :order order-uuid "fills.edn") fill)
    fill)

  (fills-for [_ order-uuid]
    (read-edn-lines (entity-file base-path :order order-uuid "fills.edn")))

  (delete-entity! [this entity-type uuid]
    (when-let [entity (load-by-uuid this entity-type uuid)]
      (remove-indexes! base-path entity-type entity))
    (delete-dir! (entity-dir base-path entity-type uuid))))


(defn edn-store
  "Create a store backed by EDN files at the given path."
  [base-path]
  (tio/create-dirs (tio/as-path base-path))
  (->EdnFileStore base-path))
