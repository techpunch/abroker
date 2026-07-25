(ns abroker.trading
  "Trading abstraction"
  (:require [abroker.ibkr.client :as ib]))

; For now just forwards to IBKR

(defn send-order! [instrument order]
  (ib/send-order! instrument order))

(defn screen
  "Runs a one-shot market screen with good defaults, returning a chan that delivers
  a rank-sorted vec of results. See abroker.ibkr.client/req-scanner-data for spec
  options and result details."
  [& {:as scan-spec}]
  (ib/req-scanner-data scan-spec))
