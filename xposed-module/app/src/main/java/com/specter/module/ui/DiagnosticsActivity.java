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
 * per-signal rows grouped into Props / Files / Stat/access — so the user sees, as they use a scoped
 * target, exactly which device signals it read and how often. Auto-refreshes every 2s; a manual Refresh
 * and Clear are provided. READ-ONLY: this only observes the capture, it applies nothing — safe for any
 * scoped app (the native companion still guards APPLY).
 */
public final class DiagnosticsActivity extends Activity {
    /** Optional intent extra: the package the caller just monitored, shown as "watching <app>". Absent when
     *  opened from Settings' global "View live trace" (then we show the scoped-target set instead). */
    public static final String EXTRA_PKG = "specter.diag.pkg";

    private static final int MAX_ROWS = 400;         // cap distinct signals rendered (parser drops excess)
    private static final long REFRESH_MS = 2000;
    private static final long BLINK_MS = 650;        // live-dot flash period

    private LinearLayout list;                        // rows container (rebuilt each refresh)
    private LinearLayout statRow;                      // the KPI tile row (signals/spoofed/real/reads)
    private TextView summary;
    private View liveDot;                             // the flashing-red "capturing" indicator
    private boolean dotOn = true;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean live = true;
    private volatile boolean reading = false;   // one in-flight read at a time (no su-exec pileup)
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

        // Stat tiles (signals / spoofed / real / reads) — a scannable KPI row, not a run-on string.
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
        Button exportBtn = flatButton("Export");
        exportBtn.setOnClickListener(v -> exportLog());
        btns.addView(exportBtn);
        Button clearBtn = flatButton("Clear");
        // Clear off the UI thread — `su -c : > log` + waitFor() blocks; inline it would ANR if su is slow.
        clearBtn.setOnClickListener(v -> new Thread(() -> {
            clearLog();
            runOnUiThread(this::refresh);
        }, "specter-diag-clear").start());
        btns.addView(clearBtn);
        root.addView(btns);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        setContentView(scroll);
    }

    @Override protected void onResume() {
        super.onResume(); live = true;
        h.removeCallbacks(tick); h.post(tick);
        h.removeCallbacks(blink); h.post(blink);
    }
    @Override protected void onPause() {
        super.onPause(); live = false;
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
        sb.append(" — reads from all of them are mixed below.");
        return sb.toString();
    }

    /** Read + parse the (root-owned) log OFF the main thread — `su -c cat` + a full-file read would ANR
     *  the UI if run inline in the 2s tick — then hand the parsed rows back to the main thread to render.
     *  A single in-flight read at a time ({@link #reading}) so a slow read can't pile up su processes. */
    private void refresh() {
        if (reading) return;
        reading = true;
        new Thread(() -> {
            final String raw = readLog();
            final List<TraceParser.Row> rows = TraceParser.parse(raw, MAX_ROWS);
            h.post(() -> { try { render(raw, rows); } finally { reading = false; } });
        }, "specter-diag-read").start();
    }

    private void render(String raw, List<TraceParser.Row> rows) {
        lastRows = rows;   // snapshot for Export (a readable coverage report, not the raw log)
        list.removeAllViews();
        statRow.removeAllViews();
        if (raw == null) {
            summary.setTextSize(12);
            summary.setText("Capture not running. Turn on “Read logging” in Settings, then APPLY to a "
                    + "scoped target and open it.");
            return;
        }
        summary.setTextSize(11);
        int props = 0, files = 0, stat = 0, hits = 0, spoofed = 0, real = 0;
        for (TraceParser.Row r : rows) {
            hits += r.count;
            if (r.kind == TraceParser.Kind.PROP) props++;
            else if (r.kind == TraceParser.Kind.FILE) files++;
            else if (r.kind == TraceParser.Kind.STAT) stat++;
            Coverage.State c = Coverage.of(r.verb, r.target);
            if (c == Coverage.State.SPOOFED) spoofed++;
            else if (c == Coverage.State.REAL) real++;
        }
        statRow.addView(statTile(String.valueOf(rows.size()), "signals", Theme.INK));
        statRow.addView(statTile(String.valueOf(spoofed), "spoofed", Theme.SAGE));
        statRow.addView(statTile(String.valueOf(real), "real", Theme.DIM));
        statRow.addView(statTile(String.valueOf(hits), "reads", Theme.GOLD));
        summary.setText(props + " props · " + files + " files · " + stat + " stat"
                + (rows.size() >= MAX_ROWS ? "  ·  list capped at " + MAX_ROWS : ""));

        addGroup("Properties", TraceParser.Kind.PROP, rows);
        addGroup("Files", TraceParser.Kind.FILE, rows);
        addGroup("Stat / access", TraceParser.Kind.STAT, rows);
        addGroup("Other", TraceParser.Kind.OTHER, rows);
    }

    /** A distinct accent color per kind so the eye can group reads at a glance (a left rule on each row). */
    private static int accentFor(TraceParser.Kind kind) {
        switch (kind) {
            case PROP: return Theme.GOLD;
            case FILE: return Theme.BLUE;
            case STAT: return Theme.SAGE;
            default:   return Theme.DIM;
        }
    }

    private void addGroup(String name, TraceParser.Kind kind, List<TraceParser.Row> rows) {
        int n = 0;
        for (TraceParser.Row r : rows) if (r.kind == kind) n++;
        if (n == 0) return;
        final int accent = accentFor(kind);

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

        for (TraceParser.Row r : rows) {
            if (r.kind != kind) continue;
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
            tgt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(tgt);

            // Coverage badge: is this signal SPOOFED by Specter, left REAL (non-identifying), or UNKNOWN?
            // (UNKNOWN gets no badge — never over-claim.) This is the flagship "what's protected" readout.
            Coverage.State cov = Coverage.of(r.verb, r.target);
            if (cov != Coverage.State.UNKNOWN) {
                TextView cb = new TextView(this);
                boolean spoofed = cov == Coverage.State.SPOOFED;
                cb.setText(spoofed ? "spoofed" : "real");
                cb.setTextColor(spoofed ? Theme.ON_GOLD : Theme.DIM);
                cb.setTextSize(10);
                cb.setPadding(dp(7), dp(1), dp(7), dp(2));
                cb.setBackground(roundRect(spoofed ? Theme.SAGE : Theme.BG2, spoofed ? Theme.SAGE : Theme.LINE, dp(8)));
                LinearLayout.LayoutParams cbl = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                cbl.setMargins(dp(8), 0, 0, 0);
                cb.setLayoutParams(cbl);
                row.addView(cb);
            }

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
        final String report = DiagReport.build(lastRows);
        final String name = "specter-coverage-" + System.currentTimeMillis() + ".txt";
        final String dir = com.specter.module.gen.AppDataVault.EXPORT_DIR;   // /sdcard/Download/Specter
        final String dest = dir + "/" + name;
        new Thread(() -> {
            boolean ok = false;
            java.io.File staged = new java.io.File(getFilesDir(), name);
            try {
                try (java.io.FileOutputStream fos = new java.io.FileOutputStream(staged)) {
                    fos.write(report.getBytes("UTF-8"));
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
            h.post(() -> android.widget.Toast.makeText(this,
                    done ? "Coverage report -> " + dest : "Export failed (grant root?)",
                    android.widget.Toast.LENGTH_LONG).show());
        }, "specter-diag-export").start();
    }


    private TextView liveLabel;   // "Live" / "Paused" text inside the live toggle

    /** The live toggle: a pill holding a flashing-red dot + "Live"/"Paused". The dot flashes while capturing
     *  (this is the recording indicator), holds steady-dim when paused. Tapping pauses/resumes the 2s refresh. */
    private View buildLiveToggle() {
        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setBackgroundColor(Theme.BTN);
        pill.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.setMargins(0, 0, dp(8), 0);
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
        liveLabel.setTextSize(13);
        pill.addView(liveLabel);

        pill.setOnClickListener(v -> {
            live = !live;
            liveLabel.setText(live ? "Live" : "Paused");
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

    private Button flatButton(String text) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(Theme.INK);
        btn.setTextSize(13);
        btn.setBackgroundColor(Theme.BTN);
        btn.setMinWidth(0);
        btn.setMinHeight(0);
        btn.setMinimumWidth(0);
        btn.setMinimumHeight(0);
        btn.setPadding(dp(14), dp(6), dp(14), dp(6));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, dp(8), 0);
        btn.setLayoutParams(lp);
        return btn;
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density + 0.5f); }
}
