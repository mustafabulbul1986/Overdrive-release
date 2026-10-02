package com.overdrive.app.server;

import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.usbrssi.UsbRssiModule;

import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Base64;

/**
 * API for the USB RSSI device page.
 *
 * <ul>
 *   <li>{@code GET  /api/usb-rssi}          — settings, live state, chart points, recent events</li>
 *   <li>{@code POST /api/usb-rssi/config}   — change settings (including the master switch)</li>
 *   <li>{@code POST /api/usb-rssi/firmware} — update the scanner, {@code {"image": "<base64>"}}</li>
 * </ul>
 */
public class UsbRssiApiHandler {

    private static final String TAG = "UsbRssiApiHandler";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String STAGED_IMAGE = "/data/local/tmp/odkey-firmware.bin";

    /** Guards against a second upload while one is still being pushed to the scanner. */
    private static volatile boolean installing = false;

    public static boolean handle(String method, String path, String body, OutputStream out) throws Exception {
        String clean = path.contains("?") ? path.substring(0, path.indexOf('?')) : path;

        if (("/api/usb-rssi".equals(clean) || "/api/usb-rssi/status".equals(clean)) && "GET".equals(method)) {
            HttpResponse.sendJson(out, UsbRssiModule.getInstance().status().toString());
            return true;
        }

        if ("/api/usb-rssi/config".equals(clean) && "POST".equals(method)) {
            JSONObject in = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            String rejected = UsbRssiModule.getInstance().updateSettings(in);
            if (rejected != null) {
                HttpResponse.sendJson(out, new JSONObject()
                        .put("success", false).put("rejected", rejected).toString());
                return true;
            }
            HttpResponse.sendJson(out, UsbRssiModule.getInstance().status().toString());
            return true;
        }

        if ("/api/usb-rssi/firmware".equals(clean) && "POST".equals(method)) {
            HttpResponse.sendJson(out, installFirmware(body).toString());
            return true;
        }

        return false;
    }

    private static JSONObject installFirmware(String body) throws Exception {
        JSONObject r = new JSONObject();
        try {
            if (installing) return r.put("success", false).put("error", "an update is already running");
            JSONObject in = new JSONObject(body == null || body.isEmpty() ? "{}" : body);
            String b64 = in.optString("image", "");
            if (b64.isEmpty()) return r.put("success", false).put("error", "no image");
            byte[] image;
            try {
                image = Base64.getDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                return r.put("success", false).put("error", "image is not valid base64");
            }
            File staged = new File(STAGED_IMAGE);
            String md5;
            try {
                md5 = UsbRssiModule.receiveFirmware(
                        new ByteArrayInputStream(image), image.length, staged);
            } catch (IOException e) {
                // Rejected before anything reached the scanner, so it keeps running as it is.
                return r.put("success", false).put("error", e.getMessage());
            }
            installing = true;
            // The push takes about half a minute; answer now and let the page poll the status.
            Thread t = new Thread(() -> {
                try {
                    UsbRssiModule.getInstance().installFirmware(staged, md5);
                } catch (Throwable e) {
                    logger.error("scanner firmware push failed", e);
                } finally {
                    installing = false;
                }
            }, "UsbRssiFirmware");
            t.setDaemon(true);
            t.start();
            return r.put("success", true).put("size", image.length).put("md5", md5);
        } catch (Exception e) {
            logger.error("firmware upload failed", e);
            return r.put("success", false).put("error", String.valueOf(e.getMessage()));
        }
    }
}
