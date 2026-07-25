# Key Decisions

Architectural and design decisions, with rationale.


## Screens are one-shot by default [2026-07]

Decision: `abroker.screen` defines broker-agnostic screens; `client/req-scan` runs one, delivers a single vec of rows and cancels the broker subscription itself. Continuous screening is the separate, explicitly-managed `req-scan-stream`. See [doc/data-model/ScreenModel.md](doc/data-model/ScreenModel.md).

Why: IBKR's scanner is a streaming subscription capped at 10 concurrent, with no natural end. The overwhelmingly common use ("what's moving right now?") wants one answer, and forgetting to cancel silently burns one of the 10 slots. Making the safe reading the default keeps the dangerous one honest — `req-scan-stream` hands back the req-id precisely because the caller now owns the cancel.


## Screen defaults filter for tradability, not for results [2026-07]

Decision: `screen/defaults` is US stocks, major exchanges, 50 rows, price above $5. No volume or market-cap floor. Every setter takes nil to clear a default, and presets (`top-gainers`, `unusual-volume`, …) layer opinions on top.

Why: defaults should remove results that are never actionable, not results that are merely uninteresting. Major-exchange + $5 excludes OTC and sub-$5 names — hard to margin, hard to borrow, wide spreads — and both are things we'd otherwise filter by hand every time. A volume floor looks equally sensible but isn't: IBKR's `aboveVolume` is *today's* share count, so a default would silently empty every pre-market scan. That belongs in presets, where the tradeoff is visible.


## Scan codes: canonical vocabulary first, mechanical conversion as fallback [2026-07]

Decision: the scan codes `abroker.screen`'s presets use (`:top-gainers`, `:most-active`, …) are canonical and mapped per adapter in `ibkr/codes.clj`. Any other keyword converts mechanically (`:top-perc-gain` → `"TOP_PERC_GAIN"`) with no validation, and raw broker strings pass straight through. Instrument, location and stock-type keywords go through curated maps and throw on an unknown or missing keyword.

Why: this is the same shape as canonical order types and order statuses — core owns a small portable vocabulary, adapters own their translation — so `screen/top-gainers` doesn't hardcode IBKR's word for it in the broker-agnostic layer. Below the canonical set, IBKR has hundreds of scan codes and adds them; a whitelist would be stale within a release and the mechanical mapping is right for all of them. Locations and instrument codes, by contrast, are small irregular sets (`STK.US.MAJOR`, `STOCK.EU`) that can't be derived, and a wrong one comes back as an empty scan rather than an error — so a guess must fail loudly at the boundary. The string escape hatch means none of these choices can block a caller.


## Screens are ended by the events that invalidate them [2026-07]

Decision: a scan is torn down — chan closed, subscription cancelled, entry dropped — by whatever makes it meaningless: its `scannerDataEnd` (one-shot), a TWS error on its req-id, its own timeout, or the connection closing. Timers identity-check the scan's chan before acting rather than trusting the req-id alone.

Why: an abandoned scanner subscription costs one of only 10 slots, and a consumer parked on a chan that will never deliver is worse than an error. The identity check exists because req-ids are not stable: `connect!` reseeds the counter from TWS's next valid order id, so ids repeat after a reconnect and a stale timer would otherwise cancel a healthy scan that inherited its id.


## FA group ghost position filtering [2026-03]

Decision: `tools.clj` filters out zero-average-cost positions created by FA group trades for recently-closed positions.

Why: IBKR sends phantom position records for FA group allocations even after the position is fully closed. Without filtering, these appear as open positions with $0 cost basis.


## Call context pattern (`async-ctx`) for deduplication [2025-12]
(extracted from `client.clj` into own namespace)

Decision: When multiple callers concurrently request the same paced operation (e.g. `reqPositions`), they share a single in-flight channel rather than each issuing a separate request.

Why: IBKR rate-limits API calls. `reqPositions` fires many individual callbacks before signaling completion — issuing it multiple times concurrently would produce interleaved, ambiguous results.


## Two-layer architecture: broker-agnostic + adapters [2025-12]

Decision: Core namespaces (`data`, `trading`, `risk`, `price`, `async-ctx`) are broker-agnostic. Broker-specific code lives in adapter subdirectories (currently `ibkr/`).

Why: Enables adding new brokers (Alpaca, Schwab) without touching core logic. Callers program against the generic data model; adapter translates to/from broker-native types.


## core.async channel for TWS callbacks [2025-12]

Decision: `ewrapper.clj` implements `EWrapper` and funnels every TWS callback into a single core.async channel. A worker go-loop dispatches via `handle-event` multimethod.

Why: TWS callbacks arrive on a dedicated socket thread. Doing real work inside those callbacks risks blocking the reader and stalling the connection. The channel decouples arrival from processing.


## Exponential backoff reconnect [2025-12]

Decision: On disconnect (IBKR error codes 1100/1102), retry with backoff starting at 2s, doubling each attempt, capped at 60s. Reconnect is disabled on explicit disconnect.

Why: TWS does a daily auto-restart. Aggressive retries would spam the log and potentially confuse TWS mid-restart. The cap prevents indefinite long waits if the problem is persistent.


## EDN config via cprop [2025-12]

Decision: Runtime config (`resources/config.edn`) is gitignored; `config.sample.edn` is the template. cprop handles loading.

Why: Keeps credentials and account IDs out of version control. cprop supports env var overrides which is useful in CI or containerized deployments.


## Canonical order status (6 states) [2025-12]

Decision: IBKR's 11+ order status strings are normalized to 6 canonical states: `:pending`, `:open`, `:partially-filled`, `:filled`, `:cancelled`, `:error`.

Why: Broker-agnostic callers shouldn't need to know IBKR's internal status distinctions (e.g. `PreSubmitted` vs `Submitted`). The mapping is in `ibkr/data.clj`; full state machine is in `doc/data-model/OrderModel.md`.
