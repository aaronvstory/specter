package com.specter.module.gen;

import java.util.regex.Pattern;

/**
 * Durable store for captured app-data (login) tarballs, LINKED to the fingerprint that was active when the
 * capture was taken — so a later restore can re-apply the SAME device identity AND the login together, and
 * the app comes back up logged in on a coherent device.
 *
 * <p>SessionMigrator stages a capture at a root-owned {@code /data/local/tmp/specter/session-<pkg>.tgz},
 * which is volatile (a tmp clear / reboot can drop it) and not app-readable. This class copies that tarball
 * into a durable, app-owned home ({@code <filesDir>/appdata/<label>.tgz}) via {@code su}, and writes a tiny
 * sidecar {@code <label>.meta} recording: package, capture time, byte size, the fingerprint vault-label it
 * was captured under (the LINK), and a human device string. Restore copies the tarball back to the staging
 * path SessionMigrator expects, then the caller runs {@link SessionMigrator#restore}.
 *
 * <p>The command-building + parsing is pure/testable ({@link #buildCopyIn}, {@link #buildCopyOut},
 * {@link #serializeMeta}, {@link #parseMeta}); only {@link #save}/{@link #restore} do process exec.
 */
public final class AppDataVault {
    private final java.io.File dir;

    public AppDataVault(java.io.File filesDir) {
        dir = new java.io.File(filesDir, "appdata");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    // Same package grammar as the rest of the su boundary — the ONLY app-controlled value interpolated.
    private static final Pattern PKG = SessionMigratorPkg.PKG;
    // A vault label is our own MMDDYY-Day-HHMM[-name] scheme; restrict to a safe charset so it can never
    // carry a shell metacharacter into the su copy commands.
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9_.-]{1,80}");

    public static boolean validLabel(String s) { return s != null && LABEL.matcher(s).matches(); }
    public static boolean validPkg(String s) { return s != null && PKG.matcher(s).matches(); }

    /** One saved app-data artifact: the login tarball + its link to a fingerprint. */
    public static final class Entry {
        public final String label;        // filename stem (== the linked fingerprint's vault label)
        public final String pkg;          // the app the login belongs to
        public final long savedAt;        // capture time, millis
        public final long sizeBytes;      // tarball size
        public final String fingerprint;  // the fingerprint vault-label active at capture (the LINK), "" if none
        public final String device;       // human device string, e.g. "Motorola razr 2020"
        Entry(String label, String pkg, long savedAt, long sizeBytes, String fingerprint, String device) {
            this.label = label; this.pkg = pkg; this.savedAt = savedAt; this.sizeBytes = sizeBytes;
            this.fingerprint = fingerprint == null ? "" : fingerprint;
            this.device = device == null ? "" : device;
        }
    }

    /** Serialize metadata to the flat {@code key=value} sidecar text (one per line). Values are single-line
     *  by construction (pkg/label/device are constrained), so no escaping is needed. */
    public static String serializeMeta(String pkg, long savedAt, long sizeBytes, String fingerprint, String device) {
        return "pkg=" + pkg + "\n"
                + "savedAt=" + savedAt + "\n"
                + "sizeBytes=" + sizeBytes + "\n"
                + "fingerprint=" + (fingerprint == null ? "" : fingerprint) + "\n"
                + "device=" + (device == null ? "" : device) + "\n";
    }

    /** Parse a sidecar back into an Entry (label comes from the filename). Returns null if the text is junk. */
    public static Entry parseMeta(String label, String text) {
        if (text == null) return null;
        String pkg = "", fingerprint = "", device = "";
        long savedAt = 0, sizeBytes = 0;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq), v = line.substring(eq + 1);
            switch (k) {
                case "pkg": pkg = v; break;
                case "savedAt": try { savedAt = Long.parseLong(v.trim()); } catch (Exception ignored) {} break;
                case "sizeBytes": try { sizeBytes = Long.parseLong(v.trim()); } catch (Exception ignored) {} break;
                case "fingerprint": fingerprint = v; break;
                case "device": device = v; break;
                default: break;
            }
        }
        if (pkg.isEmpty()) return null;
        return new Entry(label, pkg, savedAt, sizeBytes, fingerprint, device);
    }

    /** su command: copy the staged (root-owned) capture INTO the vault + make it app-readable. Both paths are
     *  validated before interpolation. Fails if the staged tarball is missing. */
    public static String buildCopyIn(String stagedTar, String destTar) {
        return "test -f '" + stagedTar + "' || { echo 'no staged capture'; exit 3; }; "
                + "cp '" + stagedTar + "' '" + destTar + "' && chmod 644 '" + destTar + "' && "
                + "echo copied $(stat -c %s '" + destTar + "') bytes";
    }

    /** su command: copy a vaulted tarball back OUT to the staging path SessionMigrator.restore reads. */
    public static String buildCopyOut(String vaultTar, String stagedTar) {
        return "test -f '" + vaultTar + "' || { echo 'no vaulted appdata'; exit 3; }; "
                + "mkdir -p '" + SessionMigrator.SESSION_DIR + "' && cp '" + vaultTar + "' '" + stagedTar
                + "' && chmod 644 '" + stagedTar + "' && echo ok";
    }

    java.io.File tarFile(String label) { return new java.io.File(dir, label + ".tgz"); }
    java.io.File metaFile(String label) { return new java.io.File(dir, label + ".meta"); }

    /** Copy the freshly-staged capture for {@code pkg} into the vault under {@code label}, linked to the
     *  active {@code fingerprint} vault-label. Returns null on success, else an error message. Runs blocking
     *  su — call off the UI thread. */
    public String save(String label, String pkg, String fingerprint, String device) {
        if (!validLabel(label) || !validPkg(pkg)) return "invalid label/package";
        if (fingerprint != null && !fingerprint.isEmpty() && !validLabel(fingerprint)) return "invalid fingerprint label";
        String staged = SessionMigrator.tarPath(pkg);
        String dest = tarFile(label).getAbsolutePath();
        if (!isSafe(staged) || !isSafe(dest)) return "unsafe path";
        try {
            String out = new SessionMigrator.SuShell().run(buildCopyIn(staged, dest)).output;
            if (!new java.io.File(dest).exists()) return "copy failed: " + out;
            long size = new java.io.File(dest).length();
            String meta = serializeMeta(pkg, System.currentTimeMillis(), size, fingerprint, device);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(metaFile(label))) {
                fos.write(meta.getBytes("UTF-8"));
            }
            return null;
        } catch (Exception e) { return e.getMessage(); }
    }

    /** Copy a vaulted tarball back to the staging path so the caller can run SessionMigrator.restore.
     *  Returns null on success, else an error. Runs blocking su — call off the UI thread. */
    public String restoreToStaging(String label) {
        Entry e = get(label);
        if (e == null) return "no such saved app-data";
        String vaultTar = tarFile(label).getAbsolutePath();
        String staged = SessionMigrator.tarPath(e.pkg);
        if (!isSafe(vaultTar) || !isSafe(staged)) return "unsafe path";
        try {
            String out = new SessionMigrator.SuShell().run(buildCopyOut(vaultTar, staged)).output;
            return out != null && out.contains("ok") ? null : ("copy-out failed: " + out);
        } catch (Exception ex) { return ex.getMessage(); }
    }

    /** The package a saved app-data belongs to (needed to run SessionMigrator.restore after restoreToStaging). */
    public String pkgOf(String label) { Entry e = get(label); return e == null ? null : e.pkg; }

    /** su command: bundle a saved login's tarball + meta into a single portable tar in /sdcard/Download so it
     *  can be moved to another device. Named specter-login-&lt;label&gt;.tar. Returns the command; both the vault
     *  dir and the label are validated before this is built. */
    public static String buildExportCommand(String vaultDir, String label, String dest) {
        return "test -f '" + vaultDir + "/" + label + ".tgz' || { echo 'no such login'; exit 3; }; "
                + "tar cf '" + dest + "' -C '" + vaultDir + "' '" + label + ".tgz' '" + label + ".meta' && "
                + "chmod 644 '" + dest + "' && echo exported $(stat -c %s '" + dest + "') bytes";
    }

    /** Export a saved login (tarball + meta) to /sdcard/Download. Returns the dest path, or null on failure.
     *  Runs blocking su — call off the UI thread. */
    public String exportToDownloads(String label) {
        if (!validLabel(label) || get(label) == null) return null;
        String dest = "/sdcard/Download/specter-login-" + label + ".tar";
        String vaultDir = dir.getAbsolutePath();
        if (!isSafe(vaultDir) || !isSafe(dest)) return null;
        try {
            String out = new SessionMigrator.SuShell().run(buildExportCommand(vaultDir, label, dest)).output;
            return out != null && out.contains("exported") ? dest : null;
        } catch (Exception e) { return null; }
    }

    /** su command: extract a portable login bundle (from {@link #buildExportCommand}) back INTO the vault dir.
     *  Refuses any entry that isn't exactly {@code <something>.tgz}/{@code <something>.meta} (extraction runs as
     *  root into the app's dir, so no traversal / other files). */
    public static String buildImportCommand(String srcTar, String vaultDir) {
        return "test -f '" + srcTar + "' || { echo 'no such bundle'; exit 3; }; "
                + "tar tf '" + srcTar + "' | grep -qvE '^[A-Za-z0-9_.-]+[.](tgz|meta)$' && { echo 'bundle has unexpected entries'; exit 4; }; "
                + "tar xf '" + srcTar + "' -C '" + vaultDir + "' && echo imported";
    }

    /** Import a portable login bundle from /sdcard/Download into the vault. Returns the imported label on
     *  success, or null. Runs blocking su — call off the UI thread. */
    public String importFromDownloads(java.io.File src) {
        if (src == null) return null;
        String path = src.getAbsolutePath();
        // the bundle name encodes the label: specter-login-<label>.tar
        String name = src.getName();
        if (!name.startsWith("specter-login-") || !name.endsWith(".tar")) return null;
        String label = name.substring("specter-login-".length(), name.length() - 4);
        if (!validLabel(label)) return null;
        String vaultDir = dir.getAbsolutePath();
        if (!path.startsWith("/sdcard/Download/") || path.contains("..") || !isSafe(path) || !isSafe(vaultDir)) return null;
        try {
            String out = new SessionMigrator.SuShell().run(buildImportCommand(path, vaultDir)).output;
            // The extracted .tgz lands root-owned; the app can still read it (dir is app-owned, mode 644).
            return out != null && out.contains("imported") && metaFile(label).exists() ? label : null;
        } catch (Exception e) { return null; }
    }

    public Entry get(String label) {
        if (!validLabel(label)) return null;
        java.io.File mf = metaFile(label);
        if (!mf.exists()) return null;
        return parseMeta(label, readFile(mf));
    }

    /** All saved app-data entries, newest first. Pass a non-null {@code pkgFilter} to keep only that app. */
    public java.util.List<Entry> list(String pkgFilter) {
        java.util.List<Entry> out = new java.util.ArrayList<>();
        java.io.File[] metas = dir.listFiles((d, n) -> n.endsWith(".meta"));
        if (metas == null) return out;
        for (java.io.File mf : metas) {
            String label = mf.getName().substring(0, mf.getName().length() - 5);
            Entry e = parseMeta(label, readFile(mf));
            if (e == null) continue;
            if (pkgFilter != null && !pkgFilter.isEmpty() && !pkgFilter.equals(e.pkg)) continue;
            if (!tarFile(label).exists()) continue;   // orphan meta (tarball gone) — skip
            out.add(e);
        }
        out.sort((a, b) -> Long.compare(b.savedAt, a.savedAt));
        return out;
    }

    /** Delete a saved app-data (tarball + meta). Returns true if anything was removed. Note: the tarball may
     *  be root-owned (copied in via su), but it lives in this app-owned dir, so unlink succeeds. */
    public boolean delete(String label) {
        if (!validLabel(label)) return false;
        boolean a = tarFile(label).delete();
        boolean b = metaFile(label).delete();
        return a || b;
    }

    /** Rename a saved app-data's NAME (after the label's timestamp prefix), keeping the timestamp. Renames the
     *  .tgz + .meta pair. Returns the new label, or null if the source is missing / target exists / rename
     *  failed. The link to the fingerprint (stored INSIDE the meta) is preserved unchanged. */
    public String rename(String oldLabel, String newName) {
        if (!validLabel(oldLabel) || metaFile(oldLabel).exists() == false) return null;
        String[] p = oldLabel.split("-");
        String prefix = p.length >= 3 ? p[0] + "-" + p[1] + "-" + p[2] : oldLabel;
        String clean = sanitizeName(newName);
        String base = clean.isEmpty() ? prefix : prefix + "-" + clean;
        if (base.equals(oldLabel)) return oldLabel;
        String target = base;
        for (int i = 2; metaFile(target).exists() && i < 1000; i++) target = base + "-" + i;
        if (!validLabel(target)) return null;
        // Move the tarball first; if it exists, move meta too. Roll back the tarball move on meta failure.
        java.io.File srcTar = tarFile(oldLabel), dstTar = tarFile(target);
        java.io.File srcMeta = metaFile(oldLabel), dstMeta = metaFile(target);
        boolean tarMoved = !srcTar.exists() || srcTar.renameTo(dstTar);
        if (!tarMoved) return null;
        if (!srcMeta.renameTo(dstMeta)) {
            //noinspection ResultOfMethodCallIgnored
            if (dstTar.exists()) dstTar.renameTo(srcTar);   // roll back
            return null;
        }
        return target;
    }

    /** When a FINGERPRINT is renamed, point every app-data that linked to the old label at the new one, so the
     *  bundle stays intact. Returns how many metas were updated. */
    public int relinkFingerprint(String oldFpLabel, String newFpLabel) {
        int n = 0;
        java.io.File[] metas = dir.listFiles((d, name) -> name.endsWith(".meta"));
        if (metas == null) return 0;
        for (java.io.File mf : metas) {
            String label = mf.getName().substring(0, mf.getName().length() - 5);
            Entry e = parseMeta(label, readFile(mf));
            if (e == null || !oldFpLabel.equals(e.fingerprint)) continue;
            String meta = serializeMeta(e.pkg, e.savedAt, e.sizeBytes, newFpLabel, e.device);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(mf)) {
                fos.write(meta.getBytes("UTF-8")); n++;
            } catch (Exception ignored) {}
        }
        return n;
    }

    /** Sanitize a user-typed name to the label charset (letters/digits/_.-), space -> _, capped at 40. */
    static String sanitizeName(String name) {
        if (name == null) return "";
        StringBuilder b = new StringBuilder();
        for (char ch : name.trim().toCharArray()) {
            if (Character.isLetterOrDigit(ch) || ch == '_' || ch == '.' || ch == '-') b.append(ch);
            else if (ch == ' ') b.append('_');
            if (b.length() >= 40) break;
        }
        return b.toString();
    }

    // A path is safe to interpolate into su if it's absolute and has no shell metacharacter / quote / traversal.
    private static boolean isSafe(String p) {
        if (p == null || !p.startsWith("/") || p.contains("..")) return false;
        for (char c : p.toCharArray()) {
            if (c == '\'' || c == '"' || c == '`' || c == '$' || c == ';' || c == '|'
                    || c == '&' || c == '\n' || c == '\r' || c == '*' || c == '?') return false;
        }
        return true;
    }

    private static String readFile(java.io.File f) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) { return null; }
    }

    /** Tiny holder so this class can reuse SessionMigrator's package grammar without a hard field ref. */
    static final class SessionMigratorPkg {
        static final Pattern PKG = Pattern.compile("[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+");
    }
}
