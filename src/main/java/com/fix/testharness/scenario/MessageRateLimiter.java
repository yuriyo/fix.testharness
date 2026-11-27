package com.fix.testharness.scenario;

import java.util.concurrent.locks.LockSupport;

/**
 * Limits the average rate at which a scenario sends FIX messages.
 *
 * <p>Each send is scheduled one interval after the previous one. If sending falls behind
 * (for example, after a long {@code wait} step), the schedule catches up so the average
 * stays at the target. It does not send a burst after a long pause: once the schedule is
 * more than one second late, it restarts from now.
 *
 * <p>Only steps that send messages call {@link #acquire()}. Wait steps are not limited.
 */
public final class MessageRateLimiter {

    /** Longest lag the schedule may build up before it restarts from now. */
    private static final long MAX_LAG_NS = 1_000_000_000L;

    private final long intervalNs;
    private long nextNs;

    /** @param messagesPerSecond target average rate; must be positive */
    public MessageRateLimiter(double messagesPerSecond) {
        if (messagesPerSecond <= 0) {
            throw new IllegalArgumentException("rate must be positive: " + messagesPerSecond);
        }
        this.intervalNs = Math.round(1_000_000_000d / messagesPerSecond);
        this.nextNs = System.nanoTime();
    }

    /** Block until the next message may be sent. */
    public synchronized void acquire() throws InterruptedException {
        long now = System.nanoTime();
        if (now - nextNs > MAX_LAG_NS) {
            nextNs = now;   // long pause: do not burst to catch up
        }
        while (true) {
            long wait = nextNs - System.nanoTime();
            if (wait <= 0) break;
            if (Thread.interrupted()) throw new InterruptedException();
            LockSupport.parkNanos(wait);
        }
        nextNs += intervalNs;
    }

    /** Interval between two sends at the target rate, in nanoseconds. */
    public long intervalNanos() {
        return intervalNs;
    }
}
