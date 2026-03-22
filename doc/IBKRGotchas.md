# IBKR Gotchas

Lessons from working with the TWS API. This is an ongoing doc.


## Decimal is not a number type

IBKR uses a custom `com.ib.client.Decimal` class for quantities and sizes. It does not behave like a Java numeric type — don't compare, add, or print it directly. Its `.value` field is a `BigDecimal`, and `.longValue` works for whole share quantities.

Use the `IBDecimalConvert` protocol in `ibkr/data.clj` (`as-bigdec`, `as-long`, `as-double`) for all conversions. Zero is especially tricky: `Decimal`'s string representation of zero is not `"0"` — use `(zero? (as-bigdec d))` to test for it.


## FA group ghost positions

After closing a position via an FA allocation group, IBKR continues to report a phantom position entry for that symbol with `avgCost = 0.0`. It persists for an indeterminate amount of time.

Filter by `avg-cost > 0` when working with positions. `group-positions` in `tools.clj` does this automatically; don't bypass it.


## reqPositions fires many callbacks before signaling end

`reqPositions` does not return a single response — it fires one `:position` callback per position, then a `:position-end` event. There's no way to know how many positions are coming ahead of time.

This is why the call-context pattern (`async-ctx`) exists: accumulate results until `:position-end`, then deliver the full collection. Never assume a single round-trip.


## Historical bar streams don't resume after reconnect

`reqMktData` (real-time ticks) resumes fine after a disconnect or computer sleep. `reqHistoricalData` with `stream? true` does not — it emits error 10182 and stops permanently.

Don't rely on streaming historical bars across reconnects. Auto-resume for this is a known open TODO.


## Market orders and other fast fills may not emit a Filled status

IBKR docs note that market order executions "may not always emit `Filled`". Don't rely on receiving a `:filled` order status for orders. For fast-filling orders, the execution data often arrives before the status message can even be generated. Even a network blip can prevent the receipt of orderStatus. Pay more attention to the execDetails event.


## Error codes 1100/1102 are not errors

Code 1100 = connection lost, 1102 = connection restored. These are informational and fire during TWS's daily auto-restart cycle. They're suppressed from the error log in `client.clj` and trigger the reconnect worker instead.

Codes 2103–2108, 2119, 2157, 2158 are "chatty" market data farm connection notices. Also suppressed by default.


## OCA groups cannot do partial-fill reduce-others

It is not possible to have an OCA take-profit order partially fill and proportionally reduce the other OCA legs without canceling them. IBKR's OCA types only support cancel-on-any-fill or proportional-reduce-on-partial (which disallows the others from triggering until the partial fills). Don't re-research this — it's been tested multiple times.
