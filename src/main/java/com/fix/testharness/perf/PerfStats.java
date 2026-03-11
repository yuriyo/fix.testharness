package com.fix.testharness.perf;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe latency statistics collector for proxy performance tests.
 *
 * <p>Tracks two latency dimensions per order:
 * <ul>
 *   <li><b>to-NEW</b>  — wall-clock time (ns) from {@code Session.sendToTarget()} call to
 *       the first ExecReport (proxy acknowledged the order as NEW)</li>
 *   <li><b>to-DONE</b> — wall-clock time (ns) from {@code Session.sendToTarget()} call to
 *       the terminal ExecReport (IOC CANCELLED, or FILL, or REJECTED)</li>
 * </ul>
 *
 * <p>The first {@code warmupCount} samples of each dimension are discarded so that
 * JIT warm-up does not skew the percentiles.
 */
public class PerfStats {

    /** Maximum raw latency samples stored for percentile calculation (≈ 8 MB per dimension). */
    private static final int MAX_SAMPLES = 1_000_000;

    // ── Counters ────────────────────────────────────────────────────────────
    public final LongAdder ordersSent = new LongAdder();
    public final LongAdder newAcks    = new LongAdder();
    public final LongAdder doneAcks   = new LongAdder();
    public final LongAdder rejects    = new LongAdder();

    // ── Raw latency samples (nanoseconds) ──────────────────────────────────
    private final long[]        newSamples    = new long[MAX_SAMPLES];
    private final long[]        doneSamples   = new long[MAX_SAMPLES];
    private final AtomicInteger newSampleIdx  = new AtomicInteger(0);
    private final AtomicInteger doneSampleIdx = new AtomicInteger(0);

    // ── Running sums (for mean, excluding warmup) ───────────────────────────
    public final LongAdder newSumNs  = new LongAdder();
    public final LongAdder doneSumNs = new LongAdder();

    // ── Running min/max (volatile; occasional races on min/max are acceptable) ─
    private volatile long newMinNs  = Long.MAX_VALUE;
    private volatile long newMaxNs  = 0;
    private volatile long doneMinNs = Long.MAX_VALUE;
    private volatile long doneMaxNs = 0;

    private final int warmupCount;

    public PerfStats(int warmupCount) {
        this.warmupCount = Math.max(0, warmupCount);
    }

    // ── Recording ───────────────────────────────────────────────────────────

    /** Record the round-trip latency to the first ExecReport (NEW ack). */
    public void recordNew(long latNs) {
        long ordinal = newAcks.sum(); // snapshot before increment (approximate warmup gate)
        newAcks.increment();
        if (ordinal < warmupCount) return;   // discard warmup

        newSumNs.add(latNs);
        if (latNs < newMinNs) newMinNs = latNs;
        if (latNs > newMaxNs) newMaxNs = latNs;
        int idx = newSampleIdx.getAndIncrement();
        if (idx < MAX_SAMPLES) newSamples[idx] = latNs;
    }

    /** Record the round-trip latency to the terminal ExecReport (DONE: cancel/fill). */
    public void recordDone(long latNs) {
        long ordinal = doneAcks.sum();
        doneAcks.increment();
        if (ordinal < warmupCount) return;

        doneSumNs.add(latNs);
        if (latNs < doneMinNs) doneMinNs = latNs;
        if (latNs > doneMaxNs) doneMaxNs = latNs;
        int idx = doneSampleIdx.getAndIncrement();
        if (idx < MAX_SAMPLES) doneSamples[idx] = latNs;
    }

    public void incrementRejects() { rejects.increment(); }

    // ── Report building (call after test is complete) ────────────────────────

    public LatencyReport buildNewReport() {
        int n = Math.min(newSampleIdx.get(), MAX_SAMPLES);
        long[] s = Arrays.copyOf(newSamples, n);
        Arrays.sort(s);
        long cnt = Math.max(0, newAcks.sum() - warmupCount);
        return new LatencyReport("to-NEW (proxy ack)", cnt, newSumNs.sum(), newMinNs, newMaxNs, s);
    }

    public LatencyReport buildDoneReport() {
        int n = Math.min(doneSampleIdx.get(), MAX_SAMPLES);
        long[] s = Arrays.copyOf(doneSamples, n);
        Arrays.sort(s);
        long cnt = Math.max(0, doneAcks.sum() - warmupCount);
        return new LatencyReport("to-DONE (IOC-cxl / fill)", cnt, doneSumNs.sum(), doneMinNs, doneMaxNs, s);
    }

    // ── LatencyReport ────────────────────────────────────────────────────────

    public static final class LatencyReport {

        public final String name;
        public final long   count;
        public final long   sumNs;
        public final long   minNs;
        public final long   maxNs;
        public final long   p50Ns;
        public final long   p95Ns;
        public final long   p99Ns;
        public final long   p999Ns;

        LatencyReport(String name, long count, long sumNs,
                      long rawMinNs, long rawMaxNs, long[] sorted) {
            this.name  = name;
            this.count = count;
            this.sumNs = sumNs;
            this.minNs = (rawMinNs == Long.MAX_VALUE) ? 0 : rawMinNs;
            this.maxNs = rawMaxNs;
            this.p50Ns  = percentile(sorted, 0.500);
            this.p95Ns  = percentile(sorted, 0.950);
            this.p99Ns  = percentile(sorted, 0.990);
            this.p999Ns = percentile(sorted, 0.999);
        }

        private static long percentile(long[] sorted, double pct) {
            if (sorted.length == 0) return 0;
            int idx = (int) Math.min(sorted.length - 1, Math.floor(sorted.length * pct));
            return sorted[idx];
        }

        public double avgUs()  { return count > 0 ? sumNs  / 1000.0 / count : 0.0; }
        public double minUs()  { return minNs  / 1000.0; }
        public double maxUs()  { return maxNs  / 1000.0; }
        public double p50Us()  { return p50Ns  / 1000.0; }
        public double p95Us()  { return p95Ns  / 1000.0; }
        public double p99Us()  { return p99Ns  / 1000.0; }
        public double p999Us() { return p999Ns / 1000.0; }
    }
}
