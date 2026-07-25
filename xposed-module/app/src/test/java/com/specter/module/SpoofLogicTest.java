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

        System.out.println("SpoofLogic: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
