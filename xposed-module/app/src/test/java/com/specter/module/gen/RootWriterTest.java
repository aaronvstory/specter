package com.specter.module.gen;

/** Plain-JVM tests for RootWriter command-building + fail-loudly behaviour. Run via run-jvm-tests.sh. */
public class RootWriterTest {
    static int passed = 0, failed = 0;
    static void check(boolean cond, String name) {
        if (cond) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        // valid package names accepted
        check(RootWriter.validPkg("com.liuzh.deviceinfo"), "valid pkg");
        check(RootWriter.validPkg("com.a.b.c"), "valid multi-segment");
        // shell-injection attempts rejected (the pkg is the only thing on the su command line)
        check(!RootWriter.validPkg("com.x; rm -rf /"), "reject semicolon");
        check(!RootWriter.validPkg("com.x`whoami`"), "reject backtick");
        check(!RootWriter.validPkg("com.x$(id)"), "reject subshell");
        check(!RootWriter.validPkg("nodots"), "reject no-dot");
        check(!RootWriter.validPkg(""), "reject empty");
        check(!RootWriter.validPkg(null), "reject null");

        // command targets the exact hook-read path, feeds JSON via stdin, chmods 644, writes atomically
        String cmd = RootWriter.buildShellCommand("com.liuzh.deviceinfo");
        String finalPath = "/data/local/tmp/specter/com.liuzh.deviceinfo.json";
        check(cmd.contains(finalPath), "command targets hook path");
        check(cmd.contains("mkdir -p /data/local/tmp/specter"), "command mkdirs the dir");
        check(cmd.contains("chmod 644"), "command chmods world-readable");
        check(cmd.startsWith("mkdir"), "no pkg interpolation before validated path");
        // ATOMIC: writes to a .tmp, and mv's it over the final path (never truncates the live file).
        check(cmd.contains(finalPath + ".tmp"), "writes to a .tmp first");
        check(cmd.contains("cat > " + finalPath + ".tmp"), "stdin lands in the .tmp, not the live file");
        check(cmd.contains("mv -f " + finalPath + ".tmp " + finalPath), "atomically mv .tmp over final");
        check(cmd.contains("[ -s " + finalPath + ".tmp ]"), "verifies .tmp non-empty before mv");
        check(cmd.contains("rm -f " + finalPath + ".tmp"), "cleans the .tmp on failure");
        // the live file is only ever named on the mv target / non-empty check — never `cat >`-truncated
        check(!cmd.contains("cat > " + finalPath + " "), "never truncates the live file with cat");

        // invalid pkg throws at command-build (never reaches a shell)
        boolean threw = false;
        try { RootWriter.buildShellCommand("bad;rm"); } catch (RootWriter.WriteException e) { threw = true; }
        check(threw, "invalid pkg -> WriteException at build");

        // write() fails LOUDLY (throws) on a non-zero exit — never a silent no-op
        RootWriter.Shell denied = (command, stdin) -> 1; // simulate su denied
        boolean loud = false;
        try { RootWriter.write(denied, "com.liuzh.deviceinfo", "{}"); }
        catch (RootWriter.WriteException e) { loud = true; }
        check(loud, "non-zero su exit -> WriteException (loud)");

        // write() passes the JSON to stdin and the built command
        final String[] captured = new String[2];
        RootWriter.Shell ok = (command, stdin) -> { captured[0] = command; captured[1] = stdin; return 0; };
        RootWriter.write(ok, "com.liuzh.deviceinfo", "{\"android_id\":\"abc\"}");
        check(captured[1].equals("{\"android_id\":\"abc\"}"), "json fed via stdin");
        check(captured[0].contains("com.liuzh.deviceinfo.json"), "correct target in command");

        // exec exception surfaces as WriteException (e.g. su binary absent)
        RootWriter.Shell boom = (command, stdin) -> { throw new RuntimeException("su: not found"); };
        boolean surfaced = false;
        try { RootWriter.write(boom, "com.liuzh.deviceinfo", "{}"); }
        catch (RootWriter.WriteException e) { surfaced = e.getMessage().contains("root"); }
        check(surfaced, "su-absent surfaces as WriteException mentioning root");

        // ---- setGps: align the device GPS to a specific fix (proxy exit IP), preserving the whole identity ----
        final String[] gpsWrite = {null};
        RootWriter.Shell gpsShell = new RootWriter.Shell() {
            public int run(String command, String stdin) { gpsWrite[0] = stdin; return 0; }
            public String runCapture(String command) {
                return "{\"android_id\":\"abc123\",\"gps_lat\":\"41.878100\",\"gps_lon\":\"-87.629800\","
                        + "\"gps_accuracy\":\"12\",\"timezone\":\"America/Chicago\"}";
            }
        };
        boolean gset = RootWriter.setGps(gpsShell, "com.liuzh.deviceinfo", 32.7767, -96.7970);  // Dallas
        check(gset, "setGps returns true on a live profile");
        check(gpsWrite[0] != null, "setGps wrote the profile back");
        check(gpsWrite[0].contains("\"gps_lat\":\"32.776700\""), "setGps patched gps_lat (6dp)");
        check(gpsWrite[0].contains("\"gps_lon\":\"-96.797000\""), "setGps patched gps_lon (6dp)");
        check(gpsWrite[0].contains("\"gps_accuracy\":\"12\""), "setGps preserved gps_accuracy");
        check(gpsWrite[0].contains("\"android_id\":\"abc123\""), "setGps preserved the identity (android_id)");
        check(gpsWrite[0].contains("\"timezone\":\"America/Chicago\""), "setGps preserved other fields (timezone)");
        // idempotent: aligning to the value already present writes nothing new but returns true
        final int[] gpsWrites = {0};
        RootWriter.Shell already = new RootWriter.Shell() {
            public int run(String command, String stdin) { gpsWrites[0]++; return 0; }
            public String runCapture(String command) {
                return "{\"gps_lat\":\"32.776700\",\"gps_lon\":\"-96.797000\"}";
            }
        };
        check(RootWriter.setGps(already, "com.x.y", 32.7767, -96.7970), "setGps idempotent returns true");
        check(gpsWrites[0] == 0, "setGps no-op when already aligned (no write)");
        // guards: out-of-range coords and bad pkg are rejected
        check(!RootWriter.setGps(gpsShell, "com.x.y", 200.0, 0.0), "setGps rejects out-of-range lat");
        check(!RootWriter.setGps(gpsShell, "com.x.y", 0.0, 999.0), "setGps rejects out-of-range lon");
        check(!RootWriter.setGps(gpsShell, "bad;rm", 1.0, 2.0), "setGps rejects a bad pkg");
        // empty/absent profile -> false, never writes garbage
        RootWriter.Shell empty = new RootWriter.Shell() {
            public int run(String command, String stdin) { return 0; }
            public String runCapture(String command) { return ""; }
        };
        check(!RootWriter.setGps(empty, "com.x.y", 1.0, 2.0), "setGps returns false when no profile exists");

        // ---- onlyIfDefault: the AUTO path preserves a custom pin but overwrites a still-default fix ----
        String[] def312 = Generators.gpsForAreaCode("312", "aid999");
        final String defJson = "{\"mobile_number\":\"13125551234\",\"android_id\":\"aid999\","
                + "\"gps_lat\":\"" + def312[0] + "\",\"gps_lon\":\"" + def312[1] + "\"}";
        final String[] w1 = {null};
        RootWriter.Shell defShell = new RootWriter.Shell() {
            public int run(String c, String s) { w1[0] = s; return 0; }
            public String runCapture(String c) { return defJson; }
        };
        check(RootWriter.setGps(defShell, "com.x.y", 40.0, -80.0, true), "auto-align overwrites a still-default fix");
        check(w1[0] != null && w1[0].contains("\"gps_lat\":\"40.000000\""), "default fix took the IP coords");

        final String custJson = "{\"mobile_number\":\"13125551234\",\"android_id\":\"aid999\","
                + "\"gps_lat\":\"25.761700\",\"gps_lon\":\"-80.191800\"}";   // a custom Miami pin, != 312 default
        final int[] w2 = {0};
        RootWriter.Shell custShell = new RootWriter.Shell() {
            public int run(String c, String s) { w2[0]++; return 0; }
            public String runCapture(String c) { return custJson; }
        };
        check(!RootWriter.setGps(custShell, "com.x.y", 40.0, -80.0, true), "auto-align preserves a custom pin (false)");
        check(w2[0] == 0, "auto-align never wrote over the custom pin");
        check(RootWriter.setGps(custShell, "com.x.y", 40.0, -80.0, false), "manual align overrides even a custom pin");

        // ---- serialize <-> parse ROUND-TRIP: toFlatJson (the writer) must be losslessly readable by
        //      SpoofLogic.parseFlatJson (the reader the hooks use). This is the historically-buggy area
        //      (the 0.22.10 `\/`-unescape fix): a value with a quote/backslash/newline/CR/tab or a JSON
        //      structural char must survive the write->read trip unchanged, or an applied profile silently
        //      corrupts. esc() escapes " \ \n \r \t; readJsonString must decode every one.
        java.util.Map<String, String> orig = new java.util.LinkedHashMap<>();
        orig.put("android_id", "e117a7fba7f255ab");
        orig.put("build_fingerprint", "lge/mh2lm/mh2lm:11/RKQ1/abc:user/release-keys");  // slashes, colons
        orig.put("q", "he said \"hi\"");            // embedded quotes
        orig.put("bs", "a\\b\\c");                  // backslashes
        orig.put("ctl", "line1\nline2\r\tX");        // newline, CR, tab
        orig.put("json", "{\"nested\":1},x");        // JSON structural chars inside a value
        orig.put("empty", "");
        String flat = com.specter.module.gen.RootWriter.toFlatJson(orig);
        java.util.Map<String, String> back = new java.util.HashMap<>();
        com.specter.module.SpoofLogic.parseFlatJson(flat, back);
        boolean roundTrip = true;
        for (java.util.Map.Entry<String, String> e : orig.entrySet()) {
            if (!e.getValue().equals(back.get(e.getKey()))) {
                roundTrip = false;
                System.out.println("  round-trip lost " + e.getKey() + ": wrote " + e.getValue()
                        + " read " + back.get(e.getKey()));
            }
        }
        check(roundTrip, "toFlatJson -> parseFlatJson is lossless (quotes/backslash/newline/CR/tab/slash/JSON-chars)");
        // The android_id shadow key is mirrored on read (the true-value capture), so the map gains exactly one.
        check(back.size() == orig.size() + 1, "parse round-trip keeps every key (+1 android_id shadow)");

        System.out.println("RootWriter: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
