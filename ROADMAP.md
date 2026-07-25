# Roadmap

## Current Focus

First, getting the core data model solid. Next, stabilizing the IBKR adapter before adding new brokers.

## Near Term

- [ ] Modify configs to reflect new doc/data-model/AllocationModel.md
- [ ] Test coverage: expand unit and integration tests for core namespaces
- [ ] Market data: stabilize real-time tick and bar streaming; auto-resume 5m bar streams after reconnect
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
- [x] Data model persistence layer (EDN file store)
- [x] Order lifecycle: reliable tracking of fill events through `handle-event`
- [x] Position tracking: snapshot replacement with ghost filtering and reconciliation

## Backlog - ungroomed, random TODOs

- [x] test for the ibkr/tools ns to make sure if one account reports a position of say +100 sh and another account reports a position of -100 sh, it shows up in both :long and :short
