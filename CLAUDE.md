# CLAUDE.md

This file provides guidance to AI coding tools when working with code in this repository.

## Project

**abroker** is a Clojure library providing a broker-agnostic abstraction layer for trading, currently implemented for Interactive Brokers (TWS API).

## Commands

```bash
# Run all tests
clj -M:test

# Run a single test namespace
clj -M:test -n abroker.data-test

# Clean build artifacts
clj -M:build clean
```

## Current Focus

Getting the project structured to move fast with Claude Code.

## Project Roadmap: see ROADMAP.md

## Key Decisions: See DECISIONS.md

## Architecture

### Two-Layer Design

**Broker-agnostic layer** (`src/abroker/`):
- `data.clj` — Core data model (Order, Instrument, Allocation) and DSL for building orders (`mkt`, `lmt`, `stp`, etc.)
- `trading.clj` — High-level trading interface (currently delegates to IBKR)
- `async-ctx.clj` — Call context pattern: deduplicates concurrent in-flight requests to paced APIs
- `risk.clj` — Order validation against configured `max-order-amt` limits
- `price.clj` — Price formatting, Bar record, volume conversions

**IBKR adapter** (`src/abroker/ibkr/`):
- `client.clj` — Connection management, reconnect with exponential backoff, order placement, event routing via `handle-event` multimethod
- `ewrapper.clj` — Implements IBKR's `EWrapper` Java interface; routes all TWS callbacks into a core.async channel to avoid blocking
- `data.clj` — Converts between abroker's data model and IBKR's Java objects (`Contract`, `Order`, IBKR's custom `Decimal` type)
- `codes.clj` — IBKR enumerations and constants (order types, tick field codes, bar sizes)
- `tools.clj` — Higher-level utilities: position grouping/filtering by allocation, CSV export, timeout-aware async request wrappers

### Event Flow

IBKR TWS callbacks → `EWrapper` → core.async event channel → `handle-event` multimethod dispatch

This keeps all TWS callback processing non-blocking. The socket reader, event worker, and reconnect worker each run in their own future/go-loop.

### Key Patterns

**Call context** (`async-ctx`): If multiple callers request the same expensive/paced operation concurrently, they tap a single in-flight channel rather than each issuing a separate API call. Used for `reqPositions` which fires many callbacks before completing.

**Reconnect**: Detects connection loss via IBKR error codes 1100/1102. Retries with exponential backoff (2s → 4s → 8s… capped at 60s). Disabled on explicit disconnect.

**FA groups**: IBKR sends ghost zero-cost positions for recently-closed FA group trades; `tools.clj` filters these out. Allocation groups use `:alloc-group` + `:min-lot-size`; individual accounts use `:account`.

### Configuration

`resources/config.edn` (gitignored — copy from `config.sample.edn`):
```edn
{:broker-config
 {:brokers {:ibkr {:name "Live" :account "F12345679" :host "localhost" :port 7496}}
  :risk-mgmt {:max-order-amt 200000}
  :allocations {:bob-tax  {:account "U00001"}
                :bob-ira  {:account "U00002"}
                :team     {:alloc-group "TeamName" :min-lot-size 20}}}}
```

### TWS API Dependency

The IBKR TWS API JAR is not on Maven Central. Install it manually:
```bash
# Download TWS API Stable 10.37.02 from Interactive Brokers, then:
mvn install:install-file -Dfile=TwsApi.jar -DgroupId=com.interactivebrokers \
  -DartifactId=tws-api -Dversion=10.37.02 -Dpackaging=jar
```

### Order Status Mapping

IBKR has 11+ order statuses mapped to 6 canonical states. See `doc/data-model/OrderModel.md` for the full state machine and `ibkr/data.clj` for the mapping implementation.
