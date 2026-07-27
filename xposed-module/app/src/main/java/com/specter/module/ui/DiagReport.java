package com.specter.module.ui;

import java.util.List;

/**
 * Pure (Android-free) builder for the live-trace coverage report — a plain-text audit of what a scoped app
 * READ and whether Specter protects each signal (spoofed / real / unknown). Split out from
 * {@link DiagnosticsActivity} (which extends Activity) so it's unit-testable in the JVM harness. The
 * Activity's Export button writes this to /sdcard/Download.
 */
public final class DiagReport {
    private DiagReport() {}

    public static String build(List<TraceParser.Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("Specter live-trace coverage report\n");
        sb.append("==================================\n\n");
        if (rows == null || rows.isEmpty()) { sb.append("(no signals captured)\n"); return sb.toString(); }
        int spoofed = 0, real = 0, unknown = 0, hits = 0;
        for (TraceParser.Row r : rows) {
            hits += r.count;
            switch (Coverage.of(r.verb, r.target)) {
                case SPOOFED: spoofed++; break;
                case REAL: real++; break;
                default: unknown++; break;
            }
        }
        sb.append(rows.size()).append(" distinct signals · ").append(hits).append(" reads\n");
        sb.append(spoofed).append(" spoofed · ").append(real).append(" real (non-identifying) · ")
          .append(unknown).append(" unknown\n\n");
        appendGroup(sb, "PROPERTIES", TraceParser.Kind.PROP, rows);
        appendGroup(sb, "FILES", TraceParser.Kind.FILE, rows);
        appendGroup(sb, "STAT / ACCESS", TraceParser.Kind.STAT, rows);
        appendGroup(sb, "OTHER", TraceParser.Kind.OTHER, rows);
        return sb.toString();
    }

    private static void appendGroup(StringBuilder sb, String name, TraceParser.Kind kind, List<TraceParser.Row> rows) {
        boolean any = false;
        for (TraceParser.Row r : rows) if (r.kind == kind) { any = true; break; }
        if (!any) return;
        sb.append("-- ").append(name).append(" --\n");
        for (TraceParser.Row r : rows) {
            if (r.kind != kind) continue;
            Coverage.State c = Coverage.of(r.verb, r.target);
            String tag = c == Coverage.State.SPOOFED ? "[spoofed]" : c == Coverage.State.REAL ? "[real]   " : "[unknown]";
            sb.append(tag).append(' ').append(r.target);
            if (r.count > 1) sb.append(" (x").append(r.count).append(')');
            sb.append('\n');
        }
        sb.append('\n');
    }
}
