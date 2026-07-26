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
    private final Button[] tabButtons = new Button[4];
    private int tab = 0;            // 0=Identity 1=Saved 2=Settings 3=Location
    private Vault vault;
    private android.widget.CheckBox saveOnRandomize;   // "save to vault after RANDOMIZE ALL"
    private String vaultQuery = "";                     // Saved-tab search filter (label/device substring)
    private final Set<String> collapsedGroups = new java.util.HashSet<>();  // date groups the user collapsed

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        svc = new IdentityService(getApplicationContext());
        vault = new Vault(getApplicationContext());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        svc.setCountry(Country.of(prefs.getString("country", "US")));
        // Resume diagnostics capture if the user left it on (the service is START_STICKY but a full app
        // kill or reboot drops it — re-arm here so "on" stays on across launches).
        if (Protections.isOn(prefs, Protections.byKey("trace"))) DiagnosticsService.start(this);

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
        // Re-render on every resume so target changes from the app picker show up immediately — the
        // "Change" button lives on BOTH the Identity (tab 0) and Settings (tab 2) tabs, so gating on
        // tab==2 left the Identity target card showing stale selections after picking apps.
        if (svc != null) render();
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
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(4), dp(12), dp(2));
        bar.addView(button("RANDOMIZE ALL", false, v -> regenerate()));
        bar.addView(button("APPLY", true, v -> apply()));
        col.addView(bar);

        // "Save to vault" checkbox: when checked, RANDOMIZE ALL prompts to save the new identity (with a
        // unique date/time name prefilled) so it can be restored later from the Saved tab.
        saveOnRandomize = new android.widget.CheckBox(this);
        saveOnRandomize.setText("Save to vault after RANDOMIZE ALL");
        saveOnRandomize.setTextColor(Theme.SOFT);
        saveOnRandomize.setTextSize(13);
        saveOnRandomize.setChecked(prefs.getBoolean("save_on_randomize", false));
        saveOnRandomize.setOnCheckedChangeListener((v, on) ->
                prefs.edit().putBoolean("save_on_randomize", on).apply());
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp.setMargins(dp(16), 0, dp(16), dp(4));
        saveOnRandomize.setLayoutParams(cp);
        col.addView(saveOnRandomize);
        return col;
    }

    private View tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(2), dp(12), dp(6));
        String[] names = {"Identity", "Saved", "Settings", "Location"};
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
        btn.setTextSize(14);
        // Kill the default Button's chunky min-size + padding so it's a tight, modern pill.
        btn.setMinWidth(0); btn.setMinHeight(0);
        btn.setMinimumWidth(0); btn.setMinimumHeight(0);
        btn.setPadding(dp(16), dp(9), dp(16), dp(9));
        btn.setStateListAnimator(null);   // no elevation/shadow jump
        btn.setBackground(pill(primary ? Theme.GOLD : Theme.CARD2, primary ? Theme.GOLD : Theme.BTN_EDGE));
        btn.setTextColor(primary ? Theme.ON_GOLD : Theme.INK);
        btn.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(4), dp(2), dp(4), dp(2));
        btn.setLayoutParams(lp);
        return btn;
    }

    /** A compact, wrap-content pill button for inline/secondary actions (Change, ✕, small chips) — no
     *  forced weight, no chunky default padding. Keeps buttons consistent + small across the whole app. */
    private Button compactButton(String text, boolean primary, View.OnClickListener onClick) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setTextSize(13);
        btn.setMinWidth(0); btn.setMinHeight(0);
        btn.setMinimumWidth(0); btn.setMinimumHeight(0);
        btn.setPadding(dp(13), dp(6), dp(13), dp(6));
        btn.setStateListAnimator(null);
        btn.setBackground(pill(primary ? Theme.GOLD : Theme.CARD2, primary ? Theme.GOLD : Theme.BTN_EDGE));
        btn.setTextColor(primary ? Theme.ON_GOLD : Theme.SOFT);
        btn.setOnClickListener(onClick);
        btn.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return btn;
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(3));    // square-ish corners (user preference)
        g.setStroke(dp(1), stroke);
        return g;
    }

    // ---------- generation / apply ----------
    private void regenerate() {
        status.setText("Generating…");
        new Thread(() -> {
            try {
                final Map<String, String> p = svc.generateUnique();
                runOnUiThread(() -> {
                    profile = p;
                    status.setText("New identity ready — not yet applied.");
                    render();
                    // If "save to vault" is checked, prompt to save this fresh identity (name prefilled).
                    if (saveOnRandomize != null && saveOnRandomize.isChecked()) promptSaveName();
                });
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

    /** Profile with disabled ids removed (Build.* device bundle always kept if device_spoof on), plus
     *  the protection gate keys for any protection the user turned off (so the hooks skip it). */
    private Map<String, String> enabledProfile() {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : profile.entrySet())
            if (Toggles.isEnabled(prefs, e.getKey())) out.put(e.getKey(), e.getValue());
        Protections.applyGates(prefs, out);
        // The Gmail identifier's own inline switch (Identity tab) IS the opt-in control for account
        // masking: when it's ON, arm the hook (spoof_accounts=1); when OFF, gmail was already omitted
        // above, so the hook stays dormant. One control, shown next to the value — no separate toggle.
        if (Toggles.isEnabled(prefs, "gmail") && out.containsKey("gmail")) out.put("spoof_accounts", "1");
        return out;
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    // ---------- rendering ----------
    private void render() {
        content.removeAllViews();
        switch (tab) {
            case 0: renderIdentity(); break;
            case 1: renderSaved(); break;
            case 2: renderSettings(); break;
            case 3: renderLocation(); break;
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

    /** Target-app card (Identity tab): a clear list of the selected apps (by name) each with a quick
     *  remove (✕), an LSPosed-scope warning when an app isn't actually hooked, and a Change button. */
    private View targetHeader() {
        LinearLayout card = cardBox();
        final Set<String> targets = Targets.get(prefs);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = label(targets.isEmpty() ? "Target apps" : "Target apps (" + targets.size() + ")");
        lbl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(lbl);
        head.addView(compactButton("Change", false, v ->
                startActivity(new Intent(this, AppPickerActivity.class))));
        card.addView(head);

        if (targets.isEmpty()) {
            TextView none = value("None selected — tap Change to pick the app(s) to spoof.");
            none.setTextColor(Theme.DIM);
            card.addView(none);
            return card;
        }
        // One row per selected app: app NAME (+ package small), a scope warning if not hooked, and ✕.
        for (final String pkg : targets) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(0, dp(6), 0, dp(6));

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView name = value(Targets.label(this, pkg));
            name.setTextColor(Theme.INK);
            col.addView(name);
            TextView sub = value(pkg);
            sub.setTextColor(Theme.DIM);
            sub.setTextSize(11);
            col.addView(sub);
            r.addView(col);

            // "Not enabled in LSPosed" warning — checked off the UI thread (root grep), then shown.
            final TextView warn = new TextView(this);
            warn.setTextSize(11);
            warn.setTextColor(Theme.RED);
            warn.setVisibility(View.GONE);
            warn.setPadding(0, 0, dp(8), 0);
            r.addView(warn);
            new Thread(() -> {
                final boolean scoped = Targets.isScoped(pkg);
                runOnUiThread(() -> {
                    if (!scoped) { warn.setText("⚠ not enabled in LSPosed"); warn.setVisibility(View.VISIBLE); }
                });
            }).start();

            // Small square ✕ remove — a tight red-tinted icon-button (destructive action), not a chunky
            // full-width Button.
            TextView rm = new TextView(this);
            rm.setText("✕");
            rm.setTextSize(13);
            rm.setTextColor(Theme.RED);
            rm.setGravity(Gravity.CENTER);
            GradientDrawable rmBg = new GradientDrawable();
            rmBg.setColor(0x22EF8A8A);              // subtle red-tinted fill
            rmBg.setStroke(dp(1), 0x55EF8A8A);      // red-tinted border
            rmBg.setCornerRadius(dp(3));            // square-ish (matches the app's square corners)
            rm.setBackground(rmBg);
            LinearLayout.LayoutParams rmlp = new LinearLayout.LayoutParams(dp(32), dp(32));
            rm.setLayoutParams(rmlp);
            rm.setOnClickListener(v -> {
                Set<String> cur = Targets.get(prefs);
                cur.remove(pkg);
                Targets.set(prefs, cur);
                status.setText("Removed " + Targets.label(this, pkg) + " from targets.");
                render();
            });
            r.addView(rm);
            card.addView(r);
        }
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
        // Core coherent-identity spoofing (Build/bootloader/radio/kernel/SoC/GPU/sensors) is ALWAYS on —
        // it's the point. The toggles below control the ADDITIONAL anti-detection protections, each
        // wired to a real gate key in the applied profile (turning one off leaves that signal real).
        content.addView(sectionLabel("Anti-fingerprinting"));
        LinearLayout info = cardBox();
        info.addView(label("Core spoofing — always on"));
        TextView desc = value("Coherent identity + deep signal spoofing (Build, bootloader, radio, "
                + "kernel, HARDWARE/BOARD, SoC, GPU/GLES, /proc/cpuinfo, sensors) applies on every identity.");
        desc.setTextColor(Theme.DIM);
        info.addView(desc);
        content.addView(info);

        content.addView(sectionLabel("Protections"));
        for (Protections.P prot : Protections.ALL) content.addView(protectionRow(prot));
        // Location spoofing (proper hidemymock + Lockito-style GPS) is a planned later PR — not shown
        // as a dead toggle until it actually works.
    }

    /** One protection: label + description + a real toggle that gates the corresponding hook, plus a
     *  live ON/OFF status chip. No cosmetic switches — the state changes what the device reports. */
    private View protectionRow(final Protections.P prot) {
        LinearLayout card = cardBox();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout txt = new LinearLayout(this);
        txt.setOrientation(LinearLayout.VERTICAL);
        txt.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = label(prot.label);
        lab.setTextColor(Theme.INK);
        lab.setTextSize(14);
        titleRow.addView(lab);
        final TextView chip = statusChip(Protections.isOn(prefs, prot));
        titleRow.addView(chip);
        txt.addView(titleRow);
        TextView d = value(prot.desc);
        d.setTextColor(Theme.DIM);
        d.setTextSize(12);
        d.setTextIsSelectable(false);
        txt.addView(d);
        head.addView(txt);

        final Switch sw = new Switch(this);
        sw.setChecked(Protections.isOn(prefs, prot));
        sw.setOnCheckedChangeListener((v, on) -> {
            Protections.set(prefs, prot, on);
            styleChip(chip, on);
            // "Diagnostics logging" (trace) also manages the background capture service. It's read-only,
            // so start/stop immediately; the trace=1 gate reaches the hooks on the next APPLY.
            if ("trace".equals(prot.gateKey)) {
                if (on) DiagnosticsService.start(this); else DiagnosticsService.stop(this);
                status.setText(on
                        ? "Diagnostics ON — capturing to " + DiagnosticsService.LOG_PATH + "; APPLY to arm the hooks."
                        : "Diagnostics OFF — capture stopped.");
            } else {
                status.setText(prot.label + (on ? " enabled" : " disabled") + " — APPLY to push.");
            }
        });
        head.addView(sw);
        card.addView(head);
        return card;
    }

    private TextView statusChip(boolean on) {
        TextView chip = new TextView(this);
        chip.setTextSize(10);
        chip.setPadding(dp(6), dp(1), dp(6), dp(1));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(8), 0, 0, 0);
        chip.setLayoutParams(lp);
        styleChip(chip, on);
        return chip;
    }

    private void styleChip(TextView chip, boolean on) {
        chip.setText(on ? "ON" : "OFF");
        chip.setTextColor(on ? Theme.ON_GOLD : Theme.SOFT);
        chip.setBackground(pill(on ? Theme.GOLD : Theme.CARD2, on ? Theme.GOLD : Theme.LINE));
    }

    private void renderLocation() {
        LinearLayout card = cardBox();
        card.addView(label("Location spoofing"));
        card.addView(value("UI only — no location hook yet (planned). Lat/long fields will drive a "
                + "LocationManager hook in a later build."));
        content.addView(card);
    }

    // ---------- Saved (profile vault): save current, list, restore, delete ----------
    private void renderSaved() {
        content.addView(sectionLabel("Save current identity"));
        LinearLayout saveCard = cardBox();
        saveCard.addView(value("Save the identity shown on the Identity tab so you can re-apply this exact "
                + "device later. A unique name is prefilled from the date/time — edit it if you like."));
        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveRow.addView(button("Save current to vault", true, v -> {
            if (profile.isEmpty()) { toast("No identity yet — RANDOMIZE ALL on the Identity tab first."); return; }
            promptSaveName();
        }));
        saveCard.addView(saveRow);
        content.addView(saveCard);

        content.addView(sectionLabel("Saved profiles"));
        savedListHolder = null;   // fresh holder per full render (content was cleared by render())
        java.util.List<Vault.Entry> all = vault.list();
        if (all.isEmpty()) {
            LinearLayout empty = cardBox();
            TextView t = value("No saved profiles yet.");
            t.setTextColor(Theme.DIM);
            empty.addView(t);
            content.addView(empty);
            return;
        }

        // Search box — filters by label OR device (case-insensitive substring). Preserves the query and
        // caret across re-renders so typing feels continuous.
        final EditText search = new EditText(this);
        search.setHint("Search saved (name or device)…");
        search.setText(vaultQuery);
        search.setSelection(vaultQuery.length());
        search.setTextColor(Theme.INK);
        search.setHintTextColor(Theme.DIM);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        search.setBackground(pill(Theme.CARD, Theme.LINE));
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.setMargins(0, dp(2), 0, dp(6));
        search.setLayoutParams(slp);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                vaultQuery = s.toString();
                renderSavedList(all);   // re-render just the list below the search box
            }
        });
        content.addView(search);

        renderSavedList(all);
    }

    /** Render the filtered, date-grouped, collapsible list of saved profiles (below the search box). */
    private LinearLayout savedListHolder;
    private void renderSavedList(java.util.List<Vault.Entry> all) {
        if (savedListHolder == null) {
            savedListHolder = new LinearLayout(this);
            savedListHolder.setOrientation(LinearLayout.VERTICAL);
            content.addView(savedListHolder);
        }
        savedListHolder.removeAllViews();

        String q = vaultQuery.trim().toLowerCase();
        // Group by the date+day prefix "MMDDYY-DayAbbr" (the label's first two dash-parts), newest first.
        java.util.LinkedHashMap<String, java.util.List<Vault.Entry>> groups = new java.util.LinkedHashMap<>();
        int shown = 0;
        for (Vault.Entry e : all) {
            if (!q.isEmpty() && !e.label.toLowerCase().contains(q) && !e.device.toLowerCase().contains(q)) continue;
            shown++;
            String[] parts = e.label.split("-");
            String group = parts.length >= 2 ? parts[0] + "-" + parts[1] : e.label;   // "072626-Sun"
            groups.computeIfAbsent(group, k -> new java.util.ArrayList<>()).add(e);
        }
        if (shown == 0) {
            TextView none = value(q.isEmpty() ? "No saved profiles." : "No matches for \"" + vaultQuery + "\".");
            none.setTextColor(Theme.DIM);
            savedListHolder.addView(none);
            return;
        }
        for (Map.Entry<String, java.util.List<Vault.Entry>> g : groups.entrySet()) {
            final String groupKey = g.getKey();
            final boolean collapsed = collapsedGroups.contains(groupKey) && q.isEmpty();  // search always expands
            // Group header (tap to collapse/expand). Shows the date/day + count.
            TextView header = new TextView(this);
            header.setText((collapsed ? "▸  " : "▾  ") + prettyGroup(groupKey) + "   (" + g.getValue().size() + ")");
            header.setTextColor(Theme.GOLD);
            header.setTextSize(13);
            header.setPadding(dp(4), dp(10), dp(4), dp(4));
            header.setOnClickListener(v -> {
                if (collapsedGroups.contains(groupKey)) collapsedGroups.remove(groupKey);
                else collapsedGroups.add(groupKey);
                renderSavedList(all);
            });
            savedListHolder.addView(header);
            if (!collapsed) for (Vault.Entry e : g.getValue()) savedListHolder.addView(savedRow(e));
        }
    }

    /** "072626-Sun" -> "Sun 07/26/26" for the group header. */
    private String prettyGroup(String key) {
        String[] p = key.split("-");
        if (p.length >= 2 && p[0].length() == 6)
            return p[1] + " " + p[0].substring(0, 2) + "/" + p[0].substring(2, 4) + "/" + p[0].substring(4, 6);
        return key;
    }

    private View savedRow(final Vault.Entry e) {
        LinearLayout card = cardBox();
        TextView lab = label(e.label);
        lab.setTextColor(Theme.INK);
        lab.setTextSize(14);
        card.addView(lab);
        TextView dev = value(e.device);
        dev.setTextColor(Theme.SOFT);
        card.addView(dev);

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(button("RESTORE", true, v -> restoreSaved(e.label)));
        btns.addView(button("DELETE", false, v -> {
            new AlertDialog.Builder(this)
                    .setTitle("Delete saved profile?")
                    .setMessage(e.label)
                    .setPositiveButton("Delete", (d, w) -> {
                        vault.delete(e.label);
                        status.setText("Deleted " + e.label);
                        render();   // refresh the list
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        }));
        card.addView(btns);
        return card;
    }

    /** Load a saved profile into the current identity AND apply it to the selected target app(s). */
    private void restoreSaved(final String labelStr) {
        final Map<String, String> saved = vault.load(labelStr);
        if (saved == null || saved.isEmpty()) { toast("Could not read that saved profile."); return; }
        profile = new LinkedHashMap<>(saved);
        Set<String> targets = Targets.get(prefs);
        if (targets.isEmpty()) {
            status.setText("Restored " + labelStr + " — pick a target app (Settings), then it will apply.");
            toast("Restored into the current identity. Select a target app to apply.");
            return;
        }
        final Map<String, String> toApply = enabledProfile();   // applies protection gates too
        final List<String> pkgs = new ArrayList<>(targets);
        status.setText("Restoring " + labelStr + " to " + pkgs.size() + " app(s)…");
        new Thread(() -> {
            int ok = 0; String lastErr = null;
            for (String pkg : pkgs) {
                try { svc.apply(pkg, toApply); ok++; }
                catch (Throwable t) { lastErr = t.getMessage(); }
            }
            final int okN = ok; final String err = lastErr;
            runOnUiThread(() -> status.setText("Restored " + labelStr + " to " + okN + "/" + pkgs.size()
                    + " app(s)." + (err != null ? " Last error: " + err : " Relaunch them to see it.")));
        }).start();
    }

    private void promptSaveName() {
        final EditText in = new EditText(this);
        in.setText(Vault.makeLabel(""));   // prefill with the unique date/time label
        in.setSelection(in.getText().length());
        in.setTextColor(Theme.INK);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
                .setTitle("Save identity as")
                .setMessage("A unique date/time name is prefilled. Add or replace with your own if you like.")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    // If the user kept the prefilled label, save under it as-is; else treat their text as the name.
                    String typed = in.getText().toString().trim();
                    String label = typed.equals(Vault.makeLabel("")) || typed.isEmpty()
                            ? vault.save("", profile)          // prefilled/empty -> pure timestamp label
                            : vault.save(typed, profile);      // custom -> timestamp + sanitized name
                    status.setText("Saved as " + label);
                    toast("Saved to vault: " + label);
                })
                .setNegativeButton("Cancel", null)
                .show();
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
