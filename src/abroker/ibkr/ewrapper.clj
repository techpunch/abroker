(ns abroker.ibkr.ewrapper
  "Our implementation of the infamous IBKR EWrapper.
  IMPORTANT: Currently based on TWS API v10.37.02"
  (:require [clojure.core.async :as async]
            [clojure.tools.logging :as log]
            [abroker.ibkr.data :as ibdata]
            [abroker.ibkr.codes :refer [tick-fields-by-code]]
            [techpunch.coll :refer [locals-map]])
  (:import [com.ib.client EWrapper]))


;; No processing should be done in this wrapper other than simple field translation
;; and conversion. The purpose is to simply route events to an async channel.

(defn create
  "Creates a com.ib.client.EWrapper impl that routes events to event-chan."
  [event-chan]

  (let [event (fn
                ([type]
                 (log/debug "[ibkr]" (name type))
                 (async/put! event-chan {:type type}))
                ([type details]
                 (log/debug "[ibkr]" (name type) details)
                 (async/put! event-chan
                             (assoc details :type type))))]

    (reify EWrapper

      ; CONNECTION / SESSION

      ; connectAck()
      (connectAck [_]
        (event :connect-ack))

      ; connectionClosed()
      (connectionClosed [_]
        (event :connection-closed))

      ; nextValidId(int orderId)
      (nextValidId [_ order-id]
        (event :next-valid-id (locals-map order-id)))


      ; ERRORS

      ; error(int id, long errorTime, int errorCode, String errorMsg, String advancedOrderRejectJson)
      (error [_ req-id error-time error-code error-msg advanced-order-reject-json]
        (event :error
               (locals-map req-id error-time error-code error-msg advanced-order-reject-json)))

      ; error(Exception e)
      (^void error [_ ^Exception e]
        (event :error {:error-msg (.getMessage e)
                       :exception e}))

      ; error(String str)
      (^void error [_ ^String error-msg]
        (event :error (locals-map error-msg)))


      ; ORDERS

      ; orderStatus(int orderId, String status, Decimal filled, Decimal remaining, double avgFillPrice, long permId, int parentId, double lastFillPrice, int clientId, String whyHeld, double mktCapPrice)
      (orderStatus [_ order-id status filled remaining avg-fill-price
                    perm-id parent-id last-fill-price client-id why-held mkt-cap-price]
        (event :order-status
               (locals-map order-id status filled remaining avg-fill-price perm-id
                           parent-id last-fill-price client-id why-held mkt-cap-price)))

      ; openOrder(int orderId, Contract contract, Order order, OrderState orderState)
      (openOrder [_ order-id contract order order-state]
        (event :open-order
               (locals-map order-id contract order order-state)))

      ; openOrderEnd()
      (openOrderEnd [_]
        (event :open-order-end))

      ; execDetails(int reqId, Contract contract, Execution execution)
      (execDetails [_ req-id contract execution]
        (event :exec-details
               (locals-map req-id contract execution)))

      ; execDetailsEnd(int reqId)
      (execDetailsEnd [_ req-id]
        (event :exec-details-end (locals-map req-id)))

      ; orderBound(long permId, int clientId, int orderId)
      (orderBound [_ perm-id client-id order-id]
        (event :order-bound
               (locals-map perm-id client-id order-id)))

      ; completedOrder(Contract contract, Order order, OrderState orderState)
      (completedOrder [_ contract order order-state]
        (event :completed-order
               (locals-map contract order order-state)))

      ; completedOrdersEnd()
      (completedOrdersEnd [_]
        (event :completed-orders-end))


      ; ACCOUNT / PORTFOLIO

      ; accountSummary(int reqId, String account, String tag, String value, String currency)
      (accountSummary [_ req-id account tag value currency]
        (event :account-summary
               (locals-map req-id account tag value currency)))

      ; accountSummaryEnd(int reqId)
      (accountSummaryEnd [_ req-id]
        (event :account-summary-end (locals-map req-id)))

      ; updateAccountValue(String key, String value, String currency, String accountName)
      (updateAccountValue [_ key val currency account-name]
        (event :update-account-value
               (locals-map key val currency account-name)))

      ; updatePortfolio(Contract contract, Decimal position, double marketPrice, double marketValue, double averageCost, double unrealizedPNL, double realizedPNL, String accountName)
      (updatePortfolio [_ contract position market-price market-value
                        average-cost unrealized-pnl realized-pnl account-name]
        (event :update-portfolio
               (locals-map contract position market-price market-value
                           average-cost unrealized-pnl realized-pnl account-name)))

      ; position(String account, Contract contract, Decimal pos, double avgCost)
      (position [_ account contract pos avg-cost]
        (event :position
               (locals-map account contract pos avg-cost)))

      ; positionEnd()
      (positionEnd [_]
        (event :position-end))

      ; updateAccountTime(String timeStamp)
      (updateAccountTime [_ time-stamp]
        (event :update-account-time (locals-map time-stamp)))

      ; accountDownloadEnd(String accountName)
      (accountDownloadEnd [_ account-name]
        (event :account-download-end (locals-map account-name)))

      ; positionMulti(int reqId, String account, String modelCode, Contract contract, Decimal pos, double avgCost)
      (positionMulti [_ req-id account model-code contract pos avg-cost]
        (event :position-multi
               (locals-map req-id account model-code contract pos avg-cost)))

      ; positionMultiEnd(int reqId)
      (positionMultiEnd [_ req-id]
        (event :position-multi-end (locals-map req-id)))

      ; accountUpdateMulti(int reqId, String account, String modelCode, String key, String value, String currency)
      (accountUpdateMulti [_ req-id account model-code key value currency]
        (event :account-update-multi
               (locals-map req-id account model-code key value currency)))

      ; accountUpdateMultiEnd(int reqId)
      (accountUpdateMultiEnd [_ req-id]
        (event :account-update-multi-end (locals-map req-id)))

      ; managedAccounts(String accountsList)
      (managedAccounts [_ accounts-list]
        (event :managed-accounts (locals-map accounts-list)))

      ; receiveFA(int faDataType, String xml)
      (receiveFA [_ fa-data-type xml]
        (event :receive-fa
               (locals-map fa-data-type xml)))


      ; SECURITIES (CONTRACTS)

      ; contractDetails(int reqId, ContractDetails contractDetails)
      (contractDetails [_ req-id contract-details]
        (event :contract-details
               (locals-map req-id contract-details)))

      ; bondContractDetails(int reqId, ContractDetails contractDetails)
      (bondContractDetails [_ req-id contract-details]
        (event :bond-contract-details
               (locals-map req-id contract-details)))

      ; contractDetailsEnd(int reqId)
      (contractDetailsEnd [_ req-id]
        (event :contract-details-end (locals-map req-id)))


      ; QUOTING & DATA

      ; historicalData(int reqId, Bar bar)
      (historicalData [_ req-id bar]
        (event :historical-data
               (locals-map req-id bar)))

      ; historicalDataUpdate(int reqId, Bar bar)
      (historicalDataUpdate [_ req-id bar]
        (event :historical-data-update
               (locals-map req-id bar)))

      ; historicalDataEnd(int reqId, String startDateStr, String endDateStr)
      (historicalDataEnd [_ req-id start-date-str end-date-str]
        (event :historical-data-end
               (locals-map req-id start-date-str end-date-str)))

      ;; NOTE I've combined tickPrice tickSize tickGeneric tickString into
      ;; event type :tick-field

      ; tickPrice(int reqId, int field, double price, TickAttrib attrib)
      (tickPrice [_ req-id field price attrib]
        (let [field (tick-fields-by-code field)]
          (event :tick-field
                 (locals-map req-id field price attrib))))

      ; tickSize(int reqId, int field, Decimal size)
      (tickSize [_ req-id field size]
        (let [field (tick-fields-by-code field)
              size (ibdata/as-long size)]
          (event :tick-field
                 (locals-map req-id field size))))

      ; tickGeneric(int reqId, int field, double value)
      (tickGeneric [_ req-id field value]
        (let [field (tick-fields-by-code field)]
          (event :tick-field
                 (locals-map req-id field value))))

      ; tickString(int reqId, int field, String value)
      (tickString [_ req-id field value]
        (let [field (tick-fields-by-code field)]
          (event :tick-field
                 (locals-map req-id field value))))

      ; tickSnapshotEnd(int reqId)
      (tickSnapshotEnd [_ req-id]
        (event :tick-snapshot-end (locals-map req-id)))


      ; realtimeBar(int reqId, long time, double open, double high, double low, double close, Decimal volume, Decimal wap, int count)
      (realtimeBar [_ req-id time open high low close volume wap count]
        (event :realtime-bar
               (locals-map req-id time open high low close volume wap count)))

      ; updateMktDepth(int reqId, int position, int operation, int side, double price, Decimal size)
      (updateMktDepth [_ req-id position operation side price size]
        (event :update-mkt-depth
               (locals-map req-id position operation side price size)))

      ; updateMktDepthL2(int reqId, int position, String marketMaker, int operation, int side, double price, Decimal size, boolean isSmartDepth)
      (updateMktDepthL2 [_ req-id position market-maker operation
                         side price size is-smart-depth]
        (event :update-mkt-depth-l2
               (locals-map req-id position market-maker operation
                           side price size is-smart-depth)))

      ; currentTime(long time)
      (currentTime [_ time]
        (event :current-time (locals-map time)))

      ; currentTimeInMillis(long timeInMillis)
      (currentTimeInMillis [_ time-in-millis]
        (event :current-time-in-millis (locals-map time-in-millis)))

      ; fundamentalData(int reqId, String data)
      (fundamentalData [_ req-id data]
        (event :fundamental-data
               (locals-map req-id data)))

      ; marketDataType(int reqId, int marketDataType)
      (marketDataType [_ req-id market-data-type]
        (event :market-data-type
               (locals-map req-id market-data-type)))


      ; NEWS

      ; updateNewsBulletin(int msgId, int msgType, String message, String origExchange)
      (updateNewsBulletin [_ msg-id msg-type message orig-exchange]
        (event :update-news-bulletin
               (locals-map msg-id msg-type message orig-exchange)))


      ; SCANNER

      ; scannerParameters(String xml)
      (scannerParameters [_ xml]
        (event :scanner-parameters (locals-map xml)))

      ; scannerData(int reqId, int rank, ContractDetails contractDetails, String distance, String benchmark, String projection, String legsStr)
      (scannerData [_ req-id rank contract-details distance benchmark projection legs-str]
        (event :scanner-data
               (locals-map req-id rank contract-details distance benchmark projection legs-str)))

      ; scannerDataEnd(int reqId)
      (scannerDataEnd [_ req-id]
        (event :scanner-data-end (locals-map req-id)))


      ; OTHER TICK STUFF I haven't needed yet

      ; tickOptionComputation(int reqId, int field, int tickAttrib, double impliedVol, double delta, double optPrice, double pvDividend, double gamma, double vega, double theta, double undPrice)
      (tickOptionComputation [_ req-id field tick-attrib implied-vol delta
                              opt-price pv-dividend gamma vega theta und-price]
        (event :tick-option-computation
               (locals-map req-id field tick-attrib implied-vol delta
                           opt-price pv-dividend gamma vega theta und-price)))

      ; tickEFP(int reqId, int tickType, double basisPoints, String formattedBasisPoints, double impliedFuture, int holdDays, String futureLastTradeDate, double dividendImpact, double dividendsToLastTradeDate)
      (tickEFP [_ req-id tick-type basis-points formatted-basis-points
                implied-future hold-days future-last-trade-date dividend-impact
                dividends-to-last-trade-date]
        (event :tick-efp
               (locals-map req-id tick-type basis-points formatted-basis-points
                           implied-future hold-days future-last-trade-date dividend-impact
                           dividends-to-last-trade-date)))

      ; historicalTicks(int reqId, List<HistoricalTick> ticks, boolean done)
      (historicalTicks [_ req-id ticks done]
        (event :historical-ticks
               (locals-map req-id ticks done)))

      ; historicalTicksBidAsk(int reqId, List<HistoricalTickBidAsk> ticks, boolean done)
      (historicalTicksBidAsk [_ req-id ticks done]
        (event :historical-ticks-bid-ask
               (locals-map req-id ticks done)))

      ; historicalTicksLast(int reqId, List<HistoricalTickLast> ticks, boolean done)
      (historicalTicksLast [_ req-id ticks done]
        (event :historical-ticks-last
               (locals-map req-id ticks done)))

      ; tickByTickAllLast(int reqId, int tickType, long time, double price, Decimal size, TickAttribLast tickAttribLast, String exchange, String specialConditions)
      (tickByTickAllLast [_ req-id tick-type time price size tick-attrib-last
                          exchange special-conditions]
        (event :tick-by-tick-all-last
               (locals-map req-id tick-type time price size tick-attrib-last
                           exchange special-conditions)))

      ; tickByTickBidAsk(int reqId, long time, double bidPrice, double askPrice, Decimal bidSize, Decimal askSize, TickAttribBidAsk tickAttribBidAsk)
      (tickByTickBidAsk [_ req-id time bid-price ask-price bid-size ask-size
                         tick-attrib-bid-ask]
        (event :tick-by-tick-bid-ask
               (locals-map req-id time bid-price ask-price bid-size ask-size
                           tick-attrib-bid-ask)))

      ; tickByTickMidPoint(int reqId, long time, double midPoint)
      (tickByTickMidPoint [_ req-id time mid-point]
        (event :tick-by-tick-mid-point
               (locals-map req-id time mid-point)))


      ; TODO Sort through the rest of these

      ; deltaNeutralValidation(int reqId, DeltaNeutralContract deltaNeutralContract)
      (deltaNeutralValidation [_ req-id delta-neutral-contract]
        (event :delta-neutral-validation
               (locals-map req-id delta-neutral-contract)))

      ; commissionAndFeesReport(CommissionAndFeesReport commissionAndFeesReport)
      (commissionAndFeesReport [_ commission-report]
        (event :commission-and-fees-report (locals-map commission-report)))

      ; replaceFAEnd(int reqId, String text)
      (replaceFAEnd [_ req-id text]
        (event :replace-fa-end
               (locals-map req-id text)))

      ; wshMetaData(int reqId, String dataJson)
      (wshMetaData [_ req-id data-json]
        (event :wsh-meta-data
               (locals-map req-id data-json)))

      ; wshEventData(int reqId, String dataJson)
      (wshEventData [_ req-id data-json]
        (event :wsh-event-data
               (locals-map req-id data-json)))

      ; rerouteMktDataReq(int reqId, int conId, String exchange)
      (rerouteMktDataReq [_ req-id con-id exchange]
        (event :reroute-mkt-data-req
               (locals-map req-id con-id exchange)))

      ; rerouteMktDepthReq(int reqId, int conId, String exchange)
      (rerouteMktDepthReq [_ req-id con-id exchange]
        (event :reroute-mkt-depth-req
               (locals-map req-id con-id exchange)))

      ; marketRule(int marketRuleId, PriceIncrement[] priceIncrements)
      (marketRule [_ market-rule-id price-increments]
        (event :market-rule
               (locals-map market-rule-id price-increments)))

      ; pnl(int reqId, double dailyPnL, double unrealizedPnL, double realizedPnL)
      (pnl [_ req-id daily-pnl unrealized-pnl realized-pnl]
        (event :pnl
               (locals-map req-id daily-pnl unrealized-pnl realized-pnl)))

      ; pnlSingle(int reqId, Decimal pos, double dailyPnL, double unrealizedPnL, double realizedPnL, double value)
      (pnlSingle [_ req-id pos daily-pnl unrealized-pnl realized-pnl value]
        (event :pnl-single
               (locals-map req-id pos daily-pnl unrealized-pnl realized-pnl value)))

      ; softDollarTiers(int reqId, SoftDollarTier[] tiers)
      (softDollarTiers [_ req-id tiers]
        (event :soft-dollar-tiers
               (locals-map req-id tiers)))

      ; familyCodes(FamilyCode[] familyCodes)
      (familyCodes [_ family-codes]
        (event :family-codes (locals-map family-codes)))

      ; symbolSamples(int reqId, ContractDescription[] contractDescriptions)
      (symbolSamples [_ req-id contract-descriptions]
        (event :symbol-samples
               (locals-map req-id contract-descriptions)))

      ; mktDepthExchanges(DepthMktDataDescription[] depthMktDataDescriptions)
      (mktDepthExchanges [_ depth-mkt-data-descriptions]
        (event :mkt-depth-exchanges (locals-map depth-mkt-data-descriptions)))

      ; tickNews(int reqId, long timeStamp, String providerCode, String articleId, String headline, String extraData)
      (tickNews [_ req-id time-stamp provider-code article-id headline extra-data]
        (event :tick-news
               (locals-map req-id time-stamp provider-code article-id headline extra-data)))

      ; smartComponents(int reqId, Map<Integer, Entry<String, Character>> theMap)
      (smartComponents [_ req-id the-map]
        (event :smart-components
               (locals-map req-id the-map)))

      ; tickReqParams(int reqId, double minTick, String bboExchange, int snapshotPermissions)
      (tickReqParams [_ req-id min-tick bbo-exchange snapshot-permissions]
        (event :tick-req-params
               (locals-map req-id min-tick bbo-exchange snapshot-permissions)))

      ; newsProviders(NewsProvider[] newsProviders)
      (newsProviders [_ news-providers]
        (event :news-providers (locals-map news-providers)))

      ; newsArticle(int requestId, int articleType, String articleText)
      (newsArticle [_ request-id article-type article-text]
        (event :news-article
               (locals-map request-id article-type article-text)))

      ; historicalNews(int requestId, String time, String providerCode, String articleId, String headline)
      (historicalNews [_ request-id time provider-code article-id headline]
        (event :historical-news
               (locals-map request-id time provider-code article-id headline)))

      ; historicalNewsEnd(int requestId, boolean hasMore)
      (historicalNewsEnd [_ request-id has-more]
        (event :historical-news-end
               (locals-map request-id has-more)))

      ; headTimestamp(int reqId, String headTimestamp)
      (headTimestamp [_ req-id head-timestamp]
        (event :head-timestamp
               (locals-map req-id head-timestamp)))

      ; histogramData(int reqId, List<HistogramEntry> items)
      (histogramData [_ req-id items]
        (event :histogram-data
               (locals-map req-id items)))

      ; historicalSchedule(int reqId, String startDateTime, String endDateTime, String timeZone, List<HistoricalSession> sessions)
      (historicalSchedule [_ req-id start-date-time end-date-time time-zone sessions]
        (event :historical-schedule
               (locals-map req-id start-date-time end-date-time time-zone sessions)))

      ; securityDefinitionOptionalParameter(int reqId, String exchange, int underlyingConId, String tradingClass, String multiplier, Set<String> expirations, Set<Double> strikes)
      (securityDefinitionOptionalParameter [_ req-id exchange underlying-con-id
                                            trading-class multiplier expirations strikes]
        (event :security-definition-optional-parameter
               (locals-map req-id exchange underlying-con-id
                           trading-class multiplier expirations strikes)))

      ; securityDefinitionOptionalParameterEnd(int reqId)
      (securityDefinitionOptionalParameterEnd [_ req-id]
        (event :security-definition-optional-parameter-end (locals-map req-id)))

      ; userInfo(int reqId, String whiteBrandingId)
      (userInfo [_ req-id white-branding-id]
        (event :user-info
               (locals-map req-id white-branding-id)))

      ; verifyMessageAPI(String apiData)
      (verifyMessageAPI [_ api-data]
        (event :verify-message-api (locals-map api-data)))

      ; verifyCompleted(boolean isSuccessful, String errorText)
      (verifyCompleted [_ is-successful error-text]
        (event :verify-completed
               (locals-map is-successful error-text)))

      ; verifyAndAuthMessageAPI(String apiData, String xyzChallenge)
      (verifyAndAuthMessageAPI [_ api-data xyz-challenge]
        (event :verify-and-auth-message-api
               (locals-map api-data xyz-challenge)))

      ; verifyAndAuthCompleted(boolean isSuccessful, String errorText)
      (verifyAndAuthCompleted [_ is-successful error-text]
        (event :verify-and-auth-completed
               (locals-map is-successful error-text)))

      ; displayGroupList(int reqId, String groups)
      (displayGroupList [_ req-id groups]
        (event :display-group-list
               (locals-map req-id groups)))

      ; displayGroupUpdated(int reqId, String contractInfo)
      (displayGroupUpdated [_ req-id contract-info]
        (event :display-group-updated
               (locals-map req-id contract-info)))


      ; Protobuf methods - ignoring these as it doesn't seem IBKR has fully embraced yet?

      (orderStatusProtoBuf [_ order-status-proto]
        (log/trace "[ibkr] orderStatusProtoBuf"))

      (openOrderProtoBuf [_ open-order-proto]
        (log/trace "[ibkr] openOrderProtoBuf"))

      (openOrdersEndProtoBuf [_ open-orders-end]
        (log/trace "[ibkr] orderOrdersEndProtoBuf"))

      (errorProtoBuf [_ error-message-proto]
        (log/trace "[ibkr] errorProtoBuf"))

      (execDetailsProtoBuf [_ execution-details-proto]
        (log/trace "[ibkr] execDetailProtoBuf"))

      (execDetailsEndProtoBuf [_ execution-details-end-proto]
        (log/trace "[ibkr] execDetailsEndProtoBuf")))))
