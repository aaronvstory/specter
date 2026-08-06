package com.specter.module.ui;

import android.content.Context;
import android.content.SharedPreferences;

import com.specter.module.HookConstants;
import com.specter.module.gen.Country;
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

    // READY = configured and the module is PROVEN to load this boot (some scoped app wrote a fresh heartbeat),
    // but THIS app hasn't run yet — so it will hook on launch. Distinct from OK (this app proven hooked this boot)
    // and from WARN (genuinely unverified). Lets the status not nag "open every app" once one has proven the layer.
    enum State { OK, WARN, BAD, READY }

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
        static Check ready(String l, String d, String a) { return new Check(l, State.READY, d, Fix.NONE, a); }
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
                    "Scoped, but not loaded · reboot or toggle scope", Fix.NONE, null));
        } else {
            setup.add(Check.warn("App-hiding gate",
                    "Enable System Framework scope, then reboot", Fix.NONE, null));
        }
        groups.add(new Group("Setup", setup));

        // ---- Native layer ----
        List<Check> nativeG = new ArrayList<>();
        ZygiskInstaller.Status z;
        try { z = ZygiskInstaller.status(ctx, sh); } catch (Throwable t) { z = null; }
        if (z == null || z.bundledVersion == null) {
            nativeG.add(Check.warn("Native layer", "Check unavailable · verify root and bundled assets",
                    Fix.NONE, null));
        } else if (!z.installed) {
            nativeG.add(Check.bad("Native layer", "Not installed · native reads remain unmasked",
                    Fix.SYNC_ZYGISK, null));
        } else if (!z.current) {
            nativeG.add(Check.warn("Native layer",
                    "Installed " + nn(z.installedVersion) + " · bundled " + nn(z.bundledVersion),
                    Fix.SYNC_ZYGISK, null));
        } else {
            nativeG.add(Check.ok("Native layer", "Installed and current · " + nn(z.installedVersion)));
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
            long nowMs = System.currentTimeMillis();

            // Pass 1: read every target's facts up front, and note whether the module has PROVEN it loads on this
            // boot in ANY scoped target (a single fresh, same-version heartbeat). One launched app proves the whole
            // injection layer works this boot — so every OTHER scoped+applied app is READY (will hook on launch)
            // without its own launch. That's what stops the "open each app to check it" churn (steer #18), while
            // still reserving GREEN for an app whose OWN hooks are proven running this boot (no false-GREEN).
            final class AppFact {
                String pkg, label; boolean scoped, applied; Heartbeat hb; boolean fresh, sameVer, live;
            }
            List<AppFact> facts = new ArrayList<>();
            boolean moduleLiveThisBoot = false;
            for (String pkg : targets) {
                AppFact f = new AppFact();
                f.pkg = pkg;
                f.label = Targets.label(ctx, pkg);
                f.scoped = appScoped(ctx, sh, pkg);
                f.applied = profileApplied(sh, pkg);
                if (f.scoped && f.applied) {
                    f.hb = readHeartbeat(sh, pkg);
                    // Written this boot, by THIS module version? epochMs must be within [bootWall-10s, now+1min]:
                    // the lower bound rejects a previous boot; the upper bound rejects a forged/rolled-forward
                    // future timestamp (a stale heartbeat can't pass by jumping the clock ahead). The version
                    // match rejects a heartbeat written by OLD module code still loaded after an APK update.
                    f.fresh = f.hb != null && f.hb.epochMs >= (bootWallMs - 10_000L) && f.hb.epochMs <= (nowMs + 60_000L);
                    f.sameVer = f.hb != null && HookConstants.MODULE_VERSION.equals(f.hb.version);
                    f.live = f.fresh && f.sameVer;
                    if (f.live) moduleLiveThisBoot = true;
                }
                facts.add(f);
            }

            // Pass 2: emit one check per target now that "module live this boot" is known.
            for (AppFact f : facts) {
                if (!f.scoped) {
                    perApp.add(Check.bad(f.label, "Not scoped · add in LSPosed, then reboot", Fix.NONE, f.pkg));
                } else if (!f.applied) {
                    perApp.add(Check.warn(f.label, "Scoped · no identity applied", Fix.REAPPLY_PROFILE, f.pkg));
                } else if (f.live) {
                    // "N fields" is the loaded profile's key count, NOT a per-hook success count — each hookX()
                    // swallows its own errors, so a signal could still have failed to hook. Word it as "loaded this
                    // boot" (the heartbeat proves the Java layer ran + read the profile), not "every field verified".
                    perApp.add(Check.ok(f.label,
                            "Hooks loaded this boot · " + f.hb.fields + " profile fields · v" + f.hb.version));
                } else if (f.hb != null && f.fresh && !f.sameVer) {
                    perApp.add(Check.warn(f.label,
                            "Old module loaded · " + f.hb.version + " → " + HookConstants.MODULE_VERSION,
                            Fix.NONE, f.pkg));
                } else if (moduleLiveThisBoot) {
                    // Not run yet this boot, but the layer is proven live (another scoped app has a fresh heartbeat)
                    // and this app is scoped + has an identity — so it WILL hook on launch. READY, not a warning.
                    perApp.add(Check.ready(f.label,
                            "Ready · identity applied · module live this boot — hooks on launch", f.pkg));
                } else {
                    // Nothing has proven the module loads on this boot yet (e.g. just after a reboot). Open ANY one
                    // target once to confirm the layer is live — then the rest read READY without their own launch.
                    perApp.add(Check.warn(f.label,
                            "Identity applied · open any target once to confirm hooks load this boot",
                            Fix.NONE, f.pkg));
                }
            }
        }
        groups.add(new Group("Target apps", perApp));

        // ---- Location: is Specter's mock-location hook armed? ----
        groups.add(new Group("Location", java.util.Collections.singletonList(mockLocationCheck(prefs, sh))));

        // ---- Network: is VPN/proxy masking on, and what does the network read as? ----
        groups.add(networkGroup(ctx, prefs, z, sh, targets));

        return groups;
    }

    /** Specter hides the mock-location flag from every scoped app on its own (Location.isMock/
     *  isFromMockProvider + the Settings mock_location keys) — a mocker like Lockito being installed or
     *  selected is normal, expected use, not a leak. GREEN means the hide_mock protection is armed; a
     *  device-wide flag is only ever a suffix note, never a downgrade. */
    private static Check mockLocationCheck(SharedPreferences prefs, RootWriter.Shell sh) {
        Protections.P protection = Protections.byKey("hide_mock");
        boolean enabled = protection != null && Protections.isOn(prefs, protection);

        if (!enabled) {
            return Check.warn("Mock location", "Off · scoped apps can read mock-location flags", Fix.NONE, null);
        }

        try {
            String legacy = sh.runCapture("settings get secure mock_location 2>/dev/null").trim();
            if ("1".equals(legacy)) {
                return Check.ok("Mock location", "Hidden in scoped apps · device flag is on");
            }
            return Check.ok("Mock location", "Hidden in scoped apps");
        } catch (Throwable t) {
            return Check.ok("Mock location", "Hidden in scoped apps · device flag unavailable");
        }
    }

    /** VPN-mask toggle state + the current public (proxy exit) IP and its geolocation. The IP/geo tells the
     *  user what the network reads as, so they can align the device's timezone/locale to it. Blocking HTTP —
     *  runAll already runs off the UI thread. */
    private static Group networkGroup(Context ctx, SharedPreferences prefs, ZygiskInstaller.Status z,
                                      RootWriter.Shell sh, Set<String> targets) {
        List<Check> out = new ArrayList<>();

        // VPN interface masking: the "Hide VPN interfaces" protection. When ON, the Java NetworkInterface hook +
        // the native getifaddrs hook filter tun/ppp/wg in every scoped app. We can only report the toggle + that
        // the native layer is present here (per-app hook engagement lives inside each scoped process).
        Protections.P vpn = Protections.byKey("hide_vpn");
        boolean vpnOn = vpn != null && Protections.isOn(prefs, vpn);
        boolean nativeOk = z != null && z.installed && z.current;   // stale native layer is NOT "ok"
        if (vpnOn && nativeOk) {
            out.add(Check.ok("VPN interface masking", "Java and native VPN interfaces are hidden"));
        } else if (vpnOn) {
            out.add(Check.warn("VPN interface masking",
                    "Native masking unavailable · install the native layer", Fix.SYNC_ZYGISK, null));
        } else {
            out.add(Check.warn("VPN interface masking",
                    "Off · scoped apps can read VPN interfaces", Fix.NONE, null));
        }

        // Routing: is traffic actually going through a VPN/proxy tunnel, or straight out the home network? This
        // gates the AUTOMATIC paths — auto timezone alignment on apply, and auto reputation check on open — which
        // stay tunnel-only so the phone's real IP is never handed to a fraud API or matched by accident. Manual,
        // user-confirmed actions (the "check this IP anyway" / timezone-fix buttons) ARE allowed off-tunnel on
        // the real IP — the user asked to score/align without a tunnel — behind an explicit "uses your real IP"
        // confirm in the UI. Read from ConnectivityManager (this UI app is unscoped, so hide_vpn doesn't hide the
        // tunnel from us). No standalone warning row here — missing VPN transport can't prove direct traffic (a
        // plain proxy or upstream VPN would still read this way), so a red flag would overclaim.
        android.net.Network vpnNet = activeVpnNetwork(ctx);
        boolean routedThroughVpn = vpnNet != null;

        // Public IP + geo: one call returns IP, city/country, and the IP's timezone. Pinned to the VPN tunnel
        // when present, so the IP is the proxy exit. The IP/location is rendered as a rich card (Group.geo)
        // above these rows — here we only add the timezone verdict row.
        Geo g = lookupGeo(vpnNet);
        if (g == null) {
            out.add(Check.warn("Public IP", "IP lookup unavailable · check network", Fix.NONE, null));
        } else if (g.tz != null) {
            // Timezone alignment: the device's spoofed timezone (per applied profile) vs the IP's timezone; a
            // mismatch is exactly detectme.pro's "Timezone Mismatch" flag. One-tap fix rewrites the applied
            // profiles' timezone to the IP's zone. Offered BOTH on- and off-tunnel — the difference is only WHICH
            // IP's zone (the proxy exit's, or the device's real public IP off-tunnel). Off-tunnel the fix goes
            // through a "this uses your real IP" confirm (auto-alignment on apply still stays tunnel-only).
            if (targets != null && !targets.isEmpty()) {
                String mismatch = null;
                for (String pkg : targets) {
                    String ptz = profileTimezone(sh, pkg);
                    if (ptz != null && !ptz.equals(g.tz)) { mismatch = ptz; break; }
                }
                String src = routedThroughVpn ? "IP" : "real IP";
                if (mismatch != null) {
                    out.add(Check.warn("Timezone vs IP",
                            "Device: " + mismatch + " · " + src + ": " + g.tz, Fix.MATCH_TZ, g.tz));
                } else {
                    out.add(Check.ok("Timezone vs IP", "Device timezone matches " + src + " · " + g.tz));
                }
            }
        }

        // Carrier-country vs exit-IP-country coherence (zero API cost). A US SIM (MCC 310-316) reading behind a
        // non-US exit IP is an incoherence fraud models weight (SIM country vs IP country) — the same family as
        // the timezone mismatch above, but on a signal the timezone check can miss. One-directional: only emit a
        // row when BOTH the carrier country and the IP country are known; an unmappable MCC never false-warns.
        if (g != null && g.countryCode != null && targets != null && !targets.isEmpty()) {
            String carrierIso = null;
            for (String pkg : targets) {
                String iso = Country.countryIsoForMcc(profileSimMcc(sh, pkg));
                if (iso != null) { carrierIso = iso; break; }
            }
            if (carrierIso != null) {
                String ipCc = g.countryCode.toUpperCase(java.util.Locale.US);
                if (!carrierIso.equalsIgnoreCase(ipCc)) {
                    out.add(Check.warn("Carrier vs IP", "SIM: " + carrierIso + " · IP: " + ipCc, Fix.NONE, null));
                } else {
                    out.add(Check.ok("Carrier vs IP", "SIM country matches IP · " + carrierIso));
                }
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

    /** The applied profile's SIM operator MCC/MNC for a target, read via su (PROFILE_DIR is root-owned). Null
     *  if absent. Feeds the carrier-country vs IP-country coherence check. */
    private static String profileSimMcc(RootWriter.Shell sh, String pkg) {
        try {
            String json = sh.runCapture("cat " + RootWriter.PROFILE_DIR + "/" + pkg + ".json 2>/dev/null");
            if (json == null || json.isEmpty()) return null;
            org.json.JSONObject o = new org.json.JSONObject(json);
            return emptyToNull(o.optString("sim_operator_mccmnc"));
        } catch (Throwable t) { return null; }
    }

    /** Public IP + geo from ipwho.is (free, keyless, HTTPS JSON). tz is the IANA zone of the IP — used to flag
     *  a device-vs-IP timezone mismatch. */
    static final class Geo {
        String ip, city, region, country, countryCode, tz, isp;
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
        org.json.JSONObject o = getJson(net, "https://ipwho.is/", null);
        if (o == null || !o.optBoolean("success", false)) return null;
        Geo g = new Geo();
        g.ip = emptyToNull(o.optString("ip"));
        g.city = emptyToNull(o.optString("city"));
        g.region = emptyToNull(o.optString("region"));
        g.country = emptyToNull(o.optString("country"));
        g.countryCode = emptyToNull(o.optString("country_code"));
        org.json.JSONObject tz = o.optJSONObject("timezone");
        if (tz != null) g.tz = emptyToNull(tz.optString("id"));
        org.json.JSONObject conn = o.optJSONObject("connection");
        if (conn != null) g.isp = emptyToNull(conn.optString("isp"));
        return g.ip == null ? null : g;
    }

    /** GET a JSON document, optionally PINNED to {@code net} (the tunnel), with optional extra request headers
     *  as flat name/value pairs. Null on a transport failure; an API's OWN error body (401 bad key, 429 quota
     *  spent) is returned parsed, so a caller can repeat the real reason instead of guessing. Blocking; call
     *  off the UI thread. */
    private static org.json.JSONObject getJson(android.net.Network net, String url, String[] headers) {
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL(url);
            c = (java.net.HttpURLConnection) (net != null ? net.openConnection(u) : u.openConnection());
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);
            if (headers != null) {
                for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
            }
            java.io.InputStream in;
            try {
                in = c.getInputStream();
            } catch (java.io.IOException e) {
                // Non-2xx: getInputStream throws and the explanatory body is only on the error stream.
                in = c.getErrorStream();
                if (in == null) return null;
            }
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
            r.close();
            return new org.json.JSONObject(sb.toString());
        } catch (Throwable t) {
            return null;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /** AbuseIPDB's failure reason from its error body ({@code errors[0].detail}), else a generic hint. */
    private static String abuseError(org.json.JSONObject o) {
        if (o != null) {
            org.json.JSONArray errs = o.optJSONArray("errors");
            org.json.JSONObject first = errs != null ? errs.optJSONObject(0) : null;
            String detail = first != null ? first.optString("detail") : null;
            if (detail != null && !detail.isEmpty()) return detail;
        }
        return "lookup rejected · check the key or the daily quota";
    }

    // ---- Exit-IP reputation ------------------------------------------------------------------------------
    // A perfectly coherent device on a burned proxy exit IP still draws login friction, and that has nothing to
    // do with the fingerprint. Specter showed IP + geo + timezone but was blind to how the IP itself scores, so
    // a dirty proxy looked identical to a clean one. These lookups are ON DEMAND (never auto-polled — the IPQS
    // free tier is 35/day) and always run THROUGH the tunnel, same gate the geo/timezone path uses.

    /** IPQualityScore's scoring strictness, sent on every lookup. MEASURED on 23.159.216.252 (a Mullvad exit,
     *  AS17243) on 2026-08-05: strictness 0 returns fraud_score 20 with proxy=false — blind to a commercial VPN
     *  exit — while strictness 1 returns 100 with proxy, recent_abuse and bot_status all true. Strictness 2
     *  matches 1. IPQS documents 0 as the recommended starting point, but 0 cannot answer the only question
     *  this check asks. The readout names the setting, because the same IP scores differently on other
     *  checkers and a reader needs to be able to reconcile that. */
    static final int IPQS_STRICTNESS = 1;

    /** Scamalytics v3. api11 is the US node; EU accounts answer on api12 — an account is BOUND to the node
     *  picked at signup, so this is not a failover pair. Mirrors SCAM_HOST in specter/ipcheck.py. */
    static final String SCAM_HOST = "api11.scamalytics.com";

    /** ip2proxy's taxonomy for "this is not a real line". PUB/WEB/SES bucket into {@code datacenter}
     *  because the friction at a strict app is identical; the exact code renders on its own detail row, so
     *  the coarse bucket never hides what was measured. Mirrors _SCAM_DC_TYPES in specter/ipcheck.py
     *  (pinned by tests/test_ipcheck.py). */
    static final java.util.Set<String> SCAM_DC_TYPES = new java.util.HashSet<>(
            java.util.Arrays.asList("DCH", "VPN", "PUB", "WEB", "SES"));

    /** The DNS-over-HTTPS resolver the blocklist lookups go through.
     *
     *  <p>NOT Cloudflare, and NOT Google. Spamhaus refuses queries relayed by large public resolvers, and
     *  the two big ones fail that differently — MEASURED 2026-08-05 on 185.220.101.45, an IP the system
     *  resolver finds on seven abuse lists:
     *  <ul>
     *    <li>Cloudflare answers {@code 127.255.255.254} for Spamhaus and CBL (an explicit refusal, which
     *        {@link Dnsbl#classify} correctly excludes rather than counting clear) and SERVFAILs SpamRATS.
     *        Safe, but three zones are silently lost — which is what showed up on the phone as zones with
     *        no answer.</li>
     *    <li>Google answers NXDOMAIN for Spamhaus and CBL — indistinguishable from "not listed". That is a
     *        FALSE CLEAN on a listed IP, and is the reason Google is not the fallback.</li>
     *    <li>dns.sb returns the true records: Spamhaus {@code 127.0.0.11}, and agrees with the system
     *        resolver on 17 of 17 zones (Cloudflare managed 14).</li>
     *  </ul>
     *
     *  <p>DoH is mandatory here regardless of resolver: the proxy apps this runs behind hijack DNS with a
     *  fake-IP pool, so a plain lookup can never see a 127.0.0.x listing code through the tunnel. */
    static final String DOH = "https://doh.sb/dns-query?type=A&name=";

    /** Fallback resolver, tried only when the primary gives no usable answer at all. Cloudflare loses
     *  Spamhaus/CBL/SpamRATS but never manufactures a clean result, so it degrades safely — which is the
     *  only property a fallback needs. Without one, a dns.sb outage would silently drop every zone. */
    static final String DOH_FALLBACK = "https://cloudflare-dns.com/dns-query?type=A&name=";

    /** How the exit IP looks to fraud/abuse data sources. Every field is optional — with no API keys set this
     *  still carries the keyless DNSBL blacklist count. */
    static final class Reputation {
        String ip;
        Integer fraudScore;                     // IPQualityScore 0-100 (null = no key / lookup failed)
        Boolean proxy, vpn, tor, recentAbuse;   // IPQualityScore verdicts
        /** IPQualityScore's mobile flag. Read because connection_class() in specter/ipcheck.py gives
         *  `mobile` precedence over the datacenter name regex — without it, a mobile exit whose ISP
         *  string happens to contain a hosting term classifies as `mobile` on desktop and `datacenter`
         *  on the phone, for the same IP. */
        Boolean mobile;
        Integer abuseConfidence, abuseReports;  // AbuseIPDB (null = no key / lookup failed)
        Double getipintel;                       // getIPIntel 0-1 proxy/hosting probability (null = not run)
        boolean getipintelBad;                   // getIPIntel: the IP behaved maliciously
        String connectionType, organization, asn, abuseVelocity, isp, host;   // IPQualityScore context
        String usageType, countryCode, domain, lastReport;    // AbuseIPDB context
        Integer abuseReporters;                 // AbuseIPDB: distinct reporters (one loud reporter != real abuse)
        String getipintelCountry;               // getIPIntel oflags=c
        /** Scamalytics. {@code scamRisk == null} is the "did it run?" sentinel — nothing else is
         *  guaranteed present. The SCORE is carried for display only and has ZERO weight in
         *  {@link #verdictFactors}: measured, it tracks scamIspScore on every IP (an ASN prior) and
         *  mis-ranks — a Tor exit scored 15 "low", clean Comcast 18, Mullvad 44 the highest of the set. */
        Integer scamScore, scamIspScore;
        String scamRisk, scamIspRisk, scamProxyType, scamUrl;
        Boolean scamDatacenter, scamVpn, scamTor, scamBlacklistedExternal;
        // ip-api.com — KEYLESS hosting/proxy/mobile classifier (mirrors _ipapi_lookup in ipcheck.py). Gives
        // a no-key user a real exit-type verdict where before only the name regex + getIPIntel could.
        Boolean ipapiHosting, ipapiProxy, ipapiMobile;
        String ipapiAsname;
        int dnsblChecked;                       // DNSBL zones that actually answered
        boolean dnsblUsable;                    // the sentinel resolved -> a zero-hit result is trustworthy
        /** Which table was queried, and how many zones it holds — the HONEST denominator. An IPv6 exit is
         *  checked against four zones, not seventeen, and reporting "0 of 17" there would be a lie. */
        String dnsblFamily;                     // "ipv4" / "ipv6" / null when the address parsed as neither
        int dnsblZonesTotal;
        final List<String> blacklists = new ArrayList<>();    // zones listing this IP for ABUSE
        final List<String> policyLists = new ArrayList<>();   // dynamic/consumer-range listings (not abuse)
        /** Per-zone outcome for the detail breakdown: zone name -> listed / policy / clean / refused /
         *  no answer. Every zone in the table appears, so "12 clean, 3 refused" is visible instead of only
         *  the count of hits. Mirrors dnsbl_detail in specter/ipcheck.py. */
        final java.util.LinkedHashMap<String, String> zoneStatus = new java.util.LinkedHashMap<>();
        /** Why a source had nothing to say (bad key, quota spent). One per source — a single field
         *  would let whichever failed first hide the other's reason entirely. */
        final List<String> notes = new ArrayList<>();
    }

    /** What a getIPIntel error code means, in one line. Mirrors GETIPINTEL_ERRORS in specter/ipcheck.py.
     *  -5 is the one worth acting on: the CONNECTING device is banned or over quota, which says nothing
     *  about the IP being checked. */
    static String getipintelError(int code, String raw) {
        switch (code) {
            case -1: return "invalid query";
            case -2: return "invalid IP address";
            case -3: return "unroutable or private address";
            case -4: return "database unreachable — mid-update";
            case -5: return "over quota from here — this IP wasn't checked";
            case -6: return "no valid contact address";
            default: return "error " + raw;
        }
    }

    /** What a getIPIntel 0-1 probability means, in words. Deliberately NOT "residential" at the low end: a low
     *  score means getIPIntel saw no proxy evidence, which is not the same as proving a real ISP line.
     *  Mirrors getipintel_band() in specter/ipcheck.py. */
    static String getipintelBand(double score) {
        return score >= 0.99 ? "proxy/hosting exit" : score >= 0.90 ? "likely proxy"
                : score >= 0.50 ? "mixed signals" : "no proxy signal";
    }

    /** clean / suspect / dirty / unknown, plus the individual signals that decided it — element 0 is the
     *  level, the rest are the factors. Mirrors verdict_factors() in specter/ipcheck.py, INCLUDING its
     *  central judgement: a bare high fraud score never decides, because IPQS scores almost any proxy 75-100
     *  and vetting proxies is the whole point. What separates a usable exit from a burned one is
     *  datacenter-vs-real-line plus INDEPENDENT abuse evidence.
     *
     *  <p>{@code geoIsp} is the free ipwho.is ISP, needed because rep.isp is only set when an IPQS key is
     *  present — without it the datacenter check silently never fires on the keyless path. */
    static List<String> verdictFactors(Reputation r, String geoIsp) {
        List<String> why = new ArrayList<>();
        if (r == null) { why.add("unknown"); why.add("no check has run"); return why; }
        int hits = r.blacklists.size();
        boolean tor = Boolean.TRUE.equals(r.scamTor);
        // `mobile` outranks the datacenter signal here too, or the verdict would call an exit
        // "datacenter/hosting IP" while the Exit type tile above it reads Mobile.
        boolean dc = !tor && !Boolean.TRUE.equals(r.mobile) && (scamDatacenter(r)
                || isDatacenter(r.organization, r.isp != null ? r.isp : geoIsp, r.host)
                || giiDatacenter(r) || ipapiDatacenter(r));
        boolean ipqsAbuse = Boolean.TRUE.equals(r.recentAbuse);

        // DIRTY: a Tor or datacenter exit, corroborated abuse, or getIPIntel's near-certain hosting verdict.
        // Tor first: a Tor exit reads is_datacenter true as well, and "Tor" is the more useful claim.
        // When Scamalytics is what promoted it, the factor NAMES the source — its specificity on
        // residential pools is proven on only four IPs, so a wrong call has to be diagnosable at a glance.
        if (tor) why.add("Tor exit");
        else if (dc) why.add("datacenter/hosting IP" + (scamDatacenter(r)
                ? " (Scamalytics " + (r.scamProxyType != null ? r.scamProxyType : "is_datacenter") + ")"
                : ipapiDatacenter(r) && !isDatacenter(r.organization, r.isp != null ? r.isp : geoIsp, r.host)
                ? " (ip-api)" : ""));
        if (hits >= 2) why.add(hits + " blacklists");
        if (r.abuseConfidence != null && r.abuseConfidence >= 50) why.add(r.abuseConfidence + "% abuse confidence");
        if (r.getipintelBad) why.add("getIPIntel bad-IP");
        if (r.getipintel != null && r.getipintel >= 0.99) why.add("getIPIntel proxy/hosting");
        if (!why.isEmpty()) { why.add(0, "dirty"); return why; }

        // SUSPECT: one weaker signal worth knowing about. IPQS's abuse flags live HERE, not in dirty — they
        // saturate on shared/residential-proxy IPs the same way its score does.
        if (hits == 1) why.add("1 blacklist");
        if (r.abuseConfidence != null && r.abuseConfidence >= 10 && r.abuseConfidence < 50)
            why.add(r.abuseConfidence + "% abuse confidence");
        if (ipqsAbuse) why.add("IPQS abuse flags");
        if ("medium".equals(r.abuseVelocity) || "high".equals(r.abuseVelocity))
            why.add(r.abuseVelocity + " abuse velocity");
        if (r.getipintel != null && r.getipintel >= 0.90 && r.getipintel < 0.99)
            why.add((int) (r.getipintel * 100) + "% proxy (getIPIntel)");
        if (!why.isEmpty()) { why.add(0, "suspect"); return why; }

        if (r.fraudScore == null && r.abuseConfidence == null && r.getipintel == null && !r.dnsblUsable) {
            why.add("unknown"); why.add("no source answered — add an API key, or check the network");
            return why;
        }
        // CLEAN. Not called "residential": a name heuristic can't prove a real line, only fail to find a
        // hosting name, so the verdict must not claim more than was measured.
        //
        // ...and never claim a blocklist record that was never obtained. When no zone answered — a dead
        // resolver, or every zone refusing this one — this used to say "no abuse history" anyway, turning
        // "we didn't look" into "it's clean". ONE expression, read by both branches, exactly as
        // verdict_factors() in specter/ipcheck.py does it: two branches re-deriving the same decision is
        // how a difference in MEANING hides as a difference in phrasing.
        String blocklists = r.dnsblUsable ? "no abuse or blacklist history" : "blocklists NOT checked";
        why.add("clean");
        why.add("no datacenter signal");
        boolean proxyFlag = Boolean.TRUE.equals(r.proxy) || Boolean.TRUE.equals(r.vpn)
                || Boolean.TRUE.equals(r.tor) || (r.fraudScore != null && r.fraudScore >= 60);
        if (proxyFlag) { why.add(blocklists); why.add("detectable as a proxy/VPN"); }
        else { why.add("no proxy flag"); why.add(blocklists); }
        return why;
    }

    private static volatile Reputation repCache;

    /** The last reputation result IFF it's for {@code ip} — so re-opening the Status screen renders the result
     *  instead of spending another lookup. Process-lifetime only; deliberately not persisted. */
    static Reputation cachedReputation(String ip) {
        Reputation r = repCache;
        return (r != null && ip != null && ip.equals(r.ip)) ? r : null;
    }

    // Hosting/datacenter fingerprints in the ISP/org/host names — the strongest signal for whether a proxy
    // survives a strict app (real users don't originate from AWS/OVH). IPQS's own connection_type is
    // premium-gated, so this reads the free ISP/org/host names instead. Mirrors _DATACENTER_RE in
    // specter/ipcheck.py; keep the two in sync (pinned by tests/test_ipcheck.py).
    private static final java.util.regex.Pattern DATACENTER = java.util.regex.Pattern.compile(
            "\\b(amazon|aws|ec2|amazonaws|google(?!\\s+fiber)|gcp|googleusercontent|azure|microsoft|cloudapp|"
            + "cloudflare|fastly|digitalocean|linode|akamai|vultr|choopa|ovh|hetzner|contabo|leaseweb|m247|datacamp|"
            + "hostwinds|scaleway|oracle\\s+cloud|alibaba|tencent|quadranet|psychz|nforce|serverius|frantech|buyvm|"
            + "colocrossing|hosting|datacenter|data\\s?center|colocation|colo|dedicated\\s+server|virtual\\s+server|"
            + "cloud\\s+server)\\b",
            java.util.regex.Pattern.CASE_INSENSITIVE);

    /** getIPIntel's near-certain hosting verdict, as a datacenter signal of LAST resort. MEASURED: it
     *  grades residential-vs-hosting rather than flagging every proxy (AWS 1.0, Starlink 0.0), which
     *  closes a real gap — Mullvad's exit ISP "Byte Node LLC" matches nothing in DATACENTER and
     *  Scamalytics reported it is_datacenter false with no ip2proxy record, so a known commercial VPN exit
     *  rendered "unclassified". 0.99 because that already earns a DIRTY on its own below, so this adds no
     *  new verdict, only the NAME of what the exit is. Mirrors _GII_HOSTING in specter/ipcheck.py. */
    static final double GII_HOSTING = 0.99;

    static boolean giiDatacenter(Reputation r) {
        return r.getipintel != null && r.getipintel >= GII_HOSTING;
    }

    /** ip-api.com's `hosting` boolean — a KEYLESS datacenter signal (mirrors _ipapi_dc in ipcheck.py). */
    static boolean ipapiDatacenter(Reputation r) {
        return r != null && Boolean.TRUE.equals(r.ipapiHosting);
    }

    /** Scamalytics says hosting/proxy. Consulted alongside the name regex because it caught all four
     *  hosting IPs the regex missed (including Mullvad's "Byte Node LLC") and stayed quiet on all four
     *  real residential exits. Mirrors _scam_dc() in specter/ipcheck.py. */
    static boolean scamDatacenter(Reputation r) {
        return Boolean.TRUE.equals(r.scamDatacenter) || SCAM_DC_TYPES.contains(r.scamProxyType);
    }

    /** "tor" / "mobile" / "datacenter" / null for the readout. null = couldn't tell — deliberately not
     *  guessed "residential". Mirrors connection_class() in specter/ipcheck.py. */
    static String connectionClass(Reputation r, String geoIsp) {
        if (r == null) return null;
        if (Boolean.TRUE.equals(r.scamTor)) return "tor";
        // mobile BEFORE the datacenter check, exactly as connection_class() orders it. A real mobile line
        // is a real line whatever its ISP string looks like.
        if (Boolean.TRUE.equals(r.mobile)) return "mobile";
        if (scamDatacenter(r) || isDatacenter(r.organization, r.isp != null ? r.isp : geoIsp, r.host)
                || giiDatacenter(r) || ipapiDatacenter(r))
            return "datacenter";
        return null;
    }

    /** True iff the ISP/org/host names look like a datacenter/hosting provider — best-effort, name-based. */
    static boolean isDatacenter(String... parts) {
        StringBuilder b = new StringBuilder();
        for (String p : parts) if (p != null) b.append(p).append(' ');
        return b.length() > 0 && DATACENTER.matcher(b).find();
    }

    /** Blocking exit-IP reputation lookup. When {@code net} is a VPN tunnel the request is PINNED to it, so the
     *  exit IP is provably the proxy's and the home IP is never exposed. {@code net} may be null (the default
     *  network / the device's REAL public IP) ONLY on the user-confirmed off-tunnel path — the automatic paths
     *  always pass the tunnel. Both keys are optional; with neither set this still returns the keyless DNSBL
     *  count. Never throws. Call off the UI thread. */
    static Reputation lookupReputation(android.net.Network net, String ip, String ipqsKey, String abuseKey,
                                       String getipintelContact) {
        return lookupReputation(net, ip, ipqsKey, abuseKey, getipintelContact, null, null);
    }

    static Reputation lookupReputation(android.net.Network net, String ip, String ipqsKey, String abuseKey,
                                       String getipintelContact, String scamUser, String scamKey) {
        Reputation r = new Reputation();
        r.ip = ip;
        if (ip == null) return r;

        if (ipqsKey != null && !ipqsKey.isEmpty()) {
            org.json.JSONObject o = getJson(net, "https://ipqualityscore.com/api/json/ip/"
                    + enc(ipqsKey) + "/" + enc(ip) + "?strictness=" + IPQS_STRICTNESS, null);
            if (o == null) {
                r.notes.add("IPQualityScore unreachable");
            } else if (!o.optBoolean("success", false)) {
                // Their error body says WHY (bad key vs. daily quota spent) — surface it instead of a
                // generic failure the user can't act on. IPQS takes the key in the URL PATH and echoes it
                // back in the rejection message, so scrub it first (matches the Python core) — even though
                // it's the device owner's own key, a secret must never ride out in a surfaced string.
                String msg = emptyOr(o.optString("message"), "lookup rejected");
                r.notes.add("IPQualityScore: " + msg.replace(ipqsKey, "<key>"));
            } else {
                if (o.has("fraud_score")) r.fraudScore = o.optInt("fraud_score");
                r.proxy = o.optBoolean("proxy", false) || o.optBoolean("active_vpn", false);
                r.vpn = o.optBoolean("vpn", false);
                r.tor = o.optBoolean("tor", false) || o.optBoolean("active_tor", false);
                r.mobile = o.optBoolean("mobile", false);
                r.recentAbuse = o.optBoolean("recent_abuse", false)
                        || o.optBoolean("frequent_abuser", false)
                        || o.optBoolean("high_risk_attacks", false)
                        || o.optBoolean("bot_status", false);
                r.connectionType = paidField(o.optString("connection_type"));
                r.abuseVelocity = paidField(o.optString("abuse_velocity"));
                r.organization = paidField(o.optString("organization"));
                r.isp = paidField(o.optString("ISP"));      // not premium-gated — used for datacenter detection
                r.host = paidField(o.optString("host"));
                if (o.optInt("ASN", 0) > 0) r.asn = "AS" + o.optInt("ASN");
            }
        }

        if (abuseKey != null && !abuseKey.isEmpty()) {
            org.json.JSONObject o = getJson(net,
                    "https://api.abuseipdb.com/api/v2/check?maxAgeInDays=90&ipAddress=" + enc(ip),
                    new String[]{"Key", abuseKey, "Accept", "application/json"});
            org.json.JSONObject d = o != null ? o.optJSONObject("data") : null;
            if (d != null) {
                r.abuseConfidence = d.optInt("abuseConfidenceScore");
                r.abuseReports = d.optInt("totalReports");
                r.abuseReporters = d.optInt("numDistinctUsers");
                r.usageType = emptyToNull(d.optString("usageType"));
                r.countryCode = emptyToNull(d.optString("countryCode"));
                r.domain = emptyToNull(d.optString("domain"));
                r.lastReport = emptyToNull(d.optString("lastReportedAt"));
            } else {
                // Their 401/402/429 bodies carry the actual reason; getJson reads the error stream
                // so we can repeat it instead of guessing between "bad key" and "quota spent".
                r.notes.add("AbuseIPDB: " + abuseError(o));
            }
        }

        // getIPIntel — free, no signup, just a contact email. A 0-1 proxy/hosting probability (near 1 = a
        // hosting/VPN/Tor exit) + a BadIP flag. Docs: do NOT URL-encode the params. Mirrors lookup_getipintel
        // in specter/ipcheck.py.
        if (getipintelContact != null && !getipintelContact.isEmpty()) {
            // oflags is a character SET: "bc" asks for the BadIP verdict AND the country getIPIntel resolved.
            // Verified live 2026-08-05 that the combined form is accepted.
            org.json.JSONObject o = getJson(net, "https://check.getipintel.net/check.php?ip=" + ip
                    + "&contact=" + getipintelContact + "&format=json&oflags=bc", null);
            if (o == null) {
                r.notes.add("getIPIntel unreachable");
            } else {
                // `result` carries the error code on BOTH statuses, so read it first: its one-line meaning
                // beats getIPIntel's own message, which is a three-sentence paragraph of questions.
                Double s = null;
                try { s = Double.parseDouble(o.optString("result")); } catch (NumberFormatException ignored) {}
                if (s != null && s < 0) {
                    r.notes.add("getIPIntel: " + getipintelError((int) (double) s, o.optString("result")));
                } else if (s == null || !"success".equals(o.optString("status"))) {
                    // First sentence only, contact scrubbed — getIPIntel echoes the contact in every response.
                    String msg = emptyOr(o.optString("message"), "rejected — is the contact address valid?");
                    int dot = msg.indexOf(". ");
                    if (dot > 0) msg = msg.substring(0, dot);
                    r.notes.add("getIPIntel: " + msg.replace(getipintelContact, "<contact>"));
                } else {
                    r.getipintel = s;
                    r.getipintelBad = o.optInt("BadIP", 0) == 1;
                    r.getipintelCountry = emptyToNull(o.optString("Country"));
                }
            }
        }

        // Scamalytics — a datacenter/VPN/Tor classifier, plus a score we deliberately do not act on.
        // Mirrors lookup_scamalytics in specter/ipcheck.py, including its traps: HTTP 200 does not mean
        // success (read `status` FIRST — on every error shape `external_datasources` is an empty ARRAY, and
        // optJSONObject returning null is the natural guard for that), and a rejected key answers 404 with
        // an HTML body, so getJson gives null for both "unreachable" and "bad credentials".
        if (scamUser != null && !scamUser.isEmpty() && scamKey != null && !scamKey.isEmpty()) {
            org.json.JSONObject o = getJson(net, "https://" + SCAM_HOST + "/v3/" + enc(scamUser)
                    + "/?key=" + enc(scamKey) + "&ip=" + enc(ip), null);
            org.json.JSONObject s = o != null ? o.optJSONObject("scamalytics") : null;
            org.json.JSONObject credits = o != null ? o.optJSONObject("credits") : null;
            // remaining is int in live mode, str in test mode.
            int left = 1;
            if (credits != null && credits.has("remaining")) {
                try { left = Integer.parseInt(credits.optString("remaining", "1")); }
                catch (NumberFormatException ignored) { left = 1; }
            }
            if (s == null) {
                r.notes.add("Scamalytics: no answer — unreachable, or the user/key was rejected");
            } else if (!"ok".equals(s.optString("status"))) {
                // Never echo the credential: the key rides in the QUERY STRING, so an error message that
                // quotes what was rejected would publish it into the readout.
                String msg = emptyOr(s.optString("error"), "lookup rejected");
                if (msg.contains(scamKey) || msg.contains(scamUser)) msg = "<redacted>";
                r.notes.add("Scamalytics: " + msg);
            } else if (left <= 0) {
                r.notes.add("Scamalytics: credits exhausted — not measured");
            } else {
                org.json.JSONObject prox = s.optJSONObject("scamalytics_proxy");
                org.json.JSONObject ext = s.optJSONObject("external_datasources");
                org.json.JSONObject ip2 = ext != null ? ext.optJSONObject("ip2proxy") : null;
                org.json.JSONObject x4b = ext != null ? ext.optJSONObject("x4bnet") : null;
                if (s.has("scamalytics_score")) r.scamScore = s.optInt("scamalytics_score");
                if (s.has("scamalytics_isp_score")) r.scamIspScore = s.optInt("scamalytics_isp_score");
                r.scamRisk = paidField(s.optString("scamalytics_risk"));
                r.scamIspRisk = paidField(s.optString("scamalytics_isp_risk"));
                r.scamUrl = emptyToNull(s.optString("scamalytics_url"));
                r.scamDatacenter = prox != null && prox.optBoolean("is_datacenter", false);
                r.scamVpn = prox != null && prox.optBoolean("is_vpn", false);
                r.scamBlacklistedExternal = s.optBoolean("is_blacklisted_external", false);
                String ptype = ip2 != null ? paidField(ip2.optString("proxy_type")) : null;
                // "0"/"" means NO RECORD, not "clean" — drop it so the readout can say so.
                if (ptype != null && !"0".equals(ptype)) r.scamProxyType = ptype;
                // x4bnet read is_tor FALSE on a real Tor exit ip2proxy typed "TOR" — union them.
                r.scamTor = (x4b != null && x4b.optBoolean("is_tor", false)) || "TOR".equals(ptype);
            }
        }

        // ip-api.com — KEYLESS hosting/proxy/mobile classifier (mirrors _ipapi_lookup in ipcheck.py). HTTP
        // free tier, through the same Network. A failure/rate-limit is an absent signal, never an error.
        try {
            org.json.JSONObject ia = getJson(net, "http://ip-api.com/json/" + enc(ip)
                    + "?fields=status,proxy,hosting,mobile,asname", null);
            if (ia != null && "success".equals(ia.optString("status"))) {
                r.ipapiHosting = ia.optBoolean("hosting", false);
                r.ipapiProxy = ia.optBoolean("proxy", false);
                r.ipapiMobile = ia.optBoolean("mobile", false);
                r.ipapiAsname = emptyOr(ia.optString("asname"), null);
                if (Boolean.TRUE.equals(r.ipapiMobile)) r.mobile = true;
            }
        } catch (Exception ignored) {}   // a failed ip-api lookup is an absent signal, never fatal — but don't swallow VM Errors

        checkDnsbl(net, ip, r);
        repCache = r;
        return r;
    }

    /** "Found in N blacklists", the keyless way: resolve {@code <reversed-ip>.<zone>} and read the answer (see
     *  {@link Dnsbl} for the query form, what counts as a listing, and the abuse-vs-policy split). Zones run in
     *  parallel behind a hard 10s cap so one dead zone can't stall the check. Only zones that gave a USABLE
     *  answer are counted, so "none of N" can never be a refusal or a timeout wearing a clean face. */
    private static void checkDnsbl(final android.net.Network net, String ip, Reputation out) {
        // Pick the table by address family. An IPv6 exit used to return here and read as a clean verdict
        // backed by ZERO checks; it now gets the four zones that hold IPv6 data, and reports THAT
        // denominator. Mirrors dnsbl_check() in specter/ipcheck.py.
        String rev = Dnsbl.reverseV4(ip);
        final String[][] zones = rev != null ? Dnsbl.ZONES : Dnsbl.ZONES_V6;
        if (rev == null) rev = Dnsbl.reverseV6(ip);
        else out.dnsblFamily = "ipv4";
        if (rev == null) return;                        // parsed as neither family — nothing to query
        if (out.dnsblFamily == null) out.dnsblFamily = "ipv6";
        out.dnsblZonesTotal = zones.length;
        final String rq = rev;
        // "no answer" is the default for every zone in the table: a zone that never replied is not a clean
        // result and must not read as one. Overwritten below by whatever it actually said.
        for (String[] z : zones) out.zoneStatus.put(z[0], "no answer");
        java.util.concurrent.ExecutorService ex =
                java.util.concurrent.Executors.newFixedThreadPool(zones.length);
        try {
            List<java.util.concurrent.Callable<String>> tasks = new ArrayList<>();
            // "" = the zone answered but this IP isn't listed; null = it gave no usable answer.
            for (final String[] z : zones) tasks.add(() -> {
                List<String> a = resolve(net, rq + "." + z[1]);
                if (a == null) return null;
                String kind = Dnsbl.classify(z[1], a);
                // Policy listings carry WHY (the PBL code spelled out); the label holds no ':' so the
                // kind/name split below still works.
                return kind == null ? "" : kind + ":"
                        + (Dnsbl.POLICY.equals(kind) ? Dnsbl.policyLabel(z[0], z[1], a) : z[0]);
            });

            List<java.util.concurrent.Future<String>> fs =
                    ex.invokeAll(tasks, 10, java.util.concurrent.TimeUnit.SECONDS);
            // invokeAll returns futures in submission order, so index i is zones[i] — that's what lets
            // each zone's outcome be recorded by name for the detail breakdown.
            for (int i = 0; i < fs.size(); i++) {
                String zone = zones[i][0];
                String v;
                try { v = fs.get(i).get(); } catch (Throwable t) { continue; }   // timed out / cancelled
                if (v == null) continue;                                  // no usable answer
                // A BLOCKED zone told us nothing — counting it would turn a refusal into a clean result.
                if (v.startsWith(Dnsbl.BLOCKED + ":")) { out.zoneStatus.put(zone, "refused"); continue; }
                out.dnsblChecked++;
                int sep = v.indexOf(':');
                if (sep < 0) { out.zoneStatus.put(zone, "clean"); continue; }   // "" — answered, not listed
                String kind = v.substring(0, sep), zoneName = v.substring(sep + 1);
                if (Dnsbl.ABUSE.equals(kind)) { out.blacklists.add(zoneName); out.zoneStatus.put(zone, "listed"); }
                else if (Dnsbl.POLICY.equals(kind)) { out.policyLists.add(zoneName); out.zoneStatus.put(zone, "policy"); }
                else out.zoneStatus.put(zone, "clean");
            }
            // DoH returns an explicit status, so a zone that answered NXDOMAIN is a definitive "not listed" —
            // no sentinel probe needed to tell a real all-clear from a dead resolver.
            out.dnsblUsable = out.dnsblChecked > 0;
        } catch (Throwable t) { /* best-effort */ }
        finally { try { ex.shutdownNow(); } catch (Throwable ignored) {} }
    }

    /** Every address {@code host} resolves to, or null if the lookup gave no usable answer.
     *
     *  <p>Resolved over DNS-over-HTTPS, NOT {@code InetAddress}, because the proxy apps this feature exists for
     *  hijack DNS: SuperProxy answers every hostname with a synthetic address from its own fake-IP pool
     *  (measured on-device: every DNSBL zone returned {@code 10.207.x.x}), so a plain resolve can never see a
     *  127.0.0.x listing code through the tunnel. DoH is an ordinary HTTPS request, so it rides the proxy and
     *  comes back with the real answer. It also removes the NXDOMAIN-vs-SERVFAIL ambiguity that
     *  {@code UnknownHostException} collapses: {@code Status 3} is a definitive "not listed", anything else is
     *  "no answer". Still pinned to {@code net} — the request must leave through the tunnel like every other. */
    private static List<String> resolve(android.net.Network net, String host) {
        List<String> r = resolveVia(net, DOH, host);
        // Only when the primary said NOTHING usable. An answer — including an explicit refusal record —
        // is a result, and re-asking a second resolver for it would just spend another round trip.
        return r != null ? r : resolveVia(net, DOH_FALLBACK, host);
    }

    private static List<String> resolveVia(android.net.Network net, String doh, String host) {
        org.json.JSONObject o = getJson(net, doh + enc(host),
                new String[]{"Accept", "application/dns-json"});
        if (o == null) return null;
        int status = o.optInt("Status", -1);
        if (status == 3) return new ArrayList<>();     // NXDOMAIN — answered, this IP isn't listed
        if (status != 0) return null;                  // SERVFAIL / refused — no usable answer
        List<String> out = new ArrayList<>();
        org.json.JSONArray ans = o.optJSONArray("Answer");
        if (ans != null) {
            for (int i = 0; i < ans.length(); i++) {
                org.json.JSONObject a = ans.optJSONObject(i);
                if (a != null && a.optInt("type") == 1) out.add(a.optString("data"));
            }
        }
        return out;
    }

    private static String enc(String s) {
        try { return java.net.URLEncoder.encode(s, "UTF-8"); } catch (Throwable t) { return ""; }
    }

    /** IPQualityScore's free tier answers "Premium required." for the paid fields — that's not a value. */
    private static String paidField(String s) {
        return (s == null || s.isEmpty() || s.toLowerCase().startsWith("premium")) ? null : s;
    }

    private static String emptyOr(String s, String fallback) {
        return (s == null || s.isEmpty()) ? fallback : s;
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
            String s = sh.runCapture("cat " + HookConstants.FRAMEWORK_HB_PATH + " 2>/dev/null");
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
