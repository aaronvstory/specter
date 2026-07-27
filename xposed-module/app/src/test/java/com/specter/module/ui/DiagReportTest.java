package com.specter.module.ui;

import java.util.ArrayList;
import java.util.List;

/** JVM self-test for DiagReport.build: summary counts + per-signal spoofed/real tags. */
public final class DiagReportTest {
    static int fails = 0;
    static void check(boolean c, String m) { if (!c) { System.out.println("FAIL: " + m); fails++; } }

    public static void main(String[] args) {
        // Build a trace from lines the parser understands, then report on the parsed rows.
        String raw = String.join("\n",
                "07-26 13:00:00.000 100 100 I SpecterTrace: prop ro.product.model",
                "07-26 13:00:00.001 100 100 I SpecterTrace: prop ro.product.model",  // -> count 2, spoofed
                "07-26 13:00:00.002 100 100 I SpecterTrace: prop ro.arch",            // real
                "07-26 13:00:00.003 100 100 I SpecterTrace: open /proc/cpuinfo",      // spoofed file
                "07-26 13:00:00.004 100 100 I SpecterTrace: prop some.random.prop");  // unknown
        List<TraceParser.Row> rows = TraceParser.parse(raw, 100);
        String rep = DiagReport.build(rows);

        check(rep.contains("Specter live-trace coverage report"), "has title");
        check(rep.contains("distinct signals"), "has summary line");
        // 2 spoofed (model, cpuinfo), 1 real (arch), 1 unknown (random)
        check(rep.contains("2 spoofed"), "2 spoofed in summary: \n" + rep);
        check(rep.contains("1 real"), "1 real in summary");
        check(rep.contains("1 unknown"), "1 unknown in summary");
        check(rep.contains("[spoofed] ro.product.model (x2)"), "model row tagged spoofed with count");
        check(rep.contains("[real]") && rep.contains("ro.arch"), "arch row tagged real");
        check(rep.contains("[spoofed] /proc/cpuinfo"), "cpuinfo file tagged spoofed");
        check(rep.contains("[unknown]") && rep.contains("some.random.prop"), "random row tagged unknown");
        check(rep.contains("-- PROPERTIES --") && rep.contains("-- FILES --"), "grouped by kind");

        // Empty input is graceful.
        check(DiagReport.build(new ArrayList<>()).contains("no signals"), "empty -> graceful");
        check(DiagReport.build(null).contains("no signals"), "null -> graceful");

        if (fails == 0) System.out.println("ALL PASS (DiagReport)");
        else { System.out.println(fails + " FAILURE(S)"); System.exit(1); }
    }
}
