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

    /** Options FIRST (buffer, file, rotation), then the tag filter spec last — logcat wants that order.
     *  Rotate every 8 MB, keep 4 files (32 MB cap), silence every other tag. */
    public static String captureCommand() {
        return "mkdir -p /data/local/tmp/specter; "
                + "exec logcat -b main -f " + LOG_PATH + " -r 8192 -n 4 -s SpecterTrace:* specter:*";
    }

    /** Best-effort kill of the capture we spawned (proc.destroy() only kills the su wrapper, not the
     *  logcat child). Matches on the full unique LOG_PATH — the only process with that exact path on its
     *  cmdline is our own `logcat -f <LOG_PATH>` capture. (toybox pkill mis-parses a leading `-f ` in the
     *  pattern after `--`, so match the bare path, which is already unique.) */
    public static String killCommand() {
        return "pkill -f '" + LOG_PATH + "'";
    }
}
