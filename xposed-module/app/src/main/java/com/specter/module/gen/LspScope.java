package com.specter.module.gen;

import android.content.Context;

import java.io.File;

/**
 * Writes Specter's own LSPosed scope rows from inside the app, so a virgin-phone setup never needs the PC
 * step ({@code scripts/scope_probe.py}). Adding a target app to LSPosed scope is the ONE piece of the install
 * that was PC-only; this closes it.
 *
 * <p>LSPosed keeps scope in {@code /data/adb/lspd/config/modules_config.db} (SQLite), root-owned in an SELinux
 * domain the app can't read directly — and there's no {@code sqlite3} binary on the device. So we use the exact
 * route {@link com.specter.module.ui.HealthCheck} already uses to READ that DB: copy it into the app's own
 * files dir via {@code su} (where the app's uid can open it), edit it with Android's built-in
 * {@link android.database.sqlite.SQLiteDatabase}, then copy it back via {@code su}. LSPosed re-reads scope on
 * boot, so the caller MUST reboot for the new scope to take effect.
 *
 * <p>Scope is scoped to Specter's own module row only ({@code mid} of {@code module_pkg_name='com.specter'}) —
 * it NEVER touches any other module's scope (e.g. a co-installed module's real targets). An {@code INSERT OR
 * IGNORE} means re-running is a no-op for apps already scoped. Only ever ADDS rows; never removes, so it can't
 * un-scope an app the user set by hand.
 *
 * <p>The SQL builders are pure/testable; {@link #addTargets} does the su + SQLite side effects.
 */
public final class LspScope {
    private LspScope() {}

    /** Must match {@link com.specter.module.ui.HealthCheck}'s LSPD_DB. */
    static final String LSPD_DB = "/data/adb/lspd/config/modules_config.db";
    static final String SPECTER_PKG = "com.specter";

    public static final class ScopeException extends RuntimeException {
        public ScopeException(String m) { super(m); }
        public ScopeException(String m, Throwable t) { super(m, t); }
    }

    /** Result of a scope write: how many rows were newly added (already-scoped apps don't count). */
    public static final class Result {
        public final int added;
        public final int alreadyScoped;
        Result(int added, int alreadyScoped) { this.added = added; this.alreadyScoped = alreadyScoped; }
    }

    /** The INSERT for one scope row, scoped to Specter's module via a sub-select on modules. OR IGNORE so a
     *  re-run (or an app already scoped) is a silent no-op, and a bad/absent module row inserts nothing rather
     *  than a dangling mid. Parameterized in the actual call — this literal form is for tests/inspection. */
    static String insertSql() {
        return "INSERT OR IGNORE INTO scope (mid, app_pkg_name, user_id) "
                + "SELECT mid, ?, 0 FROM modules WHERE module_pkg_name='" + SPECTER_PKG + "'";
    }

    /**
     * Add each package in {@code pkgs} to Specter's LSPosed scope (idempotent). Returns how many were newly
     * added. Throws {@link ScopeException} if the DB can't be copied/opened/written (su denied, module row
     * absent). A reboot is required afterward for LSPosed to load the new scope.
     */
    public static Result addTargets(Context ctx, java.util.Collection<String> pkgs) {
        return addTargets(ctx, pkgs, new RootWriter.SuShell());
    }

    /** The LSPosed System-Framework scope keys (dot-less, so validPkg rejects them — allowed explicitly). */
    public static boolean isFrameworkKey(String p) { return "android".equals(p) || "system".equals(p); }

    public static Result addTargets(Context ctx, java.util.Collection<String> pkgs, RootWriter.Shell shell) {
        // Validate every pkg up front — the same grammar RootWriter enforces at its su boundary. A bad name
        // never reaches SQLite (it's bound, not interpolated, but validate anyway to fail loud + early).
        // EXCEPTION: the two framework scope keys "android"/"system" are dot-less (validPkg requires a dot),
        // but they're the LSPosed keys for the System Framework gate — allow exactly those two so "Set up
        // everything" can scope the raw-binder app-hiding gate, not just the user apps.
        for (String p : pkgs) {
            if (isFrameworkKey(p)) continue;
            if (!RootWriter.validPkg(p)) throw new ScopeException("invalid package name: " + p);
        }

        File local = new File(ctx.getFilesDir(), "lspd_rw.db");
        File localWal = new File(ctx.getFilesDir(), "lspd_rw.db-wal");
        File localShm = new File(ctx.getFilesDir(), "lspd_rw.db-shm");
        String lp = local.getAbsolutePath();
        int uid = ctx.getApplicationInfo().uid;

        // Drop any stale working copies from a prior interrupted run FIRST — else a failed source-copy below
        // could leave an old lspd_rw.db in place that passes the presence check and gets edited as if current
        // (a stale-config edit). We start from a clean slate every time.
        for (File f : new File[]{local, localWal, localShm}) {
            try { //noinspection ResultOfMethodCallIgnored
                f.delete(); } catch (Throwable ignored) {}
        }

        // 1. Copy the DB **and its -wal/-shm sidecars** into the app dir. This is the crucial WAL correctness
        //    step: LSPosed's live DB can have a large uncommitted -wal (checkpointed lazily), so the plain .db
        //    alone is a STALE snapshot — e.g. a 'system'/'android' framework-gate scope row added via the
        //    LSPosed UI may live only in the -wal. Copying just the .db (as HealthCheck's read does) would read
        //    stale AND, worse on write-back, LSPosed would later replay the stale -wal OVER our edit, erasing it
        //    or corrupting the file. So we copy all three; opening the .db below auto-replays the -wal to give
        //    the TRUE current state, and we checkpoint + drop the sidecars before writing back.
        //    The MAIN .db copy's exit status is CHECKED (shell.run returns the code — runCapture would discard
        //    it and mask a denied su); the sidecar copies are best-effort ('|| true') since a freshly-
        //    checkpointed DB may have none.
        int rc;
        try {
            rc = shell.run("cp -f " + LSPD_DB + " '" + lp + "'"
                    + " && chmod 660 '" + lp + "' && chown " + uid + ":" + uid + " '" + lp + "'"
                    + " && { cp -f " + LSPD_DB + "-wal '" + localWal.getAbsolutePath() + "' 2>/dev/null && chmod 660 '" + localWal.getAbsolutePath() + "' && chown " + uid + ":" + uid + " '" + localWal.getAbsolutePath() + "' || true; }"
                    + " && { cp -f " + LSPD_DB + "-shm '" + localShm.getAbsolutePath() + "' 2>/dev/null && chmod 660 '" + localShm.getAbsolutePath() + "' && chown " + uid + ":" + uid + " '" + localShm.getAbsolutePath() + "' || true; }",
                    "");
        } catch (Exception e) {
            throw new ScopeException("couldn't copy the LSPosed DB (root denied?)", e);
        }
        if (rc != 0) {
            throw new ScopeException("couldn't copy the LSPosed DB (su exited " + rc + " — root denied?)");
        }
        if (!local.exists() || local.length() == 0) {
            throw new ScopeException("LSPosed DB copy is missing/empty — is Specter enabled in LSPosed yet?");
        }

        // 2. Edit it with Android's own SQLite (open R/W). Opening with the -wal present replays it, so reads see
        //    the true current state. Verify our module row exists first (else the INSERT ... SELECT would
        //    silently add nothing and we'd report a false success). After editing, wal_checkpoint(TRUNCATE)
        //    folds our insert + all prior WAL pages into the .db and empties the -wal, so the .db is fully
        //    self-contained for the copy-back.
        int added = 0, already = 0;
        try {   // outer try/finally: the working copies are a full copy of the root-owned LSPosed config, so
                // they must be wiped on EVERY exit path (any exception below), not just on success.
        android.database.sqlite.SQLiteDatabase db = null;
        try {
            db = android.database.sqlite.SQLiteDatabase.openDatabase(
                    lp, null, android.database.sqlite.SQLiteDatabase.OPEN_READWRITE);
            Long mid = queryLong(db, "SELECT mid FROM modules WHERE module_pkg_name='" + SPECTER_PKG + "'");
            if (mid == null) {
                throw new ScopeException("Specter isn't a registered LSPosed module yet — enable it in LSPosed first.");
            }
            // The module row can exist but be DISABLED — writing scope then would silently yield no active hooks
            // after reboot (false success). Require enabled=1 so setup surfaces "enable Specter" instead.
            Long enabled = queryLong(db, "SELECT enabled FROM modules WHERE mid=" + mid);
            if (enabled == null || enabled != 1L) {
                throw new ScopeException("Specter is registered but NOT enabled in LSPosed — toggle it on in "
                        + "LSPosed → Modules, then run setup again.");
            }
            for (String pkg : pkgs) {
                Long before = queryLong(db,
                        "SELECT COUNT(*) FROM scope WHERE mid=" + mid + " AND app_pkg_name='" + pkg + "'");
                if (before != null && before > 0) { already++; continue; }
                db.execSQL(insertSql(), new Object[]{pkg});
                added++;
            }
            // Fold everything into the .db so the copy-back is a single self-contained file (empties the -wal).
            try (android.database.Cursor ignored = db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)) {
                ignored.moveToFirst();
            } catch (Throwable ignoredT) { /* delete-mode DBs have no WAL to checkpoint — fine */ }
        } catch (ScopeException se) {
            throw se;
        } catch (Throwable t) {
            throw new ScopeException("couldn't edit the LSPosed scope DB", t);
        } finally {
            if (db != null) try { db.close(); } catch (Throwable ignored) {}
        }

        // 3. Copy the edited (checkpointed) .db back with LSPosed's expected owner/perms (root:root 660), and
        //    DELETE the live -wal/-shm so LSPosed can't replay a now-stale journal over our write. We checkpointed
        //    every prior WAL page into the .db in step 2, so nothing is lost by dropping them. Only when we
        //    actually changed something — a pure no-op run leaves the live DB untouched.
        if (added > 0) {
            // ATOMIC copy-back (same discipline as RootWriter.buildShellCommand): stage into a same-dir .tmp,
            // set owner/perms on it, then `mv -f` over the live file — a same-directory rename is atomic, so a
            // killed su / read-at-that-instant sees either the OLD complete DB or the NEW complete one, never a
            // truncated file. A plain `cp -f` over the live path opens+truncates+streams (toybox), which could
            // leave EVERY LSPosed module reading a corrupt scope DB. Drop the -wal/-shm only after the swap
            // succeeds (we already checkpointed into the .db, so nothing is lost), else a stale journal replays.
            String tmp = LSPD_DB + ".specter.tmp";
            int wrc;
            try {
                wrc = shell.run("cp -f '" + lp + "' " + tmp
                        + " && chown 0:0 " + tmp + " && chmod 660 " + tmp
                        + " && mv -f " + tmp + " " + LSPD_DB
                        + " && rm -f " + LSPD_DB + "-wal " + LSPD_DB + "-shm"
                        + " || { rm -f " + tmp + "; exit 1; }", "");
            } catch (Exception e) {
                throw new ScopeException("couldn't write the LSPosed DB back (root denied?)", e);
            }
            if (wrc != 0) {   // exit code CHECKED — runCapture would swallow a failed copy-back as success
                throw new ScopeException("writing the LSPosed DB back failed (su exited " + wrc + ")");
            }
        }
        } finally {
            // wipe our working copies on every path — a full copy of the root-owned LSPosed config, never left behind
            for (File f : new File[]{local, localWal, localShm}) {
                try { //noinspection ResultOfMethodCallIgnored
                    f.delete(); } catch (Throwable ignored) {}
            }
        }

        return new Result(added, already);
    }

    private static Long queryLong(android.database.sqlite.SQLiteDatabase db, String sql) {
        try (android.database.Cursor c = db.rawQuery(sql, null)) {
            if (c.moveToFirst() && !c.isNull(0)) return c.getLong(0);
        } catch (Throwable ignored) {}
        return null;
    }
}
