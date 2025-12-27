(ns abroker.async-ctx-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [go alts!! >! timeout]]
            [abroker.async-ctx :as ctx]))

(deftest call-contexts

  (let [ctx-atom (ctx/ctx-atom #(update % :init-count (fnil inc 0)))
        new-called-count (atom 0)
        do-init (fn []
                  (ctx/tap! ctx-atom (fn [_] (swap! new-called-count inc))))
        do-done (fn []
                  (testing "ctx gets returned on a first call to mark-done!"
                    (let [ctx (ctx/mark-done! ctx-atom)]
                      (go
                        (>! (ctx/out-chan ctx) :expected)
                        (ctx/dispose! ctx)))))
        verify-read (fn [chan]
                      (let [timer (timeout 1000)
                            [data c] (alts!! [chan timer])]
                        (is (not (= c timer)))
                        (is (= :expected data))))]
    (testing "ctx-atom created correctly"
      (is (not (ctx/new? @ctx-atom)))
      (is (not (ctx/just-finished? @ctx-atom))))
    (let [read-chan1 (do-init)]
      (testing "first call to ctx init!"
        (is (ctx/new? @ctx-atom))
        (is (not (ctx/just-finished? @ctx-atom)))
        (is (= 1 @new-called-count))
        (is (= 1 (:init-count @ctx-atom))))
      (let [read-chan2 (do-init)]
        (testing "second call to ctx init before ctx is marked done (aka an in-progress ctx)"
          (is (not (ctx/new? @ctx-atom)))
          (is (not= read-chan1 read-chan2))
          (testing "on-new-ctx-f wasn't called on init of an in-progress ctx"
            (is (= 1 @new-called-count)))
          (testing "our data is still there"
            (is (= 1 (:init-count @ctx-atom)))))
        (do-done)
        (testing "subsequent calls to mark-done! don't return the ctx"
          (is (nil? (ctx/mark-done! ctx-atom))))
        (testing "first tap receives data"
          (verify-read read-chan1))
        (testing "second tap receives data"
          (verify-read read-chan2))
        (testing "re-init! of a 'done' ctx re-inits correctly"
          (let [read-chan3 (do-init)]
            (is (ctx/new? @ctx-atom))
            (is (not (ctx/just-finished? @ctx-atom)))
            (is (= 2 @new-called-count))
            (is (= 2 (:init-count @ctx-atom)))
            (do-done)
            (verify-read read-chan3))))
      )))
