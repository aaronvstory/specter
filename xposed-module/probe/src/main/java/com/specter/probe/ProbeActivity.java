package com.specter.probe;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Method;

/**
 * Specter verification probe. Reads every device identifier the same way a real app would (the exact
 * Android APIs Specter hooks) and writes the actual returned values to a world-readable JSON file.
 *
 * A verifier reads that file over adb and diffs it against the applied profile — deterministic,
 * covers every field, no UI scraping. Also enables GeerGit-vs-Specter side-by-side (run the probe
 * under each module's scope and compare the two JSON dumps).
 *
 * Output: /data/local/tmp/specter/probe_result.json  (falls back to app-private dir if tmp is denied).
 */
public class ProbeActivity extends Activity {

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        JSONObject o = new JSONObject();
        try {
            // ---- Build.* (device_spoof) ----
            put(o, "build_manufacturer", Build.MANUFACTURER);
            put(o, "build_brand", Build.BRAND);
            put(o, "build_device", Build.DEVICE);
            put(o, "build_product", Build.PRODUCT);
            put(o, "build_model", Build.MODEL);
            put(o, "build_id", Build.ID);
            put(o, "build_fingerprint", Build.FINGERPRINT);
            put(o, "serial_field", Build.SERIAL);
            put(o, "build_bootloader", Build.BOOTLOADER);
            put(o, "build_hardware", Build.HARDWARE);
            put(o, "build_board", Build.BOARD);
            put(o, "build_host", Build.HOST);
            put(o, "build_display", Build.DISPLAY);
            put(o, "build_release", Build.VERSION.RELEASE);
            put(o, "build_incremental", Build.VERSION.INCREMENTAL);
            put(o, "build_security_patch", Build.VERSION.SECURITY_PATCH);
            put(o, "os_version", System.getProperty("os.version"));

            // getRadioVersion() (baseband) — static, API-level available on all
            try { put(o, "build_radio", Build.getRadioVersion()); } catch (Throwable t) { put(o, "build_radio", "ERR:" + t); }

            // getSerial() (API 26+)
            try {
                Method m = Build.class.getMethod("getSerial");
                put(o, "serial_getSerial", (String) m.invoke(null));
            } catch (Throwable t) { put(o, "serial_getSerial", "ERR:" + t); }

            // SystemProperties.get("gsm.version.baseband") — the raw prop DevInfo-class readers use
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Method get = sp.getMethod("get", String.class);
                put(o, "prop_gsm_baseband", (String) get.invoke(null, "gsm.version.baseband"));
            } catch (Throwable t) { put(o, "prop_gsm_baseband", "ERR:" + t); }

            // ro.board.platform (SoC codename) — a FingerprintJS CPU-signal source
            try {
                Class<?> sp = Class.forName("android.os.SystemProperties");
                Method get = sp.getMethod("get", String.class);
                put(o, "soc_platform", (String) get.invoke(null, "ro.board.platform"));
            } catch (Throwable t) { put(o, "soc_platform", "ERR:" + t); }

            // Settings.Secure android_id
            try {
                put(o, "android_id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
            } catch (Throwable t) { put(o, "android_id", "ERR:" + t); }

            // Settings.Secure bluetooth_address — a second BT-MAC path a fingerprinter can read
            try {
                put(o, "bt_addr_settings", Settings.Secure.getString(getContentResolver(), "bluetooth_address"));
            } catch (Throwable t) { put(o, "bt_addr_settings", "ERR:" + t); }

            // RAM (ActivityManager.MemoryInfo.totalMem) — a FingerprintJS hardware signal
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                put(o, "total_ram", String.valueOf(mi.totalMem));
            } catch (Throwable t) { put(o, "total_ram", "ERR:" + t); }

            // Telephony (needs READ_PHONE_STATE; may throw on newer APIs w/o it — record the attempt)
            probeTelephony(o);
        } catch (Throwable t) {
            try { o.put("_error", String.valueOf(t)); } catch (Throwable ignored) {}
        }

        String json = o.toString();
        writeResult(json);

        TextView tv = new TextView(this);
        tv.setPadding(24, 48, 24, 24);
        tv.setTextIsSelectable(true);
        tv.setText("Specter probe wrote " + o.length() + " values.\n\n" + prettyish(json));
        ScrollView sv = new ScrollView(this);
        sv.addView(tv);
        setContentView(sv);
    }

    private void probeTelephony(JSONObject o) {
        try {
            android.telephony.TelephonyManager tm =
                    (android.telephony.TelephonyManager) getSystemService(TELEPHONY_SERVICE);
            if (tm == null) { put(o, "telephony", "null-service"); return; }
            try { put(o, "network_operator", tm.getNetworkOperator()); } catch (Throwable t) { put(o, "network_operator", "ERR"); }
            try { put(o, "network_operator_name", tm.getNetworkOperatorName()); } catch (Throwable t) { put(o, "network_operator_name", "ERR"); }
            try { put(o, "sim_operator", tm.getSimOperator()); } catch (Throwable t) { put(o, "sim_operator", "ERR"); }
            try { put(o, "sim_operator_name", tm.getSimOperatorName()); } catch (Throwable t) { put(o, "sim_operator_name", "ERR"); }
            try { put(o, "sim_serial", tm.getSimSerialNumber()); } catch (Throwable t) { put(o, "sim_serial", "ERR:no-perm"); }
            try { put(o, "subscriber_id", tm.getSubscriberId()); } catch (Throwable t) { put(o, "subscriber_id", "ERR:no-perm"); }
            try {
                Method gi = tm.getClass().getMethod("getImei");
                put(o, "imei", (String) gi.invoke(tm));
            } catch (Throwable t) { put(o, "imei", "ERR:no-perm"); }
        } catch (Throwable t) { put(o, "telephony", "ERR:" + t); }
    }

    private void put(JSONObject o, String k, String v) {
        try { o.put(k, v == null ? "null" : v); } catch (Throwable ignored) {}
    }

    private void writeResult(String json) {
        // Prefer the shared tmp dir (adb-readable without root); fall back to app-private.
        String[] paths = {"/data/local/tmp/specter/probe_result.json", getFilesDir() + "/probe_result.json"};
        for (String p : paths) {
            try {
                File f = new File(p);
                File dir = f.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                FileWriter w = new FileWriter(f);
                w.write(json);
                w.close();
                f.setReadable(true, false);  // world-readable so adb (shell user) can read it
                return;
            } catch (Throwable ignored) {}
        }
    }

    private String prettyish(String json) {
        return json.replace(",\"", ",\n\"");
    }
}
