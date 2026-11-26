package com.fix.testharness.report;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import quickfix.FieldMap;
import quickfix.FieldNotFound;
import quickfix.Group;
import quickfix.Message;
import quickfix.SessionID;
import quickfix.field.*;

/**
 * Pretty-prints incoming FIX messages from the server:
 * <ul>
 *   <li>{@code ExecutionReport (8)}</li>
 *   <li>{@code OrderCancelReject (9)}</li>
 *   <li>{@code QuoteAcknowledgement (b)}</li>
 * </ul>
 */
public class ExecutionReporter {

    private static final Logger log = LoggerFactory.getLogger(ExecutionReporter.class);

    // ExecType char constants (FIX 4.4)
    private static final char EXEC_NEW          = '0';
    private static final char EXEC_PARTIAL_FILL = '1';
    private static final char EXEC_FILL         = 'F';
    private static final char EXEC_CANCELLED    = '4';
    private static final char EXEC_REPLACED     = '5';
    private static final char EXEC_REJECTED     = '8';

    private ExecutionReporter() {}

    /**
     * Inspect the message and log it in a human-readable form.
     *
     * @param message  the incoming FIX message
     * @param sessionId the session it arrived on
     */
    public static void report(Message message, SessionID sessionId) {
        try {
            String msgType = message.getHeader().getString(MsgType.FIELD);

            switch (msgType) {
                case "8" -> reportExecutionReport(message, sessionId);
                case "9" -> reportCancelReject(message, sessionId);
                case "b" -> reportQuoteAck(message, sessionId);
                case "W" -> reportMarketDataSnapshot(message);
                case "X" -> reportMarketDataIncremental(message);
                case "Y" -> reportMarketDataReject(message);
                default  -> log.debug("<< [{}] MsgType={} (raw)", sessionId.getSenderCompID(), msgType);
            }
        } catch (FieldNotFound e) {
            log.error("Failed to parse incoming message: {}", e.getMessage());
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  ExecutionReport (8)
    // ──────────────────────────────────────────────────────────────────────

    private static void reportExecutionReport(Message msg, SessionID sid) throws FieldNotFound {
        char execType   = msg.getChar(ExecType.FIELD);
        char ordStatus  = msg.getChar(OrdStatus.FIELD);
        String clOrdId  = safeStr(msg, ClOrdID.FIELD);
        String orderId  = safeStr(msg, OrderID.FIELD);
        String symbol   = safeStr(msg, Symbol.FIELD);
        char   side     = msg.isSetField(Side.FIELD) ? msg.getChar(Side.FIELD) : '?';
        double cumQty   = safeDouble(msg, CumQty.FIELD);
        double leavQty  = safeDouble(msg, LeavesQty.FIELD);
        double lastQty  = safeDouble(msg, LastQty.FIELD);
        double lastPx   = safeDouble(msg, LastPx.FIELD);
        double avgPx    = safeDouble(msg, AvgPx.FIELD);
        String text     = safeStr(msg, Text.FIELD);

        String label    = execTypeLabel(execType);
        String sideStr  = side == Side.BUY ? "BUY" : side == Side.SELL ? "SELL" : "?";

        if (execType == EXEC_FILL || execType == EXEC_PARTIAL_FILL) {
            log.info("<< ExecReport [{}] clOrdId={} ordId={} sym={} {} {} | fill: {}@{} cumQty={} leavQty={}",
                     label, clOrdId, orderId, symbol, sideStr, ordStatusLabel(ordStatus),
                     lastQty, lastPx, cumQty, leavQty);
        } else if (execType == EXEC_REJECTED) {
            log.warn("<< ExecReport [{}] clOrdId={} sym={} {} REJECTED — {}",
                     label, clOrdId, symbol, sideStr, text);
        } else if (execType == EXEC_CANCELLED) {
            log.info("<< ExecReport [{}] clOrdId={} sym={} {} CANCELLED  cumQty={} leavQty={}",
                     label, clOrdId, symbol, sideStr, cumQty, leavQty);
        } else if (execType == EXEC_NEW) {
            log.info("<< ExecReport [{}] clOrdId={} ordId={} sym={} {} accepted — leavQty={}",
                     label, clOrdId, orderId, symbol, sideStr, leavQty);
        } else {
            log.info("<< ExecReport [{}] clOrdId={} ordId={} sym={} {} status={} cumQty={} leavQty={} avgPx={}",
                     label, clOrdId, orderId, symbol, sideStr, ordStatusLabel(ordStatus),
                     cumQty, leavQty, avgPx);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  OrderCancelReject (9)
    // ──────────────────────────────────────────────────────────────────────

    // ──────────────────────────────────────────────────────────────────────
    //  Market data: snapshot (W), incremental (X), reject (Y)
    // ──────────────────────────────────────────────────────────────────────

    private static void reportMarketDataSnapshot(Message msg) throws FieldNotFound {
        String reqId  = safeStr(msg, MDReqID.FIELD);
        String symbol = safeStr(msg, Symbol.FIELD);
        StringBuilder sb = new StringBuilder();
        int n = msg.getGroupCount(268);
        for (int i = 1; i <= n; i++) {
            Group g = msg.getGroup(i, new quickfix.fix42.MarketDataSnapshotFullRefresh.NoMDEntries());
            sb.append("  ").append(sideLabel(g.getChar(MDEntryType.FIELD)))
              .append(' ').append(safeStr(g, MDEntryPx.FIELD))
              .append(" x ").append((long) safeDouble(g, MDEntrySize.FIELD));
        }
        log.info("<< MarketDataSnapshot  reqId={} symbol={}{}", reqId, symbol, n == 0 ? "  (empty book)" : sb.toString());
    }

    private static void reportMarketDataIncremental(Message msg) throws FieldNotFound {
        String reqId = safeStr(msg, MDReqID.FIELD);
        int n = msg.getGroupCount(268);
        for (int i = 1; i <= n; i++) {
            Group g = msg.getGroup(i, new quickfix.fix42.MarketDataIncrementalRefresh.NoMDEntries());
            char action = g.getChar(MDUpdateAction.FIELD);
            String side = sideLabel(g.getChar(MDEntryType.FIELD));
            String symbol = safeStr(g, Symbol.FIELD);
            if (action == MDUpdateAction.DELETE) {
                log.info("<< MarketDataUpdate    reqId={} symbol={} {} removed", reqId, symbol, side);
            } else {
                log.info("<< MarketDataUpdate    reqId={} symbol={} {} {} x {}", reqId, symbol, side,
                         safeStr(g, MDEntryPx.FIELD), (long) safeDouble(g, MDEntrySize.FIELD));
            }
        }
    }

    private static void reportMarketDataReject(Message msg) throws FieldNotFound {
        String reqId = safeStr(msg, MDReqID.FIELD);
        String reason = safeStr(msg, MDReqRejReason.FIELD);
        String text = safeStr(msg, Text.FIELD);
        log.warn("<< MarketDataRequestReject  reqId={} reason={} {}", reqId, reason, text);
    }

    private static String sideLabel(char t) {
        return switch (t) {
            case '0' -> "BID  ";
            case '1' -> "OFFER";
            case '2' -> "TRADE";
            default  -> "?" + t;
        };
    }

    private static void reportCancelReject(Message msg, SessionID sid) throws FieldNotFound {
        String clOrdId     = safeStr(msg, ClOrdID.FIELD);
        String origClOrdId = safeStr(msg, OrigClOrdID.FIELD);
        String text        = safeStr(msg, Text.FIELD);
        int reason         = msg.isSetField(CxlRejReason.FIELD) ? msg.getInt(CxlRejReason.FIELD) : -1;

        log.warn("<< OrderCancelReject  clOrdId={} origClOrdId={} reason={} text={}",
                 clOrdId, origClOrdId, cxlRejReasonLabel(reason), text);
    }

    // ──────────────────────────────────────────────────────────────────────
    //  QuoteAcknowledgement (b)
    // ──────────────────────────────────────────────────────────────────────

    private static void reportQuoteAck(Message msg, SessionID sid) throws FieldNotFound {
        // Quote (S) ack → QuoteID (117); MassQuote (i) ack → MassQuoteID (1166)
        String quoteId     = msg.isSetField(QuoteID.FIELD)     ? msg.getString(QuoteID.FIELD)     : null;
        String massQuoteId = msg.isSetField(1166)               ? msg.getString(1166)               : null;
        int ackStatus      = msg.isSetField(QuoteAckStatus.FIELD) ? msg.getInt(QuoteAckStatus.FIELD) : -1;
        String text        = safeStr(msg, Text.FIELD);

        String id     = quoteId != null ? "quoteId=" + quoteId : "massQuoteId=" + massQuoteId;
        String status = ackStatus == 0 ? "ACCEPTED" : "REJECTED";

        if (ackStatus != 0) {
            log.warn("<< QuoteAck  {} status={} text={}", id, status, text);
        } else {
            log.info("<< QuoteAck  {} status={}", id, status);
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    //  Helpers
    // ──────────────────────────────────────────────────────────────────────

    private static String safeStr(FieldMap msg, int tag) {
        try { return msg.getString(tag); } catch (FieldNotFound e) { return ""; }
    }

    private static double safeDouble(FieldMap msg, int tag) {
        try { return msg.getDouble(tag); } catch (FieldNotFound e) { return 0.0; }
    }

    private static String execTypeLabel(char c) {
        return switch (c) {
            case '0' -> "NEW";
            case '1' -> "PARTIAL_FILL";
            case 'F' -> "FILL";
            case '4' -> "CANCELLED";
            case '5' -> "REPLACED";
            case '8' -> "REJECTED";
            default  -> "?" + c;
        };
    }

    private static String ordStatusLabel(char c) {
        return switch (c) {
            case '0' -> "NEW";
            case '1' -> "PARTIALLY_FILLED";
            case '2' -> "FILLED";
            case '4' -> "CANCELED";
            case '8' -> "REJECTED";
            default  -> "?" + c;
        };
    }

    private static String cxlRejReasonLabel(int r) {
        return switch (r) {
            case 0  -> "TOO_LATE_TO_CANCEL";
            case 1  -> "UNKNOWN_ORDER";
            default -> "UNKNOWN(" + r + ")";
        };
    }
}
