package com.overdrive.app.usbrssi;

import java.util.ArrayDeque;

/**
 * Turns a stream of beacon RSSI samples into a NEAR / FAR decision.
 *
 * <p>Pure logic, no Android or clock dependencies: the caller passes the time in, so the rules
 * are unit-testable. Rules (tuned on the car):
 * <ul>
 *   <li>NEAR when at least {@code percent}% of the samples in the last {@code nearWindowMs} are
 *       {@code >= nearRssi}, with at least {@code nearMinSamples} samples in that window.</li>
 *   <li>FAR when at least {@code percent}% of the samples in the last {@code farWindowMs} are
 *       {@code <= farRssi}, with at least {@code farMinSamples} samples.</li>
 *   <li>Anything else is BETWEEN (or SPARSE when neither window has enough samples), and no
 *       sample for {@code noSignalMs} is NONE. None of these three change the decision: the
 *       last NEAR/FAR is kept. "No signal" therefore never means "far" — the car is also
 *       driven by people who do not carry the phone.</li>
 *   <li>The first decision after a reset is adopted silently ({@link #evaluate} returns null
 *       for it), so enabling the module never fires an automation by itself.</li>
 *   <li>Two decision changes are at least {@link #MIN_CHANGE_GAP_MS} apart. A change that comes
 *       sooner is deferred, not dropped: it is applied once the gap has passed if the windows
 *       still say so. (Dropping it left the decision out of step with the car on the bench.)</li>
 * </ul>
 */
public final class ProximityClassifier {

    public enum Classification { NEAR, FAR, BETWEEN, SPARSE, NONE }

    public enum Decision { NEAR, FAR }

    public static final long MIN_CHANGE_GAP_MS = 5000;

    /** Tunables; values are validated by {@link #clamp()}. */
    public static final class Settings {
        public int nearRssi = -70;
        public int farRssi = -85;
        public int nearWindowMs = 2000;
        public int farWindowMs = 2000;
        public int percent = 80;
        public int nearMinSamples = 2;
        public int farMinSamples = 2;
        public int noSignalMs = 3000;

        public Settings copy() {
            Settings s = new Settings();
            s.nearRssi = nearRssi;
            s.farRssi = farRssi;
            s.nearWindowMs = nearWindowMs;
            s.farWindowMs = farWindowMs;
            s.percent = percent;
            s.nearMinSamples = nearMinSamples;
            s.farMinSamples = farMinSamples;
            s.noSignalMs = noSignalMs;
            return s;
        }

        public Settings clamp() {
            nearRssi = clampInt(nearRssi, -110, -20);
            farRssi = clampInt(farRssi, -110, -20);
            nearWindowMs = clampInt(nearWindowMs, 300, 10000);
            farWindowMs = clampInt(farWindowMs, 300, 10000);
            percent = clampInt(percent, 50, 100);
            nearMinSamples = clampInt(nearMinSamples, 1, 50);
            farMinSamples = clampInt(farMinSamples, 1, 50);
            noSignalMs = clampInt(noSignalMs, 1000, 30000);
            return this;
        }
    }

    private final ArrayDeque<long[]> samples = new ArrayDeque<>();   // {timeMs, rssi}
    private long lastSampleMs = -1;
    private Classification classification = Classification.NONE;
    private Decision decision = null;
    private long lastChangeMs = Long.MIN_VALUE / 2;
    private int lastCount = 0;
    private int lastAverage = 0;

    public synchronized void add(long nowMs, int rssi) {
        samples.addLast(new long[]{nowMs, rssi});
        lastSampleMs = nowMs;
    }

    /** Forgets every sample and the decision (module switched off and on again). */
    public synchronized void reset() {
        samples.clear();
        lastSampleMs = -1;
        classification = Classification.NONE;
        decision = null;
        lastChangeMs = Long.MIN_VALUE / 2;
        lastCount = 0;
    }

    /**
     * Re-evaluates the windows at {@code nowMs}.
     *
     * @return the new decision when it changed (to be published), or null when nothing is to be
     *         published: no change, the silently adopted first decision, or a deferred change.
     */
    public synchronized Decision evaluate(long nowMs, Settings s) {
        int maxWindow = Math.max(s.nearWindowMs, s.farWindowMs);
        while (!samples.isEmpty() && nowMs - samples.peekFirst()[0] > maxWindow) samples.removeFirst();

        int nearCount = 0, nearHits = 0, farCount = 0, farHits = 0, shortCount = 0, shortSum = 0;
        long latest = Integer.MIN_VALUE;
        int shortWindow = Math.min(s.nearWindowMs, s.farWindowMs);
        for (long[] x : samples) {
            long age = nowMs - x[0];
            if (age <= s.nearWindowMs) { nearCount++; if (x[1] >= s.nearRssi) nearHits++; }
            if (age <= s.farWindowMs) { farCount++; if (x[1] <= s.farRssi) farHits++; }
            if (age <= shortWindow) { shortCount++; shortSum += x[1]; }
            latest = x[1];
        }
        lastCount = shortCount;
        lastAverage = shortCount == 0 ? 0 : Math.round((float) shortSum / shortCount);

        boolean isNear = nearCount >= s.nearMinSamples && nearHits * 100 >= s.percent * nearCount;
        boolean isFar = farCount >= s.farMinSamples && farHits * 100 >= s.percent * farCount;
        if (lastSampleMs < 0 || nowMs - lastSampleMs > s.noSignalMs) classification = Classification.NONE;
        else if (isNear && isFar) classification = latest >= s.nearRssi ? Classification.NEAR : Classification.FAR;
        else if (isNear) classification = Classification.NEAR;
        else if (isFar) classification = Classification.FAR;
        else if (nearCount < s.nearMinSamples && farCount < s.farMinSamples) classification = Classification.SPARSE;
        else classification = Classification.BETWEEN;

        Decision wanted;
        if (classification == Classification.NEAR) wanted = Decision.NEAR;
        else if (classification == Classification.FAR) wanted = Decision.FAR;
        else return null;                                   // BETWEEN / SPARSE / NONE: keep
        if (wanted == decision) return null;
        if (decision == null) {                             // first decision: adopt silently
            decision = wanted;
            lastChangeMs = nowMs;
            return null;
        }
        if (nowMs - lastChangeMs < MIN_CHANGE_GAP_MS) return null;   // deferred, retried next tick
        decision = wanted;
        lastChangeMs = nowMs;
        return wanted;
    }

    public synchronized Classification classification() { return classification; }

    public synchronized Decision decision() { return decision; }

    /** Samples in the shorter of the two windows at the last {@link #evaluate}. */
    public synchronized int windowCount() { return lastCount; }

    /** Average RSSI over the shorter window at the last {@link #evaluate}; 0 when empty. */
    public synchronized int windowAverage() { return lastAverage; }

    /** Time of the newest sample, or -1. */
    public synchronized long lastSampleMs() { return lastSampleMs; }

    static int clampInt(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }
}
