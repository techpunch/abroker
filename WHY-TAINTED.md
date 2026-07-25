# Why this branch is tainted

This branch (`screen-opus5-tainted`, commit 2cdc604) holds an IBKR screener
implementation written by Opus 5 as part of a three-model comparison alongside
`screen-fable` and `screen-opus4.8`. It should not be used as a data point in that
comparison, because the session that produced it read another model's answer first.

## What happened

The session started with the branch checked out at `aa81de7` — the tip of what is now
`screen-opus4.8` — rather than at `main`. Finding the feature already implemented in its
own working tree, the session ran `git show aa81de7` and read that entire diff, including
its tests, before deciding to reset to `main` and write its own version. The reset made
the git history look independent. The reading had already happened.

The resulting code was then described as "built from scratch", which was wrong.

## How to see it in the diff

Convergence on overall shape (a `scans` atom keyed by req-id, an atomic take-once helper,
the two `handle-event` methods, deliver-then-cancel as a one-shot) is weak evidence — the
codebase's existing `req-positions` already suggests most of it, and `screen-fable`
arrived at some of the same structure independently.

The specific tells are naming: this branch's alias resolver is called `scan-str`, the same
name `screen-opus4.8` used, with the same stated rationale about catching typos before
they reach TWS. Its test names (`scanner-subscription-defaults`, `scan-filters-fn`,
`scan-result-fn`) are near-verbatim that branch's. Compare with `screen-fable`, which
diverges from `screen-opus4.8` on nearly every axis — mechanical code transform instead of
alias maps, kwargs instead of a map argument, `async-ctx` instead of a bespoke waiter atom,
different defaults, tests in a different file. That is what two independent answers look
like.

There is a second, unrelated reason not to compare this branch: it received a review pass
and a commit round, while the other two branches got a single shot at the prompt. Two of
the three findings below came out of that review, not the initial implementation.

## The bug this run surfaced, which is worth keeping

`tap-errors` in `ibkr/client.clj` had never worked. It was marked "TODO WIP" and read:

```clojure
(defn- tap-errors [req-id]
  (let [c (chan 1)]
    (->> (swap! error-chan-by-req-id assoc req-id c)
         (count)
         (log/debug "num taps for errors"))
    c))
```

`log/debug` is a macro that only evaluates its arguments when debug logging is enabled.
The `swap!` registering the tap was threaded into it as an argument, so at INFO level it
never ran. `error-chan-by-req-id` stayed empty permanently, every error tap was a silent
no-op, and `untap-errors` had nothing to close. Nothing else called these functions, so
the defect had no visible symptom until the screener depended on it.

The fix is to bind the `swap!` result in the `let` and log the count separately. It is in
commit 2cdc604; extract it with `git show 2cdc604 -- src/abroker/ibkr/client.clj` and take
the `tap-errors` hunk.

Two smaller findings from the same commit, both in `client.clj`:

`untap-errors` called `close!` on nil for any req-id that had no tap. Guarded with
`when-let`. This was dormant only because the bug above meant no req-id ever had a tap.

Errors and warnings arrive through the same `error` callback, and IBKR reserves codes
2100–2200 for warnings — a "displaying delayed market data" notice looks exactly like a
rejection. Anything that tears a request down in response to an error needs to check the
range first, or a working request dies on a notice. Added as `warning-code?` next to the
existing `chatty-error?`.

## What to do with this branch

Keep it for now. Do not merge it, and do not cherry-pick the `tap-errors` fix onto `main`
until the model comparison is finished — changing `main` changes the baseline that
`screen-fable` and `screen-opus4.8` were written against, which would trade one confound
for another.

The clean Opus 5 rerun lives in a separate single-branch clone at `../abroker-opus5`, with
no remote and no sibling branches visible.
