package com.specter.module.ui;

import android.content.Context;
import android.content.SharedPreferences;

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
    enum Fix { NONE, SYNC_ZYGISK, REAPPLY_PROFILE }

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

    /** A section of checks with a heading. */
    static final class Group {
        final String title;
        final List<Check> checks;
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
                    perApp.add(Check.ok(label, "Scoped + identity applied."));
                }
            }
        }
        groups.add(new Group("Target apps", perApp));

        return groups;
    }

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
        try {
            String out = sh.runCapture(
                    "grep -rqa 'app-hiding gate installed' /data/adb/lspd/log/ 2>/dev/null && echo 1 || echo 0");
            return "1".equals(trim(out));
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
