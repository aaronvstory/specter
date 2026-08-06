package com.specter.module.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/** Hand-rolled asserts (no framework, matching the rest of the JVM suite). Pins the restore-target rule:
 *  a save restores to the app(s) it stored, app-agnostic — never the currently-selected target. */
public class RestoreTargetsTest {
    static int checks = 0;

    static void eq(Object want, Object got, String what) {
        checks++;
        if (want == null ? got != null : !want.equals(got))
            throw new AssertionError(what + ": want " + want + ", got " + got);
    }

    static Set<String> set(String... s) { return new LinkedHashSet<>(Arrays.asList(s)); }

    public static void main(String[] args) {
        // ---- parse: the _targets CSV → package set ----
        eq(Collections.emptySet(), RestoreTargets.parse(null), "null csv is empty (older save)");
        eq(Collections.emptySet(), RestoreTargets.parse(""), "empty csv is empty");
        eq(Collections.emptySet(), RestoreTargets.parse("  , ,"), "all-blank csv is empty");
        eq(set("com.x"), RestoreTargets.parse("com.x"), "one package");
        eq(set("com.x", "com.y"), RestoreTargets.parse("com.x,com.y"), "two packages");
        eq(set("com.x", "com.y"), RestoreTargets.parse(" com.x , , com.y ,"), "trims + drops blanks");
        eq(set("com.x"), RestoreTargets.parse("com.x,com.x"), "dedupes");
        // order preserved (LinkedHashSet), so the icon cluster / status reads in save order
        eq("[com.b, com.a]", RestoreTargets.parse("com.b,com.a").toString(), "keeps CSV order, not sorted");

        // ---- resolve: which apps a restore reaches ----
        // App-agnostic: a save captured for app X restores to X even when Y is selected. THE BUG this fixes.
        eq(set("com.cash"), RestoreTargets.resolve(set("com.cash"), set("com.dasher")),
                "save's own target wins over the selected one");
        eq(set("com.a", "com.b"), RestoreTargets.resolve(set("com.a", "com.b"), set("com.z")),
                "multi-app save drives all of its own apps");
        // Older save with no _targets → fall back to the current selection (don't strand the user).
        eq(set("com.sel"), RestoreTargets.resolve(Collections.emptySet(), set("com.sel")),
                "no stored targets falls back to current selection");
        eq(Collections.emptySet(), RestoreTargets.resolve(null, null), "nothing anywhere is empty");
        eq(set("com.sel"), RestoreTargets.resolve(null, set("com.sel")), "null stored falls back");
        // result is a fresh mutable copy (caller may mutate / Targets.set consumes it)
        Set<String> src = set("com.a");
        Set<String> got = RestoreTargets.resolve(src, null);
        got.add("com.b");
        eq(1, src.size(), "resolve copies — mutating the result doesn't touch the input");
        // a TreeSet (what Targets.get returns) compares equal to our set by contents
        eq(set("com.cash"), RestoreTargets.resolve(new TreeSet<>(Arrays.asList("com.cash")), set("com.x")),
                "works with the TreeSet Targets.get hands back");

        // ---- drivesSwitch: does the UI announce a re-point? ----
        eq(true, RestoreTargets.drivesSwitch(set("com.cash"), set("com.dasher")),
                "save differs from selection → announce the switch");
        eq(false, RestoreTargets.drivesSwitch(set("com.cash"), set("com.cash")),
                "save == selection → no switch to announce");
        eq(false, RestoreTargets.drivesSwitch(Collections.emptySet(), set("com.dasher")),
                "no stored targets → never announces (kept current)");
        eq(false, RestoreTargets.drivesSwitch(null, set("com.dasher")), "null stored → no switch");
        // set equality is content-based, so selection order/impl doesn't cause a spurious switch
        eq(false, RestoreTargets.drivesSwitch(set("com.a", "com.b"), new TreeSet<>(Arrays.asList("com.b", "com.a"))),
                "same members, different order/impl → not a switch");

        System.out.println("RestoreTargetsTest: " + checks + " checks passed");
    }
}
