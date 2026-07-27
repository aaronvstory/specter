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

        // Specter Lite parity: the lite harvester (separate module, can't depend on this one) re-implements
        // the SAME checksum inline. This asserts that independent copy stays byte-identical to VaultChecksum
        // .of — if it drifts, a harvested profile fails import with "checksum mismatch". Mirrors the exact
        // algorithm in HarvestActivity.checksum() (sorted k=v\n, SHA-256, Character.forDigit hex).
        // Use a metadata-free profile: the harvester never emits _saved_at/_targets, and VaultChecksum.of
        // strips them, so the two agree exactly on real harvest output (the import-compatibility case).
        Map<String, String> clean = VaultChecksum.stripMeta(p);
        check(VaultChecksum.of(clean).equals(liteHarvestChecksum(clean)),
                "lite harvester checksum matches VaultChecksum.of");
        Map<String, String> harvested = new LinkedHashMap<>();
        harvested.put("android_id", "a1b2c3d4e5f60718");
        harvested.put("build_model", "Pixel 4a");
        harvested.put("total_ram", "5943947264");
        harvested.put("hw_gpu_renderer", "Adreno (TM) 618");
        check(VaultChecksum.of(harvested).equals(liteHarvestChecksum(harvested)),
                "lite harvester checksum matches on a realistic harvested profile");

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

    /** Byte-for-byte copy of com.specter.lite.HarvestActivity.checksum(). Kept here as the parity oracle:
     *  the lite module can't depend on the app module, so this guards that its inline re-implementation
     *  matches VaultChecksum.of. If you change one, change both (and this test catches a mismatch). */
    static String liteHarvestChecksum(Map<String, String> profile) {
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
}
