package com.specter.module.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.TreeSet;

/** Persisted set of target packages the profile applies to, + label lookup + LSPosed-scope check. */
final class Targets {
    private Targets() {}

    private static final String KEY = "target_pkgs";

    /** Current targets; defaults to the DevInfo test app on first run. */
    static Set<String> get(SharedPreferences prefs) {
        Set<String> stored = prefs.getStringSet(KEY, null);
        if (stored == null) return new TreeSet<>(Arrays.asList(MainActivity.DEFAULT_TARGET));
        return new TreeSet<>(stored);
    }

    static void set(SharedPreferences prefs, Set<String> pkgs) {
        prefs.edit().putStringSet(KEY, new LinkedHashSet<>(pkgs)).apply();
    }

    /** Human app label for a package (e.g. "Device Info HW"), or the package name if it can't resolve. */
    static String label(Context ctx, String pkg) {
        try {
            PackageManager pm = ctx.getPackageManager();
            return String.valueOf(pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)));
        } catch (Throwable t) { return pkg; }
    }

    // Per-process memo of the scope check — the LSPosed scope only changes when the user visits the
    // LSPosed manager (rare), so one grep per package per process is plenty. Without this, spawning a
    // su-grep thread on every render()/checkbox-toggle churns subprocesses badly (code-review finding).
    private static final java.util.Map<String, Boolean> SCOPE_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();

    /** Drop the memo so the next isScoped() re-checks (call when returning from the LSPosed manager). */
    static void invalidateScopeCache() { SCOPE_CACHE.clear(); }

    /** Is this package in Specter's own LSPosed scope? Heuristic: the scope DB stores package strings in
     *  plaintext, so a root grep for the package in modules_config.db tells us if it was ever scoped to a
     *  module. Best-effort — returns true if we can't check (don't cry wolf). Memoized per process. */
    static boolean isScoped(String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        Boolean cached = SCOPE_CACHE.get(pkg);
        if (cached != null) return cached;
        boolean scoped = true;   // fail-open default
        try {
            // grep -q returns 0 if found. Package names follow the OS grammar (no shell metachars), but
            // strip quotes defensively before interpolating into the single-quoted arg.
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "grep -qa '" + pkg.replace("'", "") + "' /data/adb/lspd/config/modules_config.db"});
            scoped = (p.waitFor() == 0);
        } catch (Throwable ignored) {}
        SCOPE_CACHE.put(pkg, scoped);
        return scoped;
    }
}
