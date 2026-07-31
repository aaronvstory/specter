package com.specter.module.ui;

import java.util.ArrayList;
import java.util.List;

/** JVM self-test for DiagReport.build: summary counts + grouping by coverage (spoofed / leaking /
 *  unclassified / non-identifying). */
public final class DiagReportTest {
    static int fails = 0;
    static void check(boolean c, String m) { if (!c) { System.out.println("FAIL: " + m); fails++; } }

    public static void main(String[] args) {
        // Build a trace from lines the parser understands, then report on the parsed rows.
        String raw = String.join("\n",
                "07-26 13:00:00.000 100 100 I SpecterTrace: prop ro.product.model",
                "07-26 13:00:00.001 100 100 I SpecterTrace: prop ro.product.model",  // -> count 2, spoofed
                "07-26 13:00:00.002 100 100 I SpecterTrace: prop ro.arch",            // non-identifying
                "07-26 13:00:00.003 100 100 I SpecterTrace: open /proc/cpuinfo",      // spoofed file
                "07-26 13:00:00.004 100 100 I SpecterTrace: prop ro.build.date.utc",  // leaking
                "07-26 13:00:00.005 100 100 I SpecterTrace: prop some.random.prop");  // unclassified
        List<TraceParser.Row> rows = TraceParser.parse(raw, 100);
        String rep = DiagReport.build(rows);

        check(rep.contains("Specter live-trace coverage report"), "has title");
        check(rep.contains("distinct signals"), "has summary line");
        // 2 spoofed (model, cpuinfo) · 1 leaking (build.date.utc) · 1 unclassified · 1 non-identifying (arch)
        check(rep.contains("2 spoofed"), "2 spoofed in summary: \n" + rep);
        check(rep.contains("1 leaking"), "1 leaking in summary");
        check(rep.contains("1 unclassified"), "1 unclassified in summary");
        check(rep.contains("1 non-identifying"), "1 non-identifying in summary");
        check(rep.contains("1 IDENTIFIER(S) STILL REAL"), "verdict names the leak");
        check(rep.contains("[prop ] ro.product.model (x2)"), "model row with kind tag + count");
        check(rep.contains("[file ] /proc/cpuinfo"), "cpuinfo row tagged file");
        // Grouped by what the read MEANS, not by syscall kind.
        check(rep.contains("-- SPOOFED --"), "spoofed group");
        check(rep.contains("-- LEAKING (identifying, not spoofed) --"), "leaking group");
        check(rep.contains("-- UNCLASSIFIED --"), "unclassified group");
        check(rep.contains("NON-IDENTIFYING"), "non-identifying group present but last");
        check(rep.indexOf("-- SPOOFED --") < rep.indexOf("NON-IDENTIFYING"), "noise listed after the verdict");
        // The leaking signal must land in the LEAKING group, not be buried among the noise.
        check(rep.indexOf("ro.build.date.utc") > rep.indexOf("-- LEAKING")
                && rep.indexOf("ro.build.date.utc") < rep.indexOf("-- UNCLASSIFIED --"), "leak in leak group");

        // A trace of PURE noise must report a clean verdict — this is the whole point of the redesign:
        // 200 font stats are not 200 leaks.
        StringBuilder fonts = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            fonts.append("07-26 13:00:00.000 100 100 I SpecterTrace: stat /system/fonts/F").append(i).append(".ttf\n");
        }
        String noiseRep = DiagReport.build(TraceParser.parse(fonts.toString(), 400));
        check(noiseRep.contains("No identifiers leaked"), "font-only trace reads clean: \n"
                + noiseRep.substring(0, Math.min(300, noiseRep.length())));
        check(noiseRep.contains("0 spoofed · 0 leaking"), "font-only trace has zero leaks counted");

        // Empty input is graceful.
        check(DiagReport.build(new ArrayList<>()).contains("no signals"), "empty -> graceful");
        check(DiagReport.build(null).contains("no signals"), "null -> graceful");

        if (fails == 0) System.out.println("ALL PASS (DiagReport)");
        else { System.out.println(fails + " FAILURE(S)"); System.exit(1); }
    }
}
