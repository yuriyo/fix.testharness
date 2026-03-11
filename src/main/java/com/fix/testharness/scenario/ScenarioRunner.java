package com.fix.testharness.scenario;

import com.fix.testharness.FIXTestHarnessApp;
import com.fix.testharness.perf.PerfTestRunner;
import com.fix.testharness.report.ExecutionReporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.Session;
import quickfix.SessionID;
import quickfix.SessionNotFound;
import quickfix.field.*;
import quickfix.fix42.*;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Executes a scenario step-by-step after the FIX session is established.
 *
 * <p>The runner is started in its own thread from
 * {@link com.fix.testharness.FIXTestHarnessApp#onLogon}. When all steps are
 * done (or an unrecoverable error occurs), it counts down the
 * {@code completionLatch} so that {@code Main} can shut down cleanly.
 */
public class ScenarioRunner implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(ScenarioRunner.class);

    private final Scenario         scenario;
    private final SessionID         sessionId;
    private final CountDownLatch    completionLatch;
    private final FIXTestHarnessApp app;

    /**
     * Shared counter incremented by {@link com.fix.testharness.FIXTestHarnessApp}
     * each time an ExecutionReport is received.  Used by {@code wait_for_reports}.
     */
    private final AtomicInteger reportCounter;

    public ScenarioRunner(Scenario scenario,
                          SessionID sessionId,
                          CountDownLatch completionLatch,
                          AtomicInteger reportCounter,
                          FIXTestHarnessApp app) {
        this.scenario        = scenario;
        this.sessionId       = sessionId;
        this.completionLatch = completionLatch;
        this.reportCounter   = reportCounter;
        this.app             = app;
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Runnable
    // ──────────────────────────────────────────────────────────────────────

    @Override
    public void run() {
        log.info("╔══════════════════════════════════════════════════╗");
        log.info("║  Scenario: {}",       scenario.getName());
        if (scenario.getDescription() != null)
            log.info("║  {}",              scenario.getDescription());
        log.info("║  Steps:    {}",       scenario.getSteps().size());
        log.info("║  Session:  {}",       sessionId);
        log.info("╚══════════════════════════════════════════════════╝");

        List<ScenarioStep> steps = scenario.getSteps();
        int total   = steps.size();
        int success = 0;

        for (int i = 0; i < total; i++) {
            ScenarioStep step = steps.get(i);
            log.info("── Step {}/{}: type={}", i + 1, total, step.getType());

            try {
                if (step.getDelayMs() > 0) {
                    log.debug("  (pause {}ms before step)", step.getDelayMs());
                    Thread.sleep(step.getDelayMs());
                }
                executeStep(step);
                success++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.error("Scenario interrupted at step {}", i + 1);
                break;
            } catch (Exception e) {
                log.error("Step {}/{} failed: {}", i + 1, total, e.getMessage(), e);
            }
        }

        log.info("══════════════════════════════════════════════════");
        log.info("Scenario complete.  {}/{} steps executed successfully.", success, total);
        log.info("══════════════════════════════════════════════════");

        completionLatch.countDown();
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Step dispatch
    // ──────────────────────────────────────────────────────────────────────

    private void executeStep(ScenarioStep step)
            throws SessionNotFound, InterruptedException {
        switch (step.getType().toLowerCase()) {
            case "new_order"         -> sendNewOrder(step);
            case "cancel"            -> sendCancel(step);
            case "replace"           -> sendReplace(step);
            case "quote"             -> sendQuote(step);
            case "mass_quote"        -> sendMassQuote(step);
            case "wait"              -> doWait(step);
            case "wait_for_reports"  -> waitForReports(step);
            case "perf_test"         -> new PerfTestRunner(app, sessionId, step).run();
            default -> log.warn("Unknown step type '{}' — skipping", step.getType());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  NewOrderSingle (D) — 35=D
    // ──────────────────────────────────────────────────────────────────────

    private void sendNewOrder(ScenarioStep s) throws SessionNotFound {
        NewOrderSingle order = new NewOrderSingle();

        order.set(new ClOrdID(require(s.getClOrdId(),   "clOrdId")));
        order.set(new Symbol(require(s.getSymbol(),     "symbol")));
        order.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION)); // required in FIX 4.2
        order.set(new Side(parseSide(s.getSide())));
        order.set(new OrdType(parseOrdType(s.getOrdType())));
        order.set(new OrderQty(s.getQty()));
        order.set(new TransactTime());

        // Price — required for LIMIT and STOP_LIMIT
        if (s.getPrice() > 0) order.set(new Price(s.getPrice()));

        // StopPx — required for STOP and STOP_LIMIT
        if (s.getStopPx() > 0) order.set(new StopPx(s.getStopPx()));

        // TimeInForce (default: DAY)
        if (s.getTif() != null) {
            order.set(new TimeInForce(parseTif(s.getTif())));
        }

        log.info("  >> NewOrderSingle  clOrdId={} sym={} side={} type={} qty={} price={} stopPx={} tif={}",
                 s.getClOrdId(), s.getSymbol(), s.getSide(), s.getOrdType(),
                 s.getQty(), s.getPrice(), s.getStopPx(), s.getTif());

        Session.sendToTarget(order, sessionId);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  OrderCancelRequest (F) — 35=F
    // ──────────────────────────────────────────────────────────────────────

    private void sendCancel(ScenarioStep s) throws SessionNotFound {
        OrderCancelRequest cancel = new OrderCancelRequest();

        cancel.set(new ClOrdID(require(s.getClOrdId(),     "clOrdId")));
        cancel.set(new OrigClOrdID(require(s.getOrigClOrdId(), "origClOrdId")));
        cancel.set(new Symbol(require(s.getSymbol(),       "symbol")));
        cancel.set(new Side(parseSide(s.getSide())));
        cancel.set(new TransactTime());

        log.info("  >> OrderCancelRequest  clOrdId={} origClOrdId={} sym={} side={}",
                 s.getClOrdId(), s.getOrigClOrdId(), s.getSymbol(), s.getSide());

        Session.sendToTarget(cancel, sessionId);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  OrderCancelReplaceRequest (G) — 35=G
    // ──────────────────────────────────────────────────────────────────────

    private void sendReplace(ScenarioStep s) throws SessionNotFound {
        OrderCancelReplaceRequest replace = new OrderCancelReplaceRequest();

        replace.set(new ClOrdID(require(s.getClOrdId(),      "clOrdId")));
        replace.set(new OrigClOrdID(require(s.getOrigClOrdId(), "origClOrdId")));
        replace.set(new Symbol(require(s.getSymbol(),        "symbol")));
        replace.set(new HandlInst(HandlInst.AUTOMATED_EXECUTION_ORDER_PRIVATE_NO_BROKER_INTERVENTION)); // required in FIX 4.2
        replace.set(new Side(parseSide(s.getSide())));
        replace.set(new OrdType(parseOrdType(s.getOrdType())));
        replace.set(new OrderQty(s.getQty()));
        replace.set(new TransactTime());

        if (s.getPrice() > 0)  replace.set(new Price(s.getPrice()));
        if (s.getStopPx() > 0) replace.set(new StopPx(s.getStopPx()));
        if (s.getTif() != null) replace.set(new TimeInForce(parseTif(s.getTif())));

        log.info("  >> OrderCancelReplaceRequest  clOrdId={} origClOrdId={} sym={} qty={} price={}",
                 s.getClOrdId(), s.getOrigClOrdId(), s.getSymbol(), s.getQty(), s.getPrice());

        Session.sendToTarget(replace, sessionId);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Quote (S) — 35=S
    // ──────────────────────────────────────────────────────────────────────

    private void sendQuote(ScenarioStep s) throws SessionNotFound {
        Quote quote = new Quote();

        quote.set(new QuoteID(require(s.getQuoteId(), "quoteId")));
        quote.set(new Symbol(require(s.getSymbol(),   "symbol")));

        if (s.getBidPx() > 0)   quote.set(new BidPx(s.getBidPx()));
        if (s.getBidSize() > 0)  quote.set(new BidSize(s.getBidSize()));
        if (s.getOfferPx() > 0)  quote.set(new OfferPx(s.getOfferPx()));
        if (s.getOfferSize() > 0) quote.set(new OfferSize(s.getOfferSize()));

        log.info("  >> Quote  id={} sym={} bid={}x{} ask={}x{}",
                 s.getQuoteId(), s.getSymbol(),
                 s.getBidPx(), s.getBidSize(), s.getOfferPx(), s.getOfferSize());

        Session.sendToTarget(quote, sessionId);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  MassQuote (i) — 35=i
    // ──────────────────────────────────────────────────────────────────────

    private void sendMassQuote(ScenarioStep s) throws SessionNotFound {
        List<QuoteEntry> entries = s.getEntries();
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("mass_quote step requires at least one entry");
        }

        MassQuote mq = new MassQuote();
        // MassQuote uses tag 117 (QuoteID) as the mass-quote identifier in QFJ FIX44
        mq.set(new QuoteID(require(s.getMassQuoteId(), "massQuoteId")));

        // All entries are grouped in a single QuoteSet
        MassQuote.NoQuoteSets quoteSet = new MassQuote.NoQuoteSets();
        quoteSet.set(new QuoteSetID(s.getMassQuoteId() + "-SET"));

        for (QuoteEntry e : entries) {
            MassQuote.NoQuoteSets.NoQuoteEntries entry =
                    new MassQuote.NoQuoteSets.NoQuoteEntries();

            entry.set(new QuoteEntryID(require(e.getQuoteEntryId(), "quoteEntryId")));
            entry.set(new Symbol(require(e.getSymbol(), "entry.symbol")));

            if (e.getBidPx() > 0)    entry.set(new BidPx(e.getBidPx()));
            if (e.getBidSize() > 0)  entry.set(new BidSize(e.getBidSize()));
            if (e.getOfferPx() > 0)  entry.set(new OfferPx(e.getOfferPx()));
            if (e.getOfferSize() > 0) entry.set(new OfferSize(e.getOfferSize()));

            quoteSet.addGroup(entry);

            log.info("  >>   entry: {} {} bid={}x{} ask={}x{}",
                     e.getQuoteEntryId(), e.getSymbol(),
                     e.getBidPx(), e.getBidSize(), e.getOfferPx(), e.getOfferSize());
        }

        mq.addGroup(quoteSet);

        log.info("  >> MassQuote  id={} entries={}",
                 s.getMassQuoteId(), entries.size());

        Session.sendToTarget(mq, sessionId);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Wait
    // ──────────────────────────────────────────────────────────────────────

    private void doWait(ScenarioStep s) throws InterruptedException {
        int ms = s.getMs();
        if (ms <= 0) ms = 1000;
        log.info("  -- Waiting {}ms ...", ms);
        Thread.sleep(ms);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Wait for execution reports
    // ──────────────────────────────────────────────────────────────────────

    private void waitForReports(ScenarioStep s) throws InterruptedException {
        int target    = s.getCount();
        int timeoutMs = s.getTimeoutMs() > 0 ? s.getTimeoutMs() : 10_000;
        long deadline = System.currentTimeMillis() + timeoutMs;

        log.info("  -- Waiting for {} execution report(s) (timeout={}ms) ...", target, timeoutMs);

        int baseline = reportCounter.get();
        while (reportCounter.get() - baseline < target) {
            if (System.currentTimeMillis() > deadline) {
                log.warn("  -- Timeout waiting for {} report(s); got {} so far",
                         target, reportCounter.get() - baseline);
                return;
            }
            Thread.sleep(50);
        }

        log.info("  -- Received {} report(s) as expected.", target);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  FIX field helpers
    // ──────────────────────────────────────────────────────────────────────

    private static char parseSide(String s) {
        if (s == null) throw new IllegalArgumentException("side is required");
        return switch (s.toUpperCase()) {
            case "BUY"  -> Side.BUY;
            case "SELL" -> Side.SELL;
            default -> throw new IllegalArgumentException("Unknown side: " + s + " (use BUY or SELL)");
        };
    }

    private static char parseOrdType(String s) {
        if (s == null) throw new IllegalArgumentException("ordType is required");
        return switch (s.toUpperCase()) {
            case "MARKET"     -> OrdType.MARKET;
            case "LIMIT"      -> OrdType.LIMIT;
            case "STOP"       -> OrdType.STOP_STOP_LOSS;
            case "STOP_LIMIT" -> OrdType.STOP_LIMIT;
            default -> throw new IllegalArgumentException("Unknown ordType: " + s +
                       " (use MARKET, LIMIT, STOP, STOP_LIMIT)");
        };
    }

    private static char parseTif(String s) {
        if (s == null) return TimeInForce.DAY;
        return switch (s.toUpperCase()) {
            case "DAY" -> TimeInForce.DAY;
            case "GTC" -> TimeInForce.GOOD_TILL_CANCEL;
            case "IOC" -> TimeInForce.IMMEDIATE_OR_CANCEL;
            case "FOK" -> TimeInForce.FILL_OR_KILL;
            default -> throw new IllegalArgumentException("Unknown tif: " + s +
                       " (use DAY, GTC, IOC, FOK)");
        };
    }

    private static String require(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Required field missing: " + fieldName);
        }
        return value;
    }
}
