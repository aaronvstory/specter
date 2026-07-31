package com.specter.module.ui;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Pure (no Android, no org.json) integrity helper for the portable vault format: a SHA-256 over the
 * profile's identity fields, so an imported file that was corrupted or hand-mangled can be rejected.
 * Split out from {@link VaultPortable} (which needs org.json) so this security-critical logic is
 * unit-testable in the plain-JVM harness.
 */
public final class VaultChecksum {
    private VaultChecksum() {}

    /** SHA-256 (64 hex chars) over "k=v\n" lines in SORTED key order, excluding vault-local metadata —
     *  stable regardless of JSON member order, so sender and receiver compute the same digest. */
    public static String of(Map<String, String> profile) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new TreeMap<>(stripMeta(profile)).entrySet())
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(sb.toString().getBytes("UTF-8"));
            StringBuilder hex = new StringBuilder(h.length * 2);
            for (byte b : h) { hex.append(Character.forDigit((b >> 4) & 0xf, 16)); hex.append(Character.forDigit(b & 0xf, 16)); }
            return hex.toString();
        } catch (Exception e) { return ""; }
    }

    /** A path is safe to interpolate into a {@code su -c} shell string only if it has no shell
     *  metacharacters — reject anything outside [A-Za-z0-9 _-./]. A crafted filename (e.g. containing a
     *  single quote) could otherwise break out of the quoting and inject a command; such files are refused
     *  rather than executed. */
    public static boolean isShellSafePath(String path) {
        if (path == null || path.isEmpty()) return false;
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9')
                    || c == ' ' || c == '_' || c == '-' || c == '.' || c == '/';
            if (!ok) return false;
        }
        return true;
    }

    /** Drop vault-local metadata so shared/checksummed content is only the identity itself. */
    public static Map<String, String> stripMeta(Map<String, String> profile) {
        Map<String, String> m = new LinkedHashMap<>(profile);
        m.remove("_saved_at");
        m.remove("_targets");
        m.remove("os_version_spoof_enabled");   // host-specific runtime policy, not part of the portable identity
        return m;
    }
}
