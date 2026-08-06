package com.specter.module.ui;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;

/**
 * Live "what does the target grab" viewer. Reads the diagnostics capture ({@link DiagnosticsCmd#LOG_PATH},
 * written by {@link DiagnosticsService}), parses it with {@link TraceParser}, and renders the deduped
 * per-signal rows grouped by COVERAGE — faked / leaked / not-yet-classified — so the user sees the story
 * "this was checked, this got faked, the app still works" rather than a wall of syscalls. Reads that carry
 * no device identity ({@link Coverage.State#NOISE}: fonts, library loads, scheduler bookkeeping) are counted
 * but not listed: they are ~99% of any trace, and showing them made a WORKING spoof look broken.
 * Auto-refreshes every 2s; a manual Refresh, Export and Clear are provided. READ-ONLY: this only observes
 * the capture, it applies nothing — safe for any scoped app (the native companion still guards APPLY).
 */
public final class DiagnosticsActivity extends Activity {
    /** Optional intent extra: the package the caller just monitored, shown as "watching <app>". Absent when
     *  opened from Settings' global "View live trace" (then we show the scoped-target set instead). */
    public static final String EXTRA_PKG = "specter.diag.pkg";
    /** Set true when opened by "Stop monitoring": if the {@code autosave_trace} pref is on, the coverage
     *  report is written to Download/Specter automatically once the capture is parsed — so a forgotten
     *  Export never loses the trace. */
    public static final String EXTRA_FROM_STOP = "specter.diag.from_stop";

    private boolean autoSaveOnStop = false;      // this open should auto-export once (from a stop, pref on)
    private boolean autoSaved = false;           // one-shot latch so the 2s poll can't re-export every tick

    private static final int MAX_ROWS = 400;         // cap distinct signals rendered (parser drops excess)
    private static final long REFRESH_MS = 2000;
    private static final long BLINK_MS = 650;        // live-dot flash period

    private LinearLayout list;                        // rows container (rebuilt each refresh)
    private LinearLayout statRow;                      // the KPI tile row (faked/leaked/unchecked/reads)
    private TextView summary;
    private View liveDot;                             // the flashing-red "capturing" indicator
    private boolean dotOn = true;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean live = true;
    private volatile boolean reading = false;   // one in-flight read at a time (no su-exec pileup)
    private volatile boolean exporting = false; // guards double-taps on Export while su is working
    private volatile boolean resumed = false;   // foreground state — an export finishing must not restart
                                                // the su-polling loop on a backgrounded screen
    private Button exportBtn;                   // held so the export can show progress on the button itself
    private volatile int generation = 0;        // bumped by Clear; a read carrying an older value is dropped
    private volatile List<TraceParser.Row> lastRows = java.util.Collections.emptyList();  // for Export
    private final Runnable blink = new Runnable() {
        @Override public void run() {
            if (liveDot == null) return;
            // Flash while live; hold a steady (dim) dot when paused.
            dotOn = !dotOn;
            // Flash the red dot on/off while live; a steady dim dot when paused.
            int dimRed = (Theme.RED & 0x00FFFFFF) | 0x33000000;   // same hue, ~20% alpha
            liveDot.setBackground(roundRect(live && dotOn ? Theme.RED : (live ? dimRed : Theme.LINE),
                    live ? Theme.RED : Theme.LINE, dp(5)));
            h.postDelayed(this, BLINK_MS);
        }
    };
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            if (!live) return;
            refresh();
            h.postDelayed(this, REFRESH_MS);
        }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(Theme.BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(14);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root);

        // One consistent back control (gold chevron + "Live trace" title), same as every other sub-screen.
        LinearLayout titleRow = Nav.backRow(this, "Live trace", this::finish);
        root.addView(titleRow);

        // Which app's reads are we showing? The capture log is shared by ALL scoped+armed targets, so name the
        // app the caller just monitored, or (from the global entry point) the whole scoped-target set.
        TextView who = new TextView(this);
        who.setTextColor(Theme.DIM);
        who.setTextSize(12);
        who.setPadding(0, dp(3), 0, 0);
        who.setText(subjectLine());
        root.addView(who);

        // Stat tiles (faked / leaked / unchecked / reads) — a scannable KPI row, not a run-on string.
        statRow = new LinearLayout(this);
        statRow.setOrientation(LinearLayout.HORIZONTAL);
        statRow.setPadding(0, dp(10), 0, 0);
        root.addView(statRow);

        summary = new TextView(this);
        summary.setTextColor(Theme.DIM);
        summary.setTextSize(11);
        summary.setPadding(0, dp(6), 0, 0);
        root.addView(summary);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setGravity(Gravity.CENTER_VERTICAL);
        btns.setPadding(0, dp(8), 0, dp(8));
        // The live toggle IS the "recording" affordance: a flashing-red dot + "Live" while capturing, a steady
        // dim dot + "Paused" when stopped. Tapping it pauses/resumes the 2s refresh loop.
        btns.addView(buildLiveToggle());
        Button refreshBtn = flatButton("Refresh");
        refreshBtn.setOnClickListener(v -> refresh());
        btns.addView(refreshBtn);
        exportBtn = flatButton("Export");
        exportBtn.setOnClickListener(v -> exportLog());
        btns.addView(exportBtn);
        // Clear DISCARDS the capture, so it reads as destructive (red) and confirms first — it used to look
        // and behave exactly like Refresh, one tap away from throwing away the session's reads.
        final Button clearBtn = flatButton("Clear");
        clearBtn.setTextColor(Theme.RED);
        clearBtn.setOnClickListener(v -> new android.app.AlertDialog.Builder(this)
                .setTitle("Clear captured reads?")
                .setMessage("Everything recorded so far is discarded. Export first if you want to keep it.")
                .setPositiveButton("Clear", (d, w) -> {
                    clearBtn.setEnabled(false);
                    generation++;   // invalidate any read already in flight against the pre-clear log
                    // Off the UI thread — `su -c : > log` + waitFor() blocks; inline it would ANR if su is slow.
                    new Thread(() -> {
                        clearLog();
                        runOnUiThread(() -> {
                            if (isFinishing() || isDestroyed()) return;
                            clearBtn.setEnabled(true);
                            // Don't force `reading = false` here — an in-flight pre-clear read clears it in
                            // its own finally, and stomping it would allow two concurrent `su -c tail`.
                            // Its result is already discarded by the generation check; the 2s tick (or
                            // Refresh) picks up the cleared log on the next pass.
                            refresh();
                        });
                    }, "specter-diag-clear").start();
                })
                .setNegativeButton("Cancel", null)
                .show());
        btns.addView(clearBtn);
        root.addView(btns);

        // Auto-save toggle: when on, stopping a monitor writes the coverage report to Download/Specter without
        // a manual Export tap. Reads/writes the same pref the stop flow checks. Default on — losing a capture
        // you just took is the worse failure; the raw log is archived on stop regardless.
        final android.content.SharedPreferences prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        autoSaveOnStop = getIntent() != null && getIntent().getBooleanExtra(EXTRA_FROM_STOP, false)
                && prefs.getBoolean("autosave_trace", true);
        final android.widget.CheckBox autosave = new android.widget.CheckBox(this);
        autosave.setText("Auto-save report on stop");
        autosave.setChecked(prefs.getBoolean("autosave_trace", true));
        autosave.setTextColor(Theme.SOFT);
        autosave.setTextSize(Theme.T_CAPTION);
        autosave.setButtonTintList(android.content.res.ColorStateList.valueOf(Theme.GOLD));
        autosave.setPadding(dp(4), 0, 0, dp(4));
        autosave.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean("autosave_trace", on).apply());
        root.addView(autosave);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume();
        // Don't resume the poll mid-export — the export deliberately paused it and will restore it when the
        // su round-trip finishes. Resuming here too would leave two self-rescheduling tick loops running.
        resumed = true;
        if (!exporting) { live = true; h.removeCallbacks(tick); h.post(tick); }
        h.removeCallbacks(blink); h.post(blink);
    }
    @Override protected void onPause() {
        super.onPause(); live = false; resumed = false;
        h.removeCallbacks(tick); h.removeCallbacks(blink);
    }

    /** Who are we watching? The active-monitor package (passed as {@link #EXTRA_PKG}) if present, else the set
     *  of scoped targets — the capture log is shared, so with several targets armed the rows below can mix
     *  reads from all of them. Naming them makes that explicit instead of leaving "which app is this?" open. */
    private String subjectLine() {
        String pkg = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PKG);
        if (pkg != null && !pkg.isEmpty()) return "Watching " + Targets.label(this, pkg);
        java.util.Set<String> targets = Targets.get(getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE));
        if (targets.isEmpty()) return "No target apps yet — add one on the Identity tab.";
        if (targets.size() == 1) return "Watching " + Targets.label(this, targets.iterator().next());
        StringBuilder sb = new StringBuilder("Watching " + targets.size() + " apps: ");
        int i = 0;
        for (String p : targets) { if (i++ > 0) sb.append(", "); sb.append(Targets.label(this, p)); }
        sb.append(" (reads mixed below)");
        return sb.toString();
    }

    /** Read + parse the (root-owned) log OFF the main thread — `su -c cat` + a full-file read would ANR
     *  the UI if run inline in the 2s tick — then hand the parsed rows back to the main thread to render.
     *  A single in-flight read at a time ({@link #reading}) so a slow read can't pile up su processes. */
    private void refresh() {
        if (reading) return;
        reading = true;
        final int gen = generation;   // snapshot: a Clear bumps this and invalidates reads already in flight
        new Thread(() -> {
            final String raw = readLog();
            final List<TraceParser.Row> rows = TraceParser.parse(raw, MAX_ROWS);
            h.post(() -> {
                boolean stale = gen != generation;
                try {
                    // A read that STARTED before a Clear would render pre-clear rows over a cleared log
                    // (codex). Drop its result.
                    if (!stale && !isFinishing() && !isDestroyed()) render(raw, rows);
                } finally { reading = false; }
                // ...and re-read now that we're free, so a Clear that happened mid-read still repaints
                // immediately instead of waiting for the next 2s tick (or forever, if the screen is paused).
                if (stale && !isFinishing() && !isDestroyed()) refresh();
            });
        }, "specter-diag-read").start();
    }

    private void render(String raw, List<TraceParser.Row> rows) {
        lastRows = rows;   // snapshot for Export (a readable coverage report, not the raw log)
        // Auto-save the coverage report exactly once, when opened from a stop with the pref on and there's
        // something to save. The latch stops the 2s poll re-exporting every tick; exportLog() reuses the
        // same write path as the manual button.
        if (autoSaveOnStop && !autoSaved && rows != null && !rows.isEmpty()) {
            autoSaved = true;
            exportLog();
        }
        list.removeAllViews();
        statRow.removeAllViews();
        if (raw == null) {
            summary.setTextSize(12);
            summary.setText("Capture off. Turn on Read logging in Settings, then open a scoped target.");
            return;
        }
        summary.setTextSize(11);
        int hits = 0, spoofed = 0, leaking = 0, noise = 0, unknown = 0;
        for (TraceParser.Row r : rows) {
            hits += r.count;
            switch (Coverage.of(r)) {
                case SPOOFED: spoofed++; break;
                case LEAK: leaking++; break;
                case NOISE: noise++; break;
                default: unknown++; break;
            }
        }
        // The headline answers "is the app seeing the fake device?" — identifiers we control vs identifiers
        // leaking. Non-identifying reads (fonts, libc, the app's own /proc) are the bulk of any trace and are
        // NOT a verdict on the spoof, so they're a muted count, not a scary "256 real".
        statRow.addView(statTile(String.valueOf(spoofed), "faked", Theme.SAGE));
        statRow.addView(statTile(String.valueOf(leaking), "leaked", leaking > 0 ? Theme.RED : Theme.DIM));
        statRow.addView(statTile(String.valueOf(unknown), "unchecked", Theme.GOLD));
        statRow.addView(statTile(String.valueOf(hits), "reads", Theme.INK));
        // "signals", not "reads": these counts are per DISTINCT signal (the parser dedups), while the `reads`
        // tile is the raw hit total. Saying "reads" here would imply the two numbers should reconcile.
        summary.setText(verdict(spoofed, leaking, unknown) + "  ·  " + noise + " harmless signals hidden"
                + (rows.size() >= MAX_ROWS ? "  ·  list capped at " + MAX_ROWS : ""));

        // Order tells the story: what we protected, then what escaped, then what we can't judge.
        // NOISE rows are omitted entirely — showing them is what made a working spoof look broken.
        addCoverageGroup("Faked — the app saw the wrong device", Coverage.State.SPOOFED, Theme.SAGE, rows);
        addCoverageGroup("Leaked — real values got through", Coverage.State.LEAK, Theme.RED, rows);
        addCoverageGroup("Not checked yet", Coverage.State.UNKNOWN, Theme.GOLD, rows);
    }

    /** One plain sentence a non-technical user can act on. Never claims a clean sweep while reads remain
     *  unclassified — "nothing got through" would be an overclaim when we simply couldn't judge N of them. */
    private static String verdict(int spoofed, int leaking, int unknown) {
        if (leaking > 0) return leaking + " real value" + (leaking == 1 ? "" : "s") + " got through";
        if (spoofed == 0 && unknown == 0) return "No device info read yet";
        if (unknown > 0) return "No known leaks · " + unknown + " signal" + (unknown == 1 ? "" : "s") + " we can't judge";
        return "Nothing real got through";
    }

    /** Render one coverage bucket (spoofed / leaking / unclassified). Groups by what the read MEANS rather
     *  than by which syscall fetched it — the syscall was never the question the user is asking. */
    private void addCoverageGroup(String name, Coverage.State state, int accent, List<TraceParser.Row> rows) {
        int n = 0;
        for (TraceParser.Row r : rows) if (Coverage.of(r) == state) n++;
        if (n == 0) return;

        // Group header: colored dot + name + count.
        LinearLayout hdr = new LinearLayout(this);
        hdr.setOrientation(LinearLayout.HORIZONTAL);
        hdr.setGravity(Gravity.CENTER_VERTICAL);
        hdr.setPadding(dp(2), dp(16), 0, dp(6));
        View dot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.setMargins(0, 0, dp(8), 0);
        dot.setLayoutParams(dlp);
        dot.setBackground(roundRect(accent, accent, dp(4)));
        hdr.addView(dot);
        TextView htv = new TextView(this);
        htv.setText(name);
        htv.setTextColor(Theme.INK);
        htv.setTextSize(14);
        htv.setTypeface(htv.getTypeface(), android.graphics.Typeface.BOLD);
        hdr.addView(htv);
        TextView badge = new TextView(this);
        badge.setText(String.valueOf(n));
        badge.setTextColor(Theme.DIM);
        badge.setTextSize(12);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(dp(8), 0, 0, 0);
        badge.setLayoutParams(blp);
        hdr.addView(badge);
        list.addView(hdr);

        String note = groupNote(state);
        if (note != null) {
            TextView n2 = new TextView(this);
            n2.setText(note);
            n2.setTextColor(Theme.DIM);
            n2.setTextSize(11);
            n2.setPadding(dp(2), 0, dp(2), dp(6));
            list.addView(n2);
        }

        for (TraceParser.Row r : rows) {
            if (Coverage.of(r) != state) continue;
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rowBg(accent));
            row.setPadding(dp(12), dp(9), dp(12), dp(9));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, 0, 0, dp(3));
            row.setLayoutParams(lp);

            TextView tgt = new TextView(this);
            tgt.setText(r.target);
            tgt.setTextColor(Theme.INK);
            tgt.setTextSize(13);
            tgt.setTypeface(android.graphics.Typeface.MONOSPACE);   // paths/keys read far better monospace
            tgt.setMaxLines(1); tgt.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);   // never wrap a value
            tgt.setOnLongClickListener(v -> { android.widget.Toast.makeText(this, r.target,
                    android.widget.Toast.LENGTH_SHORT).show(); return true; });   // full path on long-press (escape hatch)
            tgt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tgt);

            // The group header already states the coverage, so the per-row badge names the READ KIND instead
            // (prop vs file vs stat) — which the old kind-grouping used to convey.
            TextView kb = new TextView(this);
            kb.setText(kindLabel(r.kind));
            kb.setTextColor(Theme.DIM);
            kb.setTextSize(10);
            kb.setPadding(dp(7), dp(1), dp(7), dp(2));
            kb.setBackground(roundRect(Theme.BG2, Theme.LINE, dp(8)));
            LinearLayout.LayoutParams kbl = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            kbl.setMargins(dp(8), 0, 0, 0);
            kb.setLayoutParams(kbl);
            row.addView(kb);

            // Count pill — only when read more than once (a single read needs no ×1 clutter).
            if (r.count > 1) {
                TextView cnt = new TextView(this);
                cnt.setText("×" + r.count);
                cnt.setTextColor(Theme.SOFT);
                cnt.setTextSize(11);
                cnt.setPadding(dp(7), dp(1), dp(7), dp(2));
                cnt.setBackground(roundRect(Theme.BG2, Theme.LINE, dp(8)));
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                clp.setMargins(dp(8), 0, 0, 0);
                cnt.setLayoutParams(clp);
                row.addView(cnt);
            }
            list.addView(row);
        }
    }

    /** One plain line telling the user what a group MEANS and whether to care. Null = self-evident. */
    private static String groupNote(Coverage.State state) {
        switch (state) {
            case LEAK: return "These identify the real phone. Worth spoofing.";
            case UNKNOWN: return "Might identify the phone. Report any that look device-specific.";
            default: return null;
        }
    }

    private static String kindLabel(TraceParser.Kind kind) {
        switch (kind) {
            case PROP: return "prop";
            case FILE: return "file";
            case STAT: return "stat";
            default:   return "other";
        }
    }

    /** Rounded filled rect with a stroke — used for pills/badges/dots. */
    private android.graphics.drawable.GradientDrawable roundRect(int fill, int stroke, int radiusPx) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radiusPx);
        g.setStroke(dp(1), stroke);
        return g;
    }

    /** A row background: rounded card fill with a colored left accent rule (a thick left border in accent). */
    private android.graphics.drawable.Drawable rowBg(int accent) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Theme.CARD);
        g.setCornerRadius(dp(6));
        // left accent rule via a layer: draw the accent, inset the card to leave a 3dp left stripe.
        android.graphics.drawable.GradientDrawable stripe = new android.graphics.drawable.GradientDrawable();
        stripe.setColor(accent);
        stripe.setCornerRadius(dp(6));
        android.graphics.drawable.LayerDrawable layer =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{stripe, g});
        layer.setLayerInset(1, dp(3), 0, 0, 0);   // card inset 3dp from left -> accent stripe shows
        return layer;
    }

    /** {@code su -c cat} the log (dir is root-owned). Returns null if the file is absent / su denied.
     *  We only tail the last slice of the file — a capture rotates at 8 MB, and the parser dedups anyway,
     *  so catting a multi-MB file every 2s is wasted I/O. stderr is drained on a side thread so a large
     *  error output can't fill the pipe and deadlock the read. */
    private String readLog() {
        Process p = null;
        try {
            // tail -c keeps the read bounded regardless of log size; the parser dedups the window.
            p = Runtime.getRuntime().exec(new String[]{"su", "-c", "tail -c 1048576 " + DiagnosticsCmd.LOG_PATH});
            drain(p.getErrorStream());
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line).append('\n');
            }
            int code = p.waitFor();
            if (code != 0 && sb.length() == 0) return null;   // cat failed AND nothing read -> not running
            return sb.toString();
        } catch (Throwable t) { return null; }
        finally { if (p != null) p.destroy(); }
    }

    /** Drain a stream to /dev/null on a daemon thread so a full stderr pipe can't block the stdout read. */
    private static void drain(final java.io.InputStream in) {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[4096];
            try { while (in.read(buf) != -1) { /* discard */ } } catch (Throwable ignored) {}
        });
        t.setDaemon(true);
        t.start();
    }

    private void clearLog() {
        try { Runtime.getRuntime().exec(new String[]{"su", "-c", ": > " + DiagnosticsCmd.LOG_PATH}).waitFor(); }
        catch (Throwable ignored) {}
    }

    /** Export a READABLE coverage report (the parsed signals grouped with their spoofed/real status +
     *  summary) to the shared Download/Specter folder — the SAME place every other Specter export lands —
     *  far more useful than the raw 90k-line diag.log. Built from the in-memory parsed rows; staged in the
     *  app's own dir then su-copied out (Download isn't app-writable). */
    private void exportLog() {
        if (exporting) return;              // a second tap while su is still working is a no-op, not a queue
        exporting = true;
        // Acknowledge the tap IMMEDIATELY. The su round-trip below can take a second or more (Magisk may
        // have to spawn a fresh root shell), and with no feedback the button reads as laggy/broken.
        exportBtn.setEnabled(false);   // grey out + Toast for feedback; never swap the label (that resizes the button)
        android.widget.Toast.makeText(this, "Writing coverage report…", android.widget.Toast.LENGTH_SHORT).show();
        // Pause the 2s poll for the duration: its own `su -c tail` competes with ours for the su daemon,
        // which is what makes the export feel slow.
        final boolean wasLive = live;
        live = false;
        h.removeCallbacks(tick);

        final List<TraceParser.Row> snapshot = lastRows;
        final String name = "specter-coverage-" + System.currentTimeMillis() + ".txt";
        final String dir = com.specter.module.gen.AppDataVault.EXPORT_DIR;   // /sdcard/Download/Specter
        final String dest = dir + "/" + name;
        new Thread(() -> {
            boolean ok = false;
            java.io.File staged = new java.io.File(getFilesDir(), name);
            try {
                // Build the report HERE, not on the click: it walks every row four times (once per group).
                byte[] bytes = DiagReport.build(snapshot).getBytes("UTF-8");
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(staged)) {
                    fos.write(bytes);
                }
                Process p = Runtime.getRuntime().exec(new String[]{"su", "-c",
                        "mkdir -p '" + dir + "' && cp '" + staged.getAbsolutePath() + "' '" + dest + "' && chmod 644 '" + dest + "'"});
                drain(p.getErrorStream());
                drain(p.getInputStream());
                ok = p.waitFor() == 0;
            } catch (Throwable ignored) {}
            //noinspection ResultOfMethodCallIgnored
            staged.delete();
            final boolean done = ok;
            h.post(() -> {
                exporting = false;
                if (isFinishing() || isDestroyed()) return;   // don't touch ANY UI on a dead activity
                exportBtn.setText("Export");
                exportBtn.setEnabled(true);
                android.widget.Toast.makeText(this,
                        done ? "Saved to Download/Specter" : "Export failed (grant root?)",
                        android.widget.Toast.LENGTH_LONG).show();
                // Only resume polling if the screen is STILL in the foreground — the user may have left
                // while su was working, and onPause deliberately stopped the loop (codex).
                if (wasLive && resumed) { live = true; h.removeCallbacks(tick); h.post(tick); }
            });
        }, "specter-diag-export").start();
    }


    private TextView liveLabel;   // "Live" / "Paused" text inside the live toggle

    /** The live toggle: a pill holding a flashing-red dot + "Live"/"Paused". The dot flashes while capturing
     *  (this is the recording indicator), holds steady-dim when paused. Tapping pauses/resumes the 2s refresh. */
    private View buildLiveToggle() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER);
        // Same fill/edge/radius/ripple + 44dp height as the other controls in this row — it read as a status
        // badge before, so users didn't know it was tappable (codex).
        pill.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Theme.BTN_HI),
                roundRect(Theme.BTN, Theme.BTN_EDGE, dp(Theme.R_CTRL)), null));
        pill.setMinimumHeight(dp(44));
        pill.setPadding(dp(Theme.S4), dp(Theme.S2), dp(Theme.S4), dp(Theme.S2));
        pill.setClickable(true);
        pill.setFocusable(true);
        pill.setContentDescription("Pause live trace");
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, 0, dp(Theme.S2), 0);
        pill.setLayoutParams(plp);

        liveDot = new View(this);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(9), dp(9));
        dlp.setMargins(0, 0, dp(7), 0);
        liveDot.setLayoutParams(dlp);
        liveDot.setBackground(roundRect(Theme.RED, Theme.RED, dp(5)));
        pill.addView(liveDot);

        liveLabel = new TextView(this);
        liveLabel.setText("Live");
        liveLabel.setTextColor(Theme.INK);
        liveLabel.setTextSize(Theme.T_LABEL);
        pill.addView(liveLabel);

        pill.setOnClickListener(v -> {
            live = !live;
            liveLabel.setText(live ? "Live" : "Paused");
            // Describe the ACTION the tap performs, not the current state — a screen reader announcing
            // "Live" gives no hint that activating it pauses.
            v.setContentDescription(live ? "Pause live trace" : "Resume live trace");
            // Clear any queued tick before re-arming, or a fast toggle stacks a second self-rescheduling loop.
            h.removeCallbacks(tick);
            if (live) h.post(tick);
        });
        return pill;
    }

    /** A compact KPI tile: big number over a small caption, on a rounded card — reads far cleaner than the
     *  old run-on stat string. Tiles share the row equally (weight 1). */
    private View statTile(String number, String caption, int numColor) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(roundRect(Theme.CARD, Theme.LINE, dp(8)));
        t.setPadding(dp(6), dp(8), dp(6), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(0, 0, dp(6), 0);
        t.setLayoutParams(lp);
        TextView num = new TextView(this);
        num.setText(number);
        num.setTextColor(numColor);
        num.setTextSize(20);
        num.setTypeface(num.getTypeface(), android.graphics.Typeface.BOLD);
        t.addView(num);
        TextView cap = new TextView(this);
        cap.setText(caption);
        cap.setTextColor(Theme.DIM);
        cap.setTextSize(11);
        t.addView(cap);
        return t;
    }

    /** A control built from the Theme tokens the rest of the app uses (type scale, spacing scale, control
     *  radius, button fill+edge) with a ripple and a real 44dp touch target — it used to hardcode 13sp/6dp
     *  and a square flat color, which is why this screen's controls didn't match the rest of the app. */
    private Button flatButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Theme.INK);
        btn.setTextSize(Theme.T_LABEL);
        android.graphics.drawable.GradientDrawable bg = roundRect(Theme.BTN, Theme.BTN_EDGE, dp(Theme.R_CTRL));
        btn.setBackground(new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(Theme.BTN_HI), bg, null));
        btn.setStateListAnimator(null);
        btn.setMinWidth(0);
        btn.setMinimumWidth(0);
        btn.setMinHeight(dp(44));           // accessible touch target (was effectively ~28dp)
        btn.setMinimumHeight(dp(44));
        btn.setPadding(dp(Theme.S4), dp(Theme.S2), dp(Theme.S4), dp(Theme.S2));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(Theme.S2), 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
