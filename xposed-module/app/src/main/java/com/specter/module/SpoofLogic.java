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
}
