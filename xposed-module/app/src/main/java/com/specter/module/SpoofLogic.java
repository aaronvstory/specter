package com.specter.module;

/**
 * Pure, Android-free decision logic for the hooks — extracted so it can be unit-tested on a plain
 * JVM (no device, no Robolectric). HookEntry calls into these; the Android glue stays in HookEntry.
 */
public final class SpoofLogic {
    private SpoofLogic() {}

    /**
     * A gservices cursor row is (name, value). The value is the LAST column. This returns true when
     * the caller is reading the value column of the "android_id" row — i.e. the GSF id to spoof.
     */
    public static boolean isAndroidIdValueColumn(String rowName, int columnIndex, int columnCount) {
        if (columnCount < 1) return false;
        return "android_id".equals(rowName) && columnIndex == (columnCount - 1);
    }

    /** getImei(slot)/getDeviceId(slot): slot 0 -> imei1, slot 1 -> imei2, anything else -> imei1. */
    public static String imeiForSlot(int slot, String imei1, String imei2) {
        return slot == 1 ? imei2 : imei1;
    }

    /** Parse a decimal GSF id to long; returns fallback on any malformed value (never throws). */
    public static long gsfToLong(String gsf, long fallback) {
        try {
            return Long.parseLong(gsf);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * True if any arg equals {@code key}. The Settings.Secure/System getString family has several
     * overloads (getString(cr,name), getStringForUser(cr,name,userId), …) where the setting name
     * can sit at arg index 1 or elsewhere — so a robust hook scans ALL args rather than assuming a
     * fixed position. A single fixed-overload hook was why "android_id" leaked in DevInfo while
     * GSF/serial spoofed. Null-safe.
     */
    public static boolean argsContainKey(Object[] args, String key) {
        if (args == null) return false;
        for (Object a : args) if (key.equals(String.valueOf(a))) return true;
        return false;
    }

    /**
     * The default HTTP User-Agent (System.getProperty("http.agent")) — what HttpURLConnection/OkHttp
     * send when an app doesn't set one. PROVEN 2026-07-26 to be FingerprintJS Pro's dominant
     * visitorId anchor: the framework builds this string at zygote init from the REAL
     * Build.MODEL/VERSION.RELEASE/ID, before any in-app field hook runs, so two completely different
     * profiles both reported "Dalvik/2.1.0 (Linux; U; Android 11; Pixel 4 Build/RQ3A.211001.001)"
     * and collapsed to the same visitorId. Rebuilt here from the profile's own build fields, so it
     * is coherent by construction and consumes no RNG (byte-parity safe).
     * Shape matches libcore/luni/src/main/java/java/net/HttpURLConnection default agent.
     */
    public static String dalvikUserAgent(String release, String model, String buildId) {
        return "Dalvik/2.1.0 (Linux; U; Android " + release + "; " + model + " Build/" + buildId + ")";
    }

    /**
     * The WebView default User-Agent (WebSettings.getDefaultUserAgent) — a DIFFERENT shape from the
     * Dalvik one, per AOSP frameworks/base WebSettings. The Chrome version segment stays REAL (it
     * describes the installed WebView, not the device; faking it would be incoherent with what the
     * page-side JS can observe) — only the device segment is swapped.
     */
    public static String webViewUserAgent(String release, String model, String buildId, String chromeVersion) {
        return "Mozilla/5.0 (Linux; Android " + release + "; " + model + " Build/" + buildId
                + "; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/"
                + chromeVersion + " Mobile Safari/537.36";
    }

    // Real installs land the app's APKs a while after the factory reset (you reset, then install apps),
    // and the base/split APKs are written within a few seconds of each other. This must land AFTER the
    // reset epoch and stay stable per (identity, path).
    static final long APK_INSTALL_OFFSET_SEC = 37 * 24 * 3600L;   // ~5 weeks after the reset — a used phone

    /**
     * True when {@code path} is one of the target app's OWN installed APKs under /data/app.
     * PROVEN 2026-07-25 to be FingerprintJS Pro's `FileTimestamps` raw signal / visitorId anchor: the
     * SDK reads {@code File.lastModified()} on base.apk + split_config.*.apk, whose mtimes are the
     * INSTALL time — set once, identical across every identity rotation, unique to this install. Match
     * only this app's own package dir so we never rewrite mtimes of other files the app legitimately uses.
     */
    public static boolean isOwnApk(String path, String pkg) {
        if (path == null || pkg == null) return false;
        return path.startsWith("/data/app/") && path.endsWith(".apk") && path.contains("/" + pkg + "-");
    }

    /**
     * Spoofed install mtime (SECONDS) for an own-APK path: a stable per-identity value derived from the
     * factory-reset epoch, offset so the install plausibly follows the reset. Base and split APKs get a
     * small deterministic spread (0..12s from the path hash) so they are not byte-identical, as on a real
     * multi-APK install. Pure function of (resetEpoch, path) — no wall clock, no RNG — so it is stable
     * within a boot and identical wherever it is recomputed.
     */
    public static long apkInstallSeconds(long resetEpoch, String path) {
        long base = resetEpoch + APK_INSTALL_OFFSET_SEC;
        int spread = path == null ? 0 : (Math.abs(path.hashCode()) % 13);   // 0..12s, stable per path
        return base + spread;
    }

    // Package-name substrings that betray root / a hooking framework / an anti-fingerprint tool. The
    // installed-app list is a raw signal FPJS collects (PackageManager enumeration); any of these in it
    // both raises entropy and is a direct "this device is instrumented" tell. Hidden from enumeration.
    // Substrings distinctive enough that a real consumer app is very unlikely to contain them. Kept
    // narrow on purpose: broad tokens like "momo"/"xposed"/"riru" alone would false-positive on
    // legitimate apps (e.g. a dating app "com.momo.*"), so those are matched only in their real
    // root/hook package forms below, never as a bare substring.
    static final String[] SENSITIVE_PKG_MARKERS = {
        "com.specter",                 // this module + its probe
        "magisk", "com.topjohnwu",     // Magisk (+ manager)
        "lsposed", "edxposed", "zygisk", "shamiko",
        "de.robv.android.xposed", "org.lsposed", "io.github.lsposed",   // Xposed frameworks (specific)
        "riru.core", "riru.momo", "com.rifsxd", "eu.faircode.xlua",
        "auag0.hidemocklocation",      // the mock-location hider on this device (specific)
        "kingroot", "kingouser", "supersu", ".superuser", "com.koushikdutta.superuser",
        "com.noshufou.android.su", "me.weishu.kernelsu", "kernelsu", "com.rifsxd.ksunext",
        "io.github.vvb2060", "hidemyapplist", "com.tsng.hidemyapplist",
        "riru.momo", "com.zhufucdev", "moe.shizuku",   // detection-probe / instrumentation apps (specific)
    };

    // Per-sensor-type {maxRange, resolution, power} — the high-entropy fields FingerprintJS hashes
    // alongside a sensor's name/vendor. Leaving them REAL leaks the exact Pixel-4 sensor chip even after
    // the name/vendor are relabeled. These are plausible real values for each Android sensor type
    // (TYPE_ACCELEROMETER=1, MAGNETIC=2, GYROSCOPE=4, LIGHT=5, PRESSURE=6, PROXIMITY=8, ...). Pure +
    // testable. maxRange/resolution are in the sensor's SI unit; power in mA.
    public static float[] sensorRmp(int type, String name) {
        switch (type) {
            case 1:  return new float[]{78.4532f, 0.0023928226f, 0.17f};   // accelerometer (m/s^2)
            case 2:  return new float[]{4912.0f, 0.15f, 5.0f};             // magnetometer (uT)
            case 4:  return new float[]{34.906586f, 0.0010652645f, 6.1f};  // gyroscope (rad/s)
            case 5:  return new float[]{60000.0f, 1.0f, 0.75f};            // light (lux)
            case 6:  return new float[]{1100.0f, 0.005f, 0.0f};            // pressure (hPa)
            case 8:  return new float[]{5.0f, 1.0f, 0.75f};                // proximity (cm)
            case 9:  return new float[]{78.4532f, 0.0023928226f, 0.17f};   // gravity
            case 10: return new float[]{78.4532f, 0.0023928226f, 0.17f};   // linear accel
            case 11: return new float[]{1.0f, 5.9604645E-8f, 6.27f};       // rotation vector
            case 13: return new float[]{85.0f, 0.01f, 0.0f};               // ambient temperature
            case 12: return new float[]{100.0f, 1.0f, 0.5f};               // relative humidity
            default: return new float[]{100.0f, 1.0f, 0.5f};               // generic plausible
        }
    }

    /** True if this package name should be HIDDEN from the target's installed-app enumeration. */
    public static boolean isSensitivePackage(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase();
        // exact-equals fast path for the module itself + a couple of common exact ids
        if (p.equals("com.specter") || p.equals("com.specter.probe")) return true;
        for (String m : SENSITIVE_PKG_MARKERS) if (p.contains(m)) return true;
        return false;
    }
}
