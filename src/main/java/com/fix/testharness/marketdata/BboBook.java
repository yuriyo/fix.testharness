package com.fix.testharness.marketdata;

import quickfix.FieldNotFound;
import quickfix.Group;
import quickfix.Message;
import quickfix.field.MDEntryPx;
import quickfix.field.MDEntrySize;
import quickfix.field.MDEntryType;
import quickfix.field.MDReqRejReason;
import quickfix.field.MDUpdateAction;
import quickfix.field.MsgType;
import quickfix.field.Symbol;
import quickfix.fix42.MarketDataIncrementalRefresh;
import quickfix.fix42.MarketDataSnapshotFullRefresh;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

/**
 * Best bid and offer per symbol, as the simulator reported it: built from MarketDataSnapshotFullRefresh (W)
 * and MarketDataIncrementalRefresh (X) messages. Also counts MarketDataRequestReject (Y) messages by reason.
 *
 * <p>Scenarios use {@link #awaitTop} and {@link #awaitRejects} to check what the simulator sent.
 * Both wait with a timeout, so a check passes as soon as the expected value arrives.
 */
public class BboBook {

    /** One side of the book: price and total quantity. A null Level means the side is empty. */
    public record Level(BigDecimal price, int qty) {}

    /** Best bid and best offer for one symbol. */
    public record Top(Level bid, Level ask) {
        public static final Top EMPTY = new Top(null, null);
    }

    /**
     * Expected top of book from a scenario. A null field is not checked.
     * {@code "none"} as a price means the side must be empty.
     */
    public record Expected(String bid, Integer bidQty, String ask, Integer askQty) {

        /** Returns null when {@code top} matches, otherwise a short description of the mismatch. */
        public String mismatch(Top top) {
            String m = side("bid", bid, bidQty, top.bid());
            if (m != null) return m;
            return side("ask", ask, askQty, top.ask());
        }

        private static String side(String name, String price, Integer qty, Level level) {
            if (price == null && qty == null) return null;
            if (price != null && price.equals("none")) {
                return level == null ? null : name + " expected empty but was " + describe(level);
            }
            if (level == null) return name + " expected " + price + " but side is empty";
            if (price != null && new BigDecimal(price).compareTo(level.price()) != 0) {
                return name + " expected price " + price + " but was " + level.price().toPlainString();
            }
            if (qty != null && qty != level.qty()) {
                return name + " expected qty " + qty + " but was " + level.qty();
            }
            return null;
        }

        private static String describe(Level l) {
            return l.price().toPlainString() + " x " + l.qty();
        }
    }

    private final Map<String, Top> tops = new HashMap<>();   // guarded by this
    private final Map<String, Integer> rejects = new HashMap<>();   // guarded by this

    // ── Reading messages ──────────────────────────────────────────────────

    /** Apply a W, X or Y message. Other message types are ignored. */
    public void apply(Message m) throws FieldNotFound {
        String type = m.getHeader().getString(MsgType.FIELD);
        switch (type) {
            case MsgType.MARKET_DATA_SNAPSHOT_FULL_REFRESH -> applySnapshot(m);
            case MsgType.MARKET_DATA_INCREMENTAL_REFRESH   -> applyIncremental(m);
            case MsgType.MARKET_DATA_REQUEST_REJECT        -> recordReject(m.getString(MDReqRejReason.FIELD));
            default -> { /* not market data */ }
        }
    }

    private void applySnapshot(Message w) throws FieldNotFound {
        String symbol = w.getString(Symbol.FIELD);
        Level bid = null, ask = null;
        int n = w.getGroupCount(268);
        for (int i = 1; i <= n; i++) {
            Group g = w.getGroup(i, new MarketDataSnapshotFullRefresh.NoMDEntries());
            char side = g.getChar(MDEntryType.FIELD);
            Level level = new Level(new BigDecimal(g.getString(MDEntryPx.FIELD)), (int) g.getDouble(MDEntrySize.FIELD));
            if (side == MDEntryType.BID)        bid = level;
            else if (side == MDEntryType.OFFER) ask = level;
        }
        synchronized (this) {
            tops.put(symbol, new Top(bid, ask));
            notifyAll();
        }
    }

    private void applyIncremental(Message x) throws FieldNotFound {
        int n = x.getGroupCount(268);
        for (int i = 1; i <= n; i++) {
            Group g = x.getGroup(i, new MarketDataIncrementalRefresh.NoMDEntries());
            String symbol = g.getString(Symbol.FIELD);
            char action = g.getChar(MDUpdateAction.FIELD);
            char side   = g.getChar(MDEntryType.FIELD);
            Level level = action == MDUpdateAction.DELETE ? null
                    : new Level(new BigDecimal(g.getString(MDEntryPx.FIELD)), (int) g.getDouble(MDEntrySize.FIELD));
            synchronized (this) {
                Top t = tops.getOrDefault(symbol, Top.EMPTY);
                tops.put(symbol, side == MDEntryType.BID ? new Top(level, t.ask()) : new Top(t.bid(), level));
                notifyAll();
            }
        }
    }

    private synchronized void recordReject(String reason) {
        rejects.merge(reason, 1, Integer::sum);
        notifyAll();
    }

    // ── Waiting for expected state ────────────────────────────────────────

    /** The last top of book received for a symbol, or {@link Top#EMPTY} if nothing has been received. */
    public synchronized Top top(String symbol) {
        return tops.getOrDefault(symbol, Top.EMPTY);
    }

    /**
     * Wait until the symbol's top of book matches {@code expected}.
     *
     * @return null when it matched, otherwise the mismatch from the last value seen
     */
    public synchronized String awaitTop(String symbol, Expected expected, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        String last = expected.mismatch(tops.getOrDefault(symbol, Top.EMPTY));
        while (last != null) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) break;
            wait(left);
            last = expected.mismatch(tops.getOrDefault(symbol, Top.EMPTY));
        }
        return last;
    }

    /**
     * Wait until at least {@code count} rejects with the given reason have been received in total.
     *
     * @return true if they arrived in time
     */
    public synchronized boolean awaitRejects(String reason, int count, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (rejects.getOrDefault(reason, 0) < count) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return false;
            wait(left);
        }
        return true;
    }

    /** Number of rejects with this reason received so far. */
    public synchronized int rejectCount(String reason) {
        return rejects.getOrDefault(reason, 0);
    }
}
