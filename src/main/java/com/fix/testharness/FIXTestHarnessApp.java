package com.fix.testharness;

import com.fix.testharness.marketdata.BboBook;
import com.fix.testharness.report.ExecutionReporter;
import com.fix.testharness.scenario.Scenario;
import com.fix.testharness.scenario.ScenarioRunner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;
import quickfix.field.MsgType;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * QuickFIX/J {@link Application} implementation for the FIX Test Harness.
 *
 * <p>On session logon the scenario runner is launched in a background thread.
 * Incoming messages (ExecutionReports, QuoteAcks, CancelRejects) are
 * pretty-printed by {@link ExecutionReporter}.
 *
 * <p>When the scenario finishes (or times out), {@link #awaitCompletion}
 * returns and {@code Main} shuts the initiator down.
 */
public class FIXTestHarnessApp implements Application {

    private static final Logger log = LoggerFactory.getLogger(FIXTestHarnessApp.class);

    private final Scenario       scenario;
    private final CountDownLatch completionLatch = new CountDownLatch(1);
    private final AtomicInteger  reportCounter   = new AtomicInteger(0);

    /** Best bid and offer per symbol, built from the simulator's market data messages. */
    private final BboBook        bbo             = new BboBook();

    /** Failed checks (expect_* steps and incomplete runs). Main exits non-zero when this is above 0. */
    private final AtomicInteger  failures        = new AtomicInteger(0);

    /**
     * Callback invoked for every incoming app message when set (used by {@link com.fix.testharness.perf.PerfTestRunner}).
     * Cleared between perf test runs.
     */
    private final AtomicReference<Consumer<Message>> appMsgCallback = new AtomicReference<>();

    /**
     * When {@code true}, per-message console output via {@link ExecutionReporter} is suppressed.
     * Set during performance tests to avoid log I/O becoming the bottleneck.
     */
    private volatile boolean quietMode = false;

    /** Set when onLogon fires — used to guard against duplicate logon events. */
    private volatile boolean runnerStarted = false;

    public FIXTestHarnessApp(Scenario scenario) {
        this.scenario = scenario;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  QuickFIX/J Application lifecycle
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate(SessionID sessionId) {
        log.info("Session created: {}", sessionId);
    }

    @Override
    public void onLogon(SessionID sessionId) {
        log.info("Logon established: {}", sessionId);

        if (runnerStarted) {
            log.warn("Logon fired again for {} — runner already started, ignoring.", sessionId);
            return;
        }
        runnerStarted = true;

        ScenarioRunner runner = new ScenarioRunner(
                scenario, sessionId, completionLatch, reportCounter, this);

        Thread t = new Thread(runner, "scenario-runner");
        t.setDaemon(true);
        t.start();
    }

    @Override
    public void onLogout(SessionID sessionId) {
        log.info("Logout: {}", sessionId);
    }

    @Override
    public void toAdmin(Message message, SessionID sessionId) {
        // Heartbeats and admin messages are handled automatically by QuickFIX/J.
        if (log.isTraceEnabled()) {
            try {
                String mt = message.getHeader().getString(MsgType.FIELD);
                if ("0".equals(mt) || "A".equals(mt)) {
                    log.trace("OUT admin MsgType={}", mt);
                }
            } catch (FieldNotFound ignored) {}
        }
    }

    @Override
    public void fromAdmin(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, RejectLogon {
        // QuickFIX/J handles Heartbeat (0), TestRequest (1), Resend (2) etc.
        if (log.isTraceEnabled()) {
            try {
                log.trace("IN  admin MsgType={}", message.getHeader().getString(MsgType.FIELD));
            } catch (FieldNotFound ignored) {}
        }
    }

    @Override
    public void toApp(Message message, SessionID sessionId) throws DoNotSend {
        if (log.isTraceEnabled())
            log.trace("OUT app: {}", message);
    }

    @Override
    public void fromApp(Message message, SessionID sessionId)
            throws FieldNotFound, IncorrectDataFormat, IncorrectTagValue, UnsupportedMessageType {

        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);

            // Count execution reports for wait_for_reports steps
            if ("8".equals(msgType)) {
                reportCounter.incrementAndGet();
            }

            // Market data (W, X) and market data rejects (Y) feed the BBO book used by expect_* steps
            if ("W".equals(msgType) || "X".equals(msgType) || "Y".equals(msgType)) {
                bbo.apply(message);
            }

            // Forward to perf-test runner if active
            Consumer<Message> cb = appMsgCallback.get();
            if (cb != null) {
                cb.accept(message);
            }

            // Pretty-print to the console (suppressed during performance tests)
            if (!quietMode) {
                ExecutionReporter.report(message, sessionId);
            }

        } catch (Exception e) {
            log.error("Error processing incoming message: {}", e.getMessage(), e);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Perf-test hooks
    // ──────────────────────────────────────────────────────────────────────

    /** Register a callback to receive all incoming app messages (used by {@link com.fix.testharness.perf.PerfTestRunner}). */
    public void setAppMsgCallback(Consumer<Message> cb) { appMsgCallback.set(cb); }

    /** Remove the previously registered message callback. */
    public void clearAppMsgCallback() { appMsgCallback.set(null); }

    /** When {@code true}, {@link ExecutionReporter} output is silenced (perf test mode). */
    public void setQuietMode(boolean quiet) { this.quietMode = quiet; }

    /** The best bid and offer seen so far, per symbol. */
    public BboBook bbo() { return bbo; }

    /** Record a failed check. The message is logged, and Main exits non-zero at the end. */
    public void recordFailure(String message) {
        failures.incrementAndGet();
        log.error("FAILED: {}", message);
    }

    /** Number of failed checks so far. */
    public int failureCount() { return failures.get(); }

    /** Reset the raw ExecReport counter (call before each perf test). */
    public void resetReportCounter() { reportCounter.set(0); }

    /** Return the total number of ExecReports received since the last reset. */
    public long getReportCounter() { return reportCounter.get(); }

    // ──────────────────────────────────────────────────────────────────────
    //  Completion
    // ──────────────────────────────────────────────────────────────────────

    /**
     * Block until the scenario finishes or the timeout expires.
     *
     * @param timeoutSeconds maximum time to wait in seconds (0 = wait forever)
     * @return {@code true} if the scenario completed normally
     */
    public boolean awaitCompletion(long timeoutSeconds) throws InterruptedException {
        if (timeoutSeconds <= 0) {
            completionLatch.await();
            return true;
        }
        return completionLatch.await(timeoutSeconds, TimeUnit.SECONDS);
    }
}
