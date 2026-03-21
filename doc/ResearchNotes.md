# Research Notes


## OCA / OCO Groups (Dec2025)

IBKR OCA GROUPS - **NEVER AGAIN** research whether you can have a take partial profit order fully fill and reduce others without canceling others. Trust me, you can't, and LLMs may lead you down a fake path. Last researched and tested on 11/9/25, and I've resesarched it in the past at least once many months ago. Don't waste time on this again!


IBKR has OCA orders, and for them an OCO would pretty much be a regular OCA bracket with exactly 2 orders. OCAs give more flexibility to have multiple exit orders beyond a standard bracket, e.g. 1 regular hours stop at a normal level, 1 after hours emergency stop and a much lower level, and 1 take profit order.

Alpaca has OCO

Schwab has OCO


## Order Types (Dec2025)

IBKR  
from `(devutil/dump-enum-with-api-string "com.ib.client.OrderType")`  
MKT LMT STP STP_LMT REL TRAIL BOX_TOP FIX_PEGGED LIT LMT_PLUS_MKT LOC MIDPRICE MIT MKT_PRT MOC MTL PASSV_REL PEG_BENCH PEG_BEST PEG_MID PEG_MKT PEG_PRIM PEG_STK REL_PLUS_LMT REL_PLUS_MKT SNAP_MID SNAP_MKT SNAP_PRIM STP_PRT TRAIL_LIMIT TRAIL_LIT TRAIL_LMT_PLUS_MKT TRAIL_MIT TRAIL_REL_PLUS_MKT VOL VWAP QUOTE PEG_PRIM_VOL PEG_MID_VOL PEG_MKT_VOL PEG_SRF_VOL

Alpaca  
https://docs.alpaca.markets/reference/getorderbyorderid-1  
market limit stop stop_limit trailing_stop

Schwab  
Same as Alpaca

## Order TIFs (Dec2025)

IBKR  
from `(devutil/dump-enum-with-api-string "com.ib.client.Types$TimeInForce")`  
DAY GTC OPG IOC GTD GTT AUC FOK GTX DTC Minutes

Alpaca  
https://docs.alpaca.markets/reference/getorderbyorderid-1  
day gtc opg cls ioc fok

Schwab  
Day Day+Ext GTC GTC+Ext FOK IOC ExtAM ExtPM

## Order Status
see [ResearchOrderStatus.md](ResearchOrderStatus.md)
