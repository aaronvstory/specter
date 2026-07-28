package com.specter.module.gen;

/** Plain-JVM tests for AppDataVault pure logic: meta round-trip, command building, path/label/pkg guards. */
public class AppDataVaultTest {
    static int passed = 0, failed = 0;
    static void check(boolean cond, String name) {
        if (cond) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        // ---- label / pkg validation guards the su boundary ----
        check(AppDataVault.validLabel("072926-Sun-1924-BobPhone"), "valid label");
        check(!AppDataVault.validLabel("bad;rm -rf"), "reject label with ;");
        check(!AppDataVault.validLabel("a b"), "reject label with space");
        check(!AppDataVault.validLabel(""), "reject empty label");
        check(!AppDataVault.validLabel(null), "reject null label");
        check(AppDataVault.validPkg("com.doordash.driverapp"), "valid pkg");
        check(!AppDataVault.validPkg("com.x`whoami`"), "reject pkg backtick");

        // ---- meta serialize/parse round-trip ----
        String meta = AppDataVault.serializeMeta("com.squareup.cash", 1785267000000L, 4842823L,
                "072926-Sun-1924-razr", "Motorola razr 2020");
        AppDataVault.Entry e = AppDataVault.parseMeta("072926-Sun-1924-razr", meta);
        check(e != null, "meta parses");
        check(e.pkg.equals("com.squareup.cash"), "meta pkg");
        check(e.savedAt == 1785267000000L, "meta savedAt");
        check(e.sizeBytes == 4842823L, "meta sizeBytes");
        check(e.fingerprint.equals("072926-Sun-1924-razr"), "meta fingerprint link");
        check(e.device.equals("Motorola razr 2020"), "meta device");
        check(e.label.equals("072926-Sun-1924-razr"), "label from filename");

        // empty fingerprint (no active vault entry) is allowed and round-trips as ""
        AppDataVault.Entry e2 = AppDataVault.parseMeta("x", AppDataVault.serializeMeta("com.x.y", 1L, 2L, "", "Dev"));
        check(e2 != null && e2.fingerprint.isEmpty(), "empty fingerprint ok");

        // junk / missing pkg -> null (never a half-built entry)
        check(AppDataVault.parseMeta("x", "garbage no equals") == null, "junk meta -> null");
        check(AppDataVault.parseMeta("x", "savedAt=5\n") == null, "meta without pkg -> null");

        // ---- command building: only validated, quoted paths; guards missing source ----
        String in = AppDataVault.buildCopyIn("/data/local/tmp/specter/session-com.x.y.tgz",
                "/data/data/com.specter/files/appdata/072926-Sun-1924-razr.tgz");
        check(in.contains("cp '/data/local/tmp/specter/session-com.x.y.tgz'"), "copy-in copies the staged tar");
        check(in.contains("chmod 644"), "copy-in makes the vault copy readable");
        check(in.contains("test -f") && in.indexOf("test -f") < in.indexOf("cp "), "copy-in guards missing source first");

        String out = AppDataVault.buildCopyOut("/data/data/com.specter/files/appdata/072926-Sun-1924-razr.tgz",
                "/data/local/tmp/specter/session-com.x.y.tgz");
        check(out.contains("cp '/data/data/com.specter/files/appdata/072926-Sun-1924-razr.tgz'"),
                "copy-out copies the vault tar back to staging");
        check(out.contains("mkdir -p '/data/local/tmp/specter'"), "copy-out ensures the staging dir exists");

        System.out.println("AppDataVault: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
