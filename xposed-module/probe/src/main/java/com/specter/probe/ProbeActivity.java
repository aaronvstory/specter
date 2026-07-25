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

    /** libc __system_property_get, in-process — the path an NDK-based fingerprinter uses. */
    private static native String nativeGetprop(String key);

    private static final String NATIVE_LIB_ERR;
    static {
        String err = null;
        try { System.loadLibrary("probe"); } catch (Throwable t) { err = "ERR:" + t; }
        NATIVE_LIB_ERR = err;
    }

    /** Props Specter spoofs via the Java SystemProperties hook — read each BOTH ways and compare. */
    private static final String[] DUAL_READ_PROPS = {
        "ro.board.platform", "ro.hardware.chipname", "gsm.version.baseband",
        "ro.product.model", "ro.product.brand", "ro.product.manufacturer",
        "ro.product.device", "ro.product.name", "ro.build.id", "ro.build.fingerprint",
        "ro.build.version.release", "ro.build.version.incremental", "ro.build.host",
        "ro.bootloader", "ro.boot.bootloader", "ro.hardware", "ro.product.board",
        "ro.serialno", "ro.boot.serialno",
    };

    private void nativeVsJavaProps(JSONObject o) {
        Method get = null;
        try {
            get = Class.forName("android.os.SystemProperties").getMethod("get", String.class);
        } catch (Throwable t) { put(o, "dual_read_java_err", "ERR:" + t); }
        for (String k : DUAL_READ_PROPS) {
            String base = "prop_" + k.replace('.', '_');
            if (get != null) {
                try { put(o, base + "_java", (String) get.invoke(null, k)); }
                catch (Throwable t) { put(o, base + "_java", "ERR:" + t); }
            }
            if (NATIVE_LIB_ERR != null) { put(o, base + "_native", NATIVE_LIB_ERR); continue; }
            try { put(o, base + "_native", nativeGetprop(k)); }
            catch (Throwable t) { put(o, base + "_native", "ERR:" + t); }
        }
    }

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

            // ---- Java-vs-native read of the SAME prop (native-read blind-spot test) ----
            // Xposed hooks android.os.SystemProperties.get (Java). libc __system_property_get is a
            // different code path in the SAME process — an NDK fingerprinter uses it. If the two
            // disagree, native reads see the real device and our Java-only hooks have a blind spot.
            nativeVsJavaProps(o);

            // Settings.Secure android_id
            try {
                put(o, "android_id", Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID));
            } catch (Throwable t) { put(o, "android_id", "ERR:" + t); }

            // Settings.Secure bluetooth_address — a second BT-MAC path a fingerprinter can read
            try {
                put(o, "bt_addr_settings", Settings.Secure.getString(getContentResolver(), "bluetooth_address"));
            } catch (Throwable t) { put(o, "bt_addr_settings", "ERR:" + t); }

            // BluetoothAdapter.getAddress() — the adapter path to the BT MAC (vs the Settings path above)
            try {
                android.bluetooth.BluetoothAdapter ba = android.bluetooth.BluetoothAdapter.getDefaultAdapter();
                put(o, "bt_addr_adapter", ba == null ? "null-adapter" : ba.getAddress());
            } catch (Throwable t) { put(o, "bt_addr_adapter", "ERR:" + t); }

            // GSF id via the Gservices content provider (a FingerprintJS deviceId source)
            try {
                android.database.Cursor cur = getContentResolver().query(
                        android.net.Uri.parse("content://com.google.android.gsf.gservices"),
                        null, null, new String[]{"android_id"}, null);
                if (cur != null) {
                    if (cur.moveToFirst() && cur.getColumnCount() >= 2) {
                        long id = Long.parseLong(cur.getString(1));
                        put(o, "gsf_id", String.valueOf(id));
                    }
                    cur.close();
                }
            } catch (Throwable t) { put(o, "gsf_id", "ERR:" + t); }

            // Settings.Global dev-mode tells — should read 0 (hide the developer/rooted-device signal)
            try {
                put(o, "adb_enabled", String.valueOf(Settings.Global.getInt(getContentResolver(), "adb_enabled", -1)));
                put(o, "dev_settings", String.valueOf(Settings.Global.getInt(getContentResolver(), "development_settings_enabled", -1)));
            } catch (Throwable t) { put(o, "adb_enabled", "ERR:" + t); }

            // RAM (ActivityManager.MemoryInfo.totalMem) — a FingerprintJS hardware signal
            try {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                am.getMemoryInfo(mi);
                put(o, "total_ram", String.valueOf(mi.totalMem));
            } catch (Throwable t) { put(o, "total_ram", "ERR:" + t); }

            // StatFs total storage — was LEAKING (generated but never hooked). Read total + the
            // blockCount*blockSize path so verify can confirm they're coherent (multiply to the same total).
            try {
                android.os.StatFs sf = new android.os.StatFs(android.os.Environment.getDataDirectory().getPath());
                put(o, "storage_total_bytes", String.valueOf(sf.getTotalBytes()));
                put(o, "storage_blocks_x_size", String.valueOf(sf.getBlockCountLong() * sf.getBlockSizeLong()));
                put(o, "storage_available_bytes", String.valueOf(sf.getAvailableBytes()));
            } catch (Throwable t) { put(o, "storage_total_bytes", "ERR:" + t); }

            // MediaDrm Widevine deviceUniqueId — a FingerprintJS deviceId source.
            // Also read securityLevel: it is the COHERENCE cross-check. Specter value-spoofs
            // deviceUniqueId but (as of this probe) leaves securityLevel real — a *changing* id at a
            // real L1 is itself incoherent (a genuine L1 device has a fixed hardware id). byedentity
            // avoids this by dropping to L3 via a liboemcrypto bind-mount. If media_drm_id looks
            // spoofed but media_drm_security_level still reads L1, that mismatch is the leak.
            // See docs/BYEDENTITY-ANALYSIS.md "Widevine coherence hole".
            try {
                java.util.UUID widevine = new java.util.UUID(-0x121074568629b532L, -0x5c37d8232ae2de13L);
                android.media.MediaDrm md = new android.media.MediaDrm(widevine);
                byte[] id = md.getPropertyByteArray("deviceUniqueId");
                StringBuilder hex = new StringBuilder();
                for (byte x : id) hex.append(String.format("%02x", x));
                put(o, "media_drm_id", hex.toString());
                try { put(o, "media_drm_security_level", md.getPropertyString("securityLevel")); }
                catch (Throwable t) { put(o, "media_drm_security_level", "ERR:" + t); }
            } catch (Throwable t) {
                put(o, "media_drm_id", "ERR:" + t);
                put(o, "media_drm_security_level", "ERR:" + t);
            }

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
