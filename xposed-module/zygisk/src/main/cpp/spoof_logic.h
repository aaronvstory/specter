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
    // SoC-codename siblings a fingerprinter also reads (Cash App reads both). Alias to soc_platform so the
    // whole SoC-name set stays coherent — else ro.chipname leaks the REAL codename on a host device where
    // it's populated (empty on the Pixel 4, but Specter targets ANY Android). Lockstep w/ HookEntry.
    {"ro.chipname", "soc_platform"}, {"ro.mediatek.platform", "soc_platform"},
    // GPU driver family — ro.hardware.egl / ro.hardware.vulkan leak the real GPU vendor otherwise (a Mali-
    // renderer profile read ro.hardware.egl=adreno on a Qualcomm host). Alias to gpu_hw. NOT ro.hardware.gralloc
    // (empty on real devices — forcing a value there is less coherent). Lockstep w/ HookEntry.
    {"ro.hardware.egl", "gpu_hw"}, {"ro.hardware.vulkan", "gpu_hw"},
    // Build.MODEL and friends exist per-PARTITION on Android 10+ (system/vendor/odm/product/system_ext).
    // Aliasing only ro.product.model + .vendor.* left odm/product/system_ext leaking the REAL device
    // (proven: ro.product.odm.model=Pixel 4). Cover every partition for each identity field.
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

// Core-OS denylist: never serve a profile for the framework/system process itself — hooking `android`/
// `system` would spoof device values for the OS and every app at once (dangerous, pointless, and can
// destabilize the device). This is an OS-safety guard, NOT an app denylist: the income/target apps
// (DoorDash, GeerGit, etc.) are SPOOFABLE — that's the product's whole purpose. The dev-time rule
// "test on the FPJS demo / DevInfo, don't experiment on the live Dasher unless needed" is a workflow
// discipline (see CLAUDE.md), not a hard code block.
static const char *CORE_OS_DENYLIST[] = { "android", "system" };
static const int CORE_OS_DENYLIST_N = sizeof(CORE_OS_DENYLIST) / sizeof(CORE_OS_DENYLIST[0]);

inline bool is_core_os(const std::string &p) {
    for (int j = 0; j < CORE_OS_DENYLIST_N; j++) if (p == CORE_OS_DENYLIST[j]) return true;
    return false;
}

} // namespace specter
