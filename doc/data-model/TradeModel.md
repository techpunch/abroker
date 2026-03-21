# Trade Model

WIP - CONSIDER THIS A SCRATCHPAD FOR NOW!!!!!!!!!!

A Trade is the user-level concept: a position in an instrument with entry, risk management (stops), and profit-taking (trims). A Trade owns one or more Orders (defined in [OrderModel.md](OrderModel.md)).


## Trade

```clojure
{:uuid          <uuid>
 :allocation    <keyword>       ; account or group
 :instrument    <instrument>
 :action        :buy | :sell    ; :buy = long, :sell = short
 :risk-scale    <int 0-100>     ; pct of "full position" sizing
 :status        <trade-status>  ; see Trade State Machine below
 :strategy      <keyword?>      ; e.g. :prev-high, :opening-range-break
 :entry-order   <uuid>          ; the entry order
 :stop-orders   [<uuid>]        ; active stop order(s)
 :trim-orders   [<uuid>]        ; active trim/profit-target order(s)
 :filled-qty    <int>           ; net shares currently held
 :avg-price     <decimal>       ; average fill price of entries
 :realized-pnl  <decimal>       ; realized P&L from closed portions
 :history       [<command>]     ; ordered log of all commands/events applied
 :created-at    <instant>
 :closed-at     <instant?>}
```


## Trade State Machine

### States

| State        | Terminal? | Description                                                     |
| ------------ | --------- | --------------------------------------------------------------- |
| `draft`      | no        | Created locally, no orders submitted yet                        |
| `submitting` | no        | Entry order being placed with broker                            |
| `open`       | no        | Entry filled (fully or partially), position is live             |
| `modifying`  | no        | Adjusting stops/trims/size while position is open               |
| `closing`    | no        | Exit in progress (stop triggered, trim completing, manual close)|
| `closed`     | **yes**   | Position fully exited, all orders terminal                      |
| `failed`     | **yes**   | Entry rejected or canceled before any fill                      |

### Transitions

```
  ┌───────┐  place   ┌────────────┐  entry fill   ┌──────┐
  │ draft │─────────>│ submitting │──────────────>│ open │
  └───────┘          └────────────┘               └──────┘
                           │                      │  ^  │
                           │ rejected/            │  │  │ stop/trim/close
                           │ canceled             │  │  v
                           v              modify  │  │ ┌──────────┐
                      ┌────────┐          done    │  └─│modifying │
                      │ failed │                  │    └──────────┘
                      └────────┘                  v
                                            ┌─────────┐  all exits filled  ┌────────┐
                                            │ closing │───────────────────>│ closed │
                                            └─────────┘                    └────────┘
```

**Transition Rules:**
1. `draft` -> `submitting` (user places the trade)
2. `submitting` -> `open` (entry order fills or partially fills) | `failed` (entry rejected or canceled)
3. `open` -> `modifying` (user adjusts stops, trims, or adds to position) | `closing` (stop/trim hit, or user initiates close)
4. `modifying` -> `open` (modification confirmed) | `closing` (stop hit during modification)
5. `closing` -> `closed` (all exit orders filled, position flat)


## Trade Commands

Trade-level commands that drive state transitions. These reference but are distinct from the order-level commands in [OrderModel.md](OrderModel.md) — an order-level `:fill` event on an entry order is what causes the trade to transition from `submitting` to `open`.

### Command Structure

```clojure
{:uuid       <uuid>
 :trade-uuid <uuid>
 :order-uuid <uuid?>            ; when command targets a specific order
 :type       <command-type>
 :origin     :user | :broker
 :payload    <map>
 :timestamp  <instant>}
```

### Command Types

| Type                | Origin    | Description                                  | Payload                                          |
| ------------------- | --------- | -------------------------------------------- | ------------------------------------------------ |
| `:place-trade`      | `:user`   | Submit entry order to broker                 | `{}`                                             |
| `:move-stop`        | `:user`   | Move stop to a new level                     | `{:order-uuid <uuid>, :stop-price <decimal>}`    |
| `:add-to-position`  | `:user`   | Add shares to an open trade                  | `{:order <order>}`                               |
| `:close-trade`      | `:user`   | Initiate manual close of entire position     | `{}`                                             |
| `:emergency-close`  | `:user`   | Flatten immediately, cancel all exits        | `{}`                                             |
| `:entry-fill`       | `:broker` | Entry order filled (partial or complete)     | `{:fill <fill>}`                                 |
| `:entry-rejected`   | `:broker` | Entry order rejected                         | `{:reason <string>}`                             |
| `:exit-fill`        | `:broker` | Stop or trim filled (partial or complete)    | `{:fill <fill>}`                                 |
| `:exit-canceled`    | `:broker` | Exit order canceled by broker                | `{:order-uuid <uuid>}`                           |

### How Commands Map to State Transitions

| Command              | Origin    | From State         | To State     |
| -------------------- | --------- | ------------------ | ------------ |
| `:place-trade`       | `:user`   | `draft`            | `submitting` |
| `:entry-fill`        | `:broker` | `submitting`       | `open`       |
| `:entry-rejected`    | `:broker` | `submitting`       | `failed`     |
| `:move-stop`         | `:user`   | `open`             | `modifying`  |
| `:add-to-position`   | `:user`   | `open`             | `modifying`  |
| *(modify confirmed)* | `:broker` | `modifying`        | `open`       |
| `:exit-fill`         | `:broker` | `open`/`modifying` | `closing`    |
| `:close-trade`       | `:user`   | `open`             | `closing`    |
| `:emergency-close`   | `:user`   | `open`/`modifying` | `closing`    |
| `:exit-fill` (final) | `:broker` | `closing`          | `closed`     |


## Trade Modifiers (Future)

Captures how exit conditions are managed for an open trade. Modifiers are prioritized — higher-priority modifiers supersede lower ones.

| Modifier             | Priority | Description                                         |
| -------------------- | -------- | --------------------------------------------------- |
| Emergency Close      | highest  | Flatten immediately, cancel all other exit orders   |
| Trailing Stop        | normal   | Dynamic stop that follows price                     |
| Static Stop          | normal   | Fixed stop-loss level(s)                            |
| Trim / Profit Target | normal   | Take partial profit at level(s)                     |

When an Emergency Close fires, it cancels all other active exit orders and submits a market order to flatten. This supersedes all other modifiers.


## Example Scenarios

### 1. Long entry with stop

```
1. :place-trade       (user)    draft -> submitting
2. :entry-fill        (broker)  submitting -> open      (stop order now working)
3. :exit-fill         (broker)  open -> closing -> closed (stop hit, flat)
```

### 2. Scale in, then multiple exits

```
1. :place-trade       (user)    draft -> submitting
2. :entry-fill        (broker)  submitting -> open
3. :add-to-position   (user)    open -> modifying       (second entry placed)
4. :entry-fill        (broker)  modifying -> open       (second entry fills)
5. :exit-fill (trim)  (broker)  open -> closing         (first trim hit)
6. :exit-fill (stop)  (broker)  closing -> closed       (stop hit on remainder)
```

### 3. Entry rejected

```
1. :place-trade       (user)    draft -> submitting
2. :entry-rejected    (broker)  submitting -> failed
```

### 4. Emergency close during modification

```
1. :place-trade       (user)    draft -> submitting
2. :entry-fill        (broker)  submitting -> open
3. :move-stop         (user)    open -> modifying
4. :emergency-close   (user)    modifying -> closing    (all exits canceled, mkt order sent)
5. :exit-fill         (broker)  closing -> closed
```
