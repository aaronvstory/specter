package com.fleet.idrotate.ui;

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
 * set via {@link Targets}. Warns on fleet/system packages (fleet safety).
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

        TextView title = new TextView(this);
        title.setText("Target Apps");
        title.setTextColor(Theme.GOLD);
        title.setTextSize(20);
        title.setPadding(dp(16), dp(14), dp(16), dp(6));
        root.addView(title);

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
                if (Targets.isRisky(r.pkg)) { skipped++; continue; } // don't bulk-add fleet/system apps
                selected.add(r.pkg);
            }
            if (skipped > 0) Toast.makeText(this, "Skipped " + skipped
                    + " fleet/system app(s) — add those individually if you really mean to.",
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
        List<Row> out = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(0)) {
            boolean system = (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
            String label;
            try { label = String.valueOf(pm.getApplicationLabel(ai)); } catch (Throwable t) { label = ai.packageName; }
            Drawable icon;
            try { icon = pm.getApplicationIcon(ai); } catch (Throwable t) { icon = null; }
            out.add(new Row(ai.packageName, label, system, icon));
        }
        out.sort((a, b) -> a.label.compareToIgnoreCase(b.label));
        return out;
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
        for (Row r : visible()) list.addView(rowView(r));
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
        name.setTextColor(Targets.isRisky(r.pkg) ? Theme.RED : Theme.INK);
        name.setTextSize(14);
        TextView pkg = new TextView(this);
        pkg.setText(r.pkg + (Targets.isRisky(r.pkg) ? "  ⚠ fleet/system" : ""));
        pkg.setTextColor(Theme.DIM);
        pkg.setTextSize(11);
        texts.addView(name);
        texts.addView(pkg);
        row.addView(texts);

        CheckBox cb = new CheckBox(this);
        cb.setChecked(selected.contains(r.pkg));
        cb.setOnCheckedChangeListener((v, on) -> {
            if (on) {
                if (Targets.isRisky(r.pkg)) {
                    Toast.makeText(this, "⚠ " + r.pkg + " is a fleet/system app. Spoofing it can risk a "
                            + "real account — only do this deliberately.", Toast.LENGTH_LONG).show();
                }
                selected.add(r.pkg);
            } else selected.remove(r.pkg);
        });
        row.addView(cb);
        return row;
    }

    private Button smallBtn(String text, Runnable onClick) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
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
        g.setColor(fill); g.setCornerRadius(dp(3)); g.setStroke(dp(1), stroke);
        return g;
    }
}
