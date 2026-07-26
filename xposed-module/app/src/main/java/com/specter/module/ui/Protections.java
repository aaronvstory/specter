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
        public final String gateKey;      // profile key controlling the hook
        public final String prefKey;      // SharedPreferences key for the toggle state
        public final String label;
        public final String desc;
        public final boolean defaultOn;   // most protections default ON; risky opt-ins default OFF
        // A default-ON protection writes gateKey="0" when the user turns it OFF (the hook skips on "0").
        // A default-OFF protection writes gateKey="1" when the user turns it ON (the hook runs only on "1").
        P(String gateKey, String label, String desc) { this(gateKey, label, desc, true); }
        P(String gateKey, String label, String desc, boolean defaultOn) {
            this.gateKey = gateKey;
            this.prefKey = "prot_on_" + gateKey;
            this.label = label;
            this.desc = desc;
            this.defaultOn = defaultOn;
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
        // Media codecs have no single identity value, so their toggle lives here (not in the Identity
        // list). Default ON like the other hardware spoofs — leaving the real OMX.qcom.* codec set is a
        // per-SoC leak. Toggleable per-app: the only apps this could affect are ones that create a codec
        // by its (now-relabeled) name, so turn it off for a specific target only if media playback breaks.
        // (Google-account masking's toggle lives on the Gmail row in the Identity tab, next to its value.)
        new P("spoof_codecs",   "Spoof media codecs", "Relabels the media-codec list (a per-SoC signal that otherwise leaks the real SoC). On by default. If a specific app's media playback breaks, turn it off just for that app.", true),
        // OPT-IN diagnostics (default OFF). READ-ONLY — makes the hooks LOG what each scoped app reads +
        // what value we returned, to /data/local/tmp/specter/diag.log (via a background logcat capture).
        // Changes NOTHING the app sees, so it's safe; a slight perf/log cost is why it's off by default.
        new P("trace",          "Diagnostics logging", "Logs what each Specter-scoped app READS (props, files, IDs) and the value returned, to /data/local/tmp/specter/diag.log — so you can verify spoofs are landing. Read-only: applies nothing. Off by default (perf/log cost).", false),
    };

    /** Look up a protection by its gate key (e.g. "trace"). Returns null if unknown. */
    public static P byKey(String gateKey) {
        for (P p : ALL) if (p.gateKey.equals(gateKey)) return p;
        return null;
    }

    /** The toggle's current state — defaults to the protection's defaultOn. */
    public static boolean isOn(SharedPreferences prefs, P p) {
        if (p == null) return false;
        return prefs.getBoolean(p.prefKey, p.defaultOn);
    }

    public static void set(SharedPreferences prefs, P p, boolean on) {
        prefs.edit().putBoolean(p.prefKey, on).apply();
    }

    /** Write gate keys into the applied profile. Default-ON protections write "0" when turned OFF (hook
     *  skips on "0"); default-OFF protections write "1" when turned ON (hook runs only on "1"). */
    public static void applyGates(SharedPreferences prefs, Map<String, String> profile) {
        for (P p : ALL) {
            boolean on = isOn(prefs, p);
            if (p.defaultOn && !on) profile.put(p.gateKey, "0");
            else if (!p.defaultOn && on) profile.put(p.gateKey, "1");
        }
    }
}
