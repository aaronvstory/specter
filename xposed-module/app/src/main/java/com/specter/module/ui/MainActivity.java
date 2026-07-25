package com.specter.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.specter.module.gen.Country;
import com.specter.module.gen.Generators;
import com.specter.module.gen.IdentityService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Specter's main screen — a native (Views) 3-tab UI modeled on GeerGit: Identity / Settings /
 * Location. Generates identities ON-DEVICE and self-applies via IdentityService (su) — no PC.
 * Charcoal/gold theme (see {@link Theme}); multi-app targeting; per-country SIM; per-id toggles.
 */
public class MainActivity extends Activity {

    static final String PREFS = "specter";
    static final String DEFAULT_TARGET = "com.liuzh.deviceinfo"; // DevInfo — fleet-safe default

    private IdentityService svc;
    private Map<String, String> profile = new LinkedHashMap<>();
    private SharedPreferences prefs;

    private LinearLayout content;   // swapped per tab
    private TextView status;
    private final Button[] tabButtons = new Button[3];
    private int tab = 0;            // 0=Identity 1=Settings 2=Location

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        svc = new IdentityService(getApplicationContext());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        svc.setCountry(Country.of(prefs.getString("country", "US")));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);

        root.addView(header());
        root.addView(actionBar());
        root.addView(tabBar());

        status = new TextView(this);
        status.setTextColor(Theme.SOFT);
        status.setPadding(dp(16), dp(4), dp(16), dp(4));
        status.setTextSize(12);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(4), dp(12), dp(24));
        scroll.addView(content);
        root.addView(scroll);

        setContentView(root);
        regenerate();
    }

    @Override protected void onResume() {
        super.onResume();
        if (tab == 1) render(); // reflect target changes made in the app picker
    }

    // ---------- top chrome ----------
    private View header() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(14), dp(16), dp(6));

        ImageView logo = new ImageView(this);
        logo.setImageResource(com.specter.module.R.drawable.ic_specter_logo);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), dp(30));
        lp.setMargins(0, 0, dp(10), 0);
        logo.setLayoutParams(lp);

        TextView word = new TextView(this);
        word.setText("Specter");
        word.setTextColor(Theme.GOLD);
        word.setTextSize(22);
        word.setLetterSpacing(-0.02f);

        TextView ver = new TextView(this);
        String v = "";
        try { v = "v" + getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Throwable ignored) {}
        ver.setText(v);
        ver.setTextColor(Theme.DIM);
        ver.setTextSize(11);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        vlp.setMargins(dp(8), dp(6), 0, 0);
        ver.setLayoutParams(vlp);

        row.addView(logo);
        row.addView(word);
        row.addView(ver);
        return row;
    }

    private View actionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(4), dp(12), dp(4));
        bar.addView(button("RANDOMIZE ALL", false, v -> regenerate()));
        bar.addView(button("APPLY", true, v -> apply()));
        return bar;
    }

    private View tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(2), dp(12), dp(6));
        String[] names = {"Identity", "Settings", "Location"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button tb = tabButton(names[i], tab == i);
            tb.setOnClickListener(v -> { tab = idx; retintTabs(); render(); });
            tabButtons[i] = tb;
            bar.addView(tb);
        }
        return bar;
    }

    /** Re-tint the tab buttons so the active tab is visibly highlighted (the fix). */
    private void retintTabs() {
        for (int i = 0; i < tabButtons.length; i++) styleTab(tabButtons[i], tab == i);
    }

    private Button tabButton(String text, boolean active) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        btn.setLayoutParams(lp);
        styleTab(btn, active);
        return btn;
    }

    private void styleTab(Button btn, boolean active) {
        btn.setBackground(pill(active ? Theme.GOLD : Theme.CARD2, active ? Theme.GOLD : Theme.LINE));
        btn.setTextColor(active ? Theme.ON_GOLD : Theme.SOFT);
    }

    /** A general button: primary (gold fill, dark ink) or secondary (card fill, hairline). */
    private Button button(String text, boolean primary, View.OnClickListener onClick) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setBackground(pill(primary ? Theme.GOLD : Theme.CARD2, primary ? Theme.GOLD : Theme.BTN_EDGE));
        btn.setTextColor(primary ? Theme.ON_GOLD : Theme.INK);
        btn.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        btn.setLayoutParams(lp);
        return btn;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(3));
        g.setStroke(dp(1), stroke);
        return g;
    }

    // ---------- generation / apply ----------
    private void regenerate() {
        status.setText("Generating…");
        new Thread(() -> {
            try {
                final Map<String, String> p = svc.generateUnique();
                runOnUiThread(() -> { profile = p; status.setText("New identity ready — not yet applied."); render(); });
            } catch (Throwable t) {
                runOnUiThread(() -> status.setText("Generate failed: " + t.getMessage()));
            }
        }).start();
    }

    private void apply() {
        if (profile.isEmpty()) { toast("No identity yet — RANDOMIZE ALL first."); return; }
        Set<String> targets = Targets.get(prefs);
        if (targets.isEmpty()) { toast("No target apps selected — pick some in Settings."); return; }
        // enabled-only profile: disabled ids are omitted so the hook leaves them REAL.
        final Map<String, String> toApply = enabledProfile();
        if (toApply.isEmpty()) {
            // All ids disabled -> the hook would spoof nothing (every id stays REAL). Refuse.
            toast("Every identifier is toggled off — nothing to spoof. Enable some first.");
            return;
        }
        final List<String> pkgs = new ArrayList<>(targets);
        status.setText("Applying to " + pkgs.size() + " app(s)…");
        new Thread(() -> {
            int ok = 0; String lastErr = null;
            for (String pkg : pkgs) {
                try { svc.apply(pkg, toApply); ok++; }
                catch (Throwable t) { lastErr = t.getMessage(); }
            }
            final int okN = ok; final String err = lastErr;
            runOnUiThread(() -> {
                String m = "Applied to " + okN + "/" + pkgs.size() + " app(s)."
                        + (err != null ? " Last error: " + err + " (grant root in Magisk?)" : " Relaunch them to see it.");
                status.setText(m); toast(m);
            });
        }).start();
    }

    /** Profile with disabled ids removed (Build.* device bundle always kept if device_spoof on). */
    private Map<String, String> enabledProfile() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : profile.entrySet())
            if (Toggles.isEnabled(prefs, e.getKey())) out.put(e.getKey(), e.getValue());
        return out;
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    // ---------- rendering ----------
    private void render() {
        content.removeAllViews();
        switch (tab) {
            case 0: renderIdentity(); break;
            case 1: renderSettings(); break;
            case 2: renderLocation(); break;
        }
    }

    private void renderIdentity() {
        // GeerGit-style flow: (1) pick the target app, (2) see what will be randomized (all on by
        // default), (3) hit RANDOMIZE ALL. The target header sits at the top so the app you're
        // spoofing is always in view alongside its identifiers.
        content.addView(targetHeader());
        content.addView(sectionLabel("Device simulation"));
        for (IdentityFields.Field f : IdentityFields.DEVICE) content.addView(deviceRow(f));
        content.addView(sectionLabel("Identifiers"));
        for (IdentityFields.Field f : IdentityFields.IDENTIFIERS) content.addView(identifierCard(f));
    }

    /** Target-app card at the top of the Identity tab: shows the selected app(s) + a Change button. */
    private View targetHeader() {
        LinearLayout card = cardBox();
        Set<String> targets = Targets.get(prefs);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        txt.addView(label("Target app"));
        if (targets.isEmpty()) {
            TextView none = value("None selected — tap Change");
            none.setTextColor(Theme.DIM);
            txt.addView(none);
        } else {
            for (String pkg : targets) {
                TextView t = value(pkg);
                if (Targets.isRisky(pkg)) { t.setTextColor(Theme.RED); t.setText(pkg + "  ⚠ fleet/system"); }
                txt.addView(t);
            }
        }
        row.addView(txt);
        row.addView(button("Change", false, v ->
                startActivity(new Intent(this, AppPickerActivity.class))));
        card.addView(row);
        return card;
    }

    private View sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s.toUpperCase());
        t.setTextColor(Theme.GOLD);
        t.setTextSize(11);
        t.setLetterSpacing(0.12f);
        t.setPadding(dp(4), dp(14), dp(4), dp(4));
        return t;
    }

    private View deviceRow(IdentityFields.Field f) {
        LinearLayout card = cardBox();
        card.addView(label(f.label));
        card.addView(value(profile.get(f.key)));
        return card;
    }

    private View identifierCard(final IdentityFields.Field f) {
        LinearLayout card = cardBox();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = label(f.label);
        lab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch en = new Switch(this);
        en.setChecked(Toggles.isEnabled(prefs, f.key));
        en.setOnCheckedChangeListener((v, on) -> Toggles.set(prefs, f.key, on));
        head.addView(lab);
        head.addView(en);
        card.addView(head);

        final TextView val = value(profile.get(f.key));
        card.addView(val);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(button("EDIT", false, v -> {
            if (profile.isEmpty()) { toast("No identity yet — RANDOMIZE ALL first."); return; }
            editField(f, val);
        }));
        btns.addView(button("RANDOMIZE", false, v -> {
            if (profile.isEmpty()) { toast("No identity yet — RANDOMIZE ALL first."); return; }
            final Map<String, String> ctx = new LinkedHashMap<>(profile);
            new Thread(() -> {
                try {
                    final String nv = svc.randomizeField(ctx, f.key);
                    runOnUiThread(() -> {
                        profile.put(f.key, nv); val.setText(nv);
                        status.setText(f.label + " randomized — APPLY to push.");
                    });
                } catch (Throwable t) {
                    runOnUiThread(() -> status.setText("Randomize failed: " + t.getMessage()));
                }
            }).start();
        }));
        card.addView(btns);
        return card;
    }

    private void editField(final IdentityFields.Field f, final TextView val) {
        final EditText in = new EditText(this);
        in.setText(profile.get(f.key));
        in.setTextColor(Theme.INK);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
                .setTitle("Edit " + f.label)
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    String nv = in.getText().toString().trim();
                    if (!Generators.validate(f.key, nv)) { toast("Invalid " + f.label + " format — not saved."); return; }
                    profile.put(f.key, nv); val.setText(nv);
                    status.setText(f.label + " edited — APPLY to push.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renderSettings() {
        // Target apps
        content.addView(sectionLabel("Target apps"));
        Set<String> targets = Targets.get(prefs);
        LinearLayout tcard = cardBox();
        tcard.addView(label(targets.isEmpty() ? "None selected" : targets.size() + " app(s):"));
        for (String pkg : targets) {
            TextView t = value(pkg);
            if (Targets.isRisky(pkg)) { t.setTextColor(Theme.RED); t.setText(pkg + "  ⚠ fleet/system"); }
            tcard.addView(t);
        }
        content.addView(tcard);
        // Select-apps button lives OUTSIDE the card so it's always visible (not buried in a list).
        LinearLayout selRow = new LinearLayout(this);
        selRow.setOrientation(LinearLayout.HORIZONTAL);
        selRow.addView(button("Select target apps", false, v ->
                startActivity(new Intent(this, AppPickerActivity.class))));
        content.addView(selRow);

        // USA-only build: carrier + phone are always randomized within the US (T-Mobile/Verizon/
        // AT&T/Sprint/US Cellular/MVNOs, NANP numbers). No country picker — one coherent US market.

        // Anti-fingerprinting is ALWAYS ON — it's the whole point. Every identity is generated
        // coherently (device ⇄ carrier ⇄ radio ⇄ kernel all match one real US phone) and every
        // fingerprint-hash signal we can reach is spoofed. There's no toggle because turning it off
        // would just be "leak the real device" — never useful. (The dev's experimental
        // "device spoofing / anti-fingerprinting / make device legit" options are deliberately NOT
        // mirrored — he advised against them.)
        content.addView(sectionLabel("Anti-fingerprinting"));
        LinearLayout info = cardBox();
        info.addView(label("Always on"));
        TextView desc = value("Coherent identity + deep signal spoofing (Build, bootloader, radio, "
                + "kernel, HARDWARE/BOARD, SoC, GPU/GLES, /proc/cpuinfo, sensors) are applied "
                + "automatically on every identity.");
        desc.setTextColor(Theme.DIM);
        info.addView(desc);
        content.addView(info);
        // Location spoofing (proper hidemymock + Lockito-style GPS) is a planned later PR — not shown
        // as a dead toggle until it actually works.
    }

    private void renderLocation() {
        LinearLayout card = cardBox();
        card.addView(label("Location spoofing"));
        card.addView(value("UI only — no location hook yet (planned). Lat/long fields will drive a "
                + "LocationManager hook in a later build."));
        content.addView(card);
    }

    // ---------- small view builders ----------
    private LinearLayout cardBox() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(pill(Theme.CARD, Theme.LINE));
        c.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(4), 0, dp(4));
        c.setLayoutParams(lp);
        return c;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Theme.DIM);
        t.setTextSize(12);
        return t;
    }

    private TextView value(String s) {
        TextView t = new TextView(this);
        t.setText(s == null ? "—" : s);
        t.setTextColor(Theme.INK);
        t.setTextSize(14);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(2), 0, dp(4));
        return t;
    }
}
