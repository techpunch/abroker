(ns abroker.ibkr.tools-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.core.async :refer [chan go close! <! >! <!! timeout]]
            [abroker.ibkr.tools :as tools]))

(def test-positions
  [{:account "A" :symbol "AAPL" :type :stock :quantity 10.0 :avg-cost 150.0}
   {:account "B" :symbol "AAPL" :type :stock :quantity 5.0 :avg-cost 160.0}
   {:account "A" :symbol "XYZ" :type :stock :quantity -20.0 :avg-cost 250.0}
   {:account "B" :symbol "XYZ" :type :stock :quantity -10.0 :avg-cost 250.0}
   {:account "B" :symbol "CVNA" :type :option :subtype :put :quantity 2.0 :avg-cost 4000.0}
   {:account "A" :symbol "GOOGL" :type :stock :quantity 0.0 :avg-cost 2800.0}
   {:account "A" :symbol "MSFT" :type :stock :quantity 8 :avg-cost 380.0}])

(deftest long-short-test
  (testing "Position classification"
    (is (= :long (tools/long-short {:quantity 10.0})))
    (is (= :short (tools/long-short {:quantity -5.0})))
    (is (= :none (tools/long-short {:quantity 0})))
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
  (let [positions [{:account "A" :symbol "AAPL" :type :stock :quantity 10 :avg-cost 150.0}
                   {:account "B" :symbol "XYZ" :type :stock :quantity 5 :avg-cost 200.0}]
        account-filter #(= "A" (:account %))
        result (tools/group-positions account-filter positions)]
    (is (= {:stock {:long [["AAPL" 1500.0]]}} result))))

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
      (println "WARNING log message is normal for this test"))))
