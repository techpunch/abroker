# Events, Commands, and Broker Truth

The central design challenge in abroker's data model.

## The Two Goals

Goal 1: Event-source everything. Every state change is a recorded event (a fact about what happened). Current state is derivable by replaying the event log. This gives us auditability, debugging, time-travel, and the ability to answer "how did we get here?"

Goal 2: Never miss broker-side reality. The broker can do things we didn't ask for — auto-liquidations, position adjustments, order cancellations due to margin calls, corporate actions, dividend reinvestments, overnight risk desk interventions. Our local model must account for all of these, including things we can't anticipate.

## Commands vs. Events

These are distinct concepts that must not be conflated.

Commands are requests — expressions of user intent. "Place this order." "Cancel that order." A command might succeed, fail, or go unanswered. Commands are recorded for audit and history.

Events are facts — things that happened. "Order initiated locally." "Broker accepted the order." "Position changed." Events are the *only* mechanism that mutates state. Every state change, regardless of origin, flows through the event log.

The relationship between them is precise: user-initiated commands that send requests to the broker produce **pending intermediate states** (`:pending`, `:pending-cancel`, `:pending-modify`), because sending the request is itself a fact that happened. Only broker-originated events produce **outcome states** — `:accepted`, `:active`, `:filled`, `:canceled`, `:rejected`, or recovery back to an active state. A user can move an order into a pending state; only the broker can resolve it.

```
User command: "cancel order X"
  → validate
  → apply event :cancel-order → state: :pending-cancel  (fact: we sent the request)
  → persist event + updated snapshot
  → send cancel request to broker
  → ... time passes (market may be closed) ...
Broker callback: "order X is canceled"
  → apply event :order-canceled → state: :canceled       (fact: broker confirmed)
  → persist event + updated snapshot
```

## What We Control vs. What We Don't

### What We Control

Orders we send. Cancel/modify requests we initiate. These produce commands (user intent) that lead to events (what actually happened). The event log for user-initiated orders tells a complete causal story because we receive real-time callbacks for the full lifecycle.

### What We Don't Control

Everything the broker does independently:

- Auto-liquidation: Broker closes positions to meet margin. No command from us — just events.
- Forced cancellation: Broker cancels our order due to margin, risk limits, corporate action, or regulatory halt. We get an event, but the cause wasn't a user command.
- Corporate actions: Stock splits, mergers, ticker changes. Position quantities change, symbols may change, new positions appear.
- Assignment/exercise: Options positions convert to stock positions.
- Dividend reinvestment: New shares appear from DRIP.
- Account transfers: Positions moved between accounts by the broker or FA manager.
- Fractional share adjustments: Rounding from FA group allocations.

All of these are events with no corresponding command. That's fine — events don't require commands. Events are facts; commands are requests.

### The IBKR-Specific Wrinkle

IBKR doesn't stream positions. You poll with `reqPositions`, get a batch of callbacks, then `positionEnd`. Between polls, positions can change without notification.

Orders are better — IBKR pushes `orderStatus` and `execDetails` callbacks in real-time. But even here, there are gaps: market orders can fill without ever emitting a `Filled` status, and `execDetails` is the authoritative source, not `orderStatus`.

---

## The Model

### Order Events

Events are named for what is actually true at the moment they occur:

| Event | Origin | Meaning |
|-------|--------|---------|
| `:order-initiated` | local | We've recorded intent and sent to broker; no confirmation yet |
| `:order-accepted` | broker | Broker acknowledged the order |
| `:order-active` | broker | Live on exchange |
| `:order-partially-filled` | broker | Some quantity executed |
| `:order-filled` | broker | Completely executed |
| `:order-canceled` | broker | Broker confirmed cancellation |
| `:order-rejected` | broker | Broker refused the order |

### User Commands (Recorded Separately)

| Command | Meaning |
|---------|---------|
| `:place-order` | User intent to place an order → leads to `:order-initiated` |
| `:cancel-order` | User intent to cancel → eventually leads to `:order-canceled` (or doesn't) |
| `:modify-order` | User intent to change params → eventually leads to updated state (or doesn't) |

Commands are recorded for history and audit. They are not applied to state. A command might never produce an event (broker ignores it, times out, rejects at network level).

### Broker-Originated Orders

When we see an `openOrder` callback with no matching UUID in `orderRef`, the broker created this order independently (auto-liquidation, FA rebalance, etc.). We adopt it:

- Create a new order entity
- Emit `:order-initiated` with `:origin :broker` (no corresponding user command)
- From there, normal event tracking applies

Same event model, same state machine. The only difference is that there's no user command in the history.

### Position Events

One event type: **`:position`**

```clojure
{:uuid        <uuid>       ; event id (random)
 :account     "U1234"
 :symbol      "AAPL"
 :type        :stock        ; instrument type
 :subtype     nil
 :quantity    100M          ; current quantity
 :avg-cost    150.25        ; current avg cost
 :delta       25M           ; change from previous (prev was 75)
 :source      :fill | :sync
 :order-uuid  <uuid?>       ; present when source is :fill
 :observed-at <instant>}
```

The delta tells the full story:

- New position: delta from 0 (previous quantity was 0 or position didn't exist)
- Size changed: delta is the difference
- Position closed: delta to 0 (current quantity is 0)

No need for `:position-appeared` / `:position-changed` / `:position-disappeared` — these are just interpretations of the delta, not fundamentally different events.

#### Two Sources of Position Events

Position events come from two places:

1. Fills/executions (`:source :fill`). When an order fills, we know the position changed — we know the exact delta, the exact time, and the cause (the order). These events are real-time, causal, and precise. They carry `:order-uuid` so the position change is traceable to the order that caused it.

2. Position sync polling (`:source :sync`). We ask the broker "what do I hold?" and get back the full picture. These events are periodic and observational. The delta is the net change since the last known state. We don't know the cause or exact timing — just that something changed between observations.

Sync events are the **safety net**. They catch everything fills can't: corporate actions, auto-liquidations, assignment/exercise, dividend reinvestment, account transfers, and anything else the broker does independently.

Sync events also serve as **reconciliation**. If fill-derived events say we should hold 200 shares but the broker says 175, the sync event's delta corrects the local state to match. The broker is always right.

In practice, most position changes during normal trading will be covered by fill events (real-time, precise). Sync events will mostly confirm "nothing else changed" — but when they don't, that's exactly the kind of surprise you want to detect.

### Account-Level Data (Future)

NAV, margin, buying power — pure broker-computed values. Same pattern as positions: snapshot events with deltas, no commands.

---

## The Principle

**Events are the only thing that mutates state.**

Both user actions and broker callbacks are recorded as events. User-initiated events produce pending states; broker events produce outcome states. The event log is the complete, replayable history of both.

This resolves the original tension. There's no conflict between "event-source everything" and "capture broker reality" because events can come from anywhere. The broker doesn't need to send commands — it sends facts, and facts are events.

---

## What the Snapshot Is

Each entity still has a materialized snapshot (the current state). The snapshot is a performance optimization — derived entirely from the event log, but cached so we don't replay on every read. If the snapshot is lost, it can be rebuilt from events.

For positions, the snapshot is the latest `:position` event's data. For orders, it's the result of applying all events in sequence through the state machine.

---

## What This Doesn't Solve

- Real-time position streaming. IBKR doesn't offer it. `updatePortfolio` is close but requires an active account subscription and has its own quirks. Future enhancement — but when available, it just means more frequent `:position` events.
- Intra-poll precision. If positions change multiple times between syncs, we only see the net result. The `:position` event is honest about this — it records what we observed, not a complete history of what happened.
- Root cause attribution. When a position changes unexpectedly, we can detect *that* it changed but often can't determine *why*. Cross-referencing with order event history helps (if a liquidation order filled, we can connect the dots), but some changes will remain unexplained.
- Multi-broker consistency. Each broker has different capabilities for position streaming and order event granularity. The event model accommodates this naturally — brokers that stream give us more frequent events, brokers that don't give us fewer.
