(ns abroker.ibkr.client-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [chan sliding-buffer <!! alts!! timeout]]
            [abroker.ibkr.client :as client :refer :all]
            [abroker.ibkr.data-test :refer [mk-raw-scan-event]]))


;; Scan accumulation, driven through handle-event the way the event worker does it.
;; No TWS connection needed: with no client, end-scan! skips the cancel call.

(def ^:private scans #'client/scans)

(defn- start-test-scan! [req-id stream?]
  (let [out (chan (sliding-buffer 1))]
    (swap! @scans assoc req-id {:screen {:scan-code :test} :rows [] :out out
                                :stream? stream?})
    out))

(defn- scan-data! [req-id rank symbol]
  (handle-event (assoc (mk-raw-scan-event rank symbol 0 nil)
                       :type :scanner-data
                       :req-id req-id)))

(defn- taken [c]
  (first (alts!! [c (timeout 500)])))

(deftest one-shot-scan
  (let [out (start-test-scan! 101 false)]
    (scan-data! 101 1 "AMD")
    (scan-data! 101 0 "NVDA")
    (handle-event {:type :scanner-data-end :req-id 101})
    (testing "rows arrive together, ranked best first whatever order TWS sent them"
      (is (= ["NVDA" "AMD"] (map :symbol (taken out)))))
    (testing "chan closes and the scan is forgotten"
      (is (nil? (taken out)))
      (is (nil? (@@scans 101))))))

(deftest streaming-scan
  (let [out (start-test-scan! 102 true)]
    (scan-data! 102 0 "NVDA")
    (handle-event {:type :scanner-data-end :req-id 102})
    (is (= ["NVDA"] (map :symbol (taken out))))
    (testing "next snapshot starts from empty and the scan stays live"
      (scan-data! 102 0 "AMD")
      (handle-event {:type :scanner-data-end :req-id 102})
      (is (= ["AMD"] (map :symbol (taken out))))
      (is (= {102 {:scan-code :test}} (live-scans))))
    (cancel-scan 102)
    (is (nil? (taken out)))
    (is (empty? (live-scans)))))

(deftest scan-errors
  (testing "a TWS failure for the scan's req-id ends it instead of hanging the caller"
    (let [out (start-test-scan! 103 false)]
      (handle-event {:type :error :req-id 103 :error-code 162
                     :error-msg "Historical Market Data Service error"})
      (is (nil? (taken out)))
      (is (empty? (live-scans)))))
  (testing "warnings (2100+) leave the scan alone"
    (let [out (start-test-scan! 104 false)]
      (handle-event {:type :error :req-id 104 :error-code 2137
                     :error-msg "Cross Side Warning"})
      (is (= {104 {:scan-code :test}} (live-scans)))
      (cancel-scan)
      (is (nil? (taken out)))))
  (println "^ The TWS error log messages above are normal for these error tests"))

(deftest disconnect-ends-scans
  (testing "subscriptions die with the socket, so consumers see their chan close"
    (let [one-shot (start-test-scan! 105 false)
          stream (start-test-scan! 106 true)]
      (handle-event {:type :connection-closed})
      (is (nil? (taken one-shot)))
      (is (nil? (taken stream)))
      (is (empty? (live-scans))))))

(deftest events-for-unknown-scans-are-ignored
  (is (nil? (handle-event {:type :scanner-data-end :req-id 999})))
  (is (nil? (scan-data! 999 0 "NVDA")))
  (is (empty? (live-scans))))


(comment
  (let [c (req-positions)
        c2 (req-positions)]
    (println "first" (alts!! [c (timeout 2000)]))
    (println "second" (alts!! [c2 (timeout 2000)]))))
