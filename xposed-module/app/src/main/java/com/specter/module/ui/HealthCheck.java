package com.specter.module.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.specter.module.HookEntry;
import com.specter.module.gen.RootWriter;
import com.specter.module.gen.ZygiskInstaller;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Self-verification: runs every check that tells the user whether Specter is ACTUALLY configured to spoof,
 * so a misconfiguration surfaces as a red row instead of a false sense of security. Each {@link Check}
 * carries a state (OK / WARN / BAD), a one-line detail, and an optional {@link Fix} the Status screen turns
 * into a one-tap button (auto-fix where safe, else clear guidance).
 *
 * <p>All checks are best-effort and NEVER throw — a check that can't run (su denied, file absent) reports
 * WARN with the reason, not a crash. Run OFF the UI thread ({@link #runAll}) — several checks shell out.
 */
final class HealthCheck {
    private HealthCheck() {}

    enum State { OK, WARN, BAD }

    /** A fix the UI can offer. NONE = the guidance is inline in the row's detail (no button); the others get
     *  a one-tap action button. (No dialog-based "guide" fixes — the detail text carries the steps.) */
    enum Fix { NONE, SYNC_ZYGISK, REAPPLY_PROFILE, MATCH_TZ }

    static final class Check {
        final String label, detail;
        final State state;
        final Fix fix;
        final String fixArg;   // e.g. a package name for a per-app fix
        Check(String label, State state, String detail, Fix fix, String fixArg) {
            this.label = label; this.state = state; this.detail = detail; this.fix = fix; this.fixArg = fixArg;
        }
        static Check ok(String l, String d) { return new Check(l, State.OK, d, Fix.NONE, null); }
        static Check warn(String l, String d, Fix f, String a) { return new Check(l, State.WARN, d, f, a); }
        static Check bad(String l, String d, Fix f, String a) { return new Check(l, State.BAD, d, f, a); }
    }

    /** A section of checks with a heading. {@code geo} is set only on the Network group so the UI can render
     *  a rich IP/location card above the rows. */
    static final class Group {
        final String title;
        final List<Check> checks;
        Geo geo;               // network group only; null elsewhere
        boolean vpnRouting;    // network group only: is traffic tunnelled through a VPN/proxy
        Group(String title, List<Check> checks) { this.title = title; this.checks = checks; }
    }

    /** Run every check. Returns grouped results. Blocking (su) — call off the UI thread. */
    static List<Group> runAll(Context ctx, SharedPreferences prefs) {
        RootWriter.SuShell sh = new RootWriter.SuShell();
        boolean rooted = rootGranted(sh);

        List<Group> groups = new ArrayList<>();

        // ---- Setup: the things that must be true for ANY spoofing to happen ----
        List<Check> setup = new ArrayList<>();
        setup.add(rooted
                ? Check.ok("Root access", "Magisk su granted to Specter.")
                : Check.bad("Root access", "No su — allow Specter in Magisk → Superuser, then Re-check.",
                        Fix.NONE, null));

        setup.add(moduleEnabled(ctx, sh)
                ? Check.ok("LSPosed module", "Specter is enabled in LSPosed.")
                : Check.bad("LSPosed module", "Not enabled — turn Specter on in LSPosed → Modules, then reboot.",
                        Fix.NONE, null));

        // Framework app-hiding gate: distinguish (a) loaded+active, (b) scope set but NOT loaded (a LSPosed
        // quirk — don't tell the user to "enable scope" they already enabled), (c) scope not set at all.
        boolean gateLoaded = frameworkGateLoaded(sh);
        if (gateLoaded) {
            setup.add(Check.ok("App-hiding gate", "Active in system_server — closes the raw-binder bypass."));
        } else if (frameworkScopeSet(ctx, sh)) {
            setup.add(Check.warn("App-hiding gate",
                    "System-Framework scope is ON but the module didn’t load into system_server. Reboot; if it "
                    + "still won’t load, toggle the scope off/on in LSPosed once. (Optional — per-app hiding works without it.)",
                    Fix.NONE, null));
        } else {
            setup.add(Check.warn("App-hiding gate",
                    "Off. Enable “System Framework” scope in LSPosed + reboot to close the raw-binder bypass. "
                    + "(Optional — per-app hiding works without it.)", Fix.NONE, null));
        }
        groups.add(new Group("Setup", setup));

        // ---- Native layer ----
        List<Check> nativeG = new ArrayList<>();
        ZygiskInstaller.Status z;
        try { z = ZygiskInstaller.status(ctx, sh); } catch (Throwable t) { z = null; }
        if (z == null || z.bundledVersion == null) {
            nativeG.add(Check.warn("Native layer", "Couldn't check the Zygisk layer (no bundled asset or su denied).",
                    Fix.NONE, null));
        } else if (!z.installed) {
            nativeG.add(Check.bad("Native layer", "Zygisk layer NOT installed — native reads leak real values. Install it.",
                    Fix.SYNC_ZYGISK, null));
        } else if (!z.current) {
            nativeG.add(Check.warn("Native layer", "Installed " + nn(z.installedVersion) + " but app bundles "
                    + nn(z.bundledVersion) + " — update it.", Fix.SYNC_ZYGISK, null));
        } else {
            nativeG.add(Check.ok("Native layer", "Zygisk layer installed + current (" + nn(z.installedVersion) + ")."));
        }
        groups.add(new Group("Native layer", nativeG));

        // ---- Per target app: scoped? profile applied? ----
        Set<String> targets = Targets.get(prefs);
        List<Check> perApp = new ArrayList<>();
        if (targets.isEmpty()) {
            perApp.add(Check.warn("Target apps", "No target apps selected — add one on the Identity tab.",
                    Fix.NONE, null));
        } else {
            // Boot wall-clock: now minus uptime. A heartbeat written AFTER this instant proves the hook ran on
            // the CURRENT boot. We use this instead of boot_id because the native layer SPOOFS boot_id per-app,
            // so a hooked process reads a different boot_id than this (unscoped) UI — they'd never match. Wall
            // time isn't spoofed and is comparable across processes. Small slack for clock settle after boot.
            long bootWallMs = System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime();
            for (String pkg : targets) {
                String label = Targets.label(ctx, pkg);
                boolean scoped = appScoped(ctx, sh, pkg);
                boolean applied = profileApplied(sh, pkg);
                if (!scoped) {
                    perApp.add(Check.bad(label, "Not in LSPosed scope — hooks won't run. Add it in LSPosed + reboot.",
                            Fix.NONE, pkg));
                } else if (!applied) {
                    perApp.add(Check.warn(label, "Scoped, but no identity applied yet — apply one.",
                            Fix.REAPPLY_PROFILE, pkg));
                } else {
                    // Scope-row + profile-file present is only CONFIGURATION. Proof the hooks actually RAN in
                    // this app on THIS boot is the runtime heartbeat the Java layer writes after installing its
                    // hooks. Without a boot-matching heartbeat we must NOT claim GREEN — that false-GREEN is what
                    // let a mis-hooked app reach fleet looking "protected".
                    Heartbeat hb = readHeartbeat(sh, pkg);
                    // Written this boot, by THIS module version? epochMs must be within [bootWall-10s, now+1min]:
                    // the lower bound rejects a previous boot; the upper bound rejects a forged/rolled-forward
                    // future timestamp (a stale heartbeat can't pass by jumping the clock ahead). The version
                    // match rejects a heartbeat written by OLD module code still loaded after an APK update.
                    long nowMs = System.currentTimeMillis();
                    boolean fresh = hb != null && hb.epochMs >= (bootWallMs - 10_000L) && hb.epochMs <= (nowMs + 60_000L);
                    boolean sameVer = hb != null && HookEntry.MODULE_VERSION.equals(hb.version);
                    boolean live = fresh && sameVer;
                    if (live) {
                        // "N fields" is the loaded profile's key count, NOT a per-hook success count — each
                        // hookX() swallows its own errors, so a signal could still have failed to hook. Word it
                        // as "loaded this boot" (the heartbeat proves the Java layer ran + read the profile),
                        // not "every field verified" (that needs per-hook instrumentation — see IDEAS).
                        perApp.add(Check.ok(label, "Hooks loaded this boot — profile has " + hb.fields
                                + " fields (v" + hb.version + ")."));
                    } else if (hb != null && fresh && !sameVer) {
                        perApp.add(Check.warn(label, "Running an OLDER module version (" + hb.version + " vs "
                                + HookEntry.MODULE_VERSION + ") — relaunch the app so it re-hooks with the current "
                                + "build, then Re-check.", Fix.NONE, pkg));
                    } else if (hb != null) {
                        perApp.add(Check.warn(label, "Configured, but the last verified run was a PREVIOUS boot — "
                                + "relaunch the app so the hooks re-attach, then Re-check.", Fix.NONE, pkg));
                    } else {
                        perApp.add(Check.warn(label, "Configured, but hooks haven’t been verified running yet. "
                                + "Open the app once (it must be scoped + the module enabled in LSPosed), then Re-check.",
                                Fix.NONE, pkg));
                    }
                }
            }
        }
        groups.add(new Group("Target apps", perApp));

        // ---- Location: is a GPS mocker / mock-location detectable? ----
        groups.add(new Group("Location", java.util.Collections.singletonList(mockLocationCheck(ctx, sh))));

        // ---- Network: is VPN/proxy masking on, and what does the network read as? ----
        groups.add(networkGroup(ctx, prefs, z, sh, targets));

        return groups;
    }

    /** Detect a GPS-mocking / mock-location signal a fraud SDK could read as a risk flag. Two config-level
     *  signals (the app hooks make a SCOPED app read them clean, but the DEVICE-level config is what an
     *  unscoped detector or a mis-scoped run sees): (a) any app holding the ANDROID:mock_location app-op /
     *  selected as the mock-location app; (b) legacy Settings.Secure.mock_location=1. This is CONFIG-level,
     *  not a runtime proof inside the target (that needs a scoped mock-Location probe — noted for later). */
    private static Check mockLocationCheck(Context ctx, RootWriter.Shell sh) {
        try {
            // Apps granted the mock-location app-op (the modern "Select mock location app" selection surfaces
            // here). `cmd appops query-op` lists packages currently allowed the op.
            String allowed = sh.runCapture(
                    "cmd appops query-op android:mock_location allow 2>/dev/null").trim();
            // Legacy pre-M flag, still read by some SDKs.
            String legacy = sh.runCapture(
                    "settings get secure mock_location 2>/dev/null").trim();
            boolean legacyOn = "1".equals(legacy);
            java.util.List<String> apps = new java.util.ArrayList<>();
            if (!allowed.isEmpty()) {
                for (String ln : allowed.split("\\r?\\n")) {
                    ln = ln.trim();
                    if (!ln.isEmpty() && ln.contains(".")) apps.add(ln);
                }
            }
            if (apps.isEmpty() && !legacyOn) {
                return Check.ok("Mock location", "No GPS-mocking app or mock-location flag detected device-wide.");
            }
            StringBuilder d = new StringBuilder("Detectable: ");
            if (!apps.isEmpty()) d.append("mock-location app(s) ").append(String.join(", ", apps));
            if (legacyOn) { if (!apps.isEmpty()) d.append("; "); d.append("Settings.Secure.mock_location=1"); }
            d.append(". A scoped target reads these clean via the hooks, but an unscoped/mis-scoped detector "
                    + "(or the GPS app itself) can see it — keep a mock-location HIDER active, or turn the "
                    + "selection off when not spoofing GPS.");
            return Check.warn("Mock location", d.toString(), Fix.NONE, null);
        } catch (Throwable t) {
            return Check.warn("Mock location", "Couldn't check mock-location state (su/appops unavailable).",
                    Fix.NONE, null);
        }
    }

    /** VPN-mask toggle state + the current public (proxy exit) IP and its geolocation. The IP/geo tells the
     *  user what the network reads as, so they can align the device's timezone/locale to it. Blocking HTTP —
     *  runAll already runs off the UI thread. */
    private static Group networkGroup(Context ctx, SharedPreferences prefs, ZygiskInstaller.Status z,
                                      RootWriter.Shell sh, Set<String> targets) {
        List<Check> out = new ArrayList<>();

        // VPN/proxy masking: the "Hide VPN & proxy" protection. When ON, the Java NetworkInterface hook + the
        // native getifaddrs hook filter tun/ppp/wg in every scoped app. We can only report the toggle + that
        // the native layer is present here (per-app hook engagement lives inside each scoped process).
        Protections.P vpn = Protections.byKey("hide_vpn");
        boolean vpnOn = vpn != null && Protections.isOn(prefs, vpn);
        boolean nativeOk = z != null && z.installed && z.current;   // stale native layer is NOT "ok"
        if (vpnOn && nativeOk) {
            out.add(Check.ok("VPN & proxy masking",
                    "On — VPN/proxy interfaces are hidden on both the Java and native paths in scoped apps."));
        } else if (vpnOn) {
            out.add(Check.warn("VPN & proxy masking",
                    "On, but the native layer isn't installed — an NDK detector can still see tun/ppp/wg. "
                    + "Install the native layer above.", Fix.SYNC_ZYGISK, null));
        } else {
            out.add(Check.warn("VPN & proxy masking",
                    "Off — the device reads as being on a VPN/proxy. Turn on “Hide VPN & proxy” in Protections.",
                    Fix.NONE, null));
        }

        // Routing: is traffic actually going through a VPN/proxy tunnel, or straight out the home network? This
        // is the SAFETY GATE for timezone alignment — we must NEVER align the device timezone to the phone's own
        // home/carrier IP (that would MOVE a real-location device to look like it's elsewhere). Only when a VPN/
        // proxy tunnel is up is the public IP an intentional exit worth matching. Read from ConnectivityManager
        // (this UI app is unscoped, so hide_vpn doesn't hide the tunnel from us).
        android.net.Network vpnNet = activeVpnNetwork(ctx);
        boolean routedThroughVpn = vpnNet != null;
        // HONESTY: we only detect a VPN *transport* (NetworkCapabilities.TRANSPORT_VPN). An app-level HTTP or
        // SOCKS5 proxy that does NOT register a VpnService is invisible to this check and reads "Direct" — we say
        // so instead of implying "no proxy". The card's pill shows the transport state; add a row only when no
        // transport is detected, to carry the honest caveat + the connect-before-matching-TZ guidance.
        if (!routedThroughVpn) {
            out.add(Check.warn("Routing",
                    "No VPN transport detected. If you're on a VpnService-based proxy it should show here; a "
                    + "plain HTTP/SOCKS5 proxy (no VpnService) can't be detected and reads Direct. Timezone is "
                    + "only auto-matched when a VPN transport is present (never to your real network).",
                    Fix.NONE, null));
        }

        // Public IP + geo: one call returns IP, city/country, and the IP's timezone. Pinned to the VPN tunnel
        // when present, so the IP is the proxy exit. The IP/location is rendered as a rich card (Group.geo)
        // above these rows — here we only add the timezone verdict row.
        Geo g = lookupGeo(vpnNet);
        if (g == null) {
            out.add(Check.warn("Public IP", "Couldn't reach the IP lookup — check the connection/proxy.",
                    Fix.NONE, null));
        } else if (g.tz != null) {
            // Timezone alignment — ONLY when routed through a proxy/VPN (see the safety gate above). The device's
            // spoofed timezone (per applied profile) vs the IP's timezone; a mismatch is exactly detectme.pro's
            // "Timezone Mismatch" flag. One-tap fix rewrites the applied profiles' timezone to the IP's zone.
            if (routedThroughVpn && targets != null && !targets.isEmpty()) {
                String mismatch = null;
                for (String pkg : targets) {
                    String ptz = profileTimezone(sh, pkg);
                    if (ptz != null && !ptz.equals(g.tz)) { mismatch = ptz; break; }
                }
                if (mismatch != null) {
                    out.add(Check.warn("Timezone vs IP",
                            "The device reports " + mismatch + " but the IP is in " + g.tz
                            + " — a detectable mismatch. Match the timezone to the IP.", Fix.MATCH_TZ, g.tz));
                } else {
                    out.add(Check.ok("Timezone vs IP", "The device timezone matches the IP’s zone (" + g.tz + ")."));
                }
            } else if (!routedThroughVpn) {
                out.add(Check.warn("Timezone vs IP",
                        "Not matched — connect a proxy/VPN first so the timezone aligns to the exit IP, not your "
                        + "real network.", Fix.NONE, null));
            }
        }

        Group grp = new Group("Network", out);
        grp.geo = g;
        grp.vpnRouting = routedThroughVpn;
        return grp;
    }

    /** True iff the device's active network is a VPN/proxy tunnel (NetworkCapabilities.TRANSPORT_VPN). This app
     *  is unscoped, so hide_vpn never hides the tunnel from it — the read is honest. Best-effort; false if the
     *  API is unavailable. */
    static boolean vpnRouting(Context ctx) {
        return activeVpnNetwork(ctx) != null;
    }

    /** The active network IFF it's a VPN/proxy tunnel (TRANSPORT_VPN), else null. Returning the Network (not
     *  just a bool) lets the geo lookup run THROUGH the tunnel — so the IP we read is provably the proxy exit,
     *  not a home IP momentarily exposed if the VPN flaps (closes the check-then-act / ABA race). This app is
     *  unscoped, so hide_vpn never hides the tunnel from it. */
    static android.net.Network activeVpnNetwork(Context ctx) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                    ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return null;
            android.net.Network net = cm.getActiveNetwork();
            if (net == null) return null;
            android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(net);
            return (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) ? net : null;
        } catch (Throwable t) { return null; }
    }

    /** A per-process runtime attestation heartbeat the Java hook writes after installing its hooks. */
    static final class Heartbeat {
        final String bootId; final int fields; final String version; final long epochMs;
        Heartbeat(String b, int f, String v, long e) { bootId = b; fields = f; version = v; epochMs = e; }
    }

    /** Read {@code pkg}'s heartbeat ({@code bootId|fields|version|epochMs}) from its files dir via su. The hook
     *  writes it world-readable; we read via su because the app dir isn't ours. Null if absent/unparseable. */
    private static Heartbeat readHeartbeat(RootWriter.Shell sh, String pkg) {
        try {
            String s = sh.runCapture("cat /data/data/" + pkg + "/files/.specter_hb 2>/dev/null");
            if (s == null) return null;
            s = s.trim();
            if (s.isEmpty()) return null;
            String[] a = s.split("\\|");
            if (a.length < 4) return null;
            return new Heartbeat(a[0], Integer.parseInt(a[1].trim()), a[2], Long.parseLong(a[3].trim()));
        } catch (Throwable t) { return null; }
    }

    /** The applied profile's timezone for a target, read via su (PROFILE_DIR is root-owned). Null if absent. */
    private static String profileTimezone(RootWriter.Shell sh, String pkg) {
        try {
            String json = sh.runCapture("cat " + RootWriter.PROFILE_DIR + "/" + pkg + ".json 2>/dev/null");
            if (json == null || json.isEmpty()) return null;
            org.json.JSONObject o = new org.json.JSONObject(json);
            return emptyToNull(o.optString("timezone"));
        } catch (Throwable t) { return null; }
    }

    /** Public IP + geo from ipwho.is (free, keyless, HTTPS JSON). tz is the IANA zone of the IP — used to flag
     *  a device-vs-IP timezone mismatch. */
    static final class Geo {
        String ip, city, region, country, tz, isp;
        String location() {
            StringBuilder b = new StringBuilder();
            if (city != null) b.append(city);
            if (region != null) { if (b.length() > 0) b.append(", "); b.append(region); }
            if (country != null) { if (b.length() > 0) b.append(", "); b.append(country); }
            return b.length() > 0 ? b.toString() : "Unknown";
        }
    }

    /** Blocking IP-geo lookup. Null on any failure (offline, timeout, parse). */
    static Geo lookupGeo() { return lookupGeo(null); }

    /** Blocking IP-geo lookup, optionally pinned to a specific {@code net} (the VPN tunnel) so the exit IP is
     *  provably the tunnel's, not a home IP if the VPN flaps mid-lookup. Null on any failure. */
    static Geo lookupGeo(android.net.Network net) {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL("https://ipwho.is/");
            c = (java.net.HttpURLConnection) (net != null ? net.openConnection(u) : u.openConnection());
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            org.json.JSONObject o = new org.json.JSONObject(sb.toString());
            if (!o.optBoolean("success", false)) return null;
            Geo g = new Geo();
            g.ip = emptyToNull(o.optString("ip"));
            g.city = emptyToNull(o.optString("city"));
            g.region = emptyToNull(o.optString("region"));
            g.country = emptyToNull(o.optString("country"));
            org.json.JSONObject tz = o.optJSONObject("timezone");
            if (tz != null) g.tz = emptyToNull(tz.optString("id"));
            org.json.JSONObject conn = o.optJSONObject("connection");
            if (conn != null) g.isp = emptyToNull(conn.optString("isp"));
            if (g.ip == null) return null;
            return g;
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private static String emptyToNull(String s) { return s == null || s.isEmpty() ? null : s; }

    // ---- individual probes (all best-effort, never throw) ----

    private static boolean rootGranted(RootWriter.Shell sh) {
        try { return "ok".equals(trim(sh.runCapture("echo ok"))); } catch (Throwable t) { return false; }
    }

    // The LSPosed config DB — queried STRUCTURALLY (not grepped) via a read-only SQLite copy, so "enabled=1"
    // and the module↔scope relationship are actually verified, not just "the bytes appear somewhere".
    private static final String LSPD_DB = "/data/adb/lspd/config/modules_config.db";

    private static boolean moduleEnabled(Context ctx, RootWriter.Shell sh) {
        // enabled column for com.specter's module row. Structural query beats a byte-grep (a disabled module's
        // pkg name still appears in the file).
        Integer v = queryInt(ctx, sh, "SELECT enabled FROM modules WHERE module_pkg_name='com.specter' LIMIT 1;");
        return v != null && v == 1;
    }

    /** Is "System Framework" (android/system) in SPECTER'S scope specifically (join scope->modules)? */
    private static boolean frameworkScopeSet(Context ctx, RootWriter.Shell sh) {
        Integer v = queryInt(ctx, sh, "SELECT COUNT(*) FROM scope s JOIN modules m ON s.mid=m.mid "
                + "WHERE m.module_pkg_name='com.specter' AND s.app_pkg_name IN ('android','system');");
        return v != null && v > 0;
    }

    /** True if a specific app is in Specter's scope (join, not a loose grep of the whole file). */
    private static boolean appScoped(Context ctx, RootWriter.Shell sh, String pkg) {
        if (pkg == null || pkg.isEmpty()) return true;
        Integer v = queryInt(ctx, sh, "SELECT COUNT(*) FROM scope s JOIN modules m ON s.mid=m.mid "
                + "WHERE m.module_pkg_name='com.specter' AND s.app_pkg_name='" + pkg.replace("'", "") + "';");
        return v != null && v > 0;
    }

    /** The gate logs "app-hiding gate installed" from system_server on boot; its presence == the gate loaded.
     *  grep -rq (quiet, any-file match) avoids the -rhc/head-1 false-negative when a LATER log file matches. */
    private static boolean frameworkGateLoaded(RootWriter.Shell sh) {
        // Boot-scoped: the gate writes a heartbeat (methods|epochMs) in system_server when it installs. A stale
        // log line from a previous boot no longer reads as loaded (the old recursive grep did). GREEN only when
        // the heartbeat was written on THIS boot (epoch >= boot wall-time, 10s slack).
        try {
            String s = sh.runCapture("cat " + HookEntry.FRAMEWORK_HB_PATH + " 2>/dev/null");
            if (s == null) return false;
            s = s.trim();
            if (s.isEmpty()) return false;
            String[] a = s.split("\\|");
            if (a.length < 2) return false;
            long methods = Long.parseLong(a[0].trim());   // hooked-method count — 0 means the gate didn't install
            long epoch = Long.parseLong(a[1].trim());
            long nowMs = System.currentTimeMillis();
            long bootWallMs = nowMs - android.os.SystemClock.elapsedRealtime();
            // This boot (lower bound) AND not a forged/rolled-forward future epoch (upper bound) AND actually
            // hooked >=1 method.
            return methods > 0 && epoch >= (bootWallMs - 10_000L) && epoch <= (nowMs + 60_000L);
        } catch (Throwable t) { return false; }
    }

    /** Run a single-integer SQLite query against the LSPosed DB. The DB is root-owned in shell_data_file
     *  context; the APP's SELinux domain is DENIED read on /data/local/tmp (confirmed avc denial), so we copy
     *  it (via su) into the app's OWN files dir (app_data_file — always app-readable) and open it READ-ONLY
     *  with Android's own SQLite (no on-device sqlite3 binary needed). Null on any failure. */
    private static Integer queryInt(Context ctx, RootWriter.Shell sh, String sql) {
        java.io.File tmp = new java.io.File(ctx.getFilesDir(), "lspd_ro.db");
        String tp = tmp.getAbsolutePath();
        try {
            // Copy the checkpointed DB into the app dir. `.timeout`+WAL: force a checkpoint first so the plain
            // .db has the latest rows, then copy just the .db (a stale -wal in a different dir would confuse it).
            int uid = ctx.getApplicationInfo().uid;
            sh.runCapture("cp -f " + LSPD_DB + " '" + tp + "' 2>/dev/null; chmod 660 '" + tp
                    + "' 2>/dev/null; chown " + uid + ":" + uid + " '" + tp + "' 2>/dev/null");
            android.database.sqlite.SQLiteDatabase db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    tp, null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            try (android.database.Cursor c = db.rawQuery(sql, null)) {
                if (c.moveToFirst()) return c.getInt(0);
            } finally { db.close(); }
        } catch (Throwable t) { /* fall through */ }
        finally { try { //noinspection ResultOfMethodCallIgnored
            tmp.delete(); } catch (Throwable ignored) {} }
        return null;
    }

    /** A per-app profile is applied iff its live profile JSON exists in the push dir. */
    private static boolean profileApplied(RootWriter.Shell sh, String pkg) {
        try {
            String out = sh.runCapture("[ -f " + RootWriter.PROFILE_DIR + "/" + pkg + ".json ] && echo y || echo n");
            return "y".equals(trim(out));
        } catch (Throwable t) { return false; }
    }

    private static String trim(String s) { return s == null ? "" : s.trim(); }
    private static String nn(String s) { return s == null ? "?" : s; }
}
