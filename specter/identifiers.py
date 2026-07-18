"""
identifiers.py — the single source of truth for the identifier surface.

Derived from GeerGit 2.9.4/2.9.6's own Dart string pool (docs/geergit-dart-strings-2.9.6.txt)
and its Xposed hook behavior. Each entry declares:
  key        -> our profile field name
  geergit    -> GeerGit's toggle/value key (for parity mapping)
  unique     -> must be globally unique across signups (ban-critical if reused)
  hook       -> the Android API surface the Xposed module hooks to inject it
  gen        -> how the value is generated (see generators.py)

This module is DATA ONLY — no logic — so tests can assert coverage against GeerGit's
surface and the Xposed module can be generated/checked from the same list.
"""
from dataclasses import dataclass


@dataclass(frozen=True)
class IdSpec:
    key: str
    geergit_key: str
    unique: bool
    hook: str          # human-readable hook target (also asserted against the Java module)
    gen: str           # generator name in generators.py


# Ordered by the GeerGit UI. `unique=True` = an id that MUST differ every signup.
SPECS = [
    IdSpec("android_id",          "android_id",     True,  "Settings.Secure.getString(...,'android_id')", "hex16"),
    IdSpec("imei1",               "imei1",          True,  "TelephonyManager.getImei/getDeviceId(0)",      "imei"),
    IdSpec("imei2",               "imei2",          True,  "TelephonyManager.getImei/getDeviceId(1)",      "imei"),
    IdSpec("serial",              "serial",         True,  "Build.getSerial / Build.SERIAL",               "hex16upper"),
    IdSpec("sim_operator_mccmnc", "sim_operator",   False, "TelephonyManager.getSimOperator/getNetworkOperator", "mccmnc"),
    IdSpec("sim_operator_name",   "sim_operator",   False, "TelephonyManager.get(Sim|Network)OperatorName", "carrier_name"),
    IdSpec("advertising_id",      "adsid",          True,  "AdvertisingIdClient.Info.getId",               "uuid"),
    IdSpec("serial_dup",          "serial",         False, "(alias, see serial)",                          "noop"),
    IdSpec("bluetooth_mac",       "bmac",           True,  "BluetoothAdapter.getAddress",                  "mac_upper"),
    IdSpec("wifi_mac",            "wmac",           True,  "WifiInfo.getMacAddress",                       "mac_upper"),
    IdSpec("wifi_ssid",           "wssid",          False, "WifiInfo.getSSID",                             "ssid"),
    IdSpec("wifi_bssid",          "wbssid",         True,  "WifiInfo.getBSSID",                            "mac_lower"),
    IdSpec("mobile_number",       "mob",            True,  "TelephonyManager.getLine1Number",              "phone_us"),
    IdSpec("sim_subscriber_imsi", "subid",          True,  "TelephonyManager.getSubscriberId",             "imsi"),
    IdSpec("sim_serial_iccid",    "simcs",          True,  "TelephonyManager.getSimSerialNumber",          "iccid"),
    IdSpec("gsf_id",              "gsfid",          True,  "Gservices.getString('android_id') / GSF provider", "gsf"),
    IdSpec("gmail",               "email",          False, "AccountManager.getAccountsByType('com.google')", "gmail"),
    IdSpec("media_drm_id",        "media_drm",      True,  "MediaDrm.getPropertyByteArray('deviceUniqueId')", "hex32"),
]

# device-profile (Build.*) fields come as a coherent BUNDLE from the device DB, not individually generated
BUILD_FIELDS = [
    "build_manufacturer", "build_brand", "build_device", "build_product",
    "build_model", "build_release", "build_id", "build_incremental",
    "build_fingerprint", "build_security_patch", "build_bootloader",
    "build_hardware", "build_board", "build_kernel_version",
]

UNIQUE_KEYS = [s.key for s in SPECS if s.unique]
ALL_KEYS = [s.key for s in SPECS if s.gen != "noop"] + BUILD_FIELDS

# GeerGit toggle keys we consider covered (for the parity test against docs/geergit-dart-strings)
GEERGIT_COVERED = {
    "android_id", "imei1", "imei2", "serial", "sim_operator", "adsid", "bmac",
    "wmac", "wssid", "wbssid", "mob", "subid", "simcs", "gsfid", "email", "media_drm",
    "device_spoof",
}
