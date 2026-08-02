package com.specter.module;

import android.os.Build;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * System_server-side app-hiding — the ONE gate that closes the raw-IPackageManager-binder bypass a per-app
 * PackageManager hook can't (an SDK calling {@code ServiceManager.getService("package")} directly skips the
 * app-side wrapper). Modeled on HideMyApplist (Dr-TSNG): hook {@code AppsFilter.shouldFilterApplication}
 * (API 30–32) / {@code AppsFilterImpl.shouldFilterApplication} (API 33+) inside system_server and force it
 * to return {@code true} (= "target is invisible to caller") when a SCOPED target app queries a SENSITIVE
 * package (root/hook/gps-spoofer/proxy — {@link SpoofLogic#isSensitivePackage}).
 *
 * <p>SAFETY (why this can't brick the device, matching HMA's guards):
 * <ul>
 *   <li>Never filters for a system/privileged caller (uid == 1000 or uid &lt; 10000) — the launcher, Settings,
 *       PMS itself keep full visibility, so the framework never loses an app it needs.</li>
 *   <li>Never filters a CRITICAL system package ({@link #NEVER_HIDE}) even from a scoped caller.</li>
 *   <li>Never filters caller == target (self).</li>
 *   <li>Only filters when the CALLER is one of our scoped targets (has a profile) AND the target is sensitive
 *       — a blacklist scoped to our own apps, not a blanket hide.</li>
 *   <li>The whole hook body is wrapped so any unexpected throwable flips a kill switch (fail-open: on error
 *       the gate reverts to stock AOSP behavior rather than crashing system_server).</li>
 *   <li>The caller is derived from the {@code callingSetting} ARG already passed to the hook — we do NOT
 *       call back into PMS ({@code getPackagesForUid}) from its own visibility gate, which would re-enter
 *       this method and risk a lock inversion / deadlock in system_server.</li>
 * </ul>
 */
final class PmsHook {
    private PmsHook() {}

    private static final String TAG = "[specter][pms]";
    private static final int UID_SYSTEM = 1000;
    private static final int FIRST_APP_UID = 10000;   // Process.FIRST_APPLICATION_UID
    private static final String PROFILE_DIR = "/data/local/tmp/specter/";

    // Critical packages we NEVER hide, even from a scoped caller — hiding these breaks the framework/GMS.
    private static final Set<String> NEVER_HIDE = new HashSet<>(java.util.Arrays.asList(
            "android", "android.media", "android.uid.system", "android.uid.shell", "android.uid.systemui",
            "com.android.permissioncontroller", "com.android.providers.downloads",
            "com.android.providers.downloads.ui", "com.android.providers.media",
            "com.android.providers.media.module", "com.android.providers.settings",
            "com.google.android.webview", "com.google.android.gms", "com.google.android.gsf"));

    // Fail-open kill switch: any unexpected throwable in the hook flips this, and every later call short-
    // circuits to stock AOSP behavior. Simpler + race-free vs truly unhooking (and the stub has no Unhook).
    private static volatile boolean disabled = false;
    // Scoped-target package set (apps with a profile), refreshed at most every REFRESH_MS.
    private static volatile Set<String> scoped = java.util.Collections.emptySet();
    private static volatile long scopedAt = 0;
    private static final long REFRESH_MS = 5000;
    // The PackageSetting field holding the package name: "name" (<=API32) or "mName" (>=API33).
    private static final String NAME_FIELD = Build.VERSION.SDK_INT >= 33 ? "mName" : "name";

    static void install(XC_LoadPackage.LoadPackageParam lp) {
        try {
            // API 33+ split AppsFilter into AppsFilterImpl (+ a Computer snapshot first param); 30–32 use
            // AppsFilter directly. Hook whichever class exists on this build.
            Class<?> cls = XposedHelpers.findClassIfExists("com.android.server.pm.AppsFilterImpl", lp.classLoader);
            if (cls == null) cls = XposedHelpers.findClassIfExists("com.android.server.pm.AppsFilter", lp.classLoader);
            if (cls == null) { XposedBridge.log(TAG + " AppsFilter not found — app-hiding gate not installed"); return; }
            final Class<?> hooked = cls;
            XC_MethodHook cb = new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam mp) {
                    if (disabled) return;
                    try { filter(mp); }
                    catch (Throwable t) { disabled = true; XposedBridge.log(TAG + " error, gate disabled: " + t); }
                }
            };
            // hookAllMethods matches every shouldFilterApplication overload/signature across API levels.
            java.util.Set<?> hs = XposedBridge.hookAllMethods(hooked, "shouldFilterApplication", cb);
            XposedBridge.log(TAG + " app-hiding gate installed on " + hooked.getName() + " (" + hs.size() + " method(s))");
            // Only attest the gate when it ACTUALLY hooked at least one method — hookAllMethods can return an
            // empty set (no matching method / obfuscation drift), which is a non-installed gate, not a loaded one.
            if (hs.size() > 0) writeFrameworkHeartbeat(hs.size());
        } catch (Throwable t) {
            XposedBridge.log(TAG + " install failed (gate off): " + t);
        }
    }

    /** Boot-scoped attestation the framework gate loaded in system_server THIS boot — the status screen checks
     *  the boot_id so a stale log line from a previous boot can't read as GREEN. system_server (uid system) can
     *  write PROFILE_DIR. Best-effort. */
    private static void writeFrameworkHeartbeat(int methods) {
        try {
            // system_server runs as uid system and CANNOT write the root-owned /data/local/tmp/specter dir, but
            // it CAN write /data/system. Content: methods|epochMs; the status screen checks epoch >= boot time
            // (the boot_id is spoofed per-app, so wall-time is the portable "this boot" signal).
            String line = methods + "|" + System.currentTimeMillis();
            java.io.File f = new java.io.File(HookEntry.FRAMEWORK_HB_PATH);
            java.io.FileOutputStream fo = new java.io.FileOutputStream(f);
            fo.write(line.getBytes("UTF-8"));
            fo.close();
            //noinspection ResultOfMethodCallIgnored
            f.setReadable(true, false);
        } catch (Throwable ignored) {}
    }

    /** The visibility decision. Sets result=true to HIDE the target from the caller. */
    private static void filter(XC_MethodHook.MethodHookParam mp) throws Exception {
        Object[] a = mp.args;
        if (a == null || a.length < 3) return;
        // callingUid is arg0 (API 30–32) or arg1 (API 33+, where arg0 is the Computer snapshot).
        int uidIdx = (a[0] instanceof Integer) ? 0 : 1;
        if (!(a[uidIdx] instanceof Integer)) return;
        int callingUid = (Integer) a[uidIdx];
        if (callingUid == UID_SYSTEM || callingUid < FIRST_APP_UID) return;   // never hide from system/priv callers

        // targetPkgSetting is the LAST-but-one arg (…, targetPkgSetting, int userId). Read its package name.
        Object targetSetting = a[a.length - 2];
        String target = pkgNameOf(targetSetting);
        if (target == null || !SpoofLogic.isSensitivePackage(target) || NEVER_HIDE.contains(target)) return;

        // NOTE: com.specter/.lite/.probe are hidden ONLY from a SCOPED caller (an app we're actively spoofing),
        // via the same caller-gate below as every other sensitive package — NOT from every app. An earlier
        // version hid them from ALL callers, which also hid Specter's own LAUNCHER ICON from the home screen /
        // app drawer (the launcher is a normal uid-10000+ app, so it hit the hide), making the app unusable.
        // The threat is a fingerprinter INSIDE a scoped target enumerating packages — the launcher and Settings
        // are not that, and hiding from them only breaks usability for no detection benefit.

        // Derive the caller package(s) from the callingSetting ARG that's already passed in — NOT by calling
        // back into PMS (IPackageManager.getPackagesForUid re-enters shouldFilterApplication and, even with a
        // cleared identity that stops the recursion, a synchronous self-call into PMS from its own visibility
        // gate risks lock inversion / deadlock in system_server; codex-flagged). callingSetting sits right
        // after callingUid: a PackageSetting (single pkg) or a SharedUserSetting (a set of PackageSettings).
        Object callingSetting = a[uidIdx + 1];
        Set<String> callers = callerPackages(callingSetting);
        if (callers.isEmpty()) return;   // couldn't resolve caller -> don't filter (fail-open)
        Set<String> sc = scopedTargets();
        for (String caller : callers) {
            if (caller.equals(target)) continue;                      // never hide self
            if (sc.contains(caller)) { mp.setResult(true); return; }  // scoped caller querying a sensitive pkg -> hide
        }
    }

    /** Package name(s) held by a callingSetting, WITHOUT any PMS call. A PackageSetting yields one name; a
     *  SharedUserSetting yields the names of every PackageSetting sharing the UID (field "packages"/"mPackages",
     *  an ArraySet). Read purely from the object graph already handed to the hook. */
    private static Set<String> callerPackages(Object callingSetting) {
        Set<String> out = new HashSet<>();
        if (callingSetting == null) return out;
        // Single PackageSetting: it has the version-correct name field directly.
        String single = pkgNameOf(callingSetting);
        if (single != null) { out.add(single); return out; }
        // SharedUserSetting: pull its collection of PackageSettings and read each one's name.
        try {
            Field pf = findField(callingSetting.getClass(), "packages");
            if (pf == null) pf = findField(callingSetting.getClass(), "mPackages");
            if (pf != null) {
                Object coll = pf.get(callingSetting);
                if (coll instanceof Iterable) {
                    for (Object ps : (Iterable<?>) coll) { String n = pkgNameOf(ps); if (n != null) out.add(n); }
                } else if (coll instanceof java.util.Map) {
                    for (Object ps : ((java.util.Map<?, ?>) coll).values()) { String n = pkgNameOf(ps); if (n != null) out.add(n); }
                }
            }
        } catch (Throwable ignored) {}
        return out;
    }

    /** The package name held by a PackageSetting/PackageStateInternal, via the version-correct field. */
    private static String pkgNameOf(Object setting) {
        if (setting == null) return null;
        try {
            Field f = findField(setting.getClass(), NAME_FIELD);
            if (f == null) return null;
            Object v = f.get(setting);
            return (v instanceof String) ? (String) v : null;
        } catch (Throwable t) { return null; }
    }

    private static Field findField(Class<?> c, String name) {
        for (Class<?> k = c; k != null; k = k.getSuperclass()) {
            try { Field f = k.getDeclaredField(name); f.setAccessible(true); return f; } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    /** Scoped targets = packages that have a profile file. Cached; refreshed every REFRESH_MS. */
    private static Set<String> scopedTargets() {
        long now = android.os.SystemClock.elapsedRealtime();
        // Throttle on time alone (not "&& !isEmpty()") — an empty scoped set is a valid cached state; gating
        // on non-empty would re-listFiles() on EVERY call from system_server whenever zero apps are scoped.
        if (scopedAt != 0 && now - scopedAt < REFRESH_MS) return scoped;
        Set<String> s = new HashSet<>();
        try {
            File[] files = new File(PROFILE_DIR).listFiles();
            if (files != null) for (File f : files) {
                String n = f.getName();
                if (n.endsWith(".json")) s.add(n.substring(0, n.length() - 5));
            }
        } catch (Throwable ignored) {}
        scoped = s; scopedAt = now;
        return s;
    }

}
