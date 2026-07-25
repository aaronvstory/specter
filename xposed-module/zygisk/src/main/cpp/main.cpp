// Specter Zygisk native layer — closes the two signals PROVEN to leak exclusively through libc,
// straight past every Xposed Java hook (GOAL 1.2):
//   1. System properties — libc __system_property_read_callback (the Android 10+ path behind
//      __system_property_get). An NDK fingerprinter reading ro.product.model etc. sees the REAL device
//      through the Java SystemProperties.get hook; this closes it in-process.
//   2. factoryReset mtime — libc stat/fstatat/statx/lstat on the reset-marker dirs. FingerprintJS Pro
//      reads the reset timestamp natively; the Java Os.stat hook (verified active) did not stop it.
//
// Per-app by design: postAppSpecialize gates on the package name, and values come from the SAME
// /data/local/tmp/specter/<pkg>.json the Xposed module reads (ONE source of truth, never a second
// generator). The fleet apps (GeerGit's) have no profile file, so they are never touched.
//
// The profile dir is root:root shell_data_file:s0 — an untrusted_app cannot read it (SELinux), so the
// read happens in the root companion and the JSON is passed back over the Zygisk companion socket.
#include <android/log.h>
#include <sys/stat.h>
#include <sys/system_properties.h>
#include <unistd.h>
#include <cstring>
#include <cstdlib>
#include <string>
#include <map>
#include <fcntl.h>

#include <dlfcn.h>          // dlsym(RTLD_DEFAULT, ...) to resolve libc symbol addresses
#include "zygisk.hpp"
#include "And64InlineHook.hpp"   // inline hooks (the internal bionic prop path can't be caught via PLT)
#include "spoof_logic.h"   // parse_flat_json, is_reset_marker, valid_pkg, PROP_ALIASES, RESET_PATHS

using specter::PROP_ALIASES;
using specter::parse_flat_json;
using specter::is_reset_marker;
using specter::valid_pkg;
using specter::is_fleet_app;

#define LOG_TAG "SpecterZygisk"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

// -------- per-process spoof state (populated once in postAppSpecialize) --------
static std::map<std::string, std::string> g_prop_spoof;  // prop name -> spoofed value
static long g_reset_epoch = 0;                            // factory_reset_epoch (seconds), 0 = unset

// ================= system-property hook =================
// __system_property_read_callback(pi, callback, cookie) invokes callback(cookie, name, value, serial).
// We keep the real function but swap the caller's callback+cookie for our trampoline, which substitutes
// the value when `name` is one we spoof. This is the exact PlayIntegrityFork / NyaZygisk technique.
using prop_read_cb_t = void (*)(void *cookie, const char *name, const char *value, uint32_t serial);
using prop_read_t = void (*)(const prop_info *pi, prop_read_cb_t callback, void *cookie);
static prop_read_t orig_prop_read = nullptr;

struct cb_ctx {
    prop_read_cb_t real_cb;
    void *real_cookie;
};

static void tramp_cb(void *cookie, const char *name, const char *value, uint32_t serial) {
    auto *ctx = reinterpret_cast<cb_ctx *>(cookie);
    if (name) {
        auto it = g_prop_spoof.find(name);
        if (it != g_prop_spoof.end()) {
            ctx->real_cb(ctx->real_cookie, name, it->second.c_str(), serial);
            return;
        }
    }
    ctx->real_cb(ctx->real_cookie, name, value, serial);
}

static void my_prop_read(const prop_info *pi, prop_read_cb_t callback, void *cookie) {
    if (g_prop_spoof.empty()) { orig_prop_read(pi, callback, cookie); return; }
    cb_ctx ctx{callback, cookie};
    orig_prop_read(pi, tramp_cb, &ctx);
}

// The classic path: __system_property_get(name, value) → fills value, returns length. Many SDKs (and
// our own dual-read probe) call this directly rather than the callback form, so we must intercept it
// too — hooking only the callback misses these callers (that was the first on-device failure mode).
using prop_get_t = int (*)(const char *name, char *value);
static prop_get_t orig_prop_get = nullptr;

static int my_prop_get(const char *name, char *value) {
    if (name && value) {
        auto it = g_prop_spoof.find(name);
        if (it != g_prop_spoof.end()) {
            size_t n = it->second.size();
            if (n >= PROP_VALUE_MAX) n = PROP_VALUE_MAX - 1;
            memcpy(value, it->second.c_str(), n);
            value[n] = '\0';
            return (int) n;
        }
    }
    return orig_prop_get(name, value);
}

// ================= stat-family hooks (factory-reset mtime) =================
// Rewrite mtime/ctime/atime on the reset-marker dirs to the spoofed epoch. Leaving ctime/atime real
// would leak the true reset date via a different field (a provable leak); on a dir untouched since
// factory reset, all three being equal is in fact the common case (mirrors the Java Os.stat hook).
static void spoof_stat(struct stat *st) {
    if (!st || g_reset_epoch == 0) return;
    st->st_mtim.tv_sec = g_reset_epoch; st->st_mtim.tv_nsec = 0;
    st->st_ctim.tv_sec = g_reset_epoch; st->st_ctim.tv_nsec = 0;
    st->st_atim.tv_sec = g_reset_epoch; st->st_atim.tv_nsec = 0;
}

using stat_t = int (*)(const char *, struct stat *);
using lstat_t = int (*)(const char *, struct stat *);
using fstatat_t = int (*)(int, const char *, struct stat *, int);
using statx_t = int (*)(int, const char *, int, unsigned int, struct statx *);
static stat_t orig_stat = nullptr;
static lstat_t orig_lstat = nullptr;
static fstatat_t orig_fstatat = nullptr;
static statx_t orig_statx = nullptr;

static int my_stat(const char *path, struct stat *st) {
    int r = orig_stat(path, st);
    if (r == 0 && is_reset_marker(path)) spoof_stat(st);
    return r;
}
static int my_lstat(const char *path, struct stat *st) {
    int r = orig_lstat(path, st);
    if (r == 0 && is_reset_marker(path)) spoof_stat(st);
    return r;
}
static int my_fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    int r = orig_fstatat(dirfd, path, st, flags);
    // The reset markers are absolute paths, so dirfd is irrelevant when the path matches.
    if (r == 0 && is_reset_marker(path)) spoof_stat(st);
    return r;
}
static int my_statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *stx) {
    int r = orig_statx(dirfd, path, flags, mask, stx);
    if (r == 0 && stx && g_reset_epoch != 0 && is_reset_marker(path)) {
        stx->stx_mtime.tv_sec = g_reset_epoch; stx->stx_mtime.tv_nsec = 0;
        stx->stx_ctime.tv_sec = g_reset_epoch; stx->stx_ctime.tv_nsec = 0;
        stx->stx_atime.tv_sec = g_reset_epoch; stx->stx_atime.tv_nsec = 0;
    }
    return r;
}

// Why inline hooks, not PLT: bionic's __system_property_get calls __system_property_read_callback via
// an INTERNAL direct call, not through libc's PLT. PLT hooking only rewrites a *caller library's* GOT
// import, so it can never intercept that internal path (proven on-device: the PLT attempt reported a
// backup yet native props still leaked). An inline hook rewrites the first bytes of the function in
// libc itself, so every caller — internal, external, or a library dlopen'd later — is intercepted.
// And64InlineHook (A64HookFunction) patches by address; dlsym gives us the libc symbol addresses.
// CRITICAL: never hook the same resolved address twice. On arm64 LP64, aliases like fstatat/fstatat64
// (and sometimes stat) point at the SAME libc function; hooking it a second time patches the already-
// patched code, so the second "original" trampoline jumps back into the first hook — infinite
// recursion, stack overflow, crash (observed on-device). Dedupe by address.
static std::map<void *, bool> g_hooked_addrs;

static bool hookSym(const char *sym, void *fn, void **backup) {
    void *addr = dlsym(RTLD_DEFAULT, sym);
    if (!addr) { LOGD("dlsym %s -> null", sym); return false; }
    if (g_hooked_addrs.count(addr)) { LOGD("skip %s (addr already hooked)", sym); return false; }
    void *tramp = nullptr;
    A64HookFunction(addr, fn, &tramp);
    if (!tramp) { LOGD("A64HookFunction %s failed", sym); return false; }
    g_hooked_addrs[addr] = true;
    *backup = tramp;
    return true;
}

class SpecterModule : public zygisk::ModuleBase {
public:
    void onLoad(Api *api, JNIEnv *env) override {
        this->api = api;
        this->env = env;
    }

    void preAppSpecialize(AppSpecializeArgs *args) override {
        // Grab the package name and fetch its profile from the root companion BEFORE specialization —
        // connectCompanion() only works pre-specialize (SELinux). Cache it; hook after specialize.
        if (!args || !args->nice_name) return;
        const char *nice = env->GetStringUTFChars(args->nice_name, nullptr);
        if (nice) { pkg = nice; env->ReleaseStringUTFChars(args->nice_name, nice); }
        if (pkg.empty()) return;

        int fd = api->connectCompanion();
        if (fd < 0) { LOGE("connectCompanion failed for %s", pkg.c_str()); return; }
        // send: 4-byte length + package name; recv: 4-byte length + json bytes (0 == no profile)
        uint32_t plen = (uint32_t) pkg.size();
        write(fd, &plen, sizeof(plen));
        write(fd, pkg.data(), plen);
        uint32_t jlen = 0;
        if (read_full(fd, &jlen, sizeof(jlen)) && jlen > 0 && jlen < (1u << 20)) {
            json.resize(jlen);
            if (!read_full(fd, json.data(), jlen)) json.clear();
        }
        close(fd);
    }

    void postAppSpecialize(const AppSpecializeArgs *) override {
        if (json.empty()) return;     // not a Specter target — leave the process untouched
        auto profile = parse_flat_json(json);
        if (profile.empty()) return;

        for (auto &a : PROP_ALIASES) {
            auto it = profile.find(a[1]);
            if (it != profile.end()) g_prop_spoof[a[0]] = it->second;
        }
        auto ep = profile.find("factory_reset_epoch");
        if (ep != profile.end()) g_reset_epoch = strtol(ep->second.c_str(), nullptr, 10);

        if (g_prop_spoof.empty() && g_reset_epoch == 0) return;
        installHooks();
    }

private:
    Api *api = nullptr;
    JNIEnv *env = nullptr;
    std::string pkg;
    std::string json;

    static bool read_full(int fd, void *buf, size_t n) {
        auto *p = reinterpret_cast<uint8_t *>(buf);
        size_t got = 0;
        while (got < n) {
            ssize_t r = read(fd, p + got, n - got);
            if (r <= 0) return false;
            got += (size_t) r;
        }
        return true;
    }

    void installHooks() {
        int applied = 0;
        if (!g_prop_spoof.empty()) {
            // Both property read paths: the modern callback form and the classic get form.
            applied += hookSym("__system_property_read_callback", (void *) my_prop_read, (void **) &orig_prop_read);
            applied += hookSym("__system_property_get",           (void *) my_prop_get,  (void **) &orig_prop_get);
        }
        if (g_reset_epoch != 0) {
            applied += hookSym("stat",      (void *) my_stat,    (void **) &orig_stat);
            applied += hookSym("lstat",     (void *) my_lstat,   (void **) &orig_lstat);
            applied += hookSym("fstatat",   (void *) my_fstatat, (void **) &orig_fstatat);
            applied += hookSym("fstatat64", (void *) my_fstatat, (void **) &orig_fstatat);
            applied += hookSym("statx",     (void *) my_statx,   (void **) &orig_statx);
        }
        if (applied == 0) { LOGE("no hooks applied for %s", pkg.c_str()); return; }
        LOGD("hooks installed for %s (%d syms, props=%zu reset=%ld)",
             pkg.c_str(), applied, g_prop_spoof.size(), g_reset_epoch);
    }
};

// ================= root companion: read the profile file =================
// Runs as root in the Zygisk daemon. Reads /data/local/tmp/specter/<pkg>.json (the file an
// untrusted_app cannot read due to SELinux) and streams it back. valid_pkg() (spoof_logic.h) rejects
// any package name that isn't a plain reverse-DNS token, so it can never escape the specter dir.
static void companion_handler(int client) {
    uint32_t plen = 0;
    if (read(client, &plen, sizeof(plen)) != (ssize_t) sizeof(plen) || plen == 0 || plen > 255) {
        uint32_t zero = 0; write(client, &zero, sizeof(zero)); return;
    }
    std::string pkg(plen, '\0');
    size_t got = 0;
    while (got < plen) {
        ssize_t r = read(client, pkg.data() + got, plen - got);
        if (r <= 0) { uint32_t zero = 0; write(client, &zero, sizeof(zero)); return; }
        got += (size_t) r;
    }
    if (!valid_pkg(pkg)) { uint32_t zero = 0; write(client, &zero, sizeof(zero)); return; }
    // Fleet safety: never serve a profile for a GeerGit-owned app, even if a stray file exists.
    if (is_fleet_app(pkg)) { uint32_t zero = 0; write(client, &zero, sizeof(zero)); return; }

    std::string path = "/data/local/tmp/specter/" + pkg + ".json";
    std::string data;
    int fd = open(path.c_str(), O_RDONLY | O_CLOEXEC);
    if (fd >= 0) {
        char buf[4096]; ssize_t r;
        while ((r = read(fd, buf, sizeof(buf))) > 0) data.append(buf, (size_t) r);
        close(fd);
    }
    uint32_t jlen = (uint32_t) data.size();
    write(client, &jlen, sizeof(jlen));
    if (jlen > 0) write(client, data.data(), jlen);
}

// The Zygisk loader resolves the entry points by their plain (C) names via dlsym, so they must have C
// linkage — the REGISTER_ZYGISK_* macros emit C++-mangled names, which the loader can't find. Define
// them here with extern "C", delegating to the same internal impl the macros would call.
extern "C" [[gnu::visibility("default")]]
void zygisk_module_entry(zygisk::internal::api_table *table, JNIEnv *env) {
    zygisk::internal::entry_impl<SpecterModule>(table, env);
}
extern "C" [[gnu::visibility("default")]]
void zygisk_companion_entry(int client) {
    companion_handler(client);
}
