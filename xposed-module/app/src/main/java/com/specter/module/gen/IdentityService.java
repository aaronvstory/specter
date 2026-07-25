package com.specter.module.gen;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Android-layer glue: loads the bundled device DB, generates a coherent + globally-unique identity
 * on-device (no PC), serializes it, and writes it to where the hook reads. This is the thin layer
 * that depends on Android {@link Context} + org.json; all the ban-critical logic lives in the pure
 * (JVM-tested) {@link Generators}/{@link Profile}/{@link UsedStore}.
 *
 * The no-reuse ledger is a JSON file in the app's private filesDir (NOT world-readable — the target
 * app must never see it). It FAILS CLOSED: a corrupt ledger is quarantined and the call throws,
 * never silently treated as empty (which would let issued ids be reused).
 */
public final class IdentityService {

    private final Context ctx;
    private final RootWriter.Shell shell;
    private Country country = Country.US;   // set from the Settings country picker

    public IdentityService(Context ctx) { this(ctx, new RootWriter.SuShell()); }
    public IdentityService(Context ctx, RootWriter.Shell shell) { this.ctx = ctx; this.shell = shell; }

    /** Set the SIM/phone country for subsequent generation (from the Settings picker). */
    public void setCountry(Country c) { if (c != null) this.country = c; }
    public Country getCountry() { return country; }

    // One SecureRandom for the process — reseeding a fresh instance per call is a known perf
    // anti-pattern and needless; SecureRandom is thread-safe.
    private static final SecureRandom RND = new SecureRandom();

    /** SecureRandom-backed production RNG. */
    static Generators.Rng secureRng() {
        return new Generators.Rng() {
            @Override public int next(int n) { return RND.nextInt(n); }
            @Override public long nextLong(long n) {
                long v = RND.nextLong() & Long.MAX_VALUE; // non-negative
                return v % n;
            }
        };
    }

    /** Load and parse the bundled devices.json into positional rows. */
    List<List<String>> loadDevices() {
        try (InputStream in = ctx.getAssets().open("devices.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            JSONArray arr = new JSONArray(new String(bos.toByteArray(), "UTF-8"));
            List<List<String>> devices = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONArray row = arr.getJSONArray(i);
                List<String> r2 = new ArrayList<>(row.length());
                for (int j = 0; j < row.length(); j++) r2.add(row.getString(j));
                devices.add(r2);
            }
            return devices;
        } catch (Exception e) {
            throw new RuntimeException("failed to load devices.json asset: " + e.getMessage(), e);
        }
    }

    /**
     * Load data/hardware.json (APK asset) and render each entry to the flat hardware-descriptor
     * fields Profile.build consumes — the SAME encoding as specter/profile.py _hw_fields (sensors as
     * {@code name|vendor|type} joined by ';', lists comma-joined, cpuinfo verbatim). Keyed by device
     * codename, plus "_default". Returns an empty map (→ Profile falls back to DEFAULT_HW) on failure.
     */
    Map<String, Map<String, String>> loadHardware() {
        Map<String, Map<String, String>> out = new java.util.HashMap<>();
        try (InputStream in = ctx.getAssets().open("hardware.json")) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192]; int r;
            while ((r = in.read(buf)) != -1) bos.write(buf, 0, r);
            JSONObject root = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            for (java.util.Iterator<String> it = root.keys(); it.hasNext(); ) {
                String codename = it.next();
                JSONObject e = root.getJSONObject(codename);
                Map<String, String> f = new java.util.HashMap<>();
                f.put("hw_gpu_renderer", e.optString("gpu_renderer"));
                f.put("hw_gpu_vendor", e.optString("gpu_vendor"));
                f.put("hw_gles_version", e.optString("gles_version"));
                f.put("hw_cores", String.valueOf(e.optInt("cores", 8)));
                JSONArray sensors = e.optJSONArray("sensors");
                StringBuilder sb = new StringBuilder();
                for (int i = 0; sensors != null && i < sensors.length(); i++) {
                    JSONObject s = sensors.getJSONObject(i);
                    if (i > 0) sb.append(';');
                    sb.append(s.optString("name")).append('|').append(s.optString("vendor"))
                      .append('|').append(s.optInt("type"));
                }
                f.put("hw_sensors", sb.toString());
                f.put("hw_cameras", joinArray(e.optJSONArray("cameras")));
                f.put("hw_codecs", joinArray(e.optJSONArray("codecs")));
                f.put("hw_input_devices", joinArray(e.optJSONArray("input_devices")));
                f.put("proc_cpuinfo", e.optString("cpuinfo"));
                out.put(codename, f);
            }
        } catch (Exception e) {
            // A missing/broken asset must not brick generation — Profile.build falls back to
            // DEFAULT_HW when the dataset is empty, so every profile stays complete and valid.
        }
        return out;
    }

    private static String joinArray(JSONArray a) {
        if (a == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length(); i++) { if (i > 0) sb.append(','); sb.append(a.optString(i)); }
        return sb.toString();
    }

    private File ledgerFile() { return new File(ctx.getFilesDir(), "used_ids.json"); }

    /** Load the no-reuse ledger; FAILS CLOSED (quarantine + throw) on a corrupt file. */
    UsedStore loadLedger() {
        File f = ledgerFile();
        if (!f.exists()) return new UsedStore();
        String text;
        try {
            byte[] b = new byte[(int) f.length()];
            try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                int off = 0, r;
                while (off < b.length && (r = in.read(b, off, b.length - off)) != -1) off += r;
            }
            text = new String(b, "UTF-8");
        } catch (Exception e) {
            throw new UsedStore.CorruptLedger("cannot read ledger: " + e.getMessage(), e);
        }
        try {
            JSONObject j = new JSONObject(text);
            Map<String, List<String>> parsed = new java.util.HashMap<>();
            for (String k : Profile.UNIQUE_KEYS) {
                JSONArray a = j.optJSONArray(k);
                if (a != null) {
                    List<String> vals = new ArrayList<>(a.length());
                    for (int i = 0; i < a.length(); i++) vals.add(a.getString(i));
                    parsed.put(k, vals);
                }
            }
            return UsedStore.fromParsed(parsed);
        } catch (Exception e) {
            // FAIL CLOSED: refuse rather than reuse ids. The throw below is the actual guard;
            // quarantine is best-effort operator convenience. Clear any prior .corrupt so the
            // rename can't silently fail on an existing target.
            File q = new File(f.getParentFile(), "used_ids.json.corrupt");
            q.delete();
            f.renameTo(q);
            throw new UsedStore.CorruptLedger("ledger unparseable (quarantined): " + e.getMessage(), e);
        }
    }

    // Serializes ledger read-modify-write across ALL IdentityService instances in this process
    // (MainActivity + DebugActivity, each on background threads) so concurrent generate/randomize
    // can't lose records — the ban-critical no-reuse guarantee. Single-process, so an in-JVM lock
    // suffices (no cross-process file lock needed on-device).
    private static final Object LEDGER_LOCK = new Object();

    void saveLedger(UsedStore store) {
        try {
            JSONObject j = new JSONObject();
            for (Map.Entry<String, Set<String>> e : store.snapshot().entrySet())
                j.put(e.getKey(), new JSONArray(e.getValue()));
            File tmp = new File(ctx.getFilesDir(), "used_ids.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(j.toString(2).getBytes("UTF-8"));
            }
            File dest = ledgerFile();
            dest.delete(); // renameTo won't overwrite on some filesystems; clear first
            if (!tmp.renameTo(dest)) {
                // FAIL CLOSED: an unpersisted ledger means new ids could be reissued — never swallow.
                throw new java.io.IOException("could not replace ledger " + dest.getName());
            }
        } catch (Exception e) {
            throw new RuntimeException("failed to persist ledger: " + e.getMessage(), e);
        }
    }

    /** Generate a fresh, validated, never-before-used profile and record it. */
    public Map<String, String> generateUnique() {
        synchronized (LEDGER_LOCK) {
            List<List<String>> devices = loadDevices();
            Map<String, Map<String, String>> hardware = loadHardware();
            UsedStore store = loadLedger();
            Generators.Rng r = secureRng();
            for (int tries = 0; tries < 1000; tries++) {
                Map<String, String> p = Profile.build(r, devices, true, country, hardware);
                if (!Profile.isValid(p)) continue;
                if (store.collides(p)) continue;
                if (store.record(p)) { saveLedger(store); return p; }
            }
            throw new RuntimeException("could not generate a fresh valid profile in 1000 tries");
        }
    }

    /** Serialize a profile to the flat JSON string the hook consumes. */
    public static String toJson(Map<String, String> profile) {
        JSONObject j = new JSONObject();
        try { for (Map.Entry<String, String> e : profile.entrySet()) j.put(e.getKey(), e.getValue()); }
        catch (Exception ignored) {}
        return j.toString();
    }

    /**
     * Generate a fresh identity and apply it to {@code pkg} (write to /data/local/tmp/specter via su).
     * Returns the applied profile. Throws loudly if su is denied.
     */
    public Map<String, String> generateAndApply(String pkg) {
        Map<String, String> p = generateUnique();
        RootWriter.write(shell, pkg, toJson(p));
        return p;
    }

    /** Apply an already-built profile (e.g. one the user edited) to {@code pkg}. */
    public void apply(String pkg, Map<String, String> profile) {
        RootWriter.write(shell, pkg, toJson(profile));
    }

    private static boolean isUniqueKey(String key) {
        for (String k : Profile.UNIQUE_KEYS) if (k.equals(key)) return true;
        return false;
    }

    /** Generate one candidate value for {@code key} (no ledger interaction). Null for non-rotatable keys. */
    private static String genOne(Generators.Rng r, Map<String, String> p, String key) {
        // Fallback carrier so a carrier-linked regen (imsi/iccid) can't NPE on an incomplete profile.
        String mccmnc = p.get("sim_operator_mccmnc");
        if (mccmnc == null || mccmnc.isEmpty()) mccmnc = "310260"; // T-Mobile
        String imei1 = p.getOrDefault("imei1", "");
        String tac = imei1.length() >= 8 ? imei1.substring(0, 8) : null;
        // Phone format follows the profile's own carrier country (MCC 234/235 = UK, else NANP),
        // so a per-field phone regen stays coherent with the existing SIM.
        String phoneKind = (mccmnc.startsWith("234") || mccmnc.startsWith("235")) ? "uk" : "nanp";
        switch (key) {
            case "android_id":          return Generators.hex16(r);
            case "serial":              return Generators.hex16upper(r);
            case "media_drm_id":        return Generators.hex32(r);
            case "imei1": case "imei2": return Generators.imei(r, tac);
            case "advertising_id":      return Generators.uuid(r);
            case "bluetooth_mac":
            case "wifi_mac":            return Generators.macUpper(r);
            case "wifi_bssid":          return Generators.macLower(r);
            case "wifi_ssid":           return Generators.ssid(r);
            case "mobile_number":       return Generators.phoneForCountry(r, phoneKind);
            case "sim_subscriber_imsi": return Generators.imsi(r, mccmnc);
            case "sim_serial_iccid":    return Generators.iccid(r, mccmnc);
            case "gsf_id":              return Generators.gsf(r);
            case "gmail":               return Generators.gmail(r);
            default:                    return null; // Build.* etc. rotate via the device bundle
        }
    }

    /**
     * Compute a fresh value for a SINGLE identifier (the per-card RANDOMIZE button). Carrier-linked
     * keys (imsi/iccid) use the profile's existing carrier so coherence is kept; imei1/imei2 keep the
     * device's TAC. Returns the new value, or the existing one for non-rotatable keys.
     *
     * IMPORTANT: this does NOT mutate {@code context} — the caller applies the result to the shared
     * profile on the UI thread. That keeps every mutation of the UI-owned profile map on a single
     * thread (this method runs on a worker for the ledger I/O), avoiding a data race on the map.
     *
     * BAN-CRITICAL: for globally-unique keys, the value is checked against the no-reuse ledger and
     * recorded + persisted before it's returned — so a randomized-then-applied id can never be
     * reissued by a later generateUnique() (the "coordinated accounts" reuse this tool prevents).
     *
     * @param context a snapshot of the current profile (read-only here) for carrier/TAC coherence.
     */
    public String randomizeField(Map<String, String> context, String key) {
        Generators.Rng r = secureRng();
        if (!isUniqueKey(key)) {
            String v = genOne(r, context, key);    // e.g. wifi_ssid — not ledgered
            return v != null ? v : context.get(key);
        }
        synchronized (LEDGER_LOCK) {
            UsedStore store = loadLedger();
            for (int tries = 0; tries < 1000; tries++) {
                String v = genOne(r, context, key);
                if (v == null) return context.get(key);
                if (!Generators.validate(key, v)) continue;
                if (store.recordOne(key, v)) {   // claims it iff never issued before for this key
                    saveLedger(store);
                    return v;
                }
            }
            throw new RuntimeException("could not randomize a fresh " + key + " in 1000 tries");
        }
    }
}
