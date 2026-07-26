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

        // 5 distinct real signals survive: ro.product.model, /proc/cpuinfo, /system/bin/su,
        // ro.build.version.sdk, and /proc/9999/cmdline (reading ANOTHER process = app-enumeration, kept).
        // The caller's own /proc/12336/* is dropped as self-introspection. The stat + fstatat of
        // /system/bin/su collapse to ONE STAT row (dedup is by kind+target).
        check(rows.size() == 5, "expected 5 signal rows, got " + rows.size());
        check(find(rows, "/proc/12336/statm") == null, "own pid /proc dropped (self-introspection)");
        check(find(rows, "/proc/12336/status") == null, "own pid /proc dropped 2");
        check(find(rows, "/proc/9999/cmdline") != null, "OTHER pid /proc kept (app-enumeration signal)");
        check(find(rows, "/system/lib64/libc.so") == null, ".so lib load dropped");
        check(find(rows, "6") == null, "getauxval loader noise dropped");
        check(find(rows, "mmap") == null, "dlsym loader noise dropped");
        TraceParser.Row suRow = find(rows, "/system/bin/su");
        check(suRow != null && suRow.count == 2, "stat+fstatat of su merged into one STAT row, count 2");

        TraceParser.Row model = find(rows, "ro.product.model");
        check(model != null && model.count == 2, "ro.product.model deduped with count 2");
        check(model != null && model.kind == TraceParser.Kind.PROP, "prop classified PROP");

        check(find(rows, "/proc/cpuinfo") != null, "/proc/cpuinfo kept (not pid-noise)");
        check(find(rows, "/proc/12336/status") == null, "self-proc pid dir dropped");
        check(find(rows, "sys.boot_completed") == null, "boot_completed dropped");
        check(find(rows, "/proc/self/maps") == null, "/proc/self dropped");

        TraceParser.Row su = find(rows, "/system/bin/su");
        check(su != null && su.kind == TraceParser.Kind.STAT, "stat classified STAT");

        // Cap: distinct signals beyond maxRows are dropped (first-seen kept).
        List<TraceParser.Row> capped = TraceParser.parse(raw, 2);
        check(capped.size() == 2, "cap honored, got " + capped.size());
        check(capped.get(0).target.equals("ro.product.model"), "first-seen kept under cap");

        // Robustness: null/empty never throw.
        check(TraceParser.parse(null, 10).isEmpty(), "null raw -> empty");
        check(TraceParser.parse("", 10).isEmpty(), "empty raw -> empty");

        // pidOf: extract caller pid from the logcat prefix (3rd-from-end token before the tag).
        String l = "07-26 13:15:36.353 12336 12340 I SpecterTrace: open /proc/cpuinfo";
        check("12336".equals(TraceParser.pidOf(l, l.indexOf("SpecterTrace: "))), "pidOf extracts caller pid");
        check(TraceParser.pidOf("garbage SpecterTrace: x", "garbage SpecterTrace: x".indexOf("SpecterTrace: ")) == null,
                "pidOf null on malformed prefix");

        if (fails == 0) System.out.println("ALL PASS (TraceParser)");
        else { System.out.println(fails + " FAILURE(S)"); System.exit(1); }
    }

    static TraceParser.Row find(List<TraceParser.Row> rows, String target) {
        for (TraceParser.Row r : rows) if (r.target.equals(target)) return r;
        return null;
    }
}
