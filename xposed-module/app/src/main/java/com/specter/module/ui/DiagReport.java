package com.specter.module.ui;

import java.util.List;

/**
 * Pure (Android-free) builder for the live-trace coverage report — a plain-text audit of what a scoped app
 * READ and whether Specter protects each signal (spoofed / leaking / unclassified / non-identifying).
 * Split out from
 * {@link DiagnosticsActivity} (which extends Activity) so it's unit-testable in the JVM harness. The
 * Activity's Export button writes this to Download/Specter.
 */
public final class DiagReport {
    private DiagReport() {}

    public static String build(List<TraceParser.Row> rows) {
        StringBuilder sb = new StringBuilder();
        sb.append("Specter live-trace coverage report\n");
        sb.append("==================================\n\n");
        if (rows == null || rows.isEmpty()) { sb.append("(no signals captured)\n"); return sb.toString(); }
        int spoofed = 0, leaking = 0, noise = 0, unknown = 0, hits = 0;
        for (TraceParser.Row r : rows) {
            hits += r.count;
            switch (Coverage.of(r.verb, r.target)) {
                case SPOOFED: spoofed++; break;
                case LEAK: leaking++; break;
                case NOISE: noise++; break;
                default: unknown++; break;
            }
        }
        sb.append(rows.size()).append(" distinct signals · ").append(hits).append(" reads\n");
        // Mirrors the on-screen verdict: never claim a clean sweep while reads remain unclassified.
        sb.append(leaking > 0 ? leaking + " IDENTIFIER(S) STILL REAL"
                : unknown > 0 ? "No known leaks (" + unknown + " unclassified)"
                : "No identifiers leaked").append('\n');
        sb.append(spoofed).append(" spoofed · ").append(leaking).append(" leaking · ")
          .append(unknown).append(" unclassified · ").append(noise).append(" non-identifying\n\n");
        appendGroup(sb, "SPOOFED", Coverage.State.SPOOFED, rows);
        appendGroup(sb, "LEAKING (identifying, not spoofed)", Coverage.State.LEAK, rows);
        appendGroup(sb, "UNCLASSIFIED", Coverage.State.UNKNOWN, rows);
        // Listed last and clearly labelled: kept for auditability, but never part of the verdict.
        appendGroup(sb, "NON-IDENTIFYING (fonts, libs, own /proc — not a leak)", Coverage.State.NOISE, rows);
        return sb.toString();
    }

    private static void appendGroup(StringBuilder sb, String name, Coverage.State state, List<TraceParser.Row> rows) {
        boolean any = false;
        for (TraceParser.Row r : rows) if (Coverage.of(r.verb, r.target) == state) { any = true; break; }
        if (!any) return;
        sb.append("-- ").append(name).append(" --\n");
        for (TraceParser.Row r : rows) {
            if (Coverage.of(r.verb, r.target) != state) continue;
            sb.append('[').append(kindTag(r.kind)).append("] ").append(r.target);
            if (r.count > 1) sb.append(" (x").append(r.count).append(')');
            sb.append('\n');
        }
        sb.append('\n');
    }

    private static String kindTag(TraceParser.Kind kind) {
        switch (kind) {
            case PROP: return "prop ";
            case FILE: return "file ";
            case STAT: return "stat ";
            default:   return "other";
        }
    }
}
