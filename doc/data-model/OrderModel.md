# Order Model

Broker-agnostic data model for orders, fills, and instruments — the common primitives across brokers.


## Instrument

Pure identity — what you're trading, not how or where. Routing details (exchange, currency) belong on the Order or in broker-specific adapter config.

```clojure
{:type     :stock | :option | :crypto | :future | :forex
 :symbol   <string>             ; uppercase ticker, e.g. "AAPL", "BTC.USD"
 :subtype  :put | :call | nil   ; options only
 :expiry   <local-date?>        ; options only
 :strike   <decimal?>}          ; options only
```

Two instruments are equal if all their fields match.


## Order

A single instruction to a broker to buy or sell a quantity of an instrument.

```clojure
{:uuid           <uuid>          ; unique id, generated at creation
 :allocation     <keyword>       ; references an allocation (see AllocationModel.md)
 :instrument     <instrument>
 :action         :buy | :sell
 :quantity       <pos-int>       ; must be divisible by allocation's optional min-lot-size
 :type           :mkt | :lmt | :stp | :stp-lmt | :mit | :lit
 :tif            :day | :gtc | :gtd
 :good-till      <zoned-dt | keyword?> ; required for :gtd — ZonedDateTime or delta keyword like :15m :2h :2d
 :limit-price    <decimal?>      ; required for :lmt, :stp-lmt, :lit
 :stop-price     <decimal?>      ; required for :stp, :stp-lmt
 :touch-price    <decimal?>      ; required for :mit, :lit
 :oca-group      <string?>       ; OCA/OCO group name
 :eth?           <bool>          ; eligible for extended trading hours session
 :overnight?     <bool>          ; eligible for overnight trading session
 :transmit?      <bool>          ; false = save with broker without transmitting to market
 :trigger-method <keyword?>      ; :default :double-bid-ask :last :double-last
                                 ; :bid-ask :last-or-bid-ask :midpoint
 :stop-orders    [<order>]       ; child stop/bracket orders
 :exchange       <string?>       ; routing hint, broker-specific (e.g. "ARCA"); nil = broker default
 :currency       <string?>       ; routing hint; nil = USD
 :broker-id      <any?>          ; broker-assigned order id (set after submit)
 :status         <order-status>  ; see Order State Machine below
 :fills          [<fill>]        ; execution reports received
 :created-at     <instant>
 :updated-at     <instant>}
```


## Fill

A single execution report — one partial or complete fill of an order.

```clojure
{:order-uuid    <uuid>          ; parent order
 :quantity      <pos-int>       ; shares/contracts filled in this execution
 :price         <decimal>       ; execution price
 :commission    <decimal?>
 :broker-exec-id <string?>      ; broker's execution id
 :timestamp     <instant>}
```


---


## Order State Machine

Broker-agnostic order lifecycle. Each broker's native statuses map into these states. See [ResearchOrderStatus.md](ResearchOrderStatus.md) for the raw broker status tables.

### States

| State             | Terminal? | Description                                              |
| ----------------- | --------- | -------------------------------------------------------- |
| `pending`         | no        | Submitted to broker, awaiting acknowledgement            |
| `accepted`        | no        | Broker accepted, routing to exchange                     |
| `active`          | no        | Live on exchange, eligible for execution                 |
| `partially-filled`| no        | Some quantity executed, remainder still active            |
| `filled`          | **yes**   | Completely executed                                      |
| `pending-cancel`  | no        | Cancel requested, awaiting broker confirmation           |
| `pending-modify`  | no        | Modify requested, awaiting broker confirmation           |
| `canceled`        | **yes**   | Definitively canceled (includes expired)                 |
| `rejected`        | **yes**   | Rejected by broker, will not execute                     |
| `unknown`         | no        | Unrecognized status from broker, needs investigation     |

### Transitions

```
  Diagram shows primary paths only. Transition rules below are authoritative.
                    ┌──────────────────────────────────┐
                    │                                  │
                    v                                  │
  ┌─────────┐  ┌──────────┐  ┌─────────┐  ┌───────────────────┐  ┌────────┐
  │ pending │─>│ accepted │─>│ active  │─>│ partially-filled  │─>│ filled │
  └─────────┘  └──────────┘  └─────────┘  └───────────────────┘  └────────┘
       │            │            │  │              │
       │            │            │  │              │
       v            v            v  │              v
  ┌──────────┐ ┌──────────┐      │  │         ┌──────────┐
  │ rejected │ │ canceled │<─────┘  │         │ canceled │
  └──────────┘ └──────────┘         │         └──────────┘
                     ^              │
                     │              v
                    ┌────────────────┐
                    │ pending-cancel │
                    └────────────────┘

  Any non-terminal state ──> pending-cancel ──> canceled | active | partially-filled | filled
  Any non-terminal state ──> pending-modify ──> active | partially-filled | filled | canceled
  Any non-terminal state ──> unknown
```

**Transition Rules:**
1. `pending` -> `accepted` | `active` | `rejected` | `filled` (market orders can fill immediately)
2. `accepted` -> `active` | `canceled` | `rejected` | `filled`
3. `active` -> `partially-filled` | `filled` | `pending-cancel` | `pending-modify` | `canceled`
4. `partially-filled` -> `filled` | `pending-cancel` | `pending-modify` | `canceled`
5. `pending-cancel` -> `canceled` | `filled` | `active` | `partially-filled`
6. `pending-modify` -> `active` | `filled` | `partially-filled` | `canceled`
7. `unknown` -> any state (once broker reports a recognized status)

### Broker Status Mapping

| Broker-Agnostic    | IBKR                      | Alpaca                          | Schwab                                    |
| ------------------ | ------------------------- | ------------------------------- | ----------------------------------------- |
| `pending`          | PendingSubmit             | pending_new                     | PENDING_ACKNOWLEDGEMENT, NEW              |
| `accepted`         | PreSubmitted              | accepted, new                   | ACCEPTED, QUEUED, PENDING_ACTIVATION      |
| `active`           | Submitted                 | new, done_for_day               | WORKING, AWAITING_STOP_CONDITION          |
| `partially-filled` | *(via executions)*        | partially_filled                | *(via executions)*                        |
| `filled`           | Filled                    | filled                          | FILLED                                    |
| `pending-cancel`   | PendingCancel             | pending_cancel                  | PENDING_CANCEL                            |
| `pending-modify`   | *(via order update)*      | pending_replace                 | PENDING_REPLACE                           |
| `canceled`         | Cancelled, ApiCancelled   | canceled, expired, replaced     | CANCELED, EXPIRED, REPLACED               |
| `rejected`         | Inactive                  | rejected, suspended             | REJECTED                                  |
| `unknown`          | *(anything else)*         | accepted_for_bidding, stopped, calculated | AWAITING_MANUAL_REVIEW, AWAITING_UR_OUT, etc. |


---


## Event Log

Every state change to an order is recorded as an event. Both user-initiated actions and broker-originated notifications use the same structure. This gives a complete, replayable audit trail.

User-originated events produce pending intermediate states (`:pending`, `:pending-cancel`, `:pending-modify`) — the act of sending a request to the broker is itself a fact. Broker-originated events produce outcome states (`:accepted`, `:active`, `:filled`, `:canceled`, `:rejected`) or resolve pending states.

### Event Structure

```clojure
{:uuid       <uuid>             ; unique event id
 :order-uuid <uuid>             ; the order this applies to
 :type       <event-type>       ; see table below
 :origin     :user | :broker    ; who initiated this
 :payload    <map>              ; event-specific data
 :timestamp  <instant>}
```

### Event Types

| Type                | Origin    | Description                                  | Payload                                          |
| ------------------- | --------- | -------------------------------------------- | ------------------------------------------------ |
| `:submit-order`     | `:user`   | Submit order to broker → `:pending`          | `{}`                                             |
| `:cancel-order`     | `:user`   | Cancel request sent → `:pending-cancel`      | `{}`                                             |
| `:modify-order`     | `:user`   | Modify request sent → `:pending-modify`      | `{:changes {...}}`                               |
| `:order-accepted`   | `:broker` | Broker acknowledged the order → `:accepted`  | `{:broker-id <any>}`                             |
| `:order-active`     | `:broker` | Order is live on exchange → `:active`        | `{}`                                             |
| `:partial-fill`     | `:broker` | Partial execution received → `:partially-filled` | `{:fill <fill>}`                             |
| `:fill`             | `:broker` | Order completely filled → `:filled`          | `{:fill <fill>}`                                 |
| `:order-rejected`   | `:broker` | Broker rejected the order → `:rejected`      | `{:reason <string>}`                             |
| `:order-canceled`   | `:broker` | Broker confirmed cancellation → `:canceled`  | `{}`                                             |
| `:status-change`    | `:broker` | Catch-all for other broker status updates    | `{:from <status>, :to <status>}`                 |

### How Events Drive State

Events are the only way order state changes. Processing an event:

1. Validates the transition against current state (e.g. can't cancel an already-filled order)
2. Applies the state transition
3. Appends the event to the order's history
4. Persists the updated order

The order map is the **current snapshot** and the event history is the **complete log**.
