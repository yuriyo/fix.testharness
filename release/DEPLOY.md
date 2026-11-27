# FIX Test Harness: deploy and run

The test harness is a FIX client. It logs on to a FIX 4.2 server (for example the FIX Exchange Simulator), sends the orders in a YAML scenario, and checks the replies. You need Docker and nothing else.

## What is in this release

| File | What it is |
|------|-----------|
| Docker Hub `tradeeze/fixtestharness:<version>` | The Docker image. Pull it with `docker pull`. |
| `fixtestharness-<version>.jar` | The client as one runnable jar (the image already contains it) |
| `fixtestharness-<version>-scenarios.tar.gz` | `config/` (client settings) and `scenarios/` (the YAML scenarios) |
| `fixtestharness-<version>-tools.tar.gz` | `play_scenario.sh` (run scenarios) and `sync_seqnums.sh` (reset sequence numbers) |
| `DEPLOY.md` | This guide |
| `SHA256SUMS` | Checksums of every file above |

Check the downloads before you load them:

```bash
sha256sum -c SHA256SUMS
```

## 1. Get the image

From Docker Hub (needs Internet access):

```bash
docker pull tradeeze/fixtestharness:<version>
```

The image is `tradeeze/fixtestharness:<version>`. Use that name below. The image is built for `linux/amd64`. On an Apple Silicon Mac, add `--platform linux/amd64` to `docker pull` and `docker run`.

## 2. Set up a working folder

```bash
mkdir -p harness && cd harness
tar -xzf ../fixtestharness-<version>-scenarios.tar.gz   # creates ./config and ./scenarios
tar -xzf ../fixtestharness-<version>-tools.tar.gz       # creates play_scenario.sh and sync_seqnums.sh
chmod +x play_scenario.sh sync_seqnums.sh
```

Edit `config/client.cfg` for your session. The key settings:

| Setting | Meaning |
|---------|---------|
| `SenderCompID` | your client's name. Each name gets its own session on the server. |
| `TargetCompID` | the server's name, for example `EXCHANGE` for the simulator |
| `SocketConnectHost` / `SocketConnectPort` | the server (for example port `9876`) |
| `BeginString` | `FIX.4.2` |
| `ResetOnLogon` | `Y` resets the sequence numbers at each logon |

You can change the host and port per run with `--host` and `--port` (below), so the file can stay as it is.

## 3. Run a scenario

With no arguments, `play_scenario.sh` runs every scenario in `scenarios/` except the performance ones (`perf_*`), in alphabetical order.

```bash
./play_scenario.sh --list                                        # show the scenarios
./play_scenario.sh --host host.docker.internal --port 9876 order_flow
./play_scenario.sh --host host.docker.internal --port 9876       # all non-perf scenarios
```

Use `host.docker.internal` to reach a server on the same machine. For a server on another machine, give its name or address.

| Option | Meaning |
|--------|---------|
| `--config <file>` | another client settings file (it must be inside the folder, so the container can read it) |
| `--host`, `--port` | server address for this run |
| `--rate <n>` | average message rate for the scenario, in messages per second. Wait steps are not limited. Default: no limit. |
| `--tag <tag>` | image tag (default `tradeeze/fixtestharness:latest`; use `tradeeze/fixtestharness:<version>`) |

Set the image tag once for the folder:

```bash
export IMAGE_TAG=tradeeze/fixtestharness:<version>
```

`play_scenario.sh` exits 0 when every scenario passes, and 1 if any scenario fails. A check that fails (for example, an `expect_bbo` that does not match), a step that cannot run, or a run that does not finish in time all count as failures.

## 4. Run without the script

The same run with `docker run` directly, which is useful in CI or a scheduler:

```bash
docker run --rm \
  -e SCENARIO_RATE=50 \
  -e FIX_TIMEOUT=600 \
  --add-host=host.docker.internal:host-gateway \
  -v "$(pwd)/config:/app/config:ro" \
  -v "$(pwd)/scenarios:/app/scenarios:ro" \
  -v "$(pwd)/store:/app/store" \
  -v "$(pwd)/logs:/app/logs" \
  tradeeze/fixtestharness:<version> \
  /app/config/client.cfg /app/scenarios/order_flow.yml
```

Environment variables:

| Variable | Default | Meaning |
|----------|---------|---------|
| `FIX_HOST`, `FIX_PORT` | from `client.cfg` | server address |
| `FIX_TIMEOUT` | `120` | seconds allowed for a scenario to finish |
| `SCENARIO_RATE` | no limit | average message rate, messages per second |

`FIX_TIMEOUT` applies to the whole scenario. For a long scenario at a low rate, set it higher. For example, 5,000 messages at 50 per second takes about 100 seconds. `play_scenario.sh` does not pass `FIX_TIMEOUT`, so use `docker run` for those runs.

## 5. Read the results

Each run prints one line per message: execution reports, cancel rejects, market data snapshots and updates, and rejects. Checks print `✔` when they pass and `FAILED` when they do not. The end of a run prints `Scenario complete. N/N steps executed successfully.`

Logs are kept in `logs/`. Each session has `FIX.4.2-<sender>-<target>.messages.log` (every FIX message) and `.event.log` (session events).

## 6. Reset sequence numbers

If the client and the server disagree about sequence numbers (`MsgSeqNum too low`, or messages that never get replies), reset the client's stored sequence numbers:

```bash
./sync_seqnums.sh          # reset both to 1
./sync_seqnums.sh 42 42    # or set the sender and target numbers yourself
```

Stop any running scenario first. Reset the server's session too, following its own guide.

## 7. Keep the folder tidy

| Path | What | Notes |
|------|------|-------|
| `store/` | FIX sequence numbers | keep it if you want sessions to resume. Delete it to start clean. |
| `logs/` | FIX message and event logs | grows with every run. Delete the files you no longer need. |
| `config/`, `scenarios/` | your settings and scenarios | keep them with your own changes |

Back up the folder before you upgrade:

```bash
tar -czf harness-backup.tgz config scenarios store logs
```

## 8. Upgrade to a new release

```bash
docker pull tradeeze/fixtestharness:<new-version>
export IMAGE_TAG=tradeeze/fixtestharness:<new-version>
```

Then run as before. The release's `config/` and `scenarios/` may differ from yours, so compare them before you replace yours.

## 9. Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| `image ... not found` | the image is not pulled or loaded, or the tag is wrong | `docker pull` it (or `docker load`) and check `IMAGE_TAG` |
| `Logon` never happens, scenario times out | wrong host, port, or `TargetCompID` | check `--host`, `--port`, and `TargetCompID` in `config/client.cfg` |
| `MsgSeqNum too low` | sequence numbers out of step | `./sync_seqnums.sh`, or reset the server's session |
| `Unknown entry type` or `Step N/M ... failed` | a bad value in the scenario | fix the YAML value, the message names the step |
| `expect_bbo ... did not match` | the book is not what the scenario expects | the book may hold orders from another run; restart the server's books or cancel them |
| `Permission denied` on `store` or `logs` | the folder is owned by another user | `chown` the folder to your user |
| Exit code 1 | a check failed or the run did not finish | read the lines marked `FAILED` |

## Limits of this release

- FIX 4.2 only.
- The harness does not check the server's replies beyond the scenario's own `expect_*` steps.
- Performance scenarios (`perf_*`) are run only when named explicitly; they are not part of the default run.
