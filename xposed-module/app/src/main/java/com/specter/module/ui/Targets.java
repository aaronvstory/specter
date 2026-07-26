package com.specter.module.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/** Persisted set of target packages the profile applies to, + label lookup + LSPosed-scope check. */
final class Targets {
    private Targets() {}

    private static final String KEY = "target_pkgs";

    /** Packages the operator earns real income on / core system — never auto-selected in bulk. */
    static final Set<String> RISKY = new HashSet<>(Arrays.asList(
            "com.doordash.driverapp", "com.dd.doordash", "com.pyshivam.geergit",
            "android", "com.android.systemui"));

    /** Current targets; defaults to the DevInfo test app on first run. */
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

    /** Human app label for a package (e.g. "Device Info HW"), or the package name if it can't resolve. */
    static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) { return pkg; }
    }

    /** Is this package in Specter's own LSPosed scope? Heuristic: the scope DB stores package strings in
     *  plaintext, so a root grep for the package in modules_config.db tells us if it was ever scoped to a
     *  module. Best-effort — returns true if we can't check (don't cry wolf when root/DB is unavailable). */
    static boolean isScoped(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        try {
            // grep -q returns 0 if found. The DB has one scope row per (module, app); our module (mid 25)
            // is the only Specter one, so a match means some module scopes it — good enough for a warning.
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "grep -qa '" + pkg.replace("'", "") + "' /data/adb/lspd/config/modules_config.db"});
            int code = p.waitFor();
            return code == 0;
        } catch (Throwable t) {
            return true;   // can't check -> assume ok (don't false-warn)
        }
    }
}
