package com.specter.module.ui;

/**
 * Pure (Android-free) builder for the diagnostics logcat-capture command, so it can be unit-tested off
 * the Service. The command streams the SpecterTrace + specter tags to a rotating file — logcat's own
 * -f/-r/-n do the write, no read-loop. Keep the tags in lockstep with what the hooks actually emit
 * (native {@code SpecterTrace}, Java {@code specter}).
 */
public final class DiagnosticsCmd {
    private DiagnosticsCmd() {}

    public static final String LOG_PATH = "/data/local/tmp/specter/diag.log";

    /** Rotate every 8 MB, keep 4 files (32 MB cap), silence every other tag. */
    public static String captureCommand() {
        return "mkdir -p /data/local/tmp/specter; "
                + "exec logcat -b main -s SpecterTrace:* specter:* -f " + LOG_PATH
                + " -r 8192 -n 4";
    }

    /** Best-effort kill of a lingering capture we spawned (su -c destroy only kills the wrapper). */
    public static String killCommand() {
        return "pkill -f 'logcat.*" + LOG_PATH + "'";
    }
}
