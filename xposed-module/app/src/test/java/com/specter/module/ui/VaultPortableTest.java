package com.specter.module.ui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * JVM self-test for the portable vault format's integrity logic ({@link VaultChecksum}): SHA-256 that is
 * order-independent and metadata-excluding, so corruption is detectable on import. The org.json envelope
 * build/parse (Android-only) round-trip is verified on-device.
 */
public final class VaultPortableTest {
    static int fails = 0;
    static void check(boolean c, String m) { if (!c) { System.out.println("FAIL: " + m); fails++; } }

    public static void main(String[] args) {
        Map<String, String> p = new LinkedHashMap<>();
        p.put("android_id", "fd3833c66a179a71");
        p.put("build_manufacturer", "samsung");
        p.put("build_model", "SM-A505F");
        p.put("timezone", "America/New_York");
        p.put("_saved_at", "12345");
        p.put("_targets", "com.x");

        Map<String, String> stripped = VaultChecksum.stripMeta(p);
        check(!stripped.containsKey("_saved_at") && !stripped.containsKey("_targets"), "metadata stripped");
        check(stripped.get("android_id").equals("fd3833c66a179a71"), "identity kept after strip");

        String c1 = VaultChecksum.of(p);
        check(c1 != null && c1.length() == 64, "sha-256 checksum is 64 hex chars");

        // Order-independent + metadata is excluded (reordered map, no metadata -> same digest).
        Map<String, String> reordered = new LinkedHashMap<>();
        reordered.put("timezone", "America/New_York");
        reordered.put("build_model", "SM-A505F");
        reordered.put("build_manufacturer", "samsung");
        reordered.put("android_id", "fd3833c66a179a71");
        check(c1.equals(VaultChecksum.of(reordered)), "checksum order-independent + metadata-excluded");

        // A changed identity value flips the checksum -> corruption detectable on import.
        Map<String, String> tampered = new LinkedHashMap<>(p);
        tampered.put("android_id", "ff3833c66a179a71");
        check(!c1.equals(VaultChecksum.of(tampered)), "value change flips the checksum");

        // Changing ONLY metadata does not change the digest (metadata isn't shared/checksummed).
        Map<String, String> metaOnly = new LinkedHashMap<>(p);
        metaOnly.put("_saved_at", "99999");
        check(c1.equals(VaultChecksum.of(metaOnly)), "metadata-only change keeps the checksum");

        // isShellSafePath: legit paths pass; anything with a shell metacharacter is refused (injection guard).
        check(VaultChecksum.isShellSafePath("/sdcard/Download/specter-profile-072626_Sun_1021.json"), "normal path safe");
        check(!VaultChecksum.isShellSafePath("/sdcard/Download/x'; rm -rf /;'.json"), "single-quote path refused");
        check(!VaultChecksum.isShellSafePath("/sdcard/Download/$(reboot).json"), "command-subst path refused");
        check(!VaultChecksum.isShellSafePath("/sdcard/Download/a`id`.json"), "backtick path refused");
        check(!VaultChecksum.isShellSafePath("/sdcard/Download/a;b.json"), "semicolon path refused");
        check(!VaultChecksum.isShellSafePath(""), "empty path refused");
        check(!VaultChecksum.isShellSafePath(null), "null path refused");

        if (fails == 0) System.out.println("ALL PASS (VaultPortable)");
        else { System.out.println(fails + " FAILURE(S)"); System.exit(1); }
    }
}
