package com.fix.testharness.scenario;

import java.util.List;

/**
 * A single step in a scenario.
 *
 * <h3>Step types and their fields</h3>
 *
 * <b>new_order</b> — sends a {@code NewOrderSingle (D)}:
 * <pre>
 *   type: new_order
 *   clOrdId: "ORD001"
 *   symbol: "AAPL"
 *   side: BUY          # BUY | SELL
 *   ordType: LIMIT     # MARKET | LIMIT | STOP | STOP_LIMIT
 *   qty: 100
 *   price: 150.00      # required for LIMIT / STOP_LIMIT
 *   stopPx: 148.00     # required for STOP / STOP_LIMIT
 *   tif: DAY           # DAY | GTC | IOC | FOK  (default: DAY)
 *   delayMs: 500       # pause before sending (default: 0)
 * </pre>
 *
 * <b>cancel</b> — sends an {@code OrderCancelRequest (F)}:
 * <pre>
 *   type: cancel
 *   clOrdId: "CXL001"
 *   origClOrdId: "ORD001"
 *   symbol: "AAPL"
 *   side: BUY
 *   delayMs: 0
 * </pre>
 *
 * <b>replace</b> — sends an {@code OrderCancelReplaceRequest (G)}:
 * <pre>
 *   type: replace
 *   clOrdId: "RPL001"
 *   origClOrdId: "ORD001"
 *   symbol: "AAPL"
 *   side: BUY
 *   ordType: LIMIT
 *   qty: 200
 *   price: 149.00
 *   tif: DAY
 *   delayMs: 0
 * </pre>
 *
 * <b>quote</b> — sends a {@code Quote (S)}:
 * <pre>
 *   type: quote
 *   quoteId: "Q001"
 *   symbol: "AAPL"
 *   bidPx: 149.90
 *   bidSize: 500
 *   offerPx: 150.10
 *   offerSize: 500
 *   delayMs: 0
 * </pre>
 *
 * <b>mass_quote</b> — sends a {@code MassQuote (i)}:
 * <pre>
 *   type: mass_quote
 *   massQuoteId: "MQ001"
 *   entries:
 *     - quoteEntryId: "E001"
 *       symbol: "AAPL"
 *       bidPx: 149.90
 *       bidSize: 500
 *       offerPx: 150.10
 *       offerSize: 500
 *     - quoteEntryId: "E002"
 *       symbol: "MSFT"
 *       bidPx: 299.50
 *       bidSize: 200
 *       offerPx: 300.00
 *       offerSize: 200
 *   delayMs: 0
 * </pre>
 *
 * <b>wait</b> — sleep for {@code ms} milliseconds:
 * <pre>
 *   type: wait
 *   ms: 2000
 * </pre>
 *
 * <b>wait_for_reports</b> — wait until {@code count} execution reports have arrived
 * (or until {@code timeoutMs} elapses):
 * <pre>
 *   type: wait_for_reports
 *   count: 3
 *   timeoutMs: 5000
 * </pre>
 *
 * <b>perf_test</b> — high-volume throughput / latency test against the proxy:
 * <pre>
 *   type: perf_test
 *   mode: ioc            # ioc (default) | ladder
 *   symbol: AAPL
 *   side: BUY
 *   qty: 100
 *   price: 99.00         # for ioc: limit price (set far from market so order always cancels)
 *   rate: 1000           # target orders per second
 *   totalOrders: 5000    # total orders to send
 *   warmupOrders: 200    # discard first N samples from latency stats (JIT warm-up)
 *   reportIntervalSec: 2 # print progress every N seconds (default 5)
 *   ackTimeoutMs: 30000  # max wait for acks after sending (default 30000)
 *   # ladder-mode extras:
 *   ladderStartPx: 90.00
 *   ladderEndPx:  110.00
 *   ladderLevels: 20
 * </pre>
 */
public class ScenarioStep {

    private String type;

    // ── Timing ────────────────────────────────────────────────────────────
    /** Pause before sending this step (milliseconds). */
    private int delayMs = 0;

    // ── new_order / cancel / replace ──────────────────────────────────────
    private String clOrdId;
    private String origClOrdId;
    private String symbol;
    /** BUY | SELL */
    private String side;
    /** MARKET | LIMIT | STOP | STOP_LIMIT */
    private String ordType;
    private int    qty;
    private double price;
    private double stopPx;
    /** DAY | GTC | IOC | FOK */
    private String tif;

    // ── quote ─────────────────────────────────────────────────────────────
    private String quoteId;
    private double bidPx;
    private int    bidSize;
    private double offerPx;
    private int    offerSize;

    // ── mass_quote ────────────────────────────────────────────────────────
    private String            massQuoteId;
    private List<QuoteEntry>  entries;

    // ── wait ──────────────────────────────────────────────────────────────
    /** Milliseconds to sleep (used by type=wait). */
    private int ms;

    // ── wait_for_reports ──────────────────────────────────────────────────
    /** Number of execution reports to wait for. */
    private int count;
    /** Maximum wait time in milliseconds (default 10 000). */
    private int timeoutMs = 10_000;

    // ── perf_test ─────────────────────────────────────────────────────────
    /** Mode: ioc | ladder. */
    private String mode;
    /** Target send rate in orders per second. */
    private int    rate;
    /** Total number of orders to send. */
    private int    totalOrders;
    /** Orders sent during warmup phase (excluded from latency statistics). */
    private int    warmupOrders;
    /** Print a progress line every N seconds (default 5). */
    private int    reportIntervalSec = 5;
    /** Ladder mode: lowest price level. */
    private double ladderStartPx;
    /** Ladder mode: highest price level. */
    private double ladderEndPx;
    /** Ladder mode: number of distinct price levels (default 10). */
    private int    ladderLevels = 10;
    /** How long to wait for acks after all orders are sent, ms (default 30 000). */
    private int    ackTimeoutMs = 30_000;
    /**
     * Max outstanding (un-DONE) orders at any moment.  0 = unlimited (flood mode).
     * Set to e.g. 50 to cap in-flight orders and get accurate latency measurements
     * when the proxy cannot keep up with the send rate.
     */
    private int    windowSize   = 0;

    // ── Getters & Setters ─────────────────────────────────────────────────

    public String getType()                 { return type; }
    public void setType(String type)        { this.type = type; }

    public int getDelayMs()                 { return delayMs; }
    public void setDelayMs(int delayMs)     { this.delayMs = delayMs; }

    public String getClOrdId()              { return clOrdId; }
    public void setClOrdId(String v)        { this.clOrdId = v; }

    public String getOrigClOrdId()          { return origClOrdId; }
    public void setOrigClOrdId(String v)    { this.origClOrdId = v; }

    public String getSymbol()               { return symbol; }
    public void setSymbol(String v)         { this.symbol = v; }

    public String getSide()                 { return side; }
    public void setSide(String v)           { this.side = v; }

    public String getOrdType()              { return ordType; }
    public void setOrdType(String v)        { this.ordType = v; }

    public int getQty()                     { return qty; }
    public void setQty(int v)               { this.qty = v; }

    public double getPrice()                { return price; }
    public void setPrice(double v)          { this.price = v; }

    public double getStopPx()               { return stopPx; }
    public void setStopPx(double v)         { this.stopPx = v; }

    public String getTif()                  { return tif; }
    public void setTif(String v)            { this.tif = v; }

    public String getQuoteId()              { return quoteId; }
    public void setQuoteId(String v)        { this.quoteId = v; }

    public double getBidPx()                { return bidPx; }
    public void setBidPx(double v)          { this.bidPx = v; }

    public int getBidSize()                 { return bidSize; }
    public void setBidSize(int v)           { this.bidSize = v; }

    public double getOfferPx()              { return offerPx; }
    public void setOfferPx(double v)        { this.offerPx = v; }

    public int getOfferSize()               { return offerSize; }
    public void setOfferSize(int v)         { this.offerSize = v; }

    public String getMassQuoteId()          { return massQuoteId; }
    public void setMassQuoteId(String v)    { this.massQuoteId = v; }

    public List<QuoteEntry> getEntries()              { return entries; }
    public void setEntries(List<QuoteEntry> entries)  { this.entries = entries; }

    public int getMs()                      { return ms; }
    public void setMs(int ms)               { this.ms = ms; }

    public int getCount()                   { return count; }
    public void setCount(int count)         { this.count = count; }

    public int getTimeoutMs()               { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public String getMode()                   { return mode; }
    public void   setMode(String v)           { this.mode = v; }

    public int    getRate()                   { return rate; }
    public void   setRate(int v)              { this.rate = v; }

    public int    getTotalOrders()            { return totalOrders; }
    public void   setTotalOrders(int v)       { this.totalOrders = v; }

    public int    getWarmupOrders()           { return warmupOrders; }
    public void   setWarmupOrders(int v)      { this.warmupOrders = v; }

    public int    getReportIntervalSec()      { return reportIntervalSec; }
    public void   setReportIntervalSec(int v) { this.reportIntervalSec = v; }

    public double getLadderStartPx()          { return ladderStartPx; }
    public void   setLadderStartPx(double v)  { this.ladderStartPx = v; }

    public double getLadderEndPx()            { return ladderEndPx; }
    public void   setLadderEndPx(double v)    { this.ladderEndPx = v; }

    public int    getLadderLevels()           { return ladderLevels; }
    public void   setLadderLevels(int v)      { this.ladderLevels = v; }

    public int    getAckTimeoutMs()           { return ackTimeoutMs; }
    public void   setAckTimeoutMs(int v)      { this.ackTimeoutMs = v; }

    public int    getWindowSize()             { return windowSize; }
    public void   setWindowSize(int v)        { this.windowSize = v; }

    @Override
    public String toString() {
        return "Step{type='" + type + "', clOrdId='" + clOrdId + "', symbol='" + symbol + "'}";
    }
}
