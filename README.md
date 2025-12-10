```clj
(defmethod what-is :abroker [_]
  "Abstract Broker")
```

![](https://img.shields.io/github/v/tag/techpunch/abroker)

## Pre-Alpha Warning

This project is in its infancy, there are bugs and testing gaps, and the core design is in flux. Not recommended for use yet, but if you have questions or comments, do let me know. I'm using it frequently to interact with Interactive Brokers.

## Purpose

To provide a batteries-included abstraction layer atop different brokers, starting with Interactive Brokers' TWS API. The TWS API has pitfalls and gotchas that this lib will smooth out, such as:

- Connect/reconnect. TWS's periodic auto shutdown/restart can be brittle, so we detect connection loss, and use an expotential backoff/retry reconnect scheme.

- Historical vs real time market data interruptions. Real time market data streaming is more resilient than "historical" streaming. For instance, if you want to stream 5 minute price bars, it works great until a connection or other issue comes up. Where real time ticks would resume on reconnect, 5 minute price bar streams will stop and not resume on reconnect. We'll provide a way to auto resume.

- Somewhat cumbersome event-driven async response architecture, which we can smooth with Clojure's async channels

This lib provides its own data view of orders, instruments, price bars, quotes, etc., so we can talk to different brokers using the same language of clojure data structures. That way we'll also be able to do cool things like:

- Fail over for market data. As IBKR makes clear in their docs, market data streaming is not their core business. We'd like to be able to detect market data problems, and fail over to another broker like Alpaca, or vice-versa.

- Execute strategies across brokers. Maybe you have accounts at both IBKR & Schwab. We'll be able to enter a trade that can execute across both accounts, including stop orders, trimming, and take profit rules.



## Installation

This lib uses the TWS API Stable jar version 10.37.02.

1. Download the jar (yeah, I know) from https://interactivebrokers.github.io/
2. Cmd line `unzip [downloaded file]`
3. `cd IBJts/source/JavaClient`
4. `cp TwsApi.jar twsapi-10.37.02.jar`

Now install it in your local mvn repo:
```
mvn install:install-file \
  -Dfile=twsapi-10.37.02.jar \
  -DgroupId=com.ib \
  -DartifactId=twsapi \
  -Dversion=10.37.02 \
  -Dpackaging=jar
```

abroker's deps.edn should now be satisfied. In your project's deps.edn :deps map add:
```edn
techpunch/abroker {:git/url "https://github.com/techpunch/abroker.git"
                   :git/tag  "v0.0.1"
                   :git/sha  "(latest commit)"}
```
Or clone the project locally and use:
```edn
techpunch/abroker {:local/root "../abroker"}
```
You can then, in the resources dir, `cp config.sample.edn config.edn` and modify config.edn for your account and TWS setup. config.edn is .gitignored so you don't have to worry about it committing. If you don't clone abroker locally, you'll still need a config.edn on the classpath (or set with cprop's conf env var) when running your project.


## Usage

Example sending a 100 share GTC market order with an attached stop, assuming you have an account defined in config.edn with key :ira

```clj
(require '[abroker.data :refer [stock order mkt gtc stp add-stop]])
(require '[abroker.trading :refer [send-order!]])

(let [buffett (stock :brk.b)
      orders (-> (order :ira :buy 100)
                 (mkt)
                 (gtc)
                 (add-stop (-> (order :ira :sell 100)
                               (stp 49.99)
                               (gtc))))]
  (send-order! buffett orders))
```


---
Copyright © 2025 Techpunch LLC
