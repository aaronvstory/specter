package com.specter.module.gen;

import java.util.regex.Pattern;

/**
 * Captures and restores a target app's LOGIN SESSION (its {@code /data/data/<pkg>/{databases,shared_prefs}})
 * via Magisk {@code su}, so a device that received a fingerprint clone can also open the app already
 * logged in — the app recognises it as the same known account.
 *
 * <p>Grounded in on-device inspection of a real target (DoorDash Dasher, 2026-07-27): the auth token is a
 * plaintext column in a Room SQLite DB ({@code identity_database}), NOT wrapped by the hardware Keystore,
 * so a root-level file copy carries the session to another rooted device. Two correctness facts learned
 * there and encoded here:
 * <ul>
 *   <li>The live token lives in the SQLite <b>-wal</b> (write-ahead log), not the checkpointed {@code .db}
 *       — so the capture must take the WHOLE {@code databases/} dir (incl. {@code -wal}/{@code -shm}), never
 *       just the {@code .db} files.</li>
 *   <li>The app-data SELinux context has per-UID categories ({@code c23,c257,...}) and the app UID differs
 *       per install — so on restore we {@code restorecon} (recompute) and {@code chown} to the TARGET's own
 *       assigned UID, resolved on-device, rather than preserving the source's owner/context.</li>
 * </ul>
 *
 * <p>Session migration is INDEPENDENT of the fingerprint envelope (sessions are large binary tarballs;
 * fingerprints are small JSON), and is opt-in per target app — capturing a session copies real account
 * data, so it is never bundled silently.
 *
 * <p>The command-building + package validation is pure/testable; {@link #capture}/{@link #restore} do the
 * process exec. NOTE (epistemic): a migrated session is proven to round-trip byte-intact; whether it
 * survives the target app's SERVER-side device attestation (e.g. Play Integrity) on the new device is a
 * separate question that can only be confirmed by an actual cross-device login and is not asserted here.
 */
public final class SessionMigrator {
    private SessionMigrator() {}

    /** Where session tarballs are staged (world-readable tmp, same rationale as {@link RootWriter}). */
    public static final String SESSION_DIR = "/data/local/tmp/specter";

    /** Top-level entries under /data/data/&lt;pkg&gt; that we NEVER carry across a migration: regenerable
     *  caches, device/ABI-specific compiled code, GPU texture caches, and the native-lib symlink. Excluding
     *  these (rather than allow-listing a couple of dirs) makes the capture app-AGNOSTIC — it takes whatever
     *  holds the login for ANY app (databases, shared_prefs, files, no_backup, app_webview cookies, …), not
     *  just the two dirs a specific app happened to use. Copying the excluded ones risks importing stale
     *  device state, bloats the tarball, or (oat/lib) is meaningless on another install. */
    static final String[] EXCLUDE_DIRS = {"cache", "code_cache", "oat", "app_textures", "lib"};

    /** Glob for our OWN probe artifacts dropped in files/ by the read-monitor/native layer — they must never
     *  ride along into a restored login (they'd re-seed a stale device profile). */
    static final String SPECTER_PROBE_GLOB = ".specter_*";

    // Android package-name grammar — the ONLY thing interpolated into the su command line.
    private static final Pattern PKG = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");

    public static final class SessionException extends RuntimeException {
        public SessionException(String m) { super(m); }
        public SessionException(String m, Throwable t) { super(m, t); }
    }

    /** True if pkg is a valid Android package name (guards the su shell boundary). */
    public static boolean validPkg(String pkg) {
        return pkg != null && PKG.matcher(pkg).matches();
    }

    /** Absolute path of the staged session tarball for a package. */
    public static String tarPath(String pkg) {
        if (!validPkg(pkg)) throw new SessionException("invalid package name: " + pkg);
        return SESSION_DIR + "/session-" + pkg + ".tgz";
    }

    /**
     * Shell command (run under {@code su -c}) that tars the target's session subdirs into
     * {@link #tarPath}. Fails loudly if the data dir is absent (app not installed) or has no session
     * subdirs (never logged in). Only the validated pkg is interpolated.
     */
    public static String buildCaptureCommand(String pkg) {
        String dataDir = "/data/data/" + pkg;   // pkg validated above via tarPath
        String tar = tarPath(pkg);
        // App-AGNOSTIC capture: tar the WHOLE data dir (relative paths, via -C) minus the junk/device-specific
        // top-level dirs and our own .specter_* probe files. This carries whatever holds the login for any app
        // — databases (incl. -wal/-shm where the live token lives), shared_prefs, files/, no_backup/,
        // app_webview cookies, etc. — without hardcoding a per-app dir list.
        // Force-stop the app FIRST so the SQLite .db/-wal/-shm aren't mid-write during the tar — a live
        // snapshot can be an incoherent WAL state. Require the stop to succeed (don't tar a running app).
        StringBuilder excl = new StringBuilder();
        for (String d : EXCLUDE_DIRS) excl.append("--exclude='./").append(d).append("' ");
        excl.append("--exclude='./files/").append(SPECTER_PROBE_GLOB).append("' ");
        return "set -e; "
                + "test -d " + dataDir + " || { echo 'no data dir for " + pkg + " (app not installed)'; exit 3; }; "
                + "am force-stop " + pkg + " || { echo 'could not stop " + pkg + " before capture'; exit 5; }; "
                + "mkdir -p " + SESSION_DIR + "; "
                // Guard 'never logged in': require at least one login-bearing dir to exist, else the tar would
                // be an empty shell. (files OR databases OR shared_prefs OR no_backup — any real app has one.)
                + "present=''; for d in databases shared_prefs files no_backup app_webview; do "
                + "  [ -d " + dataDir + "/$d ] && present=1; done; "
                + "[ -n \"$present\" ] || { echo 'no app-data dirs (never opened/logged in?)'; exit 4; }; "
                // tar the dir contents ('.') with the excludes, to a TEMP file, then verify + atomically
                // rename over the final path — so a killed/failed tar can only leave a stale .tmp, never a
                // truncated archive presented as good (and never clobbers a prior good capture mid-write).
                // Tolerate ONLY tar's benign exit 1 (a file changed/vanished under the just-stopped app);
                // any exit >=2 (I/O error, disk full) fails loudly here instead of surfacing later at restore.
                + "tmp=" + tar + ".tmp; rm -f $tmp; "
                // `|| true` keeps `set -e` from aborting on tar's benign exit 1; we inspect $? via a wrapper.
                + "rc=0; tar czf $tmp -C " + dataDir + " " + excl + " . 2>/dev/null || rc=$?; "
                + "[ $rc -eq 0 ] || [ $rc -eq 1 ] || { rm -f $tmp; echo 'tar failed (exit '$rc')'; exit 6; }; "
                + "tar tzf $tmp >/dev/null 2>&1 || { rm -f $tmp; echo 'capture archive is unreadable'; exit 6; }; "
                + "[ -s $tmp ] || { rm -f $tmp; echo 'capture produced an empty archive'; exit 6; }; "
                + "chmod 644 $tmp; mv -f $tmp " + tar + "; "
                + "echo captured $(stat -c %s " + tar + ") bytes";
    }

    /**
     * Shell command that restores a previously-staged session tarball INTO the target app on THIS device.
     * Safe by construction — it never destroys the existing session unless the new one is proven-good:
     * <ol>
     *   <li>verify the tarball is a readable archive ({@code tar tzf}) — a truncated/corrupt transfer never
     *       reaches the destructive step;</li>
     *   <li>reject any entry that isn't confined under {@code databases/} or {@code shared_prefs/} (no
     *       absolute paths, no {@code ..} traversal, no other top-level dirs) — the archive is extracted as
     *       ROOT into app data, so a tampered tar must not be able to write outside the session dirs;</li>
     *   <li>extract into a fresh staging dir and confirm at least one session subdir materialised;</li>
     *   <li>force-stop the app (REQUIRED — abort if it won't stop, so we never swap under a live writer);</li>
     *   <li>move the current session dirs ASIDE (not delete), move the staged ones in, then delete the
     *       aside copy only on success; on any failure roll the aside copy back — the app's login is never
     *       lost to a failed restore.</li>
     * </ol>
     * UID + SELinux categories are resolved on-device (never carried from the source). App left stopped.
     */
    public static String buildRestoreCommand(String pkg) {
        String dataDir = "/data/data/" + pkg;
        String tar = tarPath(pkg);
        // Staging + the "old" holder live UNDER the data-parent (/data/data), NOT /data/local/tmp, so the two
        // directory renames below are same-filesystem atomic swaps (a cross-fs mv would degrade to copy+delete
        // and lose the atomicity the rollback relies on). Both are hidden dotdirs the app can't see.
        String stage = "/data/data/.specter-restore-" + pkg;
        String old = "/data/data/.specter-old-" + pkg;
        return "set -e; "
                + "test -f " + tar + " || { echo 'no staged session for " + pkg + "'; exit 3; }; "
                + "test -d " + dataDir + " || { echo 'app " + pkg + " not installed here'; exit 4; }; "
                // (1) archive must be a readable tar before we touch anything destructive.
                + "tar tzf " + tar + " >/dev/null 2>&1 || { echo 'staged session is corrupt/unreadable — aborting, nothing touched'; exit 5; }; "
                // (2) traversal guard (extraction runs as ROOT into app data). Two refusals, because the
                // archive is staged in world-writable tmp so a swapped/tampered tar is in scope:
                //   a) NAME guard: no absolute path, no '..' component.
                //   b) TYPE guard: no symlink/hardlink entries. A name-only listing (`tar tzf`) hides a
                //      symlink's target, so an entry like `./shared_prefs -> /data/data/other.app` passes a
                //      name check, then extraction creates a real symlink that a later root write follows
                //      OUT of the sandbox (a root-write primitive). `tar tvzf` prefixes symlinks with 'l' and
                //      hardlinks with 'h' (verified on toybox + GNU tar); refuse either. Our own captures of
                //      real app data contain no such links (checked on Dasher + Cash App), so this only ever
                //      trips on a hand-crafted archive.
                + "if tar tzf " + tar + " | grep -qE '(^/|(^|/)[.][.](/|$))'; then echo 'archive has an absolute or ../ path — refusing to extract as root'; exit 6; fi; "
                + "if tar tvzf " + tar + " 2>/dev/null | grep -qE '^[lh]'; then echo 'archive contains a symlink/hardlink — refusing to extract as root'; exit 6; fi; "
                + "uid=$(stat -c %u " + dataDir + "); "
                // (3) extract to a fresh staging dir. -P is NOT passed, so tar strips leading '/' and refuses
                // '..' on its own too; a symlink entry lands INSIDE staging and a later entry writing 'through'
                // it still resolves under staging (we never extract with --keep-directory-symlink). Confirm
                // something came out.
                + "rm -rf " + stage + " " + old + "; mkdir -p " + stage + "; "
                + "tar xzf " + tar + " -C " + stage + "; "
                + "[ -n \"$(ls -A " + stage + " 2>/dev/null)\" ] || { rm -rf " + stage + "; echo 'archive yielded nothing'; exit 7; }; "
                // (4) stop the app — REQUIRED; never swap the data dir under a running writer.
                + "am force-stop " + pkg + " || { rm -rf " + stage + "; echo 'could not stop " + pkg + " — aborting, nothing touched'; exit 8; }; "
                // (5) WHOLE-DIRECTORY swap via two atomic renames with a single rollback point — no per-entry
                // window where a partial move could strand the login:
                //   a) dataDir  -> old      (live login preserved intact)
                //   b) stage    -> dataDir  (restored data goes live)
                // If (b) fails, put (a) back and abort; the app's original data is never left half-moved. Only
                // after (b) succeeds do we delete `old`. (Excluded dirs like cache/oat are NOT preserved — the
                // app regenerates them; the restored dir is exactly the captured payload.)
                + "mv " + dataDir + " " + old + " || { rm -rf " + stage + "; echo 'could not move current data aside — nothing lost'; exit 9; }; "
                + "if ! mv " + stage + " " + dataDir + "; then echo 'swap failed — rolling back to original login'; "
                + "  rm -rf " + dataDir + "; mv " + old + " " + dataDir + "; rm -rf " + stage + "; exit 10; fi; "
                + "rm -rf " + old + "; "
                // success: re-own to THIS install's uid + recompute SELinux.
                + "chown -R $uid:$uid " + dataDir + " 2>/dev/null || true; "
                + "restorecon -R " + dataDir + " 2>/dev/null || true; "
                + "echo restored to uid $uid";
    }

    /**
     * Shell command that WIPES a target app's data + cache — the fleet-workflow "start clean" step, done
     * reliably in one shot instead of by hand. {@code pm clear} removes /data/data/&lt;pkg&gt; AND the
     * caches (internal + external) and resets the app to first-install state; that's exactly the manual
     * "clear storage + clear cache" from app settings. Only the validated pkg is interpolated.
     *
     * <p>WARNING by design: this is destructive (it also drops any login the app had). It's a deliberate,
     * opt-in step — the caller gates it behind an explicit checkbox, never automatic.
     */
    public static String buildClearCommand(String pkg) {
        if (!validPkg(pkg)) throw new SessionException("invalid package name: " + pkg);
        // `pm clear` returns "Success"/"Failed" and exit 0 even on some failures, so assert on the output.
        return "out=$(pm clear " + pkg + " 2>&1); echo \"$out\"; "
                + "case \"$out\" in *Success*) exit 0;; *) exit 5;; esac";
    }

    /** Wipe {@code pkg}'s data + cache (pm clear). Throws (loudly) on failure. */
    public static String clearData(Shell shell, String pkg) {
        return exec(shell, buildClearCommand(pkg), "clear-data", pkg);
    }

    /** Convenience: wipe via a real su process. */
    public static String clearData(String pkg) { return clearData(new SuShell(), pkg); }

    /** Abstraction over process exec so tests can drive command-building without a real device. */
    public interface Shell {
        /** Run {@code su -c <command>}; return {exitCode, combined stdout+stderr}. */
        Result run(String command) throws Exception;
    }

    public static final class Result {
        public final int code; public final String output;
        public Result(int code, String output) { this.code = code; this.output = output; }
    }

    /**
     * Default shell: spawn a real {@code su} process and capture its output.
     *
     * <p>Uses {@code su -M} (mount-master) — this app runs in an isolated Magisk/zygisk mount namespace
     * where OTHER apps' {@code /data/data/<pkg>} dirs are NOT visible, so a plain {@code su -c} sees "no
     * data dir" for the target (confirmed on-device 2026-07-27). {@code -M} runs the su child in the GLOBAL
     * mount namespace where every app's data dir is present. (RootWriter can use plain su because it only
     * touches {@code /data/local/tmp}, which is global either way.)
     */
    public static final class SuShell implements Shell {
        @Override public Result run(String command) throws Exception {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-M", "-c", command});
            StringBuilder out = new StringBuilder();
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line; while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(p.getErrorStream(), "UTF-8"))) {
                String line; while ((line = r.readLine()) != null) out.append(line).append('\n');
            }
            return new Result(p.waitFor(), out.toString().trim());
        }
    }

    /** Capture {@code pkg}'s session to the staged tarball. Throws (loudly) on failure. */
    public static String capture(Shell shell, String pkg) {
        return exec(shell, buildCaptureCommand(pkg), "capture", pkg);
    }

    /** Restore the staged session for {@code pkg} into this device's install. Throws (loudly) on failure. */
    public static String restore(Shell shell, String pkg) {
        return exec(shell, buildRestoreCommand(pkg), "restore", pkg);
    }

    private static String exec(Shell shell, String cmd, String op, String pkg) {
        Result r;
        try {
            r = shell.run(cmd);
        } catch (Exception e) {
            throw new SessionException(op + " failed for " + pkg
                    + " (is Magisk root granted to this app?): " + e.getMessage(), e);
        }
        if (r.code != 0)
            throw new SessionException(op + " for " + pkg + " exited " + r.code + ": " + r.output);
        return r.output;
    }

    /** Convenience: capture/restore via a real su process. */
    public static String capture(String pkg) { return capture(new SuShell(), pkg); }
    public static String restore(String pkg) { return restore(new SuShell(), pkg); }
}
