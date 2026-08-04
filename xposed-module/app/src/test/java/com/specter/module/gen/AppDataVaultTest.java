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
        // ATOMIC: copy to .tmp then rename over the final path (a partial copy can't be seen as good).
        check(in.contains(".tgz.tmp") && in.contains("mv -f"), "copy-in is atomic (temp + rename)");

        String out = AppDataVault.buildCopyOut("/data/data/com.specter/files/appdata/072926-Sun-1924-razr.tgz",
                "/data/local/tmp/specter/session-com.x.y.tgz");
        check(out.contains("cp '/data/data/com.specter/files/appdata/072926-Sun-1924-razr.tgz'"),
                "copy-out copies the vault tar back to staging");
        check(out.contains("mkdir -p '/data/local/tmp/specter'"), "copy-out ensures the staging dir exists");

        // ---- export/import bundle commands ----
        String exp = AppDataVault.buildExportCommand("/data/data/com.specter/files/appdata",
                "072926-Sun-1924-razr", "/sdcard/Download/Specter/specter-login-072926-Sun-1924-razr.tar",
                "/sdcard/Download/Specter");
        check(exp.contains("tar cf '/sdcard/Download/Specter/specter-login-072926-Sun-1924-razr.tar'"), "export builds the bundle tar");
        check(exp.contains("'072926-Sun-1924-razr.tgz' '072926-Sun-1924-razr.meta'"), "export bundles BOTH tgz + meta");
        check(exp.contains("test -f") && exp.indexOf("test -f") < exp.indexOf("tar cf"), "export guards missing source");
        check(exp.contains("mkdir -p '/sdcard/Download/Specter'"), "export auto-creates the Specter folder");

        String imp = AppDataVault.buildImportCommand("/sdcard/Download/specter-login-x.tar",
                "/data/data/com.specter/files/appdata", "072926-Sun-1924-razr");
        check(imp.contains("tar xf '/sdcard/Download/specter-login-x.tar'"), "import extracts the bundle");
        // import TYPE guard: require EVERY entry to be a regular file (rejects symlink/hardlink/device/fifo).
        check(imp.contains("tar tvf") && imp.contains("grep -qvE '^-'"), "import requires regular-file entries only");
        // import EXACT-SET guard: members must be exactly <label>.meta + <label>.tgz for the expected label.
        check(imp.contains("072926-Sun-1924-razr.meta|072926-Sun-1924-razr.tgz|"), "import requires exactly the label's two files");

        // ---- combined-bundle commands (fingerprint .json + AppData .tgz/.meta as one tar) ----
        String cexp = AppDataVault.buildComboExportCommand("/data/data/com.specter/files/appdata",
                "072926-Sun-1924-razr", "/sdcard/Download/Specter/specter-combo-072926-Sun-1924-razr.tar",
                "/sdcard/Download/Specter");
        check(cexp.contains("'072926-Sun-1924-razr.json' '072926-Sun-1924-razr.tgz' '072926-Sun-1924-razr.meta'"),
                "combo export bundles json + tgz + meta");
        check(cexp.contains("test -f") && cexp.contains(".json'"), "combo export guards BOTH sources");
        check(cexp.contains("mkdir -p '/sdcard/Download/Specter'"), "combo export auto-creates the Specter folder");

        String cimp = AppDataVault.buildComboImportCommand("/sdcard/Download/Specter/specter-combo-x.tar",
                "/data/data/com.specter/files/combo-import-072926-Sun-1924-razr", "072926-Sun-1924-razr");
        // TOCTOU-safe: the untrusted /sdcard tar is COPIED to an app-owned staged path FIRST, then ALL
        // validation + extraction touch only the staged copy.
        check(cimp.contains("cp '/sdcard/Download/Specter/specter-combo-x.tar' '/data/data/com.specter/files/combo-import-072926-Sun-1924-razr/src.tar'"),
                "combo import stages the untrusted tar to an app-owned copy first");
        check(cimp.contains("tar tvf '/data/data/com.specter/files/combo-import-072926-Sun-1924-razr/src.tar'"),
                "combo import validates the STAGED copy, not the /sdcard original (no TOCTOU)");
        check(cimp.contains("tar tvf") && cimp.contains("grep -qvE '^-'"), "combo import requires regular-file entries only");
        check(cimp.contains("072926-Sun-1924-razr.json|072926-Sun-1924-razr.meta|072926-Sun-1924-razr.tgz|"),
                "combo import requires exactly the label's three files");
        check(cimp.contains("tar xf '/data/data/com.specter/files/combo-import-072926-Sun-1924-razr/src.tar' -C '/data/data/com.specter/files/combo-import-072926-Sun-1924-razr'"),
                "combo import extracts the STAGED copy to the temp dir");
        check(cimp.contains("chmod 644 '/data/data/com.specter/files/combo-import-072926-Sun-1924-razr/072926-Sun-1924-razr.json'"),
                "combo import chmods extracted members so the app can read them");

        // ---- label grammar: first char must be alphanumeric (no leading '-' -> tar-option, no leading '.' -> hidden) ----
        check(AppDataVault.validLabel("072926-Sun-1924-razr"), "validLabel accepts a normal timestamp label");
        check(!AppDataVault.validLabel("-rf"), "validLabel rejects a leading dash (would be read as a tar/rm option)");
        check(!AppDataVault.validLabel(".hidden"), "validLabel rejects a leading dot");

        // ---- bundle-name routing ----
        check("072926-Sun-1924-razr".equals(AppDataVault.labelOfBundle("specter-combo-072926-Sun-1924-razr.tar")),
                "labelOfBundle reads a combo bundle label");
        check("072926-Sun-1924-razr".equals(AppDataVault.labelOfBundle("specter-login-072926-Sun-1924-razr.tar")),
                "labelOfBundle reads a login bundle label");
        check(AppDataVault.labelOfBundle("specter-profile-x.json") == null, "labelOfBundle rejects a non-tar name");
        check(AppDataVault.labelOfBundle("specter-combo-../evil.tar") == null, "labelOfBundle rejects a traversal label");

        // ---- name sanitizer ----
        check(AppDataVault.sanitizeName("Bob's Phone!").equals("Bobs_Phone"), "sanitizeName strips punctuation, space->_");
        check(AppDataVault.sanitizeName("").isEmpty(), "sanitizeName empty");

        // ---- drift detection: which saved logins were captured under a different device ----
        // (fingerprint left "" so parseMeta doesn't reject the synthetic labels — the drift check reads
        //  only the device string.)
        String cash = "com.squareup.cash";
        java.util.List<AppDataVault.Entry> saved = new java.util.ArrayList<>();
        saved.add(AppDataVault.parseMeta("a", AppDataVault.serializeMeta(cash, 3, 1, "", "Google Pixel 4a (5G)")));
        saved.add(AppDataVault.parseMeta("b", AppDataVault.serializeMeta(cash, 2, 1, "", "Samsung SM-G996U")));
        saved.add(AppDataVault.parseMeta("c", AppDataVault.serializeMeta(cash, 1, 1, "", "Google Pixel 4a (5G)")));
        // Applying SM-G996U: only the Pixel-4a logins disagree, and the duplicate collapses to one.
        check(AppDataVault.conflictingDevices(saved, "Samsung SM-G996U")
                .equals(java.util.Collections.singletonList("Google Pixel 4a (5G)")),
                "drift: SM-G996U conflicts only with the distinct Pixel 4a logins");
        // Applying the very device a login was captured under: only the OTHER model conflicts.
        check(AppDataVault.conflictingDevices(saved, "Google Pixel 4a (5G)")
                .equals(java.util.Collections.singletonList("Samsung SM-G996U")),
                "drift: applying Pixel 4a conflicts only with the SM-G996U login");
        // Case/space-insensitive.
        java.util.List<AppDataVault.Entry> one = java.util.Collections.singletonList(
                AppDataVault.parseMeta("d", AppDataVault.serializeMeta(cash, 1, 1, "", "  pixel 4A (5g) ")));
        check(!AppDataVault.conflictingDevices(one, "Something Else").isEmpty(),
                "drift compares a genuinely different device as a conflict");
        check(AppDataVault.conflictingDevices(one, "pixel 4a (5g)").isEmpty(),
                "drift: same device modulo case/space is NOT a conflict");
        // A login with no recorded device is ignored.
        java.util.List<AppDataVault.Entry> noDev = java.util.Collections.singletonList(
                AppDataVault.parseMeta("e", AppDataVault.serializeMeta(cash, 1, 1, "", "")));
        check(AppDataVault.conflictingDevices(noDev, "anything").isEmpty(),
                "drift: a login with no device recorded never conflicts");
        check(AppDataVault.conflictingDevices(null, "x").isEmpty(), "drift: null list is safe");

        System.out.println("AppDataVault: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
