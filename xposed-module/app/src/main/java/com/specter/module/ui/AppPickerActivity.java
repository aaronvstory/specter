package com.specter.module.ui;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Target-app picker (GeerGit parity: "Target Apps", "Show system apps", multi-select, Select All).
 * Lists installed apps via PackageManager, splits system/user by FLAG_SYSTEM, persists the checked
 * set via {@link Targets}. Warns on system/income packages before adding them.
 */
public class AppPickerActivity extends Activity {

    private SharedPreferences prefs;
    private PackageManager pm;
    private final List<Row> rows = new ArrayList<>();
    private final Set<String> selected = new TreeSet<>();
    private LinearLayout list;
    private boolean showSystem = false;
    private String query = "";

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    static final class Row {
        final String pkg, label; final boolean system; final Drawable icon;
        Row(String pkg, String label, boolean system, Drawable icon) {
            this.pkg = pkg; this.label = label; this.system = system; this.icon = icon;
        }
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        pm = getPackageManager();
        selected.addAll(Targets.get(prefs));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);

        // Title row with a Back control (this is a sub-screen; give an explicit way out).
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        titleRow.setPadding(dp(10), dp(12), dp(16), dp(6));
        Button back = new Button(this);
        back.setText("‹ Back");
        back.setAllCaps(false);
        back.setTextColor(Theme.INK);
        back.setMinWidth(0); back.setMinHeight(0); back.setMinimumWidth(0); back.setMinimumHeight(0);
        back.setPadding(dp(14), dp(7), dp(14), dp(7)); back.setTextSize(14); back.setStateListAnimator(null);
        back.setBackground(pill(Theme.CARD2, Theme.BTN_EDGE));
        back.setOnClickListener(v -> finish());
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, 0, dp(12), 0);
        back.setLayoutParams(blp);
        TextView title = new TextView(this);
        title.setText("Target Apps");
        title.setTextColor(Theme.GOLD);
        title.setTextSize(20);
        titleRow.addView(back);
        titleRow.addView(title);
        root.addView(titleRow);

        // search
        EditText search = new EditText(this);
        search.setHint("Search apps…");
        search.setTextColor(Theme.INK);
        search.setHintTextColor(Theme.DIM);
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(dp(12), 0, dp(12), dp(4));
        search.setLayoutParams(slp);
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b2, int c) {}
            public void onTextChanged(CharSequence s, int a, int b2, int c) {}
            public void afterTextChanged(Editable s) { query = s.toString().toLowerCase(Locale.US); rebuild(); }
        });
        root.addView(search);

        // controls: show system toggle + select/deselect all
        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        controls.setGravity(Gravity.CENTER_VERTICAL);
        controls.setPadding(dp(14), dp(2), dp(14), dp(4));
        TextView sysLbl = new TextView(this);
        sysLbl.setText("Show system apps");
        sysLbl.setTextColor(Theme.SOFT);
        sysLbl.setTextSize(13);
        sysLbl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Switch sysSw = new Switch(this);
        sysSw.setChecked(showSystem);
        sysSw.setOnCheckedChangeListener((v, on) -> { showSystem = on; rebuild(); });
        controls.addView(sysLbl);
        controls.addView(sysSw);
        root.addView(controls);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.setPadding(dp(12), 0, dp(12), dp(4));
        btns.addView(smallBtn("Select all (shown)", () -> {
            int skipped = 0;
            for (Row r : visible()) {
                if (Targets.isRisky(r.pkg)) { skipped++; continue; } // don't bulk-add system/income apps
                selected.add(r.pkg);
            }
            if (skipped > 0) Toast.makeText(this, "Skipped " + skipped
                    + " system/income app(s) — add those individually if you mean to.",
                    Toast.LENGTH_LONG).show();
            rebuild();
        }));
        btns.addView(smallBtn("Deselect all", () -> { selected.clear(); rebuild(); }));
        root.addView(btns);

        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), 0, dp(12), dp(12));
        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.addView(list);
        root.addView(scroll);

        // save
        Button save = new Button(this);
        save.setText("Save");
        save.setAllCaps(false);
        save.setTextColor(Theme.ON_GOLD);
        save.setMinWidth(0); save.setMinHeight(0); save.setMinimumWidth(0); save.setMinimumHeight(0);
        save.setPadding(dp(16), dp(11), dp(16), dp(11)); save.setTextSize(15); save.setStateListAnimator(null);
        save.setBackground(pill(Theme.GOLD, Theme.GOLD));
        LinearLayout.LayoutParams savp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        savp.setMargins(dp(12), dp(4), dp(12), dp(12));
        save.setLayoutParams(savp);
        save.setOnClickListener(v -> {
            Targets.set(prefs, selected);
            Toast.makeText(this, selected.size() + " target app(s) saved.", Toast.LENGTH_SHORT).show();
            finish();
        });
        root.addView(save);

        setContentView(root);

        // Enumerating installed apps + loading each icon is slow; do it off the UI thread so the
        // picker opens instantly and doesn't jank on devices with many apps.
        TextView loading = new TextView(this);
        loading.setText("Loading apps…");
        loading.setTextColor(Theme.DIM);
        loading.setPadding(dp(4), dp(12), dp(4), dp(12));
        list.addView(loading);
        new Thread(() -> {
            final List<Row> loaded = loadApps();
            runOnUiThread(() -> { rows.clear(); rows.addAll(loaded); rebuild(); });
        }).start();
    }

    private List<Row> loadApps() {
        java.util.Map<String, Row> byPkg = new java.util.LinkedHashMap<>();
        // Primary: every installed app (needs QUERY_ALL_PACKAGES on API 30+, declared in manifest).
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) addRow(byPkg, ai);
        // Fallback: launchable apps via a LAUNCHER intent — returned even under tighter package
        // visibility, so the list is never empty if getInstalledApplications is restricted.
        try {
            android.content.Intent launcher = new android.content.Intent(android.content.Intent.ACTION_MAIN);
            launcher.addCategory(android.content.Intent.CATEGORY_LAUNCHER);
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(launcher, 0)) {
                if (ri.activityInfo == null) continue;
                String pkg = ri.activityInfo.packageName;
                if (byPkg.containsKey(pkg)) continue;
                try { addRow(byPkg, pm.getApplicationInfo(pkg, 0)); } catch (Throwable ignored) {}
            }
        } catch (Throwable ignored) {}
        List<Row> out = new ArrayList<>(byPkg.values());
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
    }

    private void addRow(java.util.Map<String, Row> byPkg, ApplicationInfo ai) {
        if (byPkg.containsKey(ai.packageName)) return;
        boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        String label;
        try { label = String.valueOf(pm.getApplicationLabel(ai)); } catch (Throwable t) { label = ai.packageName; }
        Drawable icon;
        try { icon = pm.getApplicationIcon(ai); } catch (Throwable t) { icon = null; }
        byPkg.put(ai.packageName, new Row(ai.packageName, label, system, icon));
    }

    private List<Row> visible() {
        List<Row> out = new ArrayList<>();
        for (Row r : rows) {
            if (r.system && !showSystem) continue;
            if (!query.isEmpty()
                    && !r.label.toLowerCase(Locale.US).contains(query)
                    && !r.pkg.toLowerCase(Locale.US).contains(query)) continue;
            out.add(r);
        }
        return out;
    }

    private void rebuild() {
        list.removeAllViews();
        List<Row> vis = visible();

        // SELECTED apps pinned to the top (in their own section) so it's always obvious what's chosen and
        // easy to uncheck — even if an app is far down the alphabetical list or filtered out by search.
        List<Row> sel = new ArrayList<>();
        List<Row> rest = new ArrayList<>();
        for (Row r : vis) (selected.contains(r.pkg) ? sel : rest).add(r);
        // Selected apps that aren't in the visible set (e.g. a system app while "show system" is off, or
        // filtered by search) still need to show so the user can uncheck them.
        java.util.Set<String> visPkgs = new java.util.HashSet<>();
        for (Row r : vis) visPkgs.add(r.pkg);
        for (Row r : rows) if (selected.contains(r.pkg) && !visPkgs.contains(r.pkg)) sel.add(r);

        if (!sel.isEmpty()) {
            list.addView(sectionHeader("Selected (" + sel.size() + ")"));
            for (Row r : sel) list.addView(rowView(r));
            list.addView(sectionHeader("All apps"));
        }

        TextView count = new TextView(this);
        count.setText(rest.size() + " app(s)" + (showSystem ? "" : " — user apps (toggle to show system)"));
        count.setTextColor(Theme.DIM);
        count.setTextSize(12);
        count.setPadding(dp(4), dp(4), dp(4), dp(6));
        list.addView(count);
        if (rest.isEmpty() && sel.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(query.isEmpty()
                    ? "No apps found. (If this stays empty, the app may lack package-visibility access.)"
                    : "No apps match \"" + query + "\".");
            empty.setTextColor(Theme.SOFT);
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            list.addView(empty);
            return;
        }
        for (Row r : rest) list.addView(rowView(r));
    }

    private TextView sectionHeader(String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase(Locale.US));
        t.setTextColor(Theme.GOLD);
        t.setTextSize(11);
        t.setLetterSpacing(0.1f);
        t.setPadding(dp(4), dp(12), dp(4), dp(4));
        return t;
    }

    private View rowView(final Row r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(pill(Theme.CARD, Theme.LINE));
        row.setPadding(dp(10), dp(8), dp(10), dp(8));
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(3), 0, dp(3));
        row.setLayoutParams(rlp);

        if (r.icon != null) {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(r.icon);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(32), dp(32));
            ilp.setMargins(0, 0, dp(10), 0);
            iv.setLayoutParams(ilp);
            row.addView(iv);
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        texts.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(r.label);
        name.setTextColor(Theme.INK);
        name.setTextSize(14);
        TextView pkg = new TextView(this);
        pkg.setText(r.pkg);
        pkg.setTextColor(Theme.DIM);
        pkg.setTextSize(11);
        texts.addView(name);
        texts.addView(pkg);
        // "Not enabled in LSPosed" hint for a SELECTED app that isn't actually in scope (won't be spoofed
        // until the user enables Specter for it in the LSPosed manager). Checked off-thread.
        if (selected.contains(r.pkg)) {
            final TextView scopeWarn = new TextView(this);
            scopeWarn.setTextSize(11);
            scopeWarn.setTextColor(Theme.RED);
            scopeWarn.setVisibility(View.GONE);
            texts.addView(scopeWarn);
            new Thread(() -> {
                final boolean scoped = Targets.isScoped(r.pkg);
                runOnUiThread(() -> {
                    if (!scoped) { scopeWarn.setText("⚠ not enabled in LSPosed — enable Specter for this app"); scopeWarn.setVisibility(View.VISIBLE); }
                });
            }).start();
        }
        row.addView(texts);

        CheckBox cb = new CheckBox(this);
        cb.setChecked(selected.contains(r.pkg));
        cb.setOnCheckedChangeListener((v, on) -> {
            if (on) {
                if (Targets.isRisky(r.pkg)) {
                    Toast.makeText(this, r.label + " is a system/income app — spoofing it can affect a real "
                            + "account. Enable only if you mean to.", Toast.LENGTH_LONG).show();
                }
                selected.add(r.pkg);
            } else selected.remove(r.pkg);
            rebuild();   // re-pin to the Selected section immediately so the choice is visible
        });
        row.addView(cb);
        return row;
    }

    private Button smallBtn(String text, Runnable onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setMinWidth(0); b.setMinHeight(0); b.setMinimumWidth(0); b.setMinimumHeight(0);
        b.setPadding(dp(12), dp(8), dp(12), dp(8));
        b.setStateListAnimator(null);
        b.setTextColor(Theme.INK);
        b.setBackground(pill(Theme.CARD2, Theme.BTN_EDGE));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill); g.setCornerRadius(dp(10)); g.setStroke(dp(1), stroke);
        return g;
    }
}
