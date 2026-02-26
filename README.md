# FIX Test Harness

A FIX 4.4 client test harness that connects to the `fix-simulator` (or any compatible FIX 4.4 acceptor), maintains the session with heartbeats, and plays back YAML-driven scenario files.

---

## Quick Start

```bash
# 1. Make sure the FIX simulator is running on localhost:9876

# 2. Build the fat-jar
chmod +x build.sh && ./build.sh

# 3. Run the default scenario (order_flow.yml)
java -jar target/fix-testharness-1.0.0-SNAPSHOT.jar

# 4. Run a specific scenario
java -jar target/fix-testharness-1.0.0-SNAPSHOT.jar config/client.cfg scenarios/quote_flow.yml
```

---

## Configuration

`config/client.cfg` is a standard QuickFIX/J initiator settings file.

| Key | Default | Description |
|-----|---------|-------------|
| `SocketConnectHost` | `localhost` | Server hostname |
| `SocketConnectPort` | `9876` | Server port |
| `SenderCompID` | `TESTCLIENT` | Client CompID |
| `TargetCompID` | `EXCHANGE` | Server CompID |
| `HeartBtInt` | `30` | Heartbeat interval (seconds) |
| `ResetOnLogon` | `Y` | Reset sequence numbers on every connect |

### Environment variable overrides

| Variable | Description |
|----------|-------------|
| `FIX_HOST` | Override `SocketConnectHost` |
| `FIX_PORT` | Override `SocketConnectPort` |
| `FIX_TIMEOUT` | Scenario completion timeout in seconds (default `120`) |

```bash
FIX_HOST=10.0.0.5 FIX_PORT=9876 FIX_TIMEOUT=60 java -jar target/fix-testharness-*.jar
```

---

## Scenario File Format

Scenarios are YAML files located in the `scenarios/` directory.

```yaml
name: "My Scenario"
description: "Optional description"
steps:
  - type: new_order
    ...
  - type: wait
    ms: 500
```

### Step Types

#### `new_order` — NewOrderSingle (D)

```yaml
- type: new_order
  clOrdId: "ORD001"       # required; must be unique per session
  symbol: "AAPL"          # required
  side: BUY               # required: BUY | SELL
  ordType: LIMIT          # required: MARKET | LIMIT | STOP | STOP_LIMIT
  qty: 100                # required; integer
  price: 150.00           # required for LIMIT / STOP_LIMIT
  stopPx: 148.00          # required for STOP / STOP_LIMIT
  tif: DAY                # optional: DAY | GTC | IOC | FOK  (default: DAY)
  delayMs: 500            # optional: pause before sending (ms)
```

#### `cancel` — OrderCancelRequest (F)

```yaml
- type: cancel
  clOrdId: "CXL001"       # new unique ID for this cancel request
  origClOrdId: "ORD001"   # ClOrdID of the order to cancel
  symbol: "AAPL"
  side: BUY
  delayMs: 0
```

#### `replace` — OrderCancelReplaceRequest (G)

```yaml
- type: replace
  clOrdId: "RPL001"
  origClOrdId: "ORD001"
  symbol: "AAPL"
  side: BUY
  ordType: LIMIT
  qty: 200
  price: 149.00
  tif: GTC
  delayMs: 0
```

#### `quote` — Quote (S)

```yaml
- type: quote
  quoteId: "Q001"
  symbol: "AAPL"
  bidPx: 149.90
  bidSize: 500
  offerPx: 150.10
  offerSize: 500
  delayMs: 0
```

At least one side (bid or offer) must be provided.

#### `mass_quote` — MassQuote (i)

```yaml
- type: mass_quote
  massQuoteId: "MQ001"
  entries:
    - quoteEntryId: "E001"
      symbol: "AAPL"
      bidPx: 149.90
      bidSize: 500
      offerPx: 150.10
      offerSize: 500
    - quoteEntryId: "E002"
      symbol: "MSFT"
      bidPx: 299.50
      bidSize: 200
      offerPx: 300.00
      offerSize: 200
  delayMs: 0
```

#### `wait` — sleep

```yaml
- type: wait
  ms: 2000      # pause for 2 seconds
```

#### `wait_for_reports` — wait for execution reports

Blocks until `count` ExecutionReport messages have been received from the server (since the last `wait_for_reports` step), or until `timeoutMs` elapses.

```yaml
- type: wait_for_reports
  count: 3          # expected number of ExecutionReports
  timeoutMs: 5000   # max wait in ms (default 10 000)
```

---

## Included Scenarios

| File | Description |
|------|-------------|
| `scenarios/order_flow.yml` | Limit orders, partial fills, replace, cancel, IOC, FOK-market |
| `scenarios/quote_flow.yml` | Quote (S) and MassQuote (i); crosses against quote liquidity |
| `scenarios/stop_orders.yml` | STOP and STOP LIMIT orders; trigger and cancel |

---

## Project Structure

```
fix.testharness/
├── build.sh
├── pom.xml
├── config/
│   └── client.cfg                          QuickFIX/J initiator settings
├── scenarios/
│   ├── order_flow.yml
│   ├── quote_flow.yml
│   └── stop_orders.yml
└── src/main/java/com/fix/testharness/
    ├── Main.java                            Entry point
    ├── FIXTestHarnessApp.java               QuickFIX/J Application
    ├── scenario/
    │   ├── Scenario.java                   Top-level scenario POJO
    │   ├── ScenarioStep.java               Per-step POJO
    │   ├── QuoteEntry.java                 Mass-quote entry POJO
    │   ├── ScenarioLoader.java             YAML → Scenario via SnakeYAML
    │   └── ScenarioRunner.java             Executes steps; builds FIX messages
    └── report/
        └── ExecutionReporter.java          Pretty-prints incoming messages
```

---

## Dependencies

| Library | Version | Purpose |
|---------|---------|---------|
| `quickfixj-core` | 2.3.1 | FIX protocol engine |
| `quickfixj-messages-fix44` | 2.3.1 | FIX 4.4 message types & dictionary |
| `snakeyaml` | 2.2 | YAML scenario parsing |
| `logback-classic` | 1.4.14 | Logging |

---

## Adding a New Scenario

1. Create `scenarios/my_scenario.yml` following the format above.
2. Run:
   ```bash
   java -jar target/fix-testharness-*.jar config/client.cfg scenarios/my_scenario.yml
   ```

All logs are written to both the console and `logs/testharness.log`.
Raw FIX message traces are stored in `logs/` by QuickFIX/J (controlled by `FileLogPath` in `client.cfg`).
