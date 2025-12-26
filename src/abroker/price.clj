(ns abroker.price
  (:import (java.time ZonedDateTime))
  (:require [techpunch.num :refer [as-long as-double]]))

;; BigDecimal vs double: I originally implemented prices with BigDecimal.
;; But after research & thought, I went with double because:
;; 1. Current data sources clearly use doubles for their price history, e.g. we
;;    see prices like 150.499999 in yahoo's price history service. Seems
;;    pointless to convert that to bigdec.
;; 2. The rule of thumb "one should always use bigdec for money" makes sense
;;    when you're talking about prices that must audit/foot correctly to the
;;    penny, like a customer's bill or an accounting ledger. Not the case here.
;; 3. Doubles are more efficient for both storage and compute.

(defn fmt-price [price]
  (let [price (as-double price)
        fmt (if (>= price 1.0) "%.2f" "%.4f")]
    (format fmt price)))

(defn price
  "Converts x into a price of type double. If split is provided, x will be
  adjusted by dividing by number split."
  ([x]
   (as-double x))
  ([x split]
   (/ (as-double x) split)))

(defn vol
  "Converts x into a volume of type long. If split is provided, x will be
  adjusted by multiplying by number split."
  ([x]
   (as-long x))
  ([x split]
   (as-long (* (as-long x) split))))

(defrecord Bar [^ZonedDateTime date-time ^long volume
                ^double open ^double high ^double low ^double close]
  Object
  (toString [_]
    (let [[o h l c] (map fmt-price [open high low close])]
      (str date-time " V:" volume " O:" o " H:" h " L:" l " C:" c))))

(def ^{:arglists '([date volume open high low close])
       :doc "Fast bar constructor, no coercing."}
  bar ->Bar)
