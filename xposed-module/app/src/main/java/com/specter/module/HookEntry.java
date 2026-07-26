package com.specter.module;

import android.os.Build;
import android.provider.Settings;

import java.io.File;
import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import org.json.JSONObject;

/**
 * GeerGit replacement — per-app identifier spoofing Xposed module.
 *
 * Reads a flat profile.json (pushed to /data/local/tmp/specter/<pkg>.json OR a shared
 * profile) and injects those fake values into the target app. Covers the SAME identifier
 * surface GeerGit 2.9.4 hooks — extracted from its dart string pool — with the fix baked in:
 * every value comes from a freshly-generated profile, so there is no stale-GSF reuse
 * (the 2.9.6 regression that caused the DoorDash "coordinated accounts" bans).
 *
 * Scope which apps get hooked via LSPosed's scope UI (e.g. com.doordash.driverapp).
 */
public class HookEntry implements IXposedHookLoadPackage {

    // where the push .bat drops per-app profiles
    private static final String PROFILE_DIR = "/data/local/tmp/specter/";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        final String pkg = lpparam.packageName;
        final Map<String, String> p = loadProfile(pkg);
        if (p == null || p.isEmpty()) return; // not a scoped/targeted app -> do nothing
        XposedBridge.log("[specter] active for " + pkg + " (" + p.size() + " fields)");

        hookBuildFields(p);
        if (!gateOff(p, "spoof_ua")) hookUserAgent(lpparam, p);
        hookSettingsSecure(p);
        if (!gateOff(p, "hide_dev")) hookSettingsGlobal(p);
        hookTelephony(lpparam, p);
        hookWifi(lpparam, p);
        hookBluetooth(lpparam, p);
        hookAdvertisingId(lpparam, p);
        hookAppSetId(lpparam, p);
        hookAccounts(lpparam, p);
        hookCodecs(lpparam, p);
        hookGsf(lpparam, p);
        hookMediaDrm(lpparam, p);
        hookSystemProperties(p);
        hookHardwareInfo(lpparam, p);
        hookHardwareSignals(lpparam, p);
        hookStorage(lpparam, p);
        hookFactoryResetTime(pkg, p);
        if (!gateOff(p, "hide_apps")) hookInstalledApps(lpparam);
        if (!gateOff(p, "spoof_sysfs")) hookDisplayMetrics(lpparam, p);
    }

    // A protection gate: the profile carries "<key>":"0" only when the user toggled it OFF in the app.
    // Absent/any-other value = ON (the default), so existing profiles keep full protection.
    private static boolean gateOff(Map<String, String> p, String key) {
        return "0".equals(p.get(key));
    }

    // ---- profile loading: per-app file wins, else a shared default ----
    private Map<String, String> loadProfile(String pkg) {
        Map<String, String> m = new HashMap<>();
        try {
            File f = new File(PROFILE_DIR + pkg + ".json");
            if (!f.exists()) f = new File(PROFILE_DIR + "profile.json");
            if (!f.exists()) return m;
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[4096]; int n;
            while ((n = in.read(buf)) != -1) bos.write(buf, 0, n);
            in.close();
            JSONObject j = new JSONObject(new String(bos.toByteArray(), "UTF-8"));
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) { String k = it.next(); m.put(k, j.getString(k)); }
        } catch (Throwable t) { XposedBridge.log("[specter] profile load fail: " + t); }
        return m;
    }

    private void setStatic(String field, String val) {
        if (val == null) return;
        try {
            Field f = Build.class.getField(field);
            f.setAccessible(true);
            XposedHelpers.setStaticObjectField(Build.class, field, val);
        } catch (Throwable ignored) {}
    }

    // ---- Build.* (device_spoof) ----
    private void hookBuildFields(Map<String, String> p) {
        setStatic("MANUFACTURER", p.get("build_manufacturer"));
        setStatic("BRAND",        p.get("build_brand"));
        setStatic("DEVICE",       p.get("build_device"));
        setStatic("PRODUCT",      p.get("build_product"));
        setStatic("MODEL",        p.get("build_model"));
        setStatic("FINGERPRINT",  p.get("build_fingerprint"));
        setStatic("SERIAL",       p.get("serial"));
        setStatic("ID",           p.get("build_id"));
        setStatic("BOOTLOADER",   p.get("build_bootloader"));
        // Fingerprint-hash hardware signals (FingerprintJS reads HARDWARE/BOARD; kernel via os.version).
        setStatic("HARDWARE",     p.get("build_hardware"));
        setStatic("BOARD",        p.get("build_board"));
        setStatic("RADIO",        p.get("build_radio"));
        setStatic("HOST",         p.get("build_host"));
        setStatic("DISPLAY",      p.get("build_display"));
        hookKernelVersion(p.get("build_kernel_version"));
        hookRadioVersion(p.get("build_radio"));
        // getSerial() (API 26+) is a method, not just the field. Zero-arg -> hookAllMethods
        // (findAndHookMethod's varargs overload NoSuchMethodErrors against obfuscated XposedHelpers).
        try {
            final String ser = p.get("serial");
            if (ser != null) XposedBridge.hookAllMethods(Build.class, "getSerial", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) { mp.setResult(ser); }
            });
        } catch (Throwable ignored) {}
        // Build.VERSION.* must match the spoofed fingerprint's Android version, or an app that
        // reads VERSION.RELEASE/INCREMENTAL/SECURITY_PATCH sees a mismatch vs the fingerprint.
        setVersion("RELEASE", p.get("build_release"));
        setVersion("INCREMENTAL", p.get("build_incremental"));
        setVersion("SECURITY_PATCH", p.get("build_security_patch"));
        // Build.VERSION.SDK_INT is an int field — a claimed Android 9 must report SDK 28, not the real
        // device's 30. setStaticObjectField can't set a primitive int, so use plain reflection.
        String sdk = p.get("build_sdk");
        if (sdk != null) {
            try {
                java.lang.reflect.Field f = Build.VERSION.class.getField("SDK_INT");
                f.setAccessible(true);
                f.setInt(null, Integer.parseInt(sdk));
            } catch (Throwable ignored) {}
            // SDK is also exposed as a String field/prop; cover the String field too.
            setVersion("SDK", sdk);
        }
    }

    private void setVersion(String field, String val) {
        if (val == null) return;
        try {
            XposedHelpers.setStaticObjectField(Build.VERSION.class, field, val);
        } catch (Throwable ignored) {}
    }

    // ---- kernel version (os.version) — a high-entropy FingerprintJS signal ----
    private void hookKernelVersion(final String kernel) {
        if (kernel == null) return;
        sysProps.put("os.version", kernel);
        hookSystemGetProperty();
    }

    // Java system properties (System.getProperty), NOT android.os.SystemProperties. Both "os.version"
    // and "http.agent" are read through here, and getProperty is hot — register ONE hook and dispatch
    // from a map, the same pattern as hookSystemProperties below.
    private final Map<String, String> sysProps = new HashMap<>();
    private boolean sysPropsHooked = false;

    private void hookSystemGetProperty() {
        if (sysPropsHooked) return;
        sysPropsHooked = true;
        // hookAllMethods over findAndHookMethod (the varargs overload NoSuchMethodErrors against
        // LSPosed's obfuscated XposedHelpers).
        try {
            XposedBridge.hookAllMethods(System.class, "getProperty", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) {
                    if (mp.args.length == 0 || !(mp.args[0] instanceof String)) return;
                    String v = sysProps.get((String) mp.args[0]);
                    if (v != null) mp.setResult(v);
                }
            });
        } catch (Throwable ignored) {}
    }

    // ---- User-Agent — PROVEN to be FingerprintJS Pro's dominant visitorId anchor (2026-07-26).
    // The framework builds the UA from Build.MODEL/VERSION.RELEASE/ID in a process that ran BEFORE
    // our field hooks (libcore caches http.agent at zygote init; WebView builds its UA in the
    // WebView provider). Result: two totally different profiles both reported
    //   "Dalvik/2.1.0 (Linux; U; Android 11; Pixel 4 Build/RQ3A.211001.001)"
    // to the FPJS Server API and collapsed to the SAME visitorId. So rebuild both UA strings from the
    // profile's build_release/build_model/build_id and serve them on every read path.
    // Coherent by construction (no new field, no RNG -> byte-parity safe).
    // The two string builders live in SpoofLogic so the plain-JVM test suite covers their exact shape.
    private void hookUserAgent(final XC_LoadPackage.LoadPackageParam lp, Map<String, String> p) {
        final String release = p.get("build_release");
        final String model = p.get("build_model");
        final String id = p.get("build_id");
        if (release == null || model == null || id == null) return;

        // http.agent -> the Dalvik UA. This is what HttpURLConnection/OkHttp send by default and what
        // the FPJS SDK's server-side browserDetails.userAgent came from.
        sysProps.put("http.agent", SpoofLogic.dalvikUserAgent(release, model, id));
        hookSystemGetProperty();

        // WebView UA: keep the device's REAL Chrome version (that part isn't device-identifying and
        // faking it would be incoherent with the installed WebView), only swap the device segment.
        final String webUa = SpoofLogic.webViewUserAgent(release, model, id, chromeVersion());
        try {
            Class<?> ws = XposedHelpers.findClass("android.webkit.WebSettings", lp.classLoader);
            // static WebSettings.getDefaultUserAgent(Context) and the instance
            // WebSettings.getUserAgentString() (WebSettingsClassic/AwSettings subclass it, so hook
            // the concrete provider class too via hookAllMethods on the returned object's class).
            XposedBridge.hookAllMethods(ws, "getDefaultUserAgent", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) { mp.setResult(webUa); }
            });
        } catch (Throwable ignored) {}
        try {
            // The instance getter lives on the provider impl (WebSettingsWrapper -> AwSettings), not
            // the abstract WebSettings. ponytail: rewrite unconditionally — an app that set a CUSTOM
            // UA is not the leak we are closing, and detecting "custom" reliably needs the pre-hook
            // default we no longer have.
            Class<?> aw = XposedHelpers.findClass("android.webkit.WebSettingsWrapper", lp.classLoader);
            XposedBridge.hookAllMethods(aw, "getUserAgentString", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) { mp.setResult(webUa); }
            });
        } catch (Throwable ignored) {}
        XposedBridge.log("[specter] UA -> " + sysProps.get("http.agent"));
    }

    // Real installed WebView Chrome version, e.g. "120.0.6099.43". Falls back to a plausible constant
    // if the package isn't queryable (some apps can't see the WebView provider).
    private String chromeVersion() {
        // Plain reflection, not XposedHelpers — the vendored Xposed stub only exposes
        // setStaticObjectField/findClass/hookAllMethods (see CLAUDE.md).
        try {
            Class<?> wv = Class.forName("android.webkit.WebViewFactory");
            Object info = wv.getMethod("getLoadedPackageInfo").invoke(null);
            if (info != null) {
                Object v = info.getClass().getField("versionName").get(info);
                if (v instanceof String && ((String) v).length() > 0) return (String) v;
            }
        } catch (Throwable ignored) {}
        return "120.0.6099.43";
    }

    // ---- baseband/radio — a confirmed FingerprintJS leak. DevInfo (and getRadioVersion itself)
    //      read it from the "gsm.version.baseband" system property, so hook SystemProperties.get
    //      for the baseband keys AND Build.getRadioVersion for callers that use the API directly. ----
    private void hookRadioVersion(final String radio) {
        if (radio == null) return;
        try {
            // hookAllMethods is robust for the zero-arg getRadioVersion (findAndHookMethod's varargs
            // overload isn't reliably resolvable against LSPosed's obfuscated XposedHelpers).
            XposedBridge.hookAllMethods(Build.class, "getRadioVersion", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) {
                    mp.setResult(radio);
                }
            });
        } catch (Throwable ignored) {}
    }

    // ---- RAM (ActivityManager.MemoryInfo.totalMem) + SoC platform — FingerprintJS hardware signals ----
    private void hookHardwareInfo(final XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        final String ramStr = p.get("total_ram");
        if (ramStr == null) return;
        final long ram;
        try { ram = Long.parseLong(ramStr); } catch (Throwable t) { return; }
        try {
            Class<?> am = XposedHelpers.findClass("android.app.ActivityManager", lp.classLoader);
            XposedBridge.hookAllMethods(am, "getMemoryInfo", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) {
                    if (mp.args.length > 0 && mp.args[0] != null) {
                        // totalMem is a public field on ActivityManager.MemoryInfo — set it directly.
                        try {
                            Field f = mp.args[0].getClass().getField("totalMem");
                            f.setLong(mp.args[0], ram);
                        } catch (Throwable ignored) {}
                    }
                }
            });
        } catch (Throwable ignored) {}
    }

    // ---- Hardware-characteristic signals (GOAL 1.3) — real per-model values from the profile ----
    // FPJS Pro's visitorId is a server-side fuzzy match over ~50 signals; a big, STABLE, real subset
    // (GPU/GLES renderer, sensor list, camera list, input devices, core count) leaked unchanged every
    // rotation. Now spoofed to the COHERENT per-model bundle carried in the profile (hw_* fields,
    // from data/hardware.json), so every reading app sees the hardware of the device this identity
    // claims to be. The native (Zygisk) layer covers the direct-JNI reads libfp uses; these Java
    // hooks close the framework-API paths other SDKs read (SensorManager, CameraManager, GLES20, etc).
    private void hookHardwareSignals(final XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        // GL_ES version reported by ConfigurationInfo (e.g. "3.2").
        final String gles = p.get("hw_gles_version");
        if (gles != null && !gles.isEmpty()) {
            try {
                Class<?> ci = XposedHelpers.findClass("android.content.pm.ConfigurationInfo", lp.classLoader);
                XposedBridge.hookAllMethods(ci, "getGlEsVersion", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam mp) { mp.setResult(gles); }
                });
            } catch (Throwable ignored) {}
        }
        // glGetString(GL_RENDERER/GL_VENDOR/GL_VERSION) — the GPU renderer string is a strong hardware
        // signal. Dispatch by the GL enum arg so RENDERER/VENDOR/VERSION each return the profile value.
        final String renderer = p.get("hw_gpu_renderer");
        final String vendor = p.get("hw_gpu_vendor");
        if ((renderer != null && !renderer.isEmpty()) || (vendor != null && !vendor.isEmpty())) {
            final int GL_VENDOR = 0x1F00, GL_RENDERER = 0x1F01, GL_VERSION = 0x1F02;
            XC_MethodHook glHook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) {
                    if (mp.args.length == 0 || !(mp.args[0] instanceof Integer)) return;
                    int name = (Integer) mp.args[0];
                    if (name == GL_RENDERER && renderer != null && !renderer.isEmpty()) mp.setResult(renderer);
                    else if (name == GL_VENDOR && vendor != null && !vendor.isEmpty()) mp.setResult(vendor);
                    else if (name == GL_VERSION && gles != null && !gles.isEmpty()) mp.setResult("OpenGL ES " + gles + " V@0.0");
                }
            };
            for (String cls : new String[]{"android.opengl.GLES20", "android.opengl.GLES30"}) {
                try {
                    Class<?> gl = XposedHelpers.findClass(cls, lp.classLoader);
                    XposedBridge.hookAllMethods(gl, "glGetString", glHook);
                } catch (Throwable ignored) {}
            }
        }
        // Core count — Runtime.availableProcessors is the common Java path. Report the profile's count.
        final String coresStr = p.get("hw_cores");
        if (coresStr != null && !coresStr.isEmpty()) {
            try {
                final int cores = Integer.parseInt(coresStr);
                XposedBridge.hookAllMethods(Runtime.class, "availableProcessors", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam mp) { mp.setResult(cores); }
                });
            } catch (Throwable ignored) {}
        }
        // Camera id list — return exactly the profile's camera ids (a hardware-count signal).
        final String cams = p.get("hw_cameras");
        if (cams != null && !cams.isEmpty()) {
            final String[] camIds = cams.split(",");
            try {
                Class<?> cm = XposedHelpers.findClass("android.hardware.camera2.CameraManager", lp.classLoader);
                XposedBridge.hookAllMethods(cm, "getCameraIdList", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam mp) { mp.setResult(camIds); }
                });
            } catch (Throwable ignored) {}
        }
        // Input devices — FPJS reads InputDevice.getName()+getVendorId() for every id (decompiled
        // C0465h, case 4). Faking only the COUNT left the real touchscreen/PMIC names (fts, qpnp_pon on
        // a Pixel 4) leaking — a stable per-device anchor. Advertise the REAL ids (capped at the
        // profile's device count) so every id resolves, AND relabel each returned InputDevice's mName
        // to a profile name, zeroing mVendorId/mProductId (internal touchscreens report 0).
        final String inputs = p.get("hw_input_devices");
        if (inputs != null && !inputs.isEmpty()) {
            // Filter empty tokens so a malformed ","/", ," value can't yield n==0 (div-by-zero below).
            final java.util.ArrayList<String> nameList = new java.util.ArrayList<>();
            for (String s : inputs.split(",")) { String t = s.trim(); if (!t.isEmpty()) nameList.add(t); }
            final String[] inNames = nameList.toArray(new String[0]);
            final int n = inNames.length;
            if (n > 0) try {
                Class<?> im = XposedHelpers.findClass("android.hardware.input.InputManager", lp.classLoader);
                XposedBridge.hookAllMethods(im, "getInputDeviceIds", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam mp) {
                        // Keep only REAL ids (capped at n), so every advertised id resolves to a
                        // (relabeled) device. Advertising 0..n-1 when only ~3 real devices exist made
                        // the count disagree with the number of readable names â itself a tell
                        // (both codex + code-reviewer flagged it). count == names now.
                        int[] real = (int[]) mp.getResult();
                        if (real == null || real.length == 0) return;
                        int cap = Math.min(n, real.length);
                        int[] ids = new int[cap];
                        System.arraycopy(real, 0, ids, 0, cap);
                        mp.setResult(ids);
                    }
                });
                XposedBridge.hookAllMethods(im, "getInputDevice", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam mp) {
                        Object dev = mp.getResult();
                        if (dev == null) return;
                        int id = 0;
                        try { id = (Integer) mp.args[0]; } catch (Throwable ignored) {}
                        String name = inNames[Math.floorMod(id, n)];
                        setStringFieldSafe(dev, "mName", name);
                        setIntFieldSafe(dev, "mVendorId", 0);
                        setIntFieldSafe(dev, "mProductId", 0);
                    }
                });
            } catch (Throwable ignored) {}
        }
        // Sensor list — Sensor objects can't be constructed from an app hook, so relabel the REAL list
        // in place to the profile's sensor names/vendors (reflection on private mName/mVendor) and
        // truncate to the profile's sensor count. This changes the sensor-set hash without fabricating
        // Sensor instances. The native layer covers the ASensorManager NDK reads.
        final String sensors = p.get("hw_sensors");
        if (sensors != null && !sensors.isEmpty()) {
            final String[] rows = sensors.split(";");
            try {
                Class<?> sm = XposedHelpers.findClass("android.hardware.SensorManager", lp.classLoader);
                XposedBridge.hookAllMethods(sm, "getSensorList", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam mp) {
                        Object res = mp.getResult();
                        if (!(res instanceof java.util.List)) return;
                        java.util.List<?> in = (java.util.List<?>) res;
                        if (in.isEmpty()) return;
                        java.util.List<Object> out = new java.util.ArrayList<>();
                        int idx = 0;
                        for (Object sensor : in) {
                            if (idx >= rows.length) break;
                            String[] parts = rows[idx].split("\\|");
                            if (parts.length >= 2) {
                                try {
                                    Field nm = sensor.getClass().getDeclaredField("mName");
                                    nm.setAccessible(true); nm.set(sensor, parts[0]);
                                    Field vn = sensor.getClass().getDeclaredField("mVendor");
                                    vn.setAccessible(true); vn.set(sensor, parts[1]);
                                    // The high-entropy fields FPJS hashes: resolution / maxRange / power /
                                    // version. Leaving them REAL leaks the exact Pixel-4 sensor chip even
                                    // after the name/vendor are relabeled. Set coherent per-type values
                                    // derived from the sensor type (SpoofLogic — pure, testable).
                                    int type = parts.length >= 3 ? parseIntSafe(parts[2]) : 0;
                                    float[] rmp = SpoofLogic.sensorRmp(type, parts[0]);
                                    setFloatFieldSafe(sensor, "mMaxRange", rmp[0]);
                                    setFloatFieldSafe(sensor, "mResolution", rmp[1]);
                                    setFloatFieldSafe(sensor, "mPower", rmp[2]);
                                    setIntFieldSafe(sensor, "mVersion", 1);
                                } catch (Throwable ignored) {}
                            }
                            out.add(sensor);
                            idx++;
                        }
                        if (out.isEmpty()) out.add(in.get(0));
                        mp.setResult(out);
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    private static int parseIntSafe(String v) {
        try { return Integer.parseInt(v.trim()); } catch (Throwable t) { return 0; }
    }
    private static void setFloatFieldSafe(Object o, String field, float val) {
        try { Field f = o.getClass().getDeclaredField(field); f.setAccessible(true); f.setFloat(o, val); }
        catch (Throwable ignored) {}
    }
    private static void setIntFieldSafe(Object o, String field, int val) {
        try { Field f = o.getClass().getDeclaredField(field); f.setAccessible(true); f.setInt(o, val); }
        catch (Throwable ignored) {}
    }
    private static void setStringFieldSafe(Object o, String field, String val) {
        try { Field f = o.getClass().getDeclaredField(field); f.setAccessible(true); f.set(o, val); }
        catch (Throwable ignored) {}
    }

    // ---- StatFs total/available storage — a FingerprintJS hardware signal that was LEAKING ----
    // profile.py generates total_storage but nothing injected it, so real storage leaked (a STABLE
    // real value that links accounts). Hook the whole StatFs family COHERENTLY: getTotalBytes and the
    // blockCount*blockSize path must multiply out to the SAME spoofed total, else an app computing
    // total from blocks sees the real value and the two disagree (a worse tell than leaving it real).
    // available/free = a realistic ~35-55% of total (kept deterministic per-identity via the id hash).
    // ---- factory-reset timestamp (File.lastModified on the reset-marker dirs) ----
    // PROVEN 2026-07-25: FingerprintJS Pro re-identified the device across THREE full identity
    // rotations via its `factoryReset` smart signal. That value is the mtime of directories written
    // once at factory reset and never again; /data/misc/profiles and /data/bootchart are readable by
    // an unprivileged app (verified: stat as plain shell succeeds), so any app gets a stable
    // per-device id no other spoofed field can hide.
    //
    // lastModified() is a HOT, generic call on a path every app uses for its own files, so this
    // matches an EXPLICIT path set and passes everything else through untouched. Matching broadly
    // (e.g. any /data path) would corrupt target apps' own file bookkeeping.
    static final String[] FACTORY_RESET_PATHS = {
            "/data/misc/profiles", "/data/bootchart", "/data/misc/wifi", "/data/misc/bluetooth",
            "/data/vendor", "/data/dalvik-cache", "/data/misc", "/data/system",
    };

    private void hookFactoryResetTime(final String pkg, final Map<String, String> p) {
        String v = p.get("factory_reset_epoch");
        if (v == null) return;
        final long millis;
        try { millis = Long.parseLong(v) * 1000L; } catch (Throwable t) { return; }
        final long resetSecs = millis / 1000L;
        try {
            XposedBridge.hookAllMethods(File.class, "lastModified", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) {
                    if (!(mp.thisObject instanceof File)) return;
                    String ap = ((File) mp.thisObject).getAbsolutePath();
                    if ("1".equals(p.get("trace"))) XposedBridge.log("[specter][lastmod] " + ap);
                    if (isResetMarker(ap)) { mp.setResult(millis); return; }
                    // The app's own APK install-time — FPJS Pro's FileTimestamps visitorId anchor.
                    if (!gateOff(p, "spoof_apktime") && SpoofLogic.isOwnApk(ap, pkg))
                        mp.setResult(SpoofLogic.apkInstallSeconds(resetSecs, ap) * 1000L);
                }
            });
        } catch (Throwable t) { XposedBridge.log("[specter] factory-reset File hook fail: " + t); }

        // android.system.Os.stat/lstat is a SECOND, independent read of the same fact — and it is the
        // one that matters: PROVEN 2026-07-25 that FingerprintJS Pro reported the REAL reset time while
        // our File.lastModified hook was verified active, and the probe confirmed Os.stat().st_mtime
        // still returned the real value. Hooking only File.lastModified closes the path nobody uses.
        // StructStat fields are final, so rewrite st_mtime by reflection on the returned object.
        final long secs = millis / 1000L;
        try {
            Class<?> os = XposedHelpers.findClass("android.system.Os", null);
            final boolean trace = "1".equals(p.get("trace"));
            XC_MethodHook statHook = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) {
                    if (mp.args.length == 0 || !(mp.args[0] instanceof String)) return;
                    String path = (String) mp.args[0];
                    if (trace) XposedBridge.log("[specter][osstat] " + path);
                    long spoofSecs;
                    if (isResetMarker(path)) spoofSecs = secs;
                    else if (!gateOff(p, "spoof_apktime") && SpoofLogic.isOwnApk(path, pkg)) spoofSecs = SpoofLogic.apkInstallSeconds(secs, path);
                    else return;
                    Object st = mp.getResult();
                    if (st == null) return;
                    // Rewrite ctime/atime alongside mtime: leaving them real would leak the true
                    // reset date via a different StructStat field (a provable leak), which is worse
                    // than the mild "all three equal" tell — and on a dir untouched since factory
                    // reset, ctime==mtime and (under relatime) atime==mtime is in fact common.
                    for (String f : new String[]{"st_mtime", "st_ctime", "st_atime"}) {
                        try {
                            Field fl = st.getClass().getField(f);
                            fl.setAccessible(true);
                            fl.setLong(st, spoofSecs);
                        } catch (Throwable ignored) {}
                    }
                }
            };
            XposedBridge.hookAllMethods(os, "stat", statHook);
            XposedBridge.hookAllMethods(os, "lstat", statHook);
        } catch (Throwable t) { XposedBridge.log("[specter] factory-reset Os.stat hook fail: " + t); }
    }

    // ---- display metrics — getResources().getDisplayMetrics() (widthPixels/heightPixels/densityDpi) ----
    // A stable, high-entropy signal FingerprintJS reads via a Java API (invisible to the native tracer);
    // it leaked the REAL device's screen (Pixel 4 = 1080x2280@440) on every rotation. Rewrite the
    // DisplayMetrics fields to the profile's per-model screen. Hook Resources.getDisplayMetrics (the SDK's
    // path) and, best-effort, the WindowManager/Display real-metrics path apps also use.
    private void hookDisplayMetrics(final XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        final String w = p.get("screen_width"), h = p.get("screen_height"), d = p.get("screen_density");
        if (w == null || h == null || d == null) return;
        final int wi, hi, di;
        try { wi = Integer.parseInt(w); hi = Integer.parseInt(h); di = Integer.parseInt(d); }
        catch (Throwable t) { return; }
        final XC_MethodHook rewrite = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam mp) {
                Object r = mp.getResult();
                if (r instanceof android.util.DisplayMetrics) applyMetrics((android.util.DisplayMetrics) r, wi, hi, di);
            }
        };
        // Also rewrite a DisplayMetrics passed BY REFERENCE into getMetrics(dm)/getRealMetrics(dm).
        final XC_MethodHook rewriteArg = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam mp) {
                for (Object a : mp.args)
                    if (a instanceof android.util.DisplayMetrics) applyMetrics((android.util.DisplayMetrics) a, wi, hi, di);
            }
        };
        try {
            Class<?> res = XposedHelpers.findClass("android.content.res.Resources", lp.classLoader);
            XposedBridge.hookAllMethods(res, "getDisplayMetrics", rewrite);
        } catch (Throwable ignored) {}
        try {
            Class<?> disp = XposedHelpers.findClass("android.view.Display", lp.classLoader);
            XposedBridge.hookAllMethods(disp, "getMetrics", rewriteArg);
            XposedBridge.hookAllMethods(disp, "getRealMetrics", rewriteArg);
        } catch (Throwable ignored) {}
    }

    private static void applyMetrics(android.util.DisplayMetrics m, int w, int h, int d) {
        // Portrait: width is the shorter edge. Keep density fields coherent (dpi -> density scale).
        m.widthPixels = w;
        m.heightPixels = h;
        m.densityDpi = d;
        m.density = d / 160f;
        m.scaledDensity = d / 160f;
        m.xdpi = d;
        m.ydpi = d;
    }

    // ---- installed-app list — hide root/hooking/anti-fingerprint packages ----
    // The installed-app enumeration is a raw signal FingerprintJS collects (PackageManager). Leaving
    // com.specter, the probe, Magisk/LSPosed managers, or a hide-my-app tool in the returned list both
    // raises the device's entropy and is a direct "this device is instrumented" tell. Filter them out of
    // getInstalledApplications/getInstalledPackages/getInstalledModules, and make a direct lookup of a
    // hidden package throw NameNotFound (as if it were not installed).
    @SuppressWarnings("unchecked")
    private void hookInstalledApps(final XC_LoadPackage.LoadPackageParam lp) {
        Class<?> pm = XposedHelpers.findClassIfExists("android.app.ApplicationPackageManager", lp.classLoader);
        if (pm == null) return;
        XC_MethodHook listFilter = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam mp) {
                Object res = mp.getResult();
                if (!(res instanceof java.util.List)) return;
                java.util.List<Object> in = (java.util.List<Object>) res;
                java.util.List<Object> out = new java.util.ArrayList<>(in.size());
                for (Object item : in) {
                    String name = pkgNameOf(item);
                    if (name != null && SpoofLogic.isSensitivePackage(name)) continue;
                    out.add(item);
                }
                if (out.size() != in.size()) mp.setResult(out);
            }
        };
        for (String m : new String[]{"getInstalledApplications", "getInstalledPackages",
                "getInstalledApplicationsAsUser", "getInstalledPackagesAsUser", "getInstalledModules"}) {
            try { XposedBridge.hookAllMethods(pm, m, listFilter); } catch (Throwable ignored) {}
        }
        // A direct lookup of a hidden package must look like "not installed".
        XC_MethodHook notFound = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam mp) throws Throwable {
                if (mp.args.length > 0 && mp.args[0] instanceof String
                        && SpoofLogic.isSensitivePackage((String) mp.args[0])) {
                    throw new android.content.pm.PackageManager.NameNotFoundException((String) mp.args[0]);
                }
            }
        };
        // These four DECLARE NameNotFoundException (checked) as their real not-installed contract, so
        // throwing it is the correct simulated "not installed".
        for (String m : new String[]{"getPackageInfo", "getApplicationInfo", "getPackageUid",
                "getPackageGids"}) {
            try { XposedBridge.hookAllMethods(pm, m, notFound); } catch (Throwable ignored) {}
        }
        // getInstallerPackageName does NOT throw NameNotFoundException (its real not-found behavior is
        // to return null / throw the UNCHECKED IllegalArgumentException). Return null for a hidden
        // package so a caller that catches IllegalArgumentException isn't hit by an undeclared checked
        // exception. null == "installed by an unknown installer", a benign, common real value.
        try {
            XposedBridge.hookAllMethods(pm, "getInstallerPackageName", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) {
                    if (mp.args.length > 0 && mp.args[0] instanceof String
                            && SpoofLogic.isSensitivePackage((String) mp.args[0])) mp.setResult(null);
                }
            });
        } catch (Throwable ignored) {}
    }

    // ApplicationInfo / PackageInfo both expose the package name; ResolveInfo nests it. Pull it robustly.
    private static String pkgNameOf(Object item) {
        if (item == null) return null;
        try {
            if (item instanceof android.content.pm.PackageInfo) return ((android.content.pm.PackageInfo) item).packageName;
            if (item instanceof android.content.pm.ApplicationInfo) return ((android.content.pm.ApplicationInfo) item).packageName;
            java.lang.reflect.Field f = item.getClass().getField("packageName");
            Object v = f.get(item);
            return v instanceof String ? (String) v : null;
        } catch (Throwable ignored) { return null; }
    }

    /** True only for the exact reset-marker dirs — never a prefix match, so app files are untouched. */
    static boolean isResetMarker(String path) {
        if (path == null) return false;
        for (String marker : FACTORY_RESET_PATHS) if (path.equals(marker)) return true;
        return false;
    }

    private void hookStorage(final XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        final String stStr = p.get("total_storage");
        if (stStr == null) return;
        final long total;
        try { total = Long.parseLong(stStr); } catch (Throwable t) { return; }
        final long BS = 4096L;                          // standard f2fs/ext4 block size
        final long blockCount = total / BS;             // blockCount*blockSize == total (coherent)
        // free fraction: derive a stable 35-55% from the value itself so it doesn't jitter per call.
        final long freeBytes = total * (35 + (int)(Math.abs(total % 21))) / 100;
        final long freeBlocks = freeBytes / BS;
        try {
            Class<?> sf = XposedHelpers.findClass("android.os.StatFs", lp.classLoader);
            XposedBridge.hookAllMethods(sf, "getTotalBytes",     constLong(total));
            XposedBridge.hookAllMethods(sf, "getBlockCountLong", constLong(blockCount));
            XposedBridge.hookAllMethods(sf, "getBlockSizeLong",  constLong(BS));
            XposedBridge.hookAllMethods(sf, "getBlockCount",     constInt((int) Math.min(blockCount, Integer.MAX_VALUE)));
            XposedBridge.hookAllMethods(sf, "getBlockSize",      constInt((int) BS));
            XposedBridge.hookAllMethods(sf, "getAvailableBytes", constLong(freeBytes));
            XposedBridge.hookAllMethods(sf, "getFreeBytes",      constLong(freeBytes));
            XposedBridge.hookAllMethods(sf, "getAvailableBlocksLong", constLong(freeBlocks));
            XposedBridge.hookAllMethods(sf, "getFreeBlocksLong",      constLong(freeBlocks));
            XposedBridge.hookAllMethods(sf, "getAvailableBlocks", constInt((int) Math.min(freeBlocks, Integer.MAX_VALUE)));
            XposedBridge.hookAllMethods(sf, "getFreeBlocks",      constInt((int) Math.min(freeBlocks, Integer.MAX_VALUE)));
        } catch (Throwable ignored) {}
    }

    private static XC_MethodHook constLong(final long v) {
        return new XC_MethodHook() { @Override protected void afterHookedMethod(MethodHookParam mp) { mp.setResult(v); } };
    }
    private static XC_MethodHook constInt(final int v) {
        return new XC_MethodHook() { @Override protected void afterHookedMethod(MethodHookParam mp) { mp.setResult(v); } };
    }

    // ---- ONE SystemProperties.get hook for ALL spoofed props ----
    // SystemProperties.get is an extremely HOT path — hook it exactly once and dispatch every spoofed
    // key inside a single callback (kernel os.version, baseband, SoC platform) instead of registering
    // three separate hooks that each add overhead on every property read.
    // prop key -> profile key. Every Build.* field we spoof has a ro.* property alias; spoofing only
    // the field leaves the alias reading the REAL device (proven on-device: SystemProperties.get(
    // "ro.product.model") returned "Pixel 4" while Build.MODEL was correctly "sailfish"). Same values
    // as the fields, so this is coherent by construction and consumes no RNG (byte-parity safe).
    private static final String[][] PROP_ALIASES = {
        {"os.version", "build_kernel_version"},
        {"gsm.version.baseband", "build_radio"}, {"ril.baseband", "build_radio"},
        {"ro.board.platform", "soc_platform"}, {"ro.hardware.chipname", "soc_platform"},
        {"ro.soc.model", "soc_platform"},
        // Build.MODEL & friends exist per-PARTITION on Android 10+ (system/vendor/odm/product/system_ext);
        // aliasing only ro.product.model + .vendor.* left odm/product/system_ext leaking the REAL device.
        {"ro.product.model", "build_model"}, {"ro.product.vendor.model", "build_model"},
        {"ro.product.odm.model", "build_model"}, {"ro.product.product.model", "build_model"},
        {"ro.product.system_ext.model", "build_model"},
        {"ro.product.brand", "build_brand"}, {"ro.product.vendor.brand", "build_brand"},
        {"ro.product.odm.brand", "build_brand"}, {"ro.product.product.brand", "build_brand"},
        {"ro.product.system.brand", "build_brand"}, {"ro.product.system_ext.brand", "build_brand"},
        {"ro.product.manufacturer", "build_manufacturer"},
        {"ro.product.vendor.manufacturer", "build_manufacturer"},
        {"ro.product.odm.manufacturer", "build_manufacturer"},
        {"ro.product.product.manufacturer", "build_manufacturer"},
        {"ro.product.system.manufacturer", "build_manufacturer"},
        {"ro.product.system_ext.manufacturer", "build_manufacturer"},
        {"ro.product.device", "build_device"}, {"ro.product.vendor.device", "build_device"},
        {"ro.product.odm.device", "build_device"}, {"ro.product.product.device", "build_device"},
        {"ro.product.system_ext.device", "build_device"},
        {"ro.product.name", "build_product"}, {"ro.product.vendor.name", "build_product"},
        {"ro.product.odm.name", "build_product"}, {"ro.product.product.name", "build_product"},
        {"ro.product.system_ext.name", "build_product"},
        {"ro.build.id", "build_id"}, {"ro.build.display.id", "build_display"},
        {"ro.product.build.id", "build_id"},
        {"ro.build.fingerprint", "build_fingerprint"},
        {"ro.vendor.build.fingerprint", "build_fingerprint"},
        {"ro.product.build.fingerprint", "build_fingerprint"},
        {"ro.odm.build.fingerprint", "build_fingerprint"},
        {"ro.system.build.fingerprint", "build_fingerprint"},
        {"ro.system_ext.build.fingerprint", "build_fingerprint"},
        {"ro.bootimage.build.fingerprint", "build_fingerprint"},
        {"ro.build.product", "build_device"},
        {"ro.build.flavor", "build_flavor"}, {"ro.build.description", "build_description"},
        {"ro.build.version.incremental", "build_incremental"},
        {"ro.product.build.version.incremental", "build_incremental"},
        {"ro.build.version.release", "build_release"},
        {"ro.product.build.version.release", "build_release"},
        {"ro.build.version.security_patch", "build_security_patch"},
        {"ro.build.host", "build_host"},
        {"ro.bootloader", "build_bootloader"}, {"ro.boot.bootloader", "build_bootloader"},
        {"ro.hardware", "build_hardware"}, {"ro.boot.hardware", "build_hardware"},
        {"ro.boot.hardware.platform", "soc_platform"},
        {"ro.product.board", "build_board"},
        {"ro.serialno", "serial"}, {"ro.boot.serialno", "serial"},
    };

    private void hookSystemProperties(final Map<String, String> p) {
        final Map<String, String> byProp = new HashMap<>();
        for (String[] a : PROP_ALIASES) {
            String v = p.get(a[1]);
            if (v != null) byProp.put(a[0], v);
        }
        if (byProp.isEmpty()) return;
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", null);
            XposedBridge.hookAllMethods(sp, "get", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) {
                    if (mp.args.length == 0 || !(mp.args[0] instanceof String)) return;
                    String v = byProp.get((String) mp.args[0]);
                    if (v != null) mp.setResult(v);
                }
            });
        } catch (Throwable ignored) {}
    }

    // ---- Settings.Secure.getString(..., "android_id") ----
    // Hook ALL getString overloads (getString(cr,name), getStringForUser(cr,name,userId), etc.).
    // A single-overload hook missed DevInfo's read (GSF/serial spoofed in the same process but
    // android_id leaked the real value) — the key can be at any arg position, so scan all args.
    private void hookSettingsSecure(final Map<String, String> p) {
        final String aid = p.get("android_id");
        final String btMac = p.get("bluetooth_mac");
        if (aid == null) return;
        XC_MethodHook h = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (SpoofLogic.argsContainKey(param.args, "android_id")) {
                    XposedBridge.log("[specter][idtrace] Settings.Secure android_id -> " + aid);
                    param.setResult(aid); return;
                }
                // Settings.Secure.getString(cr, "bluetooth_address") is a SECOND path to the BT MAC that
                // BluetoothAdapter.getAddress() doesn't cover — spoof it here too, or it leaks the real MAC.
                if (btMac != null && SpoofLogic.argsContainKey(param.args, "bluetooth_address")) {
                    param.setResult(btMac);
                }
            }
        };
        // Settings.Secure and Settings.System both expose getString/getStringForUser; cover both.
        try { XposedBridge.hookAllMethods(Settings.Secure.class, "getString", h); } catch (Throwable ignored) {}
        try { XposedBridge.hookAllMethods(Settings.Secure.class, "getStringForUser", h); } catch (Throwable ignored) {}
        try { XposedBridge.hookAllMethods(Settings.System.class, "getString", h); } catch (Throwable ignored) {}
    }

    // ---- Settings.Global device-state tells — hide the "developer/rooted device" fingerprint ----
    // FingerprintJS reads adb_enabled + development_settings_enabled. On this fleet phone both are 1
    // (a strong "not a normal user" signal, stable across every signup). Return 0 so the device looks
    // like an ordinary consumer phone. These are read via getInt AND getString — cover both.
    private void hookSettingsGlobal(final Map<String, String> p) {
        final java.util.Set<String> devTells = new java.util.HashSet<>(java.util.Arrays.asList(
                "adb_enabled", "development_settings_enabled"));
        XC_MethodHook getInt = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (argsContainAny(param.args, devTells)) param.setResult(0);
            }
        };
        final boolean trace = "1".equals(p.get("trace"));
        XC_MethodHook getStr = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                boolean hit = argsContainAny(param.args, devTells);
                if (hit) param.setResult("0");
                if (trace && param.args != null && param.args.length > 1 && param.args[1] instanceof String
                        && ((String) param.args[1]).matches(".*(adb|develop|settings).*"))
                    XposedBridge.log("[specter][global] getString " + param.args[1] + " hit=" + hit
                            + " final=" + param.getResult());
            }
        };
        try { XposedBridge.hookAllMethods(Settings.Global.class, "getInt", getInt); } catch (Throwable ignored) {}
        try { XposedBridge.hookAllMethods(Settings.Global.class, "getString", getStr); } catch (Throwable ignored) {}
    }

    private static boolean argsContainAny(Object[] args, java.util.Set<String> keys) {
        if (args == null) return false;
        for (Object a : args) if (a instanceof String && keys.contains(a)) return true;
        return false;
    }

    // ---- TelephonyManager: imei/deviceid/subscriber/simserial/line1/operator ----
    private void hookTelephony(XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        Class<?> tm = XposedHelpers.findClassIfExists("android.telephony.TelephonyManager", lp.classLoader);
        if (tm == null) return;
        rc(tm, "getImei", p.get("imei1"));
        rc(tm, "getDeviceId", p.get("imei1"));
        // slot overloads: slot 0 -> imei1, slot 1 -> imei2 (a dual-SIM app reading both must
        // see two DIFFERENT imeis, or the mismatch flags). Use a slot-aware hook, not a constant.
        hookSlotImei(tm, "getImei", p.get("imei1"), p.get("imei2"));
        hookSlotImei(tm, "getDeviceId", p.get("imei1"), p.get("imei2"));
        rc(tm, "getSubscriberId", p.get("sim_subscriber_imsi"));
        rc(tm, "getSimSerialNumber", p.get("sim_serial_iccid"));
        rc(tm, "getLine1Number", p.get("mobile_number"));
        rc(tm, "getNetworkOperator", p.get("sim_operator_mccmnc"));
        rc(tm, "getSimOperator", p.get("sim_operator_mccmnc"));
        rc(tm, "getNetworkOperatorName", p.get("sim_operator_name"));
        rc(tm, "getSimOperatorName", p.get("sim_operator_name"));
    }

    // ---- WifiInfo mac/ssid/bssid ----
    private void hookWifi(XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        Class<?> wi = XposedHelpers.findClassIfExists("android.net.wifi.WifiInfo", lp.classLoader);
        if (wi == null) return;
        rc(wi, "getMacAddress", p.get("wifi_mac"));
        rc(wi, "getSSID", "\"" + p.get("wifi_ssid") + "\"");   // WifiInfo wraps SSID in quotes
        rc(wi, "getBSSID", p.get("wifi_bssid"));
    }

    // ---- BluetoothAdapter address ----
    private void hookBluetooth(XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        Class<?> ba = XposedHelpers.findClassIfExists("android.bluetooth.BluetoothAdapter", lp.classLoader);
        if (ba == null) return;
        rc(ba, "getAddress", p.get("bluetooth_mac"));
    }

    // ---- AdvertisingIdClient.Info.getId + the static getAdvertisingIdInfo factory ----
    // Hooking only Info.getId leaks the real value when getId is inlined or the Info is built from
    // a Binder IPC result that doesn't route through our hook. So ALSO hook the static factory
    // getAdvertisingIdInfo(Context) and replace the whole returned Info with our fake id.
    private void hookAdvertisingId(XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        final String adid = p.get("advertising_id");
        if (adid == null) return;
        final Class<?> info = XposedHelpers.findClassIfExists(
            "com.google.android.gms.ads.identifier.AdvertisingIdClient$Info", lp.classLoader);
        Class<?> client = XposedHelpers.findClassIfExists(
            "com.google.android.gms.ads.identifier.AdvertisingIdClient", lp.classLoader);
        // Primary: swap the Info returned by the static factory (covers inlined getId / IPC result).
        if (client != null && info != null) {
            try {
                XposedBridge.hookAllMethods(client, "getAdvertisingIdInfo", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Object real = param.getResult();
                        boolean lat = false;
                        if (real != null) {
                            // preserve the real limit-ad-tracking flag (plain reflection; no helper deps)
                            try {
                                java.lang.reflect.Method m = real.getClass().getMethod("isLimitAdTrackingEnabled");
                                Object v = m.invoke(real);
                                if (v instanceof Boolean) lat = (Boolean) v;
                            } catch (Throwable ignored) {}
                        }
                        try {
                            java.lang.reflect.Constructor<?> ctor =
                                info.getConstructor(String.class, boolean.class);
                            ctor.setAccessible(true);
                            param.setResult(ctor.newInstance(adid, lat));
                        } catch (Throwable ignored) {
                            // if the (String,boolean) ctor shape changed, fall back to getId hook below
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
        // Belt-and-suspenders: also force Info.getId to our value.
        if (info != null) rc(info, "getId", adid);
    }

    // ---- App Set ID (com.google.android.gms.appset.AppSetIdInfo.getId) ----
    // A per-app-scoped install id apps read for analytics. The value comes from an async Task, but the
    // final read is AppSetIdInfo.getId() â hook it to return the profile's app_set_id. (No dedicated
    // factory hook needed: unlike the ad-id Info, getId() here is the single value accessor.)
    private void hookAppSetId(XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        final String asid = p.get("app_set_id");
        if (asid == null) return;
        Class<?> info = XposedHelpers.findClassIfExists(
            "com.google.android.gms.appset.AppSetIdInfo", lp.classLoader);
        if (info != null) rc(info, "getId", asid);
    }

    // ---- Google account (AccountManager) â the real Gmail links accounts across apps ----
    // A fingerprinter reads AccountManager.getAccountsByType("com.google")/getAccounts() to see the
    // device's Google account(s). Left real, that email is a strong cross-app/cross-account linker.
    // We rewrite the com.google entries in the RETURNED Account list to the profile's gmail (keeping
    // any other account types intact), and answer getAccountsByType("com.google") with just it.
    // Scope note: this is per-app (LSPosed scope) and only rewrites the ENUMERATION result â auth-token
    // paths (getAuthToken) are NOT touched, so an app that merely reads the account name sees the spoof
    // while we don't fabricate credentials. Masking model, same as GeerGit.
    private void hookAccounts(final XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        final String email = p.get("gmail");
        if (email == null || email.isEmpty()) return;
        final Class<?> am = XposedHelpers.findClassIfExists("android.accounts.AccountManager", lp.classLoader);
        final Class<?> acct = XposedHelpers.findClassIfExists("android.accounts.Account", lp.classLoader);
        if (am == null || acct == null) return;
        final Object googleAcct;
        try {
            googleAcct = acct.getConstructor(String.class, String.class).newInstance(email, "com.google");
        } catch (Throwable t) { return; }
        // Type-based queries (getAccountsByType(type,...), getAccountsByTypeForPackage(type,pkg,...)):
        // arg[0] is ALWAYS the account type. Rewrite ONLY when the app asked for com.google â checking
        // every String arg was wrong (the package-name arg of ...ForPackage is never "com.google", which
        // silently neutered that hook and leaked the real account).
        XC_MethodHook byTypeRewrite = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam mp) {
                if (!(mp.getResult() instanceof Object[])) return;
                if (mp.args.length == 0 || !"com.google".equals(mp.args[0])) return;   // not a google query
                Object[] arr = (Object[]) java.lang.reflect.Array.newInstance(acct, 1);
                arr[0] = googleAcct;
                mp.setResult(arr);
            }
        };
        // getAccounts() returns ALL account types. Filter IN PLACE: swap the com.google entries for our
        // synthetic one, KEEP every other account (Exchange/Samsung/etc.) â replacing the whole array
        // would drop real non-Google accounts and break apps enumerating them. If the device has NO
        // google account, leave the list untouched (don't invent one where there is none).
        XC_MethodHook allRewrite = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam mp) {
                Object res = mp.getResult();
                if (!(res instanceof Object[])) return;
                Object[] real = (Object[]) res;
                java.util.ArrayList<Object> out = new java.util.ArrayList<>(real.length);
                boolean replaced = false;
                for (Object a : real) {
                    String type = null;
                    try { type = (String) acct.getField("type").get(a); } catch (Throwable ignored) {}
                    if ("com.google".equals(type)) {
                        if (!replaced) { out.add(googleAcct); replaced = true; }   // one google acct, coherent
                    } else {
                        out.add(a);
                    }
                }
                if (!replaced) return;   // no google account on device -> don't fabricate one
                Object[] arr = (Object[]) java.lang.reflect.Array.newInstance(acct, out.size());
                mp.setResult(out.toArray(arr));
            }
        };
        try { XposedBridge.hookAllMethods(am, "getAccountsByType", byTypeRewrite); } catch (Throwable ignored) {}
        try { XposedBridge.hookAllMethods(am, "getAccountsByTypeForPackage", byTypeRewrite); } catch (Throwable ignored) {}
        try { XposedBridge.hookAllMethods(am, "getAccounts", allRewrite); } catch (Throwable ignored) {}
    }

    // ---- Media codecs (MediaCodecInfo.getName) â the codec-name SET leaks the SoC vendor ----
    // MediaCodecList.getCodecInfos() -> each MediaCodecInfo.getName() (e.g. "OMX.qcom.video.decoder.avc"
    // reveals Qualcomm). Left real, the name set is a stable per-SoC signal. We relabel mName in place
    // to the profile's hw_codecs list (positional, capped to the real count â same technique as the
    // sensor/input relabel). Capabilities (mCaps) stay real; we only change the visible NAME, which is
    // what a fingerprinter hashes. Objects can't be constructed from an app hook, so relabel in place.
    private void hookCodecs(final XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        final String codecs = p.get("hw_codecs");
        if (codecs == null || codecs.isEmpty()) return;
        final java.util.ArrayList<String> nl = new java.util.ArrayList<>();
        for (String s : codecs.split(",")) { String t = s.trim(); if (!t.isEmpty()) nl.add(t); }
        if (nl.isEmpty()) return;
        final String[] names = nl.toArray(new String[0]);
        final Class<?> mcl = XposedHelpers.findClassIfExists("android.media.MediaCodecList", lp.classLoader);
        final Class<?> mci = XposedHelpers.findClassIfExists("android.media.MediaCodecInfo", lp.classLoader);
        if (mcl == null || mci == null) return;
        // Rewrite getCodecInfos() to return the real infos CAPPED to the profile codec count, each
        // 1:1 relabeled to a distinct profile codec name (no duplicate names, count == names). We keep
        // the real MediaCodecInfo objects (their capabilities stay valid) and only change mName â the
        // codec-NAME set is what a fingerprinter hashes. Same in-place-relabel technique as sensors.
        try {
            XposedBridge.hookAllMethods(mcl, "getCodecInfos", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) {
                    Object res = mp.getResult();
                    if (!(res instanceof Object[])) return;
                    Object[] real = (Object[]) res;
                    int cap = Math.min(names.length, real.length);
                    Object[] out = (Object[]) java.lang.reflect.Array.newInstance(mci, cap);
                    for (int i = 0; i < cap; i++) {
                        setStringFieldSafe(real[i], "mName", names[i]);
                        out[i] = real[i];
                    }
                    mp.setResult(out);
                }
            });
        } catch (Throwable ignored) {}
    }

    // ---- GSF id: content query to com.google.android.gsf.gservices returning android_id ----
    // THE surface that regressed in 2.9.6. Hook the provider read + GServices helper.
    private void hookGsf(XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        final String gsf = p.get("gsf_id");
        if (gsf == null) return;
        Class<?> gs = XposedHelpers.findClassIfExists("com.google.android.gsf.Gservices", lp.classLoader);
        if (gs != null) {
            // getString(..., "android_id", ...) -> fake gsf
            try {
                XposedBridge.hookAllMethods(gs, "getString", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        for (Object a : param.args)
                            if ("android_id".equals(String.valueOf(a))) {
                                XposedBridge.log("[specter][idtrace] Gservices.getString android_id -> " + gsf);
                                param.setResult(gsf); return;
                            }
                    }
                });
            } catch (Throwable ignored) {}
            // getLong(..., "android_id", ...) -> fake gsf parsed to long (the common numeric read)
            try {
                final long gsfLong = SpoofLogic.gsfToLong(gsf, -1L);
                XposedBridge.hookAllMethods(gs, "getLong", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (gsfLong < 0) return;
                        for (Object a : param.args)
                            if ("android_id".equals(String.valueOf(a))) {
                                XposedBridge.log("[specter][idtrace] Gservices.getLong android_id -> " + gsfLong);
                                param.setResult(gsfLong); return;
                            }
                    }
                });
            } catch (Throwable ignored) {}
        }
        // Broad path: wrap ContentResolver.query / ContentProviderClient.query so a direct gservices
        // cursor read of "android_id" returns our fake GSF. This is a LARGE, fragile hook surface
        // (every provider read in the process), so we gate it to the DevInfo TEST app ONLY — DevInfo
        // reads GSF via this cursor path (confirmed by dexdump), so it's how we keep GSF fully
        // verifiable on our test target. Real targets use the narrow Gservices.getString/getLong hooks
        // above only (GeerGit's approach — smaller surface, less fragile). See docs/PAIRIP-CONSTRAINT.md.
        // The FPJS SDK reads GSF ID via the ContentResolver gservices CURSOR path (it holds
        // READ_GSERVICES). The narrow Gservices.getString/getLong hooks above may not cover that
        // read, so enable the cursor wrapper for the FPJS demo too — else the real GSF leaks and
        // becomes a stable cross-wipe device identifier.
        if (!"com.liuzh.deviceinfo".equals(lp.packageName)
                && !"com.fingerprintjs.android.fpjs_pro_demo".equals(lp.packageName)) return;
        final XC_MethodHook wrapCursor = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object uri = param.args.length > 0 ? param.args[0] : null;
                    if (uri == null || !String.valueOf(uri).contains("com.google.android.gsf.gservices"))
                        return;
                    final android.database.Cursor real = (android.database.Cursor) param.getResult();
                    if (real == null) return;
                    XposedBridge.log("[specter] GSF cursor wrapped for " + lp.packageName + " uri=" + uri);
                    // idtrace: dump the ACTUAL cursor schema so we can see whether the (name,value)
                    // assumption in SpoofLogic.isAndroidIdValueColumn holds for this caller.
                    try {
                        StringBuilder sb = new StringBuilder("[specter][idtrace] gsf cursor cols=");
                        sb.append(real.getColumnCount()).append(" names=");
                        for (String cn : real.getColumnNames()) sb.append(cn).append(',');
                        sb.append(" rows=").append(real.getCount());
                        Object sel = param.args.length > 3 ? param.args[3] : null;
                        sb.append(" selArgs=");
                        if (sel instanceof String[]) for (String sa : (String[]) sel) sb.append(sa).append(',');
                        else sb.append(String.valueOf(sel));
                        XposedBridge.log(sb.toString());
                    } catch (Throwable ignored2) {}
                    param.setResult(new GsfCursorWrapper(real, gsf));
                } catch (Throwable ignored) {}
            }
        };
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.content.ContentResolver", lp.classLoader), "query", wrapCursor);
        } catch (Throwable ignored) {}
        try {
            XposedBridge.hookAllMethods(
                XposedHelpers.findClass("android.content.ContentProviderClient", lp.classLoader), "query", wrapCursor);
        } catch (Throwable ignored) {}
    }

    /**
     * Wraps the gservices cursor: rows are (name, value) pairs. When the current row's name is
     * "android_id", getString(valueColumn) returns our fake GSF instead of the real one.
     */
    static final class GsfCursorWrapper extends android.database.CursorWrapper {
        private final String fakeGsf;
        GsfCursorWrapper(android.database.Cursor c, String fakeGsf) { super(c); this.fakeGsf = fakeGsf; }
        @Override public String getString(int columnIndex) {
            if (isAndroidIdValueColumn(columnIndex)) {
                XposedBridge.log("[specter][idtrace] GSF cursor SUBSTITUTED getString(" + columnIndex + ")");
                return fakeGsf;
            }
            return super.getString(columnIndex);
        }
        @Override public long getLong(int columnIndex) {
            // GSF android_id is frequently read via getLong on the value column — cover it too.
            if (isAndroidIdValueColumn(columnIndex)) {
                XposedBridge.log("[specter][idtrace] GSF cursor SUBSTITUTED getLong(" + columnIndex + ")");
                return SpoofLogic.gsfToLong(fakeGsf, super.getLong(columnIndex));
            }
            return super.getLong(columnIndex);
        }
        @Override public byte[] getBlob(int columnIndex) {
            // Some callers read the value column as a blob — return the fake GSF's bytes.
            if (isAndroidIdValueColumn(columnIndex)) return fakeGsf.getBytes();
            return super.getBlob(columnIndex);
        }
        @Override public void copyStringToBuffer(int columnIndex, android.database.CharArrayBuffer buffer) {
            // Cursor.copyStringToBuffer bypasses getString — cover it so it can't leak the real value.
            if (isAndroidIdValueColumn(columnIndex)) {
                char[] data = fakeGsf.toCharArray();
                buffer.data = data;
                buffer.sizeCopied = data.length;
                return;
            }
            super.copyStringToBuffer(columnIndex, buffer);
        }
        private boolean isAndroidIdValueColumn(int columnIndex) {
            // rows are (name, value); value is the last column. Guard against unexpected schemas.
            try {
                return SpoofLogic.isAndroidIdValueColumn(super.getString(0), columnIndex, getColumnCount());
            } catch (Throwable t) { return false; }
        }
    }

    // ---- MediaDrm widevine device unique id ----
    private void hookMediaDrm(XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        final String drm = p.get("media_drm_id");
        if (drm == null) return;
        Class<?> md = XposedHelpers.findClassIfExists("android.media.MediaDrm", lp.classLoader);
        if (md == null) return;
        try {
            final boolean drmTrace = "1".equals(p.get("trace"));
            XposedBridge.hookAllMethods(md, "getPropertyByteArray", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (drmTrace && param.args.length > 0)
                        XposedBridge.log("[specter][drm] getPropertyByteArray " + param.args[0]);
                    if (param.args.length > 0 && "deviceUniqueId".equals(String.valueOf(param.args[0]))) {
                        XposedBridge.log("[specter][idtrace] MediaDrm deviceUniqueId -> " + drm);
                        param.setResult(hexToBytes(drm));
                    }
                }
            });
        } catch (Throwable ignored) {}
        // securityLevel MUST be coherent with the spoofed deviceUniqueId: a changing id at a real L1
        // (fixed-hardware-id) is itself a fingerprint. Return L3 (software Widevine) so id+level agree.
        // Confirmed on-device: without this, probe read spoofed id @ real L1. See docs/BYEDENTITY-ANALYSIS.md.
        final String drmLevel = p.get("media_drm_security_level");
        if (drmLevel != null) {
            try {
                XposedBridge.hookAllMethods(md, "getPropertyString", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if ("1".equals(p.get("trace")) && param.args.length > 0)
                            XposedBridge.log("[specter][drm] getPropertyString " + param.args[0]);
                        if (param.args.length > 0 && "securityLevel".equals(String.valueOf(param.args[0]))) {
                            param.setResult(drmLevel);
                        }
                    }
                });
            } catch (Throwable ignored) {}
        }
    }

    // ---- helpers ----
    // slot-aware IMEI: getImei(0)->imei1, getImei(1)->imei2
    private void hookSlotImei(Class<?> tm, String method, final String imei1, final String imei2) {
        try {
            XposedHelpers.findAndHookMethod(tm, method, int.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    int slot = (param.args.length > 0 && param.args[0] instanceof Integer)
                            ? (Integer) param.args[0] : 0;
                    param.setResult(SpoofLogic.imeiForSlot(slot, imei1, imei2));
                }
            });
        } catch (Throwable ignored) {}
    }

    private void rc(Class<?> c, String method, String val, Class<?>... params) {
        if (val == null) return;
        try { XposedHelpers.findAndHookMethod(c, method, appended(params,
                XC_MethodReplacement.returnConstant(val))); } catch (Throwable ignored) {}
    }
    private Object[] appended(Class<?>[] params, Object hook) {
        Object[] a = new Object[params.length + 1];
        System.arraycopy(params, 0, a, 0, params.length);
        a[params.length] = hook;
        return a;
    }
    private static byte[] hexToBytes(String s) {
        int n = s.length() / 2; byte[] b = new byte[n];
        for (int i = 0; i < n; i++)
            b[i] = (byte) Integer.parseInt(s.substring(i*2, i*2+2), 16);
        return b;
    }
}
