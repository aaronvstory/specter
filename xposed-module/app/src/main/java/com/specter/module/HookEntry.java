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
        hookSettingsSecure(p);
        hookSettingsGlobal(p);
        hookTelephony(lpparam, p);
        hookWifi(lpparam, p);
        hookBluetooth(lpparam, p);
        hookAdvertisingId(lpparam, p);
        hookGsf(lpparam, p);
        hookMediaDrm(lpparam, p);
        hookSystemProperties(p);
        hookHardwareInfo(lpparam, p);
        hookStorage(lpparam, p);
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
        // hookAllMethods over findAndHookMethod (the varargs overload NoSuchMethodErrors against
        // LSPosed's obfuscated XposedHelpers). Cover System.getProperty AND the raw SystemProperties.
        try {
            XposedBridge.hookAllMethods(System.class, "getProperty", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) {
                    if (mp.args.length > 0 && "os.version".equals(mp.args[0])) mp.setResult(kernel);
                }
            });
        } catch (Throwable ignored) {}
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

    // ---- StatFs total/available storage — a FingerprintJS hardware signal that was LEAKING ----
    // profile.py generates total_storage but nothing injected it, so real storage leaked (a STABLE
    // real value that links accounts). Hook the whole StatFs family COHERENTLY: getTotalBytes and the
    // blockCount*blockSize path must multiply out to the SAME spoofed total, else an app computing
    // total from blocks sees the real value and the two disagree (a worse tell than leaving it real).
    // available/free = a realistic ~35-55% of total (kept deterministic per-identity via the id hash).
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
    private void hookSystemProperties(final Map<String, String> p) {
        final String kernel = p.get("build_kernel_version");
        final String radio = p.get("build_radio");
        final String soc = p.get("soc_platform");
        if (kernel == null && radio == null && soc == null) return;
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", null);
            XposedBridge.hookAllMethods(sp, "get", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam mp) {
                    if (mp.args.length == 0 || !(mp.args[0] instanceof String)) return;
                    String k = (String) mp.args[0];
                    switch (k) {
                        case "os.version":
                            if (kernel != null) mp.setResult(kernel); break;
                        case "gsm.version.baseband": case "ril.baseband":
                            if (radio != null) mp.setResult(radio); break;
                        case "ro.board.platform": case "ro.hardware.chipname": case "ro.soc.model":
                            if (soc != null) mp.setResult(soc); break;
                        default: // not a spoofed key — leave the real value
                    }
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
                if (SpoofLogic.argsContainKey(param.args, "android_id")) { param.setResult(aid); return; }
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
        XC_MethodHook getStr = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (argsContainAny(param.args, devTells)) param.setResult("0");
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
                            if ("android_id".equals(String.valueOf(a))) { param.setResult(gsf); return; }
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
                            if ("android_id".equals(String.valueOf(a))) { param.setResult(gsfLong); return; }
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
        if (!"com.liuzh.deviceinfo".equals(lp.packageName)) return;
        final XC_MethodHook wrapCursor = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object uri = param.args.length > 0 ? param.args[0] : null;
                    if (uri == null || !String.valueOf(uri).contains("com.google.android.gsf.gservices"))
                        return;
                    final android.database.Cursor real = (android.database.Cursor) param.getResult();
                    if (real == null) return;
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
            if (isAndroidIdValueColumn(columnIndex)) return fakeGsf;
            return super.getString(columnIndex);
        }
        @Override public long getLong(int columnIndex) {
            // GSF android_id is frequently read via getLong on the value column — cover it too.
            if (isAndroidIdValueColumn(columnIndex)) {
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
            XposedBridge.hookAllMethods(md, "getPropertyByteArray", new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length > 0 && "deviceUniqueId".equals(String.valueOf(param.args[0]))) {
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
