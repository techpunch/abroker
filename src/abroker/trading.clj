(ns abroker.trading
  "Trading abstraction"
  (:require [abroker.ibkr.client :as ib]))

; For now just forwards to IBKR

(defn send-order! [instrument order]
  (ib/send-order! instrument order))
