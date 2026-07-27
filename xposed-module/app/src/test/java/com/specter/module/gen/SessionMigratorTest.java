package com.specter.module.gen;

/** Plain-JVM tests for SessionMigrator command-building + fail-loudly behaviour. Run via run-jvm-tests.sh. */
public class SessionMigratorTest {
    static int passed = 0, failed = 0;
    static void check(boolean cond, String name) {
        if (cond) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        // package validation guards the su shell boundary (same grammar as RootWriter)
        check(SessionMigrator.validPkg("com.doordash.driverapp"), "valid pkg");
        check(!SessionMigrator.validPkg("com.x; rm -rf /"), "reject semicolon");
        check(!SessionMigrator.validPkg("com.x`whoami`"), "reject backtick");
        check(!SessionMigrator.validPkg("com.x$(id)"), "reject subshell");
        check(!SessionMigrator.validPkg(""), "reject empty");
        check(!SessionMigrator.validPkg(null), "reject null");

        // tarPath is under the shared tmp dir and names the package
        String tar = SessionMigrator.tarPath("com.doordash.driverapp");
        check(tar.equals("/data/local/tmp/specter/session-com.doordash.driverapp.tgz"), "tarPath shape");
        boolean tarThrew = false;
        try { SessionMigrator.tarPath("bad;rm"); } catch (SessionMigrator.SessionException e) { tarThrew = true; }
        check(tarThrew, "tarPath rejects bad pkg");

        // capture command: tars the session subdirs (whole databases/ dir -> includes -wal/-shm where the
        // live token lives), targets the right tar, and guards app-not-installed / never-logged-in.
        String cap = SessionMigrator.buildCaptureCommand("com.doordash.driverapp");
        check(cap.contains("tar czf " + tar), "capture writes the tarball");
        check(cap.contains("-C /data/data/com.doordash.driverapp"), "capture -C into the data dir");
        check(cap.contains("databases") && cap.contains("shared_prefs"), "capture includes both session dirs");
        check(cap.contains("test -d /data/data/com.doordash.driverapp"), "capture guards app-not-installed");
        check(cap.contains("chmod 644 " + tar), "capture chmods the tarball readable");
        // it must NOT restrict to *.db (that would drop the -wal where the live token is) — we tar dirs.
        check(!cap.contains("*.db"), "capture takes whole dirs, not just .db (keeps -wal)");
        // capture stops the app first so the SQLite WAL isn't mid-write (coherent snapshot), and requires it.
        check(cap.contains("am force-stop com.doordash.driverapp"), "capture stops the app before tarring");
        check(cap.indexOf("am force-stop") < cap.indexOf("tar czf"), "capture stops BEFORE the tar");

        // restore command: SAFE-BY-CONSTRUCTION — validates the archive, extracts to staging, then swaps
        // with rollback. It must NEVER rm the live session before the new one is proven good.
        String res = SessionMigrator.buildRestoreCommand("com.doordash.driverapp");
        check(res.contains("uid=$(stat -c %u /data/data/com.doordash.driverapp)"), "restore resolves target uid on-device");
        check(res.contains("am force-stop com.doordash.driverapp"), "restore stops the app");
        check(res.contains("chown -R $uid:$uid"), "restore re-owns to resolved uid");
        check(res.contains("restorecon -R /data/data/com.doordash.driverapp"), "restore relabels SELinux");
        check(res.contains("test -f " + tar), "restore guards missing staged session");
        // (1) integrity: a `tar tzf` readability check gates everything destructive.
        check(res.contains("tar tzf " + tar + " >/dev/null"), "restore verifies the archive is readable first");
        // (2) traversal: refuse any entry not confined to the two session dirs (extraction runs as root).
        check(res.contains("grep -qvE '^(databases|shared_prefs)(/|$)'"), "restore refuses entries outside the session dirs");
        // (3) staging: extract to a staging dir, not straight into the data dir.
        check(res.contains("tar xzf " + tar + " -C /data/local/tmp/specter/restore-com.doordash.driverapp"),
                "restore extracts to a staging dir, not the live data dir");
        // (4)/(5) NO destructive rm of the live dirs before the swap — the old dirs are moved ASIDE, and a
        // failed swap rolls them back. The only `rm -rf` of a data-dir path is the rollback wipe, which is
        // immediately followed by a restore of the aside copy.
        check(res.contains("mv /data/data/com.doordash.driverapp/$d $aside/$d"), "restore moves current dirs aside (not delete)");
        check(res.contains("rolling back"), "restore has a rollback path");
        check(!res.contains("rm -rf /data/data/com.doordash.driverapp/$d; tar xzf"),
                "restore never rm's the live dir immediately before untar (the old destructive shape)");
        // force-stop must be REQUIRED (guarded), not `|| true` — never swap under a live writer.
        check(!res.contains("am force-stop com.doordash.driverapp || true"), "restore does not ignore a failed force-stop");

        // only the validated pkg is interpolated — a bad pkg throws at build, never reaches a shell
        boolean capThrew = false, resThrew = false;
        try { SessionMigrator.buildCaptureCommand("nodots"); } catch (SessionMigrator.SessionException e) { capThrew = true; }
        try { SessionMigrator.buildRestoreCommand("x;y"); } catch (SessionMigrator.SessionException e) { resThrew = true; }
        check(capThrew, "capture rejects bad pkg at build");
        check(resThrew, "restore rejects bad pkg at build");

        // capture()/restore() fail LOUDLY on a non-zero exit (never a silent no-op), surfacing the output
        SessionMigrator.Shell denied = command -> new SessionMigrator.Result(4, "no session dirs (never logged in?)");
        boolean loud = false;
        try { SessionMigrator.capture(denied, "com.doordash.driverapp"); }
        catch (SessionMigrator.SessionException e) { loud = e.getMessage().contains("never logged in"); }
        check(loud, "non-zero exit -> SessionException carrying the shell output");

        // exec exception (su absent) surfaces mentioning root
        SessionMigrator.Shell boom = command -> { throw new RuntimeException("su: not found"); };
        boolean surfaced = false;
        try { SessionMigrator.restore(boom, "com.doordash.driverapp"); }
        catch (SessionMigrator.SessionException e) { surfaced = e.getMessage().contains("root"); }
        check(surfaced, "su-absent surfaces as SessionException mentioning root");

        // clear-data command: pm clear the validated pkg, assert on "Success" in the output (pm clear can
        // exit 0 even on failure), reject a bad pkg at build.
        String clr = SessionMigrator.buildClearCommand("com.doordash.driverapp");
        check(clr.contains("pm clear com.doordash.driverapp"), "clear runs pm clear on the pkg");
        check(clr.contains("Success"), "clear asserts on the Success marker");
        boolean clrThrew = false;
        try { SessionMigrator.buildClearCommand("bad;rm"); } catch (SessionMigrator.SessionException e) { clrThrew = true; }
        check(clrThrew, "clear rejects bad pkg at build");
        SessionMigrator.Shell clrFail = command -> new SessionMigrator.Result(5, "Failed");
        boolean clrLoud = false;
        try { SessionMigrator.clearData(clrFail, "com.doordash.driverapp"); }
        catch (SessionMigrator.SessionException e) { clrLoud = true; }
        check(clrLoud, "clear fails loudly on non-Success");

        // happy path: the exact built command is what the shell receives, exit 0 returns the output
        final String[] seen = new String[1];
        SessionMigrator.Shell okCap = command -> { seen[0] = command; return new SessionMigrator.Result(0, "captured 125223 bytes"); };
        String out = SessionMigrator.capture(okCap, "com.doordash.driverapp");
        check(seen[0].equals(SessionMigrator.buildCaptureCommand("com.doordash.driverapp")), "capture runs the built command verbatim");
        check(out.equals("captured 125223 bytes"), "capture returns the shell output");

        System.out.println("SessionMigrator: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
