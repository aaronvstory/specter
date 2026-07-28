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
    private com.specter.module.gen.AppDataVault appDataVault;
    private String activeVaultLabel = "";   // the fingerprint vault-label active now (set on save/restore of a
                                            // fingerprint) — an AppData capture links to it so restore re-applies
                                            // the SAME device identity + login together. "" if none saved yet.
    private android.widget.CheckBox saveOnRandomize;   // "save to vault after RANDOMIZE ALL"
    private boolean widevineBusy = false;              // guards the Widevine-L3 toggle's failure-rollback re-fire
    private boolean opBusy = false;                     // one guard for the destructive APPLY/RESTORE paths —
                                                        // both `pm clear` + write a profile, so two running at
                                                        // once could clear/overwrite each other's target. Set on
                                                        // entry, cleared when the worker finishes (UI thread).
    private com.specter.module.gen.ZygiskInstaller.Status zygiskStatus;   // native-layer health (async, null until checked)
    private boolean zygiskBusy = false;                // guards the native-layer install button
    private String vaultQuery = "";                     // Saved-tab search filter (label/device substring)
    private String appDataFilter = "";                  // Saved-logins filter: "" = all apps, else a pkg
    private String appliedTargets = "";                 // comma-sep pkgs the CURRENT profile was applied to
                                                        // ("" until Apply succeeds — vault saves only applied)
    private String appliedSig = "";                      // signature (android_id + target set) of the LAST
                                                        // successful apply — so a second APPLY of the SAME
                                                        // unchanged identity says "already applied" instead
                                                        // of silently re-doing it + re-prompting to save.
    private String seededRecentGroup = null;   // the most-recent date group we auto-expanded (so it opens once, per key)
    private final Set<String> expandedGroups = new java.util.HashSet<>();  // date groups the user EXPANDED
    private String monitoringPkg = null;       // the pkg currently being trace-monitored (null = not monitoring).
                                               // The button toggles "Monitor reads" -> "Monitoring…"; a second tap
                                               // (or the 30-min auto-stop) ends it and opens the read report.
    private final android.os.Handler monitorTimeout = new android.os.Handler(android.os.Looper.getMainLooper());
                                                                          // (Saved profiles collapse by default)
    private final Set<String> expandedApps = new java.util.HashSet<>();   // per-target cards whose actions the
                                                                          // user expanded (collapsed by default so
                                                                          // the 3 actions never overflow the row)

    private int dp(float v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        svc = new IdentityService(getApplicationContext());
        vault = new Vault(getApplicationContext());
        appDataVault = new com.specter.module.gen.AppDataVault(getFilesDir());
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        svc.setCountry(Country.of(prefs.getString("country", "US")));
        // Resume diagnostics capture if the user left it on (the service is START_STICKY but a full app
        // kill or reboot drops it — re-arm here so "on" stays on across launches).
        if (Protections.isOn(prefs, Protections.byKey("trace"))) DiagnosticsService.start(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);

        root.addView(appBar());

        // Status is now a transient banner (hidden until there's something to say), not a permanent empty
        // strip taking header space. Kept as the same `status` TextView so existing setText(...) calls work.
        status = new TextView(this);
        status.setTextColor(Theme.SOFT);
        status.setPadding(dp(Theme.S4), dp(Theme.S2), dp(Theme.S4), dp(Theme.S2));
        status.setTextSize(Theme.T_CAPTION);
        status.setVisibility(View.GONE);
        status.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                status.setVisibility(s.length() == 0 ? View.GONE : View.VISIBLE);
            }
        });
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        scroll.setClipToPadding(false);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, dp(Theme.S1), 0, dp(Theme.S6) * 2);   // side padding now lives on cards; big bottom pad
        scroll.addView(content);
        root.addView(scroll);

        root.addView(bottomNav());

        setContentView(root);
        regenerate();
        checkZygisk();
    }

    // ---- New chrome: a slim app bar + a bottom navigation bar (3 destinations). ----

    /** Slim 56dp app bar: logo + wordmark + version. Quiet — no actions crammed in. */
    private View appBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(dp(56));
        bar.setPadding(dp(Theme.S4), dp(Theme.S2), dp(Theme.S4), dp(Theme.S2));

        ImageView logo = new ImageView(this);
        logo.setImageResource(com.specter.module.R.drawable.ic_specter_logo);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(26), dp(26));
        lp.setMargins(0, 0, dp(Theme.S2), 0);
        logo.setLayoutParams(lp);
        bar.addView(logo);

        TextView word = new TextView(this);
        word.setText("Specter");
        word.setTextColor(Theme.INK);
        word.setTextSize(Theme.T_TITLE);
        word.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        word.setLetterSpacing(-0.02f);
        bar.addView(word);

        TextView ver = new TextView(this);
        String v = "";
        try { v = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Throwable ignored) {}
        ver.setText(v);
        ver.setTextColor(Theme.DIM);
        ver.setTextSize(Theme.T_CAPTION);
        LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        vlp.setMargins(dp(Theme.S2), dp(6), 0, 0);
        ver.setLayoutParams(vlp);
        ver.setGravity(Gravity.BOTTOM);
        bar.addView(ver);
        return bar;
    }

    private LinearLayout bottomNavBar;   // rebuilt tint on tab change

    /** Bottom navigation: 3 true destinations (Identity / Vault / Settings). Icon + label, gold when active. */
    private View bottomNav() {
        bottomNavBar = new LinearLayout(this);
        bottomNavBar.setOrientation(LinearLayout.HORIZONTAL);
        bottomNavBar.setBackgroundColor(Theme.CARD);
        bottomNavBar.setMinimumHeight(dp(60));
        // a hairline top edge so it reads as a bar
        View edge = new View(this);
        edge.setBackgroundColor(Theme.LINE);
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(edge, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
        wrap.addView(bottomNavBar);
        rebuildBottomNav();
        return wrap;
    }

    private final String[] NAV = {"Identity", "Vault", "Settings"};
    private void rebuildBottomNav() {
        if (bottomNavBar == null) return;
        bottomNavBar.removeAllViews();
        for (int i = 0; i < NAV.length; i++) {
            final int idx = i;
            boolean active = tab == i;
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER);
            item.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
            item.setBackground(ripple(0));
            item.setPadding(0, dp(Theme.S2), 0, dp(Theme.S2));
            ImageView ic = new ImageView(this);
            ic.setImageDrawable(navIcon(idx, dp(22)));
            ic.setColorFilter(active ? Theme.GOLD : Theme.DIM);
            item.addView(ic, new LinearLayout.LayoutParams(dp(24), dp(24)));
            TextView lbl = new TextView(this);
            lbl.setText(NAV[idx]);
            lbl.setTextSize(11);
            lbl.setTextColor(active ? Theme.GOLD : Theme.DIM);
            lbl.setPadding(0, dp(3), 0, 0);
            item.addView(lbl);
            item.setOnClickListener(v -> { if (tab != idx) { tab = idx; rebuildBottomNav(); render(); } });
            bottomNavBar.addView(item);
        }
    }

    /** Simple line icons for the 3 nav destinations. 0=Identity (person), 1=Vault (lock), 2=Settings (gear). */
    private android.graphics.drawable.Drawable navIcon(final int which, final int px) {
        return new StrokeIcon(px) {
            @Override void draw(android.graphics.Canvas c, android.graphics.Paint p, float s) {
                float cx = s * 0.5f;
                if (which == 0) {                     // person: head + shoulders
                    c.drawCircle(cx, s * 0.36f, s * 0.15f, p);
                    android.graphics.RectF r = new android.graphics.RectF(s * 0.22f, s * 0.56f, s * 0.78f, s * 0.92f);
                    c.drawArc(r, 200, 140, false, p);
                } else if (which == 1) {              // lock: body + shackle
                    android.graphics.RectF body = new android.graphics.RectF(s * 0.28f, s * 0.46f, s * 0.72f, s * 0.80f);
                    c.drawRoundRect(body, s * 0.06f, s * 0.06f, p);
                    android.graphics.RectF sh = new android.graphics.RectF(s * 0.36f, s * 0.26f, s * 0.64f, s * 0.58f);
                    c.drawArc(sh, 180, 180, false, p);
                } else {                              // gear-ish: circle + ticks
                    c.drawCircle(cx, cx, s * 0.16f, p);
                    for (int k = 0; k < 8; k++) {
                        double a = Math.PI * k / 4.0;
                        float x1 = cx + (float) Math.cos(a) * s * 0.30f, y1 = cx + (float) Math.sin(a) * s * 0.30f;
                        float x2 = cx + (float) Math.cos(a) * s * 0.40f, y2 = cx + (float) Math.sin(a) * s * 0.40f;
                        c.drawLine(x1, y1, x2, y2, p);
                    }
                }
            }
        };
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
        bar.addView(button("Randomize", false, v -> regenerate()));
        bar.addView(button("Apply", true, v -> apply()));
        col.addView(bar);

        // "Save to vault" checkbox: when checked, a successful APPLY prompts to save the identity (name
        // prefilled) so it can be restored later. We save on APPLY (not RANDOMIZE) so a vault entry always
        // represents an identity that actually reached at least one app — saving un-applied profiles is
        // pointless/misleading.
        saveOnRandomize = new android.widget.CheckBox(this);
        saveOnRandomize.setText("Save to vault after applying");
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
        autoClear.setText("Each target is wiped before every apply.");
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

    /** Full-width variant of {@link #compactButton} — used for the widest action (Monitor reads / Monitoring…)
     *  so its label never gets clipped or forces a sibling to wrap. */
    private Button wideButton(String text, boolean primary, View.OnClickListener onClick) {
        Button b = compactButton(text, primary, onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    /** Equal-weight half-width button — two of these share a row without either overflowing. */
    private Button halfButton(String text, View.OnClickListener onClick) {
        Button b = compactButton(text, false, onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        b.setGravity(Gravity.CENTER);
        return b;
    }

    /** Equal-weight third-width button — three of these share a row. */
    private Button thirdButton(String text, View.OnClickListener onClick) {
        Button b = compactButton(text, false, onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(6), dp(6), dp(6), dp(6));   // tighter h-padding so 3 short labels fit
        return b;
    }

    private void addGap(LinearLayout row) {
        View g = new View(this);
        g.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1));
        row.addView(g);
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
        if (opBusy) { toast("Busy — wait for the current apply/restore to finish."); return; }
        if (profile.isEmpty()) { toast("No identity yet — tap Randomize first."); return; }
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
        // Already-applied guard: re-APPLYING the SAME thing to the SAME targets is a no-op we skip (so we
        // don't needlessly wipe + re-prompt to save). Any DIFFERENT input always goes through the full clear
        // below. Sign off the EXACT bytes that get applied (`toApply`) + the target set — so editing ANY
        // field, flipping ANY identifier toggle, or changing ANY protection gate changes the signature and
        // a re-APPLY actually re-applies (the old android_id-only signature made all those edits a silent
        // no-op: "Already applied", nothing pushed).
        final String sig = applySignature(toApply, targets);
        if (!appliedSig.isEmpty() && appliedSig.equals(sig)) {
            String msg = "Already applied. Relaunch the app(s), or tap Randomize for a new one.";
            status.setText(msg); toast(msg);
            return;
        }
        // The wipe ends the session being monitored — flush that capture first. State teardown happens here
        // (UI thread); the su work runs as the FIRST thing on the wipe thread, so it completes before the wipe.
        final String flushPkg = beginFlushBeforeWipe(pkgs);
        opBusy = true;
        status.setText("Deep-cleaning + applying to " + pkgs.size() + " app(s)…");
        new Thread(() -> {
            finishFlush(flushPkg);   // disarm trace + archive the capture BEFORE anything is wiped
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
                try {
                    // Only claim "no carry-over" when EVERY target was actually cleared.
                    if (allClean) toast("Wiped and applied to " + pkgs.size() + " app(s).");
                    else if (clearedN > 0) toast("⚠️ Only " + clearedN + "/" + pkgs.size()
                            + " app(s) done — grant root in Magisk?");
                    String m = "Applied to " + okN + "/" + pkgs.size() + " app(s)."
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
                    render();   // refresh the summary card so its state flips to "Applied to N apps"
                } finally {
                    opBusy = false;
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

    /** Signature identifying "exactly THIS applied to this target set" — every key=value in the applied
     *  map (sorted, so order-independent) plus the sorted package set. Two Applies match iff the applied
     *  bytes AND the targets are both unchanged, which is exactly when a re-apply would be a true no-op.
     *  Hashing the whole applied map (not just android_id) means a field edit, an identifier toggle, or a
     *  protection-gate change all shift the signature and make the next APPLY actually push. */
    private String applySignature(Map<String, String> applied, Set<String> targets) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(applied).entrySet())
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        return sb.append('|').append(new java.util.TreeSet<>(targets)).toString();
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    /** True if it's safe to show a dialog / touch views right now. A background su task can finish AFTER the
     *  user rotated or backed out — calling .show() on a finishing/destroyed Activity throws BadTokenException.
     *  Guard every dialog raised from a runOnUiThread completion with this. */
    private boolean alive() { return !isFinishing() && !isDestroyed(); }

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
        LinearLayout c = card();
        // Amber left-edge accent so it reads as a warning without shouting (a thin colored bar, not a border).
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFF2A2418);   // warm amber-tinted surface
        bg.setCornerRadius(dp(Theme.R_CARD));
        c.setBackground(bg);
        c.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S4), dp(Theme.S3));
        boolean stale = zygiskStatus.installed;   // installed but wrong version vs missing entirely
        TextView lab = new TextView(this);
        lab.setText(stale ? "Native layer out of date" : "Native layer not installed");
        lab.setTextColor(Theme.AMBER); lab.setTextSize(Theme.T_BODY);
        lab.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        c.addView(lab);
        TextView d = new TextView(this);
        d.setText(stale
                ? "An update is available. Install it to keep deep signals covered."
                : "Without it, some deep signals still read the real device.");
        d.setTextColor(Theme.SOFT); d.setTextSize(Theme.T_CAPTION);
        d.setPadding(0, dp(Theme.S1), 0, dp(Theme.S3));
        c.addView(d);
        c.addView(secondaryButton(stale ? "Update native layer" : "Install native layer", v -> installZygisk()));
        return c;
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
                    if (alive()) new AlertDialog.Builder(this)
                            .setTitle("Native layer installed")
                            .setMessage("It activates on boot. Reboot now?")
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

    private boolean detailsExpanded = false;   // Identity screen: is the full field editor expanded?

    private void renderIdentity() {
        // Summary-first: what the identity IS + the one primary action, THEN targets, THEN (collapsed)
        // the full field editor. A user applies in 2 taps; power users expand details when they want them.
        content.addView(identitySummaryCard());

        // Target apps — one group card, plain rows.
        content.addView(section("Target apps"));
        content.addView(targetAppsCard());

        // Full device + identifier editor, collapsed behind a disclosure row.
        content.addView(section("Identity details"));
        LinearLayout discCard = card();
        int n = countEnabledIdentifiers();
        discCard.addView(row(detailsExpanded ? "Hide details" : "Show all fields",
                n + " identifier" + (n == 1 ? "" : "s") + " included · tap to " + (detailsExpanded ? "collapse" : "customize"),
                chevronTrailing(detailsExpanded), v -> { detailsExpanded = !detailsExpanded; render(); }));
        content.addView(discCard);
        if (detailsExpanded) {
            content.addView(section("Device"));
            content.addView(deviceSpecCard());
            content.addView(section("Identifiers"));
            for (IdentityFields.Field f : IdentityFields.IDENTIFIERS) content.addView(identifierCard(f));
        }
    }

    /** The hero card: current identity summary + the primary Apply action + Generate-another. This is the
     *  "one dominant task per screen" the design brief calls for. */
    private View identitySummaryCard() {
        LinearLayout c = card();
        c.setPadding(dp(Theme.S4), dp(Theme.S4), dp(Theme.S4), dp(Theme.S4));

        // Title + state
        TextView title = new TextView(this);
        title.setText("Current identity");
        title.setTextColor(Theme.SOFT);
        title.setTextSize(Theme.T_LABEL);
        c.addView(title);

        String device = deviceString();
        String carrier = profile.getOrDefault("sim_operator_name", "");
        TextView dev = new TextView(this);
        dev.setText(device + (carrier.isEmpty() ? "" : "  ·  " + carrier));
        dev.setTextColor(Theme.INK);
        dev.setTextSize(Theme.T_HEADING);
        dev.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        dev.setPadding(0, dp(Theme.S1), 0, 0);
        c.addView(dev);

        boolean applied = !appliedTargets.isEmpty()
                && appliedSig.equals(applySignature(enabledProfile(), Targets.get(prefs)));
        TextView state = new TextView(this);
        Set<String> tgts = Targets.get(prefs);
        state.setText(applied ? "Applied to " + tgts.size() + " app" + (tgts.size() == 1 ? "" : "s")
                : "New identity · not applied yet");
        state.setTextColor(applied ? Theme.SAGE : Theme.SOFT);
        state.setTextSize(Theme.T_CAPTION);
        state.setPadding(0, dp(Theme.S1), 0, dp(Theme.S3));
        c.addView(state);

        // Primary: Apply to N apps
        int napps = tgts.size();
        c.addView(primaryButton(napps == 0 ? "Select target apps" : "Apply to " + napps + " app" + (napps == 1 ? "" : "s"),
                v -> { if (napps == 0) startActivity(new Intent(this, AppPickerActivity.class)); else apply(); }));
        // Secondary quiet: generate another
        c.addView(textButton("Generate another identity", Theme.SOFT, v -> regenerate()));
        return c;
    }

    /** Target apps as ONE group card: a "Change" disclosure row, then a plain row per selected app that
     *  expands its actions (Monitor / Save-AppData). Replaces the old one-card-per-app "card soup". */
    private View targetAppsCard() {
        LinearLayout c = card();
        final Set<String> targets = Targets.get(prefs);
        // Change row (opens the picker)
        TextView changeVal = new TextView(this);
        changeVal.setText(targets.isEmpty() ? "None" : targets.size() + " selected");
        changeVal.setTextColor(Theme.SOFT); changeVal.setTextSize(Theme.T_LABEL);
        changeVal.setPadding(0, 0, dp(Theme.S2), 0);
        LinearLayout changeTrailing = new LinearLayout(this);
        changeTrailing.setOrientation(LinearLayout.HORIZONTAL);
        changeTrailing.setGravity(Gravity.CENTER_VERTICAL);
        changeTrailing.addView(changeVal);
        changeTrailing.addView(chevronTrailing(false));
        c.addView(row("Change apps", null, changeTrailing,
                v -> startActivity(new Intent(this, AppPickerActivity.class))));

        if (targets.isEmpty()) return c;
        for (final String pkg : targets) {
            c.addView(hairlineInset());
            c.addView(targetAppRow(pkg));
        }
        return c;
    }

    /** One target-app row inside the group card: icon + name + (live-monitor dot) + expand chevron + remove.
     *  Expanding reveals the per-app actions (Monitor reads / Save AppData / Restore AppData). */
    private View targetAppRow(final String pkg) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        final boolean expanded = expandedApps.contains(pkg);
        final boolean monitoring = pkg.equals(monitoringPkg);

        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(56));
        r.setPadding(dp(Theme.S4), dp(Theme.S2), dp(Theme.S2), dp(Theme.S2));
        r.setBackground(ripple(0));
        View.OnClickListener toggle = v -> {
            if (expandedApps.contains(pkg)) expandedApps.remove(pkg); else expandedApps.add(pkg);
            render();
        };
        r.setOnClickListener(toggle);

        try {
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(getPackageManager().getApplicationIcon(pkg));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(30), dp(30));
            ilp.setMargins(0, 0, dp(Theme.S3), 0);
            iv.setLayoutParams(ilp);
            r.addView(iv);
        } catch (Throwable ignored) {}

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(Targets.label(this, pkg));
        name.setTextColor(Theme.INK); name.setTextSize(Theme.T_BODY);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        col.addView(name);
        TextView sub = new TextView(this);
        sub.setText(monitoring ? "● Monitoring reads" : pkg);
        sub.setTextColor(monitoring ? Theme.GOLD : Theme.DIM); sub.setTextSize(Theme.T_CAPTION);
        col.addView(sub);
        // scoped-in-LSPosed warning (async)
        final TextView warn = new TextView(this);
        warn.setTextSize(Theme.T_CAPTION); warn.setTextColor(Theme.RED); warn.setVisibility(View.GONE);
        col.addView(warn);
        new Thread(() -> { final boolean scoped = Targets.isScoped(pkg);
            runOnUiThread(() -> { if (!scoped) { warn.setText("Not enabled in LSPosed"); warn.setVisibility(View.VISIBLE); } }); }).start();
        r.addView(col);

        ImageView chev = new ImageView(this);
        chev.setImageDrawable(icChevron(expanded ? 1 : 0, dp(18)));
        chev.setColorFilter(monitoring ? Theme.GOLD : Theme.DIM);
        chev.setLayoutParams(new LinearLayout.LayoutParams(dp(28), dp(28)));
        r.addView(chev);
        r.addView(iconButton(icClose(dp(16)), Theme.DIM, v -> {
            Set<String> cur = Targets.get(prefs); cur.remove(pkg); Targets.set(prefs, cur);
            toast("Removed " + Targets.label(this, pkg)); render();
        }));
        box.addView(r);

        if (expanded) {
            final TextView sessStatus = new TextView(this);
            sessStatus.setTextSize(Theme.T_CAPTION); sessStatus.setTextColor(Theme.DIM);
            LinearLayout actions = new LinearLayout(this);
            actions.setOrientation(LinearLayout.VERTICAL);
            actions.setPadding(dp(Theme.S4), 0, dp(Theme.S4), dp(Theme.S3));
            LinearLayout row1 = new LinearLayout(this); row1.setOrientation(LinearLayout.HORIZONTAL);
            row1.addView(wideButton(monitoring ? "Stop monitoring" : "Monitor reads", monitoring, v -> toggleMonitor(pkg, sessStatus)));
            actions.addView(row1);
            LinearLayout row2 = new LinearLayout(this); row2.setOrientation(LinearLayout.HORIZONTAL);
            row2.setPadding(0, dp(Theme.S2), 0, 0);
            Button save = halfButton("Save AppData", v -> runSession(pkg, true, sessStatus));
            View gap = new View(this); gap.setLayoutParams(new LinearLayout.LayoutParams(dp(Theme.S2), 1));
            Button rest = halfButton("Restore AppData", v -> runSession(pkg, false, sessStatus));
            row2.addView(save); row2.addView(gap); row2.addView(rest);
            actions.addView(row2);
            actions.addView(sessStatus);
            box.addView(actions);
        }
        return box;
    }

    private int countEnabledIdentifiers() {
        int n = 0;
        for (IdentityFields.Field f : IdentityFields.IDENTIFIERS)
            if (Toggles.isEnabled(prefs, f.key)) n++;
        return n;
    }

    /** A right-chevron trailing view (rotated down when expanded) for disclosure rows. */
    private View chevronTrailing(boolean expanded) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(icChevron(expanded ? 1 : 0, dp(18)));
        iv.setColorFilter(Theme.DIM);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(24), dp(24)));
        return iv;
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
                if (profile.isEmpty()) { toast("No identity yet — tap Randomize first."); return; }
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
            TextView none = value("None yet — tap Change to pick apps.");
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
            // Chevron: expands/collapses this card's actions. Collapsed by default so three actions can never
            // overflow the header row (the old flat row split "Paste login" in half when Monitoring… widened).
            final boolean expanded = expandedApps.contains(pkg);
            final boolean isMonitoring = pkg.equals(monitoringPkg);
            TextView chev = new TextView(this);
            chev.setText(expanded ? "⌄" : "›");
            chev.setTextSize(18);
            chev.setTextColor(isMonitoring ? Theme.GOLD : Theme.DIM);   // gold hint when a session is live
            chev.setGravity(Gravity.CENTER);
            chev.setLayoutParams(new LinearLayout.LayoutParams(dp(32), dp(32)));
            View.OnClickListener toggle = v -> {
                if (expandedApps.contains(pkg)) expandedApps.remove(pkg); else expandedApps.add(pkg);
                render();
            };
            chev.setOnClickListener(toggle);
            col.setOnClickListener(toggle);   // tapping the name area also expands (bigger hit target)
            r.addView(chev);
            r.addView(rm);
            appCard.addView(r);

            // A one-line hint under the header when collapsed + a session is live, so state is visible without
            // expanding. (Apple pattern: the collapsed row still tells you what's happening.)
            if (!expanded && isMonitoring) {
                TextView live = new TextView(this);
                live.setText("● Monitoring reads — tap to stop");
                live.setTextSize(11);
                live.setTextColor(Theme.GOLD);
                live.setPadding(dp(40), dp(2), 0, 0);
                live.setOnClickListener(toggle);
                appCard.addView(live);
            }

            if (expanded) {
                final TextView sessStatus = new TextView(this);
                sessStatus.setTextSize(11);
                sessStatus.setTextColor(Theme.DIM);
                sessStatus.setPadding(0, dp(6), 0, 0);

                // Actions stacked so they NEVER overflow: Monitor reads on its own row (its "Monitoring…"
                // label is the widest), then Save/Restore AppData sharing the next row equally.
                LinearLayout row1 = new LinearLayout(this);
                row1.setOrientation(LinearLayout.HORIZONTAL);
                row1.setPadding(0, dp(8), 0, 0);
                // MONITOR READS: record every device signal THIS app reads during a real session, then open a
                // spoofed/real report. Tap to start, tap (or a 30-min auto-stop) to end + archive the capture.
                Button mon = wideButton(isMonitoring ? "Monitoring… (tap to stop)" : "Monitor reads",
                        isMonitoring, v -> toggleMonitor(pkg, sessStatus));
                row1.addView(mon);
                appCard.addView(row1);

                LinearLayout row2 = new LinearLayout(this);
                row2.setOrientation(LinearLayout.HORIZONTAL);
                row2.setPadding(0, dp(6), 0, 0);
                // Save / Restore AppData: capture the app's whole logged-in data (databases, prefs, files,
                // cookies) to a tarball, and restore it later — the app comes back already logged in. Root-only;
                // copies real account data, so each is a deliberate button. (Formerly "Copy/Paste login".)
                Button save = halfButton("Save AppData", v -> runSession(pkg, true, sessStatus));
                View gap = new View(this);
                gap.setLayoutParams(new LinearLayout.LayoutParams(dp(8), 1));
                Button restore = halfButton("Restore AppData", v -> runSession(pkg, false, sessStatus));
                row2.addView(save); row2.addView(gap); row2.addView(restore);
                appCard.addView(row2);
                appCard.addView(sessStatus);
            }

            wrap.addView(appCard);
        }
        return wrap;
    }

    /** Start/stop trace-monitoring what {@code pkg} reads. YOU decide the window: tap to start (arms trace on
     *  the app's live profile + starts the capture service), tap again to stop + open the read report. A
     *  30-minute auto-stop is a safety net so a forgotten capture can't log for days. */
    private void toggleMonitor(final String pkg, final TextView statusView) {
        if (pkg.equals(monitoringPkg)) { stopMonitor(); return; }
        if (monitoringPkg != null) { toast("Already monitoring " + Targets.label(this, monitoringPkg) + " — stop it first."); return; }
        statusView.setTextColor(Theme.DIM);
        statusView.setText("Starting monitor…");
        new Thread(() -> {
            String err = armTrace(pkg, true);   // add "trace":"1" to the app's applied profile (su)
            runOnUiThread(() -> {
                if (err != null) { statusView.setTextColor(Theme.RED); statusView.setText("Monitor failed: " + err); return; }
                monitoringPkg = pkg;
                DiagnosticsService.start(this);   // background capture -> diag.log
                statusView.setTextColor(Theme.SAGE);
                statusView.setText("Monitoring — relaunch " + Targets.label(this, pkg) + ", use it, then tap Stop.");
                // 30-min auto-stop safety net.
                monitorTimeout.removeCallbacksAndMessages(null);
                monitorTimeout.postDelayed(() -> { if (pkg.equals(monitoringPkg)) { toast("Monitor auto-stopped after 30 min."); stopMonitor(); } }, 30 * 60 * 1000L);
                render();   // redraw so the button shows "Monitoring…"
            });
        }, "specter-mon-start").start();
    }

    /** Stop the active monitor (user-initiated, or the 30-min auto-stop): disarm trace, stop the capture,
     *  archive the raw capture so the NEXT monitor can't overwrite it, then open the read report.
     *  The pre-wipe flush does NOT go through here — it needs the work to complete before the wipe runs,
     *  so it uses {@link #beginFlushBeforeWipe} + {@link #finishFlush} instead. */
    private void stopMonitor() {
        final String pkg = monitoringPkg;
        if (pkg == null) return;
        monitorTimeout.removeCallbacksAndMessages(null);
        monitoringPkg = null;
        DiagnosticsService.stop(this);
        status.setText("Stopping monitor…");
        new Thread(() -> {
            final String msg = disarmAndArchive(pkg);
            runOnUiThread(() -> {
                status.setText(msg);
                render();
                startActivity(new Intent(this, DiagnosticsActivity.class));   // reads diag.log -> spoofed/real report
            });
        }, "specter-mon-stop").start();
    }

    /** The actual stop work, off the UI thread: disarm the trace flag, then archive the capture. Returns the
     *  user-facing result line. A FAILED disarm is reported, not swallowed — it means the app is still being
     *  logged even though the UI says the monitor stopped, which is exactly the state you'd want to know about. */
    private String disarmAndArchive(String pkg) {
        final String disarmErr = armTrace(pkg, false);   // remove "trace":"1" so we don't keep logging that app
        final String saved = archiveCapture(pkg);
        return "Monitor stopped for " + Targets.label(this, pkg) + "."
                + (saved != null ? " Capture saved → " + saved : " No reads captured.")
                + (disarmErr != null ? " ⚠️ Trace still armed (" + disarmErr + ") — re-APPLY to clear it." : "");
    }

    /** Copy the raw capture out to /sdcard/Download with a timestamped name. logcat -f TRUNCATES the single
     *  fixed diag.log, so back-to-back captures would otherwise clobber each other; the archive is what makes
     *  each session's reads recoverable. Returns the dest path, or null if nothing was written.
     *
     *  Waits for the capture to actually stop first. DiagnosticsService.stop() only ASKS the service to stop,
     *  and its onDestroy pkills the logcat child on yet another thread — so copying immediately can catch a
     *  still-writing logcat and archive a truncated file. We poll for the capture process to disappear
     *  (bounded, ~2s) instead of folding the kill into this command: `pkill -f` matches on the full cmdline,
     *  and this command necessarily CONTAINS the log path, so a kill here terminates our own su
     *  (verified on-device: rc=143, nothing copied). Let the service own the kill; we just wait for it.
     *
     *  `cp -n` (no-clobber) keeps two captures landing in the same millisecond from overwriting each other. */
    private String archiveCapture(String pkg) {
        if (!com.specter.module.gen.RootWriter.validPkg(pkg)) return null;
        String dest = "/sdcard/Download/specter-reads-" + pkg + "-" + System.currentTimeMillis() + ".log";
        try {
            com.specter.module.gen.RootWriter.SuShell sh = new com.specter.module.gen.RootWriter.SuShell();
            waitForCaptureToStop(sh);
            // -s: only copy a NON-EMPTY capture, so a monitor that recorded nothing leaves no misleading file.
            int code = sh.run("[ -s '" + DiagnosticsCmd.LOG_PATH + "' ] && cp -n '" + DiagnosticsCmd.LOG_PATH
                            + "' '" + dest + "' && chmod 644 '" + dest + "'", "");
            return code == 0 ? dest : null;
        } catch (Exception e) { return null; }
    }

    /** Poll (bounded) until no logcat capture is WRITING our log, so the archive can't catch a partial file.
     *  Best-effort: if it's still up after the budget we archive anyway — a slightly-short capture beats none.
     *  The probe only greps the process table, so it never signals anything and can't self-kill.
     *
     *  Match `logcat` AND `-f <log>` — i.e. the writer only. Matching the bare log path instead would also
     *  hit every READER of it: DiagnosticsActivity polls with `tail -c 1048576 <log>` every 2s while the
     *  report is open, and Magisk's su-logger echoes that command in its own cmdline. Verified on-device:
     *  with the viewer open the broad pattern reads 1 forever (so every archive burned the full budget for
     *  nothing), while this one correctly reads 0. */
    private void waitForCaptureToStop(com.specter.module.gen.RootWriter.Shell sh) {
        for (int i = 0; i < 10; i++) {   // ~2s budget; the service's pkill normally lands well inside this
            try {
                // The [l] bracket keeps the grep's OWN cmdline from matching itself.
                String out = sh.runCapture("ps -Ao args | grep '[l]ogcat' | grep -c -- '-f "
                        + DiagnosticsCmd.LOG_PATH + "' || true");
                if (out != null && out.trim().startsWith("0")) return;
            } catch (Exception e) { return; }   // can't probe -> don't stall the stop
            try { Thread.sleep(200); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); return; }
        }
    }

    /** UI-thread half of the pre-wipe flush: if a monitor is running on a package that's about to be wiped,
     *  tear the monitor state down NOW (so the button + 30-min timer are correct immediately) and return that
     *  package so the caller's worker can finish the flush. Returns null when there's nothing to flush.
     *  Call this on the UI thread, BEFORE spawning the wipe thread; pass the result to {@link #finishFlush}. */
    private String beginFlushBeforeWipe(List<String> pkgs) {
        final String pkg = monitoringPkg;
        if (pkg == null || !pkgs.contains(pkg)) return null;
        monitorTimeout.removeCallbacksAndMessages(null);
        monitoringPkg = null;
        DiagnosticsService.stop(this);   // tear the capture down before the wipe thread starts
        toast("Saving the in-progress read capture for " + Targets.label(this, pkg) + " first.");
        render();   // the button must stop saying "Monitoring…" now, not at some later redraw
        return pkg;
    }

    /** Worker half of the pre-wipe flush — MUST run on the wipe thread, before the first clearData(), so the
     *  disarm + archive genuinely COMPLETE before the wipe touches anything. (Spawning a second thread here
     *  would only race the wipe, which is the bug this shape exists to avoid.) No report is opened: the user
     *  asked to APPLY, not to read a trace. */
    private void finishFlush(String pkg) {
        if (pkg == null) return;
        final String msg = disarmAndArchive(pkg);
        // Toast, not the status line: the caller (APPLY/RESTORE) owns `status` from here on.
        runOnUiThread(() -> toast(msg));
    }

    /** Add/remove {@code "trace":"1"} in the app's live profile file via su. Returns null on success, else an error. */
    private String armTrace(String pkg, boolean on) {
        if (!com.specter.module.gen.RootWriter.validPkg(pkg)) return "invalid package";
        String path = com.specter.module.gen.RootWriter.PROFILE_DIR + "/" + pkg + ".json";
        // on: insert "trace":"1", after the opening brace IF not already present. off: strip it. Idempotent seds.
        String cmd = on
                ? "grep -q '\"trace\"' " + path + " || sed -i 's/^{/{\"trace\":\"1\",/' " + path
                : "sed -i 's/\"trace\":\"1\",//; s/,\"trace\":\"1\"//' " + path;
        try {
            int code = new com.specter.module.gen.RootWriter.SuShell().run(cmd, "");
            return code == 0 ? null : "su exited " + code + " (is there an applied profile for this app? APPLY first)";
        } catch (Exception e) { return e.getMessage(); }
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
                    // Snapshot the WHOLE logged-in state: the login tarball AND the fingerprint the app is
                    // CURRENTLY running under — so an already-logged-in app whose fingerprint was never saved
                    // can still be captured in one action, and restore re-applies both together.
                    String fpLabel = ensureFingerprintSaved(pkg);   // saves the live applied fingerprint if new
                    String device = deviceStringForPkg(pkg);
                    String label = (fpLabel != null && !fpLabel.isEmpty())
                            ? uniqueAppDataLabel(fpLabel + "-" + shortPkg(pkg))
                            : uniqueAppDataLabel(Vault.makeLabel(shortPkg(pkg)));
                    String verr = appDataVault.save(label, pkg, fpLabel == null ? "" : fpLabel, device);
                    msg = verr == null
                            ? "Saved " + Targets.label(this, pkg) + " login (" + out + ")"
                                + (fpLabel != null && !fpLabel.isEmpty() ? " + fingerprint " + fpLabel : "")
                            : "Captured, but vault save failed: " + verr + " (staged at " + SessionMigrator.tarPath(pkg) + ")";
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

    /** Ensure the fingerprint the app is CURRENTLY running under is in the vault, and return its label.
     *  Reads the app's live on-device profile JSON (what the hook actually applies), so this works even for an
     *  app that was logged in before its identity was ever saved. If that exact identity (by android_id) is
     *  already a saved fingerprint, reuse it — no duplicate. Returns null if there's no applied profile to read
     *  (then the AppData just saves unlinked). Runs blocking su — call off the UI thread. */
    private String ensureFingerprintSaved(String pkg) {
        Map<String, String> live = readLiveProfile(pkg);
        if (live == null || live.isEmpty()) return null;
        String liveAid = live.get("android_id");
        // Already saved? match on android_id (the unique per-identity key). Reuse that label.
        if (liveAid != null && !liveAid.isEmpty()) {
            for (Vault.Entry e : vault.list()) {
                Map<String, String> saved = vault.load(e.label);
                if (saved != null && liveAid.equals(saved.get("android_id"))) { activeVaultLabel = e.label; return e.label; }
            }
        }
        // New identity -> save it as a fingerprint, named after the app so it's recognizable in the list.
        String label = vault.save(shortPkg(pkg), live, pkg);
        if (label != null) activeVaultLabel = label;
        return label;
    }

    /** Read the live applied profile JSON for {@code pkg} (what the hook is running) via su, as a flat map.
     *  Null if the file is absent/unreadable. Uses the GeerGit-proof flat parser (no org.json). */
    private Map<String, String> readLiveProfile(String pkg) {
        if (!com.specter.module.gen.RootWriter.validPkg(pkg)) return null;
        String path = com.specter.module.gen.RootWriter.PROFILE_DIR + "/" + pkg + ".json";
        try {
            String json = new com.specter.module.gen.RootWriter.SuShell().runCapture("cat '" + path + "' 2>/dev/null");
            if (json == null || json.trim().isEmpty()) return null;
            Map<String, String> m = new LinkedHashMap<>();
            com.specter.module.SpoofLogic.parseFlatJson(json, m);
            m.remove(com.specter.module.SpoofLogic.TRUE_ANDROID_ID_KEY);   // internal shadow key, not a profile field
            m.remove("trace");   // a transient monitor flag, not part of the identity
            return m.isEmpty() ? null : m;
        } catch (Exception e) { return null; }
    }

    /** Device string from an app's live profile (for an AppData entry captured off the on-device fingerprint). */
    private String deviceStringForPkg(String pkg) {
        Map<String, String> live = readLiveProfile(pkg);
        if (live == null) return deviceString();
        String s = (cap(live.getOrDefault("build_manufacturer", "")) + " "
                + live.getOrDefault("build_model", "")).trim();
        return s.isEmpty() ? "(unknown device)" : s;
    }

    /** Short, filename-safe tail of a package for labelling (e.g. com.doordash.driverapp -> driverapp). */
    private static String shortPkg(String pkg) {
        if (pkg == null || pkg.isEmpty()) return "app";
        int dot = pkg.lastIndexOf('.');
        String tail = dot >= 0 && dot + 1 < pkg.length() ? pkg.substring(dot + 1) : pkg;
        StringBuilder b = new StringBuilder();
        for (char c : tail.toCharArray()) if (Character.isLetterOrDigit(c)) b.append(c);
        return b.length() == 0 ? "app" : b.toString();
    }

    /** A vault-label-safe base, made unique against existing AppData entries by appending -2, -3, … */
    private String uniqueAppDataLabel(String base) {
        // sanitize to the AppDataVault label charset (letters/digits/_.-)
        StringBuilder b = new StringBuilder();
        for (char c : base.toCharArray())
            if (Character.isLetterOrDigit(c) || c == '_' || c == '.' || c == '-') b.append(c);
        String clean = b.length() == 0 ? "appdata" : b.toString();
        if (clean.length() > 70) clean = clean.substring(0, 70);
        String label = clean;
        for (int i = 2; appDataVault.get(label) != null && i < 1000; i++) label = clean + "-" + i;
        return label;
    }

    /** Human device string for an AppData entry, from the current in-memory profile. */
    private String deviceString() {
        String mfr = profile.getOrDefault("build_manufacturer", "");
        String model = profile.getOrDefault("build_model", "");
        String s = (cap(mfr) + " " + model).trim();
        return s.isEmpty() ? "(unknown device)" : s;
    }

    private static String cap(String s) {
        return (s == null || s.isEmpty()) ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private View sectionLabel(String s) {
        // Route legacy callers to the quiet v2 section header (SOFT, sentence case, proper inset) — so every
        // not-yet-rewritten screen loses the gold-uppercase "developer dashboard" shout automatically.
        return section(s);
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
        final Switch en = new Switch(this); tintSwitch(en);
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
            if (profile.isEmpty()) { toast("No identity yet — tap Randomize first."); return; }
            editField(f, val);
        });
        Button rnd = compactButton("⟳", false, v -> {
            if (profile.isEmpty()) { toast("No identity yet — tap Randomize first."); return; }
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
            warn.setText("⚠ Device fields go together — edit them all to match one real phone.");
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
        // Target apps live on the Identity screen (where you apply) — not duplicated here. Settings is
        // preferences + protections only.

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
        TextView desc = value("Every device signal is aligned to the applied phone.");
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
        TextView lab = label("Downgrade Widevine to L3");
        lab.setTextColor(Theme.INK); lab.setTextSize(14);
        titleRow.addView(lab);
        final TextView chip = statusChip(prefs.getBoolean("widevine_l3", false));
        titleRow.addView(chip);
        txt.addView(titleRow);
        TextView d = value("Reports software DRM, device-wide. Breaks HD Netflix/Prime while on.");
        d.setTextColor(Theme.DIM); d.setTextSize(12); d.setTextIsSelectable(false);
        txt.addView(d);
        head.addView(txt);

        final Switch sw = new Switch(this); tintSwitch(sw);
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
                                    ? "Widevine set to L3 — reboot to be safe."
                                    : "Widevine restored — reboot to finish.");
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
        TextView gsfLab = label("Reset Google identity");   // match the Widevine card's header emphasis
        gsfLab.setTextColor(Theme.INK); gsfLab.setTextSize(14);
        card.addView(gsfLab);
        TextView d = value("Gives the device a fresh Google id. Signs out of Google and reboots.");
        d.setTextColor(Theme.DIM); d.setTextSize(12); d.setTextIsSelectable(false);
        card.addView(d);
        Button go = button("Reset GSF + reboot", false, v ->
                new AlertDialog.Builder(this)
                        .setTitle("Reset Google identity?")
                        .setMessage("Signs out of Google, resets the device id, and reboots now. Continue?")
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

        final Switch sw = new Switch(this); tintSwitch(sw);
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

    // The ON/OFF chip next to a switch is a redundant duplicate indicator (the switch position already says
    // state) — the design brief calls it out as clutter. statusChip() now returns a zero-size hidden view so
    // every caller drops the chip without touching each call site, and styleChip() is a no-op.
    private TextView statusChip(boolean on) {
        TextView chip = new TextView(this);
        chip.setVisibility(View.GONE);
        chip.setLayoutParams(new LinearLayout.LayoutParams(0, 0));
        return chip;
    }

    private void styleChip(TextView chip, boolean on) { /* no-op: chips removed (see statusChip) */ }

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
        TextView d = value("Mocked GPS reads as real. Follows the Hide-root toggle.");
        d.setTextColor(Theme.DIM);
        d.setTextSize(12);
        mockCard.addView(d);
        content.addView(mockCard);

        // Coordinate spoofing (lat/long -> LocationManager) is the planned next piece — marked clearly as
        // not-yet-built so it never reads as a working control (no fake UI).
        LinearLayout soon = cardBox();
        soon.addView(label("Coordinate spoofing"));
        TextView s = value("Planned — set a fixed location. Not built yet.");
        s.setTextColor(Theme.DIM);
        s.setTextSize(12);
        soon.addView(s);
        content.addView(soon);
    }

    // ---------- Saved (profile vault): save current, list, restore, delete ----------
    /** The "Saved logins (AppData)" section: an app-filter chip row + one row per saved login tarball,
     *  newest first. Each row shows the app, date, size, and the linked fingerprint; RESTORE re-applies that
     *  fingerprint + the login together. Rendered above the fingerprint list on the Saved tab. */
    private void renderSavedAppData() {
        java.util.List<com.specter.module.gen.AppDataVault.Entry> all = appDataVault.list(null);
        content.addView(sectionLabel("Saved logins (AppData)"));
        if (all.isEmpty()) {
            TextView t = value("No saved logins yet. Expand a target on the Identity tab → Save AppData.");
            t.setTextColor(Theme.DIM); t.setTextSize(12);
            LinearLayout c = cardBox(); c.addView(t); content.addView(c);
            return;
        }
        // App-filter chips: All + one per distinct pkg present. Tapping re-renders the whole tab (cheap).
        java.util.LinkedHashSet<String> pkgs = new java.util.LinkedHashSet<>();
        for (com.specter.module.gen.AppDataVault.Entry e : all) pkgs.add(e.pkg);
        if (pkgs.size() > 1) {
            LinearLayout chips = new LinearLayout(this);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            chips.setPadding(0, 0, 0, dp(4));
            chips.addView(filterChip("All", appDataFilter.isEmpty(), v -> { appDataFilter = ""; render(); }));
            for (final String p : pkgs) {
                View gap = new View(this); gap.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1));
                chips.addView(gap);
                chips.addView(filterChip(Targets.label(this, p), p.equals(appDataFilter),
                        v -> { appDataFilter = p; render(); }));
            }
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(chips);
            content.addView(hs);
        }
        int shown = 0;
        for (com.specter.module.gen.AppDataVault.Entry e : all) {
            if (!appDataFilter.isEmpty() && !appDataFilter.equals(e.pkg)) continue;
            content.addView(appDataRow(e));
            shown++;
        }
        if (shown == 0) {
            TextView t = value("No saved logins for that app.");
            t.setTextColor(Theme.DIM); content.addView(t);
        }
    }

    /** One saved-login row: app icon + name, date · size · linked fingerprint, and a RESTORE + delete. */
    private View appDataRow(final com.specter.module.gen.AppDataVault.Entry e) {
        LinearLayout card = cardBox();
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        try {
            android.graphics.drawable.Drawable ic = getPackageManager().getApplicationIcon(e.pkg);
            ImageView iv = new ImageView(this); iv.setImageDrawable(ic);
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(26), dp(26));
            ilp.setMargins(0, 0, dp(10), 0); iv.setLayoutParams(ilp); top.addView(iv);
        } catch (Throwable ignored) {}
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = value(Targets.label(this, e.pkg)); name.setTextColor(Theme.INK);
        col.addView(name);
        String meta = fmtDate(e.savedAt) + " · " + fmtSize(e.sizeBytes)
                + (e.fingerprint.isEmpty() ? " · no linked fingerprint" : " · " + e.fingerprint);
        TextView sub = value(meta); sub.setTextColor(Theme.DIM); sub.setTextSize(11);
        col.addView(sub);
        top.addView(col);
        card.addView(top);

        // Primary action full-width, secondary actions as equal thirds below — never overflows.
        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        row1.setPadding(0, dp(8), 0, 0);
        row1.addView(wideButton("Restore login", true, v -> restoreAppData(e)));
        card.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        row2.setPadding(0, dp(6), 0, 0);
        Button rename = thirdButton("Rename", v -> promptRenameAppData(e.label));
        Button export = thirdButton("Export", v -> exportAppData(e));
        Button del = thirdButton("Delete", v -> {
            if (appDataVault.delete(e.label)) { toast("Deleted saved login."); render(); }
            else toast("Could not delete.");
        });
        del.setTextColor(Theme.RED);
        row2.addView(rename); addGap(row2); row2.addView(export); addGap(row2); row2.addView(del);
        card.addView(row2);
        return card;
    }

    /** Restore a saved login: re-apply its LINKED fingerprint (so the device identity matches), then copy the
     *  tarball back to staging and run the app-data restore, and relaunch. All off the UI thread. */
    private void restoreAppData(final com.specter.module.gen.AppDataVault.Entry e) {
        if (opBusy) { toast("Busy — wait for the current operation to finish."); return; }
        status.setText("Restoring " + Targets.label(this, e.pkg) + " login…");
        new Thread(() -> {
            StringBuilder note = new StringBuilder();
            // 1) Re-apply the linked fingerprint to this app (device identity must match the captured login),
            //    if one is linked and still in the vault. Non-fatal if missing — the login restore still runs.
            if (!e.fingerprint.isEmpty()) {
                Map<String, String> fp = vault.load(e.fingerprint);
                if (fp != null && !fp.isEmpty()) {
                    try {
                        // Applying wipes the app first (clean base), then writes the fingerprint profile.
                        com.specter.module.gen.SessionMigrator.clearData(e.pkg);
                        svc.apply(e.pkg, fp);
                        note.append("fingerprint ").append(e.fingerprint).append(" applied; ");
                    } catch (Throwable t) { note.append("fingerprint apply failed (").append(t.getMessage()).append("); "); }
                } else note.append("linked fingerprint missing; ");
            }
            // 2) Copy the vaulted tarball back to staging, then run the safe app-data restore.
            String err = appDataVault.restoreToStaging(e.label);
            if (err == null) {
                try {
                    com.specter.module.gen.SessionMigrator.restore(e.pkg);
                    note.append("login restored");
                    try {
                        Intent li = getPackageManager().getLaunchIntentForPackage(e.pkg);
                        if (li != null) startActivity(li);
                    } catch (Throwable ignored) {}
                } catch (com.specter.module.gen.SessionMigrator.SessionException se) {
                    err = se.getMessage();
                }
            }
            final String fErr = err; final String fNote = note.toString();
            runOnUiThread(() -> {
                if (fErr == null) { status.setText("Restored " + Targets.label(this, e.pkg) + " — " + fNote + "."); toast("Login restored."); }
                else { status.setText("Restore failed: " + fErr); toast("Restore failed: " + fErr); }
            });
        }, "specter-appdata-restore").start();
    }

    /** Rename a saved fingerprint (keeps the timestamp prefix) + relink any AppData that pointed at it, so the
     *  bundle stays intact. */
    private void promptRenameFingerprint(final String oldLabel) {
        final EditText in = new EditText(this);
        in.setText(labelName(oldLabel));
        in.setHint("New name");
        in.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Rename fingerprint")
                .setView(in)
                .setPositiveButton("Rename", (d, w) -> {
                    String neu = vault.rename(oldLabel, in.getText().toString());
                    if (neu == null) { toast("Rename failed."); return; }
                    int relinked = appDataVault.relinkFingerprint(oldLabel, neu);
                    if (activeVaultLabel.equals(oldLabel)) activeVaultLabel = neu;
                    status.setText("Renamed to " + neu + (relinked > 0 ? " (" + relinked + " login(s) relinked)" : ""));
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Rename a saved login (AppData). Keeps its link to the fingerprint (stored inside the meta). */
    private void promptRenameAppData(final String oldLabel) {
        final EditText in = new EditText(this);
        in.setText(labelName(oldLabel));
        in.setHint("New name");
        in.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Rename saved login")
                .setView(in)
                .setPositiveButton("Rename", (d, w) -> {
                    String neu = appDataVault.rename(oldLabel, in.getText().toString());
                    if (neu == null) { toast("Rename failed."); return; }
                    status.setText("Renamed login to " + neu);
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Export a saved login bundle (tarball + meta) to /sdcard/Download so it can be moved to another device. */
    private void exportAppData(final com.specter.module.gen.AppDataVault.Entry e) {
        status.setText("Exporting " + Targets.label(this, e.pkg) + " login…");
        new Thread(() -> {
            final String dest = appDataVault.exportToDownloads(e.label);
            runOnUiThread(() -> {
                if (dest != null) { toast("Exported → " + dest); status.setText("Exported login → " + dest); }
                else { toast("Export failed (grant root?)."); status.setText("Export failed for " + e.label); }
            });
        }, "specter-appdata-export").start();
    }

    private static String fmtSize(long b) {
        if (b >= 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        if (b >= 1024) return String.format(java.util.Locale.US, "%.0f KB", b / 1024.0);
        return b + " B";
    }

    private String fmtDate(long millis) {
        if (millis <= 0) return "(unknown date)";
        return new java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.US).format(new java.util.Date(millis));
    }

    /** A small selectable filter chip (gold when active). */
    private View filterChip(String text, boolean active, View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setSingleLine(true);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        t.setTextColor(active ? Theme.ON_GOLD : Theme.SOFT);
        t.setBackground(pill(active ? Theme.GOLD : Theme.CARD2, active ? Theme.GOLD : Theme.BTN_EDGE));
        t.setOnClickListener(onClick);
        return t;
    }

    private void renderSaved() {
        content.addView(sectionLabel("Save current identity"));
        LinearLayout saveCard = cardBox();
        saveCard.addView(value("Save the applied identity to re-apply it later."));
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
        TextView idesc = value("Add a profile someone shared with you (from Download).");
        idesc.setTextColor(Theme.DIM);
        idesc.setTextSize(12);
        importCard.addView(idesc);
        LinearLayout importRow = new LinearLayout(this);
        importRow.setOrientation(LinearLayout.HORIZONTAL);
        importRow.addView(button("Import from Download", false, v -> promptImport()));
        importCard.addView(importRow);
        content.addView(importCard);

        // Saved logins (AppData) — a captured login tarball, LINKED to the fingerprint it was taken under.
        // Restoring one re-applies that fingerprint AND the login together, so the app opens already signed in.
        renderSavedAppData();

        content.addView(sectionLabel("Saved fingerprints"));
        savedListHolder = null;   // fresh holder per full render (content was cleared by render())
        java.util.List<Vault.Entry> all = vault.list();
        if (all.isEmpty()) {
            LinearLayout empty = cardBox();
            TextView t = value("No saved fingerprints yet.");
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

    /** The user-typed NAME part of a "MMDDYY-Day-HHMM[-Name][-N]" label (empty if the entry was unnamed).
     *  Everything after the HHMM (3rd dash-part) is the name; a trailing "-2/-3" collision suffix is dropped. */
    private String labelName(String label) {
        String[] p = label.split("-");
        if (p.length <= 3) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 3; i < p.length; i++) {
            if (i == p.length - 1 && p[i].matches("\\d{1,3}")) break;   // drop the -2/-3 dedup suffix
            if (sb.length() > 0) sb.append('-');
            sb.append(p[i]);
        }
        String name = sb.toString().replace('_', ' ').trim();
        // Legacy entries (saved before the doubling bug was fixed) have an embedded second timestamp at the
        // START of the name, e.g. "072726 Mon 1533 A LEHMAN" — strip a leading "MMDDYY Day HHMM" so the old
        // rows read as cleanly as new ones.
        name = name.replaceFirst("^\\d{6}\\s+[A-Za-z]{3}\\s+\\d{4}\\s*", "").trim();
        return name;
    }

    /** "MMDDYY-Day-HHMM…" -> "Mon 07/26 · 2:53 PM" for the saved-row subtitle. */
    private String labelWhen(String label) {
        String[] p = label.split("-");
        if (p.length < 3 || p[0].length() != 6 || p[2].length() != 4) return prettyGroup(label);
        String date = p[1] + " " + p[0].substring(0, 2) + "/" + p[0].substring(2, 4);
        int hh, mm;
        try { hh = Integer.parseInt(p[2].substring(0, 2)); mm = Integer.parseInt(p[2].substring(2, 4)); }
        catch (Exception e) { return date; }
        String ampm = hh < 12 ? "AM" : "PM";
        int h12 = hh % 12; if (h12 == 0) h12 = 12;
        return date + "  ·  " + h12 + ":" + String.format(java.util.Locale.US, "%02d", mm) + " " + ampm;
    }

    private View savedRow(final Vault.Entry e) {
        LinearLayout card = cardBox();
        // Clean hierarchy: the friendly NAME (the label's name part, or the device if unnamed) is the title;
        // a readable date/time is the subtitle — instead of dumping the raw "072726-Mon-1453-Name" filename.
        String name = labelName(e.label);
        TextView lab = label(name.isEmpty() ? e.device : name);
        lab.setTextColor(Theme.INK);
        lab.setTextSize(15);
        card.addView(lab);
        TextView dev = value(labelWhen(e.label) + (name.isEmpty() ? "" : "  ·  " + e.device));
        dev.setTextColor(Theme.SOFT);
        dev.setTextSize(12);
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
        btns.addView(button("Restore", true, v -> restoreSaved(e.label)));
        btns.addView(compactButton("Rename", false, v -> promptRenameFingerprint(e.label)));
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
                        boolean gone = vault.delete(e.label);
                        status.setText(gone ? "Deleted " + e.label : "Could not delete " + e.label);
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
                        + "/sdcard/Download/specter-login-*.tar "     // AppData login bundles
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
                if (!alive()) return;   // su listing finished after the user left — don't raise a dialog
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
                            final java.io.File src = new java.io.File(names.get(which));
                            final String base = labels[which];
                            status.setText("Importing…");
                            // A specter-login-*.tar is an AppData (login) bundle → the AppData vault; anything
                            // else is a fingerprint profile → the fingerprint vault.
                            if (base.startsWith("specter-login-") && base.endsWith(".tar")) {
                                new Thread(() -> {
                                    final String lbl = appDataVault.importFromDownloads(src);
                                    runOnUiThread(() -> {
                                        if (lbl != null) { status.setText("Imported login " + lbl); toast("Imported login " + lbl); render(); }
                                        else { status.setText("Login import failed (grant root? valid bundle?)"); toast("Login import failed."); }
                                    });
                                }, "specter-appdata-import").start();
                                return;
                            }
                            // Strip whichever prefix this file has (shared export or Lite harvest) + .json.
                            final String stem = base.replace("specter-profile-", "")
                                    .replace("Specter-", "").replace(".json", "");
                            new Thread(() -> {
                                final Vault.ImportResult r = vault.importOnce(src, "imported-" + stem);
                                runOnUiThread(() -> {
                                    if (r.ok()) {
                                        status.setText("Imported " + r.label + " — restore it to apply.");
                                        toast("Imported into vault as " + r.label);
                                        render();
                                    } else {
                                        status.setText("Import failed: " + r.error);
                                        toast("Import failed: " + r.error);
                                    }
                                });
                            }, "specter-import").start();
                        })
                        .setNegativeButton("Cancel", null)
                        .show();
            });
        }).start();
    }

    /** Load a saved profile into the current identity AND apply it to the selected target app(s). */
    private void restoreSaved(final String labelStr) {
        if (opBusy) { toast("Busy — wait for the current apply/restore to finish."); return; }
        final Map<String, String> saved = vault.load(labelStr);
        if (saved == null || saved.isEmpty()) { toast("Could not read that saved profile."); return; }
        profile = new LinkedHashMap<>(saved);
        activeVaultLabel = labelStr;   // restoring a fingerprint makes IT the active one for AppData linkage
        appliedSig = "";   // a restored identity is new state — force the next APPLY/RESTORE to actually run
        Set<String> targets = Targets.get(prefs);
        if (targets.isEmpty()) {
            status.setText("Restored " + labelStr + " — pick a target app (Settings), then it will apply.");
            toast("Restored into the current identity. Select a target app to apply.");
            return;
        }
        final Map<String, String> toApply = enabledProfile();   // applies protection gates too
        final List<String> pkgs = new ArrayList<>(targets);
        final String sig = applySignature(toApply, targets);   // record on full success so an immediate APPLY
                                                                // of the just-restored identity is a no-op (not
                                                                // a needless second deep-clean of the same apps)
        // Same as APPLY: tear the monitor state down here, finish the su work on the wipe thread (see apply()).
        final String flushPkg = beginFlushBeforeWipe(pkgs);
        opBusy = true;
        status.setText("Clearing + restoring " + labelStr + " to " + pkgs.size() + " app(s)…");
        new Thread(() -> {
            finishFlush(flushPkg);   // disarm trace + archive the capture BEFORE anything is wiped
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
                try {
                    if (okN > 0) appliedTargets = String.join(",", okPkgs);   // only the apps it actually reached
                    if (okN == pkgs.size()) appliedSig = sig;   // every target restored -> a repeat APPLY is a no-op
                    if (allClean) toast("Wiped and restored to " + pkgs.size() + " app(s).");
                    else if (clearedN > 0) toast("⚠️ Only " + clearedN + "/" + pkgs.size()
                            + " app(s) done — grant root in Magisk?");
                    String tail = (clrErr != null ? " Clear error: " + clrErr : "")
                            + (err != null ? " Apply error: " + err : "")
                            + (clrErr == null && err == null ? " Relaunch them to see it." : "");
                    status.setText("Restored " + labelStr + " to " + okN + "/" + pkgs.size() + " app(s)." + tail);
                } finally {
                    opBusy = false;
                }
            });
        }).start();
    }

    private void promptSaveName(final String targets) {
        if (!alive()) return;   // may be called from apply()'s background completion after the user left
        final EditText in = new EditText(this);
        // Prefill with the DEVICE name (e.g. "Galaxy Note 20") so the saved entry reads like a phone, not a
        // timestamp. Leaving it blank saves under a pure date/time label. (The old prefill was the raw
        // timestamp label, which — if the minute rolled over before Save — got treated as a custom name and
        // doubled up into "072726-Mon-1453-072726_Mon_1452___…". Prefilling a real name avoids that entirely.)
        String devName = (profile.getOrDefault("build_manufacturer", "") + " "
                + profile.getOrDefault("build_model", "")).trim();
        in.setHint("Name (optional) — blank uses the date/time");
        in.setText(devName);
        in.setSelection(in.getText().length());
        in.setTextColor(Theme.INK);
        in.setHintTextColor(Theme.DIM);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        new AlertDialog.Builder(this)
                .setTitle("Save identity as")
                .setMessage("A name is prefilled — edit it if you like.")
                .setView(in)
                .setPositiveButton("Save", (d, w) -> {
                    // Whatever's typed is the name (blank -> pure date/time label). save() always prefixes the
                    // timestamp itself, so the label sorts + groups by date regardless of the name.
                    String typed = in.getText().toString().trim();
                    String label = vault.save(typed, profile, targets);
                    if (label == null) { status.setText("Save failed — could not write the vault file."); toast("Save failed."); return; }
                    activeVaultLabel = label;   // this fingerprint is now the active one an AppData capture links to
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
        // Legacy card, restyled to the v2 design language (soft radius, no heavy border, 16dp side inset)
        // so the not-yet-rewritten screens (Vault/Settings) match the new Identity screen automatically.
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.CARD);
        bg.setCornerRadius(dp(Theme.R_CARD));
        c.setBackground(bg);
        c.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S4), dp(Theme.S3));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(Theme.S4), 0, dp(Theme.S4), dp(Theme.S3));
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

    // ============================================================================================
    // DESIGN SYSTEM v2 — one card, one row, one button system, drawn icons, press feedback.
    // (Replaces the "card-soup" + emoji-icon + ad-hoc-spacing look. Uses Theme's S*/T_*/R_* scales.)
    // ============================================================================================

    /** A grouped container card: soft radius, subtle surface, NO heavy border (surface contrast is enough).
     *  Put plain hairline-separated rows inside — not more cards. This is THE card; stop nesting cards. */
    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.CARD);
        bg.setCornerRadius(dp(Theme.R_CARD));
        c.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(dp(Theme.S4), 0, dp(Theme.S4), dp(Theme.S3));   // 16 side inset, 12 between cards
        c.setLayoutParams(lp);
        return c;
    }

    /** A 1px hairline separator for use BETWEEN rows inside a card (inset from the left like iOS lists). */
    private View hairlineInset() {
        View v = new View(this);
        v.setBackgroundColor(Theme.LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f)));
        lp.setMargins(dp(Theme.S4), 0, 0, 0);
        v.setLayoutParams(lp);
        return v;
    }

    /** A section header ABOVE a card: sentence-case, SOFT, medium weight — quiet, not a gold shout. */
    private TextView section(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextColor(Theme.SOFT);
        t.setTextSize(Theme.T_LABEL);
        t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(dp(Theme.S4) + dp(Theme.S1), dp(Theme.S5), dp(Theme.S4), dp(Theme.S2));
        return t;
    }

    /** A tappable list row inside a card: title (+ optional subtitle) on the left, an optional trailing view
     *  (chevron / switch / value) on the right, ripple feedback, ≥52dp tall. Tapping runs onClick. */
    private LinearLayout row(String title, String subtitle, View trailing, View.OnClickListener onClick) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(52));
        r.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S4), dp(Theme.S3));
        if (onClick != null) { r.setBackground(ripple(0)); r.setOnClickListener(onClick); }

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        t.setText(title); t.setTextColor(Theme.INK); t.setTextSize(Theme.T_BODY);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        col.addView(t);
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = new TextView(this);
            s.setText(subtitle); s.setTextColor(Theme.SOFT); s.setTextSize(Theme.T_CAPTION);
            s.setPadding(0, dp(1), 0, 0);
            col.addView(s);
        }
        r.addView(col);
        if (trailing != null) r.addView(trailing);
        return r;
    }

    // ---- Buttons: four kinds, all ≥48dp touch, ripple, consistent radius ----

    /** Full-width PRIMARY action (gold fill, dark ink). The one dominant action per screen. */
    private View primaryButton(String text, View.OnClickListener onClick) {
        return themedButton(text, Theme.GOLD, Theme.ON_GOLD, 0, true, onClick);
    }

    /** Full-width SECONDARY action (quiet surface, ink text, hairline edge). */
    private View secondaryButton(String text, View.OnClickListener onClick) {
        return themedButton(text, Theme.CARD2, Theme.INK, Theme.LINE_HI, true, onClick);
    }

    /** A quiet TEXT button (no fill) — for tertiary actions like "Generate another". */
    private View textButton(String text, int color, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(text); b.setTextColor(color); b.setTextSize(Theme.T_BODY);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(Theme.S3), dp(Theme.S3), dp(Theme.S3), dp(Theme.S3));
        b.setBackground(ripple(dp(Theme.R_CTRL)));
        b.setOnClickListener(onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    private View themedButton(String text, int fill, int textColor, int edge, boolean fullWidth, View.OnClickListener onClick) {
        TextView b = new TextView(this);
        b.setText(text); b.setTextColor(textColor); b.setTextSize(Theme.T_BODY);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S4), dp(Theme.S3));
        GradientDrawable base = new GradientDrawable();
        base.setColor(fill);
        base.setCornerRadius(dp(Theme.R_CTRL));
        if (edge != 0) base.setStroke(Math.max(1, dp(1)), edge);
        // ripple on press, over the filled base
        android.content.res.ColorStateList rc = android.content.res.ColorStateList.valueOf(0x33FFFFFF);
        b.setBackground(new android.graphics.drawable.RippleDrawable(rc, base, null));
        b.setOnClickListener(onClick);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                fullWidth ? ViewGroup.LayoutParams.MATCH_PARENT : ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        b.setLayoutParams(lp);
        return b;
    }

    /** A 48x48 icon button: a drawn glyph (see icon()) centered in a ripple circle — for row-trailing actions
     *  like remove/overflow. No emoji. */
    private ImageView iconButton(android.graphics.drawable.Drawable glyph, int tint, View.OnClickListener onClick) {
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(glyph);
        iv.setColorFilter(tint);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(12);
        iv.setPadding(pad, pad, pad, pad);
        iv.setBackground(ripple(dp(Theme.R_PILL)));
        iv.setOnClickListener(onClick);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return iv;
    }

    /** A translucent ripple background (for rows / icon buttons) with the given corner radius. */
    private android.graphics.drawable.Drawable ripple(int radius) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(0xFFFFFFFF);
        mask.setCornerRadius(radius);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(0x22FFFFFF), null, mask);
    }

    // ---- Drawn vector icons (no emoji). Each returns a Drawable sized to `px`, stroked in the current tint. ----

    /** A chevron (›). dir: 0=right, 1=down. Used for "expandable" / "navigates" affordances. */
    private android.graphics.drawable.Drawable icChevron(final int dir, final int px) {
        return new StrokeIcon(px) {
            @Override void draw(android.graphics.Canvas c, android.graphics.Paint p, float s) {
                float a = s * 0.34f, b = s * 0.66f;
                android.graphics.Path path = new android.graphics.Path();
                if (dir == 1) { path.moveTo(a, s * 0.42f); path.lineTo(s * 0.5f, s * 0.60f); path.lineTo(b, s * 0.42f); }
                else { path.moveTo(s * 0.42f, a); path.lineTo(s * 0.60f, s * 0.5f); path.lineTo(s * 0.42f, b); }
                c.drawPath(path, p);
            }
        };
    }

    /** A close/remove (×). */
    private android.graphics.drawable.Drawable icClose(final int px) {
        return new StrokeIcon(px) {
            @Override void draw(android.graphics.Canvas c, android.graphics.Paint p, float s) {
                float a = s * 0.32f, b = s * 0.68f;
                c.drawLine(a, a, b, b, p); c.drawLine(b, a, a, b, p);
            }
        };
    }

    /** A plus (+) for "add". */
    private android.graphics.drawable.Drawable icPlus(final int px) {
        return new StrokeIcon(px) {
            @Override void draw(android.graphics.Canvas c, android.graphics.Paint p, float s) {
                float a = s * 0.28f, b = s * 0.72f, m = s * 0.5f;
                c.drawLine(a, m, b, m, p); c.drawLine(m, a, m, b, p);
            }
        };
    }

    /** An overflow (⋯) three-dot for row menus. */
    private android.graphics.drawable.Drawable icMore(final int px) {
        return new StrokeIcon(px) {
            @Override void draw(android.graphics.Canvas c, android.graphics.Paint p, float s) {
                p.setStyle(android.graphics.Paint.Style.FILL);
                float r = s * 0.05f, y = s * 0.5f;
                c.drawCircle(s * 0.28f, y, r, p); c.drawCircle(s * 0.5f, y, r, p); c.drawCircle(s * 0.72f, y, r, p);
            }
        };
    }

    /** Base class: a Drawable that draws a stroked glyph on a square canvas. Colour comes from setColorFilter. */
    private abstract class StrokeIcon extends android.graphics.drawable.Drawable {
        final int px; final android.graphics.Paint paint;
        int filter = 0xFFFFFFFF;
        StrokeIcon(int px) {
            this.px = px;
            paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(android.graphics.Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(2f, px * 0.09f));
            paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }
        abstract void draw(android.graphics.Canvas c, android.graphics.Paint p, float sizePx);
        @Override public void draw(android.graphics.Canvas canvas) {
            paint.setColor(filter);
            android.graphics.Rect b = getBounds();
            canvas.save();
            canvas.translate(b.left, b.top);
            draw(canvas, paint, Math.min(b.width(), b.height()));
            canvas.restore();
        }
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public void setColorFilter(int color, android.graphics.PorterDuff.Mode mode) { filter = color; invalidateSelf(); }
        @Override public void setAlpha(int a) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
        @Override public int getIntrinsicWidth() { return px; }
        @Override public int getIntrinsicHeight() { return px; }
    }

    /** A properly-tinted Switch (matches gold accent when on; quiet when off) — not the raw platform teal. */
    private android.widget.Switch themedSwitch(boolean checked, android.widget.CompoundButton.OnCheckedChangeListener l) {
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setChecked(checked);
        tintSwitch(sw);
        sw.setOnCheckedChangeListener(l);
        return sw;
    }

    /** Tint an existing Switch to the gold accent (on) / quiet grey (off) — kills the raw platform teal that
     *  reads as "borrowed from another app". Apply to every Switch created inline. */
    private void tintSwitch(android.widget.Switch sw) {
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new android.content.res.ColorStateList(states, new int[]{Theme.GOLD, 0xFFCFCFD6}));
        sw.setTrackTintList(new android.content.res.ColorStateList(states, new int[]{0x66E7B94E, 0x33FFFFFF}));
    }
}
