package com.fix.testharness.scenario;

/**
 * A single entry in a {@code MassQuote (i)} scenario step.
 *
 * <pre>
 * entries:
 *   - quoteEntryId: "E001"
 *     symbol: "AAPL"
 *     bidPx: 149.90
 *     bidSize: 500
 *     offerPx: 150.10
 *     offerSize: 500
 * </pre>
 */
public class QuoteEntry {

    private String quoteEntryId;
    private String symbol;
    private double bidPx;
    private int    bidSize;
    private double offerPx;
    private int    offerSize;

    // ── Getters & Setters ─────────────────────────────────────────────────

    public String getQuoteEntryId()              { return quoteEntryId; }
    public void setQuoteEntryId(String v)        { this.quoteEntryId = v; }

    public String getSymbol()                    { return symbol; }
    public void setSymbol(String v)              { this.symbol = v; }

    public double getBidPx()                     { return bidPx; }
    public void setBidPx(double v)               { this.bidPx = v; }

    public int getBidSize()                      { return bidSize; }
    public void setBidSize(int v)                { this.bidSize = v; }

    public double getOfferPx()                   { return offerPx; }
    public void setOfferPx(double v)             { this.offerPx = v; }

    public int getOfferSize()                    { return offerSize; }
    public void setOfferSize(int v)              { this.offerSize = v; }

    @Override
    public String toString() {
        return "QuoteEntry{id='" + quoteEntryId + "', sym='" + symbol +
               "', bid=" + bidPx + "x" + bidSize +
               ", ask=" + offerPx + "x" + offerSize + "}";
    }
}
