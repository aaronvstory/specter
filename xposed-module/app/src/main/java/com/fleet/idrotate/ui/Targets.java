package com.fleet.idrotate.ui;

import android.content.SharedPreferences;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/** Persisted set of target packages the profile applies to, plus a fleet-safety check. */
final class Targets {
    private Targets() {}

    private static final String KEY = "target_pkgs";

    /** Packages the operator makes real money on / system — warned about, never auto-selected. */
    static final Set<String> RISKY = new HashSet<>(Arrays.asList(
            "com.doordash.driverapp", "com.dd.doordash", "android", "com.android.systemui"));

    /** Current targets; defaults to the fleet-safe DevInfo test app on first run. */
    static Set<String> get(SharedPreferences prefs) {
        Set<String> stored = prefs.getStringSet(KEY, null);
        if (stored == null) return new TreeSet<>(Arrays.asList(MainActivity.DEFAULT_TARGET));
        return new TreeSet<>(stored);
    }

    static void set(SharedPreferences prefs, Set<String> pkgs) {
        prefs.edit().putStringSet(KEY, new LinkedHashSet<>(pkgs)).apply();
    }

    static boolean isRisky(String pkg) {
        return RISKY.contains(pkg) || pkg.startsWith("com.android.") || pkg.startsWith("android");
    }
}
