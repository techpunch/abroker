(ns abroker.ibkr.tools
   "Contains higher level tools that build on top of the TWS base API"
   (:require [clojure.core.async :refer [go chan >! close! alts! timeout]]
             [clojure.string :as str]
             [clojure.tools.logging :as log]))

(defn nonzero [{:keys [quantity]}]
  (not (zero? quantity)))

(defn has-cost? [{:keys [avg-cost]}]
  (pos? avg-cost))

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
  "Groups real positions (nonzero quantity, nonzero avg-cost) by :long/:short then
  by :type. Positions with avg-cost 0.0 are discarded — IBKR reports FA allocation
  group entries this way, and they can appear as ghosts for recently-closed positions.
  Each :long/:short is sorted by total avg-cost desc. Applies optional filter-pred to
  positions before grouping. Result format example:
  {:stock {:long [...] :short [...]}
   :option {:long [...] :short [...]}
   ...}"
  ([positions]
   (group-positions identity positions))
  ([filter-pred positions]
   (->> positions
        (filter (every-pred nonzero has-cost? filter-pred))
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

;; SCREENER PARAMETER DISCOVERY
;; TWS's scanner parameters document (see client/req-scanner-parameters) is several MB
;; of XML whose codes we only ever need as flat lists, so we pick values out of it with
;; a regex instead of paying to parse the whole tree. `spit` it to a file if you want
;; to read the full thing.

(defn xml-tag-values
  "Distinct sorted text values of an xml tag, e.g. (xml-tag-values xml \"scanCode\")."
  [xml tag]
  (->> (re-seq (re-pattern (str "<" tag ">([^<]+)</" tag ">")) xml)
       (into (sorted-set) (map second))
       (vec)))

(defn scan-codes
  "Scan codes TWS accepts, e.g. \"TOP_PERC_GAIN\" — the :scan-code of a screen."
  [scanner-params-xml]
  (xml-tag-values scanner-params-xml "scanCode"))

(defn location-codes
  "Location codes TWS accepts, e.g. \"STK.US.MAJOR\" — a screen's :location."
  [scanner-params-xml]
  (xml-tag-values scanner-params-xml "locationCode"))

(defn filter-codes
  "Filter tag names TWS accepts, e.g. \"changePercAbove\" — keys of a screen's
  :filters map."
  [scanner-params-xml]
  (xml-tag-values scanner-params-xml "code"))


(defn req-single!
  "Convenience wrapper for calls to abroker.ibkr.client fns that return a chan that
  expect a single result. Returns a chan that will either get closed on timeout or
  be delivered the result then closed."
  [req-f & {:keys [timeout-ms] :or {timeout-ms 6000}}]
  (let [out (chan 1)
        res-chan (req-f)
        timer (timeout timeout-ms)]
    (when res-chan
      (go
        (let [[positions c] (alts! [res-chan timer])]
          (if (= c timer)
            (log/warn "req-single! Timeout, req-f:" req-f)
            (>! out positions))
          (close! out)))
      out)))


(comment
  ;; scanner parameter discovery, with abroker.ibkr.client loaded and connected
  (def params (clojure.core.async/<!! (abroker.ibkr.client/req-scanner-parameters)))
  (count (scan-codes params))
  (filter #(re-find #"(?i)volume" %) (filter-codes params))
  (spit "scanner-params.xml" params)
  ,)
