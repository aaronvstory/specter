package com.fleet.idrotate.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.fleet.idrotate.gen.Generators;
import com.fleet.idrotate.gen.IdentityService;
import com.fleet.idrotate.gen.Profile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Specter's main screen — a native (Views) 3-tab UI modeled on GeerGit: Identity / Settings /
 * Location. Generates identities ON-DEVICE and self-applies via IdentityService (su) — no PC.
 *
 * Views (not Compose) deliberately: zero new build dependencies, guaranteed to compile with the
 * vendored toolchain, and a scrollable list of ~20 identifier cards is straightforward here.
 */
public class MainActivity extends Activity {

    private static final String TARGET = "com.liuzh.deviceinfo"; // DevInfo only — fleet safety
    private static final String PREFS = "specter";

    private IdentityService svc;
    private Map<String, String> profile = new LinkedHashMap<>();
    private SharedPreferences prefs;

    private LinearLayout content;   // swapped per tab
    private TextView status;
    private int tab = 0;            // 0=Identity 1=Settings 2=Location

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        svc = new IdentityService(getApplicationContext());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF101216);

        root.addView(header());
        root.addView(actionBar());
        root.addView(tabBar());

        status = new TextView(this);
        status.setTextColor(0xFF9AA4B2);
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

        regenerate(); // start with a fresh identity shown
    }

    // ---------- top chrome ----------
    private View header() {
        TextView t = new TextView(this);
        t.setText("Specter");
        t.setTextColor(0xFFE6E9EF);
        t.setTextSize(22);
        t.setPadding(dp(16), dp(14), dp(16), dp(2));
        return t;
    }

    private View actionBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(4), dp(12), dp(4));
        bar.addView(flatButton("RANDOMIZE ALL", 0xFF2A2F3A, v -> regenerate()));
        bar.addView(flatButton("APPLY", 0xFF3B82F6, v -> apply()));
        return bar;
    }

    private View tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(2), dp(12), dp(6));
        String[] names = {"Identity", "Settings", "Location"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button tb = flatButton(names[i], tab == i ? 0xFF3B82F6 : 0xFF1B1F27, v -> { tab = idx; render(); });
            bar.addView(tb);
        }
        return bar;
    }

    private Button flatButton(String text, int color, View.OnClickListener onClick) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextColor(0xFFE6E9EF);
        btn.setBackgroundColor(color);
        btn.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        btn.setLayoutParams(lp);
        return btn;
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
        status.setText("Applying to " + TARGET + " …");
        final Map<String, String> snapshot = new LinkedHashMap<>(profile);
        new Thread(() -> {
            String msg;
            try {
                svc.apply(TARGET, snapshot);
                msg = "Applied to " + TARGET + ". Relaunch it to see the new identity.";
            } catch (Throwable t) {
                msg = "Apply FAILED: " + t.getMessage() + " (grant root in Magisk?)";
            }
            final String fmsg = msg;
            runOnUiThread(() -> { status.setText(fmsg); toast(fmsg); });
        }).start();
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
        // ponytail: tab-bar highlight is cosmetic; not re-tinted live to keep this simple.
    }

    private void renderIdentity() {
        content.addView(sectionLabel("Device simulation"));
        for (IdentityFields.Field f : IdentityFields.DEVICE) content.addView(deviceRow(f));
        content.addView(sectionLabel("Identifiers"));
        for (IdentityFields.Field f : IdentityFields.IDENTIFIERS) content.addView(identifierCard(f));
    }

    private View sectionLabel(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(0xFF7C8698);
        t.setTextSize(12);
        t.setPadding(dp(4), dp(12), dp(4), dp(4));
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
        card.addView(label(f.label));
        final TextView val = value(profile.get(f.key));
        card.addView(val);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        Button edit = flatButton("EDIT", 0xFF2A2F3A, v -> editField(f, val));
        Button rand = flatButton("RANDOMIZE", 0xFF2A2F3A, v -> {
            String nv = svc.randomizeField(profile, f.key);
            val.setText(nv);
            status.setText(f.label + " randomized — APPLY to push.");
        });
        btns.addView(edit);
        btns.addView(rand);
        card.addView(btns);
        return card;
    }

    private void editField(final IdentityFields.Field f, final TextView val) {
        final EditText in = new EditText(this);
        in.setText(profile.get(f.key));
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
                .setTitle("Edit " + f.label)
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    String nv = in.getText().toString().trim();
                    if (!Generators.validate(f.key, nv)) {
                        toast("Invalid " + f.label + " format — not saved.");
                        return;
                    }
                    profile.put(f.key, nv);
                    val.setText(nv);
                    status.setText(f.label + " edited — APPLY to push.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renderSettings() {
        content.addView(sectionLabel("Target"));
        LinearLayout tcard = cardBox();
        tcard.addView(label("Target app (fleet-safe: DevInfo only)"));
        tcard.addView(value(TARGET));
        content.addView(tcard);

        content.addView(sectionLabel("Options (GeerGit parity — cosmetic unless noted)"));
        // ponytail: these toggles persist but most have no hook behind them yet (parity display).
        for (String opt : new String[]{"Anti Fingerprinting", "Hide Mock Location",
                "Location Spoofing", "Backup App Data", "Force Stop Only", "Clear Data Only"}) {
            content.addView(togglePlaceholder(opt));
        }
    }

    private View togglePlaceholder(final String name) {
        LinearLayout card = cardBox();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = label(name);
        t.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(prefs.getBoolean("opt_" + name, false));
        sw.setOnCheckedChangeListener((v, checked) -> prefs.edit().putBoolean("opt_" + name, checked).apply());
        row.addView(t);
        row.addView(sw);
        card.addView(row);
        return card;
    }

    private void renderLocation() {
        LinearLayout card = cardBox();
        card.addView(label("Location spoofing"));
        TextView t = value("UI only — no location hook yet (planned). Latitude/longitude fields "
                + "will drive a LocationManager hook in a later build.");
        card.addView(t);
        content.addView(card);
    }

    // ---------- small view builders ----------
    private LinearLayout cardBox() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(0xFF171B22);
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
        t.setTextColor(0xFF7C8698);
        t.setTextSize(12);
        return t;
    }

    private TextView value(String s) {
        TextView t = new TextView(this);
        t.setText(s == null ? "—" : s);
        t.setTextColor(0xFFE6E9EF);
        t.setTextSize(14);
        t.setTextIsSelectable(true);
        t.setPadding(0, dp(2), 0, dp(4));
        return t;
    }
}
