(ns abroker.async-ctx
  "Defines a reuseable call context that helps us cleanly call async services that are
  expensive and/or have pacing limtiationss. If there's an existing call in progress
  when another thread tries to make the same call, we can piggy back on the result."
  (:require [clojure.core.async :as async :refer [chan close! mult tap]]))

(defn tap-ctx
  "Taps a ctx's chan, initializing ctx if uninitialized or marked done. Returns the
  updated ctx. The new channel tap is put into ctx's taps vec and can be accessed with
  `last-tap`. The tap should be returned to clients needing to read results. Arg init-f
  should take the ctx as an arg and return an intialized context. It's called when the
  current ctx is uninitialized or done. If init-f isn't passed, it's taken from
  ::init-f in the ctx.

  EXAMPLE USE: TWS's reqPositions triggers many `position` callbacks before finishing
  with a `positionEnd` callback. A thread could conceivably want to reqPositions while
  another is already in progress. The ctx allows us to make a single service call and
  deliver the result to all callers when done. The reqPositions service call only needs
  to be made when `new?` returns true, but can be skipped otherwise since the ctx
  was merely tapped but not (re-)initialized."
  ([ctx]
   (tap-ctx ctx (::init-f ctx)))
  ([ctx init-f]
   (if (pos? (or (::done ctx) 1)) ; if ctx is empty or done, initialize it
     (let [ctx (init-f ctx)
           c (chan 1)
           mux (mult c)]
       (assoc ctx
              ::done 0 ; int is more defensive than bool in case a service ever raises 2+ "end" events
              ::chan c
              ::mult mux
              ::taps [(tap mux (chan 1))]))
     (update ctx ::taps
             conj (tap (::mult ctx) (chan 1))))))

(defn new? [ctx]
  (= 1 (count (::taps ctx))))

(defn out-chan [ctx]
  (::chan ctx))

(defn last-tap [ctx]
  (peek (::taps ctx)))

(defn mark-done [ctx]
  (update ctx ::done inc))

(defn just-finished? [ctx]
  (= 1 (::done ctx)))

(defn dispose! [ctx]
  (close! (out-chan ctx))
  ; shouldn't need to untap here since these contexts are ephemeral
  )


;; Higher abstractions for the common pattern of storing ctx in an atom

(defn ctx-atom
  "Higher level abstraction for common pattern of storing ctx in an atom."
  ([]
   (ctx-atom #()))
  ([init-f]
   (atom {::init-f init-f})))

(defn tap!
  "Taps the ctx's chan (see `tap-ctx`) and returns the new tap. (Re-)initializes the
  ctx when it's uninitialized or marked as done. Calls on-new-ctx-f (must take a single
  arg: the ctx) only when the ctx was (re-)initialized (aka 'new')."
  [ctx-atom on-new-ctx-f]
  (let [ctx (swap! ctx-atom tap-ctx)]
   (when (new? ctx)
     (on-new-ctx-f ctx))
   (last-tap ctx)))

(defn mark-done!
  "Marks ctx inside ctx-atom as done and returns the ctx only if the ctx was just
  finished according to `just-finished?`. NOTE you must still dispose! of the ctx
  at some point!"
  [ctx-atom]
  (let [ctx (swap! ctx-atom mark-done)]
    (when (just-finished? ctx)
      ctx)))
