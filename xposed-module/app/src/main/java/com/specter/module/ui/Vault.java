package com.specter.module.ui;

import android.content.Context;

import com.specter.module.gen.IdentityService;

import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * On-device profile vault: save a generated identity under a name + timestamp label, list saved ones,
 * restore (re-apply an exact past device), and delete. Each entry is one JSON file
 * {@code files/vault/<label>.json} holding the full flat profile plus a {@code _saved_at} millis field,
 * so restoring re-applies the SAME identity (same unique IDs) — intended, not a fresh draw.
 *
 * Label scheme (user request): {@code MMDDYY-DayAbbr-HHMM[-Name]}, e.g. {@code 072626-Sun-1924-BobPhone}.
 * The name is optional and sanitized to a filename-safe token.
 */
public final class Vault {
    private final File dir;

    public Vault(Context ctx) {
        dir = new File(ctx.getFilesDir(), "vault");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
    }

    /** One saved entry: its label (== filename stem), the device it represents, and the apps it was
     *  applied to (comma-separated package names) — shown in the list UI. */
    public static final class Entry {
        public final String label;
        public final String device;   // e.g. "Samsung SM-A505F" for the list row
        public final long savedAt;
        public final String targets;  // packages this profile was applied to (comma-sep), "" if unknown
        Entry(String label, String device, long savedAt, String targets) {
            this.label = label; this.device = device; this.savedAt = savedAt;
            this.targets = targets == null ? "" : targets;
        }
    }

    /** Build the timestamp label for "now" + an optional user name. Uses the calendar (deliberate:
     *  this is a human-facing label, not part of the seeded/byte-parity profile). */
    public static String makeLabel(String name) {
        Calendar c = Calendar.getInstance();
        String[] days = {"Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"};
        String stamp = String.format(Locale.US, "%02d%02d%02d-%s-%02d%02d",
                c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.YEAR) % 100,
                days[c.get(Calendar.DAY_OF_WEEK) - 1], c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE));
        String clean = sanitize(name);
        return clean.isEmpty() ? stamp : stamp + "-" + clean;
    }

    private static String sanitize(String name) {
        if (name == null) return "";
        StringBuilder b = new StringBuilder();
        for (char ch : name.trim().toCharArray()) {
            if (Character.isLetterOrDigit(ch)) b.append(ch);
            else if (ch == ' ' || ch == '_' || ch == '-') b.append('_');
            if (b.length() >= 40) break;   // keep filenames sane
        }
        return b.toString();
    }

    /** Save {@code profile} (which was APPLIED to {@code targets}) under a label derived from {@code name}.
     *  Returns the label used, or {@code null} if the write FAILED (so the caller reports the truth instead
     *  of a false "Saved"). Only applied profiles are saved (see the caller) — a vault entry always
     *  represents an identity that actually reached at least one app. */
    public String save(String name, Map<String, String> profile, String targets) {
        String base = makeLabel(name);
        // Disambiguate on collision: two saves in the same minute with no custom name share a label, and
        // would silently overwrite. Append -2, -3, ... until the filename is free.
        String label = base;
        for (int i = 2; new File(dir, label + ".json").exists() && i < 1000; i++) label = base + "-" + i;
        try {
            JSONObject j = new JSONObject(IdentityService.toJson(profile));
            // Store as a STRING so it round-trips through the strict Map<String,String> readMap loop on
            // any org.json impl (Android's getString coerces numbers, but string keeps it portable).
            j.put("_saved_at", String.valueOf(System.currentTimeMillis()));
            j.put("_targets", targets == null ? "" : targets);   // apps this profile was applied to
            File f = new File(dir, label + ".json");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(j.toString().getBytes("UTF-8"));
            }
            return label;
        } catch (Throwable t) {
            return null;   // write failed — do NOT claim success
        }
    }

    /** All saved entries, newest first. */
    public List<Entry> list() {
        List<Entry> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        for (File f : files) {
            Map<String, String> p = readMap(f);
            if (p == null) continue;
            String label = f.getName().substring(0, f.getName().length() - 5);
            String device = (p.getOrDefault("build_manufacturer", "") + " "
                    + p.getOrDefault("build_model", "")).trim();
            long savedAt = 0;
            try { savedAt = Long.parseLong(p.getOrDefault("_saved_at", "0")); } catch (Throwable ignored) {}
            String targets = p.getOrDefault("_targets", "");
            out.add(new Entry(label, device.isEmpty() ? "(unknown device)" : device, savedAt, targets));
        }
        // newest first (by savedAt; label sorts reasonably too)
        out.sort((a, b) -> Long.compare(b.savedAt, a.savedAt));
        return out;
    }

    /** Load a saved profile map by label (minus the _saved_at/_targets metadata), or null if missing.
     *  Backfills any pure-derived signal fields the entry is MISSING (boot_count/battery_uah/timezone/
     *  locale) — so an OLD profile saved before those signals existed still applies them coherently
     *  instead of leaking the host value. */
    public Map<String, String> load(String label) {
        Map<String, String> p = readMap(new File(dir, label + ".json"));
        if (p != null) {
            p.remove("_saved_at"); p.remove("_targets");
            com.specter.module.gen.Profile.backfillDerived(p);
        }
        return p;
    }

    /** Delete a saved entry. Returns true if a file was removed. */
    public boolean delete(String label) {
        return new File(dir, label + ".json").delete();
    }

    /** Rename a saved fingerprint's NAME (the part after the {@code MMDDYY-Day-HHMM} timestamp prefix), keeping
     *  the timestamp so it still sorts/groups by date. Returns the new label, or null if the source is missing
     *  or the target label already exists. The stored profile bytes are unchanged — only the filename moves. */
    public String rename(String oldLabel, String newName) {
        File src = new File(dir, oldLabel + ".json");
        if (!src.exists()) return null;
        // Keep the first three dash-parts (MMDDYY, Day, HHMM) as the immutable timestamp; replace the rest.
        String[] p = oldLabel.split("-");
        String prefix = p.length >= 3 ? p[0] + "-" + p[1] + "-" + p[2] : oldLabel;
        String clean = sanitize(newName);
        String base = clean.isEmpty() ? prefix : prefix + "-" + clean;
        if (base.equals(oldLabel)) return oldLabel;   // no-op rename
        String target = base;
        for (int i = 2; new File(dir, target + ".json").exists() && i < 1000; i++) target = base + "-" + i;
        return src.renameTo(new File(dir, target + ".json")) ? target : null;
    }

    /** Export a saved profile as a portable, checksummed envelope written to /sdcard/Download so it can be
     *  shared with another user. Returns the destination path, or null on failure (missing entry / no write).
     *  The file is named specter-profile-&lt;label&gt;.json. Vault-local metadata is stripped by the envelope. */
    public String exportToDownloads(String label) {
        Map<String, String> p = readMap(new File(dir, label + ".json"));
        if (p == null) return null;
        String env = VaultPortable.buildEnvelope(p);
        if (env == null) return null;
        String destName = "specter-profile-" + sanitize(label) + ".json";
        String dest = "/sdcard/Download/" + destName;
        // The app has no storage permission, so a direct write to /sdcard/Download is DENIED. Stage the
        // file in our own (always-writable) files dir, then su-copy it into Download + make it readable.
        File staged = new File(dir.getParentFile(), destName);
        // Defense-in-depth: sanitize() already restricts the name, but refuse to su-exec any path with a
        // shell metacharacter (belt-and-braces against a future caller passing a raw label).
        if (!VaultChecksum.isShellSafePath(staged.getAbsolutePath()) || !VaultChecksum.isShellSafePath(dest)) return null;
        try (FileOutputStream fos = new FileOutputStream(staged)) {
            fos.write(env.getBytes("UTF-8"));
        } catch (Throwable t) { return null; }
        Process pr = null;
        try {
            pr = Runtime.getRuntime().exec(new String[]{"su", "-c",
                    "cp '" + staged.getAbsolutePath() + "' '" + dest + "' && chmod 644 '" + dest + "'"});
            int code = pr.waitFor();
            return code == 0 ? dest : null;
        } catch (Throwable t) { return null; }
        finally {
            if (pr != null) pr.destroy();
            //noinspection ResultOfMethodCallIgnored
            staged.delete();   // always remove the staged copy, success or fail
        }
    }

    /** Import a portable envelope file into the vault under a fresh timestamp label. Validates format +
     *  checksum. Returns the new label on success, or null if the file is missing/invalid/corrupted. The
     *  imported identity is saved with NO _targets (the recipient hasn't applied it anywhere yet). */
    public String importFromFile(File src, String name) {
        String text = readViaSu(src);
        if (text == null) return null;
        VaultPortable.Parsed parsed = VaultPortable.parseEnvelope(text);
        if (!parsed.isOk()) return null;
        // Save under a fresh label; no targets (not applied yet by this user).
        return save(name, parsed.profile, "");
    }

    /** Same as importFromFile but surfaces WHY it failed (for a clear toast). Returns null error on success. */
    public String importError(File src) {
        String text = readViaSu(src);
        if (text == null) return "could not read file (grant root?)";
        VaultPortable.Parsed parsed = VaultPortable.parseEnvelope(text);
        return parsed.isOk() ? null : parsed.error;
    }

    /** Result of a one-shot import: either {@code label} (success) or {@code error} (why it failed). */
    public static final class ImportResult {
        public final String label;   // non-null on success
        public final String error;   // non-null on failure
        ImportResult(String label, String error) { this.label = label; this.error = error; }
        public boolean ok() { return label != null; }
    }

    /** Validate + import in a SINGLE su read (no double-read / TOCTOU), returning label-or-error. Call this
     *  off the UI thread — it runs a blocking {@code su cat}. Replaces the importError()+importFromFile()
     *  pair the UI used to run back-to-back on the main thread. */
    public ImportResult importOnce(File src, String name) {
        String text = readViaSu(src);
        if (text == null) return new ImportResult(null, "could not read file (grant root?)");
        VaultPortable.Parsed parsed = VaultPortable.parseEnvelope(text);
        if (!parsed.isOk()) return new ImportResult(null, parsed.error);
        String label = save(name, parsed.profile, "");
        return label != null ? new ImportResult(label, null)
                             : new ImportResult(null, "could not write to the vault");
    }

    /** Read a file the app itself can't (no storage permission) via su. Returns null on failure. Restricted
     *  to /sdcard/Download and shell-safe paths so a crafted filename can't inject or escape the directory. */
    private static String readViaSu(File src) {
        if (src == null) return null;
        String path = src.getAbsolutePath();
        if (!VaultChecksum.isShellSafePath(path)) return null;
        if (!path.startsWith("/sdcard/Download/") || path.contains("..")) return null;
        Process pr = null;
        try {
            pr = Runtime.getRuntime().exec(new String[]{"su", "-c", "cat '" + src.getAbsolutePath() + "'"});
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            java.io.InputStream is = pr.getInputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = is.read(buf)) != -1) bos.write(buf, 0, n);
            pr.waitFor();
            String s = new String(bos.toByteArray(), "UTF-8");
            return s.isEmpty() ? null : s;
        } catch (Throwable t) { return null; }
        finally { if (pr != null) pr.destroy(); }
    }

    private static Map<String, String> readMap(File f) {
        if (f == null || !f.exists()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            JSONObject j = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            Map<String, String> m = new LinkedHashMap<>();
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                m.put(k, j.getString(k));
            }
            return m;
        } catch (Throwable t) { return null; }
    }

    // reserved for future callers that want the raw day-abbrev list
    static List<String> dayAbbrevs() { return Arrays.asList("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"); }
}
