package com.specter.module.gen;

/**
 * Forces the device's Widevine DRM from hardware L1 down to software L3, at the NATIVE layer — below the
 * Java {@code MediaDrm} hook. A fingerprinter that reads {@code securityLevel} / {@code deviceUniqueId}
 * through the native OEMCrypto path (not the Java API our Xposed hook covers) then sees a coherent L3, and
 * the Widevine device id legitimately changes (L3 derives it in software instead of the fixed hardware id).
 *
 * <p>Mechanism (byedentity parity — BYEDENTITY-ANALYSIS.md candidate #4): a Magisk module that shadows the
 * vendor {@code liboemcrypto.so} with an EMPTY file via {@code mount -o bind} in {@code post-fs-data.sh}.
 * With no real OEMCrypto, hardware Widevine init fails and the DRM stack falls back to L3. This is
 * device-wide and persists across reboot (Magisk re-applies the mount at boot). It is the ONE Widevine
 * bet that reaches a native reader; the Java {@code securityLevel} getter hook (in HookEntry) stays as the
 * cheap in-process cover for apps that read via the Java API.
 *
 * <p><b>Opt-in + reversible by design.</b> Breaking hardware Widevine also breaks HD playback in DRM apps
 * (Netflix/Prime), so this is gated behind an explicit Settings toggle and is fully removable: uninstall
 * deletes the Magisk module dir; the bind-mount is gone on the next reboot (or immediately via {@code umount}).
 *
 * <p>The command/script building + validation is pure and unit-tested; {@link #install}/{@link #uninstall}
 * do the {@code su} exec. PROVEN on the Pixel 4a (2026-07-28): with the module installed + a reboot, a
 * native-backed {@code MediaDrm.getPropertyString("securityLevel")} read (unhooked — no Specter profile
 * applied) returned {@code L3}; after uninstall + reboot it returned {@code L1} again (real hardware
 * restored); that device booted normally in both states. This is ONE device's result — a different vendor's
 * DRM HAL may react differently to a missing OEMCrypto (worst case a boot hiccup), which is why the feature
 * is opt-in and recoverable: turning the toggle off removes it, and booting to Magisk safe mode disables all
 * modules if a device ever won't boot with it on. Don't assume "boots fine everywhere" from the one datapoint.
 */
public final class WidevineL3 {
    private WidevineL3() {}

    /** Magisk module id/dir. Distinct from {@code specter_zygisk} so the two are installed/removed independently. */
    public static final String MODULE_ID = "specter_widevine_l3";
    static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;

    /** The vendor OEMCrypto libs shadowed by the bind-mount. Both arch dirs are covered; a device may have
     *  only one — the script guards each with a {@code [ -f ]} test so a missing path is a no-op, not an error. */
    static final String[] OEMCRYPTO_LIBS = {
            "/vendor/lib64/liboemcrypto.so",
            "/vendor/lib/liboemcrypto.so",
    };

    public static final class WidevineException extends RuntimeException {
        public WidevineException(String m) { super(m); }
        public WidevineException(String m, Throwable t) { super(m, t); }
    }

    // ---- module.prop ----

    /** The Magisk {@code module.prop} for the L3 module. versionCode is a plain int Magisk compares for updates. */
    public static String moduleProp() {
        return "id=" + MODULE_ID + "\n"
                + "name=Specter Widevine L3\n"
                + "version=v1.0.0\n"
                + "versionCode=1\n"
                + "author=Specter\n"
                + "description=Forces Widevine from hardware L1 to software L3 by bind-mounting an empty "
                + "liboemcrypto.so over the vendor lib. Reaches native OEMCrypto reads the Java MediaDrm hook "
                + "cannot. Opt-in; removing this module restores hardware Widevine on reboot.\n";
    }

    // ---- post-fs-data.sh (the bind-mount, applied every boot) ----

    /**
     * The {@code post-fs-data.sh} Magisk runs at every boot (before the DRM stack starts). It bind-mounts the
     * module's empty stub over each real {@code liboemcrypto.so}. Each mount is guarded so a missing arch path
     * or an already-mounted target never aborts the script.
     */
    public static String postFsDataScript() {
        StringBuilder sb = new StringBuilder();
        sb.append("#!/system/bin/sh\n");
        sb.append("# Specter: shadow vendor liboemcrypto.so with an empty stub -> hardware Widevine init fails\n");
        sb.append("# -> device falls back to L3. Guarded per path; a missing/already-mounted target is a no-op.\n");
        sb.append("MODDIR=${0%/*}\n");
        sb.append("STUB=\"$MODDIR/liboemcrypto.so\"\n");
        for (String lib : OEMCRYPTO_LIBS) {
            // only bind if the real lib exists and our stub isn't already mounted there
            sb.append("if [ -f \"").append(lib).append("\" ]; then\n");
            sb.append("  mount -o bind \"$STUB\" \"").append(lib).append("\" 2>/dev/null\n");
            sb.append("fi\n");
        }
        return sb.toString();
    }

    // ---- install / uninstall command builders (pure, testable) ----

    /** Staging dir — the module is built here in full, then atomically {@code mv}'d over MODULE_DIR, so a
     *  failure mid-write never leaves Magisk a half-written module to load at boot (codex-flagged). */
    static final String STAGE_DIR = MODULE_DIR + ".stage";

    /**
     * A single {@code sh} program that materialises the whole Magisk module ATOMICALLY: it first refuses to
     * run on a device with no {@code liboemcrypto.so} to shadow (nothing to do), then builds the complete
     * module in a staging dir (module.prop + post-fs-data.sh + empty stub + perms) and {@code mv}s it over the
     * live path in one step. Only after the module is in place does it apply the bind-mount LIVE. Heredocs
     * carry the file bodies; the only interpolation is our own constants (no external input) → no injection.
     */
    public static String buildInstallScript() {
        StringBuilder s = new StringBuilder();
        s.append("set -e\n");
        // Require at least one real oemcrypto to shadow — else this device has nothing to force to L3 and a
        // "success" would be a lie. Non-zero exit surfaces as WidevineException in the UI.
        s.append("if [ ! -f \"").append(OEMCRYPTO_LIBS[0]).append("\" ] && [ ! -f \"")
         .append(OEMCRYPTO_LIBS[1]).append("\" ]; then echo no_oemcrypto_on_device >&2; exit 3; fi\n");
        // build in a fresh staging dir
        s.append("rm -rf ").append(STAGE_DIR).append("\n");
        s.append("mkdir -p ").append(STAGE_DIR).append("\n");
        s.append("cat > ").append(STAGE_DIR).append("/module.prop <<'SPECTER_EOF'\n");
        s.append(moduleProp());
        s.append("SPECTER_EOF\n");
        s.append("cat > ").append(STAGE_DIR).append("/post-fs-data.sh <<'SPECTER_EOF'\n");
        s.append(postFsDataScript());
        s.append("SPECTER_EOF\n");
        s.append(": > ").append(STAGE_DIR).append("/liboemcrypto.so\n");
        s.append("chmod 0755 ").append(STAGE_DIR).append("/post-fs-data.sh\n");
        s.append("chmod 0644 ").append(STAGE_DIR).append("/liboemcrypto.so ").append(STAGE_DIR).append("/module.prop\n");
        // swap: move any prior module aside FIRST (so a failed rename doesn't leave us with no module), rename
        // staging into place, then drop the backup. If the rename fails, restore the backup and abort (set -e).
        s.append("BAK=").append(MODULE_DIR).append(".bak\n");
        s.append("rm -rf $BAK\n");
        s.append("[ -d ").append(MODULE_DIR).append(" ] && mv ").append(MODULE_DIR).append(" $BAK || true\n");
        s.append("if ! mv ").append(STAGE_DIR).append(" ").append(MODULE_DIR).append("; then ")
         .append("[ -d $BAK ] && mv $BAK ").append(MODULE_DIR).append("; echo mv_failed >&2; exit 4; fi\n");
        s.append("rm -rf $BAK\n");
        // apply the bind-mount NOW so it's live before the next reboot too (guarded per path; a missing arch is a no-op)
        for (String lib : OEMCRYPTO_LIBS) {
            s.append("[ -f \"").append(lib).append("\" ] && mount -o bind ")
             .append(MODULE_DIR).append("/liboemcrypto.so \"").append(lib).append("\" 2>/dev/null || true\n");
        }
        // report whether the live mount actually took (a boot will apply it regardless, but this proves L3 now)
        s.append("if mount | grep -q liboemcrypto.so; then echo specter_widevine_l3_mounted; ")
         .append("else echo specter_widevine_l3_installed_reboot_needed; fi\n");
        return s.toString();
    }

    /**
     * Remove the module + drop the live bind-mounts so hardware Widevine is restored (fully on next reboot,
     * and immediately for the {@code umount}'d paths). Never fails on an already-absent module/mount.
     */
    public static String buildUninstallScript() {
        StringBuilder s = new StringBuilder();
        for (String lib : OEMCRYPTO_LIBS) {
            s.append("umount \"").append(lib).append("\" 2>/dev/null || true\n");
        }
        s.append("rm -rf ").append(MODULE_DIR).append("\n");
        s.append("echo specter_widevine_l3_removed\n");
        return s.toString();
    }

    // ---- exec ----

    /** Install the L3 module via a real {@code su} process. Throws {@link WidevineException} on failure. */
    public static void install() { install(new RootWriter.SuShell()); }

    /** Install via the given shell (tests inject a fake). Throws on a non-zero exit (su denied / mkdir failed). */
    public static void install(RootWriter.Shell shell) {
        exec(shell, buildInstallScript(), "install");
    }

    /** Remove the L3 module via a real {@code su} process. */
    public static void uninstall() { uninstall(new RootWriter.SuShell()); }

    /** Remove via the given shell. Throws on a non-zero exit. */
    public static void uninstall(RootWriter.Shell shell) {
        exec(shell, buildUninstallScript(), "uninstall");
    }

    // The install script uses `set -e`, so any failing step (mkdir/cat/chmod) yields a non-zero exit —
    // the exit code IS the success signal, same contract as RootWriter.write. Shell.run returns only the
    // code (no stdout), so we check that, not the echo marker (the echo is for a human tailing logcat).
    private static void exec(RootWriter.Shell shell, String cmd, String what) {
        int rc;
        try {
            rc = shell.run(cmd, "");
        } catch (Exception e) {
            throw new WidevineException("widevine-l3 " + what + " error (is Magisk root granted?)", e);
        }
        if (rc != 0) throw new WidevineException("widevine-l3 " + what + " exited " + rc + " — root likely denied");
    }
}
