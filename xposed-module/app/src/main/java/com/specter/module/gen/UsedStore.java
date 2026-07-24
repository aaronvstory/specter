package com.specter.module.gen;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * On-device no-reuse ledger — the ban-critical guarantee that no unique identifier is ever
 * issued twice. A simplified port of Python {@code UsedStore}: single-writer on-device, so the
 * file-lock / concurrent-record path is dropped (ponytail: no second writer exists on-device).
 *
 * Pure logic, no JSON/Android dependency: the ledger is a {@code Map<key, Set<value>>}. The thin
 * Android layer (de)serializes it to/from {@code filesDir/used_ids.json} and is responsible for
 * the ban-critical FAIL-CLOSED behaviour — if the on-disk ledger text is present but unparseable,
 * the loader must throw, NOT hand this class an empty map (which would make every issued id
 * reusable). See {@link #fromParsed} for the intended construction contract.
 */
public final class UsedStore {

    /** Raised when the on-disk ledger exists but is unreadable — the loader FAILS CLOSED (throws). */
    public static final class CorruptLedger extends RuntimeException {
        public CorruptLedger(String m, Throwable t) { super(m, t); }
    }

    private final Map<String, Set<String>> sets = new LinkedHashMap<>();

    /** Fresh, legitimately-empty ledger. */
    public UsedStore() {
        for (String k : Profile.UNIQUE_KEYS) sets.put(k, new HashSet<>());
    }

    /**
     * Build from an already-parsed ledger (the Android loader parses JSON and, on parse failure,
     * throws BEFORE calling this — never passes an empty map for a corrupt file).
     */
    public static UsedStore fromParsed(Map<String, ? extends Iterable<String>> parsed) {
        UsedStore u = new UsedStore();
        if (parsed != null)
            for (String k : Profile.UNIQUE_KEYS) {
                Iterable<String> vals = parsed.get(k);
                if (vals != null) for (String v : vals) u.sets.get(k).add(v);
            }
        return u;
    }

    /** True if any of this profile's unique ids was already issued. */
    public boolean collides(Map<String, String> profile) {
        for (String k : Profile.UNIQUE_KEYS)
            if (sets.get(k).contains(profile.get(k))) return true;
        return false;
    }

    /**
     * Claim this profile's unique ids. Returns false (no-op) if it collides; otherwise records
     * them and returns true. Persist via {@link #snapshot()} afterwards.
     */
    public boolean record(Map<String, String> profile) {
        if (collides(profile)) return false;
        for (String k : Profile.UNIQUE_KEYS) sets.get(k).add(profile.get(k));
        return true;
    }

    /** True if this single value was already issued for {@code key}. */
    public boolean containsValue(String key, String value) {
        Set<String> s = sets.get(key);
        return s != null && s.contains(value);
    }

    /**
     * Record ONE fresh unique value (the per-field RANDOMIZE path). Returns false if it was already
     * issued for {@code key} (caller should re-roll), true if newly claimed. Only valid for keys in
     * {@link Profile#UNIQUE_KEYS}; a no-op returning true for non-unique keys.
     */
    public boolean recordOne(String key, String value) {
        Set<String> s = sets.get(key);
        if (s == null) return true;            // not a ban-critical unique key
        if (s.contains(value)) return false;   // already issued — reuse guard
        s.add(value);
        return true;
    }

    /** Number of identities recorded (by gsf_id count, matching Python UsedStore.count()). */
    public int count() { return sets.get("gsf_id").size(); }

    /** Immutable-ish view for serialization by the Android layer. */
    public Map<String, Set<String>> snapshot() {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Set<String>> e : sets.entrySet())
            out.put(e.getKey(), new HashSet<>(e.getValue()));
        return out;
    }
}
