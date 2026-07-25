(ns abroker.ibkr.tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :refer [chan go close! <! >! <!! timeout]]
            [abroker.ibkr.tools :as tools]))

(def test-positions
  [{:account "A" :symbol "AAPL" :type :stock :quantity 10.0M :avg-cost 150.0}
   {:account "B" :symbol "AAPL" :type :stock :quantity 5.0M :avg-cost 160.0}
   {:account "A" :symbol "XYZ" :type :stock :quantity -20.0M :avg-cost 250.0}
   {:account "B" :symbol "XYZ" :type :stock :quantity -10.0M :avg-cost 250.0}
   {:account "B" :symbol "CVNA" :type :option :subtype :put :quantity 2.0M :avg-cost 4000.0}
   {:account "A" :symbol "GOOGL" :type :stock :quantity 0.0M :avg-cost 2800.0}
   {:account "A" :symbol "MSFT" :type :stock :quantity 8M :avg-cost 380.0}])

(def scanner-params-xml
  "<ScanParameterResponse>
     <Instrument><type>STK</type><filters>PRICE,VOLUME</filters></Instrument>
     <LocationTree><Location><locationCode>STK.US.MAJOR</locationCode>
       <Location><locationCode>STK.US.MAJOR.NASDAQ</locationCode></Location></Location>
       <Location><locationCode>STK.US.MINOR</locationCode></Location></LocationTree>
     <ScanTypeList>
       <ScanType><displayName>Top % Gainers</displayName><scanCode>TOP_PERC_GAIN</scanCode></ScanType>
       <ScanType><displayName>Most Active</displayName><scanCode>MOST_ACTIVE</scanCode></ScanType>
       <ScanType><displayName>Top % Gainers</displayName><scanCode>TOP_PERC_GAIN</scanCode></ScanType>
     </ScanTypeList>
     <FilterList>
       <RangeFilter><AbstractField><code>priceAbove</code></AbstractField>
                    <AbstractField><code>priceBelow</code></AbstractField></RangeFilter>
       <RangeFilter><AbstractField><code>changePercAbove</code></AbstractField></RangeFilter>
     </FilterList>
   </ScanParameterResponse>")

(deftest scanner-params-extraction
  (testing "scan codes, deduped and sorted"
    (is (= ["MOST_ACTIVE" "TOP_PERC_GAIN"] (tools/scan-codes scanner-params-xml))))
  (testing "location codes, including nested ones"
    (is (= ["STK.US.MAJOR" "STK.US.MAJOR.NASDAQ" "STK.US.MINOR"]
           (tools/location-codes scanner-params-xml))))
  (testing "filter tag names"
    (is (= ["changePercAbove" "priceAbove" "priceBelow"]
           (tools/filter-codes scanner-params-xml))))
  (testing "a tag that isn't there"
    (is (= [] (tools/xml-tag-values scanner-params-xml "nope")))))

(deftest long-short-test
  (testing "Position classification"
    (is (= :long (tools/long-short {:quantity 10.0M})))
    (is (= :short (tools/long-short {:quantity -5.0M})))
    (is (= :none (tools/long-short {:quantity 0})))
    (is (= :none (tools/long-short {:quantity 0M})))
    (is (= :none (tools/long-short {:quantity 0.0})))))

(deftest group-positions-test
  (testing "Full aggregation"
    (let [res (tools/group-positions test-positions)]
      (testing "Filters out zero positions"
        (is (not (some #(= "GOOGL" (first %)) (get-in res [:stock :long])))))
      (testing "Correctly classifies stock positions"
        (is (= {:long [["MSFT" 3040.0] ["AAPL" 2300.0]]
                :short [["XYZ" 7500.0]]}
               (res :stock))))
      (testing "Correctly classifies option"
        (is (= {:long [["CVNA" 8000.0]]}
               (res :option))))
      (testing "csv tool"
        (is (= {:stock {:long "MSFT,AAPL", :short "XYZ"}
                :option {:long "CVNA"}}
               (tools/positions-csv res)))))))

(testing "Custom filter predicate"
  (let [positions [{:account "A" :symbol "AAPL" :type :stock :quantity 10M :avg-cost 150.0}
                   {:account "B" :symbol "XYZ" :type :stock :quantity 5M :avg-cost 200.0}]
        account-filter #(= "A" (:account %))
        result (tools/group-positions account-filter positions)]
    (is (= {:stock {:long [["AAPL" 1500.0]]}} result))))

; Positions as IBKR reports them when FA allocation groups are in use.
; Real account IDs look like U12345678. Alloc group "accounts" are user-named
; strings (Tax20, Tax4, TrIra etc.) and always report avg-cost 0.0.
; When a position is closed in real accounts, alloc groups may still report
; it with a non-zero quantity — a ghost position.
(def positions-with-alloc-ghosts
  [; real accounts — COIN is open, XYZ is fully closed (not reported at all)
   {:account "U12345678" :symbol "COIN" :type :stock :quantity 20M  :avg-cost 241.67}
   {:account "U87654321" :symbol "COIN" :type :stock :quantity 20M  :avg-cost 239.28}
   ; alloc group entries for COIN (real position, but zero avg-cost dupes)
   {:account "Tax20"     :symbol "COIN" :type :stock :quantity 40M  :avg-cost 0.0}
   {:account "Tax4"      :symbol "COIN" :type :stock :quantity 30M  :avg-cost 0.0}
   ; alloc group ghost: XYZ was closed in real accounts but alloc still carries it
   {:account "Tax20"     :symbol "XYZ"  :type :stock :quantity 40M  :avg-cost 0.0}
   {:account "Tax4"      :symbol "XYZ"  :type :stock :quantity 30M  :avg-cost 0.0}])

(deftest alloc-group-ghost-positions-test
  (testing "alloc group positions with avg-cost 0.0 are excluded from grouped output"
    (let [res (tools/group-positions positions-with-alloc-ghosts)]
      (testing "ghost symbol held only by alloc groups does not appear"
        (is (not (some #(= "XYZ" (first %)) (get-in res [:stock :long])))
            "XYZ is a ghost from alloc groups only and should be filtered"))
      (testing "real symbol aggregates only real account quantities, not alloc group dupes"
        (is (= [["COIN" (+ (* 20M 241.67) (* 20M 239.28))]]
               (get-in res [:stock :long]))
            "COIN qty/cost should reflect only real account positions")))))

(deftest req-single!-test
  (testing "Integration with async channel"
    (let [expected {:data :test}
          req-f (fn [] (let [res-chan (chan 1)]
                         (go (>! res-chan expected)
                             (close! res-chan))
                         res-chan))
          res-chan (tools/req-single! req-f)
          res (<!! res-chan)]
      (testing "Returns result"
        (is (= expected res)))))

  (testing "When no chan returned, like when not connected"
    (let [req-f (fn [] nil)
          res-chan (tools/req-single! req-f)]
      (testing "Returns nil instead of chan"
        (is (nil? res-chan)))))

  (testing "Timeout handling"
    (let [never-deliver-chan (chan 1)
          timeout-ms 100
          req-f (fn []
                  (go
                    (<! (timeout (+ timeout-ms 100)))
                    (close! never-deliver-chan))
                  never-deliver-chan)
          res-chan (tools/req-single! req-f :timeout-ms timeout-ms)
          res (<!! res-chan)]
      (is (nil? res) "Should close on timeout")
      (println "^ A timeout warning log message is normal for this timeout test"))))
