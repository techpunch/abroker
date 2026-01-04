(ns abroker.ibkr.tools
   "Contains higher level tools that build on top of the TWS base API"
   (:require [clojure.core.async :refer [go chan >! close! alts! timeout]]
             [clojure.string :as str]
             [clojure.tools.logging :as log]))

(defn nonzero [{:keys [quantity]}]
  (not (zero? quantity)))

(defn long-short [{:keys [quantity]}]
  (cond
    (neg? quantity) :short
    (pos? quantity) :long
    :else :none))

(defn- aggregate-group [pos-group]
  (->> pos-group
       (group-by :symbol)
       (map (fn [[symbol pos-list]]
              [symbol (reduce + (map #(* (abs (:quantity %)) (:avg-cost %)) pos-list))]))
       (sort-by second >)))

(defn group-positions
  "Groups nonzero positions (discarding others) by :long :short then by :type. Each
  :long/:short is sorted by total avg-cost desc. Applies optional filter-pred to
  positions before grouping. Result format example:
  {:stock {:long [...] :short [...]}
   :option {:long [...] :short [...]}
   ...}"
  ([positions]
   (group-positions identity positions))
  ([filter-pred positions]
   (->> positions
        (filter (every-pred nonzero filter-pred))
        (group-by :type)
        (map (fn [[type-key positions]]
               [type-key
                (->> positions
                     (group-by long-short)
                     (map (fn [[long-or-short ls-positions]]
                            [long-or-short (aggregate-group ls-positions)]))
                     (into {}))]))
        (into {}))))

(defn positions-csv
  "Turns a result of fn group-positions and turns each nested :long/:short vec into a csv
  string order by avg-cost desc."
  [grouped-positions]
  (->> grouped-positions
       (map (fn [[type-key long-short-map]]
              [type-key
               (->> long-short-map
                    (map (fn [[long-short-key symbol-list]]
                           [long-short-key (->> symbol-list
                                                (map first)
                                                (str/join ","))]))
                    (into {}))]))
       (into {})))

(defn req-single!
  "Convenience wrapper for calls to abroker.ibkr.client fns that return a chan that
  expect a single result. Returns a chan that will either get closed on timeout or
  be delivered the result then closed."
  [req-f & {:keys [timeout-ms] :or {timeout-ms 6000}}]
  (let [out (chan 1)
        pos-chan (req-f)
        timer (timeout timeout-ms)]
    (when pos-chan
      (go
        (let [[positions c] (alts! [pos-chan timer])]
          (if (= c timer)
            (log/warn "ReqPositions Timeout")
            (>! out positions))
          (close! out)))
      out)))
