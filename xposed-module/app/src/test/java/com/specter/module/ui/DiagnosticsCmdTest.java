package com.specter.module.ui;

/** Plain-JVM checks for the diagnostics capture command. Run via run-jvm-tests.sh. */
public class DiagnosticsCmdTest {
    static int passed = 0, failed = 0;
    static void check(boolean cond, String name) {
        if (cond) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        String cmd = DiagnosticsCmd.captureCommand();
        // Must write to the fixed, adb-pullable path (same dir as profiles).
        check(cmd.contains("/data/local/tmp/specter/diag.log"), "captures to the diag.log path");
        check(cmd.contains("-f "), "uses logcat -f (file, not stdout)");
        // Rotation cap so it can't fill the disk during long fleet use.
        check(cmd.contains("-r 8192") && cmd.contains("-n 4"), "rotates (8MB x4 cap)");
        // Filters to OUR tags only — silences the rest so the file isn't the whole system log.
        check(cmd.contains("SpecterTrace:*") && cmd.contains("specter:*"), "filters to specter tags");
        check(cmd.startsWith("mkdir -p /data/local/tmp/specter"), "ensures the dir exists first");
        // The kill command targets only our own capture, by its file path (not all logcat).
        check(DiagnosticsCmd.killCommand().contains("diag.log"), "kill targets our capture only");

        System.out.println("DiagnosticsCmd: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
