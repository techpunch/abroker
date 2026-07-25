# Roadmap

## Current Focus

Getting the project structured to move fast with Claude Code. Stabilizing the IBKR adapter before adding new brokers.

## Near Term

- [ ] Modify configs to reflect new doc/data-model/AllocationModel.md
- [ ] Setup data model persistence layer
- [ ] Test coverage: expand unit and integration tests for core namespaces
- [ ] Market data: stabilize real-time tick and bar streaming; auto-resume 5m bar streams after reconnect
- [ ] Order lifecycle: reliable tracking of fill events through `handle-event`
- [ ] Error handling: surface IBKR error codes more cleanly to callers

## Mid Term

- [ ] Strategy-level abstractions: stops, trims, take-profit rules that work across brokers
- [ ] Second broker adapter (Alpaca is the likely first target)
- [ ] Market data failover: detect data problems and switch sources transparently
- [ ] Unified quote/bar API across brokers

## Long Term

- [ ] Cross-broker order execution (e.g. split a trade across IBKR + Schwab accounts)
- [ ] Public release / stable API (post pre-alpha)

## Completed

- [x] IBKR connection management with exponential backoff reconnect
- [x] EWrapper → core.async channel (non-blocking callback routing)
- [x] Call context pattern (`async-ctx`) for deduplicating concurrent API requests
- [x] FA group ghost position filtering
- [x] Broker-agnostic draft data model: Order, Instrument, Allocation, Trade
- [x] Order DSL: `mkt`, `lmt`, `stp`, `gtc`, `add-stop`, etc.
- [x] Risk validation (`max-order-amt`)
- [x] Canonical order status mapping (11+ IBKR states → 6 canonical)
- [x] Position grouping/filtering by allocation, CSV export
- [x] Market scanner (screener): one-shot `req-scan` with tradeable defaults, friendly scan/location codes, results usable as instruments
