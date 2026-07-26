// Pure, Android-free logic shared by the Zygisk module and its self-test. No JNI, no libc hooks — just
// the profile-JSON parse, the reset-marker match, and the companion package-name validation, so these
// can be unit-tested off the hook path (test_spoof_logic.cpp, run on-device via adb).
#pragma once

#include <string>
#include <map>
#include <cstring>

namespace specter {

// prop key -> profile key. MUST stay in lockstep with HookEntry.PROP_ALIASES (Java side).
static const char *PROP_ALIASES[][2] = {
    {"os.version", "build_kernel_version"},
    {"gsm.version.baseband", "build_radio"}, {"ril.baseband", "build_radio"},
    {"ro.board.platform", "soc_platform"}, {"ro.hardware.chipname", "soc_platform"},
    {"ro.soc.model", "soc_platform"},
    {"ro.product.model", "build_model"}, {"ro.product.vendor.model", "build_model"},
    {"ro.product.brand", "build_brand"}, {"ro.product.vendor.brand", "build_brand"},
    {"ro.product.manufacturer", "build_manufacturer"},
    {"ro.product.vendor.manufacturer", "build_manufacturer"},
    {"ro.product.device", "build_device"}, {"ro.product.vendor.device", "build_device"},
    {"ro.product.name", "build_product"}, {"ro.product.vendor.name", "build_product"},
    {"ro.build.id", "build_id"}, {"ro.build.display.id", "build_display"},
    {"ro.build.fingerprint", "build_fingerprint"},
    {"ro.vendor.build.fingerprint", "build_fingerprint"},
    {"ro.build.version.incremental", "build_incremental"},
    {"ro.build.version.release", "build_release"},
    {"ro.build.version.security_patch", "build_security_patch"},
    {"ro.build.host", "build_host"},
    {"ro.bootloader", "build_bootloader"}, {"ro.boot.bootloader", "build_bootloader"},
    {"ro.hardware", "build_hardware"}, {"ro.boot.hardware", "build_hardware"},
    {"ro.boot.hardware.platform", "soc_platform"},
    {"ro.product.board", "build_board"},
    {"ro.serialno", "serial"}, {"ro.boot.serialno", "serial"},
};
static const int PROP_ALIASES_N = sizeof(PROP_ALIASES) / sizeof(PROP_ALIASES[0]);

// reset-marker dirs. MUST stay in lockstep with HookEntry.FACTORY_RESET_PATHS (Java side).
static const char *RESET_PATHS[] = {
    "/data/misc/profiles", "/data/bootchart", "/data/misc/wifi", "/data/misc/bluetooth",
    "/data/vendor", "/data/dalvik-cache", "/data/misc", "/data/system",
};
static const int RESET_PATHS_N = sizeof(RESET_PATHS) / sizeof(RESET_PATHS[0]);

// A minimal parser for the flat {"k":"v",...} string-only profile JSON. The generator emits exactly
// this shape (see RootWriter.java / the on-device .json files); no nesting, no numbers, no escapes
// beyond plain ASCII values. Returns k->v.
inline std::map<std::string, std::string> parse_flat_json(const std::string &s) {
    std::map<std::string, std::string> m;
    size_t i = 0, n = s.size();
    auto skip_ws = [&] {
        while (i < n && (s[i] == ' ' || s[i] == '\t' || s[i] == '\n' || s[i] == '\r' ||
                         s[i] == ',' || s[i] == '{' || s[i] == '}')) i++;
    };
    while (i < n) {
        skip_ws();
        if (i >= n || s[i] != '"') break;
        i++;                                    // opening quote of key
        size_t ks = i;
        while (i < n && s[i] != '"') i++;
        std::string key = s.substr(ks, i - ks);
        if (i < n) i++;                         // closing quote
        skip_ws();
        if (i >= n || s[i] != ':') break;
        i++;                                    // colon
        while (i < n && (s[i] == ' ' || s[i] == '\t')) i++;
        if (i >= n || s[i] != '"') break;       // values are always quoted strings
        i++;                                    // opening quote of value
        size_t vs = i;
        while (i < n && s[i] != '"') i++;
        std::string val = s.substr(vs, i - vs);
        if (i < n) i++;                         // closing quote
        if (!key.empty()) m[key] = val;
    }
    return m;
}

inline bool is_reset_marker(const char *path) {
    if (!path) return false;
    for (int j = 0; j < RESET_PATHS_N; j++) if (strcmp(path, RESET_PATHS[j]) == 0) return true;
    return false;
}

// Reject any package name that isn't a plain reverse-DNS token, so the companion can never be tricked
// into escaping /data/local/tmp/specter (no '/', no "..").
inline bool valid_pkg(const std::string &p) {
    if (p.empty() || p.size() > 255) return false;
    for (char c : p) {
        if (!((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') ||
              c == '.' || c == '_')) return false;
    }
    if (p.front() == '.' || p.back() == '.' || p.find("..") != std::string::npos) return false;
    return true;
}

// Fleet-safety hard denylist (CLAUDE.md, NON-NEGOTIABLE): GeerGit owns these apps and the user makes
// real income there. The companion refuses to serve a profile for any of them EVEN IF a stray
// /data/local/tmp/specter/<pkg>.json exists, so this native module can never hook a fleet app —
// device-wide Zygisk injection is gated by the companion, not by which files happen to be present.
static const char *FLEET_DENYLIST[] = {
    "com.doordash.driverapp", "com.dd.doordash", "com.pyshivam.geergit",
    "android", "system",
};
static const int FLEET_DENYLIST_N = sizeof(FLEET_DENYLIST) / sizeof(FLEET_DENYLIST[0]);

inline bool is_fleet_app(const std::string &p) {
    for (int j = 0; j < FLEET_DENYLIST_N; j++) if (p == FLEET_DENYLIST[j]) return true;
    return false;
}

} // namespace specter
