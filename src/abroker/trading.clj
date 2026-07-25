(ns abroker.trading
  "Trading abstraction"
  (:require [abroker.ibkr.client :as ib]))

; For now just forwards to IBKR

(defn send-order! [instrument order]
  (ib/send-order! instrument order))


;; Screens — see abroker.screen to build them, doc/data-model/ScreenModel.md for the model

(defn scan
  "Runs a screen once. Returns a chan delivering one vec of scan rows ranked best
  first, then closing; nil when not connected."
  [screen & opts]
  (apply ib/req-scan screen opts))

(defn scan-stream
  "Subscribes to a screen and keeps it live, returning {:req-id _ :out _}. The caller
  must `cancel-scan` the req-id when done."
  [screen]
  (ib/req-scan-stream screen))

(defn cancel-scan
  "Cancels the scan for req-id, or with no args every live scan."
  ([]
   (ib/cancel-scan))
  ([req-id]
   (ib/cancel-scan req-id)))
