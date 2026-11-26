package com.fix.testharness;

import com.fix.testharness.scenario.Scenario;
import com.fix.testharness.scenario.ScenarioLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.*;

import java.io.FileInputStream;
import java.io.InputStream;

/**
 * Entry point for the FIX Test Harness.
 *
 * <h3>Usage</h3>
 * <pre>
 *   java -jar fix-testharness.jar [client.cfg] [scenario.yml]
 * </pre>
 *
 * <p>Defaults:
 * <ul>
 *   <li>{@code config/client.cfg}</li>
 *   <li>{@code scenarios/order_flow.yml}</li>
 * </ul>
 *
 * <p>The harness:
 * <ol>
 *   <li>Loads the scenario YAML.</li>
 *   <li>Starts a QuickFIX/J {@link SocketInitiator} that connects to the FIX server.</li>
 *   <li>After logon, runs the scenario steps in a background thread while heartbeats
 *       keep the session alive.</li>
 *   <li>Waits for the scenario to complete, then shuts down cleanly.</li>
 * </ol>
 *
 * <h3>Environment variables</h3>
 * <ul>
 *   <li>{@code FIX_HOST}    — override the server hostname (default from client.cfg)</li>
 *   <li>{@code FIX_PORT}    — override the server port   (default from client.cfg)</li>
 *   <li>{@code FIX_TIMEOUT} — scenario completion timeout in seconds (default 120)</li>
 * </ul>
 */
public class Main {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) throws Exception {

        String cfgPath      = args.length > 0 ? args[0] : "config/client.cfg";
        String scenarioPath = args.length > 1 ? args[1] : "scenarios/order_flow.yml";
        long   timeout      = envLong("FIX_TIMEOUT", 120);

        printBanner(cfgPath, scenarioPath);

        // ── Load scenario ────────────────────────────────────────────────
        Scenario scenario = ScenarioLoader.load(scenarioPath);

        // ── Load QuickFIX/J settings ─────────────────────────────────────
        SessionSettings settings;
        try (InputStream in = new FileInputStream(cfgPath)) {
            settings = new SessionSettings(in);
        }

        // Allow environment-variable overrides for host / port
        String fixHost = System.getenv("FIX_HOST");
        String fixPort = System.getenv("FIX_PORT");
        if (fixHost != null || fixPort != null) {
            var it = settings.sectionIterator();
            while (it.hasNext()) {
                var sid = it.next();
                if (fixHost != null) settings.setString(sid, "SocketConnectHost", fixHost);
                if (fixPort != null) settings.setString(sid, "SocketConnectPort", fixPort);
            }
            log.info("Overriding server address: {}:{}",
                     fixHost != null ? fixHost : "(from cfg)",
                     fixPort != null ? fixPort : "(from cfg)");
        }

        // ── Build QuickFIX/J components ──────────────────────────────────
        FIXTestHarnessApp app      = new FIXTestHarnessApp(scenario);
        MessageStoreFactory store  = new FileStoreFactory(settings);
        LogFactory logFactory      = new FileLogFactory(settings);
        MessageFactory msgFactory  = new DefaultMessageFactory();

        SocketInitiator initiator  = new SocketInitiator(
                app, store, settings, logFactory, msgFactory);

        // ── Start ────────────────────────────────────────────────────────
        log.info("Starting FIX initiator (timeout={}s)...", timeout);
        initiator.start();

        // ── Wait for scenario completion ─────────────────────────────────
        boolean completed = app.awaitCompletion(timeout);

        if (!completed) {
            log.warn("Scenario did not complete within {}s — stopping anyway.", timeout);
            app.recordFailure("scenario did not complete within " + timeout + "s");
        }

        // ── Stop ─────────────────────────────────────────────────────────
        log.info("Stopping FIX initiator...");
        initiator.stop();

        // A failed expect_* check or an incomplete run makes the whole run fail.
        int failures = app.failureCount();
        if (failures > 0) {
            log.error("Scenario FAILED: {} check(s) did not pass.", failures);
            System.exit(1);
        }
        log.info("Done.");
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────

    private static void printBanner(String cfg, String scenario) {
        log.info("╔════════════════════════════════════════╗");
        log.info("║        FIX Test Harness  v1.0          ║");
        log.info("╠════════════════════════════════════════╣");
        log.info("║  Config:   {}",    cfg);
        log.info("║  Scenario: {}",    scenario);
        log.info("╚════════════════════════════════════════╝");
    }

    private static long envLong(String name, long defaultValue) {
        String v = System.getenv(name);
        if (v == null) return defaultValue;
        try { return Long.parseLong(v.trim()); }
        catch (NumberFormatException e) { return defaultValue; }
    }
}
