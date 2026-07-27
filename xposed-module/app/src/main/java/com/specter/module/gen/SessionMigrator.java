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

    /** The only session-bearing subdirs of an app's data. Cache/oat/code_cache are junk or device-specific
     *  and are deliberately excluded — copying them risks importing stale device state, not the login. */
    static final String[] SESSION_SUBDIRS = {"databases", "shared_prefs"};

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
        // -C into the data dir so the tar holds relative paths (databases/, shared_prefs/), then only add
        // the subdirs that actually exist (a target may have shared_prefs but an empty databases, etc.).
        // Force-stop the app FIRST so the SQLite .db/-wal/-shm aren't mid-write during the tar — a live
        // snapshot can be an incoherent WAL state. Require the stop to succeed (don't tar a running app).
        return "set -e; "
                + "test -d " + dataDir + " || { echo 'no data dir for " + pkg + " (app not installed)'; exit 3; }; "
                + "am force-stop " + pkg + " || { echo 'could not stop " + pkg + " before capture'; exit 5; }; "
                + "mkdir -p " + SESSION_DIR + "; "
                + "present=''; for d in " + join(SESSION_SUBDIRS) + "; do "
                + "  [ -d " + dataDir + "/$d ] && present=\"$present $d\"; done; "
                + "[ -n \"$present\" ] || { echo 'no session dirs (never logged in?)'; exit 4; }; "
                + "tar czf " + tar + " -C " + dataDir + " $present; "
                + "chmod 644 " + tar + "; "
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
        String stage = SESSION_DIR + "/restore-" + pkg;   // staging + aside live in the same world-tmp dir
        String subdirs = join(SESSION_SUBDIRS);
        return "set -e; "
                + "test -f " + tar + " || { echo 'no staged session for " + pkg + "'; exit 3; }; "
                + "test -d " + dataDir + " || { echo 'app " + pkg + " not installed here'; exit 4; }; "
                // (1) archive must be a readable tar before we touch anything destructive.
                + "tar tzf " + tar + " >/dev/null 2>&1 || { echo 'staged session is corrupt/unreadable — aborting, nothing touched'; exit 5; }; "
                // (2) every entry must stay under databases/ or shared_prefs/ (extraction runs as root). Any
                // line NOT matching that prefix — an absolute path, a ../ traversal, or another top-level
                // dir — trips the refusal. (grep -vE = lines that don't match; -q = just the exit status.)
                + "if tar tzf " + tar + " | grep -qvE '^(databases|shared_prefs)(/|$)'; then echo 'archive has entries outside the session dirs — refusing to extract as root'; exit 6; fi; "
                + "uid=$(stat -c %u " + dataDir + "); "
                // (3) extract to a clean staging dir and confirm something came out.
                + "rm -rf " + stage + "; mkdir -p " + stage + "; "
                + "tar xzf " + tar + " -C " + stage + "; "
                + "got=''; for d in " + subdirs + "; do [ -e " + stage + "/$d ] && got=1; done; "
                + "[ -n \"$got\" ] || { rm -rf " + stage + "; echo 'archive yielded no session dirs'; exit 7; }; "
                // (4) stop the app — REQUIRED; never swap session dirs under a running writer.
                + "am force-stop " + pkg + " || { rm -rf " + stage + "; echo 'could not stop " + pkg + " — aborting, nothing touched'; exit 8; }; "
                // (5) atomic-ish swap with rollback: move current aside, move staged in; on failure restore aside.
                + "aside=" + stage + ".aside; rm -rf $aside; mkdir -p $aside; "
                + "for d in " + subdirs + "; do [ -e " + dataDir + "/$d ] && mv " + dataDir + "/$d $aside/$d; done; "
                + "if ! ( for d in " + subdirs + "; do [ -e " + stage + "/$d ] && mv " + stage + "/$d " + dataDir + "/$d; done ); then "
                + "  echo 'swap failed — rolling back'; "
                + "  for d in " + subdirs + "; do rm -rf " + dataDir + "/$d; [ -e $aside/$d ] && mv $aside/$d " + dataDir + "/$d; done; "
                + "  rm -rf " + stage + " $aside; exit 9; fi; "
                // success: re-own to THIS install's uid, recompute SELinux, drop the aside copy + staging.
                + "for d in " + subdirs + "; do [ -e " + dataDir + "/$d ] && chown -R $uid:$uid " + dataDir + "/$d; done; "
                + "restorecon -R " + dataDir + " 2>/dev/null || true; "
                + "rm -rf " + stage + " $aside; "
                + "echo restored to uid $uid";
    }

    private static String join(String[] items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.length; i++) { if (i > 0) sb.append(' '); sb.append(items[i]); }
        return sb.toString();
    }

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
