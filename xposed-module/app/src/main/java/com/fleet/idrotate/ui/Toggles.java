package com.fleet.idrotate.ui;

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
        return prefs.getBoolean("id_on_" + key, true);
    }

    static void set(SharedPreferences prefs, String key, boolean on) {
        prefs.edit().putBoolean("id_on_" + key, on).apply();
    }
}
