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
 *   hide_mock   -> HookEntry.hookMockLocation (Location.isMock/isFromMockProvider + mock_location settings)
 *   hide_dev    -> HookEntry.hookSettingsGlobal (adb_enabled/development_settings_enabled -> 0)
 *   hide_apps   -> HookEntry.hookInstalledApps (drop root/hook/anti-fp packages from enumeration)
 *   spoof_ua    -> HookEntry.hookUserAgent (rebuild http.agent + WebView UA from the profile)
 *   spoof_apktime -> HookEntry APK-mtime spoof (File.lastModified/Os.stat on the app's own APKs)
 *   spoof_sysfs -> native /sys cpu_capacity/gpu_model/present redirect
 *   fix_webrtc  -> HookEntry.hookWebRtc (inject a JS ICE-candidate filter into WebViews: drop real
 *                  local/private/mDNS IPs, keep the proxy IP — WebRTC stays enabled, not blocked)
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
    // desc = ONE short line for the primary UI (Apple-clean); the mechanism detail lives in the comments
    // above / the design docs, not on-screen.
    public static final P[] ALL = {
        new P("hide_root",     "Hide root",              "Root tools read as absent"),
        new P("hide_mock",     "Hide mock location",     "Mock-location flags read as clean"),
        new P("hide_dev",      "Hide developer mode",    "ADB and Developer Options read as off"),
        new P("hide_apps",     "Hide app list",          "Specter and root tools stay out of app lists"),
        new P("hide_vpn",      "Hide VPN interfaces",    "Hidden from scoped apps"),
        new P("fix_webrtc",    "Fix WebRTC leak",        "WebRTC exposes only the routed address"),
        new P("spoof_ua",      "Match browser signature","User-Agent matches the applied phone"),
        new P("spoof_apktime", "Randomize install time", "Install time changes with the identity"),
        new P("spoof_sysfs",   "Match hardware profile", "Hardware and display signals match the profile"),
        // Media codecs have no single identity value, so their toggle lives here (not in the Identity
        // list). Default ON like the other hardware spoofs — leaving the real OMX.qcom.* codec set is a
        // per-SoC leak. Toggleable per-app: the only apps this could affect are ones that create a codec
        // by its (now-relabeled) name, so turn it off for a specific target only if media playback breaks.
        new P("spoof_codecs",  "Match media codecs",     "Codec names match the applied phone", true),
        // OPT-IN diagnostics (default OFF). READ-ONLY — makes the hooks LOG what each scoped app reads +
        // what value we returned, to /data/local/tmp/specter/diag.log (via a background logcat capture).
        // Changes NOTHING the app sees, so it's safe; a slight perf/log cost is why it's off by default.
        new P("trace",          "Read logging",           "Log reads without changing returned values", false),
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
