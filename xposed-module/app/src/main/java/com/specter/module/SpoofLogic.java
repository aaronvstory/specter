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

    // ---- SENSORID: per-profile sensor calibration transform --------------------------------------
    // Every physical accel/gyro/mag has a per-device FACTORY CALIBRATION — tiny per-axis scale, bias and
    // cross-axis error unique to the chip. FingerprintJS reads the raw SensorEvent.values[] stream and the
    // statistics of that error are a stable ~57-bit fingerprint that SURVIVES factory reset (Cambridge
    // TIFS-2020). Relabeling the sensor LIST does NOT change it — so across every Specter profile on the
    // one physical Pixel 4 it stays IDENTICAL, a constant that can collapse all profiles to one device.
    // We apply a profile-seeded affine transform v' = scale*v + bias (per axis) to the value stream so each
    // profile presents a different, physically-plausible calibration. Coefficients are SMALL: scale within
    // ~±2% of 1.0, bias a small fraction of the sensor's noise floor, so gravity magnitude stays ~9.81 and
    // a gyro at rest stays ~0 — the app's motion logic is unaffected, only the micro-fingerprint moves.
    //
    // Returns {sx, sy, sz, bx, by, bz}. Pure + deterministic from (type, seed) for Java/Python byte-parity
    // and so the SAME profile always yields the SAME calibration (a fingerprint that jittered per-read
    // would itself be a tell). Only the motion sensors (accel/gyro/mag + their derived variants) carry the
    // calibration fingerprint; other types return identity (no transform).
    public static float[] sensorCalib(int type, String seed) {
        // Identity for non-motion sensors (light/pressure/proximity/etc. — no calibration fingerprint).
        if (!isMotionSensor(type)) return new float[]{1f, 1f, 1f, 0f, 0f, 0f};
        // Bias magnitude scaled to the sensor's unit so it stays within the real noise floor.
        float biasMax;
        switch (baseMotionType(type)) {
            case 1:  biasMax = 0.06f;   break;  // accelerometer m/s^2 (gravity 9.81 -> ~0.6% max)
            case 4:  biasMax = 0.012f;  break;  // gyroscope rad/s (small rest bias)
            case 2:  biasMax = 1.5f;    break;  // magnetometer uT
            default: biasMax = 0.05f;   break;
        }
        // Seed the draws by the BASE motion type, not the raw type, so every stream derived from the same
        // physical chip shares ONE calibration: gravity/linear-accel/accel-uncal all use the accelerometer's
        // coefficients (a fingerprinter that reads gravity vs accel would otherwise see two unrelated
        // calibrations — itself a tell — and the linear = accel - gravity identity would break).
        int base = baseMotionType(type);
        float sx = 1f + scaleDraw(seed, base, 0);
        float sy = 1f + scaleDraw(seed, base, 1);
        float sz = 1f + scaleDraw(seed, base, 2);
        // Linear-acceleration is gravity-subtracted (rests near 0), so a constant bias there is spurious —
        // scale it but don't bias it, preserving linear = accel - gravity under the shared scale.
        boolean linearAccel = (type == 10);
        float bx = linearAccel ? 0f : biasDraw(seed, base, 3) * biasMax;
        float by = linearAccel ? 0f : biasDraw(seed, base, 4) * biasMax;
        float bz = linearAccel ? 0f : biasDraw(seed, base, 5) * biasMax;
        return new float[]{sx, sy, sz, bx, by, bz};
    }

    /** Motion sensors whose raw axes carry the factory-calibration fingerprint — including the UNCALIBRATED
     *  variants (14/16/35), which expose the raw stream WITHOUT the runtime bias-compensation and would
     *  otherwise leak the untransformed fingerprint an app can read directly. */
    public static boolean isMotionSensor(int type) {
        switch (type) {
            case 1: case 2: case 4: case 9: case 10:  // accel, mag, gyro, gravity, linear-accel
            case 14: case 16: case 35:                // mag-uncal, gyro-uncal, accel-uncal
                return true;
            default: return false;
        }
    }

    /** Map every derived/uncalibrated stream to the base sensor whose physical calibration it shares, so
     *  they all get identical coefficients (gravity/linear-accel/accel-uncal <- accel; mag-uncal <- mag;
     *  gyro-uncal <- gyro). */
    static int baseMotionType(int type) {
        switch (type) {
            case 9: case 10: case 35: return 1;   // gravity, linear-accel, accel-uncalibrated <- accelerometer
            case 14: return 2;                    // magnetic-field-uncalibrated <- magnetometer
            case 16: return 4;                    // gyroscope-uncalibrated <- gyroscope
            default: return type;
        }
    }

    // FNV-1a 32-bit over (seed | type | channel), matches Generators.fnv1a style — MUST byte-match Python.
    static long calibHash(String seed, int type, int channel) {
        String s = seed + "|" + type + "|" + channel;
        long h = 2166136261L;
        for (int i = 0; i < s.length(); i++) { h = (h ^ (s.charAt(i) & 0xff)) * 16777619L; h &= 0xffffffffL; }
        return h;
    }

    /** Scale offset in [-0.02, +0.02] (±2%), 1e-4 quantized so Java/Python floats agree exactly. */
    static float scaleDraw(String seed, int type, int channel) {
        long h = calibHash(seed, type, channel);
        int q = (int) (h % 401L);            // 0..400
        return (q - 200) / 10000f;           // -0.0200 .. +0.0200 in 1e-4 steps
    }

    /** Signed unit bias in [-1, +1], 1e-3 quantized (caller multiplies by the per-sensor biasMax). */
    static float biasDraw(String seed, int type, int channel) {
        long h = calibHash(seed, type, channel);
        int q = (int) (h % 2001L);           // 0..2000
        return (q - 1000) / 1000f;           // -1.000 .. +1.000 in 1e-3 steps
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
