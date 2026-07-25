# App Flow Scenarios

Concrete walkthroughs of common and edge-case flows. Each scenario shows who acts, what function is called, what events are recorded, and how each object's state changes at every step.

For state machine rules see OrderModel.md. For the commands-vs-events design rationale see EventSourcingVsBrokerTruth.md.

---

## Scenario 1: Market order, immediate fill

The simplest possible flow. User places a market buy, broker fills it within seconds.

Objects involved: 1 Order

```
Step 1 — User calls send-order!
  Action:   trading/send-order! called with :type :mkt
  Created:  Order {:status :pending, :fills []}
  Event:    {:type :submit-order, :origin :user}
  Store:    order snapshot saved, event appended

Step 2 — IBKR acknowledges receipt
  Callback: :order-status (PreSubmitted)
  Event:    {:type :order-accepted, :origin :broker, :payload {:broker-id 42}}
  Order:    :pending → :accepted
            :broker-id set to 42

Step 3 — IBKR routes to exchange
  Callback: :order-status (Submitted)
  Event:    {:type :order-active, :origin :broker}
  Order:    :accepted → :active

Step 4 — IBKR reports execution
  Callback: :exec-details
  Event:    {:type :fill, :origin :broker,
             :payload {:fill {:quantity 100, :price 150.25, :broker-exec-id "E1"}}}
  Order:    :active → :filled
            :fills conj'd with fill map
            :closed-at set

Final state:
  Order: {:status :filled, :fills [{:quantity 100, :price 150.25}], :closed-at <t>}
```

---

## Scenario 2: Limit order, user cancels while market is closed

User places a GTC limit order after hours, then cancels it the same evening. Market is closed so the cancel request can't be confirmed until the next morning.

Objects involved: 1 Order

```
Step 1 — User places the order (4:30pm, market closed)
  Action:   trading/send-order! with :type :lmt, :tif :gtc, :limit-price 148.00
  Created:  Order {:status :pending, :fills []}
  Event:    {:type :submit-order, :origin :user}

Step 2 — IBKR queues the order for next session
  Callback: :order-status (PreSubmitted, then Submitted)
  Events:   :order-accepted → :order-active
  Order:    :pending → :accepted → :active

Step 3 — User cancels (6:00pm, market still closed)
  Action:   trading/cancel-order! called
  Check:    order not terminal, not already :pending-cancel
  Event:    {:type :cancel-order, :origin :user}
  Order:    :active → :pending-cancel
            :updated-at set to now (this is the "cancel requested at" timestamp)
  API:      .cancelOrder sent to IBKR

  [Hours pass. Market is closed. IBKR queues the cancel for next open.]

Step 4 — User calls cancel-order! again (forgets they already canceled)
  Action:   trading/cancel-order! called
  Check:    status is :pending-cancel
  Result:   throws ex-info "Cancel already pending"
            {:cause :already-pending-cancel, :since <updated-at from step 3>}

  [Next morning, market opens]

Step 5 — IBKR confirms cancellation (9:30am)
  Callback: :order-status (Cancelled)
  Event:    {:type :order-canceled, :origin :broker}
  Order:    :pending-cancel → :canceled

Final state:
  Order: {:status :canceled, :fills []}
  Event log: [:submit-order, :order-accepted, :order-active, :cancel-order, :order-canceled]
```

---

## Scenario 3: Limit order, partially fills, user cancels remainder

User places a limit order for 100 shares. 60 fill at limit. User then cancels the remaining 40.

Objects involved: 1 Order

```
Step 1–3 — Order placed and becomes active
  (same as Scenario 1 steps 1–3)
  Order: :active

Step 4 — 60 shares fill
  Callback: :exec-details (60 shares at 148.00)
  Event:    {:type :partial-fill, :origin :broker,
             :payload {:fill {:quantity 60, :price 148.00}}}
  Order:    :active → :partially-filled
            :fills [{:quantity 60, :price 148.00}]

Step 5 — User cancels the remaining 40
  Action:   trading/cancel-order!
  Event:    {:type :cancel-order, :origin :user}
  Order:    :partially-filled → :pending-cancel

Step 6 — IBKR confirms cancellation of remainder
  Callback: :order-status (Cancelled)
  Event:    {:type :order-canceled, :origin :broker}
  Order:    :pending-cancel → :canceled

Final state:
  Order: {:status :canceled, :fills [{:quantity 60, :price 148.00}]}

Note: :canceled with fills means partial execution. The 60 shares are now a position.
      This is normal — :canceled means no more activity on this order, not that nothing happened.
```

---

## Scenario 4: Limit order, cancel races with fill

User cancels a limit order, but it fills before the cancel reaches the exchange.

Objects involved: 1 Order

```
Steps 1–3 — Order placed and becomes active
  Order: :active

Step 4 — User issues cancel
  Action:   trading/cancel-order!
  Event:    {:type :cancel-order, :origin :user}
  Order:    :active → :pending-cancel
  API:      cancel request sent to IBKR

Step 5 — Order fills before cancel arrives at exchange
  Callback: :exec-details (100 shares filled)
  Event:    {:type :fill, :origin :broker, :payload {:fill {...}}}
  Order:    :pending-cancel → :filled  (valid transition)
            :fills populated, :closed-at set

Final state:
  Order: {:status :filled, :fills [{...}]}

Note: :pending-cancel → :filled is a valid transition for exactly this race.
      The cancel request arrived too late. Broker truth wins.
```

---

## Scenario 5: Limit order modified while partially filled

User has a partially-filled limit order and moves the price to improve chances of filling the remainder.

Objects involved: 1 Order

```
Steps 1–4 — Order placed, active, partially fills
  Order: :partially-filled, :fills [{:quantity 60}]

Step 5 — User modifies limit price
  Action:   modify-order! (not yet implemented — future)
  Event:    {:type :modify-order, :origin :user,
             :payload {:changes {:limit-price 149.00}}}
  Order:    :partially-filled → :pending-modify
            :limit-price updated to 149.00

Step 6 — Broker confirms the modification
  Callback: :order-status (Submitted, with new price)
  Event:    {:type :order-active, :origin :broker}
  Order:    :pending-modify → :active

Step 7 — Remainder fills
  Callback: :exec-details (40 shares at 149.00)
  Event:    {:type :fill, :origin :broker}
  Order:    :active → :filled (or :partially-filled → :filled if further partial first)
```

---

## Scenario 6: Broker-originated order (auto-liquidation)

IBKR's risk desk closes a position without any user command. We receive an openOrder callback with no matching UUID in orderRef.

Objects involved: 1 new Order (broker-created)

```
Step 1 — IBKR sends openOrder with unknown orderRef
  Callback: :open-order (orderRef does not match any :uuid in our store)
  Action:   adopt as broker-originated order
  Created:  Order {:status :pending, :origin :broker, :fills []}
  Event:    {:type :submit-order, :origin :broker}
            (no user command in history — that's fine, events don't require commands)

Steps 2–N — Normal event tracking from here
  Subsequent callbacks (:order-accepted, :order-active, :fill) apply as usual.
  Order transitions through the standard state machine.
  Origin :broker is visible in the event log for audit purposes.

Final state (typical liquidation):
  Order: {:status :filled, :origin :broker, :fills [{...}]}
  Position: updated by fill event, quantity reduced or zero
```

---

## Scenario 7: Position sync detects unexpected change

During a routine reqPositions poll, the broker reports a quantity that differs from our local snapshot. This catches corporate actions, overnight adjustments, and any other broker-side changes we didn't initiate.

Objects involved: 1 Position

```
Local state before sync:
  Position: {:symbol "AAPL", :quantity 100M, :avg-cost 148.50, :snapshot-at <t0>}

Step 1 — reqPositions poll fires
  Action:   store sends reqPositions to IBKR
  IBKR:     streams position callbacks, ends with positionEnd

Step 2 — IBKR reports AAPL at 110 shares (unexpected — we thought we had 100)
  Callback: :position (account "U1234", symbol "AAPL", quantity 110M, avg-cost 149.20)
  Delta:    +10 (was 100, now 110)
  Source:   :sync (not traceable to a specific order fill)

Step 3 — Position snapshot updated
  Action:   store/save! with new snapshot (upsert by deterministic UUID)
  Position: {:quantity 110M, :avg-cost 149.20, :snapshot-at <t1>}

Note: We detect *that* it changed (+10 shares) but not *why* — could be DRIP,
      a fractional share adjustment, or a correction. The :sync source on the
      event communicates this honestly. Cross-referencing the order event log
      may reveal a cause (e.g., a broker-originated order filled around the same time).

Final state:
  Position: {:quantity 110M, :avg-cost 149.20, :snapshot-at <t1>}
  (previous snapshot is gone — positions are snapshot-replaced, not event-sourced)
```

---

## Scenario 8: Full trade lifecycle — entry, stop hit

A complete Trade flow using the Trade layer (future, not yet implemented). Included here to show how the Trade and Order layers interact.

Objects involved: 1 Trade, 2 Orders (entry + stop)

```
Step 1 — User places a trade
  Action:   place-trade! called with entry order and stop params
  Created:  Trade {:status :draft}
            Order (entry) {:status :pending}
  Trade event: {:type :place-trade, :origin :user}
  Trade: :draft → :submitting

Step 2 — Entry order fills
  Order event: :fill → order :filled
  Trade event: :entry-fill → trade :submitting → :open
               :filled-qty set, :avg-price set
  Created:  Order (stop) submitted to broker
            stop order now in :active state

Step 3 — Stop triggers (price drops)
  Order event (stop): :fill → stop order :filled
  Trade event: :exit-fill → trade :open → :closing

Step 4 — No remaining open orders
  Trade event: :exit-fill (final) → trade :closing → :closed
               :realized-pnl calculated, :closed-at set

Final state:
  Trade:        {:status :closed, :realized-pnl -240.00, :closed-at <t>}
  Entry order:  {:status :filled}
  Stop order:   {:status :filled}
```
