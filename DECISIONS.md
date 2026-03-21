# Key Decisions

Architectural and design decisions, with rationale.


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
