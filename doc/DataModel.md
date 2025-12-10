# Data Model

THIS DOC IS TOTALLY INCOMPLETE WIP HALF BAKED

A data model for stock trading to capture the concepts of Trades, Strategies, Orders, States, Actions. We need a high level model that is broker agnostic, and can support creating trades, modifying them (e.g. adding to a trade, setting a trim level, moving one of potentially several stops up), being able to track the state of actions as they're pending/rejected/etc. from a broker.

The very high level idea here is to have a top level immutable Trade object that uses Commands to change state and has a History Stack of commands applied.


## Example Scenarios Model Must Support:
1. Enter a trade before open to take a "full position" buy over yesterday's high with a stop at yesterday's low:
Trade:
```
{
:alloc :tax20, :symbol :nvda, :action buy, :risk-scale 100,
:state :created :strategy {:type :prev-high :timeframe :daily}
}
```

Trade States:
created, provisioning, open, reconfiguring, closed
Trade has many Orders, Order States: ...

Fills

Trade:
trade-id, created, alloc, symbol,
action (:buy for long, :sell for short),
risk-scale (int 0-100 for pct of "full position"),
state, strategy-info, trim-orders (ids), stop-orders (ids), filled-orders (ids)

Trade Modifiers:
Captures not only static Trims and Stops, but also concepts like a Trailing Stop, or an Emergency Trade Circuit Breaker, and how different modifiers may supercede others, e.g. I may have static Trims and Stops that are almost always used but an Emergency Close would overrule all those.

Trade Strategy: key

Trade State: Local Only, Active, Actions Pending

Commands/Actions:
Save Order, Place Order, Modify Order, Cancel Order, Detect Fill, Detect Mod, Detect Cancel, Detect Pause
