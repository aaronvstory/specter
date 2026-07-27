package com.specter.module;

/**
 * Plain-JVM tests for the pure hook logic (no Android, no Robolectric). Run via run-jvm-tests.sh.
 * Uses tiny hand-rolled asserts so it needs zero test framework on the classpath.
 */
public class SpoofLogicTest {
    static int passed = 0, failed = 0;

    static void check(boolean cond, String name) {
        if (cond) { passed++; }
        else { failed++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        // isAndroidIdValueColumn: value is the LAST column of an (name,value) row
        check(SpoofLogic.isAndroidIdValueColumn("android_id", 1, 2), "android_id value col=1 of 2");
        check(!SpoofLogic.isAndroidIdValueColumn("android_id", 0, 2), "name col is NOT the value");
        check(!SpoofLogic.isAndroidIdValueColumn("other_key", 1, 2), "non-android_id row ignored");
        check(SpoofLogic.isAndroidIdValueColumn("android_id", 2, 3), "value col=2 of 3");
        check(!SpoofLogic.isAndroidIdValueColumn("android_id", 0, 0), "empty cursor guarded");
        check(!SpoofLogic.isAndroidIdValueColumn(null, 1, 2), "null row name safe");

        // imeiForSlot: slot 0 -> imei1, slot 1 -> imei2, else imei1
        check(SpoofLogic.imeiForSlot(0, "AAA", "BBB").equals("AAA"), "slot 0 -> imei1");
        check(SpoofLogic.imeiForSlot(1, "AAA", "BBB").equals("BBB"), "slot 1 -> imei2");
        check(SpoofLogic.imeiForSlot(5, "AAA", "BBB").equals("AAA"), "slot 5 -> imei1 (default)");

        // gsfToLong: parse valid, fallback on garbage, never throw
        check(SpoofLogic.gsfToLong("12345", -1L) == 12345L, "parse valid gsf");
        check(SpoofLogic.gsfToLong("9223372036854775807", -1L) == Long.MAX_VALUE, "parse Long.MAX");
        check(SpoofLogic.gsfToLong("not-a-number", 77L) == 77L, "garbage -> fallback");
        check(SpoofLogic.gsfToLong("", 88L) == 88L, "empty -> fallback");

        // argsContainKey: the android_id key found at any position across getString overloads.
        // Regression: single-fixed-overload hook leaked real android_id in DevInfo (GSF/serial
        // spoofed but android_id did not); scanning all args fixes it.
        check(SpoofLogic.argsContainKey(new Object[]{null, "android_id"}, "android_id"), "key at index 1 (getString(cr,name))");
        check(SpoofLogic.argsContainKey(new Object[]{null, "android_id", 0}, "android_id"), "key at index 1 (getStringForUser)");
        check(!SpoofLogic.argsContainKey(new Object[]{null, "bluetooth_name"}, "android_id"), "other setting not matched");
        check(!SpoofLogic.argsContainKey(new Object[]{}, "android_id"), "empty args safe");
        check(!SpoofLogic.argsContainKey(null, "android_id"), "null args safe");
        check(SpoofLogic.argsContainKey(new Object[]{"android_id"}, "android_id"), "key at index 0");

        // User-Agent rebuild. The shape must match the REAL device string byte-for-byte, else the
        // UA itself becomes a novel fingerprint. Ground truth captured from the test Pixel 4
        // (Android 11 / RQ3A.211001.001) via the FPJS Server API on 2026-07-26.
        check(SpoofLogic.dalvikUserAgent("11", "Pixel 4", "RQ3A.211001.001")
                .equals("Dalvik/2.1.0 (Linux; U; Android 11; Pixel 4 Build/RQ3A.211001.001)"),
                "dalvik UA matches the real device string exactly");
        check(SpoofLogic.dalvikUserAgent("10", "moto g(6)", "PPSS29.55-37-8")
                .equals("Dalvik/2.1.0 (Linux; U; Android 10; moto g(6) Build/PPSS29.55-37-8)"),
                "dalvik UA carries the SPOOFED model, not the real one");
        check(SpoofLogic.webViewUserAgent("10", "moto g(6)", "PPSS29.55-37-8", "120.0.6099.43")
                .equals("Mozilla/5.0 (Linux; Android 10; moto g(6) Build/PPSS29.55-37-8; wv)"
                        + " AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0"
                        + " Chrome/120.0.6099.43 Mobile Safari/537.36"),
                "webview UA matches the AOSP shape");

        // APK install-time anchor (FPJS FileTimestamps). Match only the target's own /data/app APKs.
        String own = "/data/app/~~abc==/com.fingerprintjs.android.fpjs_pro_demo-xyz==/base.apk";
        String split = "/data/app/~~abc==/com.fingerprintjs.android.fpjs_pro_demo-xyz==/split_config.arm64_v8a.apk";
        String pkg = "com.fingerprintjs.android.fpjs_pro_demo";
        check(SpoofLogic.isOwnApk(own, pkg), "base.apk of own pkg matches");
        check(SpoofLogic.isOwnApk(split, pkg), "split apk of own pkg matches");
        check(!SpoofLogic.isOwnApk("/data/app/~~q==/com.pyshivam.geergit-1==/base.apk", pkg), "another pkg's apk not matched");
        check(!SpoofLogic.isOwnApk("/data/user/0/" + pkg + "/files/x.apk", pkg), "app's data-dir file not matched (only /data/app)");
        check(!SpoofLogic.isOwnApk("/data/app/~~q==/" + pkg + "-1==/lib/arm64/x.so", pkg), "non-apk in own dir not matched");
        check(!SpoofLogic.isOwnApk(null, pkg) && !SpoofLogic.isOwnApk(own, null), "null-safe");
        long reset = 1618552951L;
        long baseT = SpoofLogic.apkInstallSeconds(reset, own);
        check(baseT > reset, "install time is AFTER the factory reset");
        check(baseT == SpoofLogic.apkInstallSeconds(reset, own), "stable per (reset, path)");
        check(SpoofLogic.apkInstallSeconds(reset, split) - reset - SpoofLogic.APK_INSTALL_OFFSET_SEC < 13,
                "split spread stays within 0..12s of the base offset");

        // Installed-app filter: hide root / hooking-framework / anti-fp packages, keep ordinary ones.
        check(SpoofLogic.isSensitivePackage("com.specter"), "hide the module");
        check(SpoofLogic.isSensitivePackage("com.specter.probe"), "hide the probe");
        check(SpoofLogic.isSensitivePackage("com.topjohnwu.magisk"), "hide Magisk manager");
        check(SpoofLogic.isSensitivePackage("org.lsposed.manager"), "hide LSPosed");
        check(SpoofLogic.isSensitivePackage("io.github.auag0.hidemocklocation"), "hide mock-location hider");
        check(SpoofLogic.isSensitivePackage("com.tsng.hidemyapplist"), "hide hide-my-applist");
        check(!SpoofLogic.isSensitivePackage("com.android.chrome"), "keep Chrome");
        check(!SpoofLogic.isSensitivePackage("com.whatsapp"), "keep a normal app");
        check(!SpoofLogic.isSensitivePackage(null), "null-safe");
        // Narrowed markers must NOT false-positive on legitimate apps that happen to contain a token.
        check(!SpoofLogic.isSensitivePackage("com.immomo.momo"), "MoMo dating app kept (bare 'momo' not matched)");
        check(!SpoofLogic.isSensitivePackage("com.riru.wallpaper"), "an app named riru-something kept unless it's the real riru pkg form");
        check(!SpoofLogic.isSensitivePackage("com.example.xposedhelper"), "a random app with 'xposed' in a word kept (only real framework ids matched)");
        check(SpoofLogic.isSensitivePackage("de.robv.android.xposed.installer"), "the real Xposed installer hidden");
        check(SpoofLogic.isSensitivePackage("moe.shizuku.privileged.api"), "Shizuku hidden");

        // Sensor resolution/maxRange/power per type — must be positive, plausible, and stable per type.
        float[] acc = SpoofLogic.sensorRmp(1, "BMI160 accelerometer");
        check(acc.length == 3 && acc[0] > 0 && acc[1] > 0, "accelerometer rmp positive");
        check(java.util.Arrays.equals(acc, SpoofLogic.sensorRmp(1, "x")), "rmp stable per type (name ignored)");
        float[] prox = SpoofLogic.sensorRmp(8, "proximity");
        check(prox[0] == 5.0f, "proximity maxRange 5cm");
        float[] gen = SpoofLogic.sensorRmp(999, "unknown");
        check(gen.length == 3 && gen[0] > 0, "unknown type gets a generic plausible rmp");

        // ---- SENSORID calibration transform ----
        // Motion sensors incl. UNCALIBRATED variants (14/16/35) get a real transform; others get identity.
        for (int t : new int[]{1, 2, 4, 9, 10, 14, 16, 35}) check(SpoofLogic.isMotionSensor(t), "motion sensor type " + t);
        for (int t : new int[]{5, 6, 8, 11, 999}) check(!SpoofLogic.isMotionSensor(t), "non-motion type " + t);

        // Derived/uncalibrated streams share their base sensor's calibration (gravity/linear-accel/accel-
        // uncal all use the accelerometer's coeffs; mag-uncal <- mag; gyro-uncal <- gyro).
        check(SpoofLogic.baseMotionType(9) == 1 && SpoofLogic.baseMotionType(10) == 1
                && SpoofLogic.baseMotionType(35) == 1, "gravity/linear/accel-uncal -> accel base");
        check(SpoofLogic.baseMotionType(14) == 2, "mag-uncal -> mag base");
        check(SpoofLogic.baseMotionType(16) == 4, "gyro-uncal -> gyro base");
        float[] accelC = SpoofLogic.sensorCalib(1, "seedA");
        float[] gravC = SpoofLogic.sensorCalib(9, "seedA");
        check(accelC[0] == gravC[0] && accelC[1] == gravC[1] && accelC[2] == gravC[2],
                "gravity shares the accelerometer's SCALE (same base+seed)");
        float[] linC = SpoofLogic.sensorCalib(10, "seedA");
        check(linC[0] == accelC[0] && linC[3] == 0f && linC[4] == 0f && linC[5] == 0f,
                "linear-accel: shared scale, NO bias (preserves linear = accel - gravity)");

        float[] idc = SpoofLogic.sensorCalib(5, "seedA");   // light -> identity
        check(idc[0] == 1f && idc[1] == 1f && idc[2] == 1f && idc[3] == 0f && idc[4] == 0f && idc[5] == 0f,
                "non-motion sensor -> identity transform");

        float[] a1 = SpoofLogic.sensorCalib(1, "seedA");
        float[] a2 = SpoofLogic.sensorCalib(1, "seedA");
        check(java.util.Arrays.equals(a1, a2), "sensorCalib deterministic (same seed+type -> same coeffs)");
        float[] b = SpoofLogic.sensorCalib(1, "seedB");
        check(!java.util.Arrays.equals(a1, b), "different seed -> different calibration (the whole point)");

        // Bounds: scale within ±2% of 1.0; accel bias within its ±0.06 m/s^2 window (gravity stays ~9.81).
        check(a1[0] >= 0.98f && a1[0] <= 1.02f, "scale sx within ±2%");
        check(a1[1] >= 0.98f && a1[1] <= 1.02f, "scale sy within ±2%");
        check(a1[2] >= 0.98f && a1[2] <= 1.02f, "scale sz within ±2%");
        check(Math.abs(a1[3]) <= 0.06f && Math.abs(a1[4]) <= 0.06f && Math.abs(a1[5]) <= 0.06f,
                "accel bias within ±0.06 m/s^2 (gravity magnitude preserved)");
        // Gravity/linear-accel inherit the accelerometer calibration (baseMotionType maps them to 1).
        check(java.util.Arrays.equals(SpoofLogic.sensorCalib(9, "seedA"), SpoofLogic.sensorCalib(9, "seedA")),
                "gravity calib deterministic");
        // A resting accelerometer (0,0,9.81) stays within ~1% of 9.81 after transform.
        float gz = 9.81f * a1[2] + a1[5];
        check(gz > 9.6f && gz < 10.0f, "gravity magnitude stays plausible after transform (" + gz + ")");

        // parseFlatJson: the un-hookable profile parser (the number-survival leak fix — another module's
        // JSONObject.getString / Map.put hook must not be able to poison what Specter reads).
        java.util.Map<String, String> pm = new java.util.HashMap<>();
        SpoofLogic.parseFlatJson("{\"android_id\":\"e117a7fba7f255ab\",\"serial\":\"RREFG0T2J93\",\"build_sdk\":\"29\"}", pm);
        check("e117a7fba7f255ab".equals(pm.get("android_id")), "parseFlatJson reads android_id");
        check("RREFG0T2J93".equals(pm.get("serial")), "parseFlatJson reads serial");
        check("29".equals(pm.get("build_sdk")), "parseFlatJson reads build_sdk");
        // android_id is ALSO mirrored under the shadow key (the leak fix — GeerGit's put-hook doesn't match it)
        check("e117a7fba7f255ab".equals(pm.get(SpoofLogic.TRUE_ANDROID_ID_KEY)), "parseFlatJson mirrors android_id to shadow key");
        check(pm.size() == 4, "parseFlatJson key count incl shadow");
        // shadow key is captured even WITH JSON whitespace after the colon (format-independent, not a raw match)
        java.util.Map<String, String> wm = new java.util.HashMap<>();
        SpoofLogic.parseFlatJson("{ \"android_id\" :  \"abc123\" , \"x\":\"y\" }", wm);
        check("abc123".equals(wm.get("android_id")), "parseFlatJson tolerates whitespace");
        check("abc123".equals(wm.get(SpoofLogic.TRUE_ANDROID_ID_KEY)), "shadow key captured despite whitespace");
        // no android_id -> no shadow key
        java.util.Map<String, String> sm = new java.util.HashMap<>();
        SpoofLogic.parseFlatJson("{\"serial\":\"S\"}", sm);
        check(sm.get(SpoofLogic.TRUE_ANDROID_ID_KEY) == null && sm.size() == 1, "no shadow key when android_id absent");
        // escapes: quote, backslash, slash, newline, and 4-hex unicode are decoded
        java.util.Map<String, String> em = new java.util.HashMap<>();
        SpoofLogic.parseFlatJson("{\"a\":\"x\\ny\",\"b\":\"c\\/d\",\"q\":\"he said \\\"hi\\\"\",\"u\":\"\\u0041\"}", em);
        check("x\ny".equals(em.get("a")), "parseFlatJson decodes newline escape");
        check("c/d".equals(em.get("b")), "parseFlatJson decodes slash escape");
        check("he said \"hi\"".equals(em.get("q")), "parseFlatJson decodes escaped quotes");
        check("A".equals(em.get("u")), "parseFlatJson decodes unicode escape");
        // a value CONTAINING an escaped quote doesn't desync the scanner for the next key (false-match guard)
        java.util.Map<String, String> qm = new java.util.HashMap<>();
        SpoofLogic.parseFlatJson("{\"a\":\"has \\\"android_id\\\":\\\"fake\\\" inside\",\"android_id\":\"real\"}", qm);
        check("real".equals(qm.get("android_id")), "escaped-quote value doesn't false-match a later key");
        check("real".equals(qm.get(SpoofLogic.TRUE_ANDROID_ID_KEY)), "shadow key is the REAL android_id, not the embedded fake");
        // non-string values are skipped without desyncing the scanner
        java.util.Map<String, String> nm = new java.util.HashMap<>();
        SpoofLogic.parseFlatJson("{\"n\":42,\"ok\":\"yes\",\"b\":true}", nm);
        check("yes".equals(nm.get("ok")) && nm.size() == 1, "parseFlatJson skips non-string values");
        // duplicate key: last write wins (standard Map semantics), no crash
        java.util.Map<String, String> dm = new java.util.HashMap<>();
        SpoofLogic.parseFlatJson("{\"android_id\":\"first\",\"android_id\":\"second\"}", dm);
        check("second".equals(dm.get("android_id")) && "second".equals(dm.get(SpoofLogic.TRUE_ANDROID_ID_KEY)), "duplicate key -> last wins");
        // malformed / adversarial inputs never throw or loop
        java.util.Map<String, String> mm = new java.util.HashMap<>();
        SpoofLogic.parseFlatJson("{\"unterminated\":\"oops", mm);           // no closing quote/brace
        SpoofLogic.parseFlatJson("", mm);                                    // empty
        SpoofLogic.parseFlatJson("not json at all", mm);                     // garbage
        SpoofLogic.parseFlatJson("{\"a\":\"b\"} trailing garbage", mm);      // trailing garbage after close
        SpoofLogic.parseFlatJson("{\"bad\\", mm);                            // lone trailing backslash
        SpoofLogic.parseFlatJson("{\"u\":\"\\u12", mm);                      // truncated unicode escape
        check("b".equals(mm.get("a")), "parseFlatJson recovers valid pairs amid garbage, never throws/loops");

        System.out.println("SpoofLogic: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
