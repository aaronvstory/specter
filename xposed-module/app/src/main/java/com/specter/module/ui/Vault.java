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

    /** One saved entry: its label (== filename stem) and the device it represents, for the list UI. */
    public static final class Entry {
        public final String label;
        public final String device;   // e.g. "Samsung SM-A505F" for the list row
        public final long savedAt;
        Entry(String label, String device, long savedAt) {
            this.label = label; this.device = device; this.savedAt = savedAt;
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

    /** Save {@code profile} under a label derived from {@code name}. Returns the label used. */
    public String save(String name, Map<String, String> profile) {
        String label = makeLabel(name);
        try {
            JSONObject j = new JSONObject(IdentityService.toJson(profile));
            j.put("_saved_at", System.currentTimeMillis());
            File f = new File(dir, label + ".json");
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(j.toString().getBytes("UTF-8"));
            }
        } catch (Throwable ignored) {}
        return label;
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
            out.add(new Entry(label, device.isEmpty() ? "(unknown device)" : device, savedAt));
        }
        // newest first (by savedAt; label sorts reasonably too)
        out.sort((a, b) -> Long.compare(b.savedAt, a.savedAt));
        return out;
    }

    /** Load a saved profile map by label (minus the _saved_at metadata), or null if missing/corrupt. */
    public Map<String, String> load(String label) {
        Map<String, String> p = readMap(new File(dir, label + ".json"));
        if (p != null) p.remove("_saved_at");
        return p;
    }

    /** Delete a saved entry. Returns true if a file was removed. */
    public boolean delete(String label) {
        return new File(dir, label + ".json").delete();
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
