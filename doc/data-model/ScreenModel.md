# Screen Model

A **screen** asks the broker "which instruments look like this right now?" — IBKR calls it a market scanner. A screen is a plain map built with the DSL in `abroker.screen`; the adapter translates it and returns **scan rows** ranked best-first.

```clojure
(require '[abroker.screen :as screen]
         '[abroker.ibkr.client :as ib]
         '[clojure.core.async :refer [<!!]])

(<!! (ib/req-scan (screen/top-gainers)))
;; => [{:rank 0 :symbol "XYZ" :type :stock :con-id 4815747 :exchange "NASDAQ"
;;      :primary-exchange "NASDAQ" :currency "USD" :name "XYZ CORP"} ...]
```


## Screen

```clojure
{:scan-code           <keyword|string>  ; required — what to rank by, e.g. :top-gainers
 :instrument          <keyword|string>  ; asset class, default :stock
 :location            <keyword|string>  ; market, default :us-major
 :stock-type          <keyword|string?> ; :corp, :etf, :adr, :all — unset means all
 :rows                <int>             ; capped at screen/max-rows (50)
 :price-above         <num?>            ; default 5.0
 :price-below         <num?>
 :volume-above        <num?>            ; shares traded so far today
 :option-volume-above <num?>            ; avg daily option volume on the underlying
 :market-cap-above    <num?>
 :market-cap-below    <num?>
 :filters             <map?>}           ; broker-native filter tags, escape hatch
```

Keywords are the portable form and are converted per adapter (`:top-gainers` → `"TOP_PERC_GAIN"`, `:us-major` → `"STK.US.MAJOR"`). A raw broker string is always accepted in their place, so a scan code or location that abroker has never heard of still works. An unknown or missing *keyword* for `:instrument`, `:location` or `:stock-type` throws — the broker's answer to a bad code is an empty scan, which is miserable to debug.

`:scan-code` has three tiers: the canonical codes the presets use are mapped per adapter (`abroker.ibkr.codes/scan-code`); any other keyword converts mechanically, so IBKR's own vocabulary (`:top-perc-gain`, `:high-opt-imp-volat`) works unchanged; strings pass through untouched.

The defaults are stock-shaped, so `instrument` clears the default location when you move off stocks — location codes are instrument-specific and `STK.US.MAJOR` on a futures scan is silently wrong rather than an error. Set a matching location, and reconsider the inherited price floor:

```clojure
(-> (screen/screen :most-active)
    (screen/instrument :future)
    (screen/location "FUT.US")
    (screen/price-above nil))
```

Every DSL setter takes `nil` to clear a field, so the defaults are opt-out:

```clojure
(-> (screen/screen :most-active)
    (screen/price-above nil)      ; include sub-$5 names after all
    (screen/location :us))        ; widen beyond the major exchanges
```

### Defaults

`screen/screen` applies `screen/defaults`: US stocks on major exchanges, 50 rows, price above $5. The location keeps OTC and pink sheets out; the price floor drops the sub-$5 names that dominate percentage-move scans but are hard to actually trade (no margin, poor borrow, wide spreads).

### Filters

`:filters` passes broker-native filter tags straight through for anything the typed fields don't cover:

```clojure
(-> (screen/top-gainers)
    (screen/filters {"changePercAbove" 10 "hasOptionsIs" true}))
```

Tag names are case-sensitive and broker-specific. List the ones IBKR accepts with `abroker.ibkr.tools/filter-codes` over the XML from `abroker.ibkr.client/req-scanner-parameters`.

### Presets

`abroker.screen` ships starting points that return ordinary screens you can keep threading: `top-gainers`, `top-losers`, `most-active`, `unusual-volume`, `gap-ups`, `gap-downs`, `near-52w-high`, `near-52w-low`.


## Scan Row

```clojure
{:rank             <int>      ; 0-based, best first
 :symbol           <string>
 :type             <keyword>  ; instrument type, e.g. :stock
 :con-id           <int?>     ; broker's contract id
 :exchange         <string?>
 :primary-exchange <string?>
 :currency         <string?>
 :name             <string?>  ; company long name
 :distance         <string?>  ; scan-specific, mostly non-stock scans
 :benchmark        <string?>
 :projection       <string?>}
```

Fields the broker leaves empty are omitted rather than reported as `""`.

A row is shaped like an instrument (see `abroker.data/instrument`), so it can be handed straight to anything taking one. Prefer `screen/row-instrument` for that: it keeps `:symbol`/`:type`/`:con-id` — enough for the broker to resolve the exact contract — and drops the scanner's exchange so follow-up requests route normally (SMART for IBKR).

```clojure
(->> (<!! (ib/req-scan (screen/unusual-volume)))
     (take 5)
     (map screen/row-instrument)
     (run! #(ib/req-mkt-data % true)))
```


## One-shot vs. streaming

A broker screener is a live subscription, not a query. abroker exposes both readings:

- `ib/req-scan` — returns a chan delivering **one** vec of rows, then closing. The subscription is cancelled as soon as the rows arrive, or after `:timeout-ms` (default 15s), in which case the chan closes empty and the caller reads `nil`.
- `ib/req-scan-stream` — stays subscribed; the broker resends the whole result set whenever it changes. Returns `{:req-id _ :out _}`. The chan has a sliding buffer of 1, so a slow consumer skips stale snapshots and the event worker is never blocked. **The caller must `ib/cancel-scan` the req-id.**

`abroker.trading` forwards all three as `scan`, `scan-stream` and `cancel-scan` — prefer those; the `abroker.ibkr.client` fns are the adapter's.

`ib/live-scans` shows what's subscribed; `(ib/cancel-scan)` with no args cancels everything. IBKR allows at most 10 live scanner subscriptions (`ib/max-scans`).

A scan is torn down by whatever makes it meaningless, and in every case the chan closes so a reader gets `nil` rather than hanging: its own result set (one-shot), a TWS error on its req-id, its timeout, or the connection closing. That last one matters for streams — the subscription dies with the socket and is not resubscribed, so a consumer sees its chan close on disconnect and can decide whether to start a new one.
