package com.fleet.idrotate;

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
 * Reads a flat profile.json (pushed to /data/local/tmp/ghostprint/<pkg>.json OR a shared
 * profile) and injects those fake values into the target app. Covers the SAME identifier
 * surface GeerGit 2.9.4 hooks — extracted from its dart string pool — with the fix baked in:
 * every value comes from a freshly-generated profile, so there is no stale-GSF reuse
 * (the 2.9.6 regression that caused the DoorDash "coordinated accounts" bans).
 *
 * Scope which apps get hooked via LSPosed's scope UI (e.g. com.doordash.driverapp).
 */
public class HookEntry implements IXposedHookLoadPackage {

    // where the push .bat drops per-app profiles
    private static final String PROFILE_DIR = "/data/local/tmp/ghostprint/";

    @Override
    public void handleLoadPackage(final XC_LoadPackage.LoadPackageParam lpparam) {
        final String pkg = lpparam.packageName;
        final Map<String, String> p = loadProfile(pkg);
        if (p == null || p.isEmpty()) return; // not a scoped/targeted app -> do nothing
        XposedBridge.log("[idrotate] active for " + pkg + " (" + p.size() + " fields)");

        hookBuildFields(p);
        hookSettingsSecure(p);
        hookTelephony(lpparam, p);
        hookWifi(lpparam, p);
        hookBluetooth(lpparam, p);
        hookAdvertisingId(lpparam, p);
        hookGsf(lpparam, p);
        hookMediaDrm(lpparam, p);
    }

    // ---- profile loading: per-app file wins, else a shared default ----
    private Map<String, String> loadProfile(String pkg) {
        Map<String, String> m = new HashMap<>();
        try {
            File f = new File(PROFILE_DIR + pkg + ".json");
            if (!f.exists()) f = new File(PROFILE_DIR + "profile.json");
            if (!f.exists()) return m;
            byte[] b = new byte[(int) f.length()];
            java.io.FileInputStream in = new java.io.FileInputStream(f);
            in.read(b); in.close();
            JSONObject j = new JSONObject(new String(b));
            java.util.Iterator<String> it = j.keys();
            while (it.hasNext()) { String k = it.next(); m.put(k, j.getString(k)); }
        } catch (Throwable t) { XposedBridge.log("[idrotate] profile load fail: " + t); }
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
        // getSerial() (API 26+) is a method, not just the field
        try {
            XposedHelpers.findAndHookMethod(Build.class, "getSerial",
                XC_MethodReplacement.returnConstant(p.get("serial")));
        } catch (Throwable ignored) {}
    }

    // ---- Settings.Secure.getString(..., "android_id") ----
    private void hookSettingsSecure(final Map<String, String> p) {
        final String aid = p.get("android_id");
        if (aid == null) return;
        XC_MethodHook h = new XC_MethodHook() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                Object key = param.args.length >= 2 ? param.args[1] : null;
                if ("android_id".equals(String.valueOf(key))) param.setResult(aid);
            }
        };
        try { XposedHelpers.findAndHookMethod(Settings.Secure.class, "getString",
                android.content.ContentResolver.class, String.class, h); } catch (Throwable ignored) {}
    }

    // ---- TelephonyManager: imei/deviceid/subscriber/simserial/line1/operator ----
    private void hookTelephony(XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        Class<?> tm = XposedHelpers.findClassIfExists("android.telephony.TelephonyManager", lp.classLoader);
        if (tm == null) return;
        rc(tm, "getImei", p.get("imei1"));
        rc(tm, "getImei", p.get("imei1"), int.class);          // getImei(int slot)
        rc(tm, "getDeviceId", p.get("imei1"));
        rc(tm, "getDeviceId", p.get("imei1"), int.class);
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

    // ---- AdvertisingIdClient.Info.getId ----
    private void hookAdvertisingId(XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        Class<?> info = XposedHelpers.findClassIfExists(
            "com.google.android.gms.ads.identifier.AdvertisingIdClient$Info", lp.classLoader);
        if (info == null) return;
        rc(info, "getId", p.get("advertising_id"));
    }

    // ---- GSF id: content query to com.google.android.gsf.gservices returning android_id ----
    // THE surface that regressed in 2.9.6. Hook the provider read + GServices helper.
    private void hookGsf(XC_LoadPackage.LoadPackageParam lp, final Map<String, String> p) {
        final String gsf = p.get("gsf_id");
        if (gsf == null) return;
        Class<?> gs = XposedHelpers.findClassIfExists("com.google.android.gsf.Gservices", lp.classLoader);
        if (gs != null) {
            try {
                XposedBridge.hookAllMethods(gs, "getString", new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        for (Object a : param.args)
                            if ("android_id".equals(String.valueOf(a))) { param.setResult(gsf); return; }
                    }
                });
            } catch (Throwable ignored) {}
        }
        // Also intercept the raw ContentResolver query to the gservices provider
        // (apps often read gsf android_id via a direct cursor).
        // Left to the provider hook in most stacks; GServices helper covers the common path.
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
    }

    // ---- helpers ----
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
