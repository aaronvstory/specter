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
    private static final int MAX_ROWS = 400;         // cap distinct signals rendered (parser drops excess)
    private static final long REFRESH_MS = 2000;

    private LinearLayout list;                        // rows container (rebuilt each refresh)
    private TextView summary;
    private final Handler h = new Handler(Looper.getMainLooper());
    private volatile boolean live = true;
    private volatile boolean reading = false;   // one in-flight read at a time (no su-exec pileup)
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

        TextView title = new TextView(this);
        title.setText("Live trace — what the target reads");
        title.setTextColor(Theme.INK);
        title.setTextSize(18);
        title.setPadding(0, 0, 0, dp(4));
        root.addView(title);

        summary = new TextView(this);
        summary.setTextColor(Theme.DIM);
        summary.setTextSize(12);
        root.addView(summary);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(0, dp(8), 0, dp(8));
        final Button liveBtn = flatButton("Live ●");
        liveBtn.setOnClickListener(v -> {
            live = !live;
            liveBtn.setText(live ? "Live ●" : "Paused");
            // Always clear any queued tick before re-arming, or a fast Pause→Live toggle stacks a second
            // self-rescheduling loop (doubling su traffic each time). One loop, always.
            h.removeCallbacks(tick);
            if (live) h.post(tick);
        });
        btns.addView(liveBtn);
        Button refreshBtn = flatButton("Refresh");
        refreshBtn.setOnClickListener(v -> refresh());
        btns.addView(refreshBtn);
        Button clearBtn = flatButton("Clear log");
        clearBtn.setOnClickListener(v -> { clearLog(); refresh(); });
        btns.addView(clearBtn);
        root.addView(btns);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        root.addView(list);

        setContentView(scroll);
    }

    @Override protected void onResume() { super.onResume(); live = true; h.removeCallbacks(tick); h.post(tick); }
    @Override protected void onPause() { super.onPause(); live = false; h.removeCallbacks(tick); }

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
        list.removeAllViews();
        if (raw == null) {
            summary.setText("Capture not running. Enable “Diagnostics logging” in Settings, then APPLY to a "
                    + "scoped target and open it.");
            return;
        }
        int props = 0, files = 0, stat = 0, hits = 0;
        for (TraceParser.Row r : rows) {
            hits += r.count;
            if (r.kind == TraceParser.Kind.PROP) props++;
            else if (r.kind == TraceParser.Kind.FILE) files++;
            else if (r.kind == TraceParser.Kind.STAT) stat++;
        }
        summary.setText(rows.size() + " distinct signals · " + hits + " reads   ("
                + props + " props, " + files + " files, " + stat + " stat/access)"
                + (rows.size() >= MAX_ROWS ? "  — capped" : ""));

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
