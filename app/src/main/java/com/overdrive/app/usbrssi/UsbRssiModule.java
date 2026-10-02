package com.overdrive.app.usbrssi;

import com.overdrive.app.automation.Automations;
import com.overdrive.app.config.UnifiedConfigManager;
import com.overdrive.app.logging.DaemonLogger;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * USB RSSI device — phone proximity as an automation signal.
 *
 * <p>A small BLE scanner on the head unit's USB port (an ESP32-C3 running the companion
 * "odkey-scanner" sketch) reports every advertisement of one iBeacon UUID as
 * {@code "B <major> <minor> <rssi>"}, plus a heartbeat every 2 s. This module turns that
 * stream into a {@code phoneProximity} signal (near / far) that automations can trigger on.
 * It does not touch the locks: "unlock when I walk up" is an ordinary automation the user
 * writes, which keeps the vehicle-control side exactly where it already is.
 *
 * <p>This class owns everything except the USB link itself: settings, persistence, the
 * decision loop, the status the page renders, and validation of a firmware image before it
 * is pushed to the scanner. Opening the device is deliberately behind {@link Link} so the
 * transport can be replaced (daemon-side reader, or an app-process reader relaying through
 * {@link Automations#publishExternalEvent}) without touching any of the logic above.
 *
 * <p>Threading: the public entry points are called from the link's reader thread, the tick
 * thread and the HTTP threads, so every field is guarded by the monitor of this instance
 * except the classifier, which guards itself.
 */
public final class UsbRssiModule {

    private static final String TAG = "UsbRssi";
    private static final String SECTION = "usbRssi";
    private static final long TICK_MS = 200;
    private static final long HISTORY_MS = 120_000;
    private static final int MAX_EVENTS = 40;
    /** OTA slot of the scanner's default 4 MB layout. */
    private static final int FIRMWARE_MAX_BYTES = 0x140000;
    private static final int ESP32C3_CHIP_ID = 5;

    /** The USB transport. Implementations open the scanner and move bytes; nothing more. */
    public interface Link {
        /** True while the scanner is open and answering. */
        boolean isConnected();

        /** One short line for the page, e.g. "connected" or "no device". */
        String stateText();

        /** Queues a command for the scanner (e.g. {@code "UUID <uuid>"}). */
        void send(String command);

        /**
         * Pushes a validated image to the scanner, blocking until it is done.
         *
         * @return a human-readable result; implementations report progress through
         *         {@link #onFirmwareProgress}.
         */
        String sendFirmware(File image, String md5);

        /** Stops reading and releases the device. */
        void stop();

        /** Starts reading; called when the module is switched on. */
        void start();
    }

    private static final UsbRssiModule INSTANCE = new UsbRssiModule();

    public static UsbRssiModule getInstance() { return INSTANCE; }

    private final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private final ProximityClassifier classifier = new ProximityClassifier();
    private final ArrayDeque<long[]> history = new ArrayDeque<>();   // {uptimeMs, rssi}
    private final ArrayDeque<String> events = new ArrayDeque<>();
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss", Locale.US);

    // ---- settings ----
    private boolean enabled = false;
    private ProximityClassifier.Settings settings = new ProximityClassifier.Settings();
    private String uuid = "";
    private int major = 0, minor = 0;

    // ---- live state ----
    private Link link = null;
    private Thread ticker = null;
    private boolean loaded = false;
    private String scannerVersion = "-";
    private String scannerUuid = "-";
    private long lastBeaconMs = -1;
    private String firmwareState = "-";
    private int firmwareSent = 0, firmwareTotal = 0;

    private UsbRssiModule() { }

    // ------------------------------------------------------------------ lifecycle

    /**
     * Registers the transport and starts the module if it is switched on. Called once while
     * the daemon boots; safe to call with a null link (the page then explains there is none).
     */
    public synchronized void init(Link link) {
        this.link = link;
        load();
        if (enabled) startLocked();
        else logger.info("USB RSSI device is switched off");
    }

    private void startLocked() {
        if (ticker != null) return;
        classifier.reset();
        if (link != null) {
            link.start();
            if (!uuid.isEmpty()) link.send("UUID " + uuid);
        }
        Thread t = new Thread(this::tickLoop, "UsbRssiTick");
        t.setDaemon(true);
        ticker = t;
        t.start();
        note("started");
    }

    private void stopLocked() {
        Thread t = ticker;
        ticker = null;
        if (t != null) t.interrupt();
        if (link != null) link.stop();
        classifier.reset();
        synchronized (history) { history.clear(); }
        lastBeaconMs = -1;
        note("stopped");
    }

    private void tickLoop() {
        while (true) {
            synchronized (this) { if (ticker != Thread.currentThread()) return; }
            try {
                ProximityClassifier.Settings s;
                synchronized (this) { s = settings.copy(); }
                ProximityClassifier.Decision d = classifier.evaluate(now(), s);
                if (d != null) publish(d);
                Thread.sleep(TICK_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Throwable t) {
                logger.warn("USB RSSI tick failed: " + t);
                try { Thread.sleep(1000); } catch (InterruptedException e) { return; }
            }
        }
    }

    private void publish(ProximityClassifier.Decision d) {
        String value = d == ProximityClassifier.Decision.NEAR ? "near" : "far";
        Automations.publishExternalEvent("phoneProximity", value);
        note("phone " + value);
        logger.info("phoneProximity -> " + value);
    }

    private static long now() { return android.os.SystemClock.elapsedRealtime(); }

    // ------------------------------------------------------------------ input from the link

    /**
     * One line from the scanner. Beacon packets that match the configured major/minor feed the
     * classifier; the rest is status the page shows. Ignored while switched off, so a link that
     * is still draining its buffer cannot resurrect the signal.
     */
    public void onScannerLine(String line) {
        if (line == null) return;
        String l = line.trim();
        if (l.isEmpty()) return;
        synchronized (this) { if (ticker == null) return; }
        if (l.startsWith("B ")) {
            String[] f = l.split(" ");
            if (f.length != 4) return;
            try {
                int ma = Integer.parseInt(f[1]), mi = Integer.parseInt(f[2]), rssi = Integer.parseInt(f[3]);
                synchronized (this) {
                    if (ma != major || mi != minor) return;
                    lastBeaconMs = now();
                }
                classifier.add(now(), rssi);
                synchronized (history) {
                    history.addLast(new long[]{now(), rssi});
                    while (!history.isEmpty() && now() - history.peekFirst()[0] > HISTORY_MS) history.removeFirst();
                }
            } catch (NumberFormatException ignored) {
                // a truncated line; the next packet is 150 ms away
            }
        } else if (l.startsWith("BOOT ")) {
            // The scanner restarted and forgot nothing but its link state; re-send the UUID.
            String[] f = l.split(" ");
            String version = f.length >= 3 ? f[1] + " " + f[2] : l;
            String u;
            synchronized (this) { scannerVersion = version; u = uuid; }
            note("scanner restarted: " + version);
            if (!u.isEmpty() && link != null) link.send("UUID " + u);
        } else if (l.startsWith("odkey-scanner ")) {        // answer to VER
            synchronized (this) { scannerVersion = l; }
        } else if (l.startsWith("UUID ok ")) {
            String[] f = l.split(" ");
            if (f.length >= 3) synchronized (this) { scannerUuid = f[2]; }
        } else if (l.startsWith("UUID ")) {                 // answer to a bare UUID query
            synchronized (this) { scannerUuid = l.substring(5).trim(); }
        } else if (l.startsWith("ERR")) {
            note("scanner: " + l);
        }
    }

    /** Progress of a running firmware push, reported by the link. */
    public synchronized void onFirmwareProgress(String state, int sent, int total) {
        firmwareState = state;
        firmwareSent = sent;
        firmwareTotal = total;
    }

    private void note(String text) {
        synchronized (events) {
            events.addLast(clock.format(new Date()) + " " + text);
            while (events.size() > MAX_EVENTS) events.removeFirst();
        }
    }

    // ------------------------------------------------------------------ settings

    private void load() {
        if (loaded) return;
        loaded = true;
        try {
            JSONObject o = UnifiedConfigManager.loadConfig().optJSONObject(SECTION);
            if (o != null) applyLocked(o);
        } catch (Throwable t) {
            logger.warn("USB RSSI config unreadable, using defaults: " + t);
        }
    }

    private void applyLocked(JSONObject o) {
        enabled = o.optBoolean("enabled", enabled);
        settings.nearRssi = o.optInt("nearRssi", settings.nearRssi);
        settings.farRssi = o.optInt("farRssi", settings.farRssi);
        settings.nearWindowMs = o.optInt("nearWindowMs", settings.nearWindowMs);
        settings.farWindowMs = o.optInt("farWindowMs", settings.farWindowMs);
        settings.percent = o.optInt("percent", settings.percent);
        settings.nearMinSamples = o.optInt("nearMinSamples", settings.nearMinSamples);
        settings.farMinSamples = o.optInt("farMinSamples", settings.farMinSamples);
        settings.noSignalMs = o.optInt("noSignalMs", settings.noSignalMs);
        settings.clamp();
        major = ProximityClassifier.clampInt(o.optInt("major", major), 0, 65535);
        minor = ProximityClassifier.clampInt(o.optInt("minor", minor), 0, 65535);
        String u = normalizeUuid(o.optString("uuid", uuid));
        if (u != null) uuid = u;
    }

    /** 32 hex digits with or without dashes, to lowercase 8-4-4-4-12; null when it is not one. */
    public static String normalizeUuid(String raw) {
        if (raw == null) return null;
        String h = raw.trim().replace("-", "").toLowerCase(Locale.US);
        if (h.isEmpty()) return "";
        if (!h.matches("[0-9a-f]{32}")) return null;
        return h.substring(0, 8) + "-" + h.substring(8, 12) + "-" + h.substring(12, 16) + "-"
                + h.substring(16, 20) + "-" + h.substring(20);
    }

    /**
     * Applies a settings change from the page and persists it.
     *
     * @return null on success, or the reason it was rejected.
     */
    public synchronized String updateSettings(JSONObject o) {
        load();
        if (o.has("uuid") && normalizeUuid(o.optString("uuid", "")) == null) {
            return "uuid";
        }
        boolean was = enabled;
        String previousUuid = uuid;
        applyLocked(o);

        Map<String, Object> values = new HashMap<>();
        values.put("enabled", enabled);
        values.put("nearRssi", settings.nearRssi);
        values.put("farRssi", settings.farRssi);
        values.put("nearWindowMs", settings.nearWindowMs);
        values.put("farWindowMs", settings.farWindowMs);
        values.put("percent", settings.percent);
        values.put("nearMinSamples", settings.nearMinSamples);
        values.put("farMinSamples", settings.farMinSamples);
        values.put("noSignalMs", settings.noSignalMs);
        values.put("uuid", uuid);
        values.put("major", major);
        values.put("minor", minor);
        UnifiedConfigManager.updateValues(SECTION, values);

        if (enabled && !was) startLocked();
        else if (!enabled && was) stopLocked();
        else if (enabled && !uuid.equals(previousUuid) && link != null) link.send("UUID " + uuid);
        return null;
    }

    public synchronized boolean isEnabled() { load(); return enabled; }

    // ------------------------------------------------------------------ firmware

    /**
     * Reads an uploaded scanner image to {@code target} and checks it really is one.
     *
     * @return its MD5, for the scanner to verify against after the transfer.
     * @throws IOException with a reason the page shows when it is not a usable image.
     */
    public static String receiveFirmware(InputStream in, int length, File target) throws IOException {
        if (length <= 24 || length > FIRMWARE_MAX_BYTES) {
            throw new IOException("size " + length + " is not a scanner image");
        }
        MessageDigest md;
        try {
            md = MessageDigest.getInstance("MD5");
        } catch (Exception e) {
            throw new IOException("MD5 unavailable: " + e);
        }
        byte[] head = new byte[16];
        try (FileOutputStream fo = new FileOutputStream(target)) {
            byte[] buf = new byte[8192];
            int got = 0;
            while (got < length) {
                int r = in.read(buf, 0, Math.min(buf.length, length - got));
                if (r < 0) throw new IOException("upload ended early");
                if (got < head.length) System.arraycopy(buf, 0, head, got, Math.min(head.length - got, r));
                fo.write(buf, 0, r);
                md.update(buf, 0, r);
                got += r;
            }
        }
        // ESP application image: magic 0xE9 at byte 0, chip id (LE uint16) at byte 12.
        if ((head[0] & 0xff) != 0xE9) throw new IOException("not an ESP firmware file");
        int chip = (head[12] & 0xff) | ((head[13] & 0xff) << 8);
        if (chip != ESP32C3_CHIP_ID) throw new IOException("built for chip id " + chip + ", not ESP32-C3");
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format(Locale.US, "%02x", b));
        return hex.toString();
    }

    /** Pushes a received image to the scanner. Blocking; the page polls {@link #status}. */
    public void installFirmware(File image, String md5) {
        Link l;
        synchronized (this) { l = link; }
        if (l == null || !l.isConnected()) {
            onFirmwareProgress("no scanner connected", 0, 0);
            return;
        }
        onFirmwareProgress("sending", 0, (int) image.length());
        String result = l.sendFirmware(image, md5);
        onFirmwareProgress(result, 0, 0);
        note("firmware: " + result);
        logger.info("firmware push: " + result);
    }

    // ------------------------------------------------------------------ status

    /** Everything the page renders. */
    public JSONObject status() throws Exception {
        JSONObject o = new JSONObject();
        JSONObject cfg = new JSONObject();
        long nowMs = now();
        synchronized (this) {
            load();
            cfg.put("enabled", enabled)
                    .put("nearRssi", settings.nearRssi).put("farRssi", settings.farRssi)
                    .put("nearWindowMs", settings.nearWindowMs).put("farWindowMs", settings.farWindowMs)
                    .put("percent", settings.percent)
                    .put("nearMinSamples", settings.nearMinSamples).put("farMinSamples", settings.farMinSamples)
                    .put("noSignalMs", settings.noSignalMs)
                    .put("uuid", uuid).put("major", major).put("minor", minor);
            o.put("config", cfg);
            o.put("running", ticker != null);
            o.put("hasLink", link != null);
            o.put("link", link == null ? "no reader" : link.stateText());
            o.put("scannerVersion", scannerVersion);
            o.put("scannerUuid", scannerUuid);
            o.put("beaconAgeMs", lastBeaconMs < 0 ? -1 : nowMs - lastBeaconMs);
            o.put("firmwareState", firmwareState);
            o.put("firmwareSent", firmwareSent);
            o.put("firmwareTotal", firmwareTotal);
        }
        o.put("classification", classifier.classification().name().toLowerCase(Locale.US));
        ProximityClassifier.Decision d = classifier.decision();
        o.put("decision", d == null ? "" : d.name().toLowerCase(Locale.US));
        o.put("windowCount", classifier.windowCount());
        o.put("windowAverage", classifier.windowCount() == 0 ? JSONObject.NULL : classifier.windowAverage());
        JSONArray chart = new JSONArray();
        synchronized (history) {
            for (long[] x : history) {
                chart.put(new JSONArray().put((x[0] - nowMs) / 1000.0).put(x[1]));
            }
        }
        o.put("history", chart);
        JSONArray log = new JSONArray();
        synchronized (events) { for (String e : events) log.put(e); }
        o.put("events", log);
        o.put("success", true);
        return o;
    }

    static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bo.write(b, 0, n);
        return bo.toByteArray();
    }
}
