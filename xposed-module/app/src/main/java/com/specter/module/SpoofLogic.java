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
        // Single-SIM profile (no imei2): slot 1 falls back to imei1 rather than returning null (which would
        // leave the real value / a null leaking on a dual-SIM read).
        if (slot == 1 && imei2 != null && !imei2.isEmpty()) return imei2;
        return imei1;
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
        // GPS-SPOOFERS — a fraud/KYC SDK that CAN enumerate (declares QUERY_ALL_PACKAGES) treats an
        // installed fake-GPS app as a strong risk signal, even when the mock flag itself is hidden. Hide them.
        "lockito", "dvilleneuve.lockito", "fakegps", "fake.gps", "faketraveler", "mock.location",
        "gpsjoystick", "theappninjas.gpsjoystick", "fakegpsjoystick", "location.changer", "hola.fakelocation",
        // PROXY / VPN / tunnel apps — likewise a tell if the SDK can see them. (Legit mainstream VPNs like
        // Mullvad are deliberately NOT hidden — their presence is common/benign; only the tunneling/proxy
        // helpers used for IP rotation are.)
        "superproxy", "scheler.superproxy", "tun2socks", "tun2tap", "socksdroid", "com.github.shadowsocks",
        "v2ray", "clash", "sagernet", "httpcanary", "tech.httptoolkit",   // MITM/capture tools are a tell too
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

    // ---- profile JSON parsing without org.json ----
    // Another LSPosed module scoped to the same app (e.g. GeerGit) hooks JSONObject.getString AND
    // HashMap/ArrayMap.put and rewrites the "android_id" value to its own constant. That poisoned Specter's
    // OWN profile load, so Specter applied a foreign, stable android_id and the device stayed recognized
    // across clear+randomize (the number-survival leak). These scan the raw JSON text with plain char ops —
    // no org.json, no Map — so no other module's hook can touch what Specter reads. Kept here (pure) so the
    // parser is JVM-tested. The profile is machine-generated + flat (every value a JSON string).

    // Shadow key under which parseFlatJson mirrors the android_id value. A co-scoped module (GeerGit) hooks
    // Map.put for the EXACT key "android_id" to rewrite it; it does not match this key, so the value stored
    // here survives untouched. The hooks read TRUE_ANDROID_ID_KEY, never "android_id". (An underscore-prefixed
    // key that is never a real profile field, so it can't collide.)
    public static final String TRUE_ANDROID_ID_KEY = "__specter_true_android_id";

    /** Parse a FLAT {"k":"v",...} JSON string into out. Ignores non-string values. Never throws. Also mirrors
     *  the android_id value into out[TRUE_ANDROID_ID_KEY] (a key GeerGit's put-hook doesn't match) so the hooks
     *  can read an un-poisoned copy. Captured DURING the scan, so it's whitespace-robust and format-independent. */
    public static void parseFlatJson(String s, java.util.Map<String, String> out) {
        if (s == null) return;
        int i = 0, len = s.length();
        while (i < len) {
            while (i < len && s.charAt(i) != '"') i++;
            if (i >= len) break;
            StringBuilder key = new StringBuilder();
            i = readJsonString(s, i + 1, key);
            if (i < 0) break;
            while (i < len && s.charAt(i) != ':') i++;
            if (i >= len) break;
            i++;
            while (i < len && Character.isWhitespace(s.charAt(i))) i++;
            if (i >= len) break;
            if (s.charAt(i) == '"') {
                StringBuilder val = new StringBuilder();
                i = readJsonString(s, i + 1, val);
                if (i < 0) break;
                String k = key.toString(), v = val.toString();
                out.put(k, v);
                // Mirror android_id under the shadow key IN THE SAME scan (whitespace already skipped above),
                // so the true value is captured regardless of JSON spacing and without a second raw match.
                if ("android_id".equals(k)) out.put(TRUE_ANDROID_ID_KEY, v);
            } else {
                while (i < len && s.charAt(i) != ',' && s.charAt(i) != '}') i++;
            }
        }
    }


    // ---- Zygisk self-installer pure logic (Android-free so it's JVM-tested; ZygiskInstaller is the glue) ----

    /** The {@code version=} value from a module.prop text (trimmed), or null. */
    public static String modulePropVersion(String moduleProp) {
        if (moduleProp == null) return null;
        for (String line : moduleProp.split("\n")) {
            String t = line.trim();
            if (t.startsWith("version=")) return t.substring("version=".length()).trim();
        }
        return null;
    }

    /**
     * The {@code su} program that installs a Magisk module ATOMICALLY: build the layout under {@code
     * moduleDir + ".stage"} from the app-extracted files, then rename into place (back up + roll back on
     * failure, so a failed rename never leaves a half-written module). Only our own dir + the app-private
     * extracted paths are interpolated (no external input) → no injection surface.
     */
    public static String zygiskInstallScript(String moduleDir, String soPath, String propPath, String sePath) {
        String stage = moduleDir + ".stage";
        StringBuilder s = new StringBuilder();
        s.append("set -e\n");
        s.append("rm -rf ").append(stage).append("\n");
        s.append("mkdir -p ").append(stage).append("/zygisk\n");
        s.append("cp \"").append(soPath).append("\" ").append(stage).append("/zygisk/arm64-v8a.so\n");
        s.append("cp \"").append(propPath).append("\" ").append(stage).append("/module.prop\n");
        s.append("cp \"").append(sePath).append("\" ").append(stage).append("/sepolicy.rule\n");
        // Perms + ownership MUST match the proven reference (dev-scripts/spz_install.sh) or Magisk's Zygisk
        // loader may refuse the module at boot: dirs 0755, files 0644, everything owned by root (0:0).
        s.append("chown -R 0:0 ").append(stage).append("\n");
        s.append("chmod 0755 ").append(stage).append(" ").append(stage).append("/zygisk\n");
        s.append("chmod 0644 ").append(stage).append("/module.prop ").append(stage).append("/sepolicy.rule ")
         .append(stage).append("/zygisk/arm64-v8a.so\n");
        s.append("BAK=").append(moduleDir).append(".bak\n");
        s.append("rm -rf $BAK\n");
        s.append("[ -d ").append(moduleDir).append(" ] && mv ").append(moduleDir).append(" $BAK || true\n");
        s.append("if ! mv ").append(stage).append(" ").append(moduleDir).append("; then ")
         .append("[ -d $BAK ] && mv $BAK ").append(moduleDir).append("; echo mv_failed >&2; exit 4; fi\n");
        s.append("rm -rf $BAK\n");
        s.append("echo specter_zygisk_installed\n");
        return s.toString();
    }

    /** Read a JSON string body from `start` (char after the opening quote) into sb; return index past the
     *  closing quote, or -1 if unterminated. Handles standard escapes incl. 4-hex-digit unicode. */
    static int readJsonString(String s, int start, StringBuilder sb) {
        int i = start, len = s.length();
        while (i < len) {
            char c = s.charAt(i++);
            if (c == '"') return i;
            if (c == '\\' && i < len) {
                char e = s.charAt(i++);
                switch (e) {
                    case '"': sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case '/': sb.append('/'); break;
                    case 'n': sb.append('\n'); break;
                    case 'r': sb.append('\r'); break;
                    case 't': sb.append('\t'); break;
                    case 'b': sb.append('\b'); break;
                    case 'f': sb.append('\f'); break;
                    case 'u':
                        if (i + 4 <= len) {
                            try { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); } catch (Throwable ignored) {}
                            i += 4;
                        }
                        break;
                    default: sb.append(e);
                }
            } else {
                sb.append(c);
            }
        }
        return -1;
    }

    /** True if {@code name} is a VPN/tunnel network-interface name a detector looks for
     *  (tun*, ppp*, wg*, pptp*, ipsec*, l2tp*). An app enumerating NetworkInterface.getNetworkInterfaces()
     *  and finding one of these concludes a VPN is up — so the VPN-hiding hook drops interfaces whose name
     *  matches this. Pure + case-insensitive so it's unit-testable off the hook path. */
    public static boolean isTunnelIface(String name) {
        if (name == null) return false;
        String n = name.toLowerCase(java.util.Locale.US);
        return n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("wg")
                || n.startsWith("pptp") || n.startsWith("ipsec") || n.startsWith("l2tp");
    }

    /** WebRTC ICE-candidate filter, injected into a scoped app's WebViews. WebRTC stays ENABLED (blocking it is
     *  itself a fraud flag) — this only drops candidates that would leak the REAL device IP: mDNS ".local"
     *  names, RFC1918 private ranges (10/8, 172.16/12, 192.168/16), and link-local (169.254/16, fe80::). The
     *  proxy's public reflexive candidate passes through, so WebRTC reports the proxy IP, not the real one.
     *
     *  <p>Wraps RTCPeerConnection so the SDP set via setLocalDescription AND the trickled ICE candidates
     *  (addEventListener + the onicecandidate property) are scrubbed. Idempotent (guards a global flag). */
    public static String webRtcIceFilterJs() {
        return
        "(function(){if(window.__specter_rtc)return;window.__specter_rtc=1;try{"
        + "var OP=window.RTCPeerConnection||window.webkitRTCPeerConnection;if(!OP)return;"
        // A candidate line leaks the real IP if it names a .local mDNS host or a private/link-local IP.
        + "function leak(c){if(!c)return false;c=(''+c).toLowerCase();"
        + "if(c.indexOf('.local')>=0)return true;"
        + "if(c.indexOf('fe80:')>=0||c.indexOf('fc00:')>=0||c.indexOf('fd')>=0&&c.match(/ fd[0-9a-f]/))return true;"
        + "var m=c.match(/(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}\\.\\d{1,3}/);if(!m)return false;"
        + "var a=+m[1],b=+m[2];"
        + "return a===10||(a===192&&b===168)||a===169&&b===254||(a===172&&b>=16&&b<=31);}"
        // Remove leaking candidate lines from an SDP blob (setLocalDescription path + createOffer/Answer).
        + "function scrub(sdp){if(!sdp)return sdp;return sdp.split('\\n').filter(function(l){"
        + "return l.indexOf('a=candidate:')<0||!leak(l);}).join('\\n');}"
        + "function F(cfg,con){var pc=new OP(cfg,con);"
        // RTCSessionDescription.sdp is READ-ONLY (assigning to it throws) — pass a fresh plain init dict with
        // the scrubbed sdp instead of mutating d. setLocalDescription accepts {type,sdp}. Belt-and-braces on
        // top of the per-candidate filter below (host candidates are usually trickled, not embedded in the SDP).
        + "var _sld=pc.setLocalDescription.bind(pc);"
        + "pc.setLocalDescription=function(d){try{if(d&&d.sdp){d={type:d.type,sdp:scrub(d.sdp)};}}catch(e){}return _sld(d);};"
        // Filter trickled candidates on BOTH delivery paths: addEventListener and the onicecandidate property.
        + "var _add=pc.addEventListener.bind(pc);"
        + "pc.addEventListener=function(t,fn,o){if(t==='icecandidate'&&typeof fn==='function'){"
        + "var w=function(e){if(e&&e.candidate&&leak(e.candidate.candidate))return;return fn.call(this,e);};"
        + "return _add(t,w,o);}return _add(t,fn,o);};"
        // onicecandidate must keep real DOM 'on*' semantics: at-most-one handler, last assignment wins, a read
        // returns what was set, AND the handler keeps its ORIGINAL dispatch position (native fires the on* slot
        // in the order it was FIRST set, not re-appended on reassignment). Register ONE stable wrapper on the
        // first non-null assignment that always calls the current _oh; never remove/re-add on reassignment.
        + "var _oh=null,_reg=false;"
        + "Object.defineProperty(pc,'onicecandidate',{configurable:true,"
        + "get:function(){return _oh;},"
        + "set:function(fn){_oh=(typeof fn==='function')?fn:null;"
        + "if(_oh&&!_reg){_reg=true;_add('icecandidate',function(e){if(!_oh)return;"
        + "if(e&&e.candidate&&leak(e.candidate.candidate))return;return _oh.call(pc,e);});}}});"
        + "return pc;}"
        + "F.prototype=OP.prototype;window.RTCPeerConnection=F;window.webkitRTCPeerConnection=F;"
        + "}catch(e){}})();";
    }
}
