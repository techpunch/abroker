(ns abroker.screen
  "Broker-agnostic market screens (IBKR calls them scanners). A screen is a plain map
  describing what to look for; adapters translate it to their broker's screener call
  and return a vec of scan rows ranked best-first.

  Build screens with the DSL, which threads like the order DSL in abroker.data:

    (-> (screen :top-gainers)
        (location :us-major)
        (price-above 10)
        (volume-above 1000000))

  Every setter takes nil to clear a field, so the defaults applied by `screen` can be
  removed: (-> (screen :most-active) (price-above nil))"
  (:require [clojure.tools.logging :as log]
            [techpunch.util :refer [valid-arg]]))


(def max-rows
  "Most brokers cap screener results; IBKR returns at most 50 rows per scan."
  50)

(def defaults
  "Applied by `screen`. US major exchanges keeps OTC/pink sheets out, and the $5 floor
  drops the sub-$5 names that dominate percentage-move scans but are hard to trade
  (no margin, poor borrow, wide spreads). Both are removable per screen."
  {:instrument :stock
   :location :us-major
   :rows max-rows
   :price-above 5.0})


(defn- put
  "assoc, or dissoc when v is nil so setters can clear a default."
  [screen k v]
  (if (nil? v)
    (dissoc screen k)
    (assoc screen k v)))

(defn screen
  "Starts a screen for scan-code with `defaults` applied. scan-code is one of the
  canonical codes the presets at the bottom of this namespace use (:top-gainers,
  :most-active, …), which every adapter maps to its own vocabulary — or a broker's own
  code, as a keyword or string, which is passed through. See
  abroker.ibkr.tools/scan-codes to list the codes TWS actually accepts."
  [scan-code]
  (valid-arg (or (keyword? scan-code) (string? scan-code))
             "scan-code must be a keyword or string:" scan-code)
  (assoc defaults :scan-code scan-code))


;; What to scan

(defn instrument
  "Asset class to scan, e.g. :stock. See abroker.ibkr.codes/scan-instrument.

  Leaving :stock clears the default location, because location codes are
  instrument-specific and a stock location on a futures scan is silently wrong — set a
  matching one. Reconsider the inherited `defaults` price floor too."
  [screen instrument-code]
  (cond-> (put screen :instrument instrument-code)
    (and instrument-code
         (not= :stock instrument-code)
         (= (:location defaults) (:location screen)))
    (dissoc :location)))

(defn location
  "Market/exchange to scan, e.g. :us-major. See abroker.ibkr.codes/scan-location, and
  abroker.ibkr.tools/location-codes for the full broker list."
  [screen location-code]
  (put screen :location location-code))

(defn stock-type
  "Restricts a stock scan to a share type, e.g. :corp (common shares) or :etf.
  Unset means every type. See abroker.ibkr.codes/stock-type-filter."
  [screen type-code]
  (put screen :stock-type type-code))

(defn rows
  "Max rows to return, clamped to `max-rows`."
  [screen n]
  (valid-arg (or (nil? n) (pos? n)) "rows must be positive:" n)
  (put screen :rows (when n
                      (if (> n max-rows)
                        (do (log/warn "Screen asked for" n "rows; broker caps at" max-rows)
                            max-rows)
                        n))))


;; Filters

(defn price-above [screen price]
  (put screen :price-above price))

(defn price-below [screen price]
  (put screen :price-below price))

(defn volume-above
  "Floor on shares traded *so far today* — it screens out illiquid names during the
  session but rejects nearly everything pre-market. Use a broker filter tag such as
  \"avgVolumeAbove\" (see `filters`) when you need an average-volume floor instead."
  [screen shares]
  (put screen :volume-above shares))

(defn option-volume-above
  "Floor on average daily option volume for the underlying."
  [screen contracts]
  (put screen :option-volume-above contracts))

(defn market-cap-above [screen amt]
  (put screen :market-cap-above amt))

(defn market-cap-below [screen amt]
  (put screen :market-cap-below amt))

(defn filters
  "Merges broker-native filter tags, the escape hatch for anything the typed setters
  above don't cover, e.g. {\"changePercAbove\" 5 \"hasOptionsIs\" true}. Tag names are
  case-sensitive and broker-specific — list the valid ones with
  abroker.ibkr.tools/filter-codes. Merging nil or {} is a no-op; a tag with a nil
  value is dropped."
  [screen tag-map]
  (let [merged (->> (merge (:filters screen) tag-map)
                    (remove (comp nil? val))
                    (into {}))]
    (put screen :filters (not-empty merged))))


;; Scan rows

(defn row-instrument
  "Extracts a plain instrument map from a scan row, keeping :con-id so the broker
  resolves the exact contract, and dropping the scanner's exchange so follow-up
  requests route normally (SMART for IBKR)."
  [row]
  (select-keys row [:type :symbol :con-id]))


;; Presets — starting points worth keeping, not gospel. Each returns a screen you can
;; keep threading. Their scan codes are the canonical vocabulary: adapters map them
;; (see abroker.ibkr.codes/scan-code), so these work whatever the broker calls them.
;; Volume floors are intraday share counts (see `volume-above`).

(defn top-gainers
  "Biggest percentage gainers on the day, liquid enough to trade."
  []
  (-> (screen :top-gainers)
      (volume-above 500000)))

(defn top-losers []
  (-> (screen :top-losers)
      (volume-above 500000)))

(defn most-active
  "Highest dollar volume today — where the day's real participation is."
  []
  (screen :most-active))

(defn unusual-volume
  "Volume furthest above the name's own average: the classic 'something is going on
  here' scan."
  []
  (-> (screen :unusual-volume)
      (volume-above 500000)))

(defn gap-ups
  "Largest gaps up from the previous close. No volume floor — gaps are found before
  the day's volume exists."
  []
  (screen :gap-ups))

(defn gap-downs []
  (screen :gap-downs))

(defn near-52w-high []
  (-> (screen :near-52w-high)
      (volume-above 500000)))

(defn near-52w-low []
  (-> (screen :near-52w-low)
      (volume-above 500000)))


(comment
  (-> (top-gainers)
      (price-above 20)
      (rows 25)
      (filters {"changePercAbove" 10}))

  (-> (screen :most-active)
      (location :us)
      (stock-type :corp))
  ,)
