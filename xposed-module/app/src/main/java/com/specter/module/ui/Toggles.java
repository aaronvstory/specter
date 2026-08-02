package com.specter.module.ui;

import android.content.SharedPreferences;

/**
 * Per-identifier on/off state (GeerGit's {@code *_switch}). A disabled id is omitted from the
 * applied profile so the hook leaves that identifier REAL. Default: everything enabled.
 * Build.* keys are governed by the "build_manufacturer" toggle (the device-simulation bundle).
 */
final class Toggles {
    private Toggles() {}

    // Hardware-anchor identifiers that are LOCKED ON — never user-toggleable off. Turning one of these off
    // re-introduces the intermittent-leak failure mode: a device-intelligence SDK reads a STABLE hardware id
    // (Widevine deviceUniqueId / build serial) that survives an id rotation and re-links every identity, which
    // is the non-deterministic-ban shape we traced (see docs/ANTI-FINGERPRINT-STRATEGY 2026-07-29). There is
    // no legitimate reason to leak the real one, so these have no off switch — isEnabled ignores the pref and
    // the UI renders them as a locked/always-on row.
    static final java.util.Set<String> LOCKED = new java.util.HashSet<>(java.util.Arrays.asList(
            "media_drm_id", "media_drm_security_level", "serial"));

    static boolean isLocked(String key) {
        return LOCKED.contains(key == null ? "" : (key.startsWith("build_") ? "build_manufacturer" : key));
    }

    static boolean isEnabled(SharedPreferences prefs, String key) {
        if (key.startsWith("build_")) key = "build_manufacturer"; // one switch for the device bundle
        if (LOCKED.contains(key)) return true;                    // hardware anchor: always on, ignore pref
        // Everything defaults ON — leaking a REAL value (e.g. the device Gmail) to a scoped app is itself
        // a spoofing failure, so we mask by default. Each identifier stays individually toggleable if a
        // specific target app misbehaves.
        return prefs.getBoolean("id_on_" + key, true);
    }

    static void set(SharedPreferences prefs, String key, boolean on) {
        if (LOCKED.contains(key)) return;   // locked anchors can't be toggled off
        prefs.edit().putBoolean("id_on_" + key, on).apply();
    }
}
