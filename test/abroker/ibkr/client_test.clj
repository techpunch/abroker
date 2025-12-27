(ns abroker.ibkr.client-test
  (:require [clojure.test :refer :all]
            [abroker.ibkr.client :refer :all]))


(comment
  (let [c (req-positions)
        c2 (req-positions)]
    (println "first" (alts!! [c (timeout 2000)]))
    (println "second" (alts!! [c2 (timeout 2000)]))))
