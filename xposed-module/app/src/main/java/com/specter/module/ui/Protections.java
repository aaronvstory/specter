package com.specter.module.ui;

import android.content.SharedPreferences;

import java.util.Map;

/**
 * Anti-detection protections the user can toggle, each backed by a REAL gating key in the applied
 * profile (never a cosmetic switch). Every protection defaults ON — turning one OFF adds its gate key
 * with value "0" to the profile, which the Java/native hooks read to skip that protection and leave
 * the corresponding signal REAL. So the toggle state actually changes what the device reports.
 *
 * The gate keys and their consumers:
 *   hide_root   -> native g_hide_root (ENOENT on su/Magisk paths) + rootApps evasion
 *   hide_dev    -> HookEntry.hookSettingsGlobal (adb_enabled/development_settings_enabled -> 0)
 *   hide_apps   -> HookEntry.hookInstalledApps (drop root/hook/anti-fp packages from enumeration)
 *   spoof_ua    -> HookEntry.hookUserAgent (rebuild http.agent + WebView UA from the profile)
 *   spoof_apktime -> HookEntry APK-mtime spoof (File.lastModified/Os.stat on the app's own APKs)
 *   spoof_sysfs -> native /sys cpu_capacity/gpu_model/present redirect
 */
public final class Protections {
    private Protections() {}

    public static final class P {
        public final String gateKey;      // profile key written as "0" when the toggle is OFF
        public final String prefKey;      // SharedPreferences key for the toggle state
        public final String label;
        public final String desc;
        P(String gateKey, String label, String desc) {
            this.gateKey = gateKey;
            this.prefKey = "prot_on_" + gateKey;
            this.label = label;
            this.desc = desc;
        }
    }

    // Order = display order in the Settings "Protections" section.
    public static final P[] ALL = {
        new P("hide_root",     "Hide root",          "Makes su / Magisk / Zygisk / Frida paths read as absent AND filters Magisk out of /proc/mounts + mountinfo, so a root or bind-mount check finds nothing."),
        new P("hide_dev",      "Hide developer mode","Reports ADB and Developer Options as OFF (adb_enabled / development_settings_enabled = 0)."),
        new P("hide_apps",     "Hide My AppList",    "Drops Specter, root managers, and anti-fingerprint tools from the installed-app list an app can enumerate."),
        new P("spoof_ua",      "Spoof User-Agent",   "Rebuilds the HTTP + WebView User-Agent from the applied device, so the UA no longer leaks the real phone."),
        new P("spoof_apktime", "Spoof install time", "Rewrites the app's own APK install timestamps to a per-identity value (the FingerprintJS FileTimestamps signal)."),
        new P("spoof_sysfs",   "Spoof hardware profile", "Aligns the deep hardware signature with the applied device: /sys cpu_capacity, gpu_model and present, plus /proc/version and the screen resolution/density (getDisplayMetrics)."),
    };

    /** True unless the user explicitly turned this protection off. */
    public static boolean isOn(SharedPreferences prefs, P p) {
        return prefs.getBoolean(p.prefKey, true);
    }

    public static void set(SharedPreferences prefs, P p, boolean on) {
        prefs.edit().putBoolean(p.prefKey, on).apply();
    }

    /** Add gate keys for every protection the user turned OFF (value "0"). Applied profile only. */
    public static void applyGates(SharedPreferences prefs, Map<String, String> profile) {
        for (P p : ALL) if (!isOn(prefs, p)) profile.put(p.gateKey, "0");
    }
}
