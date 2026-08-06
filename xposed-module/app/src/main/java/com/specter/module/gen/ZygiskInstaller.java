package com.specter.module.gen;

import android.content.Context;

import com.specter.module.SpoofLogic;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Self-installs the Specter Zygisk native layer — the per-app libc/prop hook module — from the APK's
 * bundled assets, so the user never has to flash a separate Magisk zip. The app checks on launch whether
 * the module is present + current and, if not, writes it via {@code su} and prompts a reboot.
 *
 * <p>The native layer closes the NATIVE read paths (libc {@code __system_property_get}, {@code stat}/
 * {@code fstatat} of reset-marker dirs, GLES strings) that the Java Xposed hooks can't reach. Without it,
 * a fingerprinter reading those natively sees the real device — so "is the native layer installed" is a
 * first-class health signal the UI surfaces, not a silent gap.
 *
 * <p>Assets (staged into {@code assets/zygisk/} by build-apk.sh): {@code arm64-v8a.so} (the companion),
 * {@code module.prop} (version-stamped from ../VERSION), {@code sepolicy.rule}. Install writes the standard
 * Magisk module layout to {@code /data/adb/modules/specter_zygisk/} atomically (stage + rename, same
 * discipline as {@link WidevineL3}); a reboot is required for Zygisk to load a new/updated companion.
 *
 * <p>The bundled-vs-installed version compare uses {@code module.prop}'s {@code version=} line, so an app
 * update carrying a newer native layer is detected as stale and offered for re-install. All the shell
 * building is pure/testable; asset extraction + the {@code su} exec are the only side effects.
 */
public final class ZygiskInstaller {
    private ZygiskInstaller() {}

    public static final String MODULE_ID = "specter_zygisk";
    static final String MODULE_DIR = "/data/adb/modules/" + MODULE_ID;
    /** Where the app stages the extracted assets before the su copy (app-private, always writable). */
    static final String ASSET_DIR = "zygisk";

    public static final class ZygiskException extends RuntimeException {
        public ZygiskException(String m) { super(m); }
        public ZygiskException(String m, Throwable t) { super(m, t); }
    }

    /** Health/version state of the on-device native layer. */
    public static final class Status {
        public final boolean installed;   // module dir + .so present on disk
        public final boolean current;     // installed AND version matches the bundled asset
        public final String installedVersion;   // e.g. "v0.13.1" or null
        public final String bundledVersion;     // e.g. "v0.14.0" or null (no asset)
        Status(boolean installed, boolean current, String iv, String bv) {
            this.installed = installed; this.current = current;
            this.installedVersion = iv; this.bundledVersion = bv;
        }
    }

    // ---- detection ----

    /** The version the APK bundles (from assets/zygisk/module.prop), or null if no asset is bundled. */
    public static String bundledVersion(Context ctx) {
        try (InputStream in = ctx.getAssets().open(ASSET_DIR + "/module.prop")) {
            return SpoofLogic.modulePropVersion(readAll(in));
        } catch (Throwable t) { return null; }
    }

    /**
     * Read the on-device state via {@code su} (module dir + .so presence + installed version). Never throws
     * — a denied su / absent module reports installed=false so the UI can offer to install.
     */
    public static Status status(Context ctx, RootWriter.Shell shell) {
        String bundled = bundledVersion(ctx);
        // One shell round-trip: print the installed module.prop + the on-disk .so's md5 if present, else a
        // marker. The md5 is what makes the sync ROBUST: a same-VERSION rebuild (common in dev, and possible
        // across builds since the version string doesn't always bump) changes the .so bytes but not the
        // version, so a version-only "current" check would leave a STALE native layer on device forever
        // (observed: a cpufreq/topology .so silently not re-synced). Comparing the md5 catches that.
        String probe = "if [ -f " + MODULE_DIR + "/zygisk/arm64-v8a.so ]; then cat " + MODULE_DIR
                + "/module.prop 2>/dev/null; echo \"__specter_md5__ $(md5sum " + MODULE_DIR
                + "/zygisk/arm64-v8a.so 2>/dev/null | cut -d' ' -f1)\"; else echo __specter_zygisk_absent__; fi";
        String out;
        try {
            out = shell.runCapture(probe);
        } catch (Throwable t) {
            return new Status(false, false, null, bundled);   // su denied / no capture -> treat as absent
        }
        if (out == null || out.contains("__specter_zygisk_absent__")) {
            return new Status(false, false, null, bundled);
        }
        String iv = SpoofLogic.modulePropVersion(out);
        String installedMd5 = extractMd5(out);
        String bundledMd5 = bundledSoMd5(ctx);
        // "current" = the .so BYTES match (md5). The version string bumps on every release (incl. Java-only
        // ones), so gating on it re-synced a byte-identical .so and armed a pointless "Reboot required" on
        // every app update. Byte-match is the truth; version compare is the fallback when a hash is missing.
        return new Status(true, SpoofLogic.isNativeCurrent(installedMd5, bundledMd5, iv, bundled), iv, bundled);
    }

    /** Pull the md5 the status probe printed ("__specter_md5__ <hash>"), or null if absent. */
    static String extractMd5(String probeOut) {
        if (probeOut == null) return null;
        int i = probeOut.indexOf("__specter_md5__");
        if (i < 0) return null;
        String rest = probeOut.substring(i + "__specter_md5__".length()).trim();
        int sp = rest.indexOf('\n');
        if (sp >= 0) rest = rest.substring(0, sp);
        rest = rest.trim();
        return rest.isEmpty() ? null : rest;
    }

    /** md5 of the .so the APK bundles (assets/zygisk/arm64-v8a.so), or null if unreadable. */
    static String bundledSoMd5(Context ctx) {
        try (InputStream in = ctx.getAssets().open(ASSET_DIR + "/arm64-v8a.so")) {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Throwable t) { return null; }
    }

    // ---- install ----

    /**
     * Extract the bundled assets to app-private files, then write the Magisk module via {@code su}. Atomic
     * (stage dir + rename). Throws {@link ZygiskException} on any failure (missing asset, su denied, copy
     * failure). A reboot is required afterward for Zygisk to load the companion.
     */
    public static void install(Context ctx) { install(ctx, new RootWriter.SuShell()); }

    public static void install(Context ctx, RootWriter.Shell shell) {
        File soLocal, propLocal, seLocal;
        try {
            soLocal = extractAsset(ctx, "arm64-v8a.so");
            propLocal = extractAsset(ctx, "module.prop");
            seLocal = extractAsset(ctx, "sepolicy.rule");   // optional; extractAsset throws if truly absent
        } catch (Throwable t) {
            throw new ZygiskException("Zygisk native layer isn't bundled in this build — rebuild with build-zygisk.sh first.", t);
        }
        String cmd = SpoofLogic.zygiskInstallScript(MODULE_DIR, soLocal.getAbsolutePath(), propLocal.getAbsolutePath(), seLocal.getAbsolutePath());
        int rc;
        try {
            rc = shell.run(cmd, "");
        } catch (Exception e) {
            throw new ZygiskException("Zygisk install failed (is Magisk root granted to Specter?)", e);
        }
        if (rc != 0) throw new ZygiskException("Zygisk install exited " + rc + " — root likely denied");
    }

    // ---- helpers ----

    private static File extractAsset(Context ctx, String name) throws Exception {
        File out = new File(ctx.getFilesDir(), "zygisk_" + name);
        try (InputStream in = ctx.getAssets().open(ASSET_DIR + "/" + name);
             OutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
        }
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[4096]; int n;
        while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }
}
