package com.overdrive.app.usbrssi;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;

import com.overdrive.app.logging.DaemonLogger;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Daemon-side USB transport for {@link UsbRssiModule}.
 *
 * <p>The head unit's camera daemon runs as the {@code shell} user (uid 2000) through
 * {@code app_process}, which is the one context on this ROM that BYD's power-off kill leaves
 * running. The shell user holds {@code MANAGE_USB}, so it may open a USB device without the
 * on-screen permission prompt an ordinary app would get — there is no screen to tap on a parked
 * head unit. We therefore reach {@code IUsbManager} directly and open the ESP32-C3 scanner
 * (VID {@code 0x303a}, PID {@code 0x1001}) over its CDC-ACM data interface, claiming it away from
 * the kernel's {@code cdc_acm} driver.
 *
 * <p>Two private members of {@link UsbDeviceConnection} are reached by reflection, and only
 * because this process has no Activity {@link android.content.Context}: the public
 * {@code open()} / {@code bulkTransfer()} path dereferences the application context (null here →
 * NPE → the runtime's KillApplicationHandler SIGKILLs us). The native legs {@code native_open}
 * and {@code native_bulk_request} do the same I/O without touching a context. Nothing here grants
 * any capability the shell user does not already have; it only lets the standard USB-host I/O run
 * outside an app process.
 *
 * <p>This class is the whole of what {@link UsbRssiModule} delegates: enumerate, open, read lines
 * and feed them to {@link UsbRssiModule#onScannerLine}, queue outbound commands, and push a
 * firmware image. The locking/decision logic stays in the module and the automations — this link
 * never touches the vehicle.
 */
public final class UsbRssiLink implements UsbRssiModule.Link {

    private static final String TAG = "UsbRssiLink";
    private static final int ESP_VID = 0x303a;
    private static final int ESP_PID = 0x1001;
    private static final int FWUP_WAIT_MS = 180_000;

    private final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private final ConcurrentLinkedQueue<String> outbox = new ConcurrentLinkedQueue<>();

    private volatile boolean running = false;
    private volatile boolean connected = false;
    private volatile String state = "stopped";
    private Thread thread;

    // IUsbManager handle, resolved once the reader thread starts.
    private Object usbManager;
    private Class<?> usbManagerIface;

    // Firmware hand-off: the HTTP thread leaves an image here and blocks; the reader thread,
    // which is the only one allowed to touch the connection, runs the push and reports back.
    private final Object fwupLock = new Object();       // serializes sendFirmware callers
    private final Object fwupSignal = new Object();     // reader -> waiting caller
    private volatile File fwupImage;
    private volatile String fwupMd5;                    // non-null is the trigger; set last
    private volatile String fwupResult;

    // ------------------------------------------------------------------ Link

    @Override
    public boolean isConnected() { return connected; }

    @Override
    public String stateText() { return state; }

    @Override
    public void send(String command) {
        if (command != null && !command.isEmpty()) outbox.add(command);
    }

    @Override
    public synchronized void start() {
        if (thread != null) return;
        running = true;
        state = "starting";
        Thread t = new Thread(this::usbLoop, "UsbRssiLink");
        t.setDaemon(true);
        thread = t;
        t.start();
    }

    @Override
    public synchronized void stop() {
        running = false;
        connected = false;
        state = "stopped";
        Thread t = thread;
        thread = null;
        if (t != null) t.interrupt();
        outbox.clear();
    }

    @Override
    public String sendFirmware(File image, String md5) {
        if (image == null || md5 == null) return "error: no image";
        synchronized (fwupLock) {
            if (!connected) return "error: no scanner connected";
            fwupResult = null;
            fwupImage = image;
            fwupMd5 = md5;                               // armed; the reader picks it up next loop
            long end = SystemClock.elapsedRealtime() + FWUP_WAIT_MS;
            synchronized (fwupSignal) {
                while (fwupResult == null && SystemClock.elapsedRealtime() < end) {
                    try {
                        fwupSignal.wait(1000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        fwupImage = null; fwupMd5 = null;
                        return "error: interrupted";
                    }
                }
            }
            String r = fwupResult;
            fwupImage = null; fwupMd5 = null; fwupResult = null;
            return r == null ? "error: reader never ran the update" : r;
        }
    }

    // ------------------------------------------------------------------ reader

    private UsbDevice findEsp() throws Exception {
        Bundle b = new Bundle();
        usbManagerIface.getMethod("getDeviceList", Bundle.class).invoke(usbManager, b);
        for (String k : b.keySet()) {
            UsbDevice d = b.getParcelable(k);
            if (d != null && d.getVendorId() == ESP_VID && d.getProductId() == ESP_PID) return d;
        }
        return null;
    }

    private void usbLoop() {
        Constructor<UsbDeviceConnection> ctor;
        Method nOpen, nBulk;
        try {
            IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                    .getMethod("getService", String.class).invoke(null, "usb");
            usbManager = Class.forName("android.hardware.usb.IUsbManager$Stub")
                    .getMethod("asInterface", IBinder.class).invoke(null, binder);
            usbManagerIface = Class.forName("android.hardware.usb.IUsbManager");
            ctor = UsbDeviceConnection.class.getDeclaredConstructor(UsbDevice.class);
            ctor.setAccessible(true);
            nOpen = UsbDeviceConnection.class.getDeclaredMethod(
                    "native_open", String.class, FileDescriptor.class);
            nOpen.setAccessible(true);
            // See the class doc: the public bulkTransfer() needs an app Context we do not have.
            nBulk = UsbDeviceConnection.class.getDeclaredMethod(
                    "native_bulk_request", int.class, byte[].class, int.class, int.class, int.class);
            nBulk.setAccessible(true);
        } catch (Throwable t) {
            state = "USB unavailable";
            logger.warn("USB RSSI link cannot reach the USB service: " + t);
            return;
        }

        boolean reportedNoDev = false;
        while (running) {
            UsbDevice dev;
            try {
                dev = findEsp();
            } catch (Exception e) {
                state = "enum error";
                logger.warn("USB enum error: " + e);
                sleep(2000);
                continue;
            }
            if (dev == null) {
                connected = false;
                state = "no scanner";
                if (!reportedNoDev) { logger.info("no ESP on the bus"); reportedNoDev = true; }
                sleep(2000);
                continue;
            }
            reportedNoDev = false;

            UsbDeviceConnection conn = null;
            UsbInterface data = null;
            try {
                usbManagerIface.getMethod("grantDevicePermission", UsbDevice.class, int.class)
                        .invoke(usbManager, dev, android.os.Process.myUid());
                ParcelFileDescriptor pfd = (ParcelFileDescriptor) usbManagerIface
                        .getMethod("openDevice", String.class, String.class)
                        .invoke(usbManager, dev.getDeviceName(), "com.android.shell");
                if (pfd == null) { state = "open failed"; logger.warn("openDevice returned null"); sleep(2000); continue; }
                conn = ctor.newInstance(dev);
                if (!(Boolean) nOpen.invoke(conn, dev.getDeviceName(), pfd.getFileDescriptor())) {
                    state = "open failed"; logger.warn("native_open failed"); sleep(2000); continue;
                }
                for (int i = 0; i < dev.getInterfaceCount(); i++) {
                    if (dev.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_CDC_DATA) {
                        data = dev.getInterface(i);
                    }
                }
                if (data == null || !conn.claimInterface(data, true)) {
                    state = "claim failed"; logger.warn("claimInterface failed"); sleep(2000); continue;
                }
                UsbEndpoint in = null, out = null;
                for (int i = 0; i < data.getEndpointCount(); i++) {
                    UsbEndpoint ep = data.getEndpoint(i);
                    if (ep.getType() != UsbConstants.USB_ENDPOINT_XFER_BULK) continue;
                    if (ep.getDirection() == UsbConstants.USB_DIR_IN) in = ep; else out = ep;
                }
                if (in == null) { state = "no bulk IN"; logger.warn("no bulk IN endpoint"); sleep(2000); continue; }

                connected = true;
                state = "connected";
                logger.info("scanner open " + dev.getDeviceName()
                        + (out == null ? " (no bulk OUT: commands disabled)" : ""));
                if (out != null) sendCommand(conn, nBulk, out, "VER");

                byte[] buf = new byte[512];
                StringBuilder line = new StringBuilder();
                long lastData = SystemClock.elapsedRealtime();
                while (running) {
                    if (fwupMd5 != null && out != null) {
                        File img = fwupImage;
                        String md5 = fwupMd5;
                        String r = runFwup(conn, nBulk, in, out, img, md5);
                        synchronized (fwupSignal) { fwupResult = r; fwupSignal.notifyAll(); }
                        sleep(3000);                     // the ESP restarts either way: reopen
                        break;
                    }
                    String cmd;
                    while (out != null && (cmd = outbox.poll()) != null) sendCommand(conn, nBulk, out, cmd);
                    int n = (Integer) nBulk.invoke(conn, in.getAddress(), buf, 0, buf.length, 500);
                    long now = SystemClock.elapsedRealtime();
                    if (n > 0) {
                        lastData = now;
                        for (int i = 0; i < n; i++) {
                            char c = (char) (buf[i] & 0xff);
                            if (c == '\n') { UsbRssiModule.getInstance().onScannerLine(line.toString()); line.setLength(0); }
                            else if (c != '\r' && line.length() < 200) line.append(c);
                        }
                    } else if (now - lastData > 6000) {   // the ESP heartbeats every 2 s
                        if (findEsp() == null) { connected = false; state = "scanner left the bus"; logger.info("ESP left the bus"); }
                        else { state = "silent, reopening"; logger.info("silent 6 s, reopening"); }
                        break;
                    }
                }
            } catch (Throwable t) {
                Throwable c = t instanceof InvocationTargetException ? t.getCause() : t;
                state = "error";
                logger.warn("USB error: " + c);
                sleep(2000);
            } finally {
                connected = false;
                if (conn != null) {
                    try { if (data != null) conn.releaseInterface(data); } catch (Throwable ignored) { }
                    try { conn.close(); } catch (Throwable ignored) { }
                }
            }
        }
        state = "stopped";
    }

    // ------------------------------------------------------------------ firmware (FWUP protocol)

    /** Reads bulk IN until a line starting with one of the prefixes arrives; null on timeout. */
    private String waitLine(UsbDeviceConnection conn, Method nBulk, UsbEndpoint in, StringBuilder pending,
                            long timeoutMs, String... prefixes) throws Exception {
        long end = SystemClock.elapsedRealtime() + timeoutMs;
        byte[] buf = new byte[512];
        while (SystemClock.elapsedRealtime() < end) {
            int nl;
            while ((nl = pending.indexOf("\n")) >= 0) {
                String l = pending.substring(0, nl).trim();
                pending.delete(0, nl + 1);
                for (String p : prefixes) if (l.startsWith(p)) return l;
            }
            int n = (Integer) nBulk.invoke(conn, in.getAddress(), buf, 0, buf.length, 200);
            if (n > 0) for (int i = 0; i < n; i++) if (buf[i] != '\r') pending.append((char) (buf[i] & 0xff));
        }
        return null;
    }

    private boolean writeAll(UsbDeviceConnection conn, Method nBulk, UsbEndpoint out, byte[] b, int off, int len) throws Exception {
        int n = (Integer) nBulk.invoke(conn, out.getAddress(), b, off, len, 5000);
        return n == len;
    }

    /** FWUP protocol (see the ESP sketch): command, "ready &lt;chunk&gt;", chunk/ack, then "ok"/"err". */
    private String runFwup(UsbDeviceConnection conn, Method nBulk, UsbEndpoint in, UsbEndpoint out,
                           File image, String md5) {
        long t0 = SystemClock.elapsedRealtime();
        UsbRssiModule module = UsbRssiModule.getInstance();
        try {
            byte[] img;
            try (FileInputStream fi = new FileInputStream(image)) {
                img = UsbRssiModule.readAll(fi);
            }
            int total = img.length, sent = 0;
            StringBuilder pending = new StringBuilder();
            String cmd = "FWUP " + total + " " + md5;
            String r = null;
            for (int attempt = 1; attempt <= 3 && r == null; attempt++) {
                module.onFirmwareProgress("preparing scanner (try " + attempt + ")", sent, total);
                byte[] c = (cmd + "\n").getBytes(StandardCharsets.US_ASCII);
                writeAll(conn, nBulk, out, c, 0, c.length);
                r = waitLine(conn, nBulk, in, pending, 4000, "FWUP ready", "FWUP err", "ERR unknown command");
            }
            if (r == null) return "error: scanner did not answer";
            if (r.startsWith("ERR unknown")) return "error: scanner firmware too old for USB update (pre-0.7)";
            if (r.startsWith("FWUP err")) return "error: " + r.substring(9);
            int chunk = Integer.parseInt(r.split(" ")[2]);
            logger.info("FWUP start " + total + " bytes, chunk " + chunk + ", md5 " + md5);
            module.onFirmwareProgress("sending", sent, total);
            while (sent < total) {
                int len = Math.min(chunk, total - sent);
                if (!writeAll(conn, nBulk, out, img, sent, len)) return "error: USB write at " + sent;
                String a = waitLine(conn, nBulk, in, pending, 10000, "FWUP ack", "FWUP err");
                if (a == null) return "error: no ack at " + sent;
                if (a.startsWith("FWUP err")) return "error: " + a.substring(9);
                int acked = Integer.parseInt(a.split(" ")[2]);
                if (acked != sent + len) return "error: ack mismatch at " + sent;
                sent = acked;
                module.onFirmwareProgress("sending", sent, total);
            }
            module.onFirmwareProgress("verifying", sent, total);
            String fin = waitLine(conn, nBulk, in, pending, 20000, "FWUP ok", "FWUP err");
            long s = (SystemClock.elapsedRealtime() - t0) / 1000;
            // startsWith, not equals: the ESP reboots right after printing this and the line end can
            // be lost, so the ROM boot banner lands on the same line ("FWUP okESP-ROM:...").
            if (fin != null && fin.startsWith("FWUP ok")) {
                logger.info("FWUP ok in " + s + " s");
                return "done (" + s + " s), scanner rebooting into the new firmware";
            }
            return "error: " + (fin == null ? "no verify answer" : fin.substring(9)) + " — kept the old firmware";
        } catch (Throwable t) {
            Throwable c = t instanceof InvocationTargetException ? t.getCause() : t;
            logger.warn("FWUP error: " + c);
            return "error: " + c;
        }
    }

    private void sendCommand(UsbDeviceConnection conn, Method nBulk, UsbEndpoint out, String cmd) {
        byte[] b = (cmd + "\n").getBytes(StandardCharsets.US_ASCII);
        try {
            int n = (Integer) nBulk.invoke(conn, out.getAddress(), b, 0, b.length, 1000);
            if (n != b.length) logger.warn("USB send short (" + n + "/" + b.length + "): " + cmd);
        } catch (Exception e) {
            logger.warn("USB send error " + e + ": " + cmd);
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }
}
