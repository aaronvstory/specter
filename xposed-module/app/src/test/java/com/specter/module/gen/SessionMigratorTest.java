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

        // capture command: app-AGNOSTIC — tars the WHOLE data dir ('.') minus junk/probe files, so it carries
        // whatever holds the login (databases incl. -wal/-shm, shared_prefs, files, no_backup, app_webview…)
        // for ANY app, not just a hardcoded couple of dirs.
        String cap = SessionMigrator.buildCaptureCommand("com.doordash.driverapp");
        check(cap.contains("tar czf $tmp") && cap.contains("mv -f $tmp " + tar), "capture builds a tmp then publishes it to the tarball path");
        check(cap.contains("-C /data/data/com.doordash.driverapp"), "capture -C into the data dir");
        // takes the whole dir contents ('.'), not a fixed subdir list
        check(cap.matches("(?s).*-C /data/data/com.doordash.driverapp .*\\. .*"), "capture tars the whole dir ('.')");
        // excludes every junk/device-specific top-level dir (deny-list, not allow-list)
        check(cap.contains("--exclude='./cache'"), "capture excludes cache");
        check(cap.contains("--exclude='./code_cache'"), "capture excludes code_cache");
        check(cap.contains("--exclude='./oat'"), "capture excludes oat");
        check(cap.contains("--exclude='./app_textures'"), "capture excludes app_textures");
        check(cap.contains("--exclude='./lib'"), "capture excludes the lib symlink");
        // excludes OUR probe artifacts so they never ride into a restored login
        check(cap.contains("--exclude='./files/.specter_*'"), "capture excludes .specter_* probe files");
        check(cap.contains("test -d /data/data/com.doordash.driverapp"), "capture guards app-not-installed");
        // it must NOT restrict to *.db (that would drop the -wal where the live token is) — we tar dirs.
        check(!cap.contains("*.db"), "capture takes whole dirs, not just .db (keeps -wal)");
        // capture stops the app first so the SQLite WAL isn't mid-write (coherent snapshot), and requires it.
        check(cap.contains("am force-stop com.doordash.driverapp"), "capture stops the app before tarring");
        check(cap.indexOf("am force-stop") < cap.indexOf("tar czf"), "capture stops BEFORE the tar");
        // ATOMIC + integrity: tar to a .tmp, tolerate ONLY exit 0/1, verify readable, then mv over the final.
        check(cap.contains(tar + ".tmp"), "capture writes to a .tmp first");
        check(cap.contains("[ $rc -eq 0 ] || [ $rc -eq 1 ]"), "capture accepts only tar exit 0/1 (fails on >=2)");
        check(cap.contains("tar tzf $tmp") && cap.indexOf("tar tzf $tmp") < cap.indexOf("mv -f $tmp"),
                "capture verifies the archive is readable before publishing it");
        check(cap.contains("mv -f $tmp " + tar), "capture atomically renames the tmp over the final path");
        // refuses to produce an empty archive (never-logged-in / no-data guard is real)
        check(cap.contains("empty archive"), "capture rejects an empty archive");

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
        // (2a) name traversal guard: refuse absolute paths or ../ components (extraction runs as root).
        check(res.contains("grep -qE '(^/|(^|/)[.][.](/|$))'"), "restore refuses absolute/../ entries");
        // (2b) TYPE guard: refuse symlink/hardlink entries — a name-only listing hides a symlink's target, so
        // a symlink entry would otherwise become a root-write primitive out of the sandbox.
        check(res.contains("tar tvzf " + tar) && res.contains("grep -qE '^[lh]'"),
                "restore refuses symlink/hardlink entries (type guard)");
        // (3) staging: extract to a staging dir UNDER /data/data (same fs => atomic swap), not the live dir.
        check(res.contains("tar xzf " + tar + " -C /data/data/.specter-restore-com.doordash.driverapp"),
                "restore extracts to a staging dir on the same filesystem as the data dir");
        // (4)/(5) WHOLE-DIR swap via two atomic renames with ONE rollback point. Move the live dir aside
        // (login preserved intact), move staging in; if that fails, put the original back.
        check(res.contains("mv /data/data/com.doordash.driverapp /data/data/.specter-old-com.doordash.driverapp"),
                "restore moves the whole live dir aside (atomic rename, login preserved)");
        check(res.contains("mv /data/data/.specter-restore-com.doordash.driverapp /data/data/com.doordash.driverapp"),
                "restore renames staging into place (atomic)");
        check(res.contains("mv /data/data/.specter-old-com.doordash.driverapp /data/data/com.doordash.driverapp"),
                "restore rolls the original back on a failed swap");
        check(res.contains("rolling back"), "restore has a rollback path");
        // the old login is deleted only AFTER the new one is live: the standalone `rm -rf <old>;` (its own
        // statement, no staging path alongside) must come after the successful mv-in.
        check(res.indexOf("mv /data/data/.specter-restore-com.doordash.driverapp /data/data/com.doordash.driverapp")
                        < res.indexOf("rm -rf /data/data/.specter-old-com.doordash.driverapp; "),
                "restore deletes the old login only after the new one is live");
        // no word-split ls loop remains
        check(!res.contains("for d in $entries"), "restore does NOT word-split an ls var");
        // force-stop must be REQUIRED (guarded), not `|| true` — never swap under a live writer.
        check(!res.contains("am force-stop com.doordash.driverapp || true"), "restore does not ignore a failed force-stop");

        // only the validated pkg is interpolated — a bad pkg throws at build, never reaches a shell
        boolean capThrew = false, resThrew = false;
        try { SessionMigrator.buildCaptureCommand("nodots"); } catch (SessionMigrator.SessionException e) { capThrew = true; }
        try { SessionMigrator.buildRestoreCommand("x;y"); } catch (SessionMigrator.SessionException e) { resThrew = true; }
        check(capThrew, "capture rejects bad pkg at build");
        check(resThrew, "restore rejects bad pkg at build");

        // capture()/restore() fail LOUDLY on a non-zero exit (never a silent no-op), surfacing the output
        SessionMigrator.Shell denied = command -> new SessionMigrator.Result(4, "no app-data dirs (never opened/logged in?)");
        boolean loud = false;
        try { SessionMigrator.capture(denied, "com.doordash.driverapp"); }
        catch (SessionMigrator.SessionException e) { loud = e.getMessage().contains("never opened/logged in"); }
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
        // Clean-switch guard: the wipe MUST be `pm clear` (resets to first-install — internal data,
        // internal cache, AND the external /sdcard/Android/data/<pkg> cache), not a partial `rm` that
        // would miss the external cache and leave prior-identity residue across a switch.
        check(!clr.contains("rm -rf") && !clr.contains("rm -r "),
                "clear uses pm clear (full first-install reset), never a partial rm that misses external cache");
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
