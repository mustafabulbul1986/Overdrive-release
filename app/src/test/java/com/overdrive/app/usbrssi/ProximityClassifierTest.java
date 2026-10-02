package com.overdrive.app.usbrssi;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import com.overdrive.app.usbrssi.ProximityClassifier.Classification;
import com.overdrive.app.usbrssi.ProximityClassifier.Decision;
import org.junit.Test;

public class ProximityClassifierTest {

    private static ProximityClassifier.Settings settings() {
        ProximityClassifier.Settings s = new ProximityClassifier.Settings();
        s.nearRssi = -70;
        s.farRssi = -85;
        s.nearWindowMs = 2000;
        s.farWindowMs = 2000;
        s.percent = 80;
        s.nearMinSamples = 2;
        s.farMinSamples = 2;
        s.noSignalMs = 3000;
        return s;
    }

    /** Feeds one sample every 200 ms (5/s, the parked-car rate) and evaluates after each. */
    private static Decision feed(ProximityClassifier c, ProximityClassifier.Settings s,
                                 long fromMs, long toMs, int rssi) {
        Decision published = null;
        for (long t = fromMs; t <= toMs; t += 200) {
            c.add(t, rssi);
            Decision d = c.evaluate(t, s);
            if (d != null) published = d;
        }
        return published;
    }

    @Test
    public void firstDecisionIsAdoptedSilently() {
        ProximityClassifier c = new ProximityClassifier();
        assertNull(feed(c, settings(), 0, 3000, -60));
        assertEquals(Decision.NEAR, c.decision());
        assertEquals(Classification.NEAR, c.classification());
    }

    @Test
    public void walkingAwayPublishesFarThenComingBackPublishesNear() {
        ProximityClassifier c = new ProximityClassifier();
        ProximityClassifier.Settings s = settings();
        feed(c, s, 0, 3000, -60);
        assertEquals(Decision.FAR, feed(c, s, 9000, 12000, -92));
        assertEquals(Decision.NEAR, feed(c, s, 20000, 23000, -58));
    }

    @Test
    public void noSignalKeepsTheDecision() {
        ProximityClassifier c = new ProximityClassifier();
        ProximityClassifier.Settings s = settings();
        feed(c, s, 0, 3000, -60);
        // Nothing for 20 s: NONE, but the decision stays NEAR and nothing is published.
        for (long t = 3200; t <= 23000; t += 200) assertNull(c.evaluate(t, s));
        assertEquals(Classification.NONE, c.classification());
        assertEquals(Decision.NEAR, c.decision());
    }

    @Test
    public void betweenKeepsTheDecision() {
        ProximityClassifier c = new ProximityClassifier();
        ProximityClassifier.Settings s = settings();
        feed(c, s, 0, 3000, -60);
        assertNull(feed(c, s, 9000, 15000, -78));           // between -70 and -85
        assertEquals(Classification.BETWEEN, c.classification());
        assertEquals(Decision.NEAR, c.decision());
    }

    @Test
    public void tooFewSamplesIsSparseAndKeepsTheDecision() {
        ProximityClassifier c = new ProximityClassifier();
        ProximityClassifier.Settings s = settings();
        feed(c, s, 0, 3000, -60);
        c.add(9000, -95);                                   // a single packet in the window
        assertNull(c.evaluate(9000, s));
        assertEquals(Classification.SPARSE, c.classification());
        assertEquals(Decision.NEAR, c.decision());
    }

    @Test
    public void quickChangeIsDeferredNotDropped() {
        ProximityClassifier c = new ProximityClassifier();
        ProximityClassifier.Settings s = settings();
        feed(c, s, 0, 3000, -60);                           // adopt NEAR
        c.add(10000, -92);
        c.add(10100, -92);
        assertEquals(Decision.FAR, c.evaluate(10100, s));   // the -60s are out of the window
        // Back near straight away: the windows say NEAR from ~11.9 s, inside the 5 s gap,
        // so nothing is published yet...
        assertNull(feed(c, s, 11100, 15000, -58));
        assertEquals(Classification.NEAR, c.classification());
        assertEquals(Decision.FAR, c.decision());
        // ...but once the gap has passed (10.1 + 5 s) it is applied.
        assertEquals(Decision.NEAR, feed(c, s, 15200, 15400, -58));
    }

    @Test
    public void oneOutlierIsToleratedWithEnoughSamples() {
        ProximityClassifier c = new ProximityClassifier();
        ProximityClassifier.Settings s = settings();
        feed(c, s, 0, 3000, -92);                           // adopt FAR
        // 10 samples in the window, one of them weak: 9/10 = 90% >= 80% -> NEAR.
        long t0 = 9000;
        for (int i = 0; i < 10; i++) c.add(t0 + i * 200, i == 4 ? -95 : -60);
        assertEquals(Decision.NEAR, c.evaluate(t0 + 1800, s));
    }

    @Test
    public void resetForgetsTheDecision() {
        ProximityClassifier c = new ProximityClassifier();
        ProximityClassifier.Settings s = settings();
        feed(c, s, 0, 3000, -60);
        c.reset();
        assertNull(c.decision());
        assertEquals(Classification.NONE, c.classification());
        assertNull(feed(c, s, 5000, 8000, -92));            // adopted silently again
        assertEquals(Decision.FAR, c.decision());
    }
}
