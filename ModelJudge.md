# Model Judge: three screener implementations

Three branches implement "a screen" (IBKR market scanner) against the same `main`
(cbf9c30), each from a single shot at the prompt. This is a review of what each one
built and what it got right or wrong, by an architect who has to live with the result.

`screen-opus5-tainted` is excluded — its session read `screen-opus4.8`'s diff before
writing, and it also got a review-and-fix round the others didn't. Bugs it found are
kept in [IncidentalBugsFound.md](IncidentalBugsFound.md), along with bugs the three
judged branches exposed.

All three branches were checked out into worktrees and run with `clj -M:test`. All are
green; every claim below about runtime behavior was reproduced in a REPL against the
branch, not inferred from reading.

| | screen-fable | screen-opus4.8 | screen-opus5 |
|---|---|---|---|
| lines added | 215 | 278 | 1050 |
| test lines | 41 | 79 | 319 |
| doc lines | 0 | 0 | 170 |
| tests | 4, all pure | 4, all pure | 12, incl. event flow |
| broker-agnostic surface | `trading/screen` passthrough | none | `screen.clj` + `trading/scan` |
| files touched | 4 | 3 | 15 |
| survives disconnect | no | no | yes |
| survives a TWS warning | no | no | yes |
| request timeout | no | no | yes |
| streaming mode | no | no | yes |


## Verdict

Ranking: **opus5 > fable > opus4.8**.

`screen-opus5` is the one to merge, and it isn't close on correctness or on fit with
this codebase. `screen-fable` is the better of the two small ones: less code than
opus4.8, one genuinely better concurrency primitive, and the only branch besides opus5
to put anything on the broker-agnostic layer. `screen-opus4.8` has the nicest scan-result
shape, the sharpest use of core library functions, and the most careful
`reqScannerParameters` handling — and also the most defects and the least architecture.

Scored against the criteria below:

| criterion | fable | opus4.8 | opus5 |
|---|---|---|---|
| correctness / lifecycle | poor | poor | strong |
| architectural soundness | weak | absent | strong |
| clarity & simplicity | strong | good | mixed — clear but large |
| idiomatic Clojure | good | strong | strong |
| non-idiomatic lapses | one notable | few, minor | few, minor |
| tests | thin | thin but well-written | the only real ones |
| epistemic honesty | partial | none | strong |

The gap is not mostly about volume. Strip the docs and the streaming mode out of opus5
and it still wins, because it is the only branch that asked "what ends a scan?" and
answered it more than once.


## The shared problem, and where they diverge

An IBKR scanner subscription is not request/response. TWS sends one `scannerData`
callback per row, then `scannerDataEnd`, then keeps pushing re-ranked snapshots until
canceled — and only ~10 subscriptions may be live at once. So every implementation has
to decide: how does a scan end, and who owns the cancel?

All three reached the same core answer independently: an atom of in-flight scans keyed
by req-id, accumulate on `:scanner-data`, deliver and cancel on `:scanner-data-end`.
That much is over-determined by the existing `req-positions` code and earns nobody
credit. The interesting question is the rest of the lifecycle, and there they separate
completely.


## Criterion: correctness (lifecycle coverage)

A scan can stop being meaningful in four ways. Which does each branch handle?

| | own `scannerDataEnd` | TWS error on its req-id | timeout | connection closed |
|---|---|---|---|---|
| fable | yes | yes, but also on warnings | no | no |
| opus4.8 | yes | yes, but also on warnings | no | no |
| opus5 | yes | yes, warnings excluded | yes (15s default) | yes |

Two failures, both reproduced in a REPL.

Disconnect leaks the scan and hangs the caller — fable and opus4.8 both:

```
FABLE   after connection-closed -> entry live? true | caller: HANGS
OPUS4.8 after connection-closed -> entry live? true | caller: HANGS
```

The subscription died with the socket, so the row batch is never coming; the caller's
chan is never closed and the entry sits in the scans atom forever. In a library whose
headline reliability feature is "reconnect with exponential backoff", a screener that
silently dies on the first reconnect is the wrong failure. opus5 hooks `end-scans!` into
both `:connection-closed` and `disconnect!` — the latter placed *before* `.eDisconnect`
so TWS still receives the cancels — and tests it.

A benign TWS warning kills a healthy scan — fable and opus4.8 both:

```
FABLE   after warning 2137 -> scan still live? false
OPUS4.8 after warning 2137 -> scan still live? false
```

IBKR reserves 2100–2200 for warnings and delivers them through the same `error` callback
as rejections; `main`'s `chatty-error?` list covers only nine specific codes, so
everything else in the range falls through to the new teardown hook. See
IncidentalBugsFound #3. opus5 guards with `(< error-code 2100)` and has a test named for
exactly this case.

Neither failure needs a live TWS to find. Both are reachable from the event-flow tests
that fable and opus4.8 chose not to write.

There is a third, quieter one: fable and opus4.8 both delegate timeouts to
`tools/req-single!`, which hangs on a chan that closes without delivering — the exact
failure convention both of them chose. Neither noticed. Details in
IncidentalBugsFound #4.

Where fable is better than opus5: fable is the only branch whose row accumulation is
atomic.

```clojure
;; fable — atomic
(swap! scan-by-req-id
       (fn [scans]
         (cond-> scans
           (scans req-id) (update-in [req-id :results] conj result))))

;; opus5 — check-then-act
(when (@scans req-id)
  (swap! scans update-in [req-id :rows] conj (ibdata/scan-row event)))
```

If a teardown lands between opus5's deref and its swap, the swap resurrects the entry as
`{req-id {:rows [row]}}` — no `:out`, no `:screen`. It never delivers, never gets cleaned
up, shows as `{req-id nil}` in `live-scans`, and counts toward the max-scans warning.
opus4.8 has the same pattern and gets away with it by accident, having no off-thread
teardown at all. opus5 does not: its timeout go-block, `cancel-scan` and `disconnect!`
all run off the event worker, so the race its comment dismisses ("dispatched serially by
the single event worker, so … needs no further coordination") is exactly the race its own
new features opened. Correct about the event worker, wrong about the conclusion. fable
had the right instinct.


## Criterion: architectural soundness

The first thing CLAUDE.md describes is the two-layer design, and it is the reason the
project exists. It already has a worked example: 11 IBKR order statuses map onto 6
canonical ones, core owns the vocabulary, the adapter owns the translation. A screener is
the same problem.

**opus5 — strong.** `screen.clj` defines a broker-agnostic screen map with a canonical
scan-code vocabulary (`:top-gainers`, `:unusual-volume`) that `ibkr/codes.clj` maps to
IBKR's spellings, exactly mirroring the order-status precedent. Three tiers are made
explicit and argued in DECISIONS.md: canonical keywords are mapped; unknown keywords
convert mechanically so IBKR's own vocabulary works unchanged; raw strings pass through
so no choice here can block a caller. Location, instrument and stock-type keywords go
through curated maps and *throw* on an unknown one, because TWS answers a bad code with
an empty scan rather than an error. That asymmetry is deliberate and defended: IBKR has
hundreds of scan codes and adds more, so a whitelist would be stale within a release,
while locations are a small irregular set that can't be derived. This is the one design
decision on any branch that a second broker adapter will actually depend on.

The one-shot/streaming split is also the right call. A subscription with no natural end,
capped at ten, where forgetting to cancel silently burns a slot — making the safe reading
the default and handing `req-scan-stream` callers the req-id precisely because they now
own the cancel is good API design, and DECISIONS.md says so in those terms.

**fable — weak.** `trading/screen` exists, which is more than opus4.8 managed, but it is
a one-line passthrough that forwards a spec whose `:scan-code` is `:top-perc-gain` —
IBKR's word, transliterated. IBKR's vocabulary is now the broker-agnostic vocabulary.
Adding Alpaca means either teaching it IBKR's spellings or breaking every caller. The
`scanner-code` mechanical transform is a fine adapter-level helper that has been quietly
promoted into the public API.

**opus4.8 — absent.** `trading.clj` untouched, `default-scan` living in `ibkr/data.clj`,
no `abroker.*` entry point at all. This branch built only the bottom layer. Its alias
maps are the largest and most useful of the three — and they are in the adapter, which is
the right place for them, but there is nothing above.

Defaults are an architecture question too, and all three treated them differently. fable
defaults to `:above-volume 500000`; `ScannerSubscription.aboveVolume` is volume traded
*so far today*, so that default returns nothing pre-market and progressively more as the
session runs — the same screen gives different answers at 9:32 and 15:32 for reasons
unrelated to the market. A default that silently empties the result set is the worst
kind. fable also defaults `:market-cap-above 500e6`, whose units are ambiguous in IBKR's
own docs. opus5 hit the same trap, reasoned its way out, and wrote down why ("defaults
should remove results that are never actionable, not results that are merely
uninteresting"), keeping the price floor and pushing volume floors into presets where the
tradeoff is visible. opus4.8 defaults to price/volume-free and dodges it without comment.


## Criterion: clarity & simplicity

**fable — strongest per line, and the closest to the existing house style.** Same comment
voice, same `(comment …)` REPL blocks at the end of each section, tests added to the
existing `data_test.clj` rather than a new file, `req-scanner-data` / `cancel-scanner-data`
named to match the `req-mkt-data` / `cancel-mkt-data` pairs right above them. It reads
like the person who wrote `req-positions` wrote it. 215 lines, one new concept
(`scan-by-req-id`), no new namespace. If you only read the diff, this is the one you
understand fastest.

**opus4.8 — good, with one structural oddity.** Clean separation of the scanner section
and the scanner-parameters section, each with its own comment header explaining the TWS
behaviour it's working around. `default-scan` at the data layer is easy to find. The
oddity is `scan-str`, a validating alias resolver applied to two of the three fields that
need it — the inconsistency costs more clarity than the helper buys.

**opus5 — clear, but there is a lot of it.** Every individual piece is legible and the
documentation genuinely helps: ScreenModel.md matches the shape of the existing
data-model docs, the four DECISIONS entries preserve reasoning that would otherwise be
lost, and the IBKRGotchas additions are exactly the kind of thing this project keeps to
avoid re-researching. But 5x the code for the same core feature is a real cost, and two
specific things are more complex than they need to be:

- The DSL is ~15 one-line setters that assoc or dissoc a single key. `(-> (screen
  :top-gainers) (price-above 10) (rows 25))` versus `{:scan-code :top-gainers :price-above
  10 :rows 25}` — the map is shorter and needs no API to learn. The justification is
  consistency with the existing order DSL in `abroker.data`, which is a real argument,
  but orders have interactions between fields and screens mostly don't.
- Two separate timeout mechanisms in one namespace: `timeout-ctx!` for the ctx-based
  scanner-params request, and a hand-rolled go-block inside `req-scan`. Both do
  identity-checked disposal after a delay. One of them should have been the other.

And one genuinely surprising piece of behaviour: `screen/instrument` clears `:location`
as a side effect when you move off stocks. It is documented in the docstring, in
ScreenModel.md, and tested — but a setter that mutates a *different* key is the kind of
thing that costs someone an afternoon, and the DSL's own "nil clears" convention would
have let the caller do it explicitly.

Also worth noting: opus5 did more than the prompt asked. Streaming mode is real work
nobody requested. A reviewer who wanted a scanner and got a subsystem is entitled to be
annoyed, even if the subsystem is good.


## Criterion: idiomatic Clojure

All three are written by someone who knows the language. Specifics:

**fable.** The `cond->`-inside-`swap!` for a conditional atomic update is the best single
line of Clojure on any of the three branches — it is the correct idiom for "update only if
still present", and neither other branch found it. `swap-vals!` for take-once semantics
matches the existing `untap-errors`. `(seq distance)` as a blank check inside `cond->` is
right. Kwargs via `[& {:as scan-spec}]` matches `connect!`'s existing signature style and
is current 1.11+ practice.

**opus4.8.** The most fluent use of the standard library. `reset-vals!` for the
superseded-waiter swap is a precise reach for a lesser-known core fn and is exactly the
right one. `not-empty` for `""` → `nil` normalization is more idiomatic than the
`(seq …)` and `str/blank?` variants the other two used. `ex-info` carrying `{:value x
:known (vec (sort (keys m)))}` is textbook error data — the exception tells you what you
passed *and* what was available. `thrown-with-msg?` in the tests rather than bare
`thrown?`.

**opus5.** `update-vals` in `live-scans` (1.11, exactly right). A transducer arity in
`(into {} (remove …) m)`. `run!` for side effects across keys. `identical?` for chan
identity checks — correct, subtle, and the reason its timers can't cancel a healthy scan
that inherited a recycled req-id. `sliding-buffer 1` for a snapshot stream is the right
primitive for "latest wins, never block the producer". It also reuses the project's own
`techpunch.util/valid-arg` and `throw-illegal-arg` rather than reaching for raw `ex-info`,
matching what `data.clj` already does — the only branch that checked what the codebase
already had for argument validation.


## Criterion: non-idiomatic or poor Clojure

**fable — one notable lapse.** `tag-values` builds a `java.util.ArrayList` by imperative
accumulation:

```clojure
(let [l (java.util.ArrayList.)]
  (doseq [[k v] m]
    (.add l (TagValue. (name k) (str v))))
  l)
```

A Clojure vector already implements `java.util.List`, so `(mapv (fn [[k v]] (TagValue.
(name k) (str v))) m)` is a drop-in replacement — which is precisely what both other
branches wrote. This is the least Clojure-y code on any of the three branches, and it is
mutable state escaping a pure conversion function for no reason.

Second, smaller: `trading/screen` is `[& {:as scan-spec}]` and then calls
`(ib/req-scanner-data scan-spec)` — passing a map positionally into another kwargs fn. It
works (verified on 1.12; the trailing-map affordance covers it, and the no-arg case
degrades to nil which `merge` handles), but it depends on a subtlety at both ends of the
call. Either both take maps or the forward uses `apply`.

**opus4.8 — few, all minor.** `scan-str`'s `[what m x]` argument order is arbitrary — the
alias map wedged between a label and the value. `(sort-by :rank results)` is put on a
channel where the docstring promises a vector; laziness crossing a channel boundary is a
smell even when, as here, it's harmless over a small realized vector. `(int rows)` with no
nil guard, so `{:rows nil}` NPEs. And the real one, less about idiom than discipline:

```
(ibdata/scanner-subscription {:instrument :stk})
!! java.lang.ClassCastException  clojure.lang.Keyword incompatible with java.lang.String
```

That is verbatim the exception `scan-str`'s own docstring says it exists to prevent
("catching typos here rather than as a downstream ClassCastException in the setter"),
in the one field where `:stk` is the obvious thing to type. There is no instrument alias
map at all.

**opus5 — few, all minor.** The check-then-act on the scans atom, covered above, is the
substantive one. `code-of` puts its `(nil? x)` branch *after* the map lookup in a `cond`,
which works but reads backwards. `client.clj` requires `abroker.screen` solely for the
`(comment …)` block — harmless direction of dependency (adapter → core), but a
load-bearing require for nothing. `timeout-ctx!` reaches into a ctx's internals from
outside the `async-ctx` namespace (`(ctx/out-chan @ctx-atom)` on the atom's *value*,
rather than through a ctx-atom-level API), which is a slightly leaky use of that
abstraction — the ctx-atom layer exists precisely to hide that. And a `println` inside
`deftest scan-errors` to explain expected log noise, which does match an existing habit in
`tools_test.clj`, so consistent rather than wrong.


## Criterion: tests

fable and opus4.8 both tested only pure conversion functions. Real tests, well
constructed — opus4.8's `scan-result-fn` asserts the whole map in one comparison rather
than field by field, which is the better style, and its typo case uses
`thrown-with-msg?`. But they are tests of the part that was never going to break. Nothing
either branch tested would have caught either of the two defects both branches shipped.

opus5's `client_test.clj` drives `handle-event` the way the event worker does, with no TWS
connection, and covers one-shot delivery, streaming with snapshot reset, error teardown,
warning tolerance, disconnect teardown, and stray events for unknown req-ids. That harness
is worth more than the screener: it is the first test in this repo that exercises event
flow at all, and it generalizes directly to the order-lifecycle work sitting in ROADMAP's
near-term list.


## Criterion: epistemic honesty

Every one of these is written against IBKR docs, not a live TWS. Every scan code,
location code and filter tag on all three branches is unverified.

opus5 is the only branch that says so — a ROADMAP item ("verify scan codes, filter tags
and market-cap units against live TWS") and a gotchas note that `marketCapAbove`'s units
are ambiguous in IBKR's own documentation. fable is partially honest: its docstrings flag
that scans can hang outside market hours and tell callers to use a timeout, though the
timeout it recommends is broken. opus4.8 presents its alias maps with no indication of
which entries were verified and which were inferred.

Reviewing the other two, I can't tell what was checked. That is a cost paid later, by
someone else, at a market open.


## If you merge opus5

- Fix `:scanner-data` accumulation to fable's atomic `cond->`-inside-`swap!`.
- Collapse `timeout-ctx!` and `req-scan`'s inline timeout into one mechanism, and move
  the ctx-internals access into `async-ctx` where it belongs.
- Drop the `abroker.screen` require from `client.clj`, or move the comment block.
- Take opus4.8's `industry` / `category` into `scan-row`, and its `not-empty` over
  `prune`'s `str/blank?` check.
- Reconsider `screen/instrument`'s implicit `:location` clear — an explicit
  `(location nil)` is less surprising.
- Do the ROADMAP item before trusting any of it live: verify scan codes, location codes,
  filter tag names and market-cap units against a real TWS.
- Fix `tools/req-single!` (IncidentalBugsFound #4) before anything else in this repo
  adopts close-without-delivering as a failure signal — opus5's screener already has.
- The `client_test.clj` event-driving harness generalizes; use it for order lifecycle.
