# Plan: Merge repltrader into abroker — 2026-07-05

## Context

~/src/repltrader is a thin REPL trading console: one namespace
(`repltrader.core`, ~320 lines) of quick-entry commands (`bo`, `pb`, `tr`,
`st`, `on`, plus the `trade`/`tbo`/`tpb` session context) built on top of
abroker's order DSL, and one test namespace that mocks `ib/send-order!`.
It already depends on abroker via `:local/root "../abroker"` and on
`abroker.testutil` from abroker's test-utils path. It has no independent
life: every change to abroker's data model ripples into it (see its commit
"update for new abroker changes"). Merging it in removes the cross-repo
hop and lets the console evolve in lockstep with the DSL and the new order
lifecycle layer.

Current state that matters:
- repltrader has uncommitted work in core.clj (new `st`/`st-fn` stop-order
  command and a small `positions` refactor). Commit that first.
- abroker's working tree is itself mid-feature (the event-sourcing diff
  under review). Land that before or separately from this merge — don't
  mix the two in one commit.
- Dependency overlap is clean: clojure 1.12.2, tools.logging 1.2.4,
  core.async 1.8.741, java-time 1.4.3, konserve 0.8.322 are identical on
  both sides. repltrader adds only `ch.qos.logback/logback-classic 1.5.6`
  and a `resources/logback.xml` (no collision — abroker has no logback.xml).

## Decisions (with recommendations)

1. Namespace: keep `repltrader.core` as-is, living at
   `src/repltrader/core.clj` inside abroker. Multiple top-level namespaces
   in one repo are fine, and this preserves REPL muscle memory
   (`(in-ns 'repltrader.core)` and the `bo`/`pb` finger macros keep
   working unchanged). Renaming to `abroker.repl` buys nothing today; if
   abroker is ever published as a library jar, exclude `repltrader/**` in
   build.clj at that point.

2. Git history: plain copy, don't graft history. There are only four
   commits; the old repo stays on disk as the archive of record. (If you
   care more than that: `git subtree add --prefix=vendor/repltrader
   ~/src/repltrader main` then `git mv` the files into place preserves
   history behind `git log --follow`, at the cost of a messier graph.
   Not recommended for four commits.)

3. logback-classic: do NOT add to abroker's main `:deps`. abroker is a
   library; forcing a logging backend on consumers is bad manners. Put it
   in a new `:repl` alias together with the console's entry point.
   `resources/logback.xml` can sit on the main resources path harmlessly —
   it only takes effect when logback is on the classpath.

4. Entry point: add a `:repl` alias so starting a trading session is one
   command. repltrader's `-main` is a stub ("hello there!"), so the alias
   should just start an nREPL/plain REPL with the extra deps; drop the
   `:run` alias concept entirely.

## Steps

1. In ~/src/repltrader: commit the pending core.clj change
   ("add st stop order command; positions returns csv from groups").

2. Copy into abroker:
   - `src/repltrader/core.clj`  → `src/repltrader/core.clj`
   - `test/repltrader/core_test.clj` → `test/repltrader/core_test.clj`
   - `resources/logback.xml` → `resources/logback.xml`
   Do not copy: README.md, build.clj, .gitignore, deps.edn (abroker's
   versions of all of these already cover it).

3. abroker deps.edn: add the alias
   ```clojure
   :repl {:extra-deps {ch.qos.logback/logback-classic {:mvn/version "1.5.6"}}}
   ```
   Nothing else changes — every other repltrader dep is already present
   at the same version, and techpunch.num/util/test come via the existing
   `techpunch/clj` local dep.

4. Verify:
   - `clj -M:test` — cognitect runner scans test/, so repltrader.core-test
     joins the suite automatically (it uses `abroker.testutil`, which is
     already on abroker's `test-utils` path — nothing to wire).
   - REPL smoke test: `clj -M:repl`, require repltrader.core, run a
     mocked `bo-fn` via `with-test-config`.

5. Docs, same commit:
   - CLAUDE.md: add repltrader.core to the architecture section as a third
     layer ("REPL trading console — user-facing quick-entry commands over
     the DSL; sends via IBKR"), and mention `clj -M:repl`.
   - DECISIONS.md: record the merge and the rationale (lockstep evolution,
     no independent release cadence, logback isolated in :repl alias).
   - ROADMAP.md: check off / note as appropriate.

6. Retire the old repo: add a final commit to ~/src/repltrader whose
   README points at abroker, then rename the directory to
   `~/src/repltrader-archive` (or delete it once the merge commit is
   pushed wherever abroker lives). Remove it from editor workspaces so
   nobody edits the dead copy.

## Phase 2: instant startup and a shell CLI via babashka

Goal: `rt bo bob-ira aapl 23.45 22.90 500 gtc` from any terminal, answering
in tens of milliseconds, with the same grammar as the REPL macros.

### The constraint that shapes the design

Babashka cannot load external Java jars, so it can never talk to the TWS
API directly. That forces (and justifies) a client/daemon split, which is
what you want anyway — the TWS connection should be long-lived, not
per-command:

- Daemon: the normal JVM (`clj -M:repl`) holding the TWS connection, the
  order-tracking state, and the session atoms (`trade-ctx`, `mru-parents`).
  Started once per trading day; exposes a prepl socket on localhost.
- Client: a babashka script `bin/rt` (~15 ms startup) that parses argv,
  sends one form to the prepl, prints the reply, exits.

A pleasant consequence: because `trade-ctx` lives in the daemon, the
session workflow spans shell invocations — `rt trade bob-ira aapl 500`,
then later just `rt tbo 23.45 22.90`, from different terminal windows.

### Daemon side

1. Extend the `:repl` alias to open a prepl (no new deps, it's in core):
   ```clojure
   :repl {:extra-deps {ch.qos.logback/logback-classic {:mvn/version "1.5.6"}}
          :jvm-opts ["-Dclojure.server.rt={:port 5567 :accept clojure.core.server/io-prepl}"]}
   ```
   Localhost-only by default, which is the right security posture for
   something that places live orders.

2. New namespace `repltrader.cli` with a single entry point:
   ```clojure
   (dispatch ["bo" "bob-ira" "aapl" "23.45" ".9" "500" "gtc"])
   ```
   A dispatch table maps command name → existing `*-fn` plus an arg spec
   (how many keywords, how many prices, trailing opt-flags). The coercion
   the macros do at read time (keywordize symbols, `ez-num` prices) moves
   into small string-taking helpers here — `ez-num` is just "prefix a
   leading dot with 0", trivial to do on strings — and the macros can then
   share the same table so grammar exists in exactly one place. `dispatch`
   captures `*out*` and returns `{:out ... :result ...}` so the client can
   print the same acks you see at the REPL ("buy breakout bob-ira aapl
   10 @ 23.45 stop 22.9").

### Client side

3. `bin/rt` (babashka, committed to the repo):
   - Read port from `.rt-port` (written by the daemon on startup) or
     default 5567.
   - Connect; on connection-refused, either print "daemon not running —
     start with: clj -M:repl" or auto-start it in the background and wait
     for the port (auto-start is nicer but adds failure modes; start with
     the error message, add auto-start later if it annoys).
   - Send `(repltrader.cli/dispatch [...])` with argv as EDN strings, read
     the prepl response map, print `:out`, exit non-zero if `:exception`.
   - Grammar is verbatim the macro grammar, no flags to learn:
     ```
     rt bo bob-ira aapl 23.45 22.90 500 gtc
     rt ss bob-ira aapl 99 100 500          # short breakdown
     rt st bob-ira aapl 100 22.90 stop-rth
     rt trade bob-ira aapl 500 true
     rt tbo 23.45 22.90
     rt pos
     rt status                              # daemon + TWS connection info
     ```

4. `rt status` is the one genuinely new command: reports whether the
   daemon is up, which account/host/port TWS is connected to, and open
   session contexts. Cheap to build and it's the safety check you want
   before firing real orders from a shell — the acks already echo what
   was sent, and localhost-only prepl covers the rest.

### Latency budget

bb startup ~15 ms + localhost prepl round trip ~5 ms + fn execution.
Every command lands well under 50 ms except the daemon's own cold start
(JVM + TWS handshake, several seconds) which is paid once per day.

### Rejected alternatives

- GraalVM native-image of repltrader: real instant startup, but each
  invocation would still need a fresh TWS connection (seconds, plus
  order-id handshake), the TWS jar needs reflection config, and you lose
  the persistent session atoms. Wrong shape for a stateful trading day.
- nREPL instead of prepl: works (bb speaks bencode), but prepl is
  zero-dependency on both sides and we control both ends.
- bb pods / sci in-process: no TWS access, same dead end as running bb
  standalone.

### Prerequisite

babashka is not currently installed on this machine:
`brew install borkdude/brew/babashka`.

Sequencing: do this after the merge lands (the CLI imports repltrader.core
from inside abroker) but it does not need to wait for the trading.clj
lifecycle migration — `rt` drives the same `*-fn`s the macros do today.


## Follow-ups (explicitly not part of the merge)

- Route the console through the new lifecycle layer. Every repltrader
  command calls `ib/send-order!` directly, which bypasses the new
  event-sourced order tracking entirely (no persistence, no status
  tracking, no UUID in orderRef). The end state is for `tr-fn`, `st-fn`,
  `order-trio`, `on-fn` to call `abroker.trading/send-order!` instead —
  but hold off until the Full-Fable-Review critical items (Instant
  serialization, cancelOrder interop, orderRef plumbing) are fixed;
  today `trading/send-order!` is the more broken path.
- Move `ignore-tickers` (hardcoded set in core.clj) into config.edn next
  to the allocations.
- Delete or finish the commented-out `tt-fn` block (references undefined
  `stop-rth`/`parent` locals — it won't compile if ever uncommented).
- Decide whether `repltrader.core` should split (order commands vs. trade
  session context vs. position reporting) — at ~320 lines it's fine, just
  revisit if it grows.

## Risks

Low. No namespace or resource collisions, no version skew, tests are
self-contained with mocks. The only behavioral coupling is
`abroker.testutil`, already satisfied. The one thing to be careful about
is sequencing: land the in-flight event-sourcing work first so the merge
commit is clean and revertable on its own.
