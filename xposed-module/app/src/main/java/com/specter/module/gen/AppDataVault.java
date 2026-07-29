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
    // carry a shell metacharacter into the su copy commands. The FIRST char must be alphanumeric — a label
    // (hence a "<label>.tgz" tar member arg) can never start with '-' (tar would read it as an OPTION) or '.'
    // (a hidden file). Our labels always start with a digit (MMDDYY) so this rejects nothing legitimate.
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}");

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

    /** Parse a sidecar back into an Entry (label comes from the filename). Returns null if the text is junk OR
     *  fails validation — an IMPORTED meta is untrusted, and its {@code pkg}/{@code fingerprint} flow into
     *  su-command paths, so they must clear the same grammar as a freshly-built entry. Rejects an invalid pkg,
     *  an invalid (non-empty) fingerprint label, and any control char / negative number. */
    public static Entry parseMeta(String label, String text) {
        if (text == null) return null;
        String pkg = "", fingerprint = "", device = "";
        long savedAt = 0, sizeBytes = 0;
        for (String line : text.split("\n")) {
            int eq = line.indexOf('=');
            if (eq < 0) continue;
            String k = line.substring(0, eq), v = line.substring(eq + 1);
            if (hasControlChar(v)) return null;   // no newlines/control chars in a value
            switch (k) {
                case "pkg": pkg = v; break;
                case "savedAt": try { savedAt = Long.parseLong(v.trim()); } catch (Exception ignored) {} break;
                case "sizeBytes": try { sizeBytes = Long.parseLong(v.trim()); } catch (Exception ignored) {} break;
                case "fingerprint": fingerprint = v; break;
                case "device": device = v; break;
                default: break;
            }
        }
        if (!validPkg(pkg)) return null;                                   // pkg flows into tarPath() — must be valid
        if (!fingerprint.isEmpty() && !validLabel(fingerprint)) return null;   // linked-label flows into vault.load
        if (savedAt < 0 || sizeBytes < 0) return null;
        return new Entry(label, pkg, savedAt, sizeBytes, fingerprint, device);
    }

    private static boolean hasControlChar(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 0x20) return true;
        return false;
    }

    /** su command: copy the staged (root-owned) capture INTO the vault + make it app-readable. Both paths are
     *  validated before interpolation. Fails if the staged tarball is missing. */
    public static String buildCopyIn(String stagedTar, String destTar) {
        // Atomic: copy to a temp then rename over the final path, so a killed/failed cp can only leave a stale
        // .tmp (cleaned next time), never a truncated destTar that dest.exists() would accept as good.
        return "test -f '" + stagedTar + "' || { echo 'no staged capture'; exit 3; }; "
                + "cp '" + stagedTar + "' '" + destTar + ".tmp' && chmod 644 '" + destTar + ".tmp' && "
                + "mv -f '" + destTar + ".tmp' '" + destTar + "' && "
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
            // Check BOTH the exit code AND the success marker — not just dest.exists(): a stale tarball from a
            // previous save would make exists() true even when THIS cp failed, silently pairing new metadata
            // with old bytes. buildCopyIn now copies to a temp then renames, so a partial copy can't be seen.
            SessionMigrator.Result r = new SessionMigrator.SuShell().run(buildCopyIn(staged, dest));
            if (r.code != 0 || r.output == null || !r.output.contains("copied")) return "copy failed: " + r.output;
            long size = new java.io.File(dest).length();
            if (size <= 0) return "copy produced an empty file";
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
        if (!validPkg(e.pkg)) return "saved app-data has an invalid package";   // defense-in-depth (parseMeta already checks)
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

    /** The single folder all Specter exports go to (auto-created on first export). Kept in sync with
     *  {@code Vault.EXPORT_DIR} — both exporters write here so the user has one place to find shared files. */
    public static final String EXPORT_DIR = "/sdcard/Download/Specter";

    /** su command: bundle a saved AppData's tarball + meta into a single portable tar in the export folder so it
     *  can be moved to another device. Named specter-login-&lt;label&gt;.tar. Returns the command; both the vault
     *  dir and the label are validated before this is built. Auto-creates the export folder. */
    public static String buildExportCommand(String vaultDir, String label, String dest, String exportDir) {
        return "test -f '" + vaultDir + "/" + label + ".tgz' || { echo 'no such login'; exit 3; }; "
                + "mkdir -p '" + exportDir + "' && "
                + "tar cf '" + dest + "' -C '" + vaultDir + "' '" + label + ".tgz' '" + label + ".meta' && "
                + "chmod 644 '" + dest + "' && echo exported $(stat -c %s '" + dest + "') bytes";
    }

    /** Export a saved AppData (tarball + meta) to {@link #EXPORT_DIR}. Returns the dest path, or null on failure.
     *  Runs blocking su — call off the UI thread. */
    public String exportToDownloads(String label) {
        if (!validLabel(label) || get(label) == null) return null;
        String dest = EXPORT_DIR + "/specter-login-" + label + ".tar";
        String vaultDir = dir.getAbsolutePath();
        if (!isSafe(vaultDir) || !isSafe(dest) || !isSafe(EXPORT_DIR)) return null;
        try {
            String out = new SessionMigrator.SuShell().run(buildExportCommand(vaultDir, label, dest, EXPORT_DIR)).output;
            return out != null && out.contains("exported") ? dest : null;
        } catch (Exception e) { return null; }
    }

    /** su command: extract a portable login bundle (from {@link #buildExportCommand}) back INTO the vault dir.
     *  The bundle is UNTRUSTED (it came from /sdcard) and is extracted as ROOT into the app's own dir, so the
     *  guards are strict — the bundle must contain EXACTLY the two REGULAR files {@code <label>.tgz} and
     *  {@code <label>.meta} for the expected label, and NOTHING else:
     *   - TYPE guard: `tar tvf` prefixes symlinks with 'l' and hardlinks with 'h'; refuse either (a name-only
     *     listing hides a symlink target, so a symlinked `<label>.tgz` would let a later root `cp` write
     *     THROUGH it out of the vault — the same class of bug closed in SessionMigrator).
     *   - EXACT-SET guard: the sorted member list must be exactly `<label>.meta\n<label>.tgz` — no extra
     *     entries, no traversal (`/` and `..` can't appear in a label), no mismatched label. */
    public static String buildImportCommand(String srcTar, String vaultDir, String label) {
        return "test -f '" + srcTar + "' || { echo 'no such bundle'; exit 3; }; "
                // TYPE guard: EVERY entry must be a REGULAR file. `tar tvf` prefixes each line with the type
                // char ('-' regular, 'd' dir, 'l' symlink, 'h' hardlink, 'c'/'b' device, 'p' fifo, 's' socket).
                // Anything that isn't '-' is refused (extraction runs as root into the app dir, so a symlink OR
                // a device/fifo node could be a write primitive / block on open).
                + "if tar tvf '" + srcTar + "' 2>/dev/null | grep -qvE '^-'; then echo 'bundle has a non-regular-file entry'; exit 5; fi; "
                + "got=$(tar tf '" + srcTar + "' | sort | tr '\\n' '|'); "
                + "[ \"$got\" = '" + label + ".meta|" + label + ".tgz|' ] || { echo 'bundle members are not exactly " + label + ".{tgz,meta}'; exit 4; }; "
                + "tar xf '" + srcTar + "' -C '" + vaultDir + "' && echo imported";
    }

    /** su command: build a COMBINED bundle (fingerprint envelope {@code <label>.json} + AppData
     *  {@code <label>.tgz} + {@code <label>.meta}) into one portable tar, so a Fingerprint and its AppData
     *  travel together to another device. The {@code .json} is staged into the vault dir by the caller first.
     *  Auto-creates the export folder. */
    public static String buildComboExportCommand(String vaultDir, String label, String dest, String exportDir) {
        return "test -f '" + vaultDir + "/" + label + ".tgz' || { echo 'no such login'; exit 3; }; "
                + "test -f '" + vaultDir + "/" + label + ".json' || { echo 'no such fingerprint'; exit 3; }; "
                + "mkdir -p '" + exportDir + "' && "
                + "tar cf '" + dest + "' -C '" + vaultDir + "' '" + label + ".json' '" + label + ".tgz' '" + label + ".meta' && "
                + "chmod 644 '" + dest + "' && echo exported $(stat -c %s '" + dest + "') bytes";
    }

    /** su command: extract a COMBINED bundle (from {@link #buildComboExportCommand}) into {@code destDir}. Same
     *  strict guards as {@link #buildImportCommand} — EVERY entry must be a regular file, and the member set must
     *  be EXACTLY {@code <label>.json + <label>.meta + <label>.tgz}, nothing else. Extracted to an app-owned
     *  temp dir (NOT a vault) so the caller can validate + dispatch each part safely.
     *
     *  <p>TOCTOU-safe: the untrusted /sdcard tar is COPIED to an app-owned staging path ({@code destDir/src.tar},
     *  which another app can't swap) FIRST, and all validation + extraction run against that COPY — so the bytes
     *  we validate are exactly the bytes we extract, even if the /sdcard original is swapped mid-script. */
    public static String buildComboImportCommand(String srcTar, String destDir, String label) {
        String staged = destDir + "/src.tar";
        return "test -f '" + srcTar + "' || { echo 'no such bundle'; exit 3; }; "
                + "mkdir -p '" + destDir + "' && cp '" + srcTar + "' '" + staged + "' || { echo 'stage failed'; exit 6; }; "
                // From here on, ONLY the app-owned staged copy is touched (immune to a /sdcard swap).
                + "if tar tvf '" + staged + "' 2>/dev/null | grep -qvE '^-'; then echo 'bundle has a non-regular-file entry'; exit 5; fi; "
                + "got=$(tar tf '" + staged + "' | sort | tr '\\n' '|'); "
                + "[ \"$got\" = '" + label + ".json|" + label + ".meta|" + label + ".tgz|' ] "
                + "|| { echo 'bundle members are not exactly " + label + ".{json,tgz,meta}'; exit 4; }; "
                // Extract as root, chmod the members world-readable (tar restores archived modes, so without this
                // the non-root app may not be able to read them), and drop the staged copy.
                + "tar xf '" + staged + "' -C '" + destDir + "' && "
                + "chmod 644 '" + destDir + "/" + label + ".json' '" + destDir + "/" + label + ".tgz' '" + destDir + "/" + label + ".meta' && "
                + "rm -f '" + staged + "' && echo imported";
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
            String out = new SessionMigrator.SuShell().run(buildImportCommand(path, vaultDir, label)).output;
            // The extracted .tgz lands root-owned; the app can still read it (dir is app-owned, mode 644).
            // Re-validate via parseMeta (which now enforces pkg/fingerprint grammar) — a bundle can't smuggle
            // a bad pkg into a later restoreToStaging path.
            if (out == null || !out.contains("imported")) return null;
            return get(label) != null ? label : null;
        } catch (Exception e) { return null; }
    }

    /** Export a COMBINED bundle: the AppData ({@code label}) + its linked Fingerprint envelope, as one tar in
     *  {@link #EXPORT_DIR}. {@code fpEnvelopeJson} is the fingerprint envelope built by the Fingerprint vault
     *  (VaultPortable.buildEnvelope). Returns the dest path, or null. Runs blocking su — call off the UI thread. */
    public String exportCombo(String label, String fpEnvelopeJson) {
        if (!validLabel(label) || get(label) == null || fpEnvelopeJson == null) return null;
        String vaultDir = dir.getAbsolutePath();
        String dest = EXPORT_DIR + "/specter-combo-" + label + ".tar";
        if (!isSafe(vaultDir) || !isSafe(dest) || !isSafe(EXPORT_DIR)) return null;
        java.io.File staged = new java.io.File(dir, label + ".json");   // stage the envelope alongside the .tgz/.meta
        try {
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(staged)) {
                fos.write(fpEnvelopeJson.getBytes("UTF-8"));
            }
            String out = new SessionMigrator.SuShell().run(buildComboExportCommand(vaultDir, label, dest, EXPORT_DIR)).output;
            return out != null && out.contains("exported") ? dest : null;
        } catch (Exception e) { return null; }
        finally { //noinspection ResultOfMethodCallIgnored
            staged.delete(); }   // never leave the staged .json in the vault dir
    }

    /** Extract a COMBINED bundle to a fresh app-owned temp dir and return it, so the caller can validate + import
     *  each part (the .json to the Fingerprint vault, the .tgz/.meta to this vault). Returns null on failure. The
     *  caller MUST delete the returned dir when done. Runs blocking su — call off the UI thread. */
    public java.io.File importComboToTemp(java.io.File src) {
        if (src == null) return null;
        String name = src.getName();
        if (!name.startsWith("specter-combo-") || !name.endsWith(".tar")) return null;
        String label = name.substring("specter-combo-".length(), name.length() - 4);
        if (!validLabel(label)) return null;
        String path = src.getAbsolutePath();
        if (!path.startsWith("/sdcard/Download/") || path.contains("..") || !isSafe(path)) return null;
        java.io.File tmp = new java.io.File(dir.getParentFile(), "combo-import-" + label);
        //noinspection ResultOfMethodCallIgnored
        tmp.mkdirs();
        String tmpPath = tmp.getAbsolutePath();
        if (!isSafe(tmpPath)) { deleteDir(tmp); return null; }
        try {
            String out = new SessionMigrator.SuShell().run(buildComboImportCommand(path, tmpPath, label)).output;
            if (out == null || !out.contains("imported")) { deleteDir(tmp); return null; }
            return tmp;
        } catch (Exception e) { deleteDir(tmp); return null; }
    }

    /** Ingest an AppData pair ({@code <label>.tgz} + {@code <label>.meta}) from an app-owned temp dir (the
     *  output of {@link #importComboToTemp}) INTO this vault. Validates the meta (pkg/fingerprint grammar) before
     *  committing, and won't overwrite an existing label. Returns the label on success, else null. No su needed —
     *  both dirs are app-owned. */
    public String ingestPairFromDir(java.io.File tmpDir, String label) {
        if (tmpDir == null || !validLabel(label)) return null;
        java.io.File srcTar = new java.io.File(tmpDir, label + ".tgz");
        java.io.File srcMeta = new java.io.File(tmpDir, label + ".meta");
        if (!srcTar.exists() || !srcMeta.exists()) return null;
        // Validate the meta up front — a bundle's pkg/fingerprint flow into later su paths (restoreToStaging), so
        // it must clear the same grammar as a freshly-captured entry (parseMeta enforces it).
        if (parseMeta(label, readFile(srcMeta)) == null) return null;
        java.io.File dstTar = tarFile(label), dstMeta = metaFile(label);
        if (dstTar.exists() || dstMeta.exists()) return null;   // don't clobber an existing entry
        // Copy (not rename — cross-dir rename can fail across mounts), then verify both landed.
        if (!copyFile(srcTar, dstTar)) return null;
        if (!copyFile(srcMeta, dstMeta)) { //noinspection ResultOfMethodCallIgnored
            dstTar.delete(); return null; }
        return get(label) != null ? label : null;
    }

    private static boolean copyFile(java.io.File src, java.io.File dst) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            byte[] buf = new byte[8192]; int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            return true;
        } catch (Exception e) { //noinspection ResultOfMethodCallIgnored
            dst.delete(); return false; }
    }

    /** The path to the fingerprint envelope inside a combo temp dir, for the caller to hand to the FP vault. */
    public static java.io.File comboJson(java.io.File tmpDir, String label) {
        return (tmpDir == null || !validLabel(label)) ? null : new java.io.File(tmpDir, label + ".json");
    }

    /** The label a combo/login bundle file encodes, or null if the name doesn't match. Used by the UI to route. */
    public static String labelOfBundle(String fileName) {
        if (fileName == null) return null;
        String stem = null;
        if (fileName.startsWith("specter-combo-") && fileName.endsWith(".tar"))
            stem = fileName.substring("specter-combo-".length(), fileName.length() - 4);
        else if (fileName.startsWith("specter-login-") && fileName.endsWith(".tar"))
            stem = fileName.substring("specter-login-".length(), fileName.length() - 4);
        return validLabel(stem) ? stem : null;
    }

    /** Recursively delete an app-owned temp dir (best-effort). */
    public static void deleteDir(java.io.File d) {
        if (d == null) return;
        java.io.File[] kids = d.listFiles();
        if (kids != null) for (java.io.File k : kids) { //noinspection ResultOfMethodCallIgnored
            k.delete(); }
        //noinspection ResultOfMethodCallIgnored
        d.delete();
    }

    public Entry get(String label) {
        if (!validLabel(label)) return null;
        java.io.File mf = metaFile(label);
        if (!mf.exists()) return null;
        return parseMeta(label, readFile(mf));
    }

    /** Repoint the fingerprint link of ONE specific AppData entry (by its own label) — unlike
     *  {@link #relinkFingerprint}, which sweeps every entry matching an old fingerprint label. Used after a
     *  combined-bundle import, where the incoming fingerprint label is UNTRUSTED and could collide with a
     *  pre-existing local entry's link (a blind sweep would then corrupt that unrelated pairing). Returns true
     *  if the entry's meta was rewritten. */
    public boolean relinkOne(String label, String newFpLabel) {
        if (!validLabel(label) || !validLabel(newFpLabel)) return false;
        Entry e = get(label);
        if (e == null) return false;
        String meta = serializeMeta(e.pkg, e.savedAt, e.sizeBytes, newFpLabel, e.device);
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(metaFile(label))) {
            fos.write(meta.getBytes("UTF-8")); return true;
        } catch (Exception ex) { return false; }
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
        // Both must be valid labels: oldFpLabel is compared with .equals (null -> NPE) and newFpLabel is
        // written INTO the meta (an invalid/control-char value would corrupt it). Reject up front.
        if (!validLabel(oldFpLabel) || !validLabel(newFpLabel)) return 0;
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
