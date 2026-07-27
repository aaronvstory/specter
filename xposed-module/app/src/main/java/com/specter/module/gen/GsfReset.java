package com.specter.module.gen;

/**
 * Resets the device's Google-side identity — the GSF/GServices {@code android_id}, the Play "device" the
 * account is bound to, and the GMS-held install/attest state — by wiping Google Play Services + Services
 * Framework + Vending and letting them re-register a FRESH id on next boot.
 *
 * <p>Why this matters (byedentity parity — BYEDENTITY-ANALYSIS.md, and confirmed by the 2026-07-28 Dasher
 * number-leak): a target app's server can re-link a "new" install to a prior account via a stable Google
 * identifier that outlives the app's own {@code pm clear} — the GSF {@code android_id} is device-wide (in
 * {@code com.google.android.gsf}, not the target app's data), so clearing only the target leaves it intact.
 * byedentity resets it by {@code am force-stop} + {@code pm clear} of {@code gms}/{@code gsf}/{@code vending}
 * then a reboot; GSF re-registers a new id. This is the server-side re-link anchor, the class of signal the
 * per-app fingerprint spoof can't touch.
 *
 * <p><b>Heavy + opt-in.</b> Wiping GMS/Vending signs the device out of the Google account, drops Play
 * state, and REQUIRES a reboot for re-registration — so it's an explicit, deliberate action, never part of
 * a routine apply. It does NOT choose the new id (GSF does, server-side on re-register); it only forces a
 * fresh registration.
 *
 * <p>Command building + validation is pure/testable; {@link #reset} does the {@code su} exec + reboot.
 * Epistemic note: that the new GSF id actually breaks a given target app's server-side re-link is asserted
 * from the mechanism (device-wide id + fresh re-register) and byedentity's use of it; whether a SPECIFIC
 * target re-links on some OTHER signal too must be measured per target, not assumed.
 */
public final class GsfReset {
    private GsfReset() {}

    /** The Google packages whose data holds the device-wide identity/registration state. Order matters only
     *  in that all three are cleared before the reboot that triggers re-registration. */
    public static final String[] GOOGLE_PKGS = {
            "com.google.android.gsf",       // Services Framework — holds the GSF android_id
            "com.google.android.gms",       // Play Services — attest/install ids, FID backing
            "com.android.vending",          // Play Store — the "device" bound to the account
    };

    public static final class GsfException extends RuntimeException {
        public GsfException(String m) { super(m); }
        public GsfException(String m, Throwable t) { super(m, t); }
    }

    /**
     * The shell program: force-stop then {@code pm clear} each Google package. Each package is guarded — a
     * device without one (rare) is skipped, not a failure. Does NOT reboot — {@link #reset} issues the reboot
     * as a SEPARATE call only after these clears succeed, so a denied clear can never masquerade as success.
     */
    public static String buildResetCommand() {
        StringBuilder s = new StringBuilder();
        s.append("set -e\n");
        // GSF holds the id we're resetting — if it isn't even installed, this device can't do a GSF reset, so
        // fail loudly rather than "succeed" having cleared nothing. (`pm path` prints the apk path if installed.)
        s.append("pm path ").append(GOOGLE_PKGS[0]).append(" >/dev/null 2>&1 || { echo gsf_not_installed >&2; exit 5; }\n");
        // Clear each present package and REQUIRE at least one real clear — so a PM/permission failure that
        // makes every `pm clear` fail can't slip through as a no-op success.
        s.append("cleared=0\n");
        for (String pkg : GOOGLE_PKGS) {
            s.append("if pm path ").append(pkg).append(" >/dev/null 2>&1; then\n");
            s.append("  am force-stop ").append(pkg).append(" || true\n");
            // pm clear prints "Success"/"Failed"; gate on the output so a non-Success is a real failure, not
            // swallowed (pm clear can exit 0 even when it didn't clear).
            s.append("  out=$(pm clear ").append(pkg).append(" 2>&1); case \"$out\" in *Success*) cleared=$((cleared+1));; *) echo \"clear ")
             .append(pkg).append(" failed: $out\" >&2;; esac\n");
            s.append("fi\n");
        }
        s.append("[ \"$cleared\" -ge 1 ] || { echo no_package_cleared >&2; exit 6; }\n");
        s.append("echo specter_gsf_reset_done\n");
        return s.toString();
    }

    /** Just the reboot (issued as a SEPARATE su call after the clears are confirmed) — so a denied/failed
     *  clear is caught by its own exit code and we never reboot on a no-op. */
    static final String REBOOT_CMD = "svc power reboot || reboot\n";

    // ---- exec ----

    /** Clear the Google packages and reboot (the reboot re-registers a fresh GSF id). Throws on su failure.
     *  NOTE: on success the device restarts, so this call does not "return" normally. */
    public static void reset() { reset(new RootWriter.SuShell(), true); }

    /**
     * Clear the Google packages via {@code shell}, then reboot iff {@code reboot}. The clears run FIRST as
     * their own checked command — a non-zero exit (root denied / {@code pm clear} failed) throws
     * {@link GsfException} and NO reboot is issued, so a denied reset can never masquerade as success. The
     * reboot is a separate call afterwards (its exit code is ignored — a reboot tears down su). {@code
     * reboot=false} is for tests + a caller that stages its own reboot.
     */
    public static void reset(RootWriter.Shell shell, boolean reboot) {
        int rc;
        try {
            rc = shell.run(buildResetCommand(), "");   // clears only — exit code is authoritative
        } catch (Exception e) {
            throw new GsfException("gsf reset error (is Magisk root granted?)", e);
        }
        if (rc != 0) throw new GsfException("gsf reset exited " + rc + " — root likely denied, nothing cleared");
        if (!reboot) return;
        // Clears confirmed → reboot as a separate call; ignore its code (the reboot kills su before it reports).
        try { shell.run(REBOOT_CMD, ""); } catch (Exception ignored) {}
    }
}
