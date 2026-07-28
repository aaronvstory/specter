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
     * only thing on the command line is the validated pkg. Creates the dir, writes the file from
     * stdin, and chmods it 644 so the target app can read it.
     */
    public static String buildShellCommand(String pkg) {
        if (!validPkg(pkg)) throw new WriteException("invalid package name: " + pkg);
        String path = PROFILE_DIR + "/" + pkg + ".json";
        return "mkdir -p " + PROFILE_DIR
                + " && cat > " + path
                + " && chmod 644 " + path;
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
            try (OutputStream os = p.getOutputStream()) {
                os.write(stdinData.getBytes("UTF-8"));
                os.flush();
            }
            return p.waitFor();
        }

        @Override public String runCapture(String command) throws Exception {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            try (java.io.InputStream is = p.getInputStream()) {
                byte[] buf = new byte[4096]; int n;
                while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            }
            p.waitFor();
            return new String(bos.toByteArray(), "UTF-8");
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
