# Position Data Model

Positions represent the **broker's view** of what you hold. They are snapshot entities, not command-sourced — overwritten on each refresh.

## Schema

```clojure
{:uuid        <uuid>           ; deterministic from (account, symbol, type, subtype)
 :account     <string>         ; broker account id, e.g. "U1234567"
 :instrument  {:type   <keyword>   ; :stock :option :crypto :future :forex
               :symbol <string>
               :subtype <keyword?> ; :put :call — options only
               }
 :quantity    <bigdecimal>     ; positive = long, negative = short; never zero
 :avg-cost    <double>         ; per-share average cost; always > 0
 :snapshot-at <instant>}       ; when broker last confirmed this position
```

## Key Rules

- **Snapshot replacement**: Positions don't have a lifecycle or command log. Each sync overwrites the previous snapshot.
- **Deterministic UUID**: Computed from `(account, symbol, type, subtype)` via UUID v3 (`nameUUIDFromBytes`). Same position always gets the same UUID — upsert is just `save!`.
- **Ghost filtering**: Positions with `quantity = 0` or `avg-cost <= 0` are never stored. These are IBKR artifacts from FA group trades.
- **Reconciliation**: On full refresh (`position-end`), positions missing from the new snapshot are deleted from the store. The `position-end` callback confirms completeness, making this safe.

## Known Limitations

- Options positions don't include expiry/strike in the key yet (IBKR Contract has them, `ibdata/position` doesn't extract them). Fine for pre-alpha.
- Account-level data (NAV, margin) is a separate future concern — not part of this model.
