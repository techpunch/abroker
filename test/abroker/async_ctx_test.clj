(ns abroker.async-ctx-test
  (:require [clojure.test :refer :all]
            [clojure.core.async :refer [go alts!! >! timeout]]
            [abroker.async-ctx :refer :all]))

(deftest call-contexts

  (let [ctx (atom nil)]
    (is (not (new? @ctx)))
    (is (not (just-finished? @ctx)))
    (let [ctx (swap! ctx (init-fn {:test-data :foo}))
          read-chan (last-tap ctx)
          write-chan (:chan ctx)
          timer (timeout 1000)]
      (is (new? ctx))
      (is (not (just-finished? ctx)))
      (is (= :foo (:test-data ctx)))
      (go
        (>! write-chan :expected)
        (dispose ctx))
      (let [[data c] (alts!! [read-chan timer])]
        (is (not (= c timer)))
        (is (= :expected data))))))
