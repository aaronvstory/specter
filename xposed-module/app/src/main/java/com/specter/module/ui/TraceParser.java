package com.specter.module.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure (Android-free) parser for the SpecterTrace capture ({@code diag.log}). The raw log is one line
 * per intercepted read — {@code "<date> <time> <pid> <tid> I SpecterTrace: <verb> <target>"} — and is
 * dominated by high-frequency repetition. This collapses the stream into a small, readable set of
 * per-signal rows: one {@link Row} per distinct (kind, target), with a hit count, so the Diagnostics screen
 * can show "the app read &lt;signal&gt; N times". Transient process ids are folded to {@code /proc/<pid>/} so
 * hundreds of thread reads become one counted row. Classification of those rows is {@link Coverage}'s job,
 * not this class's. No Android deps so it's unit-testable in the JVM harness.
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

    /** A trace line is dropped only when it has NO analytical value at all — the loader's auxv reads and the
     *  boot/runtime polling every app spams. This is volume control, NOT a judgement about identity: a row
     *  removed here can never be displayed, not even as an honest UNKNOWN, so deciding "is this
     *  device-identifying?" belongs to {@link Coverage}, which classifies what survives. */
    static boolean isNoise(String verb, String target) {
        if (target == null || target.isEmpty()) return true;
        // NOTE (codex): this filter DROPS a row before {@link Coverage} ever sees it, so anything removed
        // here can never be shown — not even as an honest UNKNOWN. Keep it to rows with no analytical value
        // AT ALL (volume control), and leave "is this identifying?" to Coverage. In particular /proc/self/maps
        // + status and unrecognised dlsym symbols are deliberately NOT dropped here: they're tamper-detection
        // surfaces the user should see classified, not silently swallowed.
        if (verb.equals("getauxval")) return true;   // auxv: kernel ABI vector, no device identity
        if (target.startsWith("/proc/")) {
            int slash = target.indexOf('/', 6);
            String seg = slash < 0 ? target.substring(6) : target.substring(6, slash);
            boolean allDigits = !seg.isEmpty();
            for (int i = 0; i < seg.length(); i++) if (!Character.isDigit(seg.charAt(i))) { allDigits = false; break; }
            // NOTE: the app's OWN /proc/<pid>/… is no longer dropped here. collapsePid() folds every pid into
            // one row anyway, so the volume argument is gone — and self-introspection includes maps/status,
            // which are tamper-detection surfaces worth showing. Coverage decides; we just collapse.
            // Scheduler bookkeeping on ANY pid is per-thread churn, not a device signal. A measured Cash App
            // run produced 69 distinct thread ids × ~5 leaves — enough distinct rows to fill the UI's cap and
            // push the real signals off the list. The leaf, not the pid, is what makes it noise.
            if (allDigits && slash > 0) {
                String leaf = target.substring(slash + 1);
                if (leaf.equals("timerslack_ns") || leaf.equals("oom_score_adj") || leaf.equals("oom_adj")
                        || leaf.equals("sched") || leaf.equals("cgroup")) return true;
            }
        }
        // Boot / runtime polling props every app spams, with no identity content at all.
        if (target.equals("sys.boot_completed") || target.equals("sys.usb.config")
                || target.startsWith("cache_key.") || target.equals("heapprofd.enable")) return true;
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
            String rest = line.substring(tag + "SpecterTrace: ".length()).trim();
            if (rest.isEmpty()) continue;
            int sp = rest.indexOf(' ');
            String verb = sp < 0 ? rest : rest.substring(0, sp);
            String target = sp < 0 ? "" : rest.substring(sp + 1).trim();
            if (isNoise(verb, target)) continue;
            Kind kind = kindOf(verb);
            target = collapsePid(target);
            // glGetStringi(GL_EXTENSIONS, i) is called once PER INDEX — ~100 rows differing only by the
            // index. Collapse the index so it reads as one signal ("the app enumerated GL extensions ×100").
            if (verb.equals("glGetStringi")) target = collapseTrailingIndex(target);
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

    /** Drop a trailing {@code " <digits>"} loop index, so a per-index query ({@code "0x1f03 0"},
     *  {@code "0x1f03 1"}, …) folds into one counted row. Leaves anything else untouched. */
    static String collapseTrailingIndex(String target) {
        if (target == null) return null;
        int sp = target.lastIndexOf(' ');
        if (sp <= 0 || sp == target.length() - 1) return target;
        for (int i = sp + 1; i < target.length(); i++) if (!Character.isDigit(target.charAt(i))) return target;
        return target.substring(0, sp);
    }

    /** Rewrite {@code /proc/<digits>/rest} to {@code /proc/<pid>/rest} so reads across many process/thread
     *  ids collapse into ONE counted row. The interesting fact is "the app read <leaf> 40 times", not forty
     *  near-identical rows differing only by a transient id — those alone can exhaust the UI's row cap and
     *  push real signals off the screen. The count still shows the volume. Non-numeric segments
     *  (/proc/cpuinfo, /proc/self/...) are untouched. */
    static String collapsePid(String target) {
        final String pre = "/proc/";
        if (target == null || !target.startsWith(pre)) return target;
        int slash = target.indexOf('/', pre.length());
        if (slash < 0) return target;
        String seg = target.substring(pre.length(), slash);
        if (seg.isEmpty()) return target;
        for (int i = 0; i < seg.length(); i++) if (!Character.isDigit(seg.charAt(i))) return target;
        return pre + "<pid>" + target.substring(slash);
    }

}
