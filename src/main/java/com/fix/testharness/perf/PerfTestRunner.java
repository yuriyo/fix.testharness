package com.fix.testharness.perf;

import com.fix.testharness.FIXTestHarnessApp;
import com.fix.testharness.scenario.ScenarioStep;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Message;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.*;
import quickfix.fix42.NewOrderSingle;

import java.time.Instant;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

/**
 * Executes a {@code perf_test} scenario step against the proxy.
 *
 * <h3>Modes</h3>
 * <ul>
 *   <li><b>ioc</b>    — floods the proxy with IOC LIMIT orders at a configurable rate.
 *       Each order triggers a NEW ack then a CANCELLED ack (the proxy immediately
 *       cancels IOC orders that do not match). Measures end-to-end proxy latency.</li>
 *   <li><b>ladder</b> — places GTC LIMIT orders at evenly-spaced price levels between
 *       {@code ladderStartPx} and {@code ladderEndPx}. Useful for testing proxy routing
 *       table under a loaded order book.</li>
 * </ul>
 *
 * <h3>Rate control</h3>
 * Uses {@link LockSupport#parkNanos} for sub-millisecond pacing between orders.
 * At 10 000 orders/s the inter-order interval is 100 µs; LockSupport gives ~2-5 µs
 * precision on modern Linux kernels.
 *
 * <h3>Latency measurement</h3>
 * {@link System#nanoTime()} is sampled immediately before each
 * {@link Session#sendToTarget} call (application-layer send time) and on every
 * incoming {@link quickfix.fix42.ExecutionReport} via a callback registered with
 * {@link FIXTestHarnessApp}.  Two dimensions are reported:
 * <ul>
 *   <li><b>to-NEW</b>  — time until the first ExecReport for a ClOrdID</li>
 *   <li><b>to-DONE</b> — time until the terminal ExecReport (CANCELLED / FILL)</li>
 * </ul>
 */
public class PerfTestRunner {

    private static final Logger log = LoggerFactory.getLogger(PerfTestRunner.class);

    // ── Internal correlation state ──────────────────────────────────────────
    /** clOrdId → send time (nanos). Populated just before sendToTarget(). */
    private final ConcurrentHashMap<String, Long> pendingNew = new ConcurrentHashMap<>(16384);
    /** clOrdId → NEW-ack arrival time (nanos). Stored when first ExecReport arrives. */
    private final ConcurrentHashMap<String, Long> newRecvNs  = new ConcurrentHashMap<>(16384);
    /** ClOrdIds for which a NEW ack has already been recorded. */
    private final Set<String> newSeen = ConcurrentHashMap.newKeySet(16384);
    /** Number of terminal ExecReports received (used to decide when to stop waiting). */
    private final AtomicInteger doneCount = new AtomicInteger(0);
    /**
     * Flow-control window: limits orders in-flight to {@code windowSize}.
     * null when windowSize==0 (unlimited / flood mode).
     */
    private Semaphore windowSem = null;

    /**
     * Unique prefix for all ClOrdIDs in this test run.
     * Prevents backlogged ExecReports from previous runs (same "PERF-N" ClOrdIDs)
     * from being matched against this run's pendingNew map.
     */
    private final String clOrdPrefix;

    private final FIXTestHarnessApp app;
    private final SessionID         sessionId;
    private final ScenarioStep      step;
    private final PerfStats         stats;

    public PerfTestRunner(FIXTestHarnessApp app, SessionID sessionId, ScenarioStep step) {
        this.app        = app;
        this.sessionId  = sessionId;
        this.step       = step;
        this.stats      = new PerfStats(step.getWarmupOrders());
        // Use last 6 digits of epoch-seconds so the prefix is short but unique per run
        this.clOrdPrefix = "P" + (Instant.now().getEpochSecond() % 1_000_000L) + "-";
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Entry point
    // ──────────────────────────────────────────────────────────────────────

    public void run() throws InterruptedException {
        String mode  = step.getMode() == null ? "ioc" : step.getMode().toLowerCase();
        int    rate  = step.getRate()        > 0 ? step.getRate()        : 1000;
        int    total = step.getTotalOrders() > 0 ? step.getTotalOrders() : 1000;

        printHeader(mode, rate, total);

        // Initialise flow-control window (0 = unlimited)
        int ws = step.getWindowSize();
        if (ws > 0) {
            windowSem = new Semaphore(ws);
        }

        // Suppress per-message console output and register our latency callback
        app.setQuietMode(true);
        app.setAppMsgCallback(this::onMessage);
        app.resetReportCounter();

        log.info("  ClOrdID prefix for this run: {}", clOrdPrefix);

        int reportIntervalSec = step.getReportIntervalSec() > 0 ? step.getReportIntervalSec() : 5;
        long testStartNs = System.nanoTime();

        ScheduledExecutorService progress = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "perf-progress");
            t.setDaemon(true);
            return t;
        });
        progress.scheduleAtFixedRate(
                () -> printProgress(testStartNs, rate, total),
                reportIntervalSec, reportIntervalSec, TimeUnit.SECONDS);

        try {
            switch (mode) {
                case "ladder" -> floodLadderOrders(rate, total);
                default       -> floodIocOrders(rate, total);    // "ioc" and unknown → ioc
            }

            // Wait for terminal acks
            if ("ladder".equals(mode)) {
                awaitAllNewAcks(total);
            } else {
                awaitAllDoneAcks(total);
            }
        } finally {
            progress.shutdownNow();
            app.setQuietMode(false);
            app.clearAppMsgCallback();
        }

        long durationNs = System.nanoTime() - testStartNs;
        printFinalStats(durationNs, rate, total, mode);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  IOC flood
    // ──────────────────────────────────────────────────────────────────────

    private void floodIocOrders(int rate, int total) throws InterruptedException {
        long   intervalNs = 1_000_000_000L / rate;
        String sym   = step.getSymbol() != null ? step.getSymbol() : "AAPL";
        double price = step.getPrice()  > 0     ? step.getPrice()  : 100.00;
        int    qty   = step.getQty()    > 0     ? step.getQty()    : 100;
        char   side  = "SELL".equalsIgnoreCase(step.getSide()) ? Side.SELL : Side.BUY;

        String windowDesc = windowSem != null ? "  window=" + step.getWindowSize() : "  window=UNLIMITED";
        log.info("► Sending {} IOC orders @ {} o/s  (interval {}µs){}  sym={} side={} qty={} px={}",
                 total, rate, intervalNs / 1000, windowDesc, sym, side == Side.BUY ? "BUY" : "SELL", qty, price);

        long sendStartNs = System.nanoTime();

        for (int i = 0; i < total; i++) {
            // Flow control: block if window is full (too many unacked orders)
            if (windowSem != null) {
                windowSem.acquire();
            } else {
                // Flood mode: rate pacing only
                long targetNs = sendStartNs + (long) i * intervalNs;
                long sleepNs  = targetNs - System.nanoTime();
                if (sleepNs > 500L) {
                    LockSupport.parkNanos(sleepNs);
                }
            }

            String clOrdId = clOrdPrefix + i;
            long   sendNs  = System.nanoTime();
            pendingNew.put(clOrdId, sendNs);
            stats.ordersSent.increment();

            try {
                NewOrderSingle order = new NewOrderSingle();
                order.set(new ClOrdID(clOrdId));
                order.set(new Symbol(sym));
                order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
                order.set(new Side(side));
                order.set(new OrdType(OrdType.LIMIT));
                order.set(new OrderQty(qty));
                order.set(new Price(price));
                order.set(new TimeInForce(TimeInForce.IMMEDIATE_OR_CANCEL));
                order.set(new TransactTime());
                Session.sendToTarget(order, sessionId);
            } catch (SessionNotFound e) {
                log.error("Session unavailable at order #{}: {}", i, e.getMessage());
                break;
            }
        }

        log.info("■ All {} IOC orders dispatched.", total);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Ladder flood
    // ──────────────────────────────────────────────────────────────────────

    private void floodLadderOrders(int rate, int total) {
        long   intervalNs = 1_000_000_000L / rate;
        String sym    = step.getSymbol()      != null ? step.getSymbol()      : "AAPL";
        int    qty    = step.getQty()          > 0    ? step.getQty()          : 100;
        char   side   = "SELL".equalsIgnoreCase(step.getSide()) ? Side.SELL : Side.BUY;
        int    levels = step.getLadderLevels() > 0    ? step.getLadderLevels() : 10;
        double startP = step.getLadderStartPx() > 0   ? step.getLadderStartPx() : 90.0;
        double endP   = step.getLadderEndPx()   > 0   ? step.getLadderEndPx()   : 110.0;
        double stepPx = levels > 1 ? (endP - startP) / (levels - 1) : 0.0;

        log.info("► Sending {} ladder orders @ {} o/s  sym={} side={} qty={}  price=[{} .. {}] {} levels",
                 total, rate, sym, side == Side.BUY ? "BUY" : "SELL", qty, startP, endP, levels);

        long sendStartNs = System.nanoTime();

        for (int i = 0; i < total; i++) {
            long targetNs = sendStartNs + (long) i * intervalNs;
            long sleepNs  = targetNs - System.nanoTime();
            if (sleepNs > 500L) {
                LockSupport.parkNanos(sleepNs);
            }

            String clOrdId = clOrdPrefix + i;
            double price   = startP + stepPx * (i % levels);
            // Round to 2 decimal places to avoid FIX price parsing noise
            price = Math.round(price * 100.0) / 100.0;

            long sendNs = System.nanoTime();
            pendingNew.put(clOrdId, sendNs);
            stats.ordersSent.increment();

            try {
                NewOrderSingle order = new NewOrderSingle();
                order.set(new ClOrdID(clOrdId));
                order.set(new Symbol(sym));
                order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION));
                order.set(new Side(side));
                order.set(new OrdType(OrdType.LIMIT));
                order.set(new OrderQty(qty));
                order.set(new Price(price));
                order.set(new TimeInForce(TimeInForce.GOOD_TILL_CANCEL));
                order.set(new TransactTime());
                Session.sendToTarget(order, sessionId);
            } catch (SessionNotFound e) {
                log.error("Session unavailable at order #{}: {}", i, e.getMessage());
                break;
            }
        }

        log.info("■ All {} ladder orders dispatched.", total);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Incoming message handler — called from the QFJ session thread
    // ──────────────────────────────────────────────────────────────────────

    private void onMessage(Message msg) {
        // Sample nanoTime as the very first thing to minimise measurement error
        long recvNs = System.nanoTime();

        try {
            String msgType = msg.getHeader().getString(MsgType.FIELD);
            if (!"8".equals(msgType)) return;   // only ExecutionReports

            String clOrdId = msg.getString(ClOrdID.FIELD);
            Long   sendNs  = pendingNew.get(clOrdId);
            if (sendNs == null) return;

            long latNs    = recvNs - sendNs;
            char execType = msg.getChar(ExecType.FIELD);

            // First report for this ClOrdId = NEW ack
            if (newSeen.add(clOrdId)) {
                stats.recordNew(latNs);
                newRecvNs.put(clOrdId, recvNs);
            }

            // Terminal ExecType values (FIX 4.2):
            //   '4' = CANCELED   '2' = FILL   '8' = REJECTED   '3' = DONE_FOR_DAY
            if (execType == ExecType.CANCELED  ||
                execType == ExecType.FILL       ||
                execType == ExecType.REJECTED   ||
                execType == ExecType.DONE_FOR_DAY) {
                stats.recordDone(latNs);
                Long newArrival = newRecvNs.remove(clOrdId);
                if (newArrival != null) {
                    stats.recordNewToDone(recvNs - newArrival);
                }
                pendingNew.remove(clOrdId);
                doneCount.incrementAndGet();
                // Release window slot so the sender can dispatch the next order
                if (windowSem != null) {
                    windowSem.release();
                }
            }

            if (execType == ExecType.REJECTED) {
                stats.incrementRejects();
            }

        } catch (Exception ignored) {
            // No logging in the hot path — don't stall the QFJ thread
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Completion awaits
    // ──────────────────────────────────────────────────────────────────────

    private void awaitAllDoneAcks(int total) throws InterruptedException {
        long timeoutNs = ackTimeoutNs();
        log.info("⏳ Waiting for {}/{} DONE acks (IOC cxl/fill) ...", total, total);
        long deadline = System.nanoTime() + timeoutNs;
        while (doneCount.get() < total && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        if (doneCount.get() < total) {
            log.warn("⚠  Ack timeout — received {}/{} DONE acks", doneCount.get(), total);
        }
    }

    private void awaitAllNewAcks(int total) throws InterruptedException {
        long timeoutNs = ackTimeoutNs();
        log.info("⏳ Waiting for {}/{} NEW acks (ladder) ...", total, total);
        long deadline = System.nanoTime() + timeoutNs;
        while (stats.newAcks.sum() < total && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        if (stats.newAcks.sum() < total) {
            log.warn("⚠  Ack timeout — received {}/{} NEW acks", stats.newAcks.sum(), total);
        }
    }

    private long ackTimeoutNs() {
        int ms = step.getAckTimeoutMs() > 0 ? step.getAckTimeoutMs() : 30_000;
        return (long) ms * 1_000_000L;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Reporting
    // ──────────────────────────────────────────────────────────────────────

    private void printHeader(String mode, int rate, int total) {
        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║          PROXY PERFORMANCE TEST                              ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("║  Mode:          {}", pad(mode.toUpperCase(), 47));
        log.info("║  Symbol:        {}", pad(step.getSymbol() != null ? step.getSymbol() : "AAPL", 47));
        log.info("║  Target rate:   {}", pad(rate + " orders/sec", 47));
        log.info("║  Total orders:  {}", pad(String.valueOf(total), 47));
        log.info("║  Warmup:        {}", pad(String.valueOf(step.getWarmupOrders()), 47));
        if ("ladder".equals(mode)) {
            log.info("║  Ladder range:  {}", pad(step.getLadderStartPx() + " .. " +
                    step.getLadderEndPx() + "  (" + step.getLadderLevels() + " levels)", 47));
        }
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    void printProgress(long testStartNs, int targetRate, int total) {
        double elapsed   = (System.nanoTime() - testStartNs) / 1e9;
        long   sent      = stats.ordersSent.sum();
        long   newAcks   = stats.newAcks.sum();
        long   doneAcks  = stats.doneAcks.sum();
        long   achieved  = elapsed > 0 ? (long) (sent / elapsed) : 0;
        long   rawMsgs   = app.getReportCounter();
        log.info("  [+{}s]  sent={}/{}  new-acks={}  done-acks={}  rate={} o/s  [raw-execs-rcvd={}]",
                 String.format("%.1f", elapsed), sent, total, newAcks, doneAcks, achieved, rawMsgs);
    }

    private void printFinalStats(long durationNs, int targetRate, int total, String mode) {
        double durationS = durationNs / 1e9;
        long   sent      = stats.ordersSent.sum();
        long   newAcks   = stats.newAcks.sum();
        long   doneAcks  = stats.doneAcks.sum();
        long   rejects   = stats.rejects.sum();
        double sendRate  = durationS > 0 ? sent    / durationS : 0;
        double ackRate   = durationS > 0 ? newAcks / durationS : 0;
        double pctNew    = sent > 0 ? 100.0 * newAcks  / sent : 0;
        double pctDone   = sent > 0 ? 100.0 * doneAcks / sent : 0;

        PerfStats.LatencyReport newRpt       = stats.buildNewReport();
        PerfStats.LatencyReport doneRpt      = stats.buildDoneReport();
        PerfStats.LatencyReport newToDoneRpt = stats.buildNewToDoneReport();

        log.info("╔══════════════════════════════════════════════════════════════╗");
        log.info("║                  PERFORMANCE TEST RESULTS                   ║");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("  Mode: {}   Duration: {}s",
                 mode.toUpperCase(), String.format("%.3f", durationS));
        log.info("  Target: {} o/s   Achieved send: {} o/s   Ack: {} o/s",
                 targetRate,
                 String.format("%.0f", sendRate),
                 String.format("%.0f", ackRate));
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("  THROUGHPUT:");
        log.info("  Orders sent  : {}", sent);
        log.info("  NEW acks     : {}  ({}%)   @ {} acks/s",
                 newAcks, String.format("%.2f", pctNew), String.format("%.0f", ackRate));
        log.info("  DONE acks    : {}  ({}%)   IOC-cxl / fill / reject",
                 doneAcks, String.format("%.2f", pctDone));
        log.info("  Rejects      : {}", rejects);
        log.info("╠══════════════════════════════════════════════════════════════╣");
        log.info("  HOP BREAKDOWN (IOC order flow):");
        log.info("    [1] send → NEW ack     (network RTT + proxy accept)");
        log.info("    [2] NEW ack → DONE ack (proxy cancel processing, no network)");
        log.info("    [3] send → DONE ack    (total end-to-end = hop1 + hop2)");
        log.info("╠══════════════════════════════════════════════════════════════╣");
        printLatRow(newRpt);
        log.info("╠══════════════════════════════════════════════════════════════╣");
        printLatRow(newToDoneRpt);
        log.info("╠══════════════════════════════════════════════════════════════╣");
        printLatRow(doneRpt);
        log.info("╚══════════════════════════════════════════════════════════════╝");
    }

    private void printLatRow(PerfStats.LatencyReport r) {
        if (r.count == 0) {
            log.info("  LATENCY [{}]: no samples", r.name);
            return;
        }
        log.info("  LATENCY [{}]  n={}",
                 r.name, r.count);
        log.info("  min={}µs   avg={}µs   max={}µs",
                 String.format("%.1f", r.minUs()),
                 String.format("%.1f", r.avgUs()),
                 String.format("%.1f", r.maxUs()));
        log.info("  p50={}µs   p95={}µs   p99={}µs   p99.9={}µs",
                 String.format("%.1f", r.p50Us()),
                 String.format("%.1f", r.p95Us()),
                 String.format("%.1f", r.p99Us()),
                 String.format("%.1f", r.p999Us()));
    }

    private static String pad(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length()) + "║";
    }
}
