# Incidental bugs found

Pre-existing defects in `main` (cbf9c30) that the screener work surfaced, none of them
about screening. Collected across all four branches — `screen-fable`, `screen-opus4.8`,
`screen-opus5` and `screen-opus5-tainted` — including the excluded one, since bugs it
found are worth keeping even though its implementation isn't being judged.

Every item below was reproduced against `main` in a REPL. Attribution is by which
branch's work exposed the defect, which is not always the branch that noticed it.

| # | Defect | Exposed by | Noticed & fixed by |
|---|---|---|---|
| 1 | `tap-errors` registers nothing | tainted | tainted |
| 2 | `untap-errors` throws on an untapped req-id | tainted | tainted |
| 3 | Warnings indistinguishable from rejections | fable, opus4.8 (both bitten) | tainted only |
| 4 | `req-single!` hangs on a chan that closes empty | fable, opus4.8 (both recommend it) | nobody |
| 5 | A ctx that never finishes wedges all later callers | opus5 | opus5, partially |

Fixes for 1–3 exist in commit 2cdc604. Do not cherry-pick onto `main` until the model
comparison is closed out — changing the baseline the three judged branches were written
against trades one confound for another. Items 4 and 5 have no fix on any branch.

Two of these — 3 and 4 — are the same shape of problem: `main` has a convention that
works only as long as nobody relies on it, and the first three implementations to rely
on it all got hurt. That is worth more attention than any individual fix.


## 1. `tap-errors` never registers anything (`ibkr/client.clj`)

The whole per-req-id error tap mechanism is a silent no-op at any log level above
DEBUG.

```clojure
(defn- tap-errors [req-id]
  (let [c (chan 1)]
    ; TODO WIP - finish implementing & testing
    (->> (swap! error-chan-by-req-id assoc req-id c)
         (count)
         (log/debug "num taps for errors"))
    c))
```

`log/debug` is a macro that only evaluates its arguments when debug logging is
enabled. The `swap!` that performs the registration was threaded in as one of those
arguments, so at the default INFO level it never runs. `tap-errors` returns a live chan
that is not in `error-chan-by-req-id`, so `handle-event :error`'s
`(@error-chan-by-req-id req-id)` lookup is always nil and no error ever reaches the
requester that asked for it.

```
MAIN: after tap-errors, registry = {} -> tap registered? false
```

Nothing in `main` calls `tap-errors`, so there is no visible symptom — the defect only
becomes load-bearing the moment something depends on it, which is what happened on the
tainted branch. It also means the "surface IBKR error codes more cleanly to callers"
roadmap item starts from a mechanism that has never worked, not a partial one.

Note the failure mode, not just the fix: threading a side effect into a logging macro is
invisible in review and behaves differently by log level. Worth grepping for other
`->>`-into-`log/*` shapes.

Fix — bind the `swap!` result, log the count separately:

```clojure
(defn- tap-errors [req-id]
  (let [c (chan 1)
        taps (swap! error-chan-by-req-id assoc req-id c)]
    (log/debug "num taps for errors" (count taps))
    c))
```


## 2. `untap-errors` throws on a req-id that has no tap (`ibkr/client.clj`)

```clojure
(defn- untap-errors [req-id]
  (let [[old _] (swap-vals! error-chan-by-req-id dissoc req-id)]
    (close! (old req-id))))
```

`(old req-id)` is nil whenever the req-id was never tapped, and `close!` on nil throws.

```
MAIN: untap-errors threw java.lang.IllegalArgumentException
      No implementation of method: :close! of protocol: Channel found for class: nil
```

Dormant today only because of bug 1: no req-id ever *has* a tap, so any `untap-errors`
call would throw — but nothing calls it. The two bugs hide each other, and fixing 1
alone converts this from unreachable to reachable on every cleanup-after-error path.

Fix — guard with `when-let`:

```clojure
(defn- untap-errors [req-id]
  (let [[old _] (swap-vals! error-chan-by-req-id dissoc req-id)]
    (when-let [c (old req-id)]
      (close! c))))
```


## 3. Warnings and errors are indistinguishable to anything that acts on `:error`

Not a bug in `main` as written, because nothing in `main` tears down a request in
response to an error. It is a trap laid for the next thing that does — and two of the
three judged branches walked into it on their first attempt.

IBKR reserves codes 2100–2200 for warnings (delayed market data notices and similar) and
delivers them through the same `error` callback as outright rejections. `main`'s
`chatty-error?` covers nine specific market-data-farm codes and only suppresses their
*logging*; everything else in the warning range flows through the `:else` branch of
`handle-event :error` looking exactly like a failure.

Both `screen-fable` and `screen-opus4.8` hung their scan teardown on that `:else`
branch. Reproduced on each branch with code 2137, a routine warning:

```
FABLE   after warning 2137 -> scan still live? false   (a benign notice killed it)
OPUS4.8 after warning 2137 -> scan still live? false
```

`screen-opus5` guards with `(< error-code 2100)` and tests it. The tainted branch
generalised it into a named predicate, which is the version to keep:

```clojure
(defn warning-code?
  "IBKR reserves 2100-2200 for warnings — delayed data notices and the like. Anything
  below that range is a real failure."
  [error-code]
  (<= 2100 error-code 2200))
```

Worth landing on `main` with a note in `doc/IBKRGotchas.md` whichever screener branch
wins, since it's a property of the API rather than of screening. Order lifecycle
tracking and the historical-data 10182 handling already flagged as TODO in `client.clj`
will both need the same distinction.


## 4. `req-single!` hangs the caller when the wrapped chan closes without delivering

`ibkr/tools.clj`:

```clojure
(let [[positions c] (alts! [res-chan timer])]
  (if (= c timer)
    (log/warn "req-single! Timeout, req-f:" req-f)
    (>! out positions))
  (close! out))
```

There are three things `res-chan` can do, and `req-single!` handles two of them. If it
*closes* rather than delivering, `alts!` returns `[nil res-chan]`, the timer branch is
not taken, and `(>! out nil)` throws `Can't put nil on channel` from inside the go
block. The exception escapes to the async thread pool, `(close! out)` never runs, and
the caller waits forever — a hang caused by the exact mechanism that was supposed to
prevent hangs.

```
req-single! over a chan that DELIVERS:            delivered
req-single! over a chan that TIMES OUT:           closed
req-single! over a chan that CLOSES without value: HANGS
    Exception in thread "async-io-1" java.lang.IllegalArgumentException:
    Can't put nil on channel  (tools.clj:78)
```

Nothing in `main` closes a result chan without delivering, so it has never fired. But
"close without delivering" is the natural way to signal a failed request, and it is the
convention both `screen-fable` and `screen-opus4.8` chose — and both then pointed
callers at `req-single!` for timeouts. fable's `req-scanner-data` docstring: "the chan
is closed without delivering (reads as nil). Still consume with a timeout (e.g.
`abroker.ibkr.tools/req-single!`)". opus4.8's REPL block: `(tools/req-single!
req-scanner)`. Following either docstring on a rejected scan hangs the caller.

No branch noticed. `screen-opus5` avoids it only by not using `req-single!` at all
(it built the timeout into `req-scan`), so its own close-without-delivering convention
is safe today and would break the moment anyone wrapped it.

Fix — treat a closed source as a closed result:

```clojure
(let [[res c] (alts! [res-chan timer])]
  (cond
    (= c timer) (log/warn "req-single! Timeout, req-f:" req-f)
    (nil? res)  (log/warn "req-single! request failed, req-f:" req-f)
    :else       (>! out res))
  (close! out))
```

While in there: the `positions` binding name is a leftover from `req-positions` in a fn
that is documented as generic, and `req-single!` returns `nil` (not a closed chan) when
`req-f` returns nil, so `(<!! (req-single! ...))` NPEs against a disconnected client.
All three branches' req fns return nil when not connected.


## 5. A call context that never finishes wedges every later caller (`async-ctx.clj`)

`tap-ctx` re-initialises — and therefore re-issues the underlying service call — only
when the ctx is uninitialised or marked done. If a request is never marked done, the
ctx stays at `::done 0` permanently, and every subsequent caller taps a mult whose
source chan will never receive anything.

```
service calls issued for 2 sequential requests: 1 (expected 2)
```

`handle-event :position-end` is the only thing that marks `position-ctx` done. Miss one
`positionEnd` — disconnect mid-request, a TWS error against the request, anything —
and `req-positions` is dead for the life of the JVM: it stops calling `.reqPositions`
and every caller hangs. Neither `connect!` nor `disconnect!` resets it, and
`req-single!`'s timeout closes the *caller's* chan while leaving the ctx untouched, so
the wrapper that looks like it protects you actually hides the wedge.

`screen-opus5` found this class of bug and wrote the mechanism for it:

```clojure
(defn- timeout-ctx!
  "Disposes a ctx-atom's in-flight call if it hasn't finished within timeout-ms. Without
  this, a request TWS never answers leaves the ctx un-finished forever and every later
  caller taps a chan that can't deliver. The identity check keeps a stale timer from
  disposing a later call's ctx."
  [ctx-atom timeout-ms label] ...)
```

The docstring is an exact statement of the pre-existing bug. But it is applied only to
the branch's own new `scanner-params-ctx` — `position-ctx`, the ctx that has been
exposed since the pattern was introduced, is left as it was. Found and understood,
solved only for the new code.

The real fix belongs in `async-ctx` rather than at each call site: either a timeout
baked into `tap!`, or a `dispose!` that also resets the atom so the next caller
re-initialises. Note `dispose!` currently closes the out-chan without clearing `::done`
or `::taps`, which is why the reset has to be someone else's job today.


## Also worth recording

Two IBKR API facts, not defects in this codebase, from the same work.
`screen-opus5` wrote both into `doc/IBKRGotchas.md` and they belong there whichever
branch merges.

Request ids are not unique across a reconnect. `connect!` reseeds the counter from TWS's
`nextValidId`, which bears no relation to how high the previous session's counter had
climbed, so a req-id can be handed out again after a reconnect. Anything holding a
req-id across time — a timer, a pending cancel, a map of in-flight requests — has to
verify the id still refers to *its* request (identity of the chan or object it created),
not merely that the id is present. This directly affects `error-chan-by-req-id` once
bug 1 is fixed: a tap registered before a reconnect can be matched by an unrelated
request afterwards.

`ScannerSubscription`'s unset numeric fields are `Integer.MAX_VALUE` /
`Double.MAX_VALUE`, not 0 — IBKR's "no value" sentinel. Never test them against zero,
and only call a setter when there is an actual value to set.

Finally, a gap rather than a bug: `ibdata/contract` ignores `:con-id`, so there is no
way to pin an exact contract even though positions and scan rows carry one, and
ambiguous symbols resolve by luck. `screen-opus5` added `:con-id` and
`:primary-exchange` support to `contract` as part of its scan-row round trip; that part
is worth taking regardless of which branch wins.
