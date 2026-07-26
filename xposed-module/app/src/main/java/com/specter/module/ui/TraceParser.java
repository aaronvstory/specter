package com.specter.module.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure (Android-free) parser for the SpecterTrace capture ({@code diag.log}). The raw log is one line
 * per intercepted read — {@code "<date> <time> <pid> <tid> I SpecterTrace: <verb> <target>"} — and is
 * dominated by high-frequency noise ({@code /proc/<pid>/status} self-reads, {@code sys.boot_completed}
 * polling) that says nothing about fingerprinting. This collapses the stream into a small, readable set
 * of per-signal rows: one {@link Row} per distinct (verb, target), with a hit count, so the Diagnostics
 * screen can show "the app read <signal> N times" grouped by kind — exactly the "what does the target
 * grab" view the user asked for. No Android deps so it's unit-testable in the JVM harness.
 */
public final class TraceParser {
    private TraceParser() {}

    /** Kind of thing the app read, for grouping in the UI. */
    public enum Kind { PROP, FILE, STAT, OTHER }

    public static final class Row {
        public final Kind kind;
        public final String verb;    // the raw trace verb: open/openat/prop/stat/access/...
        public final String target;  // the prop key or file path
        public int count;            // how many times it was read in the captured window
        Row(Kind kind, String verb, String target) { this.kind = kind; this.verb = verb; this.target = target; }
    }

    /** A trace line is noise if it reveals nothing about device fingerprinting: process self-introspection,
     *  the linker/loader's own churn (getauxval/dlsym, loading system libs+framework jars+ART images), or
     *  the ubiquitous boot-completed / debug-prop polling every app spams. These swamp the log and would
     *  bury the handful of lines that actually identify the device. */
    static boolean isNoise(String verb, String target, String callerPid) {
        if (target == null || target.isEmpty()) return true;
        // Loader/linker internals — not device signals: auxv reads, symbol lookups.
        if (verb.equals("getauxval") || verb.equals("dlsym")) return true;
        // /proc/self/... — the app reading its OWN process; introspection, not a device signal.
        if (target.startsWith("/proc/self/")) return true;
        if (target.startsWith("/proc/")) {
            int slash = target.indexOf('/', 6);
            String seg = slash < 0 ? target.substring(6) : target.substring(6, slash);
            boolean allDigits = !seg.isEmpty();
            for (int i = 0; i < seg.length(); i++) if (!Character.isDigit(seg.charAt(i))) { allDigits = false; break; }
            // /proc/<pid>/... — filter ONLY the app's own pid (self-introspection). A read of ANOTHER
            // process's /proc entry (/proc/<otherPid>/cmdline etc.) is app-enumeration — a real
            // fingerprinting signal we WANT to surface. /proc/cpuinfo, /proc/version (non-digit) are kept.
            if (allDigits && callerPid != null && seg.equals(callerPid)) return true;
        }
        // Library / framework loading: dlopen of system libs, .jar/.art/.oat/.vdex, and the lib dirs
        // themselves. High volume, zero identity content.
        if (target.endsWith(".so") || target.endsWith(".jar") || target.endsWith(".art")
                || target.endsWith(".oat") || target.endsWith(".vdex") || target.endsWith(".apk")
                || target.equals("/system/lib64") || target.equals("/system/lib")
                || target.equals("/system_ext/lib64") || target.equals("/vendor/lib64")
                || target.equals("/system")) return true;
        // Boot / runtime polling props that every app spams and that carry no identity.
        if (target.equals("sys.boot_completed") || target.equals("sys.usb.config")
                || target.startsWith("cache_key.") || target.startsWith("debug.")
                || target.startsWith("vendor.debug.") || target.equals("heapprofd.enable")) return true;
        return false;
    }

    static Kind kindOf(String verb) {
        if (verb.equals("prop")) return Kind.PROP;
        if (verb.equals("open") || verb.equals("openat") || verb.equals("fopen")) return Kind.FILE;
        if (verb.equals("stat") || verb.equals("lstat") || verb.equals("access") || verb.equals("fstatat")
                || verb.equals("faccessat") || verb.equals("statx") || verb.equals("newfstatat")) return Kind.STAT;
        return Kind.OTHER;
    }

    /**
     * Parse the raw capture into deduped, counted signal rows (insertion order = first-seen order), with
     * noise dropped. {@code maxRows} caps the result so a huge log can't OOM the UI — excess distinct
     * signals beyond the cap are dropped (the first-seen ones are kept; a UI note should say so).
     */
    public static List<Row> parse(String raw, int maxRows) {
        Map<String, Row> byKey = new LinkedHashMap<>();
        if (raw == null) return new ArrayList<>();
        for (String line : raw.split("\n")) {
            int tag = line.indexOf("SpecterTrace: ");
            if (tag < 0) continue;
            // Extract the caller pid from the logcat prefix ("<date> <time> <pid> <tid> I SpecterTrace:")
            // so isNoise can filter the app's OWN /proc/<pid> reads while KEEPING reads of other pids
            // (app-enumeration is a real signal). Prefix format is stable across the capture.
            String callerPid = pidOf(line, tag);
            String rest = line.substring(tag + "SpecterTrace: ".length()).trim();
            if (rest.isEmpty()) continue;
            int sp = rest.indexOf(' ');
            String verb = sp < 0 ? rest : rest.substring(0, sp);
            String target = sp < 0 ? "" : rest.substring(sp + 1).trim();
            if (isNoise(verb, target, callerPid)) continue;
            Kind kind = kindOf(verb);
            // Dedup by (kind, target), NOT (verb, target): the user cares that the app STAT'd a path, not
            // which syscall variant (stat vs fstatat vs newfstatat) it used — those would otherwise render
            // as identical-looking duplicate rows. Counts across variants sum into the one row.
            String key = kind + " " + target;
            Row r = byKey.get(key);
            if (r == null) {
                if (byKey.size() >= maxRows) continue;
                r = new Row(kind, verb, target);
                byKey.put(key, r);
            }
            r.count++;
        }
        return new ArrayList<>(byKey.values());
    }

    /** Pull the caller pid from a logcat line's prefix: "&lt;date&gt; &lt;time&gt; &lt;pid&gt; &lt;tid&gt; I SpecterTrace:".
     *  {@code tagIdx} is the index of "SpecterTrace: " on that line. Returns the pid token (3rd
     *  whitespace field) or null if the prefix isn't the expected shape (then no self-pid filtering). */
    static String pidOf(String line, int tagIdx) {
        // The token just before "<tid> I SpecterTrace:" — walk back over: SpecterTrace tag, "I", tid, pid.
        String pre = line.substring(0, tagIdx).trim();     // "<date> <time> <pid> <tid> I"
        String[] parts = pre.split("\\s+");
        // Expected tail: ... <pid> <tid> <level>. pid is 3rd from the end.
        if (parts.length < 3) return null;
        String pid = parts[parts.length - 3];
        for (int i = 0; i < pid.length(); i++) if (!Character.isDigit(pid.charAt(i))) return null;
        return pid.isEmpty() ? null : pid;
    }
}
