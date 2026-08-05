package com.specter.module.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;

import com.specter.module.gen.ActivationVerifier;

/**
 * Android-side activation state: reads the REAL device id, guards the clock against being rolled back, and
 * persists the last valid code. The unforgeable part lives in {@link ActivationVerifier#verify} (pure, and
 * JVM-tested); this class only supplies the device hash and a monotonic "now".
 *
 * <p>Device binding reads {@code Settings.Secure.ANDROID_ID} from inside the Specter app. Specter is not in
 * its OWN LSPosed scope, so this read returns the REAL android_id even with a profile applied — the
 * Activation screen surfaces the hash so that assumption is visible (it stays constant across profiles).
 */
public final class ActivationStore {
    private static final String PREFS = "specter_activation";
    private static final String K_CODE = "code";
    private static final String K_MAX_EPOCH = "max_epoch";   // highest clock value ever seen (rollback guard)

    public enum State { NONE, ACTIVE, EXPIRED }

    public static final class Status {
        public final State state;
        public final long until;        // expiry epoch seconds
        public final String tier;       // "1d"/"1w"/"1m"
        public final String deviceHash; // this device's binding hash (16 hex) — show it so the user can send it
        public final String detail;     // short reason on a non-active stored code
        Status(State s, long until, String tier, String dh, String detail) {
            this.state = s; this.until = until; this.tier = tier; this.deviceHash = dh; this.detail = detail;
        }
    }

    private final Context ctx;
    public ActivationStore(Context ctx) { this.ctx = ctx.getApplicationContext(); }

    private SharedPreferences prefs() { return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    public String deviceHash() {
        String aid = Settings.Secure.getString(ctx.getContentResolver(), Settings.Secure.ANDROID_ID);
        return ActivationVerifier.deviceHash(aid);
    }

    /** now, clamped up to the highest value ever seen so winding the clock BACK can't resurrect a key.
     *  Winding it FORWARD only expires a key sooner, which is not an attack we need to stop. */
    private long effectiveNow() {
        long now = System.currentTimeMillis() / 1000L;
        SharedPreferences p = prefs();
        long max = p.getLong(K_MAX_EPOCH, 0L);
        if (now > max) { p.edit().putLong(K_MAX_EPOCH, now).apply(); max = now; }
        return max;
    }

    /** Verify a pasted code and, if valid, persist it. Returns the verifier result for the UI to message. */
    public ActivationVerifier.Result activate(String code) {
        ActivationVerifier.Result r =
                ActivationVerifier.verify(code, deviceHash(), ActivationVerifier.PUBLIC_KEY_B64, effectiveNow());
        if (r.valid) prefs().edit().putString(K_CODE, code.trim()).apply();
        return r;
    }

    /** Current activation status from the stored code (empty when nothing is stored). */
    public Status status() {
        String dh = deviceHash();
        String code = prefs().getString(K_CODE, "");
        if (code.isEmpty()) return new Status(State.NONE, 0, "", dh, "");
        ActivationVerifier.Result r =
                ActivationVerifier.verify(code, dh, ActivationVerifier.PUBLIC_KEY_B64, effectiveNow());
        if (r.valid) return new Status(State.ACTIVE, r.until, r.tier, dh, "");
        if ("expired".equals(r.reason)) return new Status(State.EXPIRED, r.until, r.tier, dh, "expired");
        return new Status(State.NONE, 0, "", dh, r.reason);   // stored code no longer verifies (wrong device, tampered)
    }

    public boolean isActive() { return status().state == State.ACTIVE; }

    /** "3 days 4 hours left" — compared against the ROLLBACK-CLAMPED now (same value {@link #status} gates
     *  expiry on), so a wound-back clock can't make the UI show more validity than the key actually has. */
    public String remaining(long until) {
        return remaining(until, effectiveNow());
    }

    /** "3 days 4 hours left" / "5 hours left" / "12 minutes left" — one short line, never a paragraph. */
    static String remaining(long until, long now) {
        long secs = until - now;
        if (secs <= 0) return "expired";
        long days = secs / 86400, hours = (secs % 86400) / 3600, mins = (secs % 3600) / 60;
        if (days > 0) return days + (days == 1 ? " day " : " days ") + hours + (hours == 1 ? " hour left" : " hours left");
        if (hours > 0) return hours + (hours == 1 ? " hour " : " hours ") + mins + (mins == 1 ? " minute left" : " minutes left");
        return mins + (mins == 1 ? " minute left" : " minutes left");
    }
}
