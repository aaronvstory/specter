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

    /** Upper bound on any single su command. A hung su daemon or an un-answered root prompt must NOT block the
     *  caller's worker thread forever (would strand the UI in a busy state). Generous enough for a slow
     *  pm-clear/cp; a real hang trips it. On timeout the process is force-killed and an exception is thrown so
     *  the caller surfaces the failure instead of spinning. */
    private static final long SU_TIMEOUT_MS = 60_000L;

    /** Default shell: spawn a real {@code su} process. */
    public static final class SuShell implements Shell {
        @Override public int run(String command, String stdinData) throws Exception {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            try {
                // Drain stdout+stderr concurrently while we feed stdin: if the command prints enough to a
                // pipe we never read, that pipe fills and su blocks forever (classic exec deadlock).
                Thread out = drain(p.getInputStream()), err = drain(p.getErrorStream());
                try (OutputStream os = p.getOutputStream()) {
                    // A command with no stdin (e.g. "am force-stop <pkg>") passes null — feed nothing, don't NPE.
                    if (stdinData != null) { os.write(stdinData.getBytes("UTF-8")); os.flush(); }
                }
                if (!awaitBounded(p)) throw new WriteException("su timed out after " + SU_TIMEOUT_MS + "ms: " + command);
                int code = p.exitValue();
                out.join(2000); err.join(2000);
                return code;
            } finally {
                p.destroy();
            }
        }

        @Override public String runCapture(String command) throws Exception {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", command});
            // A hung su must not block the reader loop forever: force-kill after the timeout so the read below
            // hits EOF instead of stalling.
            Thread killer = timeoutKiller(p);
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
                killer.interrupt();
                p.destroy();
            }
        }

        /** Wait for {@code p} up to {@link #SU_TIMEOUT_MS}; true if it exited, false on timeout. API-agnostic
         *  (Process.waitFor(long,TimeUnit) is API 26+, minSdk is 24), so we wait on a helper thread's join. */
        private static boolean awaitBounded(final Process p) {
            final Thread waiter = new Thread(() -> { try { p.waitFor(); } catch (InterruptedException ignored) {} });
            waiter.setDaemon(true);
            waiter.start();
            try { waiter.join(SU_TIMEOUT_MS); } catch (InterruptedException ignored) {}
            if (waiter.isAlive()) { p.destroy(); return false; }
            return true;
        }

        /** A daemon that force-kills {@code p} after the timeout (for the streaming read path). Interrupt it in
         *  finally when the command finished on its own. */
        private static Thread timeoutKiller(final Process p) {
            Thread t = new Thread(() -> {
                try { Thread.sleep(SU_TIMEOUT_MS); p.destroy(); } catch (InterruptedException ignored) {}
            });
            t.setDaemon(true);
            t.start();
            return t;
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

    /** Set the {@code timezone} field of an already-applied profile to {@code tzId}, leaving every other field
     *  untouched (identity is preserved). Reads the live JSON via su, patches the one key, writes it back
     *  atomically. Returns true if the profile existed and was updated. Used to align device timezone to the
     *  current IP's zone (which the phone number's area code can't know). Best-effort — false on any failure. */
    public static boolean setTimezone(Shell shell, String pkg, String tzId) {
        if (!validPkg(pkg) || tzId == null || tzId.isEmpty()) return false;
        try {
            String json = shell.runCapture("cat " + PROFILE_DIR + "/" + pkg + ".json 2>/dev/null");
            if (json == null || json.trim().isEmpty()) return false;
            // Parse with the un-hookable flat parser (NOT org.json — dodges the co-scope getString poisoning,
            // and org.json isn't on the pure-JVM test classpath). Profiles are flat string maps.
            java.util.LinkedHashMap<String, String> m = new java.util.LinkedHashMap<>();
            com.specter.module.SpoofLogic.parseFlatJson(json, m);
            m.remove(com.specter.module.SpoofLogic.TRUE_ANDROID_ID_KEY);   // shadow key, never written back
            if (m.isEmpty()) return false;
            if (tzId.equals(m.get("timezone"))) return true;   // already aligned
            m.put("timezone", tzId);
            write(shell, pkg, toFlatJson(m));
            return true;
        } catch (Throwable t) { return false; }
    }

    /** Serialize a flat string map to JSON with the same escaping the profile writer expects. */
    static String toFlatJson(java.util.Map<String, String> m) {
        StringBuilder b = new StringBuilder("{");
        boolean first = true;
        for (java.util.Map.Entry<String, String> e : m.entrySet()) {
            if (!first) b.append(',');
            first = false;
            b.append('"').append(esc(e.getKey())).append("\":\"").append(esc(e.getValue())).append('"');
        }
        return b.append('}').toString();
    }

    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  b.append("\\\""); break;
                case '\\': b.append("\\\\"); break;
                case '\n': b.append("\\n");  break;
                case '\r': b.append("\\r");  break;
                case '\t': b.append("\\t");  break;
                default:   b.append(c);
            }
        }
        return b.toString();
    }
}
