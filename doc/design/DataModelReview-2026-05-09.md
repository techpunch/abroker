# Data Model & Code Review — 2026-05-09

Ruthless senior-architect review of the uncommitted `data-model-impl` branch. The core idea (events drive state, two-layer broker abstraction, pure FSM in `order.clj`) is solid. Execution has real holes — some are correctness bugs, some are doc/code drift that will bite as soon as a second broker lands.

Issues are ordered roughly by blast radius.

---

## Architecture & data model

### A1. Doc contradictions on positions — pick one model

Three docs tell three stories:

- `EventSourcingVsBrokerTruth.md` — positions ARE event-sourced, every state change is an event, snapshot is a derived cache.
- `DECISIONS.md` (l.6-10) — same story, "position state is driven by events".
- `PositionModel.md` (l.3, 21) — "snapshot entities, not command-sourced — overwritten on each refresh", "no lifecycle or command log".
- `AppFlowScenarios.md` (l.248) — "previous snapshot is gone — positions are snapshot-replaced, not event-sourced".

The code splits the difference badly: positions append events and maintain snapshots, but on `quantity = 0` the entire entity directory (events + snapshot) is deleted (`positions.clj:21`). So:

- The append on `positions.clj:19` is wasted right before the delete.
- The "position closed" event is erased from history immediately.
- A re-opened symbol gets a brand-new random UUID, losing the link to its prior life.

Decide once: the `EventSourcingVsBrokerTruth.md` model is the right one. Positions are entities with full event logs; closing a position writes a `delta → 0` event and the entity stays around (or is tombstoned but never blow-away-deleted). Update `PositionModel.md` and `AppFlowScenarios.md` to match. Delete the "snapshot-replaced" framing.

### A2. `:cancel-order` and `:submit-order` with `:origin :broker` violate your own model

`EventSourcingVsBrokerTruth.md` is explicit: user-initiated commands produce *pending* states, broker callbacks produce *outcome* states. The whole point is that `:cancel-order` is a record of user intent.

In `ibkr/orders.clj:92-100`, the handler maps IBKR's `PendingCancel` callback to a `:cancel-order` event with `:origin :broker`, and `PendingSubmit` to `:submit-order :origin :broker`. That's exactly the conflation the design rejected. Auditing "did the user request this cancel?" by filtering `:cancel-order` events now requires inspecting `:origin` — and even that lies if the broker independently moved the order to `PendingCancel` (yes, IBKR can do this on margin/risk-desk action).

Fix: introduce broker-origin counterparts (`:order-pending-cancel`, `:order-pending-submit`) for these state observations, OR collapse them all into `:status-change` with `:from`/`:to`. Don't reuse user-command names for broker observations.

### A3. `:fill` semantics not reconciled with order-status fallback

In `ibkr/orders.clj:113-118`, you guard against emitting `:fill` from `:order-status` only if `(seq (:fills order))` already has fills. But order-status doesn't carry fill details (no exec id, no price), so emitting `:fill` from order-status — ever — appends a partial/empty fill to the order's `:fills` vector via `apply-event :fill`. The exec-details callback then appends the same fill again from `(.shares execution)` etc.

Decision: order-status is *never* the source of fill events. Drop the special-case entirely. If status says `Filled` and we have no exec yet, emit `:status-change → :filled` and wait for `:exec-details` to populate fills. Or block the `Filled` status-change until exec-details has caught up. Either way, the current branching is brittle and untested.

### A4. `apply-and-persist!` writes failed events to the log — log is no longer replayable

In `ibkr/orders.clj:60-68`, when a transition is invalid, you append the failing event AND a fallback `:status-change → :unknown`. Replay from scratch will hit the bad event first and fail again — the snapshot you persisted can't be reconstructed from the log.

Either don't append failing events (log + drop, persist a `:rejected-event` audit record separately), or make replay tolerant of the same fallback rule. Currently you have "event log is truth" in design but a non-replayable log in practice.

### A5. Two index sources of truth for orders

`store.clj` already supports `load-by :order :broker-id` via the on-disk index, *and* `ibkr/orders.clj` keeps a separate in-memory `order-index` atom. They serve overlapping purposes. The atom is faster but also duplicates state, has its own init flow, and has to be kept in sync. Either:

- Drop the atom, query the store every time (simpler, slower).
- Keep the atom but treat the store index as canonical — rebuild the atom from the store on init (you already do this), and don't write to the store index for these fields.

Right now you write both, with no contract about which wins on disagreement.

### A6. Index is one-to-one but `:account` on positions is one-to-many — silent bug

`store.clj:129-148`: index is "one file per (entity-type, field, value)" and stores a single uuid. `:position`'s indexed field is `:account`, which is one-to-many. Each `save!` of a position for the same account overwrites the same index file. `(load-by store :position :account "U1234")` returns whichever position was saved last. The tests use `query` (which scans), so the bug isn't caught.

Either remove `:account` from `indexed-fields` for `:position`, or change the index to store sets of uuids and adjust `lookup-index` / `delete-index`.

### A7. Index entry is never removed when the indexed value changes

`store.clj:182-187`: `save!` writes the new index but never deletes the prior index file when, say, `:broker-id` changes. The `store_test` acknowledges this (l.69-70) as "acceptable for the EDN backend". It is not acceptable. `load-by` will return a uuid whose entity no longer has that field value. The fix: on save, read prior snapshot, diff indexed fields, delete stale index entries, then write new.

### A8. No atomicity between event append and snapshot write

The whole event-sourced design assumes you either (a) write event then derive snapshot from log, or (b) atomically write both. Today, `append-event!` then `save!` are two file-system operations. Crash between them and the log and snapshot disagree.

Two acceptable resolutions for an EDN backend:

- Only write the event log. Rebuild the snapshot at read time (with caching). Snapshot becomes a pure cache.
- Wrap event+snapshot in a single tmp-then-move operation per entity (write a new combined file atomically).

For pre-alpha you may live with this, but document it as a known limitation in `DECISIONS.md` so the SQL backend you eventually build doesn't replicate it.

### A9. Position UUIDs are random, not deterministic

`PositionModel.md:22` promises deterministic UUIDs (UUID v3 from `(account, symbol, type, subtype)`). `position.clj:23` uses `random-uuid`. Pick one. Deterministic is genuinely better — it makes the position the same identity across loss/restore, across brokers' API quirks, and across reconcile flows. The implementation is one helper.

### A10. Risk-checked twice per order

`trading/send-order!` calls `risk/check`, then `ib/send-order!` (existing) calls it again. Pick a layer. Risk should live above the broker boundary (i.e., in `trading.clj`), and `ib/send-order!` should be a thin transport.

### A11. Race between IBKR callback and `index-new-order!`

`trading/send-order!`:

1. Save order (no broker-id yet).
2. `(ib/send-order! ...)` blocks until placeOrder returns; IBKR begins streaming callbacks immediately.
3. Save order again with broker-id.
4. `(index-new-order! uuid oid)` — only now is `oid → uuid` mapping live.

Between (2) and (4), a `:order-status` callback can arrive and `resolve-uuid` returns nil. The fallback is the `:open-order` callback which uses `orderRef = uuid`, but that handler also races — and the existing `:open-order` handler in `ibkr/client.clj:223` is an empty body that gets shadowed by the new defmethod in `ibkr/orders.clj:153`. Multimethod redefinition order is load-order dependent; this is fragile.

Fix: reserve the order-id before sending. Have `ib` expose `(reserve-order-id!)`, then in `trading.clj`: reserve → index → save with broker-id → submit. The submit can no longer surprise you.

### A12. `cancel-order!` and broker-event handling diverge in error semantics

`trading/cancel-order!` throws on invalid transitions. `apply-and-persist!` (broker-side) downgrades them to `:unknown`. Both paths apply events to the same FSM — they should share an apply layer. Otherwise the same bad event has two different fates depending on origin.

### A13. `(.cancelOrder (ib/client) ...)` leaks the IBKR layer through `trading.clj`

`trading.clj:58` reaches directly into the IBKR client. Should be `ib/cancel-order!` for parity with `ib/send-order!`. Otherwise the second-broker work will require touching `trading.clj` to special-case Alpaca calls.

### A14. `:submit-order` is a no-op transition — confusing in the log/doc

`order.clj:63-66`: `:submit-order` only updates `:updated-at`; it doesn't transition state because `make-order` already sets `:status :pending`. `OrderModel.md` says `:submit-order → :pending`. So either:

- `make-order` should produce status `nil`, and the `:submit-order` event is what creates `:pending` (preferred — replay from log works).
- Or document that `make-order` IS the `:submit-order` event implicitly, and stop emitting a redundant event.

The former is cleaner and matches "events are the only thing that mutates state".

### A15. Fills stored in two places

`apply-event :fill` updates `(:fills order)` AND `ibkr/orders.clj:149` calls `store/append-fill!` to write to `fills.edn`. Pick a source of truth. Either:

- `:fills` vector on the order is authoritative; drop `append-fill!` / `fills-for` from the protocol.
- `fills.edn` is authoritative; `:fills` on order is rebuilt at load time.

### A16. Naming inconsistency: `abroker.order` vs `abroker.ibkr.orders`

Singular for core, plural for adapter. Pick one (vote: singular — `abroker.ibkr.order` and `abroker.ibkr.position`).

### A17. Module-level state vs. testability

`defonce` atoms in `ibkr/orders.clj` (`order-index`, `store-ref`) and `ibkr/positions.clj` (`store-ref`, `known-positions`). Tests reach in with `@@#'orders/order-index`. Whenever tests need `@@#'`, the design is leaking.

Cleaner pattern: a `Tracker` record/component holding `{:store, :index}`, threaded through. `trading.clj` takes it as arg. Standard Clojure component lifecycle (Integrant or hand-rolled). This also kills the "init order matters" problem in REPL workflows.

---

## Specific code quality issues

### C1. `position.clj:48-50` rebuilds map from key indices

```clojure
(or new-pos
    {:account (k 0) :symbol (k 1) :type (k 2) :subtype (k 3)
     :quantity 0M :avg-cost 0.0})
```

Brittle to changes in `position-key`. Either define a `key->stub` helper next to `position-key`, or model the missing case explicitly.

### C2. `position.clj:26` — `:observed-at` is a string; everywhere else `:created-at` is an Instant

Mixed timestamp types in the same store. EDN doesn't print `Instant` literally — fix the EDN reader/writer with tagged literals (`#inst`) or use ISO strings consistently. Don't mix.

### C3. `ibkr/orders.clj:144` — dead `+ 0`

`cum-qty (+ (ibdata/as-long (.cumQty execution)) 0)` — vestigial. Delete.

### C4. `ibkr/orders.clj:82-127` — the `:order-status` handler is too dense

The `cmd-type` and `payload` are rebound twice with conditional logic. Extract `derive-event` as a pure function `(canonical, order, order-id, perm-id, why-held) → event`. Then test it directly. Right now the entire event-derivation is impossible to unit-test without the whole stack.

### C5. `(get-in idx [:by-order-id order-id])` style throughout

Per `~/.claude/rules/clojure.md`: prefer `((:by-order-id idx) order-id)` or `(get-in idx [...])` is fine when nested, but flat 2-deep maps are usually cleaner with keyword-of-map or destructuring. Less critical, but inconsistent across the file.

### C6. `position.clj:21-27` — `cond->` for optional `:order-uuid`

```clojure
(-> (select-keys pos ...)
    (assoc :uuid ... :delta ... :source ... :observed-at ...)
    (cond-> order-uuid (assoc :order-uuid order-uuid)))
```

Fine, but worth inspecting whether `order-uuid` is always meaningful and just include `nil` (empty maps clean up downstream).

### C7. `store.clj:191-196` — silent fallback scan is dangerous

`load-by` falls back to a full scan if no index file exists. That's a quiet O(n) operation that hides slowness. At minimum, log a warning. Better: make `load-by` fail loudly if the field isn't in `indexed-fields`, and require callers to use `query` for unindexed fields.

### C8. `ibkr/positions.clj` `apply-event!` mutates known-positions but has the *appearance* of being pure

It's a private `defn-` taking `known` and returning a new map, which suggests purity, but it does IO (`store/append-event!`, `store/save!`, `store/delete-entity!`). Rename or restructure: pure `apply-event` (returns events to apply + new known) + imperative `persist!` step. This separation also helps testing.

### C9. `ibkr/positions.clj:24` — drops `:subtype` from instrument when nil

```clojure
instrument (-> (select-keys event [:type :symbol :subtype])
               (cond-> (nil? (:subtype event)) (dissoc :subtype)))
```

But `select-keys` already includes the `nil` value for `:subtype`. Then `cond->` removes the key entirely if it's nil. Why? Inconsistent with `position.clj` which keeps `nil` subtype. Pick one shape.

### C10. `ibkr/data.clj:198-199` — `get` instead of map-as-fn

```clojure
(defn ibkr-status [s] (get ibkr-status-map s :unknown))
```

Per the rule: `(ibkr-status-map s :unknown)` works because the map is callable.

### C11. `order.clj:17-22` — terminal states implicit in transitions table

`(get transitions :filled)` returns nil; `(contains? nil x)` is false, blocking transitions out of terminal states. Currently fine but implicit. Make it explicit: include `:filled #{}`, `:canceled #{}`, `:rejected #{}` so the table is exhaustive and self-documenting.

---

## Test gaps

### T1. `ibkr/orders_test.clj` only covers helpers

The actual `:order-status`, `:exec-details`, `:open-order` defmethod logic is not tested. Given the complexity in C4, this is the highest-leverage test gap. Mock the store, don't go through atoms, test event derivation directly.

### T2. No tests for the `apply-and-persist!` "transition to :unknown" fallback

A.k.a. A4. Adding a test would force you to confront the replayability problem.

### T3. No test for the race in A11

At minimum, a test where `:order-status` arrives before `index-new-order!` to prove the recovery path works (or doesn't).

### T4. No test for store atomicity / index staleness (A7)

The `store_test` even acknowledges the bug in a comment instead of fixing it.

### T5. No test for `query :position {:account ...}` returning the right count via the index path

The integration test uses `query` (which scans), only working *because* the account index is unused there. A direct test of `load-by :position :account` would catch A6.

### T6. Position event log durability when position closes

Test that after `delta → 0`, the events are still queryable — currently they're not (A1), and that test would force the design clarification.

---

## Doc gaps

### D1. `PositionModel.md` is 29 lines; `OrderModel.md` is 187. Position model is undercooked.

Missing entirely: position event types, lifecycle states (none yet — should there be?), corp-action handling, account-level data, options chain identity (expiry/strike, which the doc itself flags as a known limitation), reconciliation rules in code terms. Bring it to parity with `OrderModel.md`.

### D2. `EventSourcingVsBrokerTruth.md` is good but standalone

Reference it from each entity model doc. It's the contract; individual model docs should not redefine the principle.

### D3. `AppFlowScenarios.md` mentions `modify-order!` (l.174) as future

It also describes scenarios involving `:pending-modify` that the tests cover. Make clear which scenarios are live vs. planned. Right now it reads as if all 8 work end-to-end.

### D4. `DECISIONS.md` entry on positions repeats `EventSourcingVsBrokerTruth.md`

Keep `DECISIONS.md` as a one-paragraph pointer; full rationale lives in the design doc. Avoid drift.

---

## Priority order

1. Fix the position event log destruction (A1, A6, T6) — pick the model and align doc + code in one PR.
2. Fix the `:cancel-order :origin :broker` semantic violation (A2) — much harder to undo after Alpaca lands.
3. Reserve order-id before submit (A11) — eliminates a real race that will manifest under load.
4. Decide fills source-of-truth (A15) and remove the redundant path.
5. Make `apply-event :submit-order` actually transition to `:pending` (A14) so the log replays.
6. Extract `derive-event` from the `:order-status` handler (C4) and test it (T1).
7. Fix index staleness on save (A7) and `:account` index for positions (A6).
8. Replace module-level atoms with a `Tracker` record threaded explicitly (A17).

---

## Verdict

The bones are good. The data-model docs in particular are substantive and well thought-through. But there's enough drift between `EventSourcingVsBrokerTruth.md`, `PositionModel.md`, and the actual position code that it's not yet a foundation you can build on without paying for cleanup later. Lock the contract first, then the second broker will be merciful.
