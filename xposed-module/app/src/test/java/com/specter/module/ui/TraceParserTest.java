package com.specter.module.ui;

import java.util.List;

/** JVM self-test for TraceParser: noise filtering, dedup+count, kind classification, cap. No framework. */
public final class TraceParserTest {
    static int fails = 0;
    static void check(boolean cond, String msg) { if (!cond) { System.out.println("FAIL: " + msg); fails++; } }

    public static void main(String[] args) {
        // A realistic capture: self-proc noise + boot polling (must drop) mixed with real device reads.
        String raw = String.join("\n",
                "07-26 13:15:36.353 12336 12336 I SpecterTrace: open /proc/12336/statm",
                "07-26 13:15:36.352 12336 12336 I SpecterTrace: open /proc/12336/status",
                "07-26 13:15:40.422 12336 12613 I SpecterTrace: prop sys.boot_completed",
                "07-26 13:15:41.000 12336 12336 I SpecterTrace: prop ro.product.model",
                "07-26 13:15:41.010 12336 12336 I SpecterTrace: prop ro.product.model",   // dup -> count 2
                "07-26 13:15:42.000 12336 12336 I SpecterTrace: open /proc/cpuinfo",       // real signal, keep
                "07-26 13:15:43.000 12336 12336 I SpecterTrace: stat /system/bin/su",
                "07-26 13:15:44.000 12336 12336 I SpecterTrace: prop ro.build.version.sdk",
                "unrelated logcat line with no tag",
                "07-26 13:15:45.000 12336 12336 I SpecterTrace: open /proc/self/maps",      // self -> noise
                "07-26 13:15:46.000 12336 12336 I SpecterTrace: getauxval 6",               // loader noise
                "07-26 13:15:46.100 12336 12336 I SpecterTrace: dlsym mmap",                // loader noise
                "07-26 13:15:46.200 12336 12336 I SpecterTrace: open /system/lib64/libc.so",// lib load noise
                "07-26 13:15:46.300 12336 12336 I SpecterTrace: stat /system/framework/x.jar", // jar noise
                "07-26 13:15:46.400 12336 12336 I SpecterTrace: prop vendor.debug.egl.x",   // debug prop noise
                "07-26 13:15:46.500 12336 12336 I SpecterTrace: fstatat /system/bin/su",    // dup of stat su -> STAT
                "07-26 13:15:47.000 12336 12336 I SpecterTrace: open /proc/9999/cmdline");  // OTHER pid -> app-enum signal, KEEP

        List<TraceParser.Row> rows = TraceParser.parse(raw, 100);

        // The parser no longer decides what's IDENTIFYING — it only drops rows with no analytical value at
        // all (getauxval, boot polling) and collapses transient pids. Everything else reaches Coverage, which
        // classifies it. Dropping a row here would make it invisible even as an honest UNKNOWN (codex).
        check(find(rows, "/proc/<pid>/statm") != null, "own-pid /proc collapsed, not dropped");
        // App-enumeration is still SURFACED — the pid is just collapsed so N processes read as one counted row.
        check(find(rows, "/proc/<pid>/cmdline") != null, "OTHER pid /proc kept (app-enumeration signal)");
        check(find(rows, "/system/lib64/libc.so") != null, "lib load kept for Coverage to classify");
        check(Coverage.of("open", "/system/lib64/libc.so") == Coverage.State.NOISE, "...and it classifies NOISE");
        check(find(rows, "6") == null, "getauxval loader noise dropped");
        check(find(rows, "mmap") != null, "dlsym kept — Coverage decides (unknown symbols stay visible)");
        check(Coverage.of("dlsym", "mmap") == Coverage.State.UNKNOWN, "unrecognised dlsym is UNKNOWN, not hidden");
        TraceParser.Row suRow = find(rows, "/system/bin/su");
        check(suRow != null && suRow.count == 2, "stat+fstatat of su merged into one STAT row, count 2");

        TraceParser.Row model = find(rows, "ro.product.model");
        check(model != null && model.count == 2, "ro.product.model deduped with count 2");
        check(model != null && model.kind == TraceParser.Kind.PROP, "prop classified PROP");

        check(find(rows, "/proc/cpuinfo") != null, "/proc/cpuinfo kept (not pid-noise)");
        check(find(rows, "sys.boot_completed") == null, "boot_completed dropped");
        // /proc/self/maps is a tamper-detection surface: it must reach the screen as UNKNOWN, NOT be hidden.
        check(find(rows, "/proc/self/maps") != null, "/proc/self/maps kept (tamper-detection surface)");
        check(Coverage.of("open", "/proc/self/maps") == Coverage.State.UNKNOWN, "...and shows as UNKNOWN");

        TraceParser.Row su = find(rows, "/system/bin/su");
        check(su != null && su.kind == TraceParser.Kind.STAT, "stat classified STAT");

        // Cap: distinct signals beyond maxRows are dropped (first-seen kept).
        List<TraceParser.Row> capped = TraceParser.parse(raw, 2);
        check(capped.size() == 2, "cap honored, got " + capped.size());
        check(capped.get(0).target.equals("/proc/<pid>/statm"), "first-seen kept under cap, got "
                + capped.get(0).target);

        // Robustness: null/empty never throw.
        check(TraceParser.parse(null, 10).isEmpty(), "null raw -> empty");
        check(TraceParser.parse("", 10).isEmpty(), "empty raw -> empty");

        // Per-thread /proc reads collapse into ONE counted row. A measured Cash App run produced 69 distinct
        // thread ids; left un-collapsed they alone fill the UI's 400-row cap and push real signals off list.
        StringBuilder threads = new StringBuilder();
        for (int tid = 20000; tid < 20060; tid++) {
            threads.append("07-26 13:15:36.000 999 999 I SpecterTrace: fopen /proc/").append(tid).append("/comm\n");
            threads.append("07-26 13:15:36.000 999 999 I SpecterTrace: open /proc/").append(tid).append("/timerslack_ns\n");
        }
        threads.append("07-26 13:15:36.000 999 999 I SpecterTrace: prop ro.product.model\n");
        List<TraceParser.Row> tr = TraceParser.parse(threads.toString(), 400);
        check(tr.size() == 2, "60 thread ids collapse to 1 comm row + the real prop, got " + tr.size());
        TraceParser.Row comm = find(tr, "/proc/<pid>/comm");
        check(comm != null && comm.count == 60, "collapsed row keeps the full count");
        check(find(tr, "/proc/<pid>/timerslack_ns") == null, "scheduler bookkeeping dropped as noise");
        check(find(tr, "ro.product.model") != null, "the real signal survives the thread churn");
        // The collapsed form must still classify (it reaches Coverage as-is).
        check(Coverage.of("open", "/proc/<pid>/timerslack_ns") == Coverage.State.NOISE, "collapsed form classifies");
        check(TraceParser.collapsePid("/proc/cpuinfo").equals("/proc/cpuinfo"), "non-numeric /proc untouched");
        check(TraceParser.collapsePid("/proc/self/maps").equals("/proc/self/maps"), "/proc/self untouched");

        // glGetStringi(GL_EXTENSIONS, i) is called once per index — collapse the index into one row.
        StringBuilder gl = new StringBuilder();
        for (int i = 0; i < 100; i++)
            gl.append("07-26 13:15:36.000 999 999 I SpecterTrace: glGetStringi 0x1f03 ").append(i).append('\n');
        List<TraceParser.Row> glRows = TraceParser.parse(gl.toString(), 400);
        check(glRows.size() == 1, "100 GL extension indices collapse to one row, got " + glRows.size());
        check(glRows.get(0).target.equals("0x1f03") && glRows.get(0).count == 100, "collapsed GL row keeps count");
        check(Coverage.of(glRows.get(0).verb, glRows.get(0).target) == Coverage.State.SPOOFED,
                "collapsed GL row still reads as SPOOFED (the extension list IS rewritten)");
        check(TraceParser.collapseTrailingIndex("ro.product.model").equals("ro.product.model"), "no trailing index");

        if (fails == 0) System.out.println("ALL PASS (TraceParser)");
        else { System.out.println(fails + " FAILURE(S)"); System.exit(1); }
    }

    static TraceParser.Row find(List<TraceParser.Row> rows, String target) {
        for (TraceParser.Row r : rows) if (r.target.equals(target)) return r;
        return null;
    }
}
