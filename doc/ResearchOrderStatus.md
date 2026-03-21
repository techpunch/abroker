# Order Status Research Notes


Assume all brokers can send either an explicit Unknown status or one that doesn't match any of these tables which we would translate into Unknown.


## IBKR

https://www.interactivebrokers.com/campus/ibkr-api-page/twsapi-doc/#order-status-message

| Status Code   | Description                                                                  |
| ------------- | ---------------------------------------------------------------------------- |
| PendingSubmit | Transmitted but confirmation of accept by dest not yet received              |
| PendingCancel | Cancel request sent but confirmation not yet received, Cancel not guaranteed |
| PreSubmitted  | Simulated order type accepted by IB but not yet elected; order held until election criteria met, then xmitd to dest |
| Submitted     | Accepted by IB                                                               |
| ApiCancelled  | Canceled by an API client after submit but before acknowledgement            |
| Cancelled     | Remaining qty of order confirmed canceled by IB. May also occur if order rejected |
| Filled        | Completely filled. Market order executions may not always emit `Filled`      |
| Inactive      | Received but no longer active due to rejection or cancellation               |


## Alpaca

https://docs.alpaca.markets/reference/getorderbyorderid-1

| Status Code            | Description                                                               |
| ---------------------- | ------------------------------------------------------------------------- |
| new                    | Received by Alpaca and routed to exchanges for execution                  |
| partially_filled       | Partially filled                                                          |
| filled                 | Fully filled and will receive no further updates                          |
| done_for_day           | Done for the day and will resume updates next trading day if applicable   |
| canceled               | Canceled by the user or exchange and will receive no further updates      |
| expired                | Expired and will receive no further updates                               |
| replaced               | Replaced by another order or updated due to a market event, e.g, corp act |
| pending_cancel         | Awaiting cancellation                                                     |
| pending_replace        | Awaiting replacement and will reject cancel requests while in this state  |
| accepted *             | Received by Alpaca but has not yet been routed to an execution venue      |
| pending_new *          | Routed to exchanges but has not yet been accepted for execution           |
| accepted_for_bidding * | Received by exchanges and is being evaluated for pricing                  |
| stopped *              | Stopped and a trade is guaranteed but has not yet occurred                |
| rejected *             | Rejected and will receive no further updates                              |
| suspended *            | Suspended and is not eligible for trading                                 |
| calculated *           | Complete for the day, but settlement calculations are still pending       |

An order may be canceled through the API up until the point it reaches a state of either filled, canceled, or expired.

\* "Less common states ... only occur on very rare occasions, and most users will likely never see their orders reach these"


## Schwab

| Status Code             | Description                                                       |
| ----------------------- | ----------------------------------------------------------------- |
| NEW                     | Created but has not yet been accepted or routed                   |
| ACCEPTED                | Accepted by Schwab                                                |
| QUEUED                  | Queued for processing or routing                                  |
| WORKING                 | Active and eligible for execution                                 |
| FILLED                  | Completely filled                                                 |
| REJECTED                | Rejected and will not be executed                                 |
| CANCELED                | Successfully canceled                                             |
| EXPIRED                 | Expired based on its time-in-force and will not be executed       |
| PENDING_CANCEL          | Cancel request submitted but not yet confirmed                    |
| PENDING_REPLACE         | Replace (modify) request submitted but not yet confirmed          |
| REPLACED                | Successfully replaced by a new order                              |
| PENDING_ACKNOWLEDGEMENT | Sent but acknowledgement not yet been received                    |
| PENDING_ACTIVATION      | Accepted but not yet active, e.g., conditional or time-based      |
| AWAITING_RELEASE_TIME   | Waiting for a specified release time before becoming active       |
| AWAITING_CONDITION      | Waiting for a condition to be met before activation               |
| AWAITING_STOP_CONDITION | Stop condition has not yet been triggered                         |
| WAITING_PARENT_ORDER    | Child order is waiting for parent order to reach a required state |
| AWAITING_MANUAL_REVIEW  | Requires manual review before further processing                  |
| PENDING_RECALL          | Recall request submitted but not yet completed                    |
| AWAITING_UR_OUT         | Uncertain, possibly an internal processing state for canceling    |


## Assume all have an "Unknown" state


| Lifecycle Phase          | IBKR Status(es)             | Alpaca Status(es)     | Meaning                                                            |
| ------------------------ | --------------------------- | --------------------- | ------------------------------------------------------------------ |
| Order received / created | `PendingSubmit`             | `new`, `accepted`     | Order acknowledged by broker but not yet fully routed or confirmed |
| Routed / accepted        | `Submitted`                 | `new`                 | Order accepted and sent to execution venue                         |
| Working / active         | `Submitted`, `PreSubmitted` | `new`, `done_for_day` | Order is live or staged (e.g., conditional / simulated)            |
| Partially filled         | *(implicit via executions)* | `partially_filled`    | Some shares/contracts executed                                     |
| Fully filled             | `Filled`                    | `filled`              | Order completely executed                                          |
| Cancel requested         | `PendingCancel`             | `pending_cancel`      | Cancel request sent, not yet confirmed                             |
| Canceled                 | `Cancelled`                 | `canceled`, `expired` | Order is definitively terminated                                   |
| Rejected / inactive      | `Inactive`                  | `rejected`            | Order will not execute due to rejection or invalid state           |
