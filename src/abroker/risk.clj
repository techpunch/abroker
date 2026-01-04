(ns abroker.risk
  (:require [abroker.data :refer [config]]
            [techpunch.util :as u]))

(defn calc
  "Returns the dollar risk amount for proposed trade params, throwing an exception if
  the args don't make sense."
  [buy-or-sell entry-price stop-price]
  (let [neg-risk? (case buy-or-sell :buy <= :sell >=)]
    (when (neg-risk? entry-price stop-price)
      (u/throw-illegal-arg "negative risk calc" entry-price stop-price)))
  (abs (- entry-price stop-price)))

(defn check
  "Checks whether an order meets risk requirements. Returns the order if it does"
  [{:keys [quantity limit-price stop-price touch-price] :as order}]
  (let [price (or limit-price stop-price touch-price)
        max-amt (config :risk-mgmt :max-order-amt)]
    (when-not max-amt
      (u/throw-rte "Config value [:broker-config :risk-mgmt :max-order-amt] missing"))
    (when (and price (> (* price quantity) max-amt))
      (u/throw-rte "Order exceeds max dollar amount allowed" quantity price max-amt))
    order))
