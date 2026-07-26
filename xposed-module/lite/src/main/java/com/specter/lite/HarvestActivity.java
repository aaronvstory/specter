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
 * to the app's external files dir. Copy that file into another device's Download and import it in Specter
 * to clone this device as closely as the root layer allows.
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
        harvest.setTextColor(0xFF231A05);
        harvest.setBackgroundColor(GOLD);
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
    }

    private void doHarvest() {
        Map<String, String> p = collect();
        String env = buildEnvelope(p);
        File dest = new File(getExternalFilesDir(null), "specter-harvest-" + safeStamp() + ".json");
        try (FileOutputStream fos = new FileOutputStream(dest)) {
            fos.write(env.getBytes("UTF-8"));
            lastPath = dest.getAbsolutePath();
        } catch (Throwable t) {
            Toast.makeText(this, "Export failed: " + t, Toast.LENGTH_LONG).show();
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Exported ").append(p.size()).append(" fields to:\n").append(lastPath).append("\n\n");
        sb.append("Copy this file to the target device's Download folder, then open Specter -> Saved -> "
                + "Import from Download.\n\n--- harvested ---\n");
        for (Map.Entry<String, String> e : p.entrySet())
            sb.append(e.getKey()).append(" = ").append(e.getValue()).append('\n');
        out.setText(sb.toString());
        Toast.makeText(this, "Harvested to " + lastPath, Toast.LENGTH_LONG).show();
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
        return p;
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
    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
