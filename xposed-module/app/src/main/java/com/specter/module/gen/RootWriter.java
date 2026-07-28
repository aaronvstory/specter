package com.specter.module.gen;

import java.io.OutputStream;
import java.util.regex.Pattern;

/**
 * Writes a generated profile to {@code /data/local/tmp/specter/<pkg>.json} via Magisk {@code su},
 * so the app self-applies with NO PC. The hook (HookEntry) reads that exact path from inside the
 * target app's sandbox — /data/local/tmp is world-readable (0644), the only place a file written by
 * this UI app's uid is also readable by the target app's uid under SELinux. So the write MUST go
 * there (not the app's private filesDir), and only root can write it — hence su.
 *
 * The command-building + package validation is pure/testable; {@link #write} does the process exec.
 */
public final class RootWriter {
    private RootWriter() {}

    /** Where the hook reads per-app profiles. Must match HookEntry.PROFILE_DIR. */
    public static final String PROFILE_DIR = "/data/local/tmp/specter";

    // Android package-name grammar — the ONLY thing interpolated into the su command line.
    private static final Pattern PKG = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");

    public static final class WriteException extends RuntimeException {
        public WriteException(String m) { super(m); }
        public WriteException(String m, Throwable t) { super(m, t); }
    }

    /** True if pkg is a valid Android package name (guards the su shell boundary). */
    public static boolean validPkg(String pkg) {
        return pkg != null && PKG.matcher(pkg).matches();
    }

    /**
     * The shell command run under {@code su -c}. JSON is fed via stdin (never interpolated), so the
     * only thing on the command line is the validated pkg.
     *
     * ATOMIC: writes to a per-pkg {@code .tmp}, verifies it's non-empty, chmods it, then {@code mv}s it
     * over the final path (a same-directory rename is atomic on the device's filesystem). So a killed
     * {@code su}, a full disk, or an interrupted write can only leave a stale {@code .tmp} — the live
     * profile the hook reads is either the OLD complete file or the NEW complete file, never a truncated
     * one. Truncating the live file with {@code cat > final} first (the old behaviour) could leave the
     * target loading an empty/partial profile => real-value leak.
     */
    public static String buildShellCommand(String pkg) {
        if (!validPkg(pkg)) throw new WriteException("invalid package name: " + pkg);
        String path = PROFILE_DIR + "/" + pkg + ".json";
        String tmp = path + ".tmp";
        return "mkdir -p " + PROFILE_DIR
                + " && cat > " + tmp
                + " && [ -s " + tmp + " ]"          // non-empty, else fail (don't clobber the live file)
                + " && chmod 644 " + tmp
                + " && mv -f " + tmp + " " + path    // atomic same-dir rename
                + " || { rm -f " + tmp + "; exit 1; }";   // on any failure, drop the tmp and report failure
    }

    /** Abstraction over process exec so tests can drive it without a real device. */
    public interface Shell {
        /** Run {@code su -c <command>}, feed {@code stdinData}, return the exit code. */
        int run(String command, String stdinData) throws Exception;

        /** Run {@code su -c <command>} and return its stdout (for a status probe). Default returns "" so a
         *  single-method test fake / lambda still satisfies the interface; SuShell overrides it for real. */
        default String runCapture(String command) throws Exception { return ""; }
    }

    /** Default shell: spawn a real {@code su} process. */
    public static final class SuShell implements Shell {
        @Override public int run(String command, String stdinData) throws Exception {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            try {
                // Drain stdout+stderr concurrently while we feed stdin: if the command prints enough to a
                // pipe we never read, that pipe fills and su blocks forever (classic exec deadlock).
                Thread out = drain(p.getInputStream()), err = drain(p.getErrorStream());
                try (OutputStream os = p.getOutputStream()) {
                    os.write(stdinData.getBytes("UTF-8"));
                    os.flush();
                }
                int code = p.waitFor();
                out.join(2000); err.join(2000);
                return code;
            } finally {
                p.destroy();
            }
        }

        @Override public String runCapture(String command) throws Exception {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            try {
                Thread err = drain(p.getErrorStream());   // drain stderr so it can't deadlock the read below
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                try (java.io.InputStream is = p.getInputStream()) {
                    byte[] buf = new byte[4096]; int n;
                    while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
                }
                p.waitFor();
                err.join(2000);
                return new String(bos.toByteArray(), "UTF-8");
            } finally {
                p.destroy();
            }
        }

        /** Spawn a daemon thread that reads {@code is} to EOF and discards it — just to keep the pipe empty. */
        private static Thread drain(final java.io.InputStream is) {
            Thread t = new Thread(() -> {
                try { byte[] b = new byte[4096]; while (is.read(b) != -1) { /* discard */ } }
                catch (Exception ignored) {}
            });
            t.setDaemon(true);
            t.start();
            return t;
        }
    }

    /**
     * Write {@code json} as the profile for {@code pkg}. Throws WriteException (loudly — never a
     * silent no-op) if su is denied/absent or the write fails, so the UI can surface it.
     */
    public static void write(Shell shell, String pkg, String json) {
        String cmd = buildShellCommand(pkg);
        int code;
        try {
            code = shell.run(cmd, json);
        } catch (Exception e) {
            throw new WriteException("su write failed for " + pkg
                    + " (is Magisk root granted to this app?): " + e.getMessage(), e);
        }
        if (code != 0)
            throw new WriteException("su write for " + pkg + " exited " + code
                    + " — root likely denied; grant this app in Magisk.");
    }

    /** Convenience: write via a real su process. */
    public static void write(String pkg, String json) { write(new SuShell(), pkg, json); }
}
