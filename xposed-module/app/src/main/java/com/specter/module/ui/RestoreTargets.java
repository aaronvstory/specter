package com.specter.module.ui;

import java.util.LinkedHashSet;
import java.util.Set;

/** Pure helpers deciding which apps a vault restore drives to. A saved bundle restores to the app(s) it was
 *  captured for (its persisted {@code _targets}), NEVER whatever target happens to be selected now. No Android
 *  deps on purpose, so the JVM harness unit-tests it. App-agnostic: the packages come entirely from the save. */
final class RestoreTargets {
    private RestoreTargets() {}

    /** Parse a comma-separated package list (the vault's {@code _targets} field) into an order-preserving,
     *  de-duped set. Blank/whitespace entries drop: {@code "a, ,b,"} → {a,b}; {@code null}/{@code ""} → {}. */
    static Set<String> parse(String csv) {
        Set<String> out = new LinkedHashSet<>();
        if (csv == null) return out;
        for (String part : csv.split(",")) {
            String p = part.trim();
            if (!p.isEmpty()) out.add(p);
        }
        return out;
    }

    /** The apps a restore of this save should reach: the save's own targets when it has any (restore follows
     *  the bundle), else the current selection (an older save with no {@code _targets}). Copies so callers can
     *  mutate the result freely. */
    static Set<String> resolve(Set<String> savedTargets, Set<String> currentTargets) {
        if (savedTargets != null && !savedTargets.isEmpty()) return new LinkedHashSet<>(savedTargets);
        return currentTargets != null ? new LinkedHashSet<>(currentTargets) : new LinkedHashSet<>();
    }

    /** True when restoring this save should actively re-point the target selection: it carries its own targets
     *  AND they differ from what's selected now. Drives whether the UI announces the switch. */
    static boolean drivesSwitch(Set<String> savedTargets, Set<String> currentTargets) {
        if (savedTargets == null || savedTargets.isEmpty()) return false;
        return !savedTargets.equals(currentTargets == null ? new LinkedHashSet<String>() : currentTargets);
    }
}
