package com.specter.module.ui;

import android.content.SharedPreferences;

/**
 * Per-identifier on/off state (GeerGit's {@code *_switch}). A disabled id is omitted from the
 * applied profile so the hook leaves that identifier REAL. Default: everything enabled.
 * Build.* keys are governed by the "build_manufacturer" toggle (the device-simulation bundle).
 */
final class Toggles {
    private Toggles() {}

    static boolean isEnabled(SharedPreferences prefs, String key) {
        if (key.startsWith("build_")) key = "build_manufacturer"; // one switch for the device bundle
        // Everything defaults ON — leaking a REAL value (e.g. the device Gmail) to a scoped app is itself
        // a spoofing failure, so we mask by default. Each identifier stays individually toggleable if a
        // specific target app misbehaves.
        return prefs.getBoolean("id_on_" + key, true);
    }

    static void set(SharedPreferences prefs, String key, boolean on) {
        prefs.edit().putBoolean("id_on_" + key, on).apply();
    }
}
