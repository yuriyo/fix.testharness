# FIX Test Harness

A FIX 4.2 client test harness that connects to the `fix-simulator` (or any compatible FIX 4.2 acceptor), maintains the session with heartbeats, and plays back YAML-driven scenario files.

**Docker is the only requirement.** The Java build, the Maven build and the scenario runs all happen in containers. Nothing needs to be installed on the host besides Docker.

---

## Quick Start

```bash
# 1. Make sure the FIX simulator is running (port 9876 on the host)

# 2. Build the image. Maven runs inside the container.
./build.sh

# 3. Run the scenarios. With no arguments, all non-perf scenarios run in order.
./play_scenario.sh --host host.docker.internal --port 9876 order_flow

# 4. Run a specific scenario with a specific config
./play_scenario.sh --config config/client.cfg scenarios/quote_flow.yml

# List the scenarios
./play_scenario.sh --list
```

`--host` and `--port` override `SocketConnectHost` and `SocketConnectPort` for one run. Use `host.docker.internal` to reach a simulator running on your machine from inside the container.

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

These are read by the container. `play_scenario.sh` does not forward them; use `--host` and `--port` instead. To set them yourself, run the image directly:

| Variable | Description |
|----------|-------------|
| `FIX_HOST` | Override `SocketConnectHost` |
| `FIX_PORT` | Override `SocketConnectPort` |
| `FIX_TIMEOUT` | Scenario completion timeout in seconds (default `120`) |

```bash
docker run --rm -e FIX_HOST=host.docker.internal -e FIX_PORT=9876 -e FIX_TIMEOUT=60 \
  --add-host=host.docker.internal:host-gateway \
  -v "$PWD/config:/app/config:ro" -v "$PWD/scenarios:/app/scenarios:ro" \
  fix-testharness:latest /app/config/client.cfg /app/scenarios/order_flow.yml
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

#### `market_data_request` — MarketDataRequest (V), top of book

Sends a request for the best bid and offer (BBO) of one or more symbols. The simulator answers with a snapshot (`W`) per symbol, and with a subscription it sends incremental updates (`X`). A refused request gets a reject (`Y`).

```yaml
- type: market_data_request
  mdReqId: "BBO-1"              # request id; the replies carry it back
  subscriptionType: snapshot    # snapshot | subscribe | unsubscribe
  marketDepth: 1                # 1 = top of book (default); the simulator supports only 1
  entryTypes: ["bid", "offer"]  # optional; default both. "trade" is accepted for negative tests
  symbols: ["GOOGL", "SPY"]
```

#### `expect_bbo` — check the best bid and offer

Waits until the symbol's best bid and offer match. Fields you leave out are not checked. Use `"none"` for an empty side. Quantities are the total open quantity at that price. If the values do not match before `timeoutMs` (default 5 000), the run fails.

```yaml
- type: expect_bbo
  symbol: "GOOGL"
  bid: "9.90"     # or "none"
  bidQty: 150
  ask: "10.10"    # or "none"
  askQty: 70
  timeoutMs: 2000
```

#### `expect_md_reject` — check that the simulator refused a request

Waits until `count` (default 1) `MarketDataRequestReject` messages with this reason have arrived in total. Reasons: `0` unknown symbol, `4` unsupported subscription type, `5` unsupported market depth, `6` unsupported update type, `8` unsupported entry type.

```yaml
- type: expect_md_reject
  rejectReason: "0"
  timeoutMs: 2000
```

A check that fails, or a step that cannot run (for example a bad value in the YAML), makes the run fail. `play_scenario.sh` reports it and exits 1.

---

## Included Scenarios

| File | Description |
|------|-------------|
| `scenarios/order_flow.yml` | Limit orders, partial fills, replace, cancel, IOC, FOK-market |
| `scenarios/quote_flow.yml` | Quote (S) and MassQuote (i); crosses against quote liquidity |
| `scenarios/stop_orders.yml` | STOP and STOP LIMIT orders; trigger and cancel |
| `scenarios/bbo_snapshot.yml` | BBO snapshot (V type 0): sums quantity at the best level, then the empty book after cancels |
| `scenarios/bbo_subscribe_updates.yml` | Subscribe (V type 1): updates for a new offer, a new bid, a better offer, a cancel that moves the BBO back, and a side that empties |
| `scenarios/bbo_unsubscribe.yml` | After unsubscribe (V type 2), a better offer is not reported |
| `scenarios/bbo_multi_symbol.yml` | One request for two symbols, one snapshot each |
| `scenarios/bbo_rejects.yml` | Rejects (Y) for an unknown symbol (0), a depth other than 1 (5), and a trade entry type (8) |

The BBO scenarios use GOOGL, NVDA, TSLA, SPY and AMZN, which the other scenarios do not use. Each one cancels its own orders, so the books are empty when it finishes. Run them with:

```bash
./play_scenario.sh --host host.docker.internal --port 9876 bbo_snapshot bbo_subscribe_updates bbo_unsubscribe bbo_multi_symbol bbo_rejects
```

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
| `quickfixj-messages-fix42` | 2.3.1 | FIX 4.2 message types & dictionary |
| `snakeyaml` | 2.2 | YAML scenario parsing |
| `logback-classic` | 1.4.14 | Logging |

---

## Adding a New Scenario

1. Create `scenarios/my_scenario.yml` following the format above.
2. Run:
   ```bash
   ./play_scenario.sh --host host.docker.internal --port 9876 my_scenario
   ```

All logs are written to both the console and `logs/testharness.log`.
Raw FIX message traces are stored in `logs/` by QuickFIX/J (controlled by `FileLogPath` in `client.cfg`).
