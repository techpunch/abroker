# Allocation Model

An allocation specifies which account(s) an Order or Trade applies to.

When a trade targets an allocation, it fans out into one Trade per account (see [TradeModel.md](TradeModel.md)). The allocation defines *how* to split; broker adapters decide whether to use a native allocation or emit separate orders.

There are 2 types of Allocations: Accounts and Account Groups.


## Account

A single brokerage account.

```clojure
{:id        <string>            ; broker account id (e.g. "U1234567")
 :broker    :ibkr | :alpaca | :schwab
 :label     <string?>           ; human-friendly name (e.g. "Roth IRA")
 :owner     <string>            ; owner nickname, e.g. "bob", "mom"
 :category  :taxable | :exempt} ; used for wash sale detection warnings when combined with owner
```


## Account Group

```clojure
{:name          <keyword>       ; e.g. :iras, :all-accounts
 :method        :qty            ; only :qty supported for now, default is :qty if not specified
 :brokers       [{:broker       <keyword>       ; e.g. :ibkr
                  :native-group <string?>        ; name of allocation group on this platform, nil = synthetic
                  :accounts     [{:account-id <string>
                                  :value      <number?>}  ; meaning depends on method; for :qty this is num shares
                                 ...]}
                 ...]
 :min-lot-size  <pos-int?>}     ; total order size must be divisible by this
```

### Distribution Method

Only `:qty` is supported (default, can be omitted). Each account's `:value` is the minimum number of shares for that account per order. The values define a ratio — e.g. values of 3, 6, and 11 with `:min-lot-size` 20 ensures orders scale in that proportion. For native groups, the values in abroker need not match the broker-side values exactly, but the ratio must be the same (e.g. the broker might use 90, 180, 330).

**IBKR native group warning:** `:qty` maps to IBKR's "User-Specified" allocation method. IBKR supports other methods (Equal, Available Equity, Net Liquidation, etc.) that *may* work with a native group, but extreme caution is warranted if you don't know exactly how they work. See [IBKR Allocation Methods](https://www.ibkrguides.com/advisorportal/trade/allocation-methods.htm) and test thoroughly before using any native group that isn't User-Specified. Edge case to consider: you open a position with an IBKR type=User-Specified alloc, then later add to it using and type=Equal, then try to trim or close using one of the groups, you will likely be very surprised by the result! Best to keep it very simple by either using only User-Specified or being certain to not mix and match.

Other distribution methods may be added in the future. If this is important to you, let me know or make the change and PR it if you're able.


### Native vs Synthetic

When a broker entry has a `:native-group`, the adapter sends one order referencing that group name and the broker handles distribution internally.

When `:native-group` is nil, the system computes the per-account split and creates separate orders. From the data model's perspective, the result is the same: one Trade per account, each with its own orders.

An allocation can mix native and synthetic across brokers — e.g. the IBKR side uses a native group while the Schwab side gets separate orders.


### Examples

Multi-account, single broker, native group:
```clojure
{:name :iras
 :min-lot-size 20
 :brokers [{:broker :ibkr
            :native-group "IRA_Accounts"
            :accounts [{:account-id "U1234567" :value 12}
                       {:account-id "U7654321" :value 8}]}]}
```

Cross-broker, mixed native/synthetic:
```clojure
{:name :all-accounts
 :min-lot-size 10
 :brokers [{:broker :ibkr
            :native-group "All_IBKR"
            :accounts [{:account-id "U1234567" :value 3}
                       {:account-id "U7654321" :value 4}]}
           {:broker :schwab
            :accounts [{:account-id "SCHW-001" :value 3}]}]}
```
