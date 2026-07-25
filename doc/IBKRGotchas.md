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

More generally, IBKR reserves 2100–2200 for warnings — delayed data notices and similar — and they arrive through the same `error` callback as outright rejections. Anything tearing a request down in response to an error should check `warning-code?` first, or a "displaying delayed market data" notice will kill a request that was working fine.


## Scanner `aboveVolume` is today's volume, not average volume

`ScannerSubscription.aboveVolume` filters on volume traded so far in the current session. Using it as a liquidity floor means a scan returns nothing in the first minutes of the day, and progressively more as the session goes on — the same screen gives different answers at 9:32 and 15:32 for reasons that have nothing to do with the market.

For an actual liquidity floor use the `avgVolumeAbove` scanner filter tag (`:filters {"avgVolumeAbove" 500000}` in `req-scan`). This is why `default-scan` has a price floor but no volume floor.


## Scanner subscriptions stream until canceled

`reqScannerSubscription` is not a request/response. TWS sends a `scannerData` callback per row, then `scannerDataEnd`, then keeps pushing re-ranked snapshots indefinitely. TWS also allows only a small number of scanner subscriptions at once, so an uncanceled scan is a leaked slot.

`req-scan` in `client.clj` treats a scan as one-shot: it takes the first snapshot, delivers it, and cancels. It also tears the scan down on error or after `scan-timeout-ms`, since a scan that never ends would otherwise hold its slot forever.


## scannerParameters has no req-id

The `scannerParameters` callback carries only the XML — no request id — so there's no way to tell two concurrent requests apart. `req-scanner-params` allows a single request in flight and hands a superseded caller a closed chan rather than someone else's answer.

The XML is several MB and is the only authoritative list of scan codes, locations, and filter tags for a given account. The friendly aliases in `codes.clj` are a curated subset, not the whole vocabulary.


## OCA groups cannot do partial-fill reduce-others

It is not possible to have an OCA take-profit order partially fill and proportionally reduce the other OCA legs without canceling them. IBKR's OCA types only support cancel-on-any-fill or proportional-reduce-on-partial (which disallows the others from triggering until the partial fills). Don't re-research this — it's been tested multiple times.
