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
    // Auto-dismiss for the transient status banner (so it never sits pinned as permanent text).
    private final android.os.Handler statusClear = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable statusClearRun = () -> { if (status != null) status.setText(""); };
    private final Button[] tabButtons = new Button[4];
    private int tab = 0;            // 0=Identity 1=Saved 2=Settings 3=Location
    private Vault vault;
    private com.specter.module.gen.AppDataVault appDataVault;
    private String activeVaultLabel = "";   // the fingerprint vault-label active now (set on save/restore of a
                                            // fingerprint) — an AppData capture links to it so restore re-applies
                                            // the SAME device identity + login together. "" if none saved yet.
    private boolean widevineBusy = false;              // guards the Widevine-L3 toggle's failure-rollback re-fire
    private boolean opBusy = false;                     // one guard for the destructive APPLY/RESTORE paths —
                                                        // both `pm clear` + write a profile, so two running at
                                                        // once could clear/overwrite each other's target. Set on
                                                        // entry, cleared when the worker finishes (UI thread).
    private com.specter.module.gen.ZygiskInstaller.Status zygiskStatus;   // native-layer health (async, null until checked)
    private boolean zygiskBusy = false;                // guards the native-layer install button
    private boolean zygiskSyncFailed = false;          // the silent stale-layer auto-sync failed -> show the banner
    private String vaultQuery = "";                     // Saved-tab search filter (label/device substring)
    // ---- Vault (Saved tab) drill-down state ----
    private int vaultFilter = 0;    // top-level type facet: 0 = all, 1 = logins only, 2 = device profiles only
    private String vaultApp = "";   // the app drilled into ("" = the app-list / top level). A pkg with saved logins.
    private boolean vaultImport = false;   // showing the dedicated Import browse screen (a Vault sub-view)
    private boolean healthScreen = false;  // showing the Protection Status sub-screen (a Settings sub-view)
    private java.util.List<HealthCheck.Group> healthResults;   // last-computed checks (null = still running)
    private boolean repBusy = false;       // an exit-IP reputation lookup is in flight (guards the button)
    private boolean repDetailsExpanded = false;   // the per-source reputation breakdown; collapsed by default
    // Exit IPs we've already auto-checked this session, so the Network card runs the reputation lookup
    // ONCE per distinct IP on open (no manual "Check" tap) without re-firing on every render — the
    // process-lifetime cache in HealthCheck holds the result, and the IPQS free tier is only 35/day.
    private final java.util.Set<String> autoCheckedIps = new java.util.HashSet<>();
    private boolean setupScreen = false;   // showing the guided "Set up everything" sub-screen (Settings sub-view)
    private boolean setupBusy = false;     // a setup run is in flight (guards the button + drives the spinner)
    private java.util.List<com.specter.module.gen.SetupFlow.StepResult> setupResults;  // last run's per-step outcomes (null = not run yet)
    private boolean setupAnySucceeded = false;  // did the last run install ANYTHING? (gates the reboot prompt + "done")
    private java.util.List<String> importPaths;   // the scanned importable file paths (null until scanned)
    /** pkg -> its saved login(s), newest first. Rebuilt once per Saved-tab render from appDataVault.list().
     *  A login's `fingerprint` field is the vault-label to re-apply on restore (may be "" / stale — restore
     *  handles both). The user organizes by APP, so pkg is the primary index, not the fingerprint label. */
    private final Map<String, java.util.List<com.specter.module.gen.AppDataVault.Entry>> loginsByApp = new LinkedHashMap<>();
    /** pkg -> signature of the identity bytes currently applied to THAT package. Per-package, because the
     *  apply paths are: a vault login restore pushes one identity to ONE app while the other targets keep
     *  whatever they already had. The old single "identity + target set" pair could not describe that — it
     *  read "Ready" whenever the selected set was bigger than the app just restored to, and an Apply from
     *  there wiped every target, destroying the login the restore had just put back. */
    private final Map<String, String> appliedByPkg = new LinkedHashMap<>();
    // `profile`/`appliedByPkg` above are otherwise pure in-memory session state: with no
    // durable copy, any fresh onCreate() (a relaunch that outlives this Activity instance, or a genuine
    // process death) lost the current identity AND the "already applied" memory, then onCreate()
    // unconditionally regenerate()'d a brand-new one out from under whatever was showing. Persisted here
    // (one flat JSON blob) and restored in onCreate() BEFORE deciding whether to regenerate.
    private static final String KEY_CURRENT_STATE = "current_state_v1";
    private void persistCurrentState() {
        try {
            org.json.JSONObject j = new org.json.JSONObject(IdentityService.toJson(profile));
            j.put("_appliedByPkg", new org.json.JSONObject(appliedByPkg));
            j.put("_activeVaultLabel", activeVaultLabel);
            prefs.edit().putString(KEY_CURRENT_STATE, j.toString()).apply();
        } catch (Throwable ignored) {}   // best-effort — a failed persist just means the next fresh onCreate regenerates
    }
    /** Restores profile/appliedByPkg/activeVaultLabel from the last persistCurrentState().
     *  Returns true iff a profile was actually restored (false -> caller should regenerate as before). */
    private boolean restoreCurrentState() {
        String raw = prefs.getString(KEY_CURRENT_STATE, null);
        if (raw == null) return false;
        try {
            org.json.JSONObject j = new org.json.JSONObject(raw);
            Map<String, String> p = new LinkedHashMap<>();
            for (java.util.Iterator<String> it = j.keys(); it.hasNext(); ) {
                String k = it.next();
                if (k.startsWith("_")) continue;
                p.put(k, j.getString(k));
            }
            if (p.isEmpty()) return false;
            profile = p;
            appliedByPkg.clear();
            org.json.JSONObject a = j.optJSONObject("_appliedByPkg");
            if (a != null) {
                for (java.util.Iterator<String> it = a.keys(); it.hasNext(); ) {
                    String k = it.next();
                    appliedByPkg.put(k, a.getString(k));
                }
            } else {
                // Upgrade from the single-slot pair. The old signature was "<bytes>|<target set>", so its
                // bytes half still identifies the identity those targets carry. Without this an upgrade
                // reads "Ready" and invites an Apply that re-wipes apps already carrying the identity.
                String sig = j.optString("_appliedSig", "");
                String tgts = j.optString("_appliedTargets", "");
                if (!sig.isEmpty() && !tgts.isEmpty()) {
                    int bar = sig.lastIndexOf('|');
                    String bytes = bar < 0 ? sig : sig.substring(0, bar);
                    for (String pkg : tgts.split(",")) if (!pkg.isEmpty()) appliedByPkg.put(pkg, bytes);
                }
            }
            activeVaultLabel = j.optString("_activeVaultLabel", "");
            return true;
        } catch (Throwable t) { return false; }
    }
    // (mode·app·group) keys whose most-recent date group we've already auto-expanded once, so returning to a
    // list doesn't re-expand a group the user manually collapsed. See renderSavedList.
    private final Set<String> seededRecentGroups = new java.util.HashSet<>();
    private final Set<String> expandedGroups = new java.util.HashSet<>();  // date groups the user EXPANDED
    private final Set<String> expandedRows = new java.util.HashSet<>();    // Vault rows whose ⋯ actions are open
    private String monitoringPkg = null;       // the pkg currently being trace-monitored (null = not monitoring).
    private boolean traceAutoEnabled = false;  // did THIS monitor turn "trace" on? (so stop only undoes what it did)
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
        // Widevine L3 defaults ON for a brand-new install (max protection by default — fleet phones don't
        // watch HD Netflix). Every read site below defaults to true, so an install that predates this seed
        // must have its REAL state written explicitly here once, or an existing user with the module never
        // installed would suddenly see an ON switch with no module behind it. `setup_done` is NOT a reliable
        // fresh-vs-existing signal (it's only set by the guided "Set up everything" flow — a user who scoped
        // LSPosed manually has setup_done=false too, identical to a fresh install), so this checks the real
        // on-device module dir via su instead of inferring intent from an unrelated flag.
        seedWidevineDefault();
        svc.setCountry(Country.of(prefs.getString("country", "US")));
        // Resume diagnostics capture if the user left it on (the service is START_STICKY but a full app
        // kill or reboot drops it — re-arm here so "on" stays on across launches).
        if (Protections.isOn(prefs, Protections.byKey("trace"))) DiagnosticsService.start(this);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.BG);

        root.addView(appBar());

        // Status is a TRANSIENT banner: it shows the last operation's line briefly, then auto-clears itself so
        // it never sits pinned under the app bar as permanent ugly text. Hidden while empty. (Most callers also
        // toast(), so the banner is a quiet secondary echo, not the primary feedback.)
        status = new TextView(this);
        status.setTextColor(Theme.SOFT);
        status.setTextSize(Theme.T_CAPTION);
        // A subtle rounded banner (inset like a card) so when it appears it reads as an intentional toast-strip,
        // not a raw log line flush under the app bar.
        status.setBackground(pill(Theme.CARD, Theme.LINE));
        status.setPadding(dp(Theme.S3), dp(Theme.S2), dp(Theme.S3), dp(Theme.S2));
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.setMargins(dp(Theme.S4), dp(Theme.S1), dp(Theme.S4), dp(Theme.S1));
        status.setLayoutParams(stlp);
        status.setVisibility(View.GONE);
        status.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) {
                boolean has = s.length() > 0;
                status.setVisibility(has ? View.VISIBLE : View.GONE);
                statusClear.removeCallbacks(statusClearRun);
                if (has) statusClear.postDelayed(statusClearRun, 6000);   // auto-dismiss after ~6s
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
        // No LayoutTransition on `content`: every state change (expand a row, flip a toggle, switch tabs) goes
        // through render() -> removeAllViews() + re-add, so a transition here can only ever animate a WHOLE-TREE
        // teardown/rebuild — which reads as a flash/flicker on each toggle, not the gentle fade it was meant to
        // be. Removing it kills the flicker with no behavioural loss (the content just swaps instantly).
        // ponytail: if per-row expand animation is ever wanted, animate the changed subtree only, not `content`.
        scroll.addView(content);
        root.addView(scroll);

        root.addView(bottomNav());

        setContentView(root);
        // Only regenerate a brand-new identity when there's nothing durable to restore (a genuinely fresh
        // install, or a persisted-state read failure) — NOT on every onCreate. See restoreCurrentState().
        if (!restoreCurrentState()) regenerate();
        else render();
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
            ic.setImageDrawable(navIcon(idx, dp(24)).tint(active ? Theme.GOLD : Theme.SOFT));
            ic.setContentDescription(NAV[idx]);
            item.setContentDescription(NAV[idx] + (active ? ", selected" : ""));
            LinearLayout.LayoutParams iclp = new LinearLayout.LayoutParams(dp(26), dp(26));
            iclp.gravity = Gravity.CENTER_HORIZONTAL;
            item.addView(ic, iclp);
            TextView lbl = new TextView(this);
            lbl.setText(NAV[idx]);
            lbl.setTextSize(11);
            lbl.setTextColor(active ? Theme.GOLD : Theme.SOFT);
            lbl.setGravity(Gravity.CENTER);
            lbl.setTypeface(android.graphics.Typeface.create("sans-serif-medium",
                    active ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL));
            LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            llp.topMargin = dp(3);
            lbl.setLayoutParams(llp);
            item.addView(lbl);
            item.setOnClickListener(v -> { if (tab != idx) { tab = idx; vaultImport = false; vaultApp = ""; healthScreen = false; setupScreen = false; rebuildBottomNav(); render(); } });
            bottomNavBar.addView(item);
        }
    }

    /** Simple line icons for the 3 nav destinations. 0=Identity (person), 1=Vault (lock), 2=Settings (gear). */
    private StrokeIcon navIcon(final int which, final int px) {
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

    /** Hardware back exits a Vault SUB-SCREEN (Import browser / app drill-down) before leaving the app. */
    @Override public void onBackPressed() {
        if (tab == 1 && vaultImport) { closeImportScreen(); return; }
        if (tab == 1 && !vaultApp.isEmpty()) { vaultApp = ""; vaultQuery = ""; render(); return; }
        if (tab == 2 && setupScreen) { setupScreen = false; render(); return; }
        if (tab == 2 && healthScreen) { healthScreen = false; render(); return; }
        super.onBackPressed();
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

    private View tabBar() {
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setPadding(dp(12), dp(2), dp(12), dp(6));
        String[] names = {"Identity", "Saved", "Settings", "Location"};
        for (int i = 0; i < names.length; i++) {
            final int idx = i;
            Button tb = tabButton(names[i], tab == i);
            tb.setOnClickListener(v -> { tab = idx; vaultImport = false; vaultApp = ""; healthScreen = false; setupScreen = false; retintTabs(); render(); });
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
        btn.setMinWidth(0); btn.setMinimumWidth(0);
        btn.setMinHeight(dp(44)); btn.setMinimumHeight(dp(44));   // accessible touch target (was 0)
        btn.setPadding(dp(14), dp(8), dp(14), dp(8));
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

    private void addGap(LinearLayout row) {
        View g = new View(this);
        g.setLayoutParams(new LinearLayout.LayoutParams(dp(6), 1));
        row.addView(g);
    }

    private GradientDrawable pill(int fill, int stroke) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(Theme.R_CTRL));   // consistent tight control radius
        g.setStroke(Math.max(1, dp(1)), stroke);
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
                    // appliedByPkg is NOT cleared: it records what each app is actually wearing, which a new
                    // identity on screen does not change. Nothing matches the fresh one, so this still reads
                    // "not applied to anything yet" — while apply() keeps knowing which apps to skip.
                    activeVaultLabel = "";                  // …and not in the vault yet either
                    persistCurrentState();
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
        // No "this won't match a saved login" confirm here. Generating an identity and applying it IS the
        // new-account flow: not matching an old login is the POINT, and applyConfirmed() force-wipes each
        // target's data before writing, so there is no surviving session left to be incoherent with. The
        // dialog asked the user to confirm the thing they had just asked for. Reopening an old account is
        // the Saved tab's job — a restore wipes and puts back the fingerprint+login PAIR.
        applyConfirmed(toApply);
    }

    /** The destructive apply itself (wipe + write per target). */
    private void applyConfirmed(final Map<String, String> toApply) {
        Set<String> targets = Targets.get(prefs);
        final String sig = applySignature(toApply);
        final List<String> pkgs = new ArrayList<>(targets);
        // Snapshot for the worker: appliedByPkg is UI-thread-confined, and the skip decision below needs su.
        final Map<String, String> known = new LinkedHashMap<>(appliedByPkg);
        // The wipe ends the session being monitored — flush that capture first. State teardown happens here
        // (UI thread); the su work runs as the FIRST thing on the wipe thread, so it completes before the wipe.
        // ponytail: the skip decision needs su, so it now lives on the wipe thread — which means the flush
        // fires for a monitored target this Apply may end up SKIPPING (including the case where every target
        // is skipped). The capture is archived, not lost; moving the flush after the decision would have to
        // hop back to the UI thread mid-wipe, which is the exact race this two-half shape exists to avoid.
        final String flushPkg = beginFlushBeforeWipe(pkgs);
        opBusy = true;
        render();   // reflect the busy state immediately (hero button -> "Applying…", disabled)
        status.setText("Deep-cleaning + applying to " + pkgs.size() + " app(s)…");
        new Thread(() -> {
            finishFlush(flushPkg);   // disarm trace + archive the capture BEFORE anything is wiped
            int cleared = 0, ok = 0, skipped = 0; String lastErr = null; String clearErr = null;
            java.util.List<String> okPkgs = new ArrayList<>();
            java.util.List<String> tried = new ArrayList<>();
            for (String pkg : pkgs) {
                // Already-applied guard, PER PACKAGE: an app already carrying exactly these bytes is left
                // alone, so it is never wiped for nothing. That is what protects a just-restored login when
                // the other targets get the identity. Remembered state alone is not enough to decide this —
                // it can outlive the profile file — so the device is asked as well.
                if (sig.equals(known.get(pkg)) && liveCarries(pkg, toApply)) { skipped++; continue; }
                tried.add(pkg);
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
            // Auto-align timezone to the proxy exit IP — but ONLY when actually routed through a VPN/proxy
            // (never align to the phone's own home/carrier IP). One lookup for the whole applied set.
            String tzAligned = autoAlignTimezone(okPkgs);
            final int clearedN = cleared, okN = ok, skippedN = skipped;
            final String clrErr = clearErr, err = lastErr;
            final String tzMsg = tzAligned;
            final int triedN = tried.size();
            final java.util.List<String> triedPkgs = tried;
            final boolean allApplied = okN == triedN;       // every app it touched cleared AND applied
            runOnUiThread(() -> {
                try {
                    if (triedN == 0) {
                        String msg = "Already applied. Relaunch the app(s), or tap \"Generate another identity\" for a new one.";
                        status.setText(msg); toast(msg);
                        return;
                    }
                    // Claim "wiped AND applied" only when every app it touched got both. A clear that
                    // succeeded but whose apply failed is NOT done — saying so would hide an unspoofed app.
                    if (allApplied) toast("Wiped and applied to " + triedN + " app(s).");
                    else toast("⚠️ Only " + okN + "/" + triedN + " app(s) done — grant root in Magisk?");
                    String m = "Applied to " + okN + "/" + triedN + " app(s)."
                            + (skippedN > 0 ? " " + skippedN + " already had it." : "")
                            + (clrErr != null ? " Clear error: " + clrErr : "")
                            + (err != null ? " Apply error: " + err + " (grant root in Magisk?)" : "")
                            + (tzMsg != null ? " " + tzMsg : "")
                            + (clrErr == null && err == null ? " Relaunch them to see it." : "");
                    status.setText(m); toast(m);
                    // Record per package what each app now carries. A target that failed to clear or apply
                    // carries something unknown, so it is dropped rather than left claiming an old identity.
                    for (String pkg : triedPkgs) {
                        if (okPkgs.contains(pkg)) appliedByPkg.put(pkg, sig);
                        else appliedByPkg.remove(pkg);
                    }
                    // Prompt to vault it only when the WHOLE selected set carries it — a partial failure
                    // must remain retryable, not be suppressed as "done".
                    if (allApplied && prefs.getBoolean("save_on_apply", true)) promptSaveName(appliedTargets());
                    persistCurrentState();
                    // Monitor-on-apply: arm the read capture on the first applied target so it's ready for the
                    // relaunch — no separate tap. Only when nothing is already being monitored (don't fight a
                    // running capture), and only for an app that actually applied.
                    if (prefs.getBoolean("monitor_on_apply", false) && monitoringPkg == null && !okPkgs.isEmpty()) {
                        startMonitor(okPkgs.get(0), status);
                    }
                } finally {
                    opBusy = false;
                    render();   // AFTER opBusy=false, so the summary flips to "Applied" (not stuck "Applying…")
                }
            });
        }).start();
    }

    /** Profile with disabled ids removed (Build.* device bundle always kept if device_spoof on), plus
     *  the protection gate keys for any protection the user turned off (so the hooks skip it). */
    private Map<String, String> enabledProfile() { return enabledProfile(profile); }

    /** Same, for a map that is not (yet) the current identity — a vault fingerprint a restore is about to
     *  push. Every apply path runs its bytes through this, so the user's toggles hold everywhere. Safe off
     *  the UI thread: it only reads SharedPreferences. */
    private Map<String, String> enabledProfile(Map<String, String> src) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : src.entrySet())
            if (Toggles.isEnabled(prefs, e.getKey())) out.put(e.getKey(), e.getValue());
        Protections.applyGates(prefs, out);
        // The Gmail identifier's own inline switch (Identity tab) IS the opt-in control for account
        // masking: when it's ON, arm the hook (spoof_accounts=1); when OFF, gmail was already omitted
        // above, so the hook stays dormant. One control, shown next to the value — no separate toggle.
        if (Toggles.isEnabled(prefs, "gmail") && out.containsKey("gmail")) out.put("spoof_accounts", "1");
        return out;
    }

    /** Signature identifying "exactly THESE bytes" — every key=value in the applied map, sorted so it is
     *  order-independent. Recorded per package, so an app matches iff re-applying to it would be a true
     *  no-op. Signing the whole map (not just android_id) means a field edit, an identifier toggle, or a
     *  protection-gate change all shift the signature and make the next APPLY actually push. */
    private String applySignature(Map<String, String> applied) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : new java.util.TreeMap<>(applied).entrySet())
            sb.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        return sb.toString();
    }

    /** True if {@code pkg}'s profile file on the device still holds this identity — the ground truth behind
     *  a skip in {@link #apply()}. Remembered state can outlive the file (deleted by hand, the directory
     *  wiped, another tool rewriting it), and a skip on a stale entry would leave that app running
     *  un-spoofed forever with the UI insisting it was fine. Needs su, so call it off the UI thread. */
    private boolean liveCarries(String pkg, Map<String, String> applied) {
        Map<String, String> live = readLiveProfile(pkg);
        if (live == null) return false;   // no profile file -> the hook has nothing; apply for real
        for (Map.Entry<String, String> e : applied.entrySet()) {
            // readLiveProfile drops these three, so they can never match — and they are not identity:
            // a transient monitor flag and a policy re-stamped on every apply.
            if (e.getKey().equals("trace") || e.getKey().equals("os_version_spoof_enabled")
                    || e.getKey().equals(com.specter.module.SpoofLogic.TRUE_ANDROID_ID_KEY)) continue;
            // The live map is a SUPERSET (apply() backfills derived + per-model hardware), so check that
            // every byte we meant to push is still there — not just that some profile file exists.
            if (!e.getValue().equals(live.get(e.getKey()))) return false;
        }
        return true;
    }

    /** Packages carrying the identity currently on screen, comma-separated ("" if none). This is what the
     *  vault records as an entry's targets, so it must name only apps the CURRENT identity actually reached. */
    private String appliedTargets() {
        String sig = applySignature(enabledProfile());
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : appliedByPkg.entrySet())
            if (sig.equals(e.getValue())) sb.append(sb.length() > 0 ? "," : "").append(e.getKey());
        return sb.toString();
    }

    /** How many of {@code targets} already carry the identity on screen. 0 = Ready, all = Applied, and in
     *  between is a real state the UI has to be able to say out loud (one app restored, others not). */
    private int appliedCount(Set<String> targets) {
        String sig = applySignature(enabledProfile());
        int n = 0;
        for (String pkg : targets) if (sig.equals(appliedByPkg.get(pkg))) n++;
        return n;
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_LONG).show(); }

    /** True if it's safe to show a dialog / touch views right now. A background su task can finish AFTER the
     *  user rotated or backed out — calling .show() on a finishing/destroyed Activity throws BadTokenException.
     *  Guard every dialog raised from a runOnUiThread completion with this. */
    private boolean alive() { return !isFinishing() && !isDestroyed(); }

    /** The device's real boot count (Settings.Global.BOOT_COUNT) — increments exactly once per boot and is
     *  immune to wall-clock changes (NTP sync, manual clock/timezone edits), unlike a currentTimeMillis() -
     *  elapsedRealtime() stamp which a clock jump can push past a stored marker with no reboot at all. This
     *  UI app is unscoped, so nothing spoofs the value it reads here (the native layer only spoofs boot_count
     *  inside SCOPED target apps' profiles — see HookEntry.java — never for this app itself). */
    private int bootCount() {
        try {
            return android.provider.Settings.Global.getInt(getContentResolver(),
                    android.provider.Settings.Global.BOOT_COUNT);
        } catch (Throwable t) {
            return -1;   // unreadable — treat as "unknown boot", never auto-clears a pending marker
        }
    }

    /** Arm the persistent "Reboot required" banner — call whenever a setup/scope/native step installs a
     *  change that only takes effect after a reboot. Idempotent: a marker already pending from an earlier,
     *  not-yet-rebooted change is left alone (don't push the deadline forward on every re-run). */
    private void armRebootPending() {
        if (!prefs.contains("reboot_pending_since")) {
            prefs.edit().putInt("reboot_pending_since", bootCount()).apply();
        }
    }

    /** Clear the marker once a reboot has actually happened: the CURRENT boot count is strictly greater than
     *  the one stamped when the marker was armed. Safe to call every render() — a no-op when nothing is
     *  pending or the count can't be read (never silently drops a pending reboot on a read failure). */
    private void clearRebootPendingIfRebooted() {
        if (!prefs.contains("reboot_pending_since")) return;
        // A v0.19.3-pre-gauntlet install may still have this key stored as a Long (the old wall-clock
        // stamp) — getInt() on a Long throws ClassCastException. That old value isn't comparable to a boot
        // count at all, so just re-arm with the current (int) boot count and keep the banner up; the next
        // actual reboot then clears it normally.
        if (!(prefs.getAll().get("reboot_pending_since") instanceof Integer)) {
            prefs.edit().putInt("reboot_pending_since", bootCount()).apply();
            return;
        }
        int since = prefs.getInt("reboot_pending_since", -1);
        int now = bootCount();
        if (since >= 0 && now >= 0 && now > since) {
            prefs.edit().remove("reboot_pending_since").apply();
        }
    }

    // ---------- rendering ----------
    private void render() {
        content.removeAllViews();
        clearRebootPendingIfRebooted();
        // Persistent reboot-required banner: stays up across dismissals of the setup reboot dialog (a "Later"
        // tap must not make Specter forget a reboot is owed) until the device actually reboots.
        if (prefs.contains("reboot_pending_since")) {
            content.addView(rebootPendingBanner());
        }
        // Native-layer health banner. A stale-but-installed layer is normally auto-synced by checkZygisk()
        // (silent, no nag). We ONLY surface the banner when the layer is genuinely NOT INSTALLED, or when that
        // silent auto-sync FAILED (root revoked etc.) — because a stale/unhooked native layer must never be
        // hidden from the user (that's the coverage gap this banner exists to prevent).
        if (zygiskStatus != null && zygiskStatus.bundledVersion != null
                && (!zygiskStatus.installed || zygiskSyncFailed)) {
            content.addView(zygiskBanner());
        }
        // First-run call-to-action: until the guided setup has been run once, surface a prominent banner on
        // every tab so a brand-new user is pointed at "Set up everything" instead of hunting through Settings.
        // Dismissed permanently once setup completes (setupResults sets the pref); the Settings row stays for
        // re-runs. Suppressed while the setup screen itself is open (would be redundant).
        if (tab != 2 && !prefs.getBoolean("setup_done", false) && !setupScreen) {
            content.addView(setupBanner());
        }
        switch (tab) {
            case 0: renderIdentity(); break;
            case 1: renderSaved(); break;
            case 2: renderSettings(); break;
        }
    }

    /** One-time (per install) Widevine-L3 default seed, run off the UI thread since it needs su. A device
     *  that already has the Magisk module mounted (installed by an earlier build, before this default
     *  existed) must seed `true`; everything else — including a device that ran setup but had no oemcrypto
     *  to shadow — seeds `false`. Never touches an already-seeded pref (checked again on the UI thread right
     *  before writing, since onCreate itself already guarantees this runs at most once per process). */
    private void seedWidevineDefault() {
        if (prefs.contains("widevine_l3")) return;
        new Thread(() -> {
            boolean installed;
            try {
                String out = new com.specter.module.gen.RootWriter.SuShell().runCapture(
                        "[ -d /data/adb/modules/" + com.specter.module.gen.WidevineL3.MODULE_ID + " ] && echo 1 || echo 0").trim();
                installed = "1".equals(out);
            } catch (Throwable t) {
                installed = false;   // no root yet (first launch, before Superuser grant) — nothing could be installed
            }
            final boolean seed = installed;
            runOnUiThread(() -> {
                if (!prefs.contains("widevine_l3")) prefs.edit().putBoolean("widevine_l3", seed).apply();
            });
        }).start();
    }

    /** Read the on-device native-layer status off the UI thread (su can block). If the layer is INSTALLED but
     *  STALE (an app-version bump re-bumped the bundled asset), silently re-write the bundled .so so the on-disk
     *  version matches again — no banner, no nag; the refreshed layer activates on the next natural reboot. Only
     *  a genuinely-absent layer surfaces a banner (see render()). Re-reads status after any silent sync. */
    private void checkZygisk() {
        new Thread(() -> {
            com.specter.module.gen.ZygiskInstaller.Status st;
            try {
                st = com.specter.module.gen.ZygiskInstaller.status(getApplicationContext(), new com.specter.module.gen.RootWriter.SuShell());
                if (st != null && st.installed && !st.current && st.bundledVersion != null) {
                    // Auto-update the file in place (install() just writes it via su — no reboot), then re-read.
                    // If the silent sync FAILS, flag it so render() still surfaces the banner — a stale/unhooked
                    // native layer must never be silently hidden (that's the coverage gap the banner guards).
                    try {
                        com.specter.module.gen.ZygiskInstaller.install(getApplicationContext());
                        st = com.specter.module.gen.ZygiskInstaller.status(getApplicationContext(), new com.specter.module.gen.RootWriter.SuShell());
                        zygiskSyncFailed = false;
                        runOnUiThread(this::armRebootPending);   // refreshed .so needs a reboot to actually load
                    } catch (Throwable t) { zygiskSyncFailed = true; }
                } else {
                    zygiskSyncFailed = false;
                }
            } catch (Throwable t) { st = null; }
            final com.specter.module.gen.ZygiskInstaller.Status f = st;
            runOnUiThread(() -> { zygiskStatus = f; if (svc != null) render(); });
        }).start();
    }

    /** The missing/stale-native-layer banner: an amber card explaining the gap + a one-tap install button.
     *  Install writes the module from the bundled asset via su, then prompts a reboot. */
    private View zygiskBanner() {
        // Clean card (same surface as everything else) with a thin bright accent bar on the left edge — reads
        // as "attention" without a muddy tinted background. Title + inline action, no big block.
        boolean stale = zygiskStatus.installed;
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.CARD);
        bg.setCornerRadius(dp(Theme.R_CARD));
        outer.setBackground(bg);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.setMargins(dp(Theme.S4), 0, dp(Theme.S4), dp(Theme.S3));
        outer.setLayoutParams(olp);

        // text column (with a bit of left inset that reads like an accent margin — no separate MATCH_PARENT bar)
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S3), dp(Theme.S3));
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView lab = new TextView(this);
        // `stale` is only reached now when the SILENT auto-sync failed (see render()/checkZygisk) — so this is a
        // manual-retry prompt, not a routine "update available" nag.
        lab.setText(stale ? "Native layer couldn't update" : "Native layer not installed");
        lab.setTextColor(Theme.INK); lab.setTextSize(Theme.T_BODY);
        lab.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        col.addView(lab);
        TextView d = new TextView(this);
        d.setText(stale ? "Tap to retry — grant root so it can update."
                : "Some deep signals still read the real device without it.");
        d.setTextColor(Theme.SOFT); d.setTextSize(Theme.T_CAPTION);
        d.setPadding(0, dp(Theme.S1), 0, 0);
        col.addView(d);
        outer.addView(col);

        // inline text action on the right
        View act = textButton(stale ? "Update" : "Install", Theme.GOLD, v -> installZygisk());
        act.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((TextView) act).setPadding(dp(Theme.S3), dp(Theme.S3), dp(Theme.S4), dp(Theme.S3));
        outer.addView(act);
        return outer;
    }

    /** Persistent "Reboot required" banner — stays up (across app relaunches, across dismissing the setup
     *  dialog) until the device actually reboots (see clearRebootPendingIfRebooted). Same clean card surface
     *  as {@link #zygiskBanner}. */
    private View rebootPendingBanner() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.CARD);
        bg.setCornerRadius(dp(Theme.R_CARD));
        outer.setBackground(bg);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.setMargins(dp(Theme.S4), 0, dp(Theme.S4), dp(Theme.S3));
        outer.setLayoutParams(olp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S3), dp(Theme.S3));
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView lab = new TextView(this);
        lab.setText("Reboot required");
        lab.setTextColor(Theme.INK); lab.setTextSize(Theme.T_BODY);
        lab.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        col.addView(lab);
        TextView d = new TextView(this);
        d.setText("A recent change needs a reboot to activate");
        d.setTextColor(Theme.SOFT); d.setTextSize(Theme.T_CAPTION);
        d.setPadding(0, dp(Theme.S1), 0, 0);
        col.addView(d);
        outer.addView(col);

        View act = textButton("Reboot", Theme.GOLD, v -> promptReboot());
        act.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((TextView) act).setPadding(dp(Theme.S3), dp(Theme.S3), dp(Theme.S4), dp(Theme.S3));
        outer.addView(act);
        return outer;
    }

    /** First-run banner: a gold-accented card that sends a new user to the guided "Set up everything" flow.
     *  Same clean card surface + inline action as {@link #zygiskBanner}. Shown until setup runs once. */
    private View setupBanner() {
        LinearLayout outer = new LinearLayout(this);
        outer.setOrientation(LinearLayout.HORIZONTAL);
        outer.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Theme.CARD);
        bg.setCornerRadius(dp(Theme.R_CARD));
        outer.setBackground(bg);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.setMargins(dp(Theme.S4), 0, dp(Theme.S4), dp(Theme.S3));
        outer.setLayoutParams(olp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S3), dp(Theme.S3));
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView lab = new TextView(this);
        lab.setText("Finish setup");
        lab.setTextColor(Theme.INK); lab.setTextSize(Theme.T_BODY);
        lab.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        col.addView(lab);
        TextView d = new TextView(this);
        d.setText("Install layers, scope apps, then reboot");
        d.setTextColor(Theme.SOFT); d.setTextSize(Theme.T_CAPTION);
        d.setPadding(0, dp(Theme.S1), 0, 0);
        col.addView(d);
        outer.addView(col);

        View act = textButton("Set up", Theme.GOLD, v -> { tab = 2; setupScreen = true; setupResults = null; rebuildBottomNav(); render(); });
        act.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ((TextView) act).setPadding(dp(Theme.S3), dp(Theme.S3), dp(Theme.S4), dp(Theme.S3));
        outer.addView(act);
        return outer;
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
                    armRebootPending();
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
            content.addView(identifiersCard());   // one group card, plain rows (was 15 separate cards)
            TextView hint = new TextView(this);
            hint.setText("Tap a row to edit · long-press to randomize just that field");
            hint.setTextColor(Theme.DIM); hint.setTextSize(Theme.T_CAPTION);
            hint.setPadding(dp(Theme.S4) + dp(Theme.S1), dp(Theme.S1), dp(Theme.S4), dp(Theme.S2));
            content.addView(hint);
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
        String deviceLine = device + (carrier.isEmpty() ? "" : "  ·  " + carrier);
        // After a restore or a vault apply, the live identity has a saved NAME (activeVaultLabel). Lead with
        // that human name ("Petra G FL"), not the bare model — the model then reads as the secondary line, so
        // "which identity is live" is answerable at a glance and the A1 device-vs-name drift is legible.
        String savedName = activeVaultLabel.isEmpty() ? "" : labelName(activeVaultLabel);

        TextView dev = new TextView(this);
        dev.setText(savedName.isEmpty() ? deviceLine : savedName);
        dev.setTextColor(Theme.INK);
        dev.setTextSize(Theme.T_HEADING);
        dev.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        dev.setPadding(0, dp(Theme.S1), 0, 0);
        c.addView(dev);
        if (!savedName.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(deviceLine);
            sub.setTextColor(Theme.SOFT);
            sub.setTextSize(Theme.T_CAPTION);
            sub.setPadding(0, dp(2), 0, 0);
            c.addView(sub);
        }

        Set<String> tgts = Targets.get(prefs);
        int appliedN = appliedCount(tgts);
        boolean applied = appliedN > 0 && appliedN == tgts.size();
        // Status pill (icon-dot + word) so state reads at a glance, not text alone. "On 1 of 3 apps" is its
        // own state — after a login restore one target carries this identity and the rest do not, and
        // rounding that down to "Ready" is what used to invite an Apply that wiped the restored app.
        String pill = opBusy ? "Applying…" : appliedN == 0 ? "Ready"
                : applied ? "Applied" : "On " + appliedN + " of " + tgts.size() + " apps";
        c.addView(statusPill(pill, opBusy ? Theme.GOLD : appliedN == 0 ? Theme.SOFT : Theme.SAGE));

        // Primary: Apply to N apps — disabled + progress label while an apply/restore is running.
        int napps = tgts.size();
        if (opBusy) {
            c.addView(disabledButton("Applying…"));
        } else {
            c.addView(primaryButton(napps == 0 ? "Select target apps" : "Apply to " + napps + " app" + (napps == 1 ? "" : "s"),
                    v -> { if (napps == 0) startActivity(new Intent(this, AppPickerActivity.class)); else apply(); }));
        }
        // Secondary: generate another — an outlined button paired UNDER Apply (a small gap), not floating text.
        View regen = themedButton("Generate another identity", Theme.CARD2, Theme.SOFT, Theme.BTN_EDGE, true,
                v -> { if (!opBusy) regenerate(); });
        LinearLayout.LayoutParams rlp = (LinearLayout.LayoutParams) regen.getLayoutParams();
        rlp.topMargin = dp(Theme.S2);
        regen.setLayoutParams(rlp);
        c.addView(regen);

        // "Save to vault on apply" — a COMPACT one-line checkbox (tap the box or its label), not a titled pane.
        // Saving on APPLY (not on generate) means a vault entry always reached an app.
        final android.widget.CheckBox save = new android.widget.CheckBox(this);
        save.setChecked(prefs.getBoolean("save_on_apply", true));
        save.setText("Save to vault on apply");
        save.setTextColor(Theme.SOFT);
        save.setTextSize(Theme.T_CAPTION);
        save.setButtonTintList(android.content.res.ColorStateList.valueOf(Theme.GOLD));
        save.setMinHeight(0); save.setMinimumHeight(0);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(Theme.S3);
        save.setLayoutParams(clp);
        save.setPadding(dp(Theme.S1), 0, 0, 0);
        save.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("save_on_apply", on).apply());
        c.addView(save);

        // "Monitor reads on apply" — the same one-line-checkbox pattern. Off by default: capture is heavier
        // than a vault save (it runs a logcat capture), so it arms only when the user opts in. When on, a
        // successful apply auto-starts the per-app read monitor on the first applied target, so a read-capture
        // is armed for the relaunch you're about to do — no separate "Monitor reads" tap to forget.
        final android.widget.CheckBox mon = new android.widget.CheckBox(this);
        mon.setChecked(prefs.getBoolean("monitor_on_apply", false));
        mon.setText("Monitor reads on apply");
        mon.setTextColor(Theme.SOFT);
        mon.setTextSize(Theme.T_CAPTION);
        mon.setButtonTintList(android.content.res.ColorStateList.valueOf(Theme.GOLD));
        mon.setMinHeight(0); mon.setMinimumHeight(0);
        LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.topMargin = dp(Theme.S2);
        mon.setLayoutParams(mlp);
        mon.setPadding(dp(Theme.S1), 0, 0, 0);
        mon.setOnCheckedChangeListener((b, on) -> prefs.edit().putBoolean("monitor_on_apply", on).apply());
        c.addView(mon);
        // Already applied but not yet in the vault -> a quiet one-tap save link (only when it's actionable).
        if (appliedN > 0 && activeVaultLabel.isEmpty()) {
            TextView saveNow = new TextView(this);
            saveNow.setText("Save this identity to the vault");
            saveNow.setTextColor(Theme.GOLD);
            saveNow.setTextSize(Theme.T_CAPTION);
            saveNow.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
            saveNow.setBackground(ripple(dp(Theme.R_CTRL)));
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.topMargin = dp(Theme.S2);
            saveNow.setLayoutParams(nlp);
            saveNow.setPadding(dp(Theme.S1), dp(Theme.S2), dp(Theme.S2), dp(Theme.S2));
            saveNow.setOnClickListener(v -> promptSaveName(appliedTargets()));
            c.addView(saveNow);
        }
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
        // A full-width divider + a quiet caption clearly separate the "Change apps" CONTROL from the LIST of
        // selected apps below it — so the card doesn't read as one undivided blob.
        c.addView(fullDivider());
        TextView cap = new TextView(this);
        cap.setText("Selected");
        cap.setTextColor(Theme.DIM); cap.setTextSize(Theme.T_CAPTION);
        cap.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        cap.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S4), dp(Theme.S1));
        c.addView(cap);
        boolean first = true;
        for (final String pkg : targets) {
            if (!first) c.addView(hairlineInset());
            first = false;
            c.addView(targetAppRow(pkg));
        }
        return c;
    }

    /** A full-width 1px divider (not inset like hairlineInset) — a hard break between sections inside a card. */
    private View fullDivider() {
        View v = new View(this);
        v.setBackgroundColor(Theme.LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.5f))));
        return v;
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

        // Just a chevron in the collapsed row — the Remove action lives INSIDE the expanded actions, so a
        // stray tap next to "expand" can't delete a target (accidental-delete guard).
        ImageView chev = new ImageView(this);
        chev.setImageDrawable(icChevron(expanded ? 1 : 0, dp(18)).tint(monitoring ? Theme.GOLD : Theme.DIM));
        chev.setContentDescription(expanded ? "Collapse" : "Expand");
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(dp(40), dp(40));
        clp.gravity = Gravity.CENTER; chev.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        chev.setLayoutParams(clp);
        r.addView(chev);
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
            Button rest = halfButton("Restore AppData", v -> restoreForPkg(pkg, sessStatus));
            row2.addView(save); row2.addView(gap); row2.addView(rest);
            actions.addView(row2);
            actions.addView(sessStatus);
            // Remove target — lives here (expanded) so it can't be tapped by accident from the collapsed row.
            // Built with themedButton like every other action in this card (it used to be a bare text label
            // with hand-patched padding, which read as a caption rather than a control), and it confirms
            // first: removing a target unhooks the app, which is not something to do on a stray tap.
            LinearLayout row3 = new LinearLayout(this); row3.setOrientation(LinearLayout.HORIZONTAL);
            row3.setPadding(0, dp(Theme.S2), 0, 0);
            row3.addView(themedButton("Remove from targets", Theme.CARD, Theme.RED, Theme.RED, true, v ->
                confirmDestructive("Remove target?",
                        Targets.label(this, pkg) + " will stop being spoofed. Its saved logins and profiles "
                                + "are kept — you can add it back any time.",
                        "Remove", () -> {
                            Set<String> cur = Targets.get(prefs); cur.remove(pkg); Targets.set(prefs, cur);
                            toast("Removed " + Targets.label(this, pkg)); render();
                        })));
            actions.addView(row3);
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
        iv.setImageDrawable(icChevron(expanded ? 1 : 0, dp(18)).tint(Theme.DIM));
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

    /** Start/stop trace-monitoring what {@code pkg} reads. YOU decide the window: tap to start (arms trace on
     *  the app's live profile + starts the capture service), tap again to stop + open the read report. A
     *  30-minute auto-stop is a safety net so a forgotten capture can't log for days. */
    private void toggleMonitor(final String pkg, final TextView statusView) {
        if (pkg.equals(monitoringPkg)) { stopMonitor(); return; }
        if (monitoringPkg != null) { toast("Already monitoring " + Targets.label(this, monitoringPkg) + " — stop it first."); return; }
        startMonitor(pkg, statusView);
    }

    /** Arm trace on {@code pkg} + start the capture, reporting into {@code statusView}. Assumes no monitor is
     *  currently running (the caller guards that). Shared by the per-app button and the monitor-on-apply
     *  checkbox so both take the exact same trace-bookkeeping and 30-min-auto-stop path. */
    private void startMonitor(final String pkg, final TextView statusView) {
        statusView.setTextColor(Theme.DIM);
        statusView.setText("Starting monitor…");
        new Thread(() -> {
            String err = armTrace(pkg, true);   // add "trace":"1" to the app's applied profile (su)
            runOnUiThread(() -> {
                if (err != null) { statusView.setTextColor(Theme.RED); statusView.setText("Monitor failed: " + err); return; }
                monitoringPkg = pkg;
                // Monitoring one app IS read logging scoped to that app — reflect it in the global Settings
                // toggle so the two controls never disagree (the source of the "are these the same?" confusion).
                // Remember whether WE flipped it on, so Stop can undo exactly that — and NOT clobber a global
                // "Read logging" the user had already switched on themselves. onCreate re-arms capture whenever
                // this pref is on, so leaving it stuck-on would silently resume capture on every future launch.
                Protections.P trace = Protections.byKey("trace");
                traceAutoEnabled = trace != null && !Protections.isOn(prefs, trace);
                if (traceAutoEnabled) Protections.set(prefs, trace, true);
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
        clearTraceAutoEnable();   // undo the auto-ON so onCreate doesn't silently resume capture next launch
        status.setText("Stopping monitor…");
        new Thread(() -> {
            final String msg = disarmAndArchive(pkg);
            runOnUiThread(() -> {
                status.setText(msg);
                render();
                startActivity(new Intent(this, DiagnosticsActivity.class)
                        .putExtra(DiagnosticsActivity.EXTRA_PKG, pkg)
                        // Signal a stop-initiated open so the trace tab auto-saves the coverage report when
                        // the autosave_trace pref is on — no manual Export tap to forget.
                        .putExtra(DiagnosticsActivity.EXTRA_FROM_STOP, true));   // reads diag.log -> report
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
        // Land in the shared Download/Specter folder, same as every other Specter export.
        String dir = com.specter.module.gen.AppDataVault.EXPORT_DIR;
        String dest = dir + "/specter-reads-" + pkg + "-" + System.currentTimeMillis() + ".log";
        try {
            com.specter.module.gen.RootWriter.SuShell sh = new com.specter.module.gen.RootWriter.SuShell();
            waitForCaptureToStop(sh);
            // -s: only copy a NON-EMPTY capture, so a monitor that recorded nothing leaves no misleading file.
            int code = sh.run("mkdir -p '" + dir + "'; [ -s '" + DiagnosticsCmd.LOG_PATH + "' ] && cp -n '" + DiagnosticsCmd.LOG_PATH
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
        clearTraceAutoEnable();   // undo the auto-ON so onCreate doesn't silently resume capture next launch
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

    /** If this monitor auto-enabled the global "Read logging" pref, switch it back off on stop — so the opt-in
     *  diagnostics toggle returns to where the user left it and onCreate won't silently re-arm capture. A no-op
     *  if the user had turned it on themselves (we only undo what the monitor turned on). */
    private void clearTraceAutoEnable() {
        if (!traceAutoEnabled) return;
        traceAutoEnabled = false;
        Protections.P trace = Protections.byKey("trace");
        if (trace != null) Protections.set(prefs, trace, false);
    }

    /** Add/remove {@code "trace":"1"} in the app's live profile file via su. Returns null on success, else an error. */
    private String armTrace(String pkg, boolean on) {
        if (!com.specter.module.gen.RootWriter.validPkg(pkg)) return "invalid package";
        String path = com.specter.module.gen.RootWriter.PROFILE_DIR + "/" + pkg + ".json";
        // on: insert "trace":"1", after the opening brace IF not already present. off: strip it. Idempotent seds.
        // The off path also strips a LEGACY unquoted `{trace:1,` — an older armTrace wrote the flag unquoted,
        // which the quoted-form strip can't touch, so it would otherwise stay as invalid-JSON cruft forever
        // (found on-device 2026-08-05). It's harmless to spoofing — parseFlatJson skips to the first quote —
        // but disarm should leave a clean, valid-JSON profile.
        String cmd = on
                ? "grep -q '\"trace\"' " + path + " || sed -i 's/^{/{\"trace\":\"1\",/' " + path
                : "sed -i 's/\"trace\":\"1\",//; s/,\"trace\":\"1\"//; s/^{trace:1,/{/' " + path;
        try {
            int code = new com.specter.module.gen.RootWriter.SuShell().run(cmd, "");
            return code == 0 ? null : "su exited " + code + " (is there an applied profile for this app? APPLY first)";
        } catch (Exception e) { return e.getMessage(); }
    }

    /** Capture (or restore) a target app's login session off the UI thread, updating {@code statusView}.
     *  Root-only: a denied/absent su surfaces as a readable message, never a silent no-op or a crash. */
    /** The per-app "Restore AppData" button: route to the SAME coherent vault restore the Saved tab uses
     *  (re-applies the login's linked fingerprint, so the device matches), instead of a bare staged restore
     *  that could leave the app incoherent. One saved login → restore it; several → let the user pick which
     *  (fp↔login is 1-to-many); none vaulted → fall back to restoring the last STAGED capture (the only case
     *  the old bare path still serves — e.g. a capture whose Save-name dialog was cancelled). Unifies the two
     *  restore entry points onto one flow. */
    private void restoreForPkg(final String pkg, final TextView statusView) {
        java.util.List<com.specter.module.gen.AppDataVault.Entry> logins = appDataVault.list(pkg);
        if (logins.isEmpty()) { runSession(pkg, false, statusView); return; }   // no vaulted login → staged
        if (logins.size() == 1) { restoreAppData(logins.get(0)); return; }
        // Several saved logins for this app — pick which one to bring back (newest first; list() is sorted).
        final CharSequence[] items = new CharSequence[logins.size()];
        for (int i = 0; i < logins.size(); i++) {
            com.specter.module.gen.AppDataVault.Entry e = logins.get(i);
            String name = labelName(e.label);
            items[i] = (name.isEmpty() ? e.label : name)
                    + (e.device == null || e.device.isEmpty() ? "" : "  ·  " + e.device);
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("Restore which " + Targets.label(this, pkg) + " login?")
                .setItems(items, (d, w) -> restoreAppData(logins.get(w)))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void runSession(final String pkg, final boolean capture, final TextView statusView) {
        final String verb = capture ? "Capturing" : "Restoring";
        statusView.setTextColor(Theme.DIM);
        statusView.setText(verb + " session…");
        new Thread(() -> {
            String msg; boolean ok = true;
            try {
                String out = capture ? SessionMigrator.capture(pkg) : SessionMigrator.restore(pkg);
                if (capture) {
                    // Snapshot the WHOLE logged-in state: the login tarball (now STAGED by capture() above) AND
                    // the fingerprint the app is CURRENTLY running under — so an already-logged-in app whose
                    // fingerprint was never saved can still be captured in one action, and restore re-applies
                    // both together. ensureFingerprintSaved reuses an existing saved fingerprint (matched by
                    // android_id) or saves the live one if new. We DON'T write the AppData entry here — the
                    // staged tarball waits while we ask the user to NAME it (parity with the fingerprint
                    // "Save to vault" flow, which prompts for a name), then saveAppDataAs() does the durable save.
                    final String fpLabel = ensureFingerprintSaved(pkg);   // saves/reuses the live applied fingerprint
                    final String device = deviceStringForPkg(pkg);
                    runOnUiThread(() -> promptAppDataName(pkg, fpLabel, device, statusView));
                    return;   // the save + status update happen after the name dialog
                } else {
                    // Leave the app STOPPED — never launch a target without an explicit user tap. The
                    // user opens it themselves when they're ready (auto-launching a login-bearing app the
                    // instant a restore finishes was startling, and could fire a target before the user
                    // meant to touch it).
                    msg = "AppData restored for " + Targets.label(this, pkg) + " — open it when ready.";
                }
            } catch (SessionMigrator.SessionException e) {
                ok = false; msg = sessionErrorMessage(pkg, capture, e.getMessage());
            }
            final String fMsg = msg; final boolean fOk = ok;
            runOnUiThread(() -> {
                // The inline per-app status line is the anchored channel (sits under the app's own buttons).
                // Toast only on success — a red inline message + a red toast saying the same thing was noisy.
                statusView.setTextColor(fOk ? Theme.SAGE : Theme.RED);
                statusView.setText(fMsg);
                if (fOk) toast(fMsg);
            });
        }, "specter-session-" + (capture ? "cap" : "res")).start();
    }

    /** After a successful AppData capture (the login tarball is STAGED, waiting), ask the user to NAME the saved
     *  entry — parity with the fingerprint "Save to vault" flow, which also prompts. Recognizes an already-saved
     *  fingerprint (fpLabel, matched by ensureFingerprintSaved) and links to it. Prefill is the app label; the
     *  date/time is always prepended to the stored label (and shown as the row's subtitle), so the name the user
     *  types is just the human tag. Blank uses the date/time alone. The durable save runs off the UI thread. */
    private void promptAppDataName(final String pkg, final String fpLabel, final String device, final TextView statusView) {
        if (!alive()) {
            // Activity gone before we could ask — fall back to an auto-named save so the capture isn't lost.
            saveAppDataAs(pkg, defaultAppDataName(pkg), fpLabel, device, statusView);
            return;
        }
        final EditText in = new EditText(this);
        in.setText(defaultAppDataName(pkg));
        in.setSelection(in.getText().length());
        in.setHint("Name (optional) — blank uses the date/time");
        in.setTextColor(Theme.INK);
        in.setHintTextColor(Theme.DIM);
        in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        String fpNote = (fpLabel != null && !fpLabel.isEmpty())
                ? "Linked to fingerprint " + labelName(fpLabel) + " — name this saved login."
                : "Name this saved login. A date and time are added automatically.";
        new AlertDialog.Builder(this)
                .setTitle("Save AppData as")
                .setMessage(fpNote)
                .setView(in)
                .setPositiveButton("Save", (d, w) ->
                        saveAppDataAs(pkg, in.getText().toString().trim(), fpLabel, device, statusView))
                .setNegativeButton("Cancel", (d, w) -> {
                    // User backed out — the capture is staged but not vaulted. Say so; nothing is lost if they
                    // re-tap Save AppData (a fresh capture just re-stages), and the staged tar is harmless.
                    statusView.setTextColor(Theme.DIM);
                    statusView.setText("Capture ready — not saved (tap Save AppData to name it).");
                })
                .show();
    }

    /** The prefilled AppData name: the app's own label (e.g. "Dasher"), which reads cleanly since the date/time
     *  is prepended to the stored label separately. Falls back to the short package tail. */
    private String defaultAppDataName(String pkg) {
        String lbl = Targets.label(this, pkg);
        return (lbl == null || lbl.isEmpty() || lbl.equals(pkg)) ? shortPkg(pkg) : lbl;
    }

    /** Durable-save the staged AppData capture under a date/time-stamped label carrying the user's {@code name}
     *  (blank -> date/time only), linked to {@code fpLabel}. Runs the su copy off the UI thread, then reports. */
    private void saveAppDataAs(final String pkg, final String name, final String fpLabel, final String device,
                               final TextView statusView) {
        statusView.setTextColor(Theme.DIM);
        statusView.setText("Saving AppData…");
        new Thread(() -> {
            // makeLabel prepends the MMDDYY-Day-HHMM stamp (same scheme as the fingerprint vault), so the label
            // sorts + groups by date and the row shows name + time regardless of what was typed. uniqueAppDataLabel
            // sanitizes to the AppData charset + disambiguates a same-minute collision.
            String label = uniqueAppDataLabel(Vault.makeLabel(name));
            String verr = appDataVault.save(label, pkg, fpLabel == null ? "" : fpLabel, device);
            final boolean ok = verr == null;
            final String msg = ok
                    ? "Saved " + Targets.label(this, pkg) + " AppData"
                        + (fpLabel != null && !fpLabel.isEmpty() ? " + fingerprint " + labelName(fpLabel) : "")
                    : "Capture ok, but vault save failed: " + verr + " (staged at " + SessionMigrator.tarPath(pkg) + ")";
            runOnUiThread(() -> {
                statusView.setTextColor(ok ? Theme.SAGE : Theme.RED);
                statusView.setText(msg);
                if (ok) { toast(msg); render(); }   // render so the Saved tab picks up the new entry
            });
        }, "specter-appdata-save").start();
    }

    /** Turn a raw {@link SessionMigrator.SessionException} ("... exited 3: no staged session for <pkg>") into a
     *  clean human line — the app LABEL, not the package, and a plain sentence, not a shell {@code exited N}.
     *  The exit codes are the ones the capture/restore shell scripts echo (see {@link SessionMigrator}); any
     *  code we don't recognise falls back to a generic message rather than leaking the raw shell error. */
    private String sessionErrorMessage(String pkg, boolean capture, String raw) {
        final String app = Targets.label(this, pkg);
        int code = -1;
        if (raw != null) {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("exited (\\d+)").matcher(raw);
            if (m.find()) { try { code = Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) {} }
        }
        if (capture) {
            switch (code) {
                case 3: return "Can't save " + app + " — it isn't installed on this device.";
                case 4: return "Nothing to save for " + app + " yet — open it and sign in first.";
                case 5: return "Couldn't save " + app + " — it wouldn't stop. Close it and try again.";
                default: return "Couldn't save " + app + "'s AppData. Is root granted to Specter?";
            }
        }
        switch (code) {
            case 3: return "No saved AppData to restore for " + app + " yet.";
            case 4: return "Can't restore " + app + " — it isn't installed on this device.";
            case 5: case 6: return "The saved AppData for " + app + " is corrupt — nothing was changed.";
            case 8: return "Couldn't restore " + app + " — it wouldn't stop. Close it and try again.";
            default: return "Couldn't restore " + app + "'s AppData. Is root granted to Specter?";
        }
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
        if (liveAid != null && !liveAid.isEmpty()) {
            // Fast path: the fingerprint the user just applied/saved is tracked in activeVaultLabel. If its
            // android_id matches the live one, reuse it directly — this is the authoritative "current identity"
            // and prevents a duplicate when the vault scan below would otherwise re-derive a fresh save.
            if (activeVaultLabel != null && !activeVaultLabel.isEmpty()) {
                Map<String, String> act = vault.load(activeVaultLabel);
                if (act != null && liveAid.equals(act.get("android_id"))) return activeVaultLabel;
            }
            // Otherwise scan the vault: any saved fingerprint with the same android_id (the unique per-identity
            // key) IS this identity — reuse + link to it, never create a duplicate.
            for (Vault.Entry e : vault.list()) {
                Map<String, String> saved = vault.load(e.label);
                if (saved != null && liveAid.equals(saved.get("android_id"))) { activeVaultLabel = e.label; persistCurrentState(); return e.label; }
            }
        }
        // Genuinely new identity (no saved fingerprint shares its android_id) -> save it, named after the app.
        String label = vault.save(shortPkg(pkg), live, pkg);
        if (label != null) { activeVaultLabel = label; persistCurrentState(); }
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
            m.remove("os_version_spoof_enabled");   // host-specific runtime policy, re-stamped on each apply
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

    /** ALL identifiers as ONE group card: a plain row each (label + value + include-switch), tap a row to
     *  edit, long-press to randomize just that field. Replaces the old one-card-per-identifier soup. */
    private View identifiersCard() {
        LinearLayout c = card();
        java.util.List<IdentityFields.Field> ids = IdentityFields.IDENTIFIERS;
        for (int i = 0; i < ids.size(); i++) {
            final IdentityFields.Field f = ids.get(i);
            if (i > 0) c.addView(hairlineInset());
            final boolean on = Toggles.isEnabled(prefs, f.key);
            final String v = profile.get(f.key);

            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setMinimumHeight(dp(56));
            r.setPadding(dp(Theme.S4), dp(Theme.S2), dp(Theme.S3), dp(Theme.S2));
            r.setBackground(ripple(0));
            r.setOnClickListener(x -> {
                if (profile.isEmpty()) { toast("No identity yet — tap Randomize first."); return; }
                editField(f, null);
            });
            r.setOnLongClickListener(x -> {
                if (profile.isEmpty()) return true;
                final Map<String, String> ctx = new LinkedHashMap<>(profile);
                new Thread(() -> { try { final String nv = svc.randomizeField(ctx, f.key);
                    runOnUiThread(() -> { profile.put(f.key, nv); persistCurrentState(); render(); status.setText(f.label + " randomized — Apply to push."); });
                } catch (Throwable t) {} }).start();
                return true;
            });

            LinearLayout col = new LinearLayout(this);
            col.setOrientation(LinearLayout.VERTICAL);
            col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView lab = new TextView(this);
            lab.setText(f.label); lab.setTextColor(Theme.SOFT); lab.setTextSize(Theme.T_CAPTION);
            col.addView(lab);
            TextView val = new TextView(this);
            val.setText(v == null ? "—" : v);
            val.setTextColor(on ? Theme.INK : Theme.DIM); val.setTextSize(Theme.T_LABEL);
            val.setSingleLine(true); val.setEllipsize(android.text.TextUtils.TruncateAt.END);
            col.addView(val);
            r.addView(col);

            final boolean locked = Toggles.isLocked(f.key);
            final Switch en = new Switch(this); tintSwitch(en);
            en.setChecked(on);
            if (locked) {
                // Hardware anchor (Widevine / serial): locked ON — toggling it off re-opens the stable-id
                // re-link leak. Show it on-but-disabled so the state is honest, not silently un-toggleable.
                en.setEnabled(false);
                en.setAlpha(0.5f);
                lab.setText(f.label + "  · locked");
            } else {
                en.setOnCheckedChangeListener((vw, isOn) -> { Toggles.set(prefs, f.key, isOn); val.setTextColor(isOn ? Theme.INK : Theme.DIM); });
            }
            r.addView(en);
            c.addView(r);
        }
        return c;
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
                        profile.put(f.key, nv); persistCurrentState(); val.setText(nv);
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
            warn.setText("Device fields go together — edit them all to match one real phone.");
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
                    profile.put(f.key, nv);
                    persistCurrentState();
                    if (val != null) val.setText(nv); else render();   // null val (grouped row) -> re-render
                    status.setText(f.label + " set to a custom value — APPLY to push.");
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void renderSettings() {
        if (setupScreen) { renderSetup(); return; }     // dedicated guided-setup sub-screen
        if (healthScreen) { renderHealth(); return; }   // dedicated status sub-screen

        // Set up everything: the one-tap first-run install (native layer + LSPosed scope + OTA block +
        // Widevine L3, then a reboot). Always available so it's re-runnable, not just a first-run gate.
        content.addView(sectionLabel("Setup"));
        LinearLayout setupCard = card();
        LinearLayout sTrail = new LinearLayout(this);
        sTrail.addView(chevronTrailing(false));
        setupCard.addView(row(
                "Set up everything",
                "Install layers, scope apps, then reboot",
                sTrail,
                v -> { setupScreen = true; setupResults = null; render(); }));
        content.addView(setupCard);

        // Health: one tap to verify everything is actually configured to spoof (LSPosed scope, framework
        // gate, native layer, per-app profiles) — so a misconfiguration shows as red instead of a silent
        // false sense of security.
        content.addView(sectionLabel("Status"));
        LinearLayout statusCard = card();
        LinearLayout hTrail = new LinearLayout(this);
        hTrail.addView(chevronTrailing(false));
        statusCard.addView(row(
                "Check protection status",
                "Verify setup, hooks, location, and network",
                hTrail,
                v -> { healthScreen = true; render(); }));
        content.addView(statusCard);

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
        info.addView(label("Device identity — always applied"));
        TextView desc = value("Model, build, hardware, and sensors match the applied phone");
        desc.setTextColor(Theme.DIM);
        info.addView(desc);
        content.addView(info);

        content.addView(sectionLabel("Protections"));
        content.addView(protectionsCard(false));

        content.addView(sectionLabel("Diagnostics"));
        content.addView(diagnosticsCard());

        // Exit-IP reputation keys. Optional — the blacklist count is keyless — but the fraud score is the
        // signal that actually catches a burned proxy, and that needs a key. Never hardcoded, never shipped.
        content.addView(sectionLabel("IP reputation"));
        LinearLayout repKeys = card();
        // getIPIntel leads — it's the free, no-signup one (just a contact email) and the datacenter check +
        // 17 blacklists already work with nothing set. Keys below only sharpen the result.
        repKeys.addView(apiKeyRow("getIPIntel email", "getipintel_contact",
                "Free proxy/VPN score · no signup, just a contact email"));
        repKeys.addView(hairlineInset());
        repKeys.addView(apiKeyRow("IPQualityScore key", "ipqs_key",
                "Fraud score and proxy verdict · 35 lookups a day free"));
        repKeys.addView(hairlineInset());
        repKeys.addView(apiKeyRow("AbuseIPDB key", "abuseipdb_key",
                "Abuse-report history · 1,000 lookups a day free"));
        repKeys.addView(hairlineInset());
        // A USER + KEY pair, so two rows — half a pair never runs. Its score is shown but scores nothing;
        // what earns it a place is the datacenter/Tor classifier feeding the Exit type.
        repKeys.addView(apiKeyRow("Scamalytics username", "scamalytics_user",
                "Account name · pairs with the key below"));
        repKeys.addView(hairlineInset());
        repKeys.addView(apiKeyRow("Scamalytics key", "scamalytics_key",
                "Datacenter and Tor classifier · 5,000 lookups a month free"));
        content.addView(repKeys);

        // Advanced (root) — device-wide, persistent Magisk-module actions, NOT per-profile hook gates.
        // Kept in their own section + explicitly opt-in because they modify the system (a /vendor bind-mount),
        // can break unrelated apps (DRM HD playback), and persist across reboot until turned off.
        content.addView(sectionLabel("Advanced (root)"));
        content.addView(widevineL3Row());
        content.addView(gsfResetRow());
        // Location spoofing (proper hidemymock + Lockito-style GPS) is a planned later PR — not shown
        // as a dead toggle until it actually works.
    }

    /** A settings row holding one optional API key in prefs. The subtitle says whether a key is saved, so it
     *  isn't a black box, and the key itself is never rendered back into the list. */
    private View apiKeyRow(final String title, final String key, final String desc) {
        boolean set = !prefs.getString(key, "").isEmpty();
        return row(title, (set ? "Saved · " : "Not set · ") + desc, null, v -> {
            final EditText in = new EditText(this);
            in.setText(prefs.getString(key, ""));
            in.setHint("Paste key (leave empty to clear)");
            in.setSingleLine(true);
            in.setTextColor(Theme.INK);
            in.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            LinearLayout box = new LinearLayout(this);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setPadding(dp(20), dp(8), dp(20), 0);
            box.addView(in);
            new AlertDialog.Builder(this)
                    .setTitle(title)
                    .setView(box)
                    .setPositiveButton("Save", (d, w) -> {
                        String nv = in.getText().toString().trim();
                        prefs.edit().putString(key, nv).apply();
                        status.setText(title + (nv.isEmpty() ? " cleared" : " saved"));
                        render();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
    }

    /** The guided "Set up everything" sub-screen: one tap installs every layer (native + scope + OTA block +
     *  Widevine L3) via {@link com.specter.module.gen.SetupFlow}, shows a live per-step checklist, then prompts
     *  the one reboot they all need. Re-runnable — it's idempotent (already-done steps just report so). */
    private void renderSetup() {
        content.addView(Nav.backRow(this, "Set up everything", () -> { setupScreen = false; render(); }));

        // BEFORE a run: an intro card that says exactly what the button does, then the button. AFTER a run: the
        // per-step checklist + a reboot prompt. While running: the same checklist area shows "Setting up…".
        if (setupResults == null && !setupBusy) {
            LinearLayout intro = cardBox();
            TextView h = label("One-tap install");
            h.setTextColor(Theme.INK); h.setTextSize(16);
            h.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
            intro.addView(h);
            TextView d = value("Installs everything, scopes your apps, blocks OS updates, sets software DRM, "
                    + "then reboots — safe to run again.");
            d.setTextColor(Theme.SOFT); d.setPadding(0, dp(Theme.S1), 0, 0);
            intro.addView(d);
            content.addView(intro);

            // Show what "your apps" means so scope isn't a black box.
            java.util.Set<String> targets = Targets.get(prefs);
            if (!targets.isEmpty()) {
                content.addView(section("Apps to scope"));
                LinearLayout tcard = card();
                int i = 0;
                for (String pkg : targets) {
                    if (i++ > 0) tcard.addView(hairlineInset());
                    LinearLayout r = new LinearLayout(this);
                    r.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S4), dp(Theme.S3));
                    TextView t = value(Targets.label(this, pkg));
                    t.setTextColor(Theme.INK);
                    r.addView(t);
                    tcard.addView(r);
                }
                content.addView(tcard);
            }

            View go = primaryButton("Set up everything", v -> runSetup());
            LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            glp.setMargins(dp(Theme.S4), dp(Theme.S3), dp(Theme.S4), dp(Theme.S4));
            go.setLayoutParams(glp);
            content.addView(go);
            return;
        }

        // Running or done: render the per-step checklist. While busy, results is still the prior list (or null);
        // show a running header either way.
        if (setupBusy) {
            TextView t = value("Setting up… this takes a few seconds.");
            t.setTextColor(Theme.DIM);
            t.setPadding(dp(Theme.S4), dp(Theme.S4), dp(Theme.S4), dp(Theme.S3));
            content.addView(t);
        }

        if (setupResults != null) {
            int failed = 0;
            for (com.specter.module.gen.SetupFlow.StepResult s : setupResults) if (!s.done) failed++;
            if (!setupBusy) {
                // Three states: all good (green), nothing installed at all (red — e.g. root denied), and
                // partial (gold). Never claim success when anySucceeded is false.
                int heroColor; String heroTitle, heroSub;
                if (!setupAnySucceeded) {
                    heroColor = Theme.RED; heroTitle = "Setup didn’t run";
                    heroSub = "Nothing installed — grant Specter root in Magisk, then try again.";
                } else if (failed == 0) {
                    heroColor = Theme.SAGE; heroTitle = "Setup complete";
                    heroSub = "Reboot to activate every layer.";
                } else {
                    heroColor = Theme.GOLD; heroTitle = "Setup finished — with notes";
                    heroSub = failed + " step" + (failed == 1 ? "" : "s") + " need attention — reboot, then check status.";
                }
                content.addView(healthHero(heroColor, heroTitle, heroSub));
            }
            content.addView(section("Steps"));
            LinearLayout card = card();
            for (int i = 0; i < setupResults.size(); i++) {
                if (i > 0) card.addView(hairlineInset());
                card.addView(setupStepRow(setupResults.get(i)));
            }
            content.addView(card);
        }

        if (!setupBusy && setupResults != null) {
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(dp(Theme.S4), dp(Theme.S3), dp(Theme.S4), dp(Theme.S2));
            // Reboot only when something actually installed — rebooting after a total failure is pointless.
            // Otherwise offer a Retry (the same one-tap run).
            View primary = setupAnySucceeded
                    ? primaryButton("Reboot now", v -> promptReboot())
                    : primaryButton("Try again", v -> runSetup());
            primary.setLayoutParams(rlp);
            content.addView(primary);

            View check = textButton("Check protection status instead", Theme.GOLD,
                    v -> { setupScreen = false; healthScreen = true; render(); });
            ((TextView) check).setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.setMargins(0, 0, 0, dp(Theme.S4));
            check.setLayoutParams(clp);
            content.addView(check);
        }
    }

    /** One setup step row: a green (done) / amber (not done) dot + label + detail. Same visual language as a
     *  health-check row, minus the Fix button (the reboot at the end is the single follow-up action). */
    private View setupStepRow(final com.specter.module.gen.SetupFlow.StepResult s) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(Theme.S4), dp(Theme.S3) + dp(1), dp(Theme.S4), dp(Theme.S3) + dp(1));

        int color = s.done ? Theme.SAGE : Theme.GOLD;
        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(color);
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.setMargins(0, dp(6), dp(Theme.S3), 0);
        r.addView(dot, dlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView lab = new TextView(this);
        lab.setText(s.label); lab.setTextColor(Theme.INK); lab.setTextSize(Theme.T_BODY);
        lab.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        col.addView(lab);
        TextView det = new TextView(this);
        det.setText(s.detail); det.setTextColor(Theme.DIM); det.setTextSize(Theme.T_CAPTION);
        det.setLineSpacing(dp(2), 1f); det.setPadding(0, dp(1), 0, 0);
        col.addView(det);
        r.addView(col);
        return r;
    }

    /** Run the whole install flow off the UI thread, then re-render into the checklist + reboot prompt. */
    private void runSetup() {
        if (setupBusy) return;
        setupBusy = true; setupResults = null;
        render();   // shows the "Setting up…" state
        final java.util.Set<String> targets = Targets.get(prefs);
        new Thread(() -> {
            com.specter.module.gen.SetupFlow.Outcome out;
            try {
                out = com.specter.module.gen.SetupFlow.run(getApplicationContext(), targets,
                        prefs.getBoolean("widevine_l3", true));
            } catch (com.specter.module.gen.SetupFlow.BusyException be) {
                // Another run (e.g. a stale worker after Activity recreation) holds the process-wide latch.
                runOnUiThread(() -> { setupBusy = false; status.setText("Setup already running — one moment."); });
                return;
            }
            final com.specter.module.gen.SetupFlow.Outcome f = out;
            runOnUiThread(() -> {
                setupBusy = false;
                setupResults = f.steps;
                setupAnySucceeded = f.anySucceeded;
                // Mark setup done (permanently hides the first-run banner) ONLY when the REQUIRED steps (native
                // layer + LSPosed scope incl. framework) both succeeded — not when merely "something" installed.
                // A partial run (e.g. scope failed because the module isn't enabled) must keep nagging.
                if (f.requiredOk) prefs.edit().putBoolean("setup_done", true).apply();
                if (f.anySucceeded) armRebootPending();
                Targets.invalidateScopeCache();   // scope may have changed — force the next isScoped() to re-check
                if (setupScreen) render();
            });
        }, "specter-setup").start();
    }

    /** Prompt + perform a reboot (the single follow-up every setup step needs to activate). Surfaces a reboot
     *  FAILURE (su denied / command error) instead of silently doing nothing, so the user isn't left thinking
     *  the device is rebooting when it isn't. */
    private void promptReboot() {
        if (!alive()) return;
        // Honest copy: only claim "everything" when the required steps actually passed this run.
        boolean ok = setupResults != null && setupRequiredOk();
        String msg = ok ? "Installed. Reboot to activate."
                : "Some steps incomplete (see list). Reboot to activate what installed.";
        new AlertDialog.Builder(this)
                .setTitle("Reboot to finish")
                .setMessage(msg)
                .setPositiveButton("Reboot now", (dl, w) -> new Thread(() -> {
                    int code = -1; String err = null;
                    try { code = new com.specter.module.gen.RootWriter.SuShell().run("svc power reboot || reboot", ""); }
                    catch (Throwable t) { err = t.getMessage(); }
                    if (code != 0) {
                        final String e = err;
                        runOnUiThread(() -> { if (alive()) toast("Reboot command failed — reboot manually"
                                + (e != null ? " (" + e + ")" : "") + "."); });
                    }
                }).start())
                .setNegativeButton("Later", null)
                .show();
    }

    /** Did the required setup steps (native + scope) pass this run? Derived from the rendered step list. */
    private boolean setupRequiredOk() {
        if (setupResults == null) return false;
        boolean nativeOk = false, scopeOk = false;
        for (com.specter.module.gen.SetupFlow.StepResult s : setupResults) {
            if ("Native layer".equals(s.label) && s.done) nativeOk = true;
            if ("App scope".equals(s.label) && s.done) scopeOk = true;
        }
        return nativeOk && scopeOk;
    }

    /** The Protection Status sub-screen: runs {@link HealthCheck} off-thread and renders a clean, grouped
     *  checklist with green/amber/red status per row + a one-tap Fix for the actionable ones. */
    private void renderHealth() {
        content.addView(Nav.backRow(this, "Protection status", () -> { healthScreen = false; render(); }));

        if (healthResults == null) {
            TextView t = value("Checking…");
            t.setTextColor(Theme.DIM);
            t.setPadding(dp(Theme.S4), dp(Theme.S4), dp(Theme.S4), dp(Theme.S4));
            content.addView(t);
            new Thread(() -> {
                final java.util.List<HealthCheck.Group> res = HealthCheck.runAll(getApplicationContext(), prefs);
                runOnUiThread(() -> { if (healthScreen) { healthResults = res; render(); } });
            }, "specter-health").start();
            return;
        }

        int bad = 0, warn = 0, ready = 0, total = 0;
        for (HealthCheck.Group g : healthResults) for (HealthCheck.Check ch : g.checks) {
            total++;
            if (ch.state == HealthCheck.State.BAD) bad++;
            else if (ch.state == HealthCheck.State.WARN) warn++;
            else if (ch.state == HealthCheck.State.READY) ready++;
        }
        // Hero summary card: a big verdict line in the worst-state colour + a one-line explanation.
        // HONESTY: a WARN is NOT "optional" — "hooks not verified running this boot" is a real not-proven state.
        // READY is different: configured + the module PROVEN to load this boot, just not yet in this app — so it
        // gets its own blue tier, never folded into green "all proven". Order: BAD > WARN > READY > all-OK.
        int heroColor = bad > 0 ? Theme.RED : warn > 0 ? Theme.GOLD : ready > 0 ? Theme.BLUE : Theme.SAGE;
        String heroTitle = bad > 0 ? "Not fully spoofing" : warn > 0 ? "Not verified"
                : ready > 0 ? "Ready" : "All good";
        String heroSub = bad > 0
                ? bad + " failed · " + warn + " unverified"
                : warn > 0
                        ? warn + " unverified" + (ready > 0 ? " · " + ready + " ready on launch" : "")
                        : ready > 0
                                ? (total - ready) + " proven this boot · " + ready + " ready on launch"
                                : total + " checks passed";
        content.addView(healthHero(heroColor, heroTitle, heroSub));

        for (HealthCheck.Group g : healthResults) {
            content.addView(section(g.title));
            if (g.geo != null) content.addView(ipLocationCard(g.geo, g.vpnRouting));   // rich IP/location card
            // "Target apps" is a different KIND of check (per-app hook attestation, not a device/config-level
            // check) — set it slightly apart: a raised card background (Theme.CARD2, same "raised" tone used
            // elsewhere) and each row's status dot swapped for the app's real icon.
            boolean isTargetApps = "Target apps".equals(g.title);
            LinearLayout card = card();
            if (isTargetApps) card.setBackground(pill(Theme.CARD2, Theme.LINE));
            for (int i = 0; i < g.checks.size(); i++) {
                if (i > 0) card.addView(hairlineInset());
                HealthCheck.Check ch = g.checks.get(i);
                card.addView(healthRow(ch, isTargetApps && ch.fixArg != null ? ch.fixArg : null));
            }
            content.addView(card);
        }

        View refresh = textButton("Re-check", Theme.GOLD, v -> { healthResults = null; render(); });
        ((TextView) refresh).setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(0, dp(Theme.S2), 0, dp(Theme.S4));
        refresh.setLayoutParams(rlp);
        content.addView(refresh);
    }

    /** The hero verdict card: a big status ring + title + subtitle, in the worst-state colour. */
    private View healthHero(int color, String title, String sub) {
        LinearLayout card = cardBox();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setPadding(dp(Theme.S4), dp(Theme.S4), dp(Theme.S4), dp(Theme.S4));

        // A filled status disc with a soft same-hue halo — reads as one clear signal.
        android.widget.FrameLayout disc = new android.widget.FrameLayout(this);
        GradientDrawable halo = new GradientDrawable(); halo.setShape(GradientDrawable.OVAL);
        halo.setColor((color & 0x00FFFFFF) | 0x26000000);
        disc.setBackground(halo);
        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(color);
        dot.setBackground(dg);
        android.widget.FrameLayout.LayoutParams inner = new android.widget.FrameLayout.LayoutParams(dp(14), dp(14));
        inner.gravity = Gravity.CENTER; disc.addView(dot, inner);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(30), dp(30));
        dlp.setMargins(0, 0, dp(Theme.S4), 0); disc.setLayoutParams(dlp);
        card.addView(disc);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        t.setText(title); t.setTextColor(color); t.setTextSize(17);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(sub); s.setTextColor(Theme.SOFT); s.setTextSize(Theme.T_CAPTION);
        s.setPadding(0, dp(2), 0, 0);
        col.addView(s);
        card.addView(col);
        return card;
    }

    /** Network-exit card for the Network group: the public IP big, ISP, location + timezone, and a transport
     *  pill. Honest about what's detectable — the pill states a VPN TRANSPORT was seen (or not), never claims
     *  to know about an upstream/router VPN or a plain (non-VpnService) proxy it can't see. */
    private View ipLocationCard(HealthCheck.Geo g, boolean vpnRouting) {
        LinearLayout card = cardBox();
        card.setPadding(dp(Theme.S4), dp(Theme.S4), dp(Theme.S4), dp(Theme.S4));

        // Header row: "NETWORK EXIT" label ... transport pill (right).
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView cap = new TextView(this);
        cap.setText("NETWORK EXIT"); cap.setTextColor(Theme.DIM); cap.setTextSize(Theme.T_CAPTION);
        cap.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        cap.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(cap);

        // Precise: we detect a VPN *transport*, not any proxy. A VpnService-based proxy (SuperProxy) shows here;
        // a plain HTTP/SOCKS5 proxy without a VpnService, or an upstream/router VPN, can't be seen from here —
        // so "not detected" is informational, never a claim of direct routing.
        int pillColor = vpnRouting ? Theme.SAGE : Theme.BLUE;
        TextView pill = new TextView(this);
        pill.setText(vpnRouting ? "VPN/proxy transport detected" : "VPN/proxy transport not detected");
        pill.setTextColor(pillColor); pill.setTextSize(Theme.T_CAPTION);
        pill.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        pill.setPadding(dp(Theme.S3), dp(Theme.S1), dp(Theme.S3), dp(Theme.S1));
        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setShape(GradientDrawable.RECTANGLE); pillBg.setCornerRadius(dp(Theme.R_PILL));
        pillBg.setColor((pillColor & 0x00FFFFFF) | 0x22000000);
        pill.setBackground(pillBg);
        head.addView(pill);
        card.addView(head);

        // The IP, big.
        TextView ip = new TextView(this);
        ip.setText(g.ip); ip.setTextColor(Theme.INK); ip.setTextSize(Theme.T_TITLE);
        ip.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        ip.setTextIsSelectable(true);
        ip.setPadding(0, dp(Theme.S3), 0, 0);
        card.addView(ip);

        if (g.isp != null) {
            TextView isp = new TextView(this);
            isp.setText(g.isp); isp.setTextColor(Theme.SOFT); isp.setTextSize(Theme.T_LABEL);
            isp.setPadding(0, dp(2), 0, 0);
            card.addView(isp);
        }

        card.addView(hairlineInset());
        card.addView(networkMetaRow("LOCATION", g.location()));
        if (g.tz != null) card.addView(networkMetaRow("TIME ZONE", g.tz));

        // Exit-IP reputation: how this IP scores with fraud/abuse data sources. A coherent device on a burned
        // proxy IP still draws friction, and no amount of fingerprint work fixes that — so it belongs on the
        // same card as the IP. Read from the process cache; a lookup only happens when the button is tapped
        // (IPQualityScore's free tier is 35/day).
        card.addView(hairlineInset());
        card.addView(reputationRows(g.ip, g.isp, HealthCheck.cachedReputation(g.ip), vpnRouting));

        card.addView(hairlineInset());
        TextView support = new TextView(this);
        support.setText("Public IP shows the network exit");
        support.setTextColor(Theme.SOFT); support.setTextSize(Theme.T_LABEL);
        support.setPadding(0, dp(Theme.S3), 0, 0);
        card.addView(support);
        TextView limit = new TextView(this);
        limit.setText("Upstream VPNs and plain proxies are not detectable here");
        limit.setTextColor(Theme.DIM); limit.setTextSize(Theme.T_CAPTION);
        limit.setPadding(0, dp(2), 0, 0);
        card.addView(limit);

        return card;
    }

    /** The reputation block of the network card: the fraud/blacklist/abuse rows once a check has run, plus the
     *  button that runs one. Kept out of the automatic check list on purpose — the lookups cost API quota, so
     *  they are user-triggered and the result is cached per IP for the process lifetime. */
    private View reputationRows(final String ip, final String geoIsp, HealthCheck.Reputation rep,
                                boolean vpnRouting) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);

        if (rep != null) {
            // 1) The verdict, and the signals that produced it. A bare level says nothing; the factors are
            //    the whole answer to "what makes this suspect?". Same model as the desktop/web readout.
            java.util.List<String> vf = HealthCheck.verdictFactors(rep, geoIsp);
            String level = vf.get(0);
            int vc = "dirty".equals(level) ? Theme.RED : "suspect".equals(level) ? Theme.AMBER
                    : "clean".equals(level) ? Theme.SAGE : Theme.DIM;
            box.addView(verdictHead(level.toUpperCase(java.util.Locale.US),
                    android.text.TextUtils.join(" · ", vf.subList(1, vf.size())), vc));

            // 2) Tiles: big number over ONE short caption, uniform height. A caption that wraps to three
            //    lines makes every tile in the row that tall, so the long text lives in the breakdown below.
            String cc = HealthCheck.connectionClass(rep, geoIsp);
            int n = rep.blacklists.size(), pol = rep.policyLists.size();
            java.util.List<View> tiles = new java.util.ArrayList<>();
            // Only shown when it says something. "Not obviously a datacenter" is all a name heuristic can
            // claim, and rendering that as a green "Real line" reassures about exits it simply failed to
            // recognise — measured on a NordVPN-operated Tor exit, which no name rule catches. Mirrors
            // connection_class in ipcheck.py, which returns None rather than guessing "residential".
            // Tor is called out separately: it also reads is_datacenter, but "Tor" is the more useful claim
            // and it is an instant deny at most apps. Neither value may ever render green.
            if ("tor".equals(cc)) tiles.add(repTile("Tor", "Exit type", Theme.RED));
            else if ("datacenter".equals(cc)) tiles.add(repTile("Hosting", "Exit type", Theme.RED));
            tiles.add(repTile(rep.dnsblUsable || n > 0 ? String.valueOf(n) : "—", "Blacklists",
                    n >= 2 ? Theme.RED : n > 0 ? Theme.AMBER
                            : rep.dnsblUsable && rep.dnsblChecked > 0 ? Theme.SAGE : Theme.DIM));
            if (pol > 0) tiles.add(repTile(String.valueOf(pol), "Policy lists", Theme.BLUE));
            if (rep.abuseConfidence != null) {
                tiles.add(repTile(rep.abuseConfidence + "%", "Abuse",
                        rep.abuseConfidence >= 50 ? Theme.RED : rep.abuseConfidence >= 10 ? Theme.AMBER : Theme.SAGE));
            }
            if (rep.fraudScore != null) {
                // Not a headline: IPQS scores almost any proxy 75-100 because "is this a proxy?" dominates it,
                // and vetting proxies is the point. One tile among the signals.
                tiles.add(repTile(String.valueOf(rep.fraudScore), "Fraud score",
                        rep.fraudScore >= 85 ? Theme.RED : rep.fraudScore >= 60 ? Theme.AMBER : Theme.SAGE));
            } else {
                tiles.add(repTile("n/a", "Fraud score", Theme.DIM));
            }
            if (rep.abuseConfidence == null) tiles.add(repTile("n/a", "Abuse", Theme.DIM));
            if (rep.getipintel == null) tiles.add(repTile("n/a", "getIPIntel", Theme.DIM));
            if (rep.getipintel != null) {
                double g = rep.getipintel;
                tiles.add(repTile(String.format(java.util.Locale.US, "%.2f", g), "getIPIntel",
                        g >= 0.99 ? Theme.RED : g >= 0.90 ? Theme.AMBER : Theme.SAGE));
            }
            // Scamalytics' score is shown because it is worth seeing, and coloured WARN-ONLY because it does
            // not decide anything: measured, "low" came back for a Tor exit AND for 127.0.0.1, and the score
            // tracks the ISP score rather than this address. What Scamalytics actually contributes is the
            // Exit type tile above. Never SAGE.
            if (rep.scamRisk == null) tiles.add(repTile("n/a", "Scamalytics", Theme.DIM));
            else tiles.add(repTile(rep.scamScore != null ? String.valueOf(rep.scamScore) : "—", "Scamalytics",
                    "very high".equals(rep.scamRisk) ? Theme.RED
                            : "high".equals(rep.scamRisk) ? Theme.AMBER : Theme.INK));
            for (View r : tileRows(tiles, 3)) box.addView(r);

            // 3) Flags, as their own compact line.
            if (rep.fraudScore != null) {
                java.util.List<String> flags = new java.util.ArrayList<>();
                if (Boolean.TRUE.equals(rep.tor)) flags.add("Tor");
                if (Boolean.TRUE.equals(rep.vpn)) flags.add("VPN");
                if (Boolean.TRUE.equals(rep.proxy)) flags.add("Proxy");
                if (Boolean.TRUE.equals(rep.recentAbuse)) flags.add("Abuse");
                box.addView(flags.isEmpty()
                        ? networkMetaRow("FLAGGED AS", "Not flagged as proxy or VPN", Theme.SAGE)
                        : networkMetaRow("FLAGGED AS", android.text.TextUtils.join(" · ", flags), Theme.AMBER));
            }

            // 4) The per-source breakdown, collapsed by default — every field each source returned, which is
            //    the only way to audit a verdict rather than take it on faith.
            box.addView(row(repDetailsExpanded ? "Hide breakdown" : "Detailed breakdown",
                    "What each source returned", chevronTrailing(repDetailsExpanded),
                    v -> { repDetailsExpanded = !repDetailsExpanded; render(); }));
            if (repDetailsExpanded) box.addView(reputationDetail(rep, geoIsp));

            // A source that could not run gets a TILE reading n/a, not a sentence. Spelling out "add a key
            // in Settings for a fraud score" on the main card buried the actual result under advice, and a
            // caption long enough to say it wraps. The tile keeps the source visible so its absence is
            // obvious, and the reason lives in the breakdown.
        }

        // Off-tunnel there's no proxy exit — this IP is the device's REAL public IP. Checking it still works
        // (the user asked to score without a tunnel), but only on an explicit tap through a "uses your real IP"
        // confirm, and never auto-checked. The auto-check + tunnel-pinned path lives in the on-tunnel branch below.
        if (!vpnRouting) {
            TextView note = new TextView(this);
            note.setText("No tunnel — this would check your device's real public IP");
            note.setTextColor(Theme.DIM); note.setTextSize(Theme.T_CAPTION);
            note.setPadding(0, dp(Theme.S3), 0, 0);
            box.addView(note);
            View anyway = textButton(repBusy ? "Checking…" : rep == null ? "Check this IP anyway" : "Re-check this IP",
                    repBusy ? Theme.DIM : Theme.GOLD,
                    v -> confirmRealIpAction("sending it to IPQualityScore, AbuseIPDB and the blocklists",
                            confirmed -> checkIpReputation(ip, confirmed)));
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.setMargins(0, dp(Theme.S2), 0, 0);
            anyway.setLayoutParams(alp);
            box.addView(anyway);
            return box;
        }
        View check = textButton(repBusy ? "Checking…" : rep == null ? "Check IP reputation" : "Re-check reputation",
                repBusy ? Theme.DIM : Theme.GOLD, v -> checkIpReputation(ip));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.setMargins(0, dp(Theme.S2), 0, 0);
        check.setLayoutParams(clp);
        box.addView(check);
        // Auto-check on open: the first time we render this card for an exit IP with the tunnel up and no
        // cached result, run the lookup automatically so the user doesn't tap "Check" every visit. The set
        // add() is true only once per IP; posted so it runs AFTER this render (checkIpReputation calls
        // render()); guarded again inside in case a manual tap won the race. A rotated IP auto-checks afresh.
        if (rep == null && !repBusy && ip != null && !ip.isEmpty() && autoCheckedIps.add(ip)) {
            final String autoIp = ip;
            runOnUiThread(() -> {
                if (alive() && !repBusy && HealthCheck.cachedReputation(autoIp) == null) checkIpReputation(autoIp);
            });
        }
        return box;
    }

    /** Confirm before any action that would use the device's REAL current public IP because no VPN/proxy tunnel
     *  is masking it. On a tunnel the exit IP is the proxy's, not the home IP, so the action runs straight through.
     *  {@code detail} completes the sentence "…uses your device's real public IP by …". This is what lets the
     *  off-tunnel reputation check and timezone fix exist without ever leaking the home IP by accident. */
    private void confirmRealIpAction(String detail, java.util.function.Consumer<Boolean> onDecided) {
        // `detail` is kept for the call sites to state what they do with the IP; nothing renders it now.
        // The real-IP consent decision is captured HERE, once, and handed to the callback as a boolean. The
        // callback must NOT re-query the VPN state later to decide, or a tunnel that flaps up (skipping this
        // dialog) then down before the worker thread would run a real-IP action with no dialog ever shown.
        if (HealthCheck.activeVpnNetwork(getApplicationContext()) != null) {
            onDecided.accept(false);   // on a tunnel: the exit is the proxy's — no real-IP consent needed or given
            return;
        }
        // No dialog. The button that reaches this path already SAYS it uses the real IP ("Check this IP
        // anyway", under a note that spells it out), so a modal asking again is a second click for a
        // decision the user already made. Consent is still an explicit boolean on this path, and the
        // auto-check never reaches it — it stays tunnel-only.
        onDecided.accept(true);
    }

    /** Look up an IP's reputation. On a tunnel the lookup is PINNED to it (so the exit IP is provably the proxy's);
     *  off-tunnel it runs through the default network on the device's REAL public IP — only ever reached from the
     *  user-confirmed "check this IP anyway" button, never the auto-check (which stays tunnel-only). Off-thread;
     *  the result is cached in {@link HealthCheck} and picked up on re-render. */
    private void checkIpReputation(final String ip) { checkIpReputation(ip, false); }

    /** @param allowRealIp true only from the user-confirmed off-tunnel button. When false (the auto-check and the
     *  on-tunnel button), a tunnel that has DROPPED since the card rendered aborts the check rather than silently
     *  querying the real IP — closing the flap/ABA window the old hard gate covered. */
    private void checkIpReputation(final String ip, final boolean allowRealIp) {
        if (repBusy) return;
        // null off-tunnel = the default network (real IP). Only allowed when the user explicitly confirmed it.
        final android.net.Network vpn = HealthCheck.activeVpnNetwork(getApplicationContext());
        if (vpn == null && !allowRealIp) {
            toast("Tunnel dropped — reputation is only auto-checked through the tunnel.");
            return;
        }
        final String ipqs = prefs.getString("ipqs_key", "").trim();
        final String abuse = prefs.getString("abuseipdb_key", "").trim();
        final String giiContact = prefs.getString("getipintel_contact", "").trim();
        final String scamUser = prefs.getString("scamalytics_user", "").trim();
        final String scamKey = prefs.getString("scamalytics_key", "").trim();
        repBusy = true;
        status.setText("Checking exit-IP reputation…");
        render();   // repaint the button as "Checking…"
        new Thread(() -> {
            final HealthCheck.Reputation r = HealthCheck.lookupReputation(vpn, ip, ipqs, abuse, giiContact,
                    scamUser, scamKey);
            runOnUiThread(() -> {
                repBusy = false;
                if (!alive()) return;
                int n = r.blacklists.size();
                status.setText(r.fraudScore != null
                        ? "Exit IP " + ip + " · fraud " + r.fraudScore + " · " + n + " blacklist" + (n == 1 ? "" : "s")
                        : "Exit IP " + ip + " · " + n + " blacklist" + (n == 1 ? "" : "s")
                                + (ipqs.isEmpty() ? " · add an IPQualityScore key for a fraud score" : ""));
                render();
            });
        }, "specter-reputation").start();
    }

    /** One "LABEL   value" row inside the network card — a fixed-width caption column so LOCATION/TIME ZONE
     *  align without relying on emoji for spacing. */
    /** The verdict line: the level, big and coloured, with the signals that produced it underneath. A bare
     *  "SUSPECT" tells a reader nothing they can act on — the factors are the answer. */
    private View verdictHead(String level, String factors, int colour) {
        LinearLayout v = new LinearLayout(this);
        v.setOrientation(LinearLayout.VERTICAL);
        v.setPadding(0, dp(Theme.S3), 0, dp(Theme.S2));
        TextView big = new TextView(this);
        big.setText(level);
        big.setTextColor(colour);
        big.setTextSize(20);
        big.setTypeface(big.getTypeface(), android.graphics.Typeface.BOLD);
        v.addView(big);
        if (factors != null && !factors.isEmpty()) {
            TextView sub = new TextView(this);
            sub.setText(factors);
            sub.setTextColor(Theme.SOFT);
            sub.setTextSize(Theme.T_CAPTION);
            sub.setPadding(0, dp(Theme.S1), 0, 0);
            v.addView(sub);
        }
        return v;
    }

    /** A compact signal tile: big value over a ONE-LINE caption, on a rounded card. The caption is capped at
     *  a single line on purpose — tiles share a row, so one that wraps makes every tile in the row that tall.
     *  The full text lives in the detailed breakdown. */
    private View repTile(String value, String caption, int valueColour) {
        LinearLayout t = new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable tileBg = new GradientDrawable();
        tileBg.setColor(Theme.CARD2);
        tileBg.setCornerRadius(dp(9));
        tileBg.setStroke(dp(1), Theme.LINE);
        t.setBackground(tileBg);
        t.setPadding(dp(Theme.S3), dp(Theme.S3), dp(Theme.S3), dp(Theme.S3));
        TextView num = new TextView(this);
        num.setText(value);
        num.setTextColor(valueColour);
        num.setTextSize(19);
        num.setTypeface(num.getTypeface(), android.graphics.Typeface.BOLD);
        num.setMaxLines(1);
        num.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(num);
        TextView cap = new TextView(this);
        cap.setText(caption);
        cap.setTextColor(Theme.DIM);
        cap.setTextSize(11);
        cap.setMaxLines(1);
        cap.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cap.setPadding(0, dp(Theme.S1), 0, 0);
        t.addView(cap);
        return t;
    }

    /** Lay tiles out {@code perRow} to a row, each taking an equal share. A short final row is padded with
     *  invisible spacers so its tiles keep the same width as the rows above instead of stretching. */
    private java.util.List<View> tileRows(java.util.List<View> tiles, int perRow) {
        java.util.List<View> rows = new java.util.ArrayList<>();
        for (int i = 0; i < tiles.size(); i += perRow) {
            LinearLayout r = new LinearLayout(this);
            r.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.setMargins(0, dp(Theme.S2), 0, 0);
            r.setLayoutParams(rlp);
            for (int c = 0; c < perRow; c++) {
                int idx = i + c;
                View cell = idx < tiles.size() ? tiles.get(idx) : new View(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                if (c < perRow - 1) lp.setMargins(0, 0, dp(Theme.S2), 0);
                cell.setLayoutParams(lp);
                r.addView(cell);
            }
            rows.add(r);
        }
        return rows;
    }

    /** The per-source breakdown: every field each source returned, grouped by source, plus the blocklist
     *  zones grouped by WHAT THE ANSWER MEANS — a reader should never have to decode a colour. */
    private View reputationDetail(HealthCheck.Reputation rep, String geoIsp) {
        LinearLayout d = new LinearLayout(this);
        d.setOrientation(LinearLayout.VERTICAL);
        d.setPadding(dp(Theme.S4), 0, dp(Theme.S4), dp(Theme.S3));

        // WHAT THIS EXIT IS — the answers the use case actually needs, before the per-source dumps. Opening
        // with IPQUALITYSCORE's raw fields made the reader assemble the verdict themselves out of eight
        // groups. Every row here states what was MEASURED; a source that did not run says so rather than
        // being omitted, because a missing row reads as "fine".
        d.addView(detailHead("WHAT THIS EXIT IS", null));

        String cc = HealthCheck.connectionClass(rep, geoIsp);
        d.addView(networkMetaRow("EXIT TYPE",
                "tor".equals(cc) ? "Tor exit — denied by most apps"
                        : "datacenter".equals(cc) ? "hosting network — real ISPs pass more easily"
                        : rep.scamRisk != null
                        ? "unclassified — no datacenter or proxy record, and no hosting name matched"
                        : "unclassified — no hosting name matched, and Scamalytics did not run",
                cc != null ? Theme.RED : Theme.DIM));

        // Blocklists with an HONEST denominator. "0 blacklists" and "0 of 17 checked" are different claims,
        // and an IPv6 exit is checked against FOUR zones, never seventeen.
        int hits = rep.blacklists.size();
        int total = rep.dnsblZonesTotal > 0 ? rep.dnsblZonesTotal : rep.dnsblChecked;
        if (!rep.dnsblUsable) {
            d.addView(networkMetaRow("BLOCKLISTS",
                    ("ipv6".equals(rep.dnsblFamily) || rep.dnsblFamily == null
                            ? "not run — no zone answered for this address"
                            : "not run — the blocklist DNS did not answer")
                            + "\nThis is NOT a clean result. Nothing was checked.", Theme.DIM));
        } else {
            d.addView(networkMetaRow("BLOCKLISTS",
                    hits + " of " + rep.dnsblChecked + " answering zones"
                            + (total > rep.dnsblChecked ? " (" + total
                            + ("ipv6".equals(rep.dnsblFamily) ? " IPv6" : "") + " queried)" : "")
                            + (hits > 0 ? "\n" + android.text.TextUtils.join(", ", rep.blacklists) : ""),
                    hits >= 2 ? Theme.RED : hits > 0 ? Theme.AMBER : Theme.SAGE));
            if ("ipv6".equals(rep.dnsblFamily)) {
                // Say what the evidence is WORTH, not just that it exists.
                d.addView(networkMetaRow("IPV6 CAVEAT",
                        "Spamhaus, CBL and s5h list /64 prefixes, so this is weaker evidence in both "
                                + "directions than the IPv4 equivalent", Theme.DIM));
            }
        }

        d.addView(networkMetaRow("FRAUD SCORE", rep.fraudScore != null
                        ? rep.fraudScore + " · " + (rep.fraudScore >= 85 ? "high risk"
                        : rep.fraudScore >= 60 ? "suspicious" : "clean")
                        : "no IPQualityScore key — not measured",
                rep.fraudScore == null ? Theme.DIM
                        : rep.fraudScore >= 85 ? Theme.RED : rep.fraudScore >= 60 ? Theme.AMBER : Theme.SAGE));

        // "Is it DETECTABLE as a proxy at all" — the question the fraud score can't answer, because it
        // saturates on every proxy. No flag while a source answered is a real result; no source is not.
        java.util.List<String> det = new java.util.ArrayList<>();
        if (Boolean.TRUE.equals(rep.tor)) det.add("Tor");
        if (Boolean.TRUE.equals(rep.vpn)) det.add("VPN");
        if (Boolean.TRUE.equals(rep.proxy)) det.add("Proxy");
        d.addView(networkMetaRow("DETECTED AS", !det.isEmpty()
                        ? android.text.TextUtils.join(" · ", det)
                        : rep.fraudScore != null ? "no proxy/VPN/Tor flag" : "not measured",
                !det.isEmpty() ? Theme.AMBER : rep.fraudScore != null ? Theme.SAGE : Theme.DIM));

        d.addView(networkMetaRow("ABUSE", rep.abuseConfidence != null
                        ? rep.abuseConfidence + "% confidence · "
                        + (rep.abuseReports == null ? 0 : rep.abuseReports) + " reports in 90d"
                        : "no AbuseIPDB key — not measured",
                rep.abuseConfidence == null ? Theme.DIM
                        : rep.abuseConfidence >= 50 ? Theme.RED
                        : rep.abuseConfidence >= 10 ? Theme.AMBER : Theme.SAGE));

        d.addView(networkMetaRow("GETIPINTEL", rep.getipintel != null
                        ? String.format(java.util.Locale.US, "%.3f", rep.getipintel) + " · "
                        + HealthCheck.getipintelBand(rep.getipintel)
                        + (rep.getipintelBad ? " · bad IP" : "")
                        : "did not answer — not measured",
                rep.getipintel == null ? Theme.DIM
                        : rep.getipintel >= 0.99 ? Theme.RED
                        : rep.getipintel >= 0.90 ? Theme.AMBER : Theme.SAGE));

        // Shown ADJACENT to the ISP score on purpose: they are near-identical on every IP measured, and
        // seeing that is what tells a reader the score is an ASN prior, not a judgement about this address.
        // Never SAGE — "low" came back for a Tor exit AND for 127.0.0.1.
        // scamRisk is the "did it run" sentinel; the SCORES are separately optional, so a response with a
        // band and no number would render the literal text "null · low". Guarded the same way the tile is.
        d.addView(networkMetaRow("SCAMALYTICS", rep.scamRisk != null
                        ? num(rep.scamScore) + " · " + rep.scamRisk
                        + (rep.scamIspRisk != null ? "  ·  ISP " + num(rep.scamIspScore) + " " + rep.scamIspRisk : "")
                        + "\nshown, not scored — it tracks the ISP score, not this IP"
                        : "no Scamalytics credentials — not measured",
                rep.scamRisk == null ? Theme.DIM
                        : "very high".equals(rep.scamRisk) ? Theme.RED
                        : "high".equals(rep.scamRisk) ? Theme.AMBER : Theme.INK));

        if (rep.fraudScore != null) {
            d.addView(detailHead("IPQUALITYSCORE", "strictness " + HealthCheck.IPQS_STRICTNESS));
            d.addView(networkMetaRow("FRAUD SCORE", String.valueOf(rep.fraudScore),
                    rep.fraudScore >= 85 ? Theme.RED : rep.fraudScore >= 60 ? Theme.AMBER : Theme.SAGE));
            d.addView(yesNo("PROXY", rep.proxy));
            d.addView(yesNo("VPN", rep.vpn));
            d.addView(yesNo("TOR", rep.tor));
            d.addView(yesNo("ABUSE", rep.recentAbuse));
            addIfSet(d, "CONNECTION", rep.connectionType);
            addIfSet(d, "VELOCITY", rep.abuseVelocity);
            addIfSet(d, "ISP", rep.isp != null ? rep.isp : geoIsp);
            addIfSet(d, "ORG", rep.organization);
            addIfSet(d, "ASN", rep.asn);
            addIfSet(d, "REVERSE DNS", rep.host);
        }
        if (rep.getipintel != null) {
            d.addView(detailHead("GETIPINTEL", HealthCheck.getipintelBand(rep.getipintel)));
            double g = rep.getipintel;
            d.addView(networkMetaRow("PROBABILITY", String.format(java.util.Locale.US, "%.3f", g),
                    g >= 0.99 ? Theme.RED : g >= 0.90 ? Theme.AMBER : Theme.SAGE));
            d.addView(yesNo("BAD IP", rep.getipintelBad));
            addIfSet(d, "COUNTRY", rep.getipintelCountry);
        }
        if (rep.abuseConfidence != null) {
            d.addView(detailHead("ABUSEIPDB", "90 days"));
            d.addView(networkMetaRow("CONFIDENCE", rep.abuseConfidence + "%",
                    rep.abuseConfidence >= 50 ? Theme.RED : rep.abuseConfidence >= 10 ? Theme.AMBER : Theme.SAGE));
            addIfSet(d, "REPORTS", rep.abuseReports == null ? null : String.valueOf(rep.abuseReports));
            addIfSet(d, "REPORTERS", rep.abuseReporters == null ? null : String.valueOf(rep.abuseReporters));
            addIfSet(d, "LAST REPORT", rep.lastReport);
            addIfSet(d, "USAGE TYPE", rep.usageType);
            addIfSet(d, "DOMAIN", rep.domain);
            addIfSet(d, "COUNTRY", rep.countryCode);
        }
        if (rep.scamRisk != null) {
            d.addView(detailHead("SCAMALYTICS", rep.scamRisk));
            d.addView(networkMetaRow("SCORE", num(rep.scamScore) + " · " + rep.scamRisk,
                    "very high".equals(rep.scamRisk) ? Theme.RED
                            : "high".equals(rep.scamRisk) ? Theme.AMBER : Theme.INK));
            addIfSet(d, "ISP RISK", rep.scamIspRisk == null ? null
                    : num(rep.scamIspScore) + " · " + rep.scamIspRisk);
            // An EMPTY ip2proxy record is "no record", not "clean" — say which, or the absence reassures.
            d.addView(networkMetaRow("PROXY TYPE", rep.scamProxyType != null
                            ? scamProxyTypeName(rep.scamProxyType) + " (" + rep.scamProxyType + ")"
                            : "no ip2proxy record — empty is not a clean result",
                    rep.scamProxyType != null ? Theme.RED : Theme.DIM));
            java.util.List<String> sf = new java.util.ArrayList<>();
            if (Boolean.TRUE.equals(rep.scamDatacenter)) sf.add("datacenter");
            if (Boolean.TRUE.equals(rep.scamVpn)) sf.add("VPN");
            if (Boolean.TRUE.equals(rep.scamTor)) sf.add("Tor");
            if (Boolean.TRUE.equals(rep.scamBlacklistedExternal)) sf.add("external blocklist");
            // DIM when none are raised, never green: these flags can only ever fire, so their silence is
            // "nothing found", not "verified clean".
            d.addView(networkMetaRow("FLAGS", sf.isEmpty() ? "none raised"
                    : android.text.TextUtils.join(" · ", sf), sf.isEmpty() ? Theme.DIM : Theme.AMBER));
            addIfSet(d, "PAGE", rep.scamUrl);
        }
        if (!rep.zoneStatus.isEmpty()) {
            d.addView(detailHead("BLOCKLISTS", rep.dnsblChecked + " of " + rep.zoneStatus.size()
                    + ("ipv6".equals(rep.dnsblFamily) ? " IPv6" : "") + " zones answered"));
            // Grouped by meaning, and the group label says what the meaning IS.
            zoneGroup(d, rep, "listed", "LISTED", "abuse reports against this IP", Theme.RED);
            zoneGroup(d, rep, "policy", "POLICY ONLY", "a mail-sending policy listing, not abuse", Theme.BLUE);
            zoneGroup(d, rep, "clean", "CLEAN", "answered, not listed", Theme.SAGE);
            // "Refused" and "no answer" are DIFFERENT and were previously merged under one misleading
            // "NO ANSWER" heading. A refusal is a specific, explainable condition (the zone declined this
            // resolver); silence is an outage or a timeout. Neither is a clean result, and saying which
            // one happened is the difference between a fixable problem and a mystery.
            zoneGroup(d, rep, "refused", "REFUSED", "the zone declined this resolver — not a clean result",
                    Theme.AMBER);
            zoneGroup(d, rep, "no answer", "NO ANSWER", "no reply before the timeout — not a clean result",
                    Theme.DIM);
        }
        return d;
    }

    /** One blocklist group: the zones whose answer meant {@code status}, under a label that spells out what
     *  that means. */
    private void zoneGroup(LinearLayout into, HealthCheck.Reputation rep, String status,
                           String title, String meaning, int colour) {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (java.util.Map.Entry<String, String> e : rep.zoneStatus.entrySet()) {
            if (status.equals(e.getValue())) names.add(e.getKey());
        }
        if (names.isEmpty()) return;
        // The COUNT leads the value, not the label. The label column is a fixed dp(96) and now truncates
        // at the end rather than wrapping — so a count appended to the title ("NO ANSWER · 3") lost the
        // digit first, which is the one actionable part of the row.
        into.addView(networkMetaRow(title,
                names.size() + " · " + android.text.TextUtils.join(", ", names) + "\n" + meaning, colour));
    }


    /** An optional number for display: an em dash rather than the literal string "null". */
    private static String num(Integer v) { return v == null ? "—" : String.valueOf(v); }

    /** ip2proxy's code spelled out. The raw code stays beside it — the coarse "datacenter" bucket the
     *  verdict uses must never hide the specific claim that produced it. */
    private static String scamProxyTypeName(String code) {
        if ("DCH".equals(code)) return "datacenter";
        if ("TOR".equals(code)) return "Tor exit";
        if ("VPN".equals(code)) return "VPN";
        if ("PUB".equals(code)) return "public proxy";
        if ("WEB".equals(code)) return "web proxy";
        if ("SES".equals(code)) return "search-engine spider";
        if ("RES".equals(code)) return "residential proxy";
        return code;
    }

    private View detailHead(String source, String meta) {
        TextView t = new TextView(this);
        t.setText(meta == null || meta.isEmpty() ? source : source + "  ·  " + meta);
        t.setTextColor(Theme.GOLD);
        t.setTextSize(Theme.T_CAPTION);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        t.setPadding(0, dp(Theme.S4), 0, dp(Theme.S1));
        return t;
    }

    private void addIfSet(LinearLayout into, String label, String value) {
        if (value != null && !value.isEmpty()) into.addView(networkMetaRow(label, value, Theme.INK));
    }

    /** A boolean field as yes/no, coloured only when it says something: a risk flag that is TRUE reads red,
     *  FALSE reads green. Colouring every field would be as unreadable as colouring none. */
    private View yesNo(String label, Boolean value) {
        boolean on = Boolean.TRUE.equals(value);
        return networkMetaRow(label, on ? "yes" : "no", on ? Theme.RED : Theme.SAGE);
    }

    private View networkMetaRow(String labelText, String valueText) {
        return networkMetaRow(labelText, valueText, Theme.INK);
    }

    /** {@link #networkMetaRow} with an explicit value colour — the reputation rows carry a verdict, so they read
     *  green/amber/red like the timezone check instead of neutral ink. */
    private View networkMetaRow(String labelText, String valueText, int valueColor) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.TOP);
        row.setPadding(0, dp(Theme.S2), 0, 0);

        TextView label = new TextView(this);
        label.setText(labelText);
        label.setTextColor(Theme.DIM);
        label.setTextSize(Theme.T_CAPTION);
        label.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium",
                android.graphics.Typeface.BOLD));
        // A gutter the label can never spend: without it a long caption ("ORGANIZATION") runs straight into
        // its value with no space between them.
        label.setPadding(0, 0, dp(Theme.S2), 0);
        // One line, ellipsised. Without this a caption longer than the column breaks MID-WORD
        // ("IPQUALITYSCO / RE"), which is unreadable and ragged. Keep captions short as well.
        label.setSingleLine(true);
        label.setEllipsize(android.text.TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView value = new TextView(this);
        value.setText(valueText);
        value.setTextColor(valueColor);
        value.setTextSize(Theme.T_LABEL);
        row.addView(value, new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f));

        return row;
    }

    /** One check row: a status dot aligned to the label, label + wrapped detail, and (only for a real action)
     *  a trailing Fix button. Guidance-only rows carry their steps inline in the detail — no popups. */
    private View healthRow(final HealthCheck.Check ch) { return healthRow(ch, null); }

    /** {@code appPkg} non-null -> a Target-apps row: swap the plain status dot for the app's real icon (reuses
     *  {@link #appIcon}, already used for target rows elsewhere) so per-app hook attestation visually reads as
     *  a different kind of check from the device/config-level ones. */
    private View healthRow(final HealthCheck.Check ch, final String appPkg) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setPadding(dp(Theme.S4), dp(Theme.S3) + dp(1), dp(Theme.S4), dp(Theme.S3) + dp(1));

        int color = ch.state == HealthCheck.State.OK ? Theme.SAGE
                : ch.state == HealthCheck.State.READY ? Theme.BLUE
                : ch.state == HealthCheck.State.WARN ? Theme.GOLD : Theme.RED;
        // Target-apps rows lead with the app's real icon (reuses appIcon(), already used for target rows
        // elsewhere) instead of a plain dot — a quiet visual cue that this is per-app hook attestation, a
        // different kind of check from the device/config-level ones below it.
        if (appPkg != null) {
            ImageView icon = new ImageView(this);
            icon.setImageDrawable(appIcon(appPkg, dp(20)));
            LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(20), dp(20));
            ilp.setMargins(0, 0, dp(Theme.S3), 0);
            r.addView(icon, ilp);
        }
        View dot = new View(this);
        GradientDrawable dg = new GradientDrawable(); dg.setShape(GradientDrawable.OVAL); dg.setColor(color);
        dot.setBackground(dg);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dlp.setMargins(0, dp(6), dp(Theme.S3), 0);   // nudge down to sit on the label's cap-height
        r.addView(dot, dlp);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView lab = new TextView(this);
        lab.setText(ch.label); lab.setTextColor(Theme.INK); lab.setTextSize(Theme.T_BODY);
        lab.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        col.addView(lab);
        TextView det = new TextView(this);
        det.setText(ch.detail); det.setTextColor(Theme.DIM); det.setTextSize(Theme.T_CAPTION);
        det.setLineSpacing(dp(2), 1f);
        det.setPadding(0, dp(1), 0, 0);
        col.addView(det);
        r.addView(col);

        if (ch.fix != HealthCheck.Fix.NONE) {
            Button fix = compactButton("Fix", false, v -> runHealthFix(ch));
            LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            flp.setMargins(dp(Theme.S3), dp(2), 0, 0); flp.gravity = Gravity.TOP;
            fix.setLayoutParams(flp);
            r.addView(fix);
        }
        return r;
    }

    /** Run a concrete health-check fix (no popups — the guidance for scope/root lives inline in each row's
     *  detail text; only the ACTIONABLE fixes get a button). */
    private void runHealthFix(final HealthCheck.Check ch) {
        switch (ch.fix) {
            case SYNC_ZYGISK:
                // installZygisk() runs async and owns its own UX (status banner + reboot prompt). The native
                // layer only becomes "current" AFTER a reboot, so re-checking now would just re-show the same
                // state — leave the list as-is; the user taps Re-check after rebooting.
                installZygisk();
                return;
            case REAPPLY_PROFILE:
                healthScreen = false; tab = 0; render();   // jump to Identity where APPLY lives
                status.setText("Apply an identity to " + Targets.label(this, ch.fixArg) + " here.");
                return;
            case MATCH_TZ:
                // The confirm decides real-IP consent once; matchTimezoneToIp gets that boolean and never
                // re-queries the VPN to decide, so a tunnel flap can't run it on the real IP without the dialog.
                confirmRealIpAction("reading its timezone and setting it on your applied profiles",
                        confirmed -> matchTimezoneToIp(confirmed));
                return;
            default:
        }
    }

    /** Auto-align applied profiles' timezone to the current proxy exit IP — GATED on being routed through a
     *  VPN/proxy (never align to the phone's own home/carrier IP). Runs on the apply worker thread. Returns a
     *  short status note (or null when nothing was changed / not on a proxy). */
    private String autoAlignTimezone(java.util.List<String> pkgs) {
        try {
            if (pkgs == null || pkgs.isEmpty()) return null;
            // Pin the VPN tunnel and run the lookup THROUGH it, so the exit IP is provably the proxy's — not a
            // home IP if the VPN flaps mid-lookup (closes the ABA race). No tunnel -> do nothing.
            android.net.Network vpn = HealthCheck.activeVpnNetwork(getApplicationContext());
            if (vpn == null) return null;
            HealthCheck.Geo g = HealthCheck.lookupGeo(vpn);
            if (g == null || g.tz == null) return null;
            // Final guard: the SAME VPN network must still be active right before we write.
            if (!vpn.equals(HealthCheck.activeVpnNetwork(getApplicationContext()))) return null;
            com.specter.module.gen.RootWriter.SuShell sh = new com.specter.module.gen.RootWriter.SuShell();
            int n = 0;
            for (String pkg : pkgs) if (com.specter.module.gen.RootWriter.setTimezone(sh, pkg, g.tz)) n++;
            return n > 0 ? "Timezone aligned to " + g.tz + " (proxy IP)." : null;
        } catch (Throwable t) { return null; }
    }

    /** Rewrite every applied target profile's "timezone" to the IP's zone (off-thread, su), then force-stop the
     *  targets so the TimeZone.getDefault() hook reloads the new value. Kills the device-vs-IP timezone mismatch. */
    /** @param allowRealIp true only when the user confirmed real-IP use (off-tunnel at confirm time). When false
     *  (on-tunnel), a tunnel that has DROPPED by the time this worker runs aborts rather than silently aligning to
     *  the device's real IP — mirrors {@link #checkIpReputation}'s guard and closes the same flap/ABA window. */
    private void matchTimezoneToIp(final boolean allowRealIp) {
        final Set<String> targets = Targets.get(prefs);
        healthResults = null; render();   // show the "Checking…" spinner while we work + re-check
        new Thread(() -> {
            // Don't trust the tzId captured at check time (the endpoint/location may have changed since). Re-resolve
            // the zone through the tunnel if there is one, else the default network (the real IP — the caller already
            // confirmed that off-tunnel). vpn==null off-tunnel is expected, not an error.
            android.net.Network vpn = HealthCheck.activeVpnNetwork(getApplicationContext());
            if (vpn == null && !allowRealIp) {
                // Tunnel dropped since the confirm, and this wasn't a confirmed real-IP action — don't align to the
                // real IP. Abort before even resolving it.
                runOnUiThread(() -> {
                    if (!healthScreen) return;
                    Toast.makeText(this, "Tunnel dropped — timezone left as-is.", Toast.LENGTH_SHORT).show();
                    healthResults = null; render();
                });
                return;
            }
            HealthCheck.Geo g = HealthCheck.lookupGeo(vpn);
            String zone = (g != null) ? g.tz : null;
            int changed = 0;
            // On a tunnel the SAME tunnel must still be active right before writing (ABA guard). Off-tunnel there's
            // no tunnel to flap, so that guard doesn't apply — a null vpn is fine.
            boolean stillSafe = vpn == null || vpn.equals(HealthCheck.activeVpnNetwork(getApplicationContext()));
            if (zone != null && stillSafe) {
                com.specter.module.gen.RootWriter.SuShell sh = new com.specter.module.gen.RootWriter.SuShell();
                for (String pkg : targets) {
                    if (com.specter.module.gen.RootWriter.setTimezone(sh, pkg, zone)) {
                        changed++;
                        try { sh.run("am force-stop " + pkg, null); } catch (Throwable ignored) {}
                    }
                }
            }
            final int n = changed; final String z = zone;
            runOnUiThread(() -> {
                if (!healthScreen) return;
                Toast.makeText(this, z == null ? "Couldn't read the IP's timezone — left as-is"
                        : n == 0 ? "No profiles updated"
                        : "Timezone set to " + z + " for " + n + " app" + (n == 1 ? "" : "s"),
                        Toast.LENGTH_SHORT).show();
                healthResults = null; render();   // re-run checks — the mismatch row should clear
            });
        }, "specter-tzfix").start();
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
        TextView lab = label("Force Widevine L3");
        lab.setTextColor(Theme.INK); lab.setTextSize(14);
        titleRow.addView(lab);
        final TextView chip = statusChip(prefs.getBoolean("widevine_l3", true));
        titleRow.addView(chip);
        txt.addView(titleRow);
        TextView d = value("Device-wide software DRM · disables HD Netflix and Prime");
        d.setTextColor(Theme.DIM); d.setTextSize(12); d.setTextIsSelectable(false);
        txt.addView(d);
        head.addView(txt);

        final Switch sw = new Switch(this); tintSwitch(sw);
        sw.setChecked(prefs.getBoolean("widevine_l3", true));
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
                            armRebootPending();
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
        TextView d = value("Fresh Google ID — signs out of Google, then reboots.");
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
    /** All protections in ONE group card with hairline-separated rows (was one card each = card soup).
     *  diagnosticsOnly splits the opt-in read-logging row into its own "Diagnostics" section. */
    private View protectionsCard(boolean diagnosticsOnly) {
        LinearLayout c = card();
        boolean first = true;
        for (Protections.P prot : Protections.ALL) {
            boolean diagnostic = "trace".equals(prot.gateKey);
            if (diagnostic != diagnosticsOnly) continue;
            if (!first) c.addView(hairlineInset());
            c.addView(protectionRowInner(prot));
            first = false;
        }
        return c;
    }

    private View diagnosticsCard() {
        return protectionsCard(true);
    }

    private View protectionRowInner(final Protections.P prot) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S3), dp(Theme.S3));
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
            // "Read logging" (trace) also manages the background capture service. It's read-only,
            // so start/stop immediately; the trace=1 gate reaches the hooks on the next APPLY.
            if ("trace".equals(prot.gateKey)) {
                if (on) DiagnosticsService.start(this); else DiagnosticsService.stop(this);
                status.setText(on
                        ? "Read logging on — APPLY to arm"
                        : "Read logging off");
            } else {
                status.setText(prot.label + (on ? " on — APPLY to push" : " off — APPLY to push"));
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
        // Mock-location HIDING is real (gated with the hide_mock protection): a driver/fraud SDK reading
        // Location.isFromMockProvider()/isMock() sees false. Show it as an active protection.
        LinearLayout mockCard = cardBox();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView lab = label("Hide mock location");
        lab.setTextColor(Theme.INK);
        lab.setTextSize(14);
        lab.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(lab);
        boolean on = Protections.isOn(prefs, Protections.byKey("hide_mock"));
        head.addView(statusChip(on));
        mockCard.addView(head);
        TextView d = value("Mock-location flags read as clean in scoped apps");
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
    /** Restore a saved login: re-apply its LINKED fingerprint (so the device identity matches), then copy the
     *  tarball back to staging and run the app-data restore. The target is left STOPPED — the user opens it
     *  themselves; nothing here launches an app for them. All off the UI thread. */
    private void restoreAppData(final com.specter.module.gen.AppDataVault.Entry e) {
        if (opBusy) { toast("Busy — wait for the current operation to finish."); return; }
        opBusy = true;   // claim the busy state so a second restore can't race this one (was checked but never set)
        status.setText("Restoring " + Targets.label(this, e.pkg) + " login…");
        new Thread(() -> {
            StringBuilder note = new StringBuilder();
            String err = null;
            Map<String, String> appliedFp = null;   // non-null once the linked fingerprint really landed
            // 1) STAGE FIRST — copy the vaulted tarball to the staging path and confirm it's there BEFORE we
            //    touch (wipe) the live app. If the login can't be staged, we abort with the app untouched
            //    (the old order wiped via the fingerprint-apply first, so a staging failure destroyed the
            //    real login with nothing to restore).
            err = appDataVault.restoreToStaging(e.label);
            if (err == null) {
                // 2) Re-apply the linked fingerprint (device identity must match the captured login). This
                //    wipes the app — but the login tarball is already staged, and SessionMigrator.restore
                //    below is a safe whole-dir swap with rollback.
                if (!e.fingerprint.isEmpty()) {
                    Map<String, String> fp = vault.load(e.fingerprint);
                    if (fp != null && !fp.isEmpty()) {
                        try {
                            com.specter.module.gen.SessionMigrator.clearData(e.pkg);
                            // Push what APPLY would push — the toggle/gate-filtered map, not the raw vault
                            // bytes. The login was captured while the app ran under an applied (filtered)
                            // profile, so this is the closer match to capture time, it stops a restore from
                            // silently overriding identifiers the user switched off, and it keeps ONE apply
                            // semantic across every path (so the applied-signature below is exact).
                            svc.apply(e.pkg, enabledProfile(fp));
                            appliedFp = fp;
                            note.append("fingerprint ").append(e.fingerprint).append(" applied; ");
                        } catch (Throwable t) { note.append("fingerprint apply failed (").append(t.getMessage()).append("); "); }
                    } else note.append("linked fingerprint missing; ");
                }
                // 3) Restore the staged login (safe swap + rollback).
                try {
                    com.specter.module.gen.SessionMigrator.restore(e.pkg);
                    note.append("login restored");
                    // Left STOPPED on purpose — nothing launches a target app without an explicit user
                    // tap. The user opens it when ready.
                } catch (com.specter.module.gen.SessionMigrator.SessionException se) {
                    err = se.getMessage();
                }
            }
            final String fErr = err; final String fNote = note.toString();
            final Map<String, String> fFp = appliedFp;
            runOnUiThread(() -> {
                try {
                    // The restore just pushed the LINKED fingerprint onto the device, so the Identity tab's
                    // in-memory state is now stale — it would keep showing the last generated identity as
                    // "Applied". Adopt the restored one (by its vault name) whenever the apply succeeded,
                    // even if the login half failed: the device really is wearing this fingerprint now.
                    // Done even if a rotation replaced this Activity mid-restore: the persisted state has
                    // to match the device. Worst case it overwrites an identity generated in those few
                    // seconds — that one was never applied to anything, this one is live on the app.
                    // (ponytail: the replacement's in-memory copy stays stale until it reloads; making it
                    // observe the result needs state ownership outside the Activity.)
                    if (fFp != null) {
                        profile = new LinkedHashMap<>(fFp);
                        activeVaultLabel = e.fingerprint;
                        // Record it against THIS app only — the restore reached one target, and the others
                        // still carry whatever they had. Sign the bytes actually pushed: profile is now fFp,
                        // so enabledProfile() recomputes exactly the map svc.apply() got.
                        appliedByPkg.put(e.pkg, applySignature(enabledProfile()));
                        persistCurrentState();
                    }
                    if (alive()) {
                        if (fErr == null) status.setText("Restored " + Targets.label(this, e.pkg) + " — " + fNote + ".");
                        else status.setText(sessionErrorMessage(e.pkg, false, fErr));
                    }
                } finally { opBusy = false; if (alive()) render(); }
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
                    if (activeVaultLabel.equals(oldLabel)) { activeVaultLabel = neu; persistCurrentState(); }
                    moveExpandedKey("fp:", oldLabel, neu);   // keep the row's actions open across the rename
                    status.setText("Renamed to " + neu + (relinked > 0 ? " (" + relinked + " login(s) relinked)" : ""));
                    render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Export AppData: if it has a linked Fingerprint, let the user pick AppData-only or a combined bundle. */
    private void exportAppDataChoice(final com.specter.module.gen.AppDataVault.Entry e) {
        boolean hasFp = !e.fingerprint.isEmpty() && vault.load(e.fingerprint) != null;
        if (!hasFp) { exportAppData(e); return; }
        new AlertDialog.Builder(this)
                .setTitle("Export")
                .setItems(new String[]{"AppData only", "With its Fingerprint (one file)"}, (d, w) -> {
                    if (w == 0) exportAppData(e);
                    else exportCombo(e.label);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Export a saved AppData bundle (tarball + meta) to the Specter folder so it can be moved to another device. */
    private void exportAppData(final com.specter.module.gen.AppDataVault.Entry e) {
        status.setText("Exporting " + appLabel(e.pkg) + " AppData…");
        new Thread(() -> {
            final String dest = appDataVault.exportToDownloads(e.label);
            runOnUiThread(() -> {
                if (dest != null) { toast("Exported → " + dest); status.setText("Exported AppData → " + dest); }
                else { toast("Export failed (grant root?)."); status.setText("Export failed for " + e.label); }
            });
        }, "specter-appdata-export").start();
    }

    private static String fmtSize(long b) {
        if (b >= 1024 * 1024) return String.format(java.util.Locale.US, "%.1f MB", b / 1048576.0);
        if (b >= 1024) return String.format(java.util.Locale.US, "%.0f KB", b / 1024.0);
        return b + " B";
    }

    /** A small selectable filter chip (gold when active). Geometry is IDENTICAL in both states — the pill uses
     *  a same-width stroke whose colour matches its own fill, so an active chip never grows/shrinks vs inactive
     *  (the old contrasting inactive border made selected chips look a hair narrower). */
    private View filterChip(String text, boolean active, View.OnClickListener onClick) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setSingleLine(true);
        t.setPadding(dp(12), dp(5), dp(12), dp(5));
        t.setTextColor(active ? Theme.ON_GOLD : Theme.SOFT);
        // Stroke == fill for both states so the border never adds visible width to one and not the other.
        t.setBackground(pill(active ? Theme.GOLD : Theme.CARD2, active ? Theme.GOLD : Theme.CARD2));
        t.setOnClickListener(onClick);
        return t;
    }

    private void renderSaved() {
        if (vaultImport) { renderImportScreen(); return; }   // dedicated Import browse sub-screen
        content.addView(sectionLabel("Save current identity"));
        LinearLayout saveCard = cardBox();
        saveCard.addView(value("Save the applied identity to re-apply it later."));
        LinearLayout saveRow = new LinearLayout(this);
        saveRow.setOrientation(LinearLayout.HORIZONTAL);
        saveRow.addView(button("Save current to vault", true, v -> {
            if (profile.isEmpty()) { toast("No identity yet — RANDOMIZE ALL on the Identity tab first."); return; }
            String at = appliedTargets();
            if (at.isEmpty()) { toast("Apply this identity to an app first — the vault only stores applied profiles."); return; }
            promptSaveName(at);
        }));
        saveCard.addView(saveRow);
        content.addView(saveCard);

        // Import a shared Fingerprint or AppData bundle (from Download). One picker handles either type.
        content.addView(sectionLabel("Import"));
        LinearLayout importCard = cardBox();
        TextView idesc = value("Import a Fingerprint or AppData.");
        idesc.setTextColor(Theme.DIM);
        idesc.setTextSize(12);
        importCard.addView(idesc);
        LinearLayout importRow = new LinearLayout(this);
        importRow.setOrientation(LinearLayout.HORIZONTAL);
        importRow.addView(button("Import…", false, v -> openImportScreen()));
        importCard.addView(importRow);
        content.addView(importCard);

        // The Saved vault, organized APP-FIRST so it scales to many apps × many AppData bundles each. Top level
        // is a list of apps that have saved AppData (tap to drill in) plus a "Fingerprints" section for the
        // device-config saves. A type facet (All / Fingerprints / AppData / Both) + search sit above both levels.
        // Terminology: a "Fingerprint" is a saved device-config; "AppData" is a saved app login (explained once
        // below). We never repeat "app login" in the UI — it's just AppData.
        content.addView(sectionLabel("Saved"));
        savedListHolder = null;   // fresh holder per full render (content was cleared by render())

        java.util.List<Vault.Entry> all = vault.list();
        // Index every saved login by APP (list() is newest-first, so each per-app list stays newest-first).
        loginsByApp.clear();
        for (com.specter.module.gen.AppDataVault.Entry a : appDataVault.list(null))
            loginsByApp.computeIfAbsent(a.pkg, k -> new java.util.ArrayList<>()).add(a);
        // Clear a stale drill target FIRST (its last login was deleted) so it can't linger and reopen if that
        // package later regains a login — do this before any early return below.
        if (!vaultApp.isEmpty() && !loginsByApp.containsKey(vaultApp)) vaultApp = "";

        if (all.isEmpty() && loginsByApp.isEmpty()) {
            LinearLayout empty = cardBox();
            TextView t = value("Nothing saved yet. Save AppData from a target app, or a fingerprint after applying.");
            t.setTextColor(Theme.DIM);
            empty.addView(t);
            content.addView(empty);
            return;
        }

        // Drilled INTO an app -> show just that app's logins (with a back header). Otherwise the app list.
        if (!vaultApp.isEmpty()) {
            renderAppLogins(vaultApp);
            return;
        }

        // One-time explainer of the vocabulary (only place "app login" is spelled out).
        TextView legend = value("Fingerprint = saved identity. AppData = saved login.");
        legend.setTextColor(Theme.DIM); legend.setTextSize(Theme.T_CAPTION);
        legend.setPadding(dp(Theme.S4) + dp(Theme.S1), 0, dp(Theme.S4), dp(Theme.S2));
        content.addView(legend);

        // Type facet: All / Fingerprints / AppData / Both. Re-renders the whole tab (chip active-state repaints).
        // A horizontal scroller so four chips never wrap/clip on a narrow screen.
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(Theme.S4), 0, dp(Theme.S4), dp(Theme.S2));
        String[] segs = {"All", "Fingerprints", "AppData", "Both"};
        for (int i = 0; i < segs.length; i++) {
            final int idx = i;
            if (i > 0) addGap(chips);
            chips.addView(filterChip(segs[i], vaultFilter == i, v -> { vaultFilter = idx; render(); }));
        }
        android.widget.HorizontalScrollView chipScroll = new android.widget.HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipScroll.addView(chips);
        content.addView(chipScroll);

        // Search re-renders ONLY the results holder below (not the whole tab) so the EditText keeps focus while
        // typing. The holder is a member so the text-watcher can refill it in place.
        final java.util.List<Vault.Entry> profiles = all;
        content.addView(vaultSearchBox("Search apps or device…", () -> renderTopLevel(profiles)));
        savedTopHolder = null;
        renderTopLevel(all);
    }

    /** The set of fingerprint vault-labels that have at least one linked AppData (used by the "Both" facet). */
    private java.util.Set<String> fingerprintsWithAppData() {
        java.util.Set<String> s = new java.util.HashSet<>();
        for (java.util.List<com.specter.module.gen.AppDataVault.Entry> list : loginsByApp.values())
            for (com.specter.module.gen.AppDataVault.Entry a : list)
                if (!a.fingerprint.isEmpty()) s.add(a.fingerprint);
        return s;
    }

    /** True if this app has at least one AppData that links to a still-present fingerprint (for the "Both" facet). */
    private boolean appHasLinkedFingerprint(String pkg, java.util.Set<String> fpLabels) {
        for (com.specter.module.gen.AppDataVault.Entry a : loginsByApp.getOrDefault(pkg, java.util.Collections.emptyList()))
            if (!a.fingerprint.isEmpty() && fpLabels.contains(a.fingerprint)) return true;
        return false;
    }

    /** The top-level results (app list + device-profiles section), rebuilt into a holder so the search box
     *  above it isn't recreated on each keystroke. Filters apps by label and profiles via renderSavedList. */
    private LinearLayout savedTopHolder;
    private void renderTopLevel(java.util.List<Vault.Entry> all) {
        if (savedTopHolder == null) {
            savedTopHolder = new LinearLayout(this);
            savedTopHolder.setOrientation(LinearLayout.VERTICAL);
            content.addView(savedTopHolder);
        }
        savedTopHolder.removeAllViews();
        savedListHolder = null;   // the profiles date-list rebuilds fresh inside this holder

        String q = vaultQuery.trim().toLowerCase();
        // Facet: 0 All · 1 Fingerprints only · 2 AppData only · 3 Both (entries that pair fp + AppData).
        final boolean showApps = vaultFilter == 0 || vaultFilter == 2 || vaultFilter == 3;
        final boolean showFps  = vaultFilter == 0 || vaultFilter == 1 || vaultFilter == 3;
        final java.util.Set<String> fpLabels = new java.util.HashSet<>();
        for (Vault.Entry e : all) fpLabels.add(e.label);

        // Apps that have saved AppData. Under "Both", only apps with AppData linked to a present fingerprint.
        if (showApps && !loginsByApp.isEmpty()) {
            java.util.List<String> pkgs = new java.util.ArrayList<>();
            for (String pkg : loginsByApp.keySet()) {
                if (!q.isEmpty() && !Targets.label(this, pkg).toLowerCase().contains(q)) continue;
                if (vaultFilter == 3 && !appHasLinkedFingerprint(pkg, fpLabels)) continue;
                pkgs.add(pkg);
            }
            pkgs.sort((x, y) -> Targets.label(this, x).compareToIgnoreCase(Targets.label(this, y)));
            if (!pkgs.isEmpty()) {
                savedTopHolder.addView(section("Apps with saved AppData"));
                LinearLayout appCard = card();
                for (int i = 0; i < pkgs.size(); i++) {
                    if (i > 0) appCard.addView(hairlineInset());
                    appCard.addView(appLoginRow(pkgs.get(i)));
                }
                savedTopHolder.addView(appCard);
            }
        }

        // Fingerprints = saved device configs, date-grouped. Under "Both", only those with a linked AppData.
        if (showFps && !all.isEmpty()) {
            savedTopHolder.addView(section("Fingerprints"));
            savedListParent = savedTopHolder;   // nest the date-list INSIDE this holder, so search rebuilds it too
            renderSavedList(all);
            savedListParent = null;
        }

        if (savedTopHolder.getChildCount() == 0) {
            TextView none = value(!q.isEmpty() ? "No matches for \"" + vaultQuery + "\"."
                    : vaultFilter == 3 ? "Nothing has both a fingerprint and AppData yet." : "Nothing saved.");
            none.setTextColor(Theme.DIM); none.setPadding(dp(Theme.S4), dp(Theme.S2), dp(Theme.S4), dp(Theme.S2));
            savedTopHolder.addView(none);
        }
    }

    /** A reusable search box that writes {@link #vaultQuery} and runs {@code onChange} on each edit. */
    private EditText vaultSearchBox(String hint, final Runnable onChange) {
        final EditText search = new EditText(this);
        search.setHint(hint);
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
        slp.setMargins(dp(Theme.S4), dp(2), dp(Theme.S4), dp(Theme.S2));
        search.setLayoutParams(slp);
        search.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(android.text.Editable s) { vaultQuery = s.toString(); onChange.run(); }
        });
        return search;
    }

    /** One app tile in the top-level list: icon + name + "N logins" + drill chevron. Tapping drills into that
     *  app's saved logins (clears the search so the drilled view starts fresh). */
    private View appLoginRow(final String pkg) {
        int n = loginsByApp.get(pkg).size();
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.HORIZONTAL);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setMinimumHeight(dp(56));
        r.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S4), dp(Theme.S3));
        r.setBackground(ripple(0));
        ImageView iv = new ImageView(this);
        iv.setImageDrawable(appIcon(pkg, dp(28)));   // real icon, or a generated monogram tile if uninstalled
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(28), dp(28));
        ilp.setMargins(0, 0, dp(Theme.S3), 0); iv.setLayoutParams(ilp); r.addView(iv);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView name = new TextView(this);
        name.setText(appLabel(pkg)); name.setTextColor(Theme.INK); name.setTextSize(Theme.T_BODY);
        name.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        col.addView(name);
        TextView sub = new TextView(this);
        sub.setText(n + " AppData"); sub.setTextColor(Theme.SOFT); sub.setTextSize(Theme.T_CAPTION);
        col.addView(sub);
        r.addView(col);
        r.addView(chevronTrailing(false));
        r.setOnClickListener(v -> { vaultApp = pkg; vaultQuery = ""; render(); });
        return r;
    }

    /** Drilled-in view for ONE app: a back header, then that app's saved logins, date-grouped + searchable,
     *  each with its own Restore / Export / Delete. This is where a user picks WHICH login to bring back. */
    private void renderAppLogins(final String pkg) {
        // Back header — the shared gold-chevron control, with the app's icon + AppData count as the label area.
        int n = loginsByApp.get(pkg).size();
        LinearLayout back = Nav.backRow(this, null, () -> { vaultApp = ""; vaultQuery = ""; render(); });
        back.setBackground(ripple(0));
        ImageView bico = new ImageView(this);
        bico.setImageDrawable(appIcon(pkg, dp(22)));
        LinearLayout.LayoutParams bilp = new LinearLayout.LayoutParams(dp(22), dp(22));
        bilp.setMargins(0, 0, dp(Theme.S2), 0); bico.setLayoutParams(bilp);
        back.addView(bico);
        TextView bt = new TextView(this);
        bt.setText(appLabel(pkg) + "  ·  " + n + " AppData");
        bt.setTextColor(Theme.GOLD); bt.setTextSize(Theme.T_BODY);
        bt.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        back.addView(bt);
        content.addView(back);

        content.addView(vaultSearchBox("Search this app's AppData…", () -> renderSavedList(null)));

        savedListHolder = null;
        renderSavedList(null);   // null = login mode (uses vaultApp)
    }

    /** One saved AppData card (in the drilled-in app view): date title, size, whether it carries a fingerprint,
     *  and the shared Restore + inline actions. */
    private View loginRow(final com.specter.module.gen.AppDataVault.Entry a) {
        LinearLayout card = cardBox();
        java.text.SimpleDateFormat fmt = new java.text.SimpleDateFormat("EEE MM/dd · h:mm a", java.util.Locale.US);
        String when = a.savedAt > 0 ? fmt.format(new java.util.Date(a.savedAt)) : "(unknown date)";
        TextView lab = label(when);
        lab.setTextColor(Theme.INK); lab.setTextSize(15);
        card.addView(lab);
        TextView sub = value(fmtSize(a.sizeBytes)
                + (a.fingerprint.isEmpty() ? "  ·  no linked fingerprint" : "  ·  restores its fingerprint too"));
        sub.setTextColor(Theme.SOFT); sub.setTextSize(12);
        card.addView(sub);

        card.addView(rowActions("ad:" + a.label,
                () -> restoreAppData(a),
                () -> promptRenameLogin(a.label),
                () -> exportAppDataChoice(a),
                () -> confirmDelete("Delete AppData?", appLabel(a.pkg) + " · " + when, () -> {
                    if (appDataVault.delete(a.label)) { toast("Deleted saved AppData."); render(); }
                    else toast("Could not delete.");
                })));
        return card;
    }

    /** Rename a saved login's label (keeps its fingerprint link inside the meta). */
    private void promptRenameLogin(final String oldLabel) {
        final EditText in = new EditText(this);
        in.setText(labelName(oldLabel)); in.setHint("New name"); in.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Rename saved login")
                .setView(in)
                .setPositiveButton("Rename", (d, w) -> {
                    String neu = appDataVault.rename(oldLabel, in.getText().toString());
                    if (neu == null) { toast("Rename failed."); return; }
                    moveExpandedKey("ad:", oldLabel, neu);   // keep the row's actions open across the rename
                    status.setText("Renamed AppData to " + neu); render();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Render the filtered, date-grouped, collapsible list. {@code all != null} -> DEVICE-PROFILE mode (the
     *  fingerprint-only Vault.Entry list at top level). {@code all == null} -> LOGIN mode: the drilled-in
     *  app's saved logins ({@link #vaultApp}), keyed by the login label's date prefix if present, else by day. */
    private LinearLayout savedListHolder;
    private ViewGroup savedListParent;   // where renderSavedList attaches its holder (null = content)
    private void renderSavedList(java.util.List<Vault.Entry> all) {
        final boolean loginMode = all == null;
        if (savedListHolder == null) {
            savedListHolder = new LinearLayout(this);
            savedListHolder.setOrientation(LinearLayout.VERTICAL);
            (savedListParent != null ? savedListParent : content).addView(savedListHolder);
        }
        savedListHolder.removeAllViews();
        String q = vaultQuery.trim().toLowerCase();

        // Build date-grouped buckets. In login mode the items are AppDataVault.Entry (grouped by savedAt day);
        // in profile mode they are Vault.Entry (grouped by the label's MMDDYY-Day prefix).
        java.util.LinkedHashMap<String, java.util.List<Object>> groups = new java.util.LinkedHashMap<>();
        int shown = 0;
        if (loginMode) {
            java.text.SimpleDateFormat dayKey = new java.text.SimpleDateFormat("EEE MM/dd/yy", java.util.Locale.US);
            for (com.specter.module.gen.AppDataVault.Entry a : loginsByApp.getOrDefault(vaultApp, java.util.Collections.emptyList())) {
                String when = a.savedAt > 0 ? dayKey.format(new java.util.Date(a.savedAt)) : "(unknown date)";
                // Match the date OR the login's (renamed) label, so a named login is findable by name.
                if (!q.isEmpty() && !when.toLowerCase().contains(q) && !a.label.toLowerCase().contains(q)) continue;
                shown++;
                groups.computeIfAbsent(when, k -> new java.util.ArrayList<>()).add(a);
            }
        } else {
            // Under the "Both" facet, show only fingerprints that have at least one linked AppData.
            java.util.Set<String> withData = vaultFilter == 3 ? fingerprintsWithAppData() : null;
            for (Vault.Entry e : all) {
                if (!q.isEmpty() && !e.label.toLowerCase().contains(q) && !e.device.toLowerCase().contains(q)) continue;
                if (withData != null && !withData.contains(e.label)) continue;
                shown++;
                String[] parts = e.label.split("-");
                String group = parts.length >= 2 ? parts[0] + "-" + parts[1] : e.label;   // "072626-Sun"
                groups.computeIfAbsent(group, k -> new java.util.ArrayList<>()).add(e);
            }
        }
        if (shown == 0) {
            TextView none = value(!q.isEmpty() ? "No matches for \"" + vaultQuery + "\"."
                    : loginMode ? "No saved AppData for this app." : "No fingerprints saved.");
            none.setTextColor(Theme.DIM);
            savedListHolder.addView(none);
            return;
        }
        // Every expand/collapse + seed key is QUALIFIED by (mode·app) so two different lists that happen to
        // share a date-group label ("Wed 07/29/26") don't share collapse state, and a manual collapse sticks.
        final String scope = loginMode ? "L:" + vaultApp + ":" : "P:";
        // The MOST RECENT group starts EXPANDED, seeded exactly ONCE per scoped group. Search force-opens all.
        if (q.isEmpty() && !groups.isEmpty()) {
            String recentKey = scope + groups.keySet().iterator().next();
            if (seededRecentGroups.add(recentKey)) expandedGroups.add(recentKey);
        }
        for (Map.Entry<String, java.util.List<Object>> g : groups.entrySet()) {
            final String groupKey = scope + g.getKey();
            final boolean collapsed = !expandedGroups.contains(groupKey) && q.isEmpty();
            TextView header = new TextView(this);
            String pretty = loginMode ? g.getKey() : prettyGroup(g.getKey());
            header.setText((collapsed ? "▸  " : "▾  ") + pretty + "   (" + g.getValue().size() + ")");
            header.setTextColor(Theme.GOLD);
            header.setTextSize(13);
            header.setPadding(dp(Theme.S4), dp(10), dp(Theme.S4), dp(4));
            header.setOnClickListener(v -> {
                if (expandedGroups.contains(groupKey)) expandedGroups.remove(groupKey);
                else expandedGroups.add(groupKey);
                renderSavedList(all);
            });
            savedListHolder.addView(header);
            if (!collapsed) for (Object o : g.getValue())
                savedListHolder.addView(loginMode
                        ? loginRow((com.specter.module.gen.AppDataVault.Entry) o)
                        : savedRow((Vault.Entry) o));
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

    /** The shared Vault-row action area: a full-width Restore + a chevron that expands an INLINE actions strip
     *  (Rename · Export · Delete) inside the card — no AlertDialog list popup. `key` is a per-row expand key.
     *  Restore is dominant; the chevron is a quiet trailing toggle, so the two don't read as mismatched sizes. */
    private View rowActions(final String key, final Runnable onRestore, final Runnable onRename,
                            final Runnable onExport, final Runnable onDelete) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final boolean open = expandedRows.contains(key);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(Theme.S2), 0, 0);
        Button restore = compactButton("Restore", true, v -> onRestore.run());
        restore.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        restore.setGravity(Gravity.CENTER);
        row.addView(restore);
        addGap(row);
        // Chevron toggle (rotates when open) — a real disclosure affordance, not a bare "…".
        ImageView chev = new ImageView(this);
        chev.setImageDrawable(icChevron(open ? 1 : 0, dp(18)).tint(Theme.SOFT));
        chev.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        chev.setPadding(dp(Theme.S3), dp(Theme.S3), dp(Theme.S3), dp(Theme.S3));
        chev.setBackground(ripple(dp(Theme.R_CTRL)));
        chev.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        chev.setOnClickListener(v -> { if (open) expandedRows.remove(key); else expandedRows.add(key); render(); });
        row.addView(chev);
        col.addView(row);

        if (open) {
            // Inline actions strip: three equal quiet buttons, Delete tinted red. Appears/vanishes with the chevron.
            LinearLayout strip = new LinearLayout(this);
            strip.setOrientation(LinearLayout.HORIZONTAL);
            strip.setPadding(0, dp(Theme.S2), 0, 0);
            strip.addView(inlineAction("Rename", Theme.SOFT, onRename));
            addGap(strip);
            strip.addView(inlineAction("Export", Theme.SOFT, onExport));
            addGap(strip);
            strip.addView(inlineAction("Delete", Theme.RED, onDelete));
            col.addView(strip);
        }
        return col;
    }

    /** Move a row's expand key when its label changes (rename), so an open actions strip stays open. */
    private void moveExpandedKey(String prefix, String oldLabel, String newLabel) {
        if (expandedRows.remove(prefix + oldLabel)) expandedRows.add(prefix + newLabel);
    }

    /** One equal-weight quiet button for the inline actions strip. */
    private Button inlineAction(String text, int color, final Runnable onClick) {
        Button b = compactButton(text, false, v -> onClick.run());
        b.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        b.setGravity(Gravity.CENTER);
        b.setTextColor(color);
        return b;
    }

    /** A styled in-app confirm (replaces the raw AlertDialog for destructive Vault actions). */
    private void confirmDelete(String title, String message, final Runnable onConfirm) {
        confirmDestructive(title, message, "Delete", onConfirm);
    }

    /** Same confirm, with the action's own verb on the positive button ("Remove", "Delete", …) — a button
     *  labelled with what it does beats a generic OK. Every destructive action routes through here so the
     *  confirm looks and behaves identically everywhere in the app. */
    private void confirmDestructive(String title, String message, String verb, final Runnable onConfirm) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton(verb, (d, w) -> onConfirm.run())
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** One DEVICE-PROFILE card (fingerprint-only saved identity, top-level "Device profiles" section): the
     *  friendly name/device as title, a readable date + the apps it was applied to, and Restore / ⋯. */
    /** App-icon cluster for a fingerprint row: the tied apps as OVERLAPPING stacked tiles (like an avatar
     *  stack), so 1..N apps read as one group and never break the row width. Shows up to 4 icons, then a "+N"
     *  chip. Each tile has a thin card-colored ring so the overlap stays legible on the dark card. Empty list
     *  (legacy/imported entry with no apps) -> the neutral dashed unlinked tile. */
    private View fpIconCluster(java.util.List<String> pkgs) {
        final int D = dp(28), OVERLAP = dp(9), RING = dp(2);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.setMargins(0, 0, dp(Theme.S3), 0);
        box.setLayoutParams(blp);
        if (pkgs.isEmpty()) { box.addView(unlinkedTile(D)); return box; }
        int show = Math.min(4, pkgs.size());
        for (int i = 0; i < show; i++) {
            // A ring-backed frame holds the icon; later tiles overlap earlier ones via a negative left margin.
            android.widget.FrameLayout tile = new android.widget.FrameLayout(this);
            android.graphics.drawable.GradientDrawable ring = new android.graphics.drawable.GradientDrawable();
            ring.setColor(Theme.CARD); ring.setCornerRadius(dp(8));
            tile.setBackground(ring);
            tile.setPadding(RING, RING, RING, RING);
            ImageView iv = new ImageView(this);
            iv.setImageDrawable(appIcon(pkgs.get(i), D));
            tile.addView(iv, new android.widget.FrameLayout.LayoutParams(D, D));
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) tlp.setMargins(-OVERLAP, 0, 0, 0);   // overlap the previous tile
            tile.setLayoutParams(tlp);
            box.addView(tile);
        }
        if (pkgs.size() > show) {
            TextView more = new TextView(this);
            more.setText("+" + (pkgs.size() - show));
            more.setTextColor(Theme.DIM); more.setTextSize(11);
            LinearLayout.LayoutParams mlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            mlp.setMargins(dp(5), 0, 0, 0);
            more.setLayoutParams(mlp);
            box.addView(more);
        }
        return box;
    }

    /** A neutral dashed placeholder tile for a fingerprint with no linked AppData — signals "not tied to an
     *  app yet" without pretending there's an app icon. A rounded dashed square with a faint device glyph feel. */
    private View unlinkedTile(int px) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(px, px));
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(Theme.BG2);
        g.setCornerRadius(dp(7));
        g.setStroke(dp(1), Theme.LINE, dp(3), dp(2));   // dashed border = "empty / unlinked"
        v.setBackground(g);
        return v;
    }

    /** "Dasher" / "Dasher, Cash App" / "Dasher, Cash App +2" from a pkg list (linked-AppData apps). */
    private String appNamesText(java.util.List<String> pkgs) {
        if (pkgs.isEmpty()) return null;
        int show = Math.min(2, pkgs.size());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < show; i++) { if (i > 0) sb.append(", "); sb.append(appLabel(pkgs.get(i))); }
        if (pkgs.size() > show) sb.append(" +").append(pkgs.size() - show);
        return sb.toString();
    }

    private View savedRow(final Vault.Entry e) {
        LinearLayout card = cardBox();

        // Header row: an app-icon cluster (the app(s) this fingerprint is tied to — linked AppData first, then
        // applied-to targets) on the left, the name/device text on the right. Every real fingerprint has ≥1 app;
        // only a legacy/imported entry with neither falls back to the unlinked tile.
        java.util.List<String> tiedApps = appsForFingerprint(e);
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(fpIconCluster(tiedApps));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        String name = labelName(e.label);
        TextView lab = label(name.isEmpty() ? e.device : name);
        lab.setTextColor(Theme.INK);
        lab.setTextSize(15);
        col.addView(lab);
        TextView dev = value(labelWhen(e.label) + (name.isEmpty() ? "" : "  ·  " + e.device));
        dev.setTextColor(Theme.SOFT);
        dev.setTextSize(12);
        col.addView(dev);
        // Name the same tied app(s) as the icon cluster (icons alone don't say "Dasher"). Null only when the
        // entry has no apps at all (legacy/import) — then just the device name shows.
        String tie = appNamesText(tiedApps);
        if (tie != null) {
            TextView tv = value(tie);
            tv.setTextColor(Theme.DIM);
            tv.setTextSize(11);
            col.addView(tv);
        }
        head.addView(col);
        card.addView(head);

        card.addView(rowActions("fp:" + e.label,
                () -> restoreSaved(e.label),
                () -> promptRenameFingerprint(e.label),
                () -> exportFingerprintChoice(e.label),
                () -> confirmDelete("Delete fingerprint?", e.label, () -> {
                    boolean gone = vault.delete(e.label);
                    status.setText(gone ? "Deleted " + e.label : "Could not delete " + e.label);
                    render();
                })));
        return card;
    }

    /** The apps a fingerprint is tied to, for its icon cluster. A fingerprint is device-level but is always
     *  saved AGAINST at least one app: "Save current to vault" requires an applied target, and an AppData
     *  capture saves the fingerprint against that app. So we take the UNION of two sources, deduped, in this
     *  priority so the most meaningful icon leads:
     *    1) apps whose saved AppData links to this fingerprint (real captured data), newest AppData first;
     *    2) apps this fingerprint was APPLIED to (e.targets) but has no AppData for.
     *  Empty only for a legacy/imported entry that carries neither — then the row shows the unlinked tile. */
    private java.util.List<String> appsForFingerprint(Vault.Entry e) {
        java.util.LinkedHashMap<String, Long> newestByApp = new java.util.LinkedHashMap<>();
        for (java.util.List<com.specter.module.gen.AppDataVault.Entry> list : loginsByApp.values())
            for (com.specter.module.gen.AppDataVault.Entry a : list)
                if (e.label.equals(a.fingerprint)) {
                    Long cur = newestByApp.get(a.pkg);
                    if (cur == null || a.savedAt > cur) newestByApp.put(a.pkg, a.savedAt);
                }
        java.util.List<String> pkgs = new java.util.ArrayList<>(newestByApp.keySet());
        pkgs.sort((x, y) -> Long.compare(newestByApp.get(y), newestByApp.get(x)));   // newest AppData first
        // Append applied-to targets that don't already have AppData (they still identify the fingerprint).
        if (e.targets != null && !e.targets.isEmpty())
            for (String t : e.targets.split(",")) {
                String pkg = t.trim();
                if (!pkg.isEmpty() && !newestByApp.containsKey(pkg)) pkgs.add(pkg);
            }
        return pkgs;
    }

    /** The AppData linked to a fingerprint label (the newest, if several), or null. */
    private com.specter.module.gen.AppDataVault.Entry appDataForFingerprint(String fpLabel) {
        for (java.util.List<com.specter.module.gen.AppDataVault.Entry> list : loginsByApp.values())
            for (com.specter.module.gen.AppDataVault.Entry a : list)
                if (fpLabel.equals(a.fingerprint)) return a;
        return null;
    }

    /** Export a Fingerprint: if it has linked AppData, let the user pick Fingerprint-only or a combined bundle. */
    private void exportFingerprintChoice(final String label) {
        final com.specter.module.gen.AppDataVault.Entry linked = appDataForFingerprint(label);
        if (linked == null) { exportFingerprint(label); return; }
        new AlertDialog.Builder(this)
                .setTitle("Export")
                .setItems(new String[]{"Fingerprint only", "With its AppData (one file)"}, (d, w) -> {
                    if (w == 0) exportFingerprint(label);
                    else exportCombo(linked.label);
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    /** Export a saved fingerprint envelope to the Specter folder (off the UI thread — it shells out to su). */
    private void exportFingerprint(final String label) {
        new Thread(() -> {
            final String path = vault.exportToDownloads(label);
            runOnUiThread(() -> {
                if (path != null) { toast("Exported to " + path); status.setText("Exported Fingerprint -> " + path); }
                else toast("Export failed.");
            });
        }, "specter-fp-export").start();
    }

    /** Export a COMBINED bundle (AppData + its linked Fingerprint) as one file in the Specter folder. */
    private void exportCombo(final String appDataLabel) {
        final com.specter.module.gen.AppDataVault.Entry ad = appDataVault.get(appDataLabel);
        if (ad == null || ad.fingerprint.isEmpty()) { toast("No linked fingerprint to bundle."); return; }
        new Thread(() -> {
            final String env = vault.envelopeFor(ad.fingerprint);
            if (env == null) { runOnUiThread(() -> toast("Linked fingerprint missing — export separately.")); return; }
            final String path = appDataVault.exportCombo(appDataLabel, env);
            runOnUiThread(() -> {
                if (path != null) { toast("Exported bundle to " + path); status.setText("Exported combined bundle -> " + path); }
                else toast("Bundle export failed.");
            });
        }, "specter-combo-export").start();
    }

    /** Enter the dedicated Import browse screen: scan for importable files off the UI thread, then render a
     *  properly-styled list (with a back button) — not an Android pop-up. */
    private void openImportScreen() {
        vaultImport = true;
        importPaths = null;   // null = still scanning (the screen shows a "Scanning…" state)
        render();
        new Thread(() -> {
            final java.util.List<String> names = new java.util.ArrayList<>();
            Process pr = null;
            try {
                // Look in the Specter export folder FIRST (where we now write), then legacy Download/ locations
                // (older exports + Specter Lite harvests). -M (mount-master) for the Magisk namespace.
                pr = Runtime.getRuntime().exec(new String[]{"su", "-M", "-c",
                        "ls -1t /sdcard/Download/Specter/specter-combo-*.tar "
                        + "/sdcard/Download/Specter/specter-profile-*.json "
                        + "/sdcard/Download/Specter/specter-login-*.tar "
                        // Specter Lite harvests now land here too (they used to go to Download/Specter-exports,
                        // still scanned below so pre-existing harvests keep importing).
                        + "/sdcard/Download/Specter/Specter-*.json "
                        + "/sdcard/Download/specter-combo-*.tar "
                        + "/sdcard/Download/specter-profile-*.json "
                        + "/sdcard/Download/specter-login-*.tar "
                        + "/sdcard/Download/Specter-*.json "
                        + "/sdcard/Download/Specter-exports/*.json 2>/dev/null"});
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(pr.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) { line = line.trim(); if (!line.isEmpty() && !names.contains(line)) names.add(line); }
                }
                pr.waitFor();
            } catch (Throwable ignored) {}
            finally { if (pr != null) pr.destroy(); }
            runOnUiThread(() -> { if (!alive() || !vaultImport) return; importPaths = names; render(); });
        }, "specter-import-scan").start();
    }

    /** Leave the Import screen and return to the main Vault list. */
    private void closeImportScreen() { vaultImport = false; importPaths = null; render(); }

    /** The dedicated Import browse screen (a Vault sub-view): a back header + one styled card per importable
     *  file, each showing its type (Fingerprint / AppData / Combined) and name, tap to import. */
    private void renderImportScreen() {
        // Back header — the shared gold-chevron control.
        LinearLayout back = Nav.backRow(this, "Import", this::closeImportScreen);
        back.setBackground(ripple(0));
        content.addView(back);

        TextView hint = value("Files exported from Specter, found in Download/Specter. Tap one to import it.");
        hint.setTextColor(Theme.DIM); hint.setTextSize(Theme.T_CAPTION);
        hint.setPadding(dp(Theme.S4) + dp(Theme.S1), 0, dp(Theme.S4), dp(Theme.S3));
        content.addView(hint);

        if (importPaths == null) {
            LinearLayout c = cardBox();
            TextView t = value("Scanning…"); t.setTextColor(Theme.DIM);
            c.addView(t); content.addView(c);
            return;
        }
        if (importPaths.isEmpty()) {
            LinearLayout c = cardBox();
            TextView t = value("Nothing to import. Export a Fingerprint or AppData first, or place a shared "
                    + "bundle in Download/Specter.");
            t.setTextColor(Theme.DIM); c.addView(t); content.addView(c);
            return;
        }
        for (final String path : importPaths) content.addView(importFileCard(path));
    }

    /** One styled card in the Import screen: a type badge + the file name, tap to import (routes by name). */
    private View importFileCard(final String path) {
        final String base = path.substring(path.lastIndexOf('/') + 1);
        final boolean isCombo = base.startsWith("specter-combo-") && base.endsWith(".tar");
        final boolean isLogin = base.startsWith("specter-login-") && base.endsWith(".tar");
        final String type = isCombo ? "Combined (Fingerprint + AppData)" : isLogin ? "AppData" : "Fingerprint";

        LinearLayout card = cardBox();
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(ripple(dp(Theme.R_CARD)));
        // a small deterministic tile keyed by the file type, so each row reads at a glance
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(new MonogramIcon(dp(30), type, isCombo ? "combo" : isLogin ? "appdata" : "fingerprint"));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(30), dp(30));
        ilp.setMargins(0, 0, dp(Theme.S3), 0); ic.setLayoutParams(ilp); card.addView(ic);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView t = new TextView(this);
        t.setText(type); t.setTextColor(Theme.INK); t.setTextSize(Theme.T_BODY);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        col.addView(t);
        TextView s = new TextView(this);
        s.setText(base); s.setTextColor(Theme.SOFT); s.setTextSize(Theme.T_CAPTION); s.setSingleLine(true);
        s.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        col.addView(s);
        card.addView(col);
        card.addView(chevronTrailing(false));
        card.setOnClickListener(v -> {
            final java.io.File src = new java.io.File(path);
            status.setText("Importing…");
            closeImportScreen();   // return to the list; the import runs + re-renders on completion
            if (isCombo) importCombo(src);
            else if (isLogin) importLogin(src);
            else importFingerprintFile(src, base);
        });
        return card;
    }

    /** Import an AppData (.tar) bundle into the AppData vault. */
    private void importLogin(final java.io.File src) {
        new Thread(() -> {
            final String lbl = appDataVault.importFromDownloads(src);
            runOnUiThread(() -> {
                if (lbl != null) { status.setText("Imported AppData " + lbl); toast("Imported AppData " + lbl); render(); }
                else { status.setText("AppData import failed (grant root? valid bundle?)"); toast("AppData import failed."); }
            });
        }, "specter-appdata-import").start();
    }

    /** Import a Fingerprint envelope (.json) into the Fingerprint vault. */
    private void importFingerprintFile(final java.io.File src, final String base) {
        final String stem = base.replace("specter-profile-", "").replace("Specter-", "").replace(".json", "");
        new Thread(() -> {
            final Vault.ImportResult r = vault.importOnce(src, "imported-" + stem);
            runOnUiThread(() -> {
                if (r.ok()) { status.setText("Imported Fingerprint " + r.label + " — restore it to apply."); toast("Imported Fingerprint " + r.label); render(); }
                else { status.setText("Import failed: " + r.error); toast("Import failed: " + r.error); }
            });
        }, "specter-import").start();
    }

    /** Import a COMBINED bundle: extract to an app-owned temp dir, import the Fingerprint envelope AND the
     *  AppData pair, then clean up. Either half can fail independently; report what landed. */
    private void importCombo(final java.io.File src) {
        new Thread(() -> {
            java.io.File tmp = appDataVault.importComboToTemp(src);
            String fpMsg = "", adMsg = ""; boolean ok = false;
            if (tmp == null) {
                fpMsg = "bundle invalid or root denied";
            } else {
                try {
                    String label = com.specter.module.gen.AppDataVault.labelOfBundle(src.getName());
                    // Fingerprint half (app-owned temp file → no su).
                    java.io.File fpJson = com.specter.module.gen.AppDataVault.comboJson(tmp, label);
                    Vault.ImportResult fr = vault.importEnvelopeFile(fpJson, "imported-" + label);
                    fpMsg = fr.ok() ? "Fingerprint " + fr.label : "Fingerprint failed (" + fr.error + ")";
                    // AppData half.
                    String adLabel = appDataVault.ingestPairFromDir(tmp, label);
                    adMsg = adLabel != null ? "AppData " + adLabel : "AppData failed";
                    // Keep the two halves LINKED on this device: the fingerprint imported under a NEW label, so
                    // repoint the AppData's stored link at it. Use relinkOne (this entry ONLY) — NOT a sweep by
                    // the old label, which is untrusted and could collide with a pre-existing local entry.
                    if (fr.ok() && adLabel != null) appDataVault.relinkOne(adLabel, fr.label);
                    ok = fr.ok() || adLabel != null;
                } finally {
                    com.specter.module.gen.AppDataVault.deleteDir(tmp);   // always remove the extracted temp
                }
            }
            final String msg = "Imported: " + fpMsg + " · " + adMsg; final boolean fOk = ok;
            runOnUiThread(() -> { status.setText(msg); toast(fOk ? msg : "Combined import failed."); if (fOk) render(); });
        }, "specter-combo-import").start();
    }

    /** Load a saved profile into the current identity AND apply it to the selected target app(s). */
    private void restoreSaved(final String labelStr) {
        if (opBusy) { toast("Busy — wait for the current apply/restore to finish."); return; }
        final Map<String, String> saved = vault.load(labelStr);
        if (saved == null || saved.isEmpty()) { toast("Could not read that saved profile."); return; }
        profile = new LinkedHashMap<>(saved);
        activeVaultLabel = labelStr;   // restoring a fingerprint makes IT the active one for AppData linkage
        // appliedByPkg keeps its entries: they describe what each app is wearing, and this restore has not
        // touched any app yet. Nothing matches the just-loaded identity, so it still reads "not applied".
        persistCurrentState();
        Set<String> targets = Targets.get(prefs);
        if (targets.isEmpty()) {
            status.setText("Restored " + labelStr + " — pick a target app (Settings), then it will apply.");
            toast("Restored into the current identity. Select a target app to apply.");
            return;
        }
        final Map<String, String> toApply = enabledProfile();   // applies protection gates too
        final List<String> pkgs = new ArrayList<>(targets);
        final String sig = applySignature(toApply);   // recorded per app it reaches, so an immediate APPLY is a
                                                      // no-op there (not a needless second deep-clean)
        // Same as APPLY: tear the monitor state down here, finish the su work on the wipe thread (see apply()).
        final String flushPkg = beginFlushBeforeWipe(pkgs);
        opBusy = true;
        render();   // reflect busy state immediately, same as apply() (keeps the two wipe paths consistent)
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
                    for (String pkg : pkgs) {   // only the apps it actually reached carry the identity
                        if (okPkgs.contains(pkg)) appliedByPkg.put(pkg, sig);
                        else appliedByPkg.remove(pkg);
                    }
                    persistCurrentState();
                    if (allClean) toast("Wiped and restored to " + pkgs.size() + " app(s).");
                    else if (clearedN > 0) toast("⚠️ Only " + clearedN + "/" + pkgs.size()
                            + " app(s) done — grant root in Magisk?");
                    String tail = (clrErr != null ? " Clear error: " + clrErr : "")
                            + (err != null ? " Apply error: " + err : "")
                            + (clrErr == null && err == null ? " Relaunch them to see it." : "");
                    status.setText("Restored " + labelStr + " to " + okN + "/" + pkgs.size() + " app(s)." + tail);
                } finally {
                    opBusy = false;
                    render();   // clear the busy state on the summary/hero
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
                    persistCurrentState();
                    status.setText("Saved as " + label);
                    toast("Saved to vault: " + label);
                    // Refresh either tab: the Saved list gains the new entry (its date group auto-expands via
                    // the seed logic), and the Identity hero drops its now-answered "Save this identity" row.
                    render();
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
        t.setPadding(dp(Theme.S4) + dp(Theme.S1), dp(Theme.S4), dp(Theme.S4), dp(Theme.S2));
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

    /** A disabled primary button (dimmed, non-interactive) shown while an operation runs. */
    private View disabledButton(String text) {
        TextView b = new TextView(this);
        b.setText(text); b.setTextColor(0x88211B02); b.setTextSize(Theme.T_BODY);
        b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        b.setGravity(Gravity.CENTER);
        b.setMinimumHeight(dp(48));
        b.setPadding(dp(Theme.S4), dp(Theme.S3), dp(Theme.S4), dp(Theme.S3));
        GradientDrawable base = new GradientDrawable();
        base.setColor(0x66FFD54A);   // dimmed gold
        base.setCornerRadius(dp(Theme.R_CTRL));
        b.setBackground(base);
        b.setEnabled(false);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return b;
    }

    /** A small status pill: a colored dot + a word (Ready / Applying… / Applied). Reads at a glance. The dot
     *  carries a faint same-hue halo ring so it reads as a polished indicator, not a flat circle — subtle,
     *  not obnoxious. Colour encodes state: SOFT=Ready, GOLD=Applying, SAGE=Applied. */
    private View statusPill(String text, int color) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(Theme.S1), 0, dp(Theme.S3));
        // A 12dp halo frame holds a 7dp solid dot centered, with a ~15%-alpha ring of the same hue behind it.
        android.widget.FrameLayout dotWrap = new android.widget.FrameLayout(this);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(dp(12), dp(12));
        wlp.setMargins(0, 0, dp(Theme.S2), 0);
        wlp.gravity = Gravity.CENTER_VERTICAL;
        GradientDrawable halo = new GradientDrawable();
        halo.setShape(GradientDrawable.OVAL);
        halo.setColor((color & 0x00FFFFFF) | 0x26000000);   // same hue, ~15% alpha
        dotWrap.setBackground(halo);
        View dot = new View(this);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL); d.setColor(color);
        dot.setBackground(d);
        android.widget.FrameLayout.LayoutParams dlp = new android.widget.FrameLayout.LayoutParams(dp(7), dp(7));
        dlp.gravity = Gravity.CENTER;
        dotWrap.addView(dot, dlp);
        row.addView(dotWrap, wlp);
        TextView t = new TextView(this);
        t.setText(text); t.setTextColor(color); t.setTextSize(Theme.T_CAPTION);
        t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        row.addView(t);
        return row;
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
        if (glyph instanceof StrokeIcon) ((StrokeIcon) glyph).tint(tint);   // tint DIRECTLY (setColorFilter no-ops)
        iv.setImageDrawable(glyph);
        iv.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = dp(12);
        iv.setPadding(pad, pad, pad, pad);
        iv.setBackground(ripple(dp(Theme.R_PILL)));
        iv.setOnClickListener(onClick);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(48), dp(48)));
        return iv;
    }

    /** The app's launcher icon if it's installed, else a generated monogram tile (rounded square, the app's
     *  first letter, a colour derived from the package) — so EVERY app row has a proper icon, never a blank. */
    private android.graphics.drawable.Drawable appIcon(String pkg, int px) {
        try { return getPackageManager().getApplicationIcon(pkg); }
        catch (Throwable ignored) { return new MonogramIcon(px, appLabel(pkg), pkg); }
    }

    /** A clean human label for a package. Installed -> its real label. Uninstalled -> the package's last
     *  meaningful segment title-cased (com.ubercab.driver -> "Ubercab Driver"), never a raw dotted string. */
    private String appLabel(String pkg) {
        String resolved = Targets.label(this, pkg);
        if (!resolved.equals(pkg)) return resolved;   // PackageManager resolved a real label
        String[] parts = pkg.split("\\.");
        // Take the last 1-2 segments, dropping a generic trailing word so context survives.
        int take = 1;
        String last = parts.length > 0 ? parts[parts.length - 1] : pkg;
        if (parts.length >= 2 && (last.equalsIgnoreCase("driver") || last.equalsIgnoreCase("app")
                || last.equalsIgnoreCase("android") || last.equalsIgnoreCase("mobile"))) take = 2;
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, parts.length - take); i < parts.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            String seg = parts[i];
            if (!seg.isEmpty()) sb.append(Character.toUpperCase(seg.charAt(0))).append(seg.substring(1));
        }
        return sb.length() > 0 ? sb.toString() : pkg;
    }

    /** A generated app-icon tile for packages with no installed launcher icon: a rounded square filled with a
     *  package-derived colour, the app's first letter centred in it. Deterministic per package. */
    private final class MonogramIcon extends android.graphics.drawable.Drawable {
        private final int px; private final String letter; private final int color;
        private final android.graphics.Paint bg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private final android.graphics.Paint tp = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        MonogramIcon(int px, String label, String pkg) {
            this.px = px;
            this.letter = (label == null || label.isEmpty()) ? "?" : label.substring(0, 1).toUpperCase();
            // Deterministic hue from the package hash → a muted, on-brand-ish fill.
            float hue = (pkg.hashCode() & 0x7fffffff) % 360;
            this.color = android.graphics.Color.HSVToColor(new float[]{hue, 0.35f, 0.55f});
            bg.setColor(color);
            tp.setColor(0xFFFFFFFF); tp.setTextAlign(android.graphics.Paint.Align.CENTER);
            tp.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
        }
        @Override public void draw(android.graphics.Canvas c) {
            android.graphics.Rect b = getBounds();
            float r = b.width() * 0.28f;
            c.drawRoundRect(new android.graphics.RectF(b), r, r, bg);
            tp.setTextSize(b.height() * 0.56f);
            android.graphics.Paint.FontMetrics fm = tp.getFontMetrics();
            float y = b.centerY() - (fm.ascent + fm.descent) / 2f;
            c.drawText(letter, b.centerX(), y, tp);
        }
        @Override public int getIntrinsicWidth() { return px; }
        @Override public int getIntrinsicHeight() { return px; }
        @Override public void setAlpha(int a) {}
        @Override public void setColorFilter(android.graphics.ColorFilter cf) {}
        @Override public int getOpacity() { return android.graphics.PixelFormat.OPAQUE; }
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
    private StrokeIcon icChevron(final int dir, final int px) {
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
    private StrokeIcon icClose(final int px) {
        return new StrokeIcon(px) {
            @Override void draw(android.graphics.Canvas c, android.graphics.Paint p, float s) {
                float a = s * 0.32f, b = s * 0.68f;
                c.drawLine(a, a, b, b, p); c.drawLine(b, a, a, b, p);
            }
        };
    }

    /** A plus (+) for "add". */
    private StrokeIcon icPlus(final int px) {
        return new StrokeIcon(px) {
            @Override void draw(android.graphics.Canvas c, android.graphics.Paint p, float s) {
                float a = s * 0.28f, b = s * 0.72f, m = s * 0.5f;
                c.drawLine(a, m, b, m, p); c.drawLine(m, a, m, b, p);
            }
        };
    }

    /** An overflow (⋯) three-dot for row menus. */
    private StrokeIcon icMore(final int px) {
        return new StrokeIcon(px) {
            @Override void draw(android.graphics.Canvas c, android.graphics.Paint p, float s) {
                p.setStyle(android.graphics.Paint.Style.FILL);
                float r = s * 0.05f, y = s * 0.5f;
                c.drawCircle(s * 0.28f, y, r, p); c.drawCircle(s * 0.5f, y, r, p); c.drawCircle(s * 0.72f, y, r, p);
            }
        };
    }

    /** Base class: a Drawable that draws a stroked glyph on a square canvas. Set the colour with {@link #tint}
     *  DIRECTLY — do NOT rely on ImageView.setColorFilter(int): that routes to setColorFilter(ColorFilter),
     *  from which the plain color can't be recovered pre-API-29, so the icons would render white. */
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
        StrokeIcon tint(int color) { filter = color; invalidateSelf(); return this; }
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
