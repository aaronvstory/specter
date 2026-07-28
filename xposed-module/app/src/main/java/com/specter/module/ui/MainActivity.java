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
import com.specter.module.gen.SessionMigrator;
import com.specter.module.gen.WidevineL3;
import com.specter.module.gen.GsfReset;

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
    private boolean widevineBusy = false;              // guards the Widevine-L3 toggle's failure-rollback re-fire
    private com.specter.module.gen.ZygiskInstaller.Status zygiskStatus;   // native-layer health (async, null until checked)
    private boolean zygiskBusy = false;                // guards the native-layer install button
    private String vaultQuery = "";                     // Saved-tab search filter (label/device substring)
    private String appliedTargets = "";                 // comma-sep pkgs the CURRENT profile was applied to
                                                        // ("" until Apply succeeds — vault saves only applied)
    private String appliedSig = "";                      // signature (android_id + target set) of the LAST
                                                        // successful apply — so a second APPLY of the SAME
                                                        // unchanged identity says "already applied" instead
                                                        // of silently re-doing it + re-prompting to save.
    private String seededRecentGroup = null;   // the most-recent date group we auto-expanded (so it opens once, per key)
    private final Set<String> expandedGroups = new java.util.HashSet<>();  // date groups the user EXPANDED
                                                                          // (Saved profiles collapse by default)

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
        checkZygisk();
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

        // "Save to vault" checkbox: when checked, a successful APPLY prompts to save the identity (name
        // prefilled) so it can be restored later. We save on APPLY (not RANDOMIZE) so a vault entry always
        // represents an identity that actually reached at least one app — saving un-applied profiles is
        // pointless/misleading.
        saveOnRandomize = new android.widget.CheckBox(this);
        saveOnRandomize.setText("Save to vault after APPLY");
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

        // APPLY/RESTORE ALWAYS deep-clean (pm clear: storage + cache) each target before writing the profile —
        // it's not optional, because applying an identity over a prior one's data links the accounts. This is a
        // fixed info line, not a toggle (a toggle here would be a footgun / dead control now that it's mandatory).
        TextView autoClear = new TextView(this);
        autoClear.setText("🧹 Auto deep-clean: every APPLY / RESTORE wipes each target's storage + cache first, so "
                + "no data carries over between identities.");
        autoClear.setTextColor(Theme.DIM);
        autoClear.setTextSize(12);
        LinearLayout.LayoutParams cp2 = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cp2.setMargins(dp(16), 0, dp(16), dp(4));
        autoClear.setLayoutParams(cp2);
        col.addView(autoClear);
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
        btn.setTextSize(14);
        // Kill the default Button's chunky min-height (was making the tab row absurdly tall) + elevation,
        // but keep a comfortable ~48dp tap target — the tabs were too short to hit reliably.
        btn.setMinWidth(0); btn.setMinHeight(dp(44));
        btn.setMinimumWidth(0); btn.setMinimumHeight(dp(44));
        btn.setPadding(dp(8), dp(11), dp(8), dp(11));
        btn.setGravity(android.view.Gravity.CENTER);
        btn.setStateListAnimator(null);
        btn.setLineSpacing(0f, 0.9f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.setMargins(dp(3), dp(1), dp(3), dp(1));
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
                    appliedTargets = ""; appliedSig = "";   // fresh identity — not applied to anything yet
                    status.setText("New identity ready — not yet applied.");
                    render();
                    // NOTE: saving happens after APPLY (below), not here — a vault entry should only ever
                    // represent an identity that was actually applied to an app.
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
        // Already-applied guard: re-APPLYING the SAME identity to the SAME targets is a no-op we skip (so we
        // don't needlessly wipe + re-prompt to save). Any DIFFERENT identity always goes through the full
        // clear below — applying a new profile over a dirty install is the exact identity-link we must avoid.
        // Sign off `profile` (the full pre-toggle map), NOT `toApply` (android_id may be toggled out of toApply).
        final String sig = applySignature(profile, targets);
        if (!appliedSig.isEmpty() && appliedSig.equals(sig)) {
            String msg = "Already applied to " + pkgs.size() + " app(s). "
                    + "Relaunch them to see it, or RANDOMIZE ALL for a new identity.";
            status.setText(msg); toast(msg);
            return;
        }
        status.setText("Deep-cleaning + applying to " + pkgs.size() + " app(s)…");
        new Thread(() -> {
            int cleared = 0, ok = 0; String lastErr = null; String clearErr = null;
            java.util.List<String> okPkgs = new ArrayList<>();
            for (String pkg : pkgs) {
                // ALWAYS wipe data+cache before writing the profile. Applying an identity onto an install that
                // still holds a PRIOR identity's data links the two accounts (the app carries over ids/session)
                // — the single worst cross-identity leak. So if the clear FAILS we do NOT apply to that app
                // (better to leave it un-spoofed than to write a new identity onto dirty, linkable data).
                boolean clean = false;
                try { com.specter.module.gen.SessionMigrator.clearData(pkg); cleared++; clean = true; }
                catch (Throwable t) { clearErr = t.getMessage(); }
                if (!clean) continue;   // clear failed -> skip apply for this pkg
                try { svc.apply(pkg, toApply); ok++; okPkgs.add(pkg); }
                catch (Throwable t) { lastErr = t.getMessage(); }
            }
            final int clearedN = cleared, okN = ok; final String clrErr = clearErr, err = lastErr;
            final boolean allClean = clearedN == pkgs.size();   // every target wiped -> the no-carry-over claim holds
            final boolean allApplied = okN == pkgs.size();      // every target cleared AND applied
            runOnUiThread(() -> {
                // Only claim "no carry-over" when EVERY target was actually cleared.
                if (allClean) toast("🧹 Deep-cleaned cache + storage on all " + pkgs.size()
                        + " app(s) before applying — no carry-over from the previous identity.");
                else if (clearedN > 0) toast("⚠️ Deep-cleaned only " + clearedN + "/" + pkgs.size()
                        + " app(s) — the rest were NOT cleared or applied (grant root in Magisk?).");
                String m = "Cleared " + clearedN + "/" + pkgs.size() + ", applied " + okN + "/" + pkgs.size() + " app(s)."
                        + (clrErr != null ? " Clear error: " + clrErr : "")
                        + (err != null ? " Apply error: " + err + " (grant root in Magisk?)" : "")
                        + (clrErr == null && err == null ? " Relaunch them to see it." : "");
                status.setText(m); toast(m);
                if (okN > 0) appliedTargets = String.join(",", okPkgs);   // only the apps it actually reached
                // Record the applied signature (so a repeat Apply is a no-op) ONLY when the WHOLE set fully
                // cleared+applied — else a partial failure must remain retryable, not be suppressed as "done".
                if (allApplied) {
                    appliedSig = sig;
                    if (saveOnRandomize != null && saveOnRandomize.isChecked()) promptSaveName(appliedTargets);
                }
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

    /** Signature identifying "this identity applied to this target set" — the android_id (unique per
     *  generated identity) plus the sorted package set. Two Applies match iff the identity AND the targets
     *  are both unchanged, which is exactly when a re-apply would be a no-op. */
    private String applySignature(Map<String, String> applied, Set<String> targets) {
        String aid = applied.get("android_id");
        return (aid == null ? "?" : aid) + "|" + new java.util.TreeSet<>(targets);
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    // ---------- rendering ----------
    private void render() {
        content.removeAllViews();
        // Native-layer health banner — shown on every tab ONLY when the Zygisk layer is missing or stale, so
        // the user can't silently run with the native read-paths unhooked (a real coverage gap). Hidden when OK.
        if (zygiskStatus != null && !zygiskStatus.current && zygiskStatus.bundledVersion != null) {
            content.addView(zygiskBanner());
        }
        switch (tab) {
            case 0: renderIdentity(); break;
            case 1: renderSaved(); break;
            case 2: renderSettings(); break;
            case 3: renderLocation(); break;
        }
    }

    /** Read the on-device native-layer status off the UI thread (su can block), then re-render so the banner
     *  reflects it. Runs on launch; the install flow re-runs it after a successful install. */
    private void checkZygisk() {
        new Thread(() -> {
            com.specter.module.gen.ZygiskInstaller.Status st;
            try { st = com.specter.module.gen.ZygiskInstaller.status(getApplicationContext(), new com.specter.module.gen.RootWriter.SuShell()); }
            catch (Throwable t) { st = null; }
            final com.specter.module.gen.ZygiskInstaller.Status f = st;
            runOnUiThread(() -> { zygiskStatus = f; if (svc != null) render(); });
        }).start();
    }

    /** The missing/stale-native-layer banner: an amber card explaining the gap + a one-tap install button.
     *  Install writes the module from the bundled asset via su, then prompts a reboot. */
    private View zygiskBanner() {
        LinearLayout card = cardBox();
        boolean stale = zygiskStatus.installed;   // installed but wrong version vs missing entirely
        TextView lab = label(stale ? "Native layer out of date" : "Native layer not installed");
        lab.setTextColor(Theme.AMBER); lab.setTextSize(14);
        card.addView(lab);
        TextView d = value(stale
                ? "The Specter Zygisk native layer is " + zygiskStatus.installedVersion + " but this app bundles "
                  + zygiskStatus.bundledVersion + ". Update it so the native read-paths (props/reset-time/GLES a "
                  + "fingerprinter reads below the Java hooks) stay covered."
                : "The Specter Zygisk native layer isn't installed. Without it, a fingerprinter reading device "
                  + "props / reset-time / GLES NATIVELY (below the Java hooks) sees the real device. Install it "
                  + "in one tap — no separate Magisk flash needed.");
        d.setTextColor(Theme.DIM); d.setTextSize(12);
        card.addView(d);
        Button go = button(stale ? "Update native layer" : "Install native layer", true, v -> installZygisk());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        go.setLayoutParams(lp);
        card.addView(go);
        return card;
    }

    /** Install/update the bundled Zygisk native layer via su, off-thread, then prompt a reboot. */
    private void installZygisk() {
        if (zygiskBusy) return;
        zygiskBusy = true;
        status.setText("Installing native layer…");
        new Thread(() -> {
            String err = null;
            try { com.specter.module.gen.ZygiskInstaller.install(getApplicationContext()); }
            catch (Throwable t) { err = t.getMessage(); }
            final String e = err;
            runOnUiThread(() -> {
                zygiskBusy = false;
                if (e == null) {
                    status.setText("Native layer installed — REBOOT to activate it.");
                    new AlertDialog.Builder(this)
                            .setTitle("Native layer installed")
                            .setMessage("The Specter Zygisk native layer is in place. It loads at boot — reboot now to activate it?")
                            .setPositiveButton("Reboot now", (dl, w) -> {
                                new Thread(() -> { try { new com.specter.module.gen.RootWriter.SuShell().run("svc power reboot || reboot", ""); } catch (Throwable ignored) {} }).start();
                            })
                            .setNegativeButton("Later", null)
                            .show();
                    checkZygisk();   // refresh status (it'll still show stale until reboot, but confirms the write)
                } else {
                    status.setText("Native-layer install failed: " + e);
                    toast("Native-layer install failed: " + e);
                }
            });
        }).start();
    }

    private void renderIdentity() {
        // GeerGit-style flow: (1) pick the target app, (2) see what will be randomized (all on by
        // default), (3) hit RANDOMIZE ALL. The target header sits at the top so the app you're
        // spoofing is always in view alongside its identifiers.
        content.addView(targetHeader());
        content.addView(sectionLabel("Device simulation"));
        content.addView(deviceSpecCard());
        content.addView(sectionLabel("Identifiers"));
        for (IdentityFields.Field f : IdentityFields.IDENTIFIERS) content.addView(identifierCard(f));
    }

    /** The device fields (manufacturer/model/brand/device/fingerprint/carrier) as ONE compact spec-sheet
     *  card: a tight label-left / value-right row per field with hairline separators, instead of a bulky
     *  full card each. Reads like a real device-info panel and cuts the scroll dramatically. Long values
     *  (the fingerprint) wrap under a full-width value line. Tapping a row opens the field editor (custom
     *  values — you can clone a specific device, not just randomize). */
    private View deviceSpecCard() {
        LinearLayout card = cardBox();
        card.setPadding(dp(12), dp(4), dp(12), dp(4));
        List<IdentityFields.Field> fields = IdentityFields.DEVICE;
        for (int i = 0; i < fields.size(); i++) {
            final IdentityFields.Field f = fields.get(i);
            final String v = profile.get(f.key);
            boolean longValue = v != null && v.length() > 24;   // fingerprint etc. -> stacked, full width

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(longValue ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
            if (!longValue) row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(9), 0, dp(9));

            TextView lab = new TextView(this);
            lab.setText(f.label);
            lab.setTextColor(Theme.DIM);
            lab.setTextSize(13);
            final TextView val = new TextView(this);
            val.setText(v == null ? "—" : v);
            val.setTextColor(Theme.INK);
            val.setTextSize(14);
            val.setTextIsSelectable(true);
            if (longValue) {
                val.setTypeface(android.graphics.Typeface.MONOSPACE);
                val.setTextSize(12);
                val.setPadding(0, dp(3), 0, 0);
                row.addView(lab); row.addView(val);
            } else {
                lab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                val.setGravity(Gravity.END);
                row.addView(lab); row.addView(val);
            }
            row.setOnClickListener(x -> {
                if (profile.isEmpty()) { toast("No identity yet — RANDOMIZE ALL first."); return; }
                editField(f, val);
            });
            card.addView(row);
            if (i < fields.size() - 1) card.addView(hairline());
        }
        return card;
    }

    /** A 1px separator line in the theme's hairline color, for spec-sheet rows. */
    private View hairline() {
        View v = new View(this);
        v.setBackgroundColor(Theme.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    /** Target-app section (Identity tab): a header row (label + Change) followed by one SEPARATED card
     *  per selected app — icon, name/package, an LSPosed-scope warning if not hooked, and a red ✕ remove.
     *  Matches the picker's separated-card look (each app clearly distinct, not run-together rows). */
    private View targetHeader() {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        final Set<String> targets = Targets.get(prefs);

        // Header row (label + Change) — its own card.
        LinearLayout headCard = cardBox();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView lbl = label(targets.isEmpty() ? "Target apps" : "Target apps (" + targets.size() + ")");
        lbl.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(lbl);
        head.addView(compactButton("Change", false, v ->
                startActivity(new Intent(this, AppPickerActivity.class))));
        headCard.addView(head);
        if (targets.isEmpty()) {
            TextView none = value("None selected — tap Change to pick the app(s) to spoof.");
            none.setTextColor(Theme.DIM);
            headCard.addView(none);
        }
        wrap.addView(headCard);

        // One SEPARATED card per selected app.
        for (final String pkg : targets) {
            LinearLayout appCard = cardBox();
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);

            // App icon (small) for quick visual identification, same as the picker rows.
            try {
                android.graphics.drawable.Drawable ic = getPackageManager().getApplicationIcon(pkg);
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(ic);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(30), dp(30));
                ilp.setMargins(0, 0, dp(10), 0);
                iv.setLayoutParams(ilp);
                r.addView(iv);
            } catch (Throwable ignored) {}

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
            // "Not enabled in LSPosed" warning — checked off the UI thread (memoized root grep).
            final TextView warn = new TextView(this);
            warn.setTextSize(11);
            warn.setTextColor(Theme.RED);
            warn.setVisibility(View.GONE);
            col.addView(warn);
            new Thread(() -> {
                final boolean scoped = Targets.isScoped(pkg);
                runOnUiThread(() -> {
                    if (!scoped) { warn.setText("⚠ not enabled in LSPosed"); warn.setVisibility(View.VISIBLE); }
                });
            }).start();
            r.addView(col);

            // Small square red-tinted ✕ remove.
            TextView rm = new TextView(this);
            rm.setText("✕");
            rm.setTextSize(13);
            rm.setTextColor(Theme.RED);
            rm.setGravity(Gravity.CENTER);
            GradientDrawable rmBg = new GradientDrawable();
            rmBg.setColor(0x22EF8A8A);
            rmBg.setStroke(dp(1), 0x55EF8A8A);
            rmBg.setCornerRadius(dp(3));
            rm.setBackground(rmBg);
            rm.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
            rm.setOnClickListener(v -> {
                Set<String> cur = Targets.get(prefs);
                cur.remove(pkg);
                Targets.set(prefs, cur);
                status.setText("Removed " + Targets.label(this, pkg) + " from targets.");
                render();
            });
            r.addView(rm);
            appCard.addView(r);

            // Session actions (opt-in, per app): capture this app's login on a rooted device, restore it on
            // another rooted device so the app opens already logged in. Fingerprint clone makes the device
            // LOOK the same; this carries the actual session. Root-only — both buttons no-op-with-a-reason
            // if su is denied. Copying a session copies real account data, so it's a deliberate button, never
            // automatic.
            LinearLayout sess = new LinearLayout(this);
            sess.setOrientation(LinearLayout.HORIZONTAL);
            sess.setPadding(0, dp(8), 0, 0);
            final TextView sessStatus = new TextView(this);
            sessStatus.setTextSize(11);
            sessStatus.setTextColor(Theme.DIM);
            sessStatus.setPadding(0, dp(4), 0, 0);
            sess.addView(compactButton("Capture session", false,
                    v -> runSession(pkg, true, sessStatus)));
            View gap = new View(this);
            gap.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1));
            sess.addView(gap);
            sess.addView(compactButton("Restore session", false,
                    v -> runSession(pkg, false, sessStatus)));
            appCard.addView(sess);
            appCard.addView(sessStatus);

            wrap.addView(appCard);
        }
        return wrap;
    }

    /** Capture (or restore) a target app's login session off the UI thread, updating {@code statusView}.
     *  Root-only: a denied/absent su surfaces as a readable message, never a silent no-op or a crash. */
    private void runSession(final String pkg, final boolean capture, final TextView statusView) {
        final String verb = capture ? "Capturing" : "Restoring";
        statusView.setTextColor(Theme.DIM);
        statusView.setText(verb + " session…");
        new Thread(() -> {
            String msg; boolean ok = true;
            try {
                String out = capture ? SessionMigrator.capture(pkg) : SessionMigrator.restore(pkg);
                if (capture) {
                    msg = "Session captured → " + SessionMigrator.tarPath(pkg) + " (" + out + ")";
                } else {
                    // After restore the app was force-stopped; relaunch so it comes up on the new session.
                    try {
                        Intent li = getPackageManager().getLaunchIntentForPackage(pkg);
                        if (li != null) startActivity(li);
                    } catch (Throwable ignored) {}
                    msg = "Session restored (" + out + "). Relaunched " + Targets.label(this, pkg) + ".";
                }
            } catch (SessionMigrator.SessionException e) {
                ok = false; msg = (capture ? "Capture" : "Restore") + " failed: " + e.getMessage();
            }
            final String fMsg = msg; final boolean fOk = ok;
            runOnUiThread(() -> {
                statusView.setTextColor(fOk ? Theme.SAGE : Theme.RED);
                statusView.setText(fMsg);
                toast(fMsg);
            });
        }, "specter-session-" + (capture ? "cap" : "res")).start();
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

    /** One identifier: label + enable toggle on the top row; the value and two SMALL inline actions
     *  (Edit / ⟳ randomize) on the second row. Compact — no full-width button row — so 15 identifiers
     *  don't dominate the scroll. Disabled (toggle off) dims the value to signal it won't be applied. */
    private View identifierCard(final IdentityFields.Field f) {
        LinearLayout card = cardBox();

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = label(f.label);
        lab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        final Switch en = new Switch(this);
        en.setChecked(Toggles.isEnabled(prefs, f.key));
        head.addView(lab);
        head.addView(en);
        card.addView(head);

        // Value + inline actions row.
        LinearLayout valRow = new LinearLayout(this);
        valRow.setOrientation(LinearLayout.HORIZONTAL);
        valRow.setGravity(Gravity.CENTER_VERTICAL);
        valRow.setPadding(0, dp(3), 0, 0);
        final TextView val = value(profile.get(f.key));
        val.setPadding(0, 0, dp(8), 0);
        val.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (!Toggles.isEnabled(prefs, f.key)) val.setTextColor(Theme.DIM);
        valRow.addView(val);

        Button edit = compactButton("Edit", false, v -> {
            if (profile.isEmpty()) { toast("No identity yet — RANDOMIZE ALL first."); return; }
            editField(f, val);
        });
        Button rnd = compactButton("⟳", false, v -> {
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
        });
        valRow.addView(edit);
        valRow.addView(rnd);
        card.addView(valRow);

        // Toggling enable also dims/undims the value so it's clear what will actually apply.
        en.setOnCheckedChangeListener((v, on) -> {
            Toggles.set(prefs, f.key, on);
            val.setTextColor(on ? Theme.INK : Theme.DIM);
        });
        return card;
    }

    // Device fields are COUPLED — model/brand/device/fingerprint/carrier must all describe ONE real
    // device. Editing one alone (to clone a specific handset) is allowed, but we warn that the others
    // won't auto-update, so an inconsistent combo is itself a fingerprint. Identifiers (android_id/imei/
    // gsf/serial/MACs…) are independent and edit freely — exactly the "clone this id from another device"
    // case, with no coherence caveat.
    private static final java.util.Set<String> COUPLED_DEVICE_FIELDS = new java.util.HashSet<>(
            java.util.Arrays.asList("build_manufacturer", "build_model", "build_brand", "build_device",
                    "build_fingerprint", "sim_operator_name"));

    private void editField(final IdentityFields.Field f, final TextView val) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        final EditText in = new EditText(this);
        in.setText(profile.get(f.key));
        in.setTextColor(Theme.INK);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.addView(in);
        if (COUPLED_DEVICE_FIELDS.contains(f.key)) {
            TextView warn = new TextView(this);
            warn.setText("⚠ Device fields are coupled — changing only this one may not match the others "
                    + "(model/brand/device/fingerprint). To clone a whole device coherently, edit them all "
                    + "to the same real handset.");
            warn.setTextColor(Theme.AMBER);
            warn.setTextSize(11);
            warn.setPadding(0, dp(8), 0, 0);
            box.addView(warn);
        }
        new AlertDialog.Builder(this)
                .setTitle("Edit " + f.label)
                .setView(box)
                .setPositiveButton("Save", (d, w) -> {
                    String nv = in.getText().toString().trim();
                    // Format validation applies to identifiers (android_id/imei/…); device fields have no
                    // strict format (validate() returns true) so a hand-entered device value is allowed.
                    if (!Generators.validate(f.key, nv)) { toast("Invalid " + f.label + " format — not saved."); return; }
                    profile.put(f.key, nv); val.setText(nv);
                    status.setText(f.label + " set to a custom value — APPLY to push.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renderSettings() {
        // Target apps
        // Same polished per-app cards (icon + name + LSPosed-scope warning + red ✕) as the Identity tab —
        // one consistent target UI everywhere, not a plain text list here and rich cards there.
        content.addView(sectionLabel("Target apps"));
        content.addView(targetHeader());

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
        TextView desc = value("Coherent identity + deep signal spoofing applies on every identity: Build.* + "
                + "props, bootloader/radio/kernel, HARDWARE/BOARD, SoC, GPU/GLES, /proc/cpuinfo, sensor list "
                + "AND raw calibration values, verified-boot / lock state, US timezone + locale, boot count, "
                + "and battery capacity — all aligned to the applied device.");
        desc.setTextColor(Theme.DIM);
        info.addView(desc);
        content.addView(info);

        content.addView(sectionLabel("Protections"));
        for (Protections.P prot : Protections.ALL) content.addView(protectionRow(prot));

        // Advanced (root) — device-wide, persistent Magisk-module actions, NOT per-profile hook gates.
        // Kept in their own section + explicitly opt-in because they modify the system (a /vendor bind-mount),
        // can break unrelated apps (DRM HD playback), and persist across reboot until turned off.
        content.addView(sectionLabel("Advanced (root)"));
        content.addView(widevineL3Row());
        content.addView(gsfResetRow());
        // Location spoofing (proper hidemymock + Lockito-style GPS) is a planned later PR — not shown
        // as a dead toggle until it actually works.
    }

    /** Widevine L1->L3 toggle: installs/uninstalls the liboemcrypto bind-mount Magisk module via su, off-thread.
     *  This reaches the NATIVE OEMCrypto read the Java MediaDrm hook can't; opt-in because it breaks DRM HD
     *  playback and modifies /vendor. State is the actual module presence (persisted in prefs after a successful
     *  install/uninstall), not a cosmetic switch. */
    private View widevineL3Row() {
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
        TextView lab = label("Widevine L1 -> L3 (native)");
        lab.setTextColor(Theme.INK); lab.setTextSize(14);
        titleRow.addView(lab);
        final TextView chip = statusChip(prefs.getBoolean("widevine_l3", false));
        titleRow.addView(chip);
        txt.addView(titleRow);
        TextView d = value("Bind-mounts an empty liboemcrypto.so over the vendor lib so hardware Widevine drops "
                + "to software L3 — coherent for a native securityLevel/deviceUniqueId read (below the Java hook). "
                + "Device-wide, survives reboot. WARNING: breaks DRM HD playback (Netflix/Prime) while on. Root.");
        d.setTextColor(Theme.DIM); d.setTextSize(12); d.setTextIsSelectable(false);
        txt.addView(d);
        head.addView(txt);

        final Switch sw = new Switch(this);
        sw.setChecked(prefs.getBoolean("widevine_l3", false));
        sw.setOnCheckedChangeListener((v, on) -> {
            // widevineBusy suppresses the recursive listener fire when we programmatically revert the switch
            // on failure (setChecked below re-invokes THIS listener) — without it a failed install would
            // immediately kick off an uninstall (and vice-versa). Guard both the entry and the rollback.
            if (widevineBusy) return;
            widevineBusy = true;
            sw.setEnabled(false);
            status.setText(on ? "Installing Widevine L3 module…" : "Removing Widevine L3 module…");
            new Thread(() -> {
                String err = null;
                try {
                    if (on) WidevineL3.install(); else WidevineL3.uninstall();
                } catch (Throwable t) { err = t.getMessage(); }
                final String e = err;
                runOnUiThread(() -> {
                    // try/finally so widevineBusy is ALWAYS cleared — if a UI call below threw, the flag would
                    // otherwise stay true and permanently dead-lock the toggle.
                    try {
                        sw.setEnabled(true);
                        if (e == null) {
                            prefs.edit().putBoolean("widevine_l3", on).apply();
                            styleChip(chip, on);
                            status.setText(on
                                    ? "Widevine L3 installed — native reads report L3. Reboot to be safe. If a DRM app "
                                      + "or boot misbehaves, turn this OFF (or boot to safe mode to disable all modules)."
                                    : "Widevine L3 removed — reboot to fully restore hardware Widevine.");
                        } else {
                            // revert the switch to the real (unchanged) state — no cosmetic ON without the module.
                            // widevineBusy stays true across this setChecked so the re-fired listener no-ops.
                            sw.setChecked(!on);
                            styleChip(chip, !on);
                            status.setText("Widevine L3 " + (on ? "install" : "remove") + " failed: " + e);
                        }
                    } finally {
                        widevineBusy = false;
                    }
                });
            }).start();
        });
        head.addView(sw);
        card.addView(head);
        return card;
    }

    /** GSF/Google-identity reset: a one-shot destructive action (pm clear gms/gsf/vending + reboot) that makes
     *  GSF re-register a fresh device android_id — the server-side re-link anchor a per-app spoof can't touch.
     *  A button (not a toggle) behind a confirm, because it signs the device out of Google and forces a reboot. */
    private View gsfResetRow() {
        LinearLayout card = cardBox();
        TextView gsfLab = label("Reset Google identity (GSF)");   // match the Widevine card's header emphasis
        gsfLab.setTextColor(Theme.INK); gsfLab.setTextSize(14);
        card.addView(gsfLab);
        TextView d = value("Wipes Play Services + Services Framework + Play Store and reboots, so Google "
                + "re-registers a FRESH device id. Attacks the server-side re-link anchor that survives an app's "
                + "own data clear. WARNING: signs the device out of Google, drops Play state, and REBOOTS. Root.");
        d.setTextColor(Theme.DIM); d.setTextSize(12); d.setTextIsSelectable(false);
        card.addView(d);
        Button go = button("Reset GSF + reboot", false, v ->
                new AlertDialog.Builder(this)
                        .setTitle("Reset Google identity?")
                        .setMessage("This clears Play Services / Framework / Store and REBOOTS now. The device "
                                + "signs out of Google and re-registers a new id on boot. Continue?")
                        .setPositiveButton("Reset + reboot", (dl, w) -> {
                            status.setText("Resetting Google identity — device will reboot…");
                            new Thread(() -> {
                                String err = null;
                                try { GsfReset.reset(); }   // clears (checked) + reboots; on a real reboot this doesn't return
                                catch (Throwable t) { err = t.getMessage(); }
                                final String e = err;
                                // If we got here the reboot call RETURNED (device didn't reboot) — either the
                                // clears failed (e != null) or the reboot itself didn't take. Tell the user plainly;
                                // never leave the stale "device will reboot…" hanging.
                                runOnUiThread(() -> status.setText(e != null
                                        ? "GSF reset failed: " + e
                                        : "Google identity cleared, but the reboot didn't fire — reboot manually to finish."));
                            }).start();
                        })
                        .setNegativeButton("Cancel", null)
                        .show());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(8), 0, 0);
        go.setLayoutParams(lp);
        card.addView(go);
        return card;
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
        // The Diagnostics-logging protection gets a "View live trace" button that opens the live viewer
        // (reads the capture file this toggle writes). Only shown for that row — the others have no log.
        if ("trace".equals(prot.gateKey)) {
            Button view = button("View live trace", false, v ->
                    startActivity(new Intent(this, DiagnosticsActivity.class)));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMargins(0, dp(8), 0, 0);
            view.setLayoutParams(lp);
            card.addView(view);
        }
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
        // Mock-location HIDING is real (gated with the Hide-root protection): a driver/fraud SDK reading
        // Location.isFromMockProvider()/isMock() sees false. Show it as an active protection.
        LinearLayout mockCard = cardBox();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = label("Hide mock-location flag");
        lab.setTextColor(Theme.INK);
        lab.setTextSize(14);
        lab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(lab);
        boolean on = Protections.isOn(prefs, Protections.byKey("hide_root"));
        head.addView(statusChip(on));
        mockCard.addView(head);
        TextView d = value("Location.isFromMockProvider() / isMock() report false to scoped targets, so a "
                + "driver/fraud SDK can't detect a mocked GPS. Tied to the Hide-root toggle (Settings).");
        d.setTextColor(Theme.DIM);
        d.setTextSize(12);
        mockCard.addView(d);
        content.addView(mockCard);

        // Coordinate spoofing (lat/long -> LocationManager) is the planned next piece — marked clearly as
        // not-yet-built so it never reads as a working control (no fake UI).
        LinearLayout soon = cardBox();
        soon.addView(label("Coordinate spoofing"));
        TextView s = value("Planned — lat/long fields will drive a LocationManager/FusedLocation hook, "
                + "coordinate-matched with the profile's US region. Not built yet.");
        s.setTextColor(Theme.DIM);
        s.setTextSize(12);
        soon.addView(s);
        content.addView(soon);
    }

    // ---------- Saved (profile vault): save current, list, restore, delete ----------
    private void renderSaved() {
        content.addView(sectionLabel("Save current identity"));
        LinearLayout saveCard = cardBox();
        saveCard.addView(value("Save the currently-APPLIED identity so you can re-apply this exact device "
                + "later. Only appears after you APPLY (saving an un-applied identity is pointless). A unique "
                + "name is prefilled from the date/time — edit it if you like."));
        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveRow.addView(button("Save current to vault", true, v -> {
            if (profile.isEmpty()) { toast("No identity yet — RANDOMIZE ALL on the Identity tab first."); return; }
            if (appliedTargets.isEmpty()) { toast("Apply this identity to an app first — the vault only stores applied profiles."); return; }
            promptSaveName(appliedTargets);
        }));
        saveCard.addView(saveRow);
        content.addView(saveCard);

        // Import a profile shared by another user (a specter-profile-*.json in /sdcard/Download).
        content.addView(sectionLabel("Import a shared profile"));
        LinearLayout importCard = cardBox();
        TextView idesc = value("Import a profile someone shared with you (a specter-profile-*.json in "
                + "Download). It's validated + checksummed, then added to your vault to apply.");
        idesc.setTextColor(Theme.DIM);
        idesc.setTextSize(12);
        importCard.addView(idesc);
        LinearLayout importRow = new LinearLayout(this);
        importRow.setOrientation(LinearLayout.HORIZONTAL);
        importRow.addView(button("Import from Download", false, v -> promptImport()));
        importCard.addView(importRow);
        content.addView(importCard);

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
        search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        search.setOnEditorActionListener((v, actionId, ev) -> {
            android.view.inputmethod.InputMethodManager imm =
                    (android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(v.getWindowToken(), 0);
            v.clearFocus();
            return true;
        });
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
        // The MOST RECENT group (first in newest-first order) starts EXPANDED so the latest profiles are
        // visible without a tap; older groups collapse by default. Seed it into expandedGroups when it's a NEW
        // most-recent group (tracked by key) so a freshly-saved profile's day auto-opens too — while a user
        // collapse still sticks (we don't re-seed the same key). Skipped during search (q force-expands anyway).
        if (q.isEmpty() && !groups.isEmpty()) {
            String recent = groups.keySet().iterator().next();
            if (!recent.equals(seededRecentGroup)) {
                expandedGroups.add(recent);
                seededRecentGroup = recent;
            }
        }
        for (Map.Entry<String, java.util.List<Vault.Entry>> g : groups.entrySet()) {
            final String groupKey = g.getKey();
            // Collapsed BY DEFAULT (except the seeded most-recent group) — a group is open only if the user
            // expanded it (or a search is active, which force-expands so matches are visible).
            final boolean collapsed = !expandedGroups.contains(groupKey) && q.isEmpty();
            // Group header (tap to collapse/expand). Shows the date/day + count.
            TextView header = new TextView(this);
            header.setText((collapsed ? "▸  " : "▾  ") + prettyGroup(groupKey) + "   (" + g.getValue().size() + ")");
            header.setTextColor(Theme.GOLD);
            header.setTextSize(13);
            header.setPadding(dp(4), dp(10), dp(4), dp(4));
            header.setOnClickListener(v -> {
                if (expandedGroups.contains(groupKey)) expandedGroups.remove(groupKey);
                else expandedGroups.add(groupKey);
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
        // Subtly show which apps this profile was applied to (by name), so a saved entry documents its
        // real scope. Empty for legacy entries saved before this was recorded.
        if (!e.targets.isEmpty()) {
            StringBuilder names = new StringBuilder("Applied to: ");
            String[] pkgs = e.targets.split(",");
            for (int i = 0; i < pkgs.length; i++) {
                if (i > 0) names.append(", ");
                names.append(Targets.label(this, pkgs[i].trim()));
            }
            TextView tv = value(names.toString());
            tv.setTextColor(Theme.DIM);
            tv.setTextSize(11);
            card.addView(tv);
        }

        LinearLayout btns = new LinearLayout(this);
        btns.setOrientation(LinearLayout.HORIZONTAL);
        btns.addView(button("RESTORE", true, v -> restoreSaved(e.label)));
        btns.addView(compactButton("Share", false, v -> {
            new Thread(() -> {
                final String path = vault.exportToDownloads(e.label);
                runOnUiThread(() -> {
                    if (path != null) { toast("Exported to " + path); status.setText("Shared " + e.label + " -> " + path); }
                    else toast("Export failed.");
                });
            }).start();
        }));
        btns.addView(compactButton("Delete", false, v -> {
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

    /** Scan /sdcard/Download (via su — no storage permission) for shared specter-profile-*.json files and
     *  let the user pick one to import. Runs the listing off the UI thread, then shows a picker. */
    private void promptImport() {
        new Thread(() -> {
            final java.util.List<String> names = new java.util.ArrayList<>();
            Process pr = null;
            try {
                // Find both flavours: profiles SHARED from another Specter (specter-profile-*.json) and
                // harvests from Specter Lite (Specter-<mfr>-<model>-*.json), in Download/ and the
                // Download/Specter-exports/ subfolder Lite writes to. -M (mount-master) for the namespace.
                pr = Runtime.getRuntime().exec(new String[]{"su", "-M", "-c",
                        "ls -1t /sdcard/Download/specter-profile-*.json "
                        + "/sdcard/Download/Specter-*.json "
                        + "/sdcard/Download/Specter-exports/*.json 2>/dev/null"});
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(pr.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) { line = line.trim(); if (!line.isEmpty()) names.add(line); }
                }
                pr.waitFor();
            } catch (Throwable ignored) {}
            finally { if (pr != null) pr.destroy(); }
            runOnUiThread(() -> {
                if (names.isEmpty()) {
                    toast("No profile found. Put a shared specter-profile-*.json or a Specter Lite harvest "
                            + "(Specter-*.json) in Download or Download/Specter-exports.");
                    return;
                }
                final String[] labels = new String[names.size()];
                for (int i = 0; i < names.size(); i++) {
                    String full = names.get(i);
                    labels[i] = full.substring(full.lastIndexOf('/') + 1);   // basename for display
                }
                new AlertDialog.Builder(this)
                        .setTitle("Import which file?")
                        .setItems(labels, (d, which) -> {
                            java.io.File src = new java.io.File(names.get(which));
                            String err = vault.importError(src);
                            if (err != null) { toast("Import failed: " + err); return; }
                            // Strip whichever prefix this file has (shared export or Lite harvest) + .json.
                            String stem = labels[which].replace("specter-profile-", "")
                                    .replace("Specter-", "").replace(".json", "");
                            String label = vault.importFromFile(src, "imported-" + stem);
                            if (label != null) {
                                status.setText("Imported " + label + " — restore it to apply.");
                                toast("Imported into vault as " + label);
                                render();
                            } else toast("Import failed.");
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }).start();
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
        status.setText("Clearing + restoring " + labelStr + " to " + pkgs.size() + " app(s)…");
        new Thread(() -> {
            int cleared = 0, ok = 0; String lastErr = null; String clearErr = null;
            java.util.List<String> okPkgs = new ArrayList<>();
            for (String pkg : pkgs) {
                // Restore ALWAYS wipes data+cache first so the restored identity lands on a fresh install. If
                // the clear FAILS, we do NOT restore onto that app (a KNOWN device written over a dirty install
                // leaves the prior account's linkable state — the exact thing we're preventing).
                boolean clean = false;
                try { com.specter.module.gen.SessionMigrator.clearData(pkg); cleared++; clean = true; }
                catch (Throwable t) { clearErr = t.getMessage(); }
                if (!clean) continue;
                try { svc.apply(pkg, toApply); ok++; okPkgs.add(pkg); }
                catch (Throwable t) { lastErr = t.getMessage(); }
            }
            final int clearedN = cleared, okN = ok; final String err = lastErr, clrErr = clearErr;
            final boolean allClean = clearedN == pkgs.size();
            runOnUiThread(() -> {
                if (okN > 0) appliedTargets = String.join(",", okPkgs);   // only the apps it actually reached
                if (allClean) toast("🧹 Deep-cleaned cache + storage on all " + pkgs.size()
                        + " app(s) before restoring — no carry-over from the previous identity.");
                else if (clearedN > 0) toast("⚠️ Deep-cleaned only " + clearedN + "/" + pkgs.size()
                        + " app(s) — the rest were NOT restored (grant root in Magisk?).");
                String tail = (clrErr != null ? " Clear error: " + clrErr : "")
                        + (err != null ? " Apply error: " + err : "")
                        + (clrErr == null && err == null ? " Relaunch them to see it." : "");
                status.setText("Restored " + labelStr + " to " + okN + "/" + pkgs.size() + " app(s)." + tail);
            });
        }).start();
    }

    private void promptSaveName(final String targets) {
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
                            ? vault.save("", profile, targets)          // prefilled/empty -> pure timestamp label
                            : vault.save(typed, profile, targets);      // custom -> timestamp + sanitized name
                    status.setText("Saved as " + label);
                    toast("Saved to vault: " + label);
                    // If we're on the Saved tab, refresh the list so the new profile appears immediately (and
                    // its date group auto-expands via the seed logic). No-op from the Identity tab (post-APPLY).
                    if (tab == 1) render();
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
