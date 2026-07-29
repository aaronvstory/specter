package com.specter.module.gen;

/**
 * Keeps a fleet device pinned to its current OS version by blocking OTA updates at four layers — an update
 * that flips an A11 device to A13 breaks the income app (PairIP native VM SIGSEGVs on rooted A13), so the
 * device MUST stay put. This installs the proven 4-layer block (see the fleet-OTA notes) as a single removable
 * Magisk module + a set of live framework tweaks, so a virgin-phone setup gets it with no PC.
 *
 * <p>The four layers:
 * <ol>
 *   <li>{@code settings put global ota_disable_automatic_update 1} — framework auto-download off.</li>
 *   <li>{@code rm -f /data/ota_package/*} — purge any already-staged payload.</li>
 *   <li>Disable the GmsCore SystemUpdate components (the check-in path that fetches OTAs).</li>
 *   <li>A systemless {@code system/etc/hosts} overlay that blackholes the OTA CDN hosts to 127.0.0.1 — the
 *       one empirically-verified layer. This is the part that must be a Magisk module (mount at boot); the
 *       other three are live {@code su} commands applied once.</li>
 * </ol>
 *
 * <p>Same discipline as {@link WidevineL3}: the module is built in a staging dir then atomically {@code mv}'d
 * over the live path (never a half-written module for Magisk to load), and the whole thing is reversible —
 * {@link #buildUninstallScript} removes the module and re-enables the update components. Layer 4 needs a
 * reboot to mount; the setup flow reboots at the end anyway. Script building is pure/testable; {@link #install}
 * does the {@code su} exec.
 */
public final class OtaBlock {
    private OtaBlock() {}

    /** Magisk module id/dir. Matches the fleet-notes name {@code specter_ota_block} so a device already
     *  carrying the hand-applied module is detected as installed, not double-written. */
    public static final String MODULE_ID = "specter_ota_block";
    static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;
    static final String STAGE_DIR = MODULE_DIR + ".stage";

    /** The OTA CDN hosts pointed at 127.0.0.1. googlezip.net is the update-payload CDN the framework hits. */
    static final String[] BLACKHOLE_HOSTS = {
            "android.googleapis.com.googlezip.net",
            "update.googleapis.com.googlezip.net",
            "www.googleapis.com.googlezip.net",
    };

    /** The GmsCore components that drive OTA check-in; disabling them stops the fetch. */
    static final String[] GMS_UPDATE_COMPONENTS = {
            "com.google.android.gms/com.google.android.gms.update.SystemUpdateService",
            "com.google.android.gms/com.google.android.gms.update.SystemUpdateGcmTaskService",
    };

    public static final class OtaException extends RuntimeException {
        public OtaException(String m) { super(m); }
        public OtaException(String m, Throwable t) { super(m, t); }
    }

    // ---- module.prop ----

    public static String moduleProp() {
        return "id=" + MODULE_ID + "\n"
                + "name=Specter OTA Block\n"
                + "version=v1.0.0\n"
                + "versionCode=1\n"
                + "author=Specter\n"
                + "description=Blocks OS OTA updates so a fleet device stays on its current Android version "
                + "(an update can break the target apps). Overlays system/etc/hosts to blackhole the OTA CDN, "
                + "plus disables framework auto-update. Removing this module restores updates on reboot.\n";
    }

    // ---- system/etc/hosts overlay (Magisk mounts this over the real hosts at boot) ----

    /** The replacement {@code hosts} file: the default localhost lines + a 127.0.0.1 line per OTA CDN host. */
    public static String hostsFile() {
        StringBuilder s = new StringBuilder();
        s.append("127.0.0.1 localhost\n");
        s.append("::1 localhost\n");
        for (String h : BLACKHOLE_HOSTS) s.append("127.0.0.1 ").append(h).append("\n");
        return s.toString();
    }

    // ---- install script (pure, testable) ----

    /**
     * One {@code sh} program that (1) materialises the Magisk module ATOMICALLY in a staging dir then swaps it
     * into place (with a .bak rollback on rename failure, exactly like {@link WidevineL3}), and (2) applies the
     * three live layers now (settings/rm/pm disable) so the block is partly effective before the reboot too.
     * Heredoc carries the hosts body; the only interpolation is our own constants → no injection.
     */
    public static String buildInstallScript() {
        StringBuilder s = new StringBuilder();
        s.append("set -e\n");
        // build the module in a fresh staging dir
        s.append("rm -rf ").append(STAGE_DIR).append("\n");
        s.append("mkdir -p ").append(STAGE_DIR).append("/system/etc\n");
        s.append("cat > ").append(STAGE_DIR).append("/module.prop <<'SPECTER_EOF'\n");
        s.append(moduleProp());
        s.append("SPECTER_EOF\n");
        s.append("cat > ").append(STAGE_DIR).append("/system/etc/hosts <<'SPECTER_EOF'\n");
        s.append(hostsFile());
        s.append("SPECTER_EOF\n");
        s.append("chmod 0644 ").append(STAGE_DIR).append("/module.prop ")
         .append(STAGE_DIR).append("/system/etc/hosts\n");
        // atomic swap: prior module aside to .bak first, rename staging in, restore on failure, then drop .bak.
        // The backup-move is FATAL if it fails (no `|| true`): if MODULE_DIR exists and can't be moved aside, a
        // following `mv STAGE MODULE_DIR` would move staging INSIDE the still-present old dir and return 0 —
        // reporting success while the old module stays active. `set -e` (top of script) aborts instead.
        s.append("BAK=").append(MODULE_DIR).append(".bak\n");
        s.append("rm -rf $BAK\n");
        s.append("if [ -d ").append(MODULE_DIR).append(" ]; then mv ").append(MODULE_DIR).append(" $BAK; fi\n");
        s.append("if ! mv ").append(STAGE_DIR).append(" ").append(MODULE_DIR).append("; then ")
         .append("[ -d $BAK ] && mv $BAK ").append(MODULE_DIR).append("; echo mv_failed >&2; exit 4; fi\n");
        s.append("rm -rf $BAK\n");
        // ---- live layers (best-effort; a failure here shouldn't abort the whole install, the module is what
        // matters most and it's already in place) ----
        s.append("settings put global ota_disable_automatic_update 1 2>/dev/null || true\n");
        s.append("rm -f /data/ota_package/* 2>/dev/null || true\n");
        for (String comp : GMS_UPDATE_COMPONENTS) {
            s.append("pm disable ").append(comp).append(" 2>/dev/null || true\n");
        }
        s.append("echo specter_ota_block_installed_reboot_needed\n");
        return s.toString();
    }

    /**
     * Remove the module and re-enable the update components so the device can update again (hosts overlay is
     * gone on the next reboot; the framework/GMS layers are restored live). Never fails on already-absent state.
     */
    public static String buildUninstallScript() {
        StringBuilder s = new StringBuilder();
        // Remove the module, then VERIFY it's actually gone — a bare `rm -rf` whose failure (busy mount, RO fs)
        // is masked by the later `echo` would report success while the module stays installed. If the dir
        // survives, exit non-zero so the caller surfaces it (the live layers below are best-effort and don't
        // gate the result).
        s.append("rm -rf ").append(MODULE_DIR).append("\n");
        s.append("settings put global ota_disable_automatic_update 0 2>/dev/null || true\n");
        for (String comp : GMS_UPDATE_COMPONENTS) {
            s.append("pm enable ").append(comp).append(" 2>/dev/null || true\n");
        }
        s.append("if [ -d ").append(MODULE_DIR).append(" ]; then echo rm_failed >&2; exit 5; fi\n");
        s.append("echo specter_ota_block_removed\n");
        return s.toString();
    }

    // ---- detection ----

    /** True if the OTA-block Magisk module dir is present on disk. Best-effort; false on any su failure. */
    public static boolean installed(RootWriter.Shell shell) {
        try {
            String out = shell.runCapture("[ -d " + MODULE_DIR + " ] && echo y || echo n");
            return out != null && out.trim().equals("y");
        } catch (Throwable t) { return false; }
    }

    // ---- exec ----

    public static void install() { install(new RootWriter.SuShell()); }

    /** Install via the given shell. Throws {@link OtaException} on a non-zero exit (su denied / mkdir failed). */
    public static void install(RootWriter.Shell shell) { exec(shell, buildInstallScript(), "install"); }

    public static void uninstall() { uninstall(new RootWriter.SuShell()); }

    public static void uninstall(RootWriter.Shell shell) { exec(shell, buildUninstallScript(), "uninstall"); }

    // The install script uses `set -e` up to the module swap (the part that must not half-write); the live
    // layers are `|| true` so a device without one of them doesn't fail the install. The exit code is the
    // success signal, same contract as WidevineL3/RootWriter.
    private static void exec(RootWriter.Shell shell, String cmd, String what) {
        int rc;
        try {
            rc = shell.run(cmd, "");
        } catch (Exception e) {
            throw new OtaException("ota-block " + what + " error (is Magisk root granted?)", e);
        }
        if (rc != 0) throw new OtaException("ota-block " + what + " exited " + rc + " — root likely denied");
    }
}
