package com.fleet.idrotate.gen;

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

    public IdentityService(Context ctx) { this(ctx, new RootWriter.SuShell()); }
    public IdentityService(Context ctx, RootWriter.Shell shell) { this.ctx = ctx; this.shell = shell; }

    /** SecureRandom-backed production RNG. */
    static Generators.Rng secureRng() {
        final SecureRandom rnd = new SecureRandom();
        return new Generators.Rng() {
            @Override public int next(int n) { return rnd.nextInt(n); }
            @Override public long nextLong(long n) {
                long v = rnd.nextLong() & Long.MAX_VALUE; // non-negative
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
            // FAIL CLOSED: quarantine the bad ledger and refuse rather than reuse ids.
            f.renameTo(new File(f.getParentFile(), "used_ids.json.corrupt"));
            throw new UsedStore.CorruptLedger("ledger unparseable (quarantined): " + e.getMessage(), e);
        }
    }

    void saveLedger(UsedStore store) {
        try {
            JSONObject j = new JSONObject();
            for (Map.Entry<String, Set<String>> e : store.snapshot().entrySet())
                j.put(e.getKey(), new JSONArray(e.getValue()));
            File tmp = new File(ctx.getFilesDir(), "used_ids.json.tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(j.toString(2).getBytes("UTF-8"));
            }
            tmp.renameTo(ledgerFile()); // atomic-ish replace
        } catch (Exception e) {
            throw new RuntimeException("failed to persist ledger: " + e.getMessage(), e);
        }
    }

    /** Generate a fresh, validated, never-before-used profile and record it. */
    public Map<String, String> generateUnique() {
        List<List<String>> devices = loadDevices();
        UsedStore store = loadLedger();
        Generators.Rng r = secureRng();
        for (int tries = 0; tries < 1000; tries++) {
            Map<String, String> p = Profile.build(r, devices, true);
            if (!Profile.isValid(p)) continue;
            if (store.collides(p)) continue;
            if (store.record(p)) { saveLedger(store); return p; }
        }
        throw new RuntimeException("could not generate a fresh valid profile in 1000 tries");
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
}
