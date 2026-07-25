(ns abroker.screen-test
  (:require [clojure.test :refer [deftest is testing]]
            [abroker.screen :as s]))

(deftest screen-fn
  (testing "applies defaults"
    (is (= {:scan-code :top-gainers
            :instrument :stock
            :location :us-major
            :rows s/max-rows
            :price-above 5.0}
           (s/screen :top-gainers))))
  (testing "raw broker code string is allowed"
    (is (= "TOP_PERC_GAIN" (:scan-code (s/screen "TOP_PERC_GAIN")))))
  (testing "rejects a nonsense scan-code"
    (is (thrown? IllegalArgumentException (s/screen 42)))))

(deftest instrument-fn
  (testing "a non-stock instrument drops the stock-shaped default location"
    (is (nil? (:location (s/instrument (s/screen :most-active) :future)))))
  (testing "an explicitly chosen location is left alone"
    (is (= "FUT.US.SOMEWHERE"
           (-> (s/screen :most-active)
               (s/location "FUT.US.SOMEWHERE")
               (s/instrument :future)
               :location))))
  (testing "staying on stocks keeps the default"
    (is (= :us-major (:location (s/instrument (s/screen :most-active) :stock))))))

(deftest setters
  (testing "thread and overwrite"
    (is (= {:scan-code :most-active
            :instrument :future
            :location :us
            :stock-type :corp
            :rows 10
            :price-above 20
            :price-below 100
            :volume-above 1000000
            :option-volume-above 500
            :market-cap-above 1e9
            :market-cap-below 1e11}
           (-> (s/screen :most-active)
               (s/instrument :future)
               (s/location :us)
               (s/stock-type :corp)
               (s/rows 10)
               (s/price-above 20)
               (s/price-below 100)
               (s/volume-above 1000000)
               (s/option-volume-above 500)
               (s/market-cap-above 1e9)
               (s/market-cap-below 1e11)))))
  (testing "nil clears a default rather than setting it"
    (let [bare (-> (s/screen :most-active)
                   (s/price-above nil)
                   (s/location nil))]
      (is (= {:scan-code :most-active :instrument :stock :rows s/max-rows} bare))))
  (testing "rows clamps to the broker cap"
    (is (= s/max-rows (:rows (s/rows (s/screen :most-active) 500)))))
  (testing "rows rejects a count that can't return anything"
    (is (thrown? IllegalArgumentException (s/rows (s/screen :most-active) 0)))))

(deftest filters-fn
  (let [scr (s/screen :most-active)]
    (testing "merges tags across calls"
      (is (= {"changePercAbove" 5 "hasOptionsIs" true}
             (-> scr
                 (s/filters {"changePercAbove" 5})
                 (s/filters {"hasOptionsIs" true})
                 :filters))))
    (testing "a nil value drops the tag"
      (is (nil? (-> scr
                    (s/filters {"changePercAbove" 5})
                    (s/filters {"changePercAbove" nil})
                    :filters))))
    (testing "no filters means no key"
      (is (nil? (:filters (s/filters scr nil)))))))

(deftest row-instrument-fn
  (testing "keeps what identifies the contract, drops scan metadata and exchange"
    (is (= {:type :stock :symbol "NVDA" :con-id 4815747}
           (s/row-instrument {:rank 0 :symbol "NVDA" :type :stock :con-id 4815747
                              :exchange "NASDAQ" :currency "USD" :name "NVIDIA CORP"})))))

(deftest presets
  (testing "build on the defaults and stay threadable"
    (is (= {:scan-code :top-gainers
            :instrument :stock
            :location :us-major
            :rows 25
            :price-above 5.0
            :volume-above 500000}
           (-> (s/top-gainers)
               (s/rows 25)))))
  (testing "use the canonical vocabulary, not a broker's"
    (is (= [:top-gainers :top-losers :most-active :unusual-volume
            :gap-ups :gap-downs :near-52w-high :near-52w-low]
           (map :scan-code [(s/top-gainers) (s/top-losers) (s/most-active)
                            (s/unusual-volume) (s/gap-ups) (s/gap-downs)
                            (s/near-52w-high) (s/near-52w-low)]))))
  (testing "gap scans carry no intraday volume floor"
    (is (nil? (:volume-above (s/gap-ups))))))
