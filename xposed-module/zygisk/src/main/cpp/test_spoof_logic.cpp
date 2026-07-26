// Self-test for the Zygisk native layer's pure logic (spoof_logic.h). No framework: assert + a main.
// Cross-compiled for arm64 by run-zygisk-tests.sh and run on-device (the only aarch64 target on hand).
#include "spoof_logic.h"
#include <cassert>
#include <cstdio>
#include <string>

using namespace specter;

// The real FPJS-demo profile shape (a raven Pixel 6 Pro row), trimmed — exercises the exact JSON the
// generator writes: flat, all-string, spaces in values ("Pixel 6 Pro"), trailing keys.
static const char *SAMPLE =
    "{\"android_id\": \"7ac1e43332d50594\", \"serial\": \"NS42FGH7HWQP87\", "
    "\"build_model\": \"raven\", \"build_manufacturer\": \"Google\", \"build_brand\": \"google\", "
    "\"build_device\": \"Pixel 6 Pro\", \"soc_platform\": \"gs101\", "
    "\"build_fingerprint\": \"google/raven/Pixel 6 Pro:12/SD1A.210817.015.A4/7697517:user/release-keys\", "
    "\"build_kernel_version\": \"4.14.143-android10-g85065886\", "
    "\"factory_reset_epoch\": \"1644830296\"}";

int main() {
    int fails = 0;
    #define CHECK(cond, msg) do { if (!(cond)) { printf("FAIL: %s\n", msg); fails++; } } while (0)

    // ---- parse_flat_json ----
    auto p = parse_flat_json(SAMPLE);
    CHECK(p["android_id"] == "7ac1e43332d50594", "android_id");
    CHECK(p["serial"] == "NS42FGH7HWQP87", "serial");
    CHECK(p["build_device"] == "Pixel 6 Pro", "value with spaces");
    CHECK(p["build_fingerprint"] ==
          "google/raven/Pixel 6 Pro:12/SD1A.210817.015.A4/7697517:user/release-keys", "value with slashes/colons");
    CHECK(p["soc_platform"] == "gs101", "soc_platform");
    CHECK(p["factory_reset_epoch"] == "1644830296", "reset epoch (last key, no trailing comma)");
    CHECK(p.count("nonexistent") == 0, "absent key");

    // Empty / malformed input must not crash and yields no keys.
    CHECK(parse_flat_json("").empty(), "empty string");
    CHECK(parse_flat_json("{}").empty(), "empty object");
    CHECK(parse_flat_json("garbage").empty(), "garbage");

    // ---- prop-alias mapping produces the right spoofed value for a native prop name ----
    // ro.product.model -> build_model -> "raven"; ro.board.platform -> soc_platform -> "gs101".
    std::map<std::string, std::string> byprop;
    for (int i = 0; i < PROP_ALIASES_N; i++) {
        auto it = p.find(PROP_ALIASES[i][1]);
        if (it != p.end()) byprop[PROP_ALIASES[i][0]] = it->second;
    }
    CHECK(byprop["ro.product.model"] == "raven", "ro.product.model alias");
    CHECK(byprop["ro.board.platform"] == "gs101", "ro.board.platform alias");
    CHECK(byprop["ro.serialno"] == "NS42FGH7HWQP87", "ro.serialno alias");
    CHECK(byprop["ro.build.fingerprint"].find("release-keys") != std::string::npos, "fingerprint alias");

    // ---- is_reset_marker: exact match only, never a prefix (app files under these dirs untouched) ----
    CHECK(is_reset_marker("/data/system"), "exact reset marker");
    CHECK(is_reset_marker("/data/misc"), "exact reset marker 2");
    CHECK(!is_reset_marker("/data/system/users"), "child of marker is NOT a match");
    CHECK(!is_reset_marker("/data/data/com.app/files"), "app file not a marker");
    CHECK(!is_reset_marker(nullptr), "null path");
    CHECK(!is_reset_marker(""), "empty path");

    // ---- valid_pkg: reverse-DNS only; no traversal ----
    CHECK(valid_pkg("com.fingerprintjs.android.fpjs_pro_demo"), "normal pkg");
    CHECK(valid_pkg("com.liuzh.deviceinfo"), "normal pkg 2");
    CHECK(!valid_pkg("../etc/passwd"), "traversal rejected");
    CHECK(!valid_pkg("com/foo"), "slash rejected");
    CHECK(!valid_pkg("a..b"), "double-dot rejected");
    CHECK(!valid_pkg(".hidden"), "leading dot rejected");
    CHECK(!valid_pkg("trailing."), "trailing dot rejected");
    CHECK(!valid_pkg(""), "empty pkg rejected");
    CHECK(!valid_pkg(std::string(300, 'a')), "overlong pkg rejected");

    // ---- is_core_os: OS-safety guard. Only the framework/system process is denied — spoofing the OS
    // itself is dangerous+pointless. Every real APP (including the income apps) IS spoofable by design. ----
    CHECK(is_core_os("android"), "android (framework) denied");
    CHECK(is_core_os("system"), "system denied");
    CHECK(!is_core_os("com.doordash.driverapp"), "doordash IS spoofable (product purpose)");
    CHECK(!is_core_os("com.pyshivam.geergit"), "geergit IS spoofable");
    CHECK(!is_core_os("com.fingerprintjs.android.fpjs_pro_demo"), "fpjs demo spoofable");
    CHECK(!is_core_os("com.liuzh.deviceinfo"), "devinfo spoofable");
    CHECK(!is_core_os("com.specter.probe"), "probe spoofable");

    if (fails == 0) printf("ALL PASS (spoof_logic)\n");
    else printf("%d FAILURE(S)\n", fails);
    return fails == 0 ? 0 : 1;
}
