package com.specter.module.gen;

/** Plain-JVM tests for the GSF/Google-identity reset command-builder + exec behaviour. */
public class GsfResetTest {
    static int passed = 0, failed = 0;
    static void check(boolean cond, String name) {
        if (cond) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        String cmd = GsfReset.buildResetCommand();
        // clears all three Google packages that hold the device-wide identity/registration state
        check(cmd.contains("pm clear com.google.android.gsf"), "clears GSF (holds the android_id)");
        check(cmd.contains("pm clear com.google.android.gms"), "clears GMS");
        check(cmd.contains("pm clear com.android.vending"), "clears Vending");
        // force-stops before clearing each (avoid clearing a live process mid-write)
        check(cmd.contains("am force-stop com.google.android.gsf"), "force-stops GSF first");
        check(cmd.indexOf("am force-stop com.google.android.gms") < cmd.indexOf("pm clear com.google.android.gms"),
                "force-stop precedes pm clear for GMS");
        // each package guarded by presence so a missing Google pkg is skipped, not a failure
        check(cmd.contains("pm path com.google.android.gsf"), "guards GSF presence");
        check(cmd.contains("set -e"), "aborts on a failing clear");
        // FAILS LOUDLY if GSF (the id holder) isn't installed — no silent "success" that cleared nothing
        check(cmd.contains("gsf_not_installed") && cmd.contains("exit 5"), "refuses when GSF not installed");
        // requires at least one real clear (a PM/permission failure that fails every clear can't pass as success)
        check(cmd.contains("cleared=0") && cmd.contains("no_package_cleared") && cmd.contains("exit 6"),
                "requires >=1 successful clear");
        // gates on pm clear's OUTPUT ("Success"), since pm clear can exit 0 without clearing
        check(cmd.contains("*Success*"), "gates each clear on the Success output");
        // the reset command does NOT reboot — the reboot is a SEPARATE call issued only after the clears
        // succeed, so a denied clear can never masquerade as a successful reset.
        check(!cmd.contains("reboot"), "buildResetCommand does not reboot (reboot is a separate step)");

        // reset(shell, false): clears only — exit code is authoritative, throws on non-zero
        boolean threw = false;
        try { GsfReset.reset((c, s) -> 1, false); } catch (GsfReset.GsfException e) { threw = true; }
        check(threw, "reset throws on non-zero clear exit (root denied)");
        boolean ok = true;
        try { GsfReset.reset((c, s) -> 0, false); } catch (GsfReset.GsfException e) { ok = false; }
        check(ok, "reset succeeds on zero clear exit");
        // reset(shell, true): the FIRST call (clears) is checked; a denied clear throws and NO reboot is issued.
        final int[] calls = {0};
        boolean deniedThrew = false;
        try {
            GsfReset.reset((c, s) -> { calls[0]++; return 1; }, true);   // first (clears) returns non-zero
        } catch (GsfReset.GsfException e) { deniedThrew = true; }
        check(deniedThrew, "reboot reset throws when the clears are denied");
        check(calls[0] == 1, "denied clears -> only ONE su call (no reboot issued on failure)");
        // successful clears -> two calls (clears then reboot); the reboot's exit code is ignored
        final int[] calls2 = {0};
        boolean rebootOk = true;
        try {
            GsfReset.reset((c, s) -> { calls2[0]++; return calls2[0] == 1 ? 0 : 137; }, true);
        } catch (GsfReset.GsfException e) { rebootOk = false; }
        check(rebootOk, "reboot reset succeeds; a torn-down reboot exit code is ignored");
        check(calls2[0] == 2, "successful reset -> clears then a separate reboot call");
        // a thrown shell exception (root denied) is wrapped, not leaked raw
        boolean wrapped = false;
        try { GsfReset.reset((c, s) -> { throw new RuntimeException("no su"); }, false); }
        catch (GsfReset.GsfException e) { wrapped = true; }
        check(wrapped, "reset wraps a shell exception");

        System.out.println("GsfReset: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
