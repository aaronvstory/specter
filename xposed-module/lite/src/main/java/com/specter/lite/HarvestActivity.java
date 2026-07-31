package com.specter.lite;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Specter Lite — a root-FREE harvester. Reads every identifier + device field obtainable WITHOUT root on
 * this device, then exports a Specter profile envelope (same format the main app's vault import consumes)
 * to public Download/Specter/ with a readable name (Specter-<mfr>-<model>-<stamp>.json). Copy that
 * file to another device (or it's already in Download on this one) and import it in Specter to clone this
 * device as closely as the root layer allows.
 *
 * Deliberately minimal + honest: fields that need root or a privileged permission to READ on modern
 * Android (IMEI, serial, IMSI, ICCID) are NOT invented — they're listed as "hand-enter in Specter", so
 * the harvest never fabricates a value it couldn't actually observe.
 */
public class HarvestActivity extends Activity {

    private static final int BG = 0xFF16161A, CARD = 0xFF212129, INK = 0xFFF1F1F4, GOLD = 0xFFE7B94E,
            DIM = 0xFF7D7D8A, SAGE = 0xFF7FB58C;

    private TextView out;
    private String lastPath;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView sc = new ScrollView(this);
        sc.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        sc.addView(root);

        TextView title = new TextView(this);
        title.setText("Specter Lite — harvest");
        title.setTextColor(GOLD);
        title.setTextSize(22);
        root.addView(title);

        TextView desc = new TextView(this);
        desc.setText("Collect this device's identifiers (no root needed) and export a profile you can import "
                + "into Specter on a rooted device. IMEI/serial/IMSI need root or a privileged permission to "
                + "read on modern Android, so they're left for you to hand-enter in Specter.");
        desc.setTextColor(DIM);
        desc.setTextSize(13);
        desc.setPadding(0, dp(6), 0, dp(12));
        root.addView(desc);

        Button harvest = new Button(this);
        harvest.setText("Harvest + export");
        harvest.setAllCaps(false);
        harvest.setTextSize(15);
        harvest.setTextColor(0xFF231A05);
        harvest.setStateListAnimator(null);
        // Rounded pill to match the main Specter app (was a flat rect).
        android.graphics.drawable.GradientDrawable pill = new android.graphics.drawable.GradientDrawable();
        pill.setColor(GOLD);
        pill.setCornerRadius(dp(3));
        harvest.setBackground(pill);
        harvest.setPadding(dp(16), dp(11), dp(16), dp(11));
        harvest.setOnClickListener(v -> doHarvest());
        root.addView(harvest);

        out = new TextView(this);
        out.setTextColor(INK);
        out.setTextSize(12);
        out.setTypeface(android.graphics.Typeface.MONOSPACE);
        out.setPadding(0, dp(14), 0, 0);
        out.setTextIsSelectable(true);
        root.addView(out);

        setContentView(sc);

        // Scriptable trigger: `am start -n com.specter.lite/.HarvestActivity --ez auto true` harvests
        // immediately (same code path as the button) so the harvest can run headless / from a test rig.
        maybeAutoHarvest(getIntent());
    }

    /** A re-launch of an ALREADY-RUNNING instance delivers to onNewIntent, NOT onCreate — so the auto
     *  trigger must be handled here too, or a scripted re-run silently does nothing. */
    @Override protected void onNewIntent(android.content.Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        maybeAutoHarvest(intent);
    }

    private void maybeAutoHarvest(android.content.Intent intent) {
        if (intent != null && intent.getBooleanExtra("auto", false)) doHarvest();
    }

    /** Runs collection + export OFF the UI thread — EGL/driver setup, MediaDrm, the GSF binder query and
     *  file I/O are all synchronous and could cross the ~5s input-ANR limit on a slow HAL/provider. UI
     *  updates (out/Toast) are posted back to the main thread. */
    private void doHarvest() {
        out.setText("Harvesting…");
        new Thread(() -> {
            Map<String, String> p = collect();
            String env = buildEnvelope(p);
            String name = exportName(p);
            String err = null;
            try {
                lastPath = writeExport(name, env);
            } catch (Throwable t) { err = String.valueOf(t); }
            final String fErr = err;
            runOnUiThread(() -> {
                if (fErr != null) {
                    out.setText("Export failed: " + fErr);
                    Toast.makeText(this, "Export failed", Toast.LENGTH_LONG).show();
                    return;
                }
                StringBuilder sb = new StringBuilder();
                sb.append("Exported ").append(p.size()).append(" fields to:\n").append(lastPath).append("\n\n");
                sb.append("On the target device open Specter -> Saved -> Import from Download (the file is "
                        + "in Download/" + EXPORT_DIR + ").\n\n--- harvested ---\n");
                for (Map.Entry<String, String> e : p.entrySet())
                    sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
                out.setText(sb.toString());
                Toast.makeText(this, "Harvested to " + lastPath, Toast.LENGTH_LONG).show();
            });
        }, "specter-lite-harvest").start();
    }

    /** Read every field obtainable without root. Missing/unreadable ones are simply omitted (never faked). */
    private Map<String, String> collect() {
        Map<String, String> p = new LinkedHashMap<>();
        // Settings.Secure ANDROID_ID — readable by any app (per-app-scoped on API 26+, but still the real
        // device value for THIS app, which is what a fingerprinter on the target would key on).
        try {
            String aid = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
            if (aid != null && !aid.isEmpty()) p.put("android_id", aid);
        } catch (Throwable ignored) {}
        // Build.* — all readable, no permission.
        put(p, "build_manufacturer", Build.MANUFACTURER);
        put(p, "build_brand", Build.BRAND);
        put(p, "build_device", Build.DEVICE);
        put(p, "build_product", Build.PRODUCT);
        put(p, "build_model", Build.MODEL);
        put(p, "build_id", Build.ID);
        put(p, "build_fingerprint", Build.FINGERPRINT);
        put(p, "build_bootloader", Build.BOOTLOADER);
        put(p, "build_hardware", Build.HARDWARE);
        put(p, "build_board", Build.BOARD);
        put(p, "build_host", Build.HOST);
        put(p, "build_display", Build.DISPLAY);
        put(p, "build_release", Build.VERSION.RELEASE);
        put(p, "build_incremental", Build.VERSION.INCREMENTAL);
        put(p, "build_security_patch", Build.VERSION.SECURITY_PATCH);
        put(p, "build_sdk", String.valueOf(Build.VERSION.SDK_INT));
        // Screen metrics (getDisplayMetrics signal).
        try {
            android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
            p.put("screen_width", String.valueOf(dm.widthPixels));
            p.put("screen_height", String.valueOf(dm.heightPixels));
            p.put("screen_density", String.valueOf(dm.densityDpi));
        } catch (Throwable ignored) {}
        // MediaDrm deviceUniqueId (Widevine) — readable without root.
        try {
            java.util.UUID widevine = new java.util.UUID(0xEDEF8BA979D64ACEL, 0xA3C827DCD51D21EDL);
            android.media.MediaDrm drm = new android.media.MediaDrm(widevine);
            byte[] id = drm.getPropertyByteArray(android.media.MediaDrm.PROPERTY_DEVICE_UNIQUE_ID);
            if (id != null && id.length > 0) {
                StringBuilder hex = new StringBuilder();
                for (byte x : id) hex.append(String.format("%02x", x));
                p.put("media_drm_id", hex.toString());
            }
            try { drm.close(); } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
        // Total RAM — ActivityManager.MemoryInfo.totalMem (bytes), no permission.
        try {
            android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
            android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            if (mi.totalMem > 0) p.put("total_ram", String.valueOf(mi.totalMem));
        } catch (Throwable ignored) {}
        // GPU renderer/vendor/GLES version — via a headless EGL pbuffer context (no root, no permission).
        collectGpu(p);
        // Sensor list — "name|vendor|type;..." matching the app's hw_sensors format. No permission.
        try {
            android.hardware.SensorManager sm = (android.hardware.SensorManager) getSystemService(SENSOR_SERVICE);
            java.util.List<android.hardware.Sensor> sensors = sm.getSensorList(android.hardware.Sensor.TYPE_ALL);
            StringBuilder sb = new StringBuilder();
            for (android.hardware.Sensor s : sensors) {
                if (sb.length() > 0) sb.append(';');
                sb.append(s.getName()).append('|').append(s.getVendor()).append('|').append(s.getType());
            }
            if (sb.length() > 0) p.put("hw_sensors", sb.toString());
        } catch (Throwable ignored) {}
        // Locale + timezone — the device's own, no permission.
        try {
            java.util.Locale lc = java.util.Locale.getDefault();
            String tag = lc.getLanguage() + (lc.getCountry().isEmpty() ? "" : "-" + lc.getCountry());
            if (!tag.isEmpty()) p.put("locale", tag);
        } catch (Throwable ignored) {}
        try { p.put("timezone", java.util.TimeZone.getDefault().getID()); } catch (Throwable ignored) {}
        // Carrier — operator MCC+MNC and name are readable without READ_PHONE_STATE. IMEI/IMSI/ICCID are
        // NOT (privileged) so we deliberately omit them (hand-enter in Specter), never fake them.
        try {
            android.telephony.TelephonyManager tm =
                    (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            String mccmnc = tm.getSimOperator();          // "" if no SIM — omitted by put()
            put(p, "sim_operator_mccmnc", mccmnc);
            put(p, "sim_operator_name", tm.getSimOperatorName());
            put(p, "network_operator_name", tm.getNetworkOperatorName());
        } catch (Throwable ignored) {}
        // GSF (Google Services Framework) ID — readable via its content provider without root, if GSF is
        // present. Guarded: absent on non-GAPPS devices, and a query failure must never crash the harvest.
        try {
            android.net.Uri uri = android.net.Uri.parse("content://com.google.android.gsf.gservices");
            String[] q = {"android_id"};
            // try-with-resources: the cursor closes even if moveToFirst/getString throws.
            try (android.database.Cursor c = getContentResolver().query(uri, null, null, q, null)) {
                if (c != null && c.moveToFirst() && c.getColumnCount() >= 2) {
                    String val = c.getString(1);
                    // The GSF gservices provider returns the id as a DECIMAL string (a signed 64-bit long)
                    // — exactly the format the app's gsf_id uses. Store it only if it's a valid positive
                    // decimal long (matches the app's validate(): allDigits + parsePositiveLong); never fake.
                    if (val != null && !val.isEmpty()) {
                        try {
                            long g = Long.parseLong(val.trim());   // decimal; throws on non-numeric/overflow
                            if (g > 0) p.put("gsf_id", String.valueOf(g));
                        } catch (NumberFormatException ignoredNfe) { /* not a decimal GSF id — omit */ }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return p;
    }

    /** Headless EGL 2.0 pbuffer context → GL_RENDERER / GL_VENDOR / GL_VERSION. No root, no permission.
     *  Best-effort: any failure just omits the GPU fields (never fakes them). */
    private void collectGpu(Map<String, String> p) {
        android.opengl.EGLDisplay dpy = android.opengl.EGL14.eglGetDisplay(android.opengl.EGL14.EGL_DEFAULT_DISPLAY);
        if (dpy == android.opengl.EGL14.EGL_NO_DISPLAY) return;
        int[] ver = new int[2];
        if (!android.opengl.EGL14.eglInitialize(dpy, ver, 0, ver, 1)) return;
        try {
            int[] cfgAttr = {
                android.opengl.EGL14.EGL_SURFACE_TYPE, android.opengl.EGL14.EGL_PBUFFER_BIT,
                android.opengl.EGL14.EGL_RENDERABLE_TYPE, android.opengl.EGL14.EGL_OPENGL_ES2_BIT,
                android.opengl.EGL14.EGL_RED_SIZE, 8, android.opengl.EGL14.EGL_GREEN_SIZE, 8,
                android.opengl.EGL14.EGL_BLUE_SIZE, 8, android.opengl.EGL14.EGL_NONE
            };
            android.opengl.EGLConfig[] cfg = new android.opengl.EGLConfig[1];
            int[] num = new int[1];
            if (!android.opengl.EGL14.eglChooseConfig(dpy, cfgAttr, 0, cfg, 0, 1, num, 0) || num[0] < 1) return;
            int[] pbAttr = { android.opengl.EGL14.EGL_WIDTH, 1, android.opengl.EGL14.EGL_HEIGHT, 1,
                    android.opengl.EGL14.EGL_NONE };
            android.opengl.EGLSurface surf = android.opengl.EGL14.eglCreatePbufferSurface(dpy, cfg[0], pbAttr, 0);
            int[] ctxAttr = { android.opengl.EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, android.opengl.EGL14.EGL_NONE };
            android.opengl.EGLContext ctx = android.opengl.EGL14.eglCreateContext(
                    dpy, cfg[0], android.opengl.EGL14.EGL_NO_CONTEXT, ctxAttr, 0);
            try {
                if (android.opengl.EGL14.eglMakeCurrent(dpy, surf, surf, ctx)) {
                    put(p, "hw_gpu_renderer", android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_RENDERER));
                    put(p, "hw_gpu_vendor",   android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VENDOR));
                    String glv = android.opengl.GLES20.glGetString(android.opengl.GLES20.GL_VERSION);
                    if (glv != null) {  // "OpenGL ES 3.2 V@..." → keep just the "3.2" the app stores
                        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+\\.\\d+)").matcher(glv);
                        if (m.find()) p.put("hw_gles_version", m.group(1));
                    }
                    android.opengl.EGL14.eglMakeCurrent(dpy, android.opengl.EGL14.EGL_NO_SURFACE,
                            android.opengl.EGL14.EGL_NO_SURFACE, android.opengl.EGL14.EGL_NO_CONTEXT);
                }
            } finally {   // destroy context/surface even if makeCurrent or a GL read threw
                if (ctx != null) try { android.opengl.EGL14.eglDestroyContext(dpy, ctx); } catch (Throwable ignored) {}
                if (surf != null) try { android.opengl.EGL14.eglDestroySurface(dpy, surf); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {
        } finally {
            try { android.opengl.EGL14.eglTerminate(dpy); } catch (Throwable ignored) {}
        }
    }

    /** Build the Specter portable envelope — MUST match the app's VaultPortable format (version + sorted-
     *  key SHA-256 checksum + profile), so the exported file imports cleanly in the main app. */
    static String buildEnvelope(Map<String, String> profile) {
        try {
            JSONObject prof = new JSONObject();
            for (Map.Entry<String, String> e : profile.entrySet()) prof.put(e.getKey(), e.getValue());
            String device = (get(profile, "build_manufacturer") + " " + get(profile, "build_model")).trim();
            JSONObject env = new JSONObject();
            env.put("specter_profile", 1);
            env.put("device", device.isEmpty() ? "unknown" : device);
            env.put("checksum", checksum(profile));
            env.put("profile", prof);
            return env.toString(2);
        } catch (Throwable t) { return "{}"; }
    }

    /** SHA-256 over "k=v\n" in SORTED key order — byte-identical to the app's VaultChecksum.of. */
    static String checksum(Map<String, String> profile) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(profile).entrySet())
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(sb.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(h.length * 2);
            for (byte b : h) { hex.append(Character.forDigit((b >> 4) & 0xf, 16)); hex.append(Character.forDigit(b & 0xf, 16)); }
            return hex.toString();
        } catch (Exception e) { return ""; }
    }

    private static String get(Map<String, String> m, String k) { String v = m.get(k); return v == null ? "" : v; }
    private static void put(Map<String, String> m, String k, String v) { if (v != null && !v.isEmpty()) m.put(k, v); }
    private String safeStamp() { return String.valueOf(System.currentTimeMillis()); }

    /** Public Download subfolder for exports. Deliberately the SAME folder every other Specter export uses
     *  (AppDataVault.EXPORT_DIR / Vault.EXPORT_DIR = /sdcard/Download/Specter) — one Specter folder on the
     *  user's device, not one per feature. The main app's import scan still also reads the old
     *  Download/Specter-exports/ path, so files exported before this change still import. */
    static final String EXPORT_DIR = "Specter";

    /** A human-readable filename: "Specter-<Manufacturer>-<Model>-<MMDDYY_HHMM>.json", sanitized to a safe
     *  filesystem token (spaces/parens/slashes -> '-'). Falls back to a timestamp if device fields are absent. */
    private String exportName(Map<String, String> p) {
        String dev = (get(p, "build_manufacturer") + "-" + get(p, "build_model")).trim();
        dev = dev.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        String stamp = new java.text.SimpleDateFormat("MMddyy_HHmm", java.util.Locale.US)
                .format(new java.util.Date());
        String base = dev.isEmpty() ? ("device-" + safeStamp()) : dev;
        return "Specter-" + base + "-" + stamp + ".json";
    }

    /** Write the envelope to public Download/Specter/<name>. API 29+ uses MediaStore (scoped
     *  storage — no permission needed to write the app's own Downloads entry); API 24–28 writes the file
     *  directly. Returns a user-facing location string. */
    private String writeExport(String name, String content) throws Exception {
        byte[] bytes = content.getBytes("UTF-8");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Downloads.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.Downloads.MIME_TYPE, "application/json");
            cv.put(android.provider.MediaStore.Downloads.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS + "/" + EXPORT_DIR);
            android.content.ContentResolver cr = getContentResolver();
            android.net.Uri uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new java.io.IOException("MediaStore insert returned null");
            try (java.io.OutputStream os = cr.openOutputStream(uri)) {
                if (os == null) throw new java.io.IOException("openOutputStream returned null");
                os.write(bytes);
            }
            return "Download/" + EXPORT_DIR + "/" + name;
        }
        // Legacy (API 24–28): direct write to public Downloads.
        File dir = new File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), EXPORT_DIR);
        if (!dir.exists() && !dir.mkdirs()) throw new java.io.IOException("could not create " + dir);
        File dest = new File(dir, name);
        try (FileOutputStream fos = new FileOutputStream(dest)) { fos.write(bytes); }
        return dest.getAbsolutePath();
    }
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
