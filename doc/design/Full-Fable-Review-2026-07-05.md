# Full Fable Review — 2026-07-05

Review of all staged and unstaged changes: the event-sourced order lifecycle
(order.clj, ibkr/orders.clj, trading.clj), position tracking (position.clj,
ibkr/positions.clj), the EDN file store (store.clj), IBKR data additions
(ibkr/data.clj), five new test namespaces, and the doc/data-model and
doc/design updates. 18 files, ~1,676 insertions.

Verdict up front: the architecture is genuinely good — pure functional core,
imperative shell, clean store protocol, and a well-reasoned design doc. But
the diff contains four verified runtime-breaking bugs that the (all green)
test suite happens to sidestep. None of the order lifecycle path has been
exercised end-to-end; if it had, bugs 1 and 2 would have surfaced immediately.

Findings are numbered by severity. Items 1, 2, and the javap check in 3 were
verified by running code, not just read.


## Critical — broken at runtime, verified

### 1. Orders cannot be loaded back from the store (Instant doesn't round-trip EDN)

`order/make-order` stamps `:created-at` / `:updated-at` with
`java.time.Instant`, and every event carries an Instant `:timestamp`.
`store/write-edn!` serializes with `pr-str`, which renders an Instant as
`#object[java.time.Instant 0x... "..."]` — a form `clojure.edn/read-string`
cannot read.

Verified against the real code:

```clojure
(store/save! s :order (order/make-order {...}))   ; writes fine
(store/load-by-uuid s :order uuid)
;; => RuntimeException: No reader function for tag object
```

Consequence: `trading/send-order!` persists an order that can never be read
again. The first `orderStatus` callback hits `load-by-uuid` in
`ibkr/orders.clj:89` and throws inside the event worker. `events-for` and
`fills-for` fail the same way (event `:timestamp`, fill `:timestamp`). The
entire order lifecycle is dead on arrival at runtime.

Why tests pass: `store_test.clj` uses `java.util.Date` (which prints as
readable `#inst`) and plain maps without timestamps. No test round-trips an
order produced by `make-order`.

Fix options, most idiomatic first:
- Add a `print-method` for `Instant` emitting `#inst "..."` and read with
  edn's built-in `#inst` reader (returns `java.util.Date` by default; supply
  `{:readers {'inst #(Instant/parse ...)}}` or use `java.time` reader fns to
  stay in Instant-land).
- Or convert to/from ISO strings at the store boundary.

Either way, add a store test that round-trips `(order/make-order ...)`
verbatim — that one test would have caught this.

### 2. `cancel-order!` calls a method that doesn't exist

`trading.clj`:

```clojure
(.cancelOrder (ib/client) (:broker-id order) "" false)
```

Verified against the installed TWS API 10.37.02 jar:

```
$ javap com.ib.client.EClient | grep cancelOrder
public synchronized void cancelOrder(int, com.ib.client.OrderCancel);
```

There is no `(int, String, boolean)` overload. The reflective interop call
compiles but throws `IllegalArgumentException: No matching method` at
runtime. Worse, the `:cancel-order` event is persisted *before* this call, so
the order is left stuck in `:pending-cancel` with no cancel ever sent to the
broker. Fix: `(.cancelOrder conn broker-id (com.ib.client.OrderCancel.))`
(check which fields TWS requires on `OrderCancel`), and consider ordering —
see item 10.

### 3. orderRef never carries the order UUID, so reconnect recovery can't work

The recovery path in `ibkr/orders.clj` `:open-order` (line 156) re-indexes
orders by parsing a UUID out of `.orderRef`. `ibdata/order` does set
`(.orderRef (str uuid))` — but `trading/send-order!` passes the *original*
`order-params` to `ib/send-order!`, not the order map `o` that `make-order`
built. Since callers don't supply `:uuid`, orderRef is sent as `(str nil)` =
`""`. The UUID exists only locally; after a restart or reconnect, no open
order can ever be re-associated.

Fix: pass `o` (which has `:uuid`) to `ib/send-order!` instead of
`order-params`. This also removes the duplicated `risk/check` (trading.clj
checks `o`, then `ib/send-order!` checks again — item 12).

### 4. Late broker events on terminal orders persist an error map as the snapshot

`apply-and-persist!` (ibkr/orders.clj:52) handles an invalid transition by
falling back to a `:status-change → :unknown` event. But the `transitions`
table has no entries for terminal states — `:filled`, `:canceled`,
`:rejected` can't transition anywhere, including to `:unknown` (this matches
OrderModel.md: "any *non-terminal* state → unknown"). So when a late or
duplicate broker event arrives on a terminal order, the fallback *also*
returns an error map, and `(store/save! s :order unknown-order)` writes
`{:error :invalid-transition, :from ..., :to ...}` over the order's
snapshot.edn — destroying the order's persisted state (no `:uuid` inside it
either, so subsequent index writes get `nil`).

This isn't exotic. Concrete sequence that triggers it: `orderStatus
"Filled"` arrives before `execDetails` (IBKR makes no ordering guarantee, and
market orders are exactly where this happens) → order goes `:filled` → then
`execDetails` arrives → fill dedup passes (fills.edn is empty) → `:fill`
event on a `:filled` order → invalid → fallback to `:unknown` → also invalid
→ error map persisted.

Fix: in `apply-and-persist!`, if the order is already terminal, log and drop
the event (append it to events.edn for audit if you like, but never touch the
snapshot); only use the `:unknown` fallback for non-terminal states, and
never `save!` a map that has `:error` in it.

### 5. `orderStatus "Filled"` with no recorded fills conjes `nil` into `:fills`

Same handler, the `:fill` branch: when canonical status is `:filled` and
`(:fills order)` is empty, `cmd-type` stays `:fill` but `payload` is `{}` —
the `cond->` only populates payloads for accepted/rejected/status-change. The
`apply-event :fill` method then does `(update :fills conj (get-in event
[:payload :fill]))`, appending `nil`. The order ends `:filled` with `:fills
[nil]`, and that non-empty-but-garbage vector then confuses the later
`(seq (:fills order))` guard and the exec-details flow (feeds item 4).

Given the comment in the code already says "exec-details is the authoritative
fill source," the simplest fix is to *always* treat order-status `Filled` as
a `:status-change` to `:filled` and let exec-details carry fill data — or add
an explicit fill-less `:fill` variant to the FSM. Either way, never conj a
payload that isn't there.


## High — design-level problems

### 6. Duplicate `defmethod ib/handle-event :open-order` in two namespaces

`client.clj:223` defines a no-op `:open-order` handler; `ibkr/orders.clj:153`
defines the recovery handler on the same multimethod and dispatch value.
Which one wins depends on load order. Today orders.clj requires client.clj so
orders.clj wins — but any REPL reload of client.clj silently replaces the
recovery handler with the no-op. Remove the no-op from client.clj (and audit
for other collisions; `:position`/`:position-end` live only in client.clj, so
those are fine).

### 7. Closing a position deletes its event history

`ibkr/positions.clj` `apply-event!`: when quantity hits zero it calls
`store/delete-entity!`, which removes the whole entity dir — *including
events.edn*. DECISIONS.md and EventSourcingVsBrokerTruth.md both stake the
design on an append-only, replayable log ("if the snapshot is lost, it can be
rebuilt from events"), yet the most interesting events — a position closing —
are exactly the ones erased. Reopening the position later mints a fresh
random UUID, so history is also fragmented across UUIDs.

Suggestion: delete only the snapshot (or mark it closed), keep events.edn.
Better: adopt the deterministic UUID the doc already promises (item 8), which
makes the log naturally continuous across open/close/reopen cycles.

### 8. PositionModel.md describes a model the code doesn't implement

Three concrete mismatches:
- Doc: "Deterministic UUID: computed from (account, symbol, type, subtype)
  via UUID v3 (`nameUUIDFromBytes`). Upsert is just save!." Code:
  `(random-uuid)` plus an in-memory `known-positions` map to find the
  previous one. The deterministic UUID is the better design and would remove
  the need for `known-positions` to carry UUID continuity.
- Doc schema says `:snapshot-at <instant>`; code writes `:updated-at` as a
  *string*.
- Doc schema shows a nested `:instrument`; code stores both the nested map
  and the flat `:symbol`/`:type`/`:subtype` keys, duplicating data in every
  snapshot.

Pick one shape and make doc and code agree.

### 9. Fill-sourced position events are designed, documented, tested — and never emitted

EventSourcingVsBrokerTruth.md presents `:source :fill` position events (with
`:order-uuid` traceability) as a core part of the model, `pos/make-event`
supports them, position_test.clj tests them — but nothing calls it. The
`:exec-details` handler updates only the order, never positions. So today the
only position source is sync polling, and the "fills are real-time and
precise, sync is the safety net" story is aspirational. Either wire fills
into position events or mark that section of the doc as future work; right
now the doc reads as a description of current behavior.

### 10. Unsynchronized read-modify-write between user thread and event worker

`trading/cancel-order!` does load → apply → save on the user's thread while
the event worker does the same on broker callbacks. Two interleaved
load/save pairs lose one of the updates (e.g. a `:partial-fill` snapshot
overwritten by the cancel path's stale copy). Same class of race in
`sync-positions!` (`reset!` of `known-positions` after an unguarded reduce).
Pre-alpha acceptable, but worth a decision now: simplest robust option is to
funnel *all* order mutations through the event channel (user commands become
events on the same single-threaded loop), which also matches the design doc's
command/event separation. An `agent` or per-order lock would also do.

Related ordering nit in `cancel-order!`: the pending-cancel event is
persisted before the broker call; if `.cancelOrder` throws (which today it
always does — item 2), local state says pending-cancel but the broker never
heard about it. Persist-then-send is defensible ("we recorded intent"), but
then the failure path needs a compensating event rather than just an
exception.


## Medium

11. Store never initialized ⇒ NPEs, not clear errors. `ib-orders/get-store`
    returns nil if `init-order-tracking!` wasn't called; `trading/send-order!`
    then NPEs inside `store/save!`. `positions/local-positions` same with
    `@store-ref`. `sync-positions!` asserts; do the same (or better) in the
    others.

12. `risk/check` runs twice per order — once in `trading/send-order!`, once
    inside `ib/send-order!`. Harmless but confusing about where the real
    gate is. Falls out naturally when fixing item 3.

13. If `ib/send-order!` throws (not connected, risk), `send-order!` has
    already persisted the order as `:pending` — it's orphaned forever and
    shows up in `active-orders` from then on. Persist after the broker
    accepts the send, or append an `:order-rejected`-style event on failure.

14. `execution->fill` stamps `:timestamp (Instant/now)` instead of the
    execution's own `.time`. Fill times will drift from reality (badly so
    for executions reported after reconnect). Parse `.time` (IBKR gives
    "yyyyMMdd HH:mm:ss zzz") — there's already `ib-datetime-str` machinery
    for the reverse direction.

15. `(+ (ibdata/as-long (.cumQty execution)) 0)` — the `+ 0` is dead code.
    Also `as-long` truncates IBKR's Decimal, so fractional-share fills would
    misreport `cum-qty`; fine for now, worth a comment or `as-decimal`.

16. `diff-snapshots` compares avg-cost with `==` on doubles. IBKR avg-costs
    routinely differ in the last ulp between reports; each wiggle emits a
    spurious `:position` event. Compare with a tolerance or round to the
    instrument's tick.

17. `sync-positions!` returns `nil` on timeout (the `log/warn` branch) vs a
    seq on success, and callers can't tell "timed out" from "flat". Return
    something explicit or throw.

18. Broker order-ids recycle across TWS sessions; the on-disk
    `index/orders/broker-id/<n>.edn` files never expire, so `load-by
    :broker-id` can resolve to an old session's order. The in-memory index
    handles the live path correctly (init only indexes non-terminal orders);
    just don't trust the disk index for anything important, or clean it in
    `maybe-deindex!`.

19. Doc drift between the two design docs: EventSourcingVsBrokerTruth.md
    says commands (`:place-order`) are "recorded for history and audit...
    not applied to state" and names the first event `:order-initiated`; the
    implementation and OrderModel.md instead apply `:submit-order` /
    `:cancel-order` as state-driving events with `:origin :user` and there
    is no `:order-initiated`. The implemented model is fine — update the
    design doc's event/command tables to match it.

20. The broker-side `:pending-cancel` status maps to a `:cancel-order` event
    with `:origin :broker`, which OrderModel.md defines as a user-origin
    event. Cosmetic, but it muddies the audit trail the whole design exists
    to provide.


## Test coverage assessment

The pure cores are well covered: order_test.clj is thorough (transition
table, every event type, three lifecycle paths including fill-during-
pending-cancel), position_test.clj covers diff-snapshots well, and the
positions shell tests with a mocked `req-positions` are a nice pattern.

The gaps line up exactly with the bugs:
- No test round-trips a *real* order (with Instants) through the store —
  masks item 1. store_test.clj's use of `java.util.Date` instead of the
  Instants production code actually writes is the tell.
- No test for the `:order-status` / `:exec-details` handlers in
  ibkr/orders.clj — the most complex, most conditional code in the diff
  (items 4, 5 live there). These are testable today: build a store, seed an
  order, call the defmethod directly with a synthetic event map (Execution
  is a plain Java class you can construct and mutate).
- No test touches `trading.clj` at all — items 2, 3, 11, 12, 13.
- Out-of-order broker callbacks (Filled before execDetails, late duplicates
  after terminal) are the known-hard part of IBKR and have zero coverage.

## What's good

Worth saying explicitly: the functional-core/imperative-shell split is
exactly right — order.clj and position.clj are pure, data-in/data-out, and a
pleasure to test. The Store protocol is a clean seam for the eventual SQL
backend. `diff-snapshots` deriving appear/change/disappear from a single
delta is elegant and correctly documented. The EventSourcingVsBrokerTruth
doc is a genuinely good piece of design writing — the command/event
distinction and "broker sends facts, facts are events" resolution is sound.
The `apply-event` multimethod returning error maps instead of throwing keeps
the core total. And the atomic write-tmp-then-move in the store is the right
instinct for crash safety.

## Suggested fix order

1. Instant serialization (item 1) + a round-trip test of `make-order` output
   — everything else is untestable end-to-end until this works.
2. `cancelOrder` interop (item 2).
3. Pass the order map (with uuid) to `ib/send-order!` (items 3, 12).
4. Terminal-state event handling in `apply-and-persist!` (items 4, 5) with
   handler-level tests for the Filled-before-execDetails race.
5. Remove the no-op `:open-order` defmethod (item 6).
6. Decide: deterministic position UUIDs + keep event logs on close
   (items 7, 8) — then reconcile PositionModel.md.
7. The rest as they annoy you.
