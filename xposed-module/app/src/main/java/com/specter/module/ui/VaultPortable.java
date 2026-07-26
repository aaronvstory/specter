package com.specter.module.ui;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Portable profile envelope for vault export/import — so two users can share a saved identity as a file.
 * A profile is wrapped as:
 * <pre>{"specter_profile": 1, "device": "&lt;maker model&gt;", "checksum": "&lt;sha256&gt;", "profile": {…flat…}}</pre>
 * The checksum is a SHA-256 over the profile's key=value pairs in SORTED key order (so it's independent of
 * JSON member ordering), letting import reject a corrupted or hand-mangled file. Pure (no Android) so the
 * build/parse/checksum logic is unit-testable in the JVM harness; the actual file I/O lives in {@link Vault}.
 */
public final class VaultPortable {
    private VaultPortable() {}

    public static final int FORMAT_VERSION = 1;

    /** Result of parsing an imported envelope: the profile map, or an error message (never both). */
    public static final class Parsed {
        public final Map<String, String> profile;   // non-null on success
        public final String error;                   // non-null on failure
        private Parsed(Map<String, String> p, String e) { profile = p; error = e; }
        static Parsed ok(Map<String, String> p) { return new Parsed(p, null); }
        static Parsed fail(String e) { return new Parsed(null, e); }
        public boolean isOk() { return profile != null; }
    }

    /** Build the portable JSON string for a profile (metadata keys like _saved_at/_targets are dropped —
     *  the recipient re-stamps those on their own save). */
    public static String buildEnvelope(Map<String, String> profile) {
        Map<String, String> clean = stripMeta(profile);
        String device = (get(clean, "build_manufacturer") + " " + get(clean, "build_model")).trim();
        try {
            JSONObject prof = new JSONObject();
            for (Map.Entry<String, String> e : clean.entrySet()) prof.put(e.getKey(), e.getValue());
            JSONObject env = new JSONObject();
            env.put("specter_profile", FORMAT_VERSION);
            env.put("device", device.isEmpty() ? "unknown" : device);
            env.put("checksum", checksum(clean));
            env.put("profile", prof);
            return env.toString(2);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Parse + validate an imported envelope string. Rejects wrong format version, missing profile, or a
     *  checksum that doesn't match (corrupted/tampered file). */
    public static Parsed parseEnvelope(String text) {
        if (text == null || text.trim().isEmpty()) return Parsed.fail("empty file");
        JSONObject env;
        try { env = new JSONObject(text); }
        catch (JSONException e) { return Parsed.fail("not valid JSON"); }
        int ver = env.optInt("specter_profile", -1);
        if (ver == -1) return Parsed.fail("not a Specter profile file");
        if (ver > FORMAT_VERSION) return Parsed.fail("file is from a newer Specter — update the app");
        JSONObject prof = env.optJSONObject("profile");
        if (prof == null) return Parsed.fail("no profile in file");
        Map<String, String> m = new LinkedHashMap<>();
        for (java.util.Iterator<String> it = prof.keys(); it.hasNext(); ) {
            String k = it.next();
            m.put(k, prof.optString(k));
        }
        if (m.isEmpty()) return Parsed.fail("profile is empty");
        String want = env.optString("checksum", "");
        if (!want.isEmpty() && !want.equals(checksum(m)))
            return Parsed.fail("checksum mismatch — file is corrupted");
        return Parsed.ok(m);
    }

    static String checksum(Map<String, String> profile) { return VaultChecksum.of(profile); }

    static Map<String, String> stripMeta(Map<String, String> profile) { return VaultChecksum.stripMeta(profile); }

    private static String get(Map<String, String> m, String k) { String v = m.get(k); return v == null ? "" : v; }
}
