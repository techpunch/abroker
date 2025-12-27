(ns abroker.async-ctx
  "Defines a reuseable call context that helps us cleanly call async services that may
  having pacing limtiations, like Interactive Brokers. E.g., if there's an existing call
  in progress when another thread tries to make the same call, we can piggy back on the result."
  (:require [clojure.core.async :as async :refer [chan close! mult tap]]))

(defn init-fn
  "Returns a fn to init or update a ctx to be used for async calls that have state. If the
  current call ctx is empty or done, a new one is created, else a new channel tap is created
  and put into the ctx's taps vec.
  Callers of a fn that uses these call ctxs should be returned the last chan in the taps vec.
  EXAMPLE USE: reqPositions triggers many callbacks from TWS ending with a
  positionEnd event, and theoretically multiple clients could be trying to call
  req-positions while one is in progress. This ctx allows us to make just 1 call and
  deliver the same result to all the callers when done. If the size of the taps vec
  is 1 the initial reqPositions call needs to be made, but can be skipped otherwise.
  Each caller is returned their own tap (the last tap in the taps vec after this
  wrapped fn is called) to read eventual result from."
  [addl-init-ctx]
  (fn [curr-ctx]
    (if (or (not curr-ctx) (pos? (:done curr-ctx))) ; if curr-ctx is empty or done, make a new one
      (let [c (chan 1)
            mux (mult c)]
        (assoc addl-init-ctx
               :done 0 ; int is more defensive than bool in case a service ever raises 2+ "end" events
               :chan c
               :mult mux
               :taps [(tap mux (chan 1))]))
      (update curr-ctx :taps
              conj (tap (:mult curr-ctx) (chan 1))))))

(defn new? [ctx]
  (= 1 (count (:taps ctx))))

(defn last-tap [ctx]
  (peek (:taps ctx)))

(defn mark-done [ctx]
  (update ctx :done inc))

(defn just-finished? [ctx]
  (= 1 (:done ctx)))

(defn dispose [ctx]
  (close! (:chan ctx))
  ; shouldn't need to untap here since these contexts are ephemeral
  )
