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
#include <ifaddrs.h>   // getifaddrs hook (native VPN-interface masking)
#include <cstring>
#include <cstdlib>
#include <cstdarg>
#include <cstdio>
#include <cerrno>
#include <string>
#include <map>
#include <vector>
#include <set>
#include <mutex>
#include <utility>
#include <algorithm>       // std::transform for case-insensitive sensor-name matching
#include <cctype>          // std::tolower
#include <fcntl.h>

#include <dlfcn.h>          // dlsym(RTLD_DEFAULT, ...) to resolve libc symbol addresses
#include "zygisk.hpp"
#include "And64InlineHook.hpp"   // inline hooks (the internal bionic prop path can't be caught via PLT)
#include "spoof_logic.h"   // parse_flat_json, is_reset_marker, valid_pkg, PROP_ALIASES, RESET_PATHS

using specter::PROP_ALIASES;
using specter::parse_flat_json;
using specter::is_reset_marker;
using specter::valid_pkg;
using specter::is_core_os;

#define LOG_TAG "SpecterZygisk"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using zygisk::Api;
using zygisk::AppSpecializeArgs;
using zygisk::ServerSpecializeArgs;

// -------- per-process spoof state (populated once in postAppSpecialize) --------
static std::map<std::string, std::string> g_prop_spoof;  // prop name -> spoofed value
// Props that CRASH the zygote if spoofed during process init (ART/libc read them before our hook state
// is safe — proven SIGSEGV for ro.product.first_api_level / ro.build.version.sdk). We spoof them ONLY
// after init is done: g_props_ready flips true ~1s post-specialize (a detached thread), long before any
// user-triggered fingerprinting read. So init-time reads pass through REAL (no crash), runtime reads
// (e.g. FingerprintJS at fingerprint time) get the spoofed value.
static std::map<std::string, std::string, std::less<>> g_prop_spoof_late;
#include <atomic>
#include <thread>
#include <chrono>
static std::atomic<bool> g_props_ready{false};

// Look up a spoofed prop value: the always-safe map first, then the init-unsafe map ONLY once ready.
// Returns nullptr if not spoofed. Kept tiny + branch-light — it's on the hot __system_property_get path.
static const std::string *prop_spoof_lookup(const char *name) {
    if (!name) return nullptr;
    auto it = g_prop_spoof.find(name);
    if (it != g_prop_spoof.end()) return &it->second;
    if (g_props_ready.load(std::memory_order_acquire)) {
        auto lit = g_prop_spoof_late.find(name);
        if (lit != g_prop_spoof_late.end()) return &lit->second;
    }
    return nullptr;
}
static long g_reset_epoch = 0;                            // factory_reset_epoch (seconds), 0 = unset
static bool g_hide_root = false;                          // hide root-indicator paths (ENOENT)
static bool g_hide_vpn = false;                           // filter tun/ppp/wg from getifaddrs (native VPN mask)
static bool g_hide_kgsl = false;                          // ARM-GPU (Mali) profile: kgsl node must read ENOENT
static bool is_root_path(const char *path);              // defined below with the file hooks

// The Adreno GPU sysfs node. A Qualcomm device exposes /sys/class/kgsl/kgsl-3d0/*; a Mali (Exynos/Tensor)
// device has NO kgsl node at all. So for an ARM-GPU profile we must make the whole kgsl dir read ENOENT —
// otherwise the host's real Adreno number leaks under a "Mali-G78" GL_RENDERER, and the node merely EXISTING
// contradicts the claimed ARM GPU. Prefix-match so kgsl-3d0/gpu_model, /gpumodel, /gpu_busy_percentage etc.
// are all hidden together.
static bool is_kgsl_path(const char *path) {
    if (!path || strncmp(path, "/sys/class/kgsl", 15) != 0) return false;
    // Require a component boundary so we match "/sys/class/kgsl" and "/sys/class/kgsl/..." but NOT a sibling
    // like "/sys/class/kgslfoo".
    return path[15] == '\0' || path[15] == '/';
}

// A path a file-op should make disappear (ENOENT) for THIS identity: a root-indicator when hiding root, or
// the Adreno kgsl node when the profile claims a Mali GPU. Consolidates the guard so every open/stat/access
// hook covers both without duplicating two conditions at ten call sites.
static inline bool path_is_hidden(const char *path) {
    return (g_hide_root && is_root_path(path)) || (g_hide_kgsl && is_kgsl_path(path));
}

// -------- passive tracer (GOAL 1.3) --------
// When the profile carries "trace":"1", log every file open / prop / getauxval the target makes, so we
// can enumerate exactly what an OBFUSCATED native fingerprinter (FPJS's libfp.so) reads — from INSIDE
// libc, invisible to its /proc/self/maps anti-Frida check. Off by default (hot path, log spam).
static bool g_trace = false;
static void trace_path(const char *tag, const char *path) {
    // Only device-signal-relevant paths, to keep the log readable.
    if (!path) return;
    if (strncmp(path, "/proc", 5) == 0 || strncmp(path, "/sys", 4) == 0 ||
        strncmp(path, "/dev", 4) == 0 || strncmp(path, "/vendor", 7) == 0 ||
        strncmp(path, "/system", 7) == 0)
        __android_log_print(ANDROID_LOG_INFO, "SpecterTrace", "%s %s", tag, path);
}

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
        const std::string *sv = prop_spoof_lookup(name);
        if (sv) {
            ctx->real_cb(ctx->real_cookie, name, sv->c_str(), serial);
            return;
        }
    }
    ctx->real_cb(ctx->real_cookie, name, value, serial);
}

static void my_prop_read(const prop_info *pi, prop_read_cb_t callback, void *cookie) {
    if (g_prop_spoof.empty() && g_prop_spoof_late.empty()) { orig_prop_read(pi, callback, cookie); return; }
    cb_ctx ctx{callback, cookie};
    orig_prop_read(pi, tramp_cb, &ctx);
}

// The classic path: __system_property_get(name, value) → fills value, returns length. Many SDKs (and
// our own dual-read probe) call this directly rather than the callback form, so we must intercept it
// too — hooking only the callback misses these callers (that was the first on-device failure mode).
using prop_get_t = int (*)(const char *name, char *value);
static prop_get_t orig_prop_get = nullptr;

static int my_prop_get(const char *name, char *value) {
    if (g_trace && name)
        __android_log_print(ANDROID_LOG_INFO, "SpecterTrace", "prop %s", name);
    if (name && value) {
        const std::string *v = prop_spoof_lookup(name);
        if (v) {
            size_t n = v->size(); if (n >= PROP_VALUE_MAX) n = PROP_VALUE_MAX - 1;
            memcpy(value, v->c_str(), n); value[n] = '\0';
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
    if (g_trace) trace_path("stat", path);
    if (path_is_hidden(path)) { errno = ENOENT; return -1; }
    int r = orig_stat(path, st);
    if (r == 0 && is_reset_marker(path)) spoof_stat(st);
    return r;
}
static int my_lstat(const char *path, struct stat *st) {
    if (g_trace) trace_path("lstat", path);
    if (path_is_hidden(path)) { errno = ENOENT; return -1; }
    int r = orig_lstat(path, st);
    if (r == 0 && is_reset_marker(path)) spoof_stat(st);
    return r;
}
static int my_fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    if (g_trace) trace_path("fstatat", path);
    // bionic's stat() routes through fstatat, so a root/kgsl probe via stat("/system/bin/su") or a stat of
    // the Adreno node lands here — must hide (ENOENT) the same as my_stat, or the path leaks despite hooking.
    // ponytail: matches ABSOLUTE paths only. A dirfd-relative probe (fstatat(fd,"su",...)) isn't caught — no
    // real fingerprinter/root-check uses that form (they all pass absolute paths), and resolving dirfd->path
    // per call would be heavy; upgrade to readlink(/proc/self/fd/dirfd)+join only if a real probe needs it.
    if (path_is_hidden(path)) { errno = ENOENT; return -1; }
    int r = orig_fstatat(dirfd, path, st, flags);
    // The reset markers are absolute paths, so dirfd is irrelevant when the path matches.
    if (r == 0 && is_reset_marker(path)) spoof_stat(st);
    return r;
}
static int my_statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *stx) {
    if (g_trace) trace_path("statx", path);
    if (path_is_hidden(path)) { errno = ENOENT; return -1; }
    int r = orig_statx(dirfd, path, flags, mask, stx);
    if (r == 0 && stx && g_reset_epoch != 0 && is_reset_marker(path)) {
        stx->stx_mtime.tv_sec = g_reset_epoch; stx->stx_mtime.tv_nsec = 0;
        stx->stx_ctime.tv_sec = g_reset_epoch; stx->stx_ctime.tv_nsec = 0;
        stx->stx_atime.tv_sec = g_reset_epoch; stx->stx_atime.tv_nsec = 0;
    }
    return r;
}

// ================= /proc/cpuinfo redirect (hardware-signal spoof, GOAL 1.3) =================
// FPJS reads /proc/cpuinfo (proven: the string is in its APK) as a stable hardware signal. We can't
// rewrite the kernel's procfs, but we CAN redirect the open: when a target opens "/proc/cpuinfo", hand
// back an fd to our own spoofed file instead. The spoofed content is written once per process to the
// app's private files dir (always readable by the app's own uid) from the profile's `proc_cpuinfo`.
static std::string g_cpuinfo_path;   // path to our spoofed cpuinfo file, empty = not active

using openat_t = int (*)(int, const char *, int, ...);
using open_t   = int (*)(const char *, int, ...);
static openat_t orig_openat = nullptr;
static open_t   orig_open   = nullptr;

static bool is_cpuinfo(const char *path) {
    return path && strcmp(path, "/proc/cpuinfo") == 0;
}

// /proc/sys/kernel/random/boot_id — a per-boot UUID FPJS reads (proven via tracer). Redirect it to a
// per-identity spoofed UUID so it varies with the identity (and stays stable within a boot, like real).
static std::string g_bootid_path;   // path to our spoofed boot_id file, empty = not active
static bool is_bootid(const char *path) {
    return path && strcmp(path, "/proc/sys/kernel/random/boot_id") == 0;
}
// Per-SoC /sys hardware signals FPJS reads directly (tracer-proven): the CPU capacity vector
// (/sys/devices/system/cpu/cpu<N>/cpu_capacity — one number per core), the KGSL GPU model
// (/sys/class/kgsl/kgsl-3d0/gpu_model), and the present-CPU range (/sys/devices/system/cpu/present).
// These leaked the REAL device (e.g. the Pixel 4's "261 261 261 261 871 871 871 1024") on every
// rotation. We write a spoof file per path from the profile and redirect exact-path reads to it.
static std::map<std::string, std::string> g_sys_redirect;   // real sysfs path -> spoof file path
static const char *sys_redirect(const char *path) {
    if (g_sys_redirect.empty() || !path) return nullptr;
    auto it = g_sys_redirect.find(path);
    return it == g_sys_redirect.end() ? nullptr : it->second.c_str();
}

// /proc/self/maps hiding: libfp.so reads maps (tracer-proven) to detect our injected .so + Magisk/Zygisk
// (that's what sets rootApps/tampering). maps changes per read, so we regenerate a FILTERED copy on each
// open: drop any line naming our lib or a known root artifact. Returns an fd to a fresh temp, or -1.
static std::string g_files_dir;   // /data/data/<pkg>/files — a dir we can write temp files to
[[maybe_unused]] static bool is_maps(const char *path) {
    return path && strcmp(path, "/proc/self/maps") == 0;
}
static const char *MAPS_HIDE_MARKERS[] = {
    "libspecter_zygisk", "/data/adb/", "magisk", "zygisk", "/memfd:", "riru", "lsposed", "edxposed",
    "/dev/.magisk", "KSU", "kernelsu", "frida", "gadget", "gum-js-loop", "gmain",
};
[[maybe_unused]] static int clean_maps_fd() {
    FILE *real = fopen("/proc/self/maps", "re");
    if (!real) return -1;
    // Build filtered content in memory.
    std::string out;
    char line[1024];
    while (fgets(line, sizeof(line), real)) {
        bool hide = false;
        for (auto m : MAPS_HIDE_MARKERS) {
            // case-insensitive-ish contains
            if (strstr(line, m)) { hide = true; break; }
        }
        if (!hide) out.append(line);
    }
    fclose(real);
    // Write to a private temp fd the caller reads. Use a unique path in the app's cache dir isn't
    // reliable pre-`pkg`; use a memfd-like temp under /data/local? Not writable. Fall back to a temp file
    // under the app files dir which we know (g_cpuinfo_path shares that dir).
    if (g_files_dir.empty()) return -1;
    std::string mp = g_files_dir + "/.specter_maps";
    int fd = open(mp.c_str(), O_RDWR | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return -1;
    write(fd, out.data(), out.size());
    lseek(fd, 0, SEEK_SET);
    return fd;
}

// /proc/mounts + /proc/self/mountinfo LEAK Magisk unambiguously: real reads show tmpfs "magisk"
// overlays on /system_ext/bin and /debug_ramdisk/.magisk lines — a strong root/bind-mount signal a
// mount-reading detector catches even when the su/magisk BINARY paths are hidden (the byedentity-relevant
// vector). Unlike /proc/self/maps (which ART reads during GC — filtering it crashes the app), mountinfo
// is safe to filter. We build a filtered copy once per process (drops any line naming magisk / a hook
// framework / /data/adb) and redirect the read to it. Gated by g_hide_root.
static std::string g_mounts_path;      // filtered /proc/mounts
static std::string g_mountinfo_path;   // filtered /proc/self/mountinfo
static bool is_mounts(const char *path) {
    return path && (strcmp(path, "/proc/mounts") == 0 || strcmp(path, "/proc/self/mounts") == 0);
}
static bool is_mountinfo(const char *path) {
    return path && (strcmp(path, "/proc/self/mountinfo") == 0 || strcmp(path, "/proc/mountinfo") == 0);
}

// Write a filtered copy of a /proc mount file (magisk/hook/adb lines dropped) into the app files dir,
// and store its path in `out`. Best-effort; on any failure `out` stays empty and the real file is read.
static void build_filtered_mounts(const char *src, const std::string &tag, std::string &out) {
    if (g_files_dir.empty()) return;
    FILE *real = fopen(src, "re");
    if (!real) return;
    std::string filtered;
    char line[2048];
    while (fgets(line, sizeof(line), real)) {
        bool hide = false;
        for (auto m : MAPS_HIDE_MARKERS) if (strstr(line, m)) { hide = true; break; }
        if (!hide) filtered.append(line);
    }
    fclose(real);
    std::string path = g_files_dir + "/.specter_" + tag;
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;
    if (write(fd, filtered.data(), filtered.size()) == (ssize_t) filtered.size()) out = path;
    close(fd);
}

// SELinux enforce status — FPJS reads it NATIVELY (decompiled: da.component13(), "SELinux status
// unavailable") as a root signal. A Magisk device is often permissive or policy-patched, which reads
// as tampered. Redirect a read of /sys/fs/selinux/enforce to a file containing "1" so it reports
// ENFORCING. Gated by g_hide_root (it's a root/tamper signal). Best-effort.
static std::string g_selinux_path;   // spoof file "1\n", empty = not active
static void build_selinux_spoof() {
    if (g_files_dir.empty()) return;
    std::string path = g_files_dir + "/.specter_selinux";
    int fd = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
    if (fd < 0) return;
    if (write(fd, "1\n", 2) == 2) g_selinux_path = path;
    close(fd);
}
static bool is_selinux_enforce(const char *path) {
    return path && strcmp(path, "/sys/fs/selinux/enforce") == 0;
}

// Map a spoof-target path to our replacement file, or return the original path unchanged.
static const char *redirect_path(const char *path) {
    if (!g_cpuinfo_path.empty() && is_cpuinfo(path)) return g_cpuinfo_path.c_str();
    if (!g_bootid_path.empty()  && is_bootid(path))  return g_bootid_path.c_str();
    if (!g_mounts_path.empty()    && is_mounts(path))    return g_mounts_path.c_str();
    if (!g_mountinfo_path.empty() && is_mountinfo(path)) return g_mountinfo_path.c_str();
    if (!g_selinux_path.empty() && is_selinux_enforce(path)) return g_selinux_path.c_str();
    const char *sr = sys_redirect(path);
    if (sr) return sr;
    return path;
}

// Root-indicator paths FPJS (and other SDKs) probe to set `rootApps`/root-detection. Making these
// reads fail (ENOENT) hides root from an in-process check. Enabled per identity (g_hide_root). We match
// the common su/Magisk/root-manager paths; exact-match a small set + the "su" binary dirs.
static const char *ROOT_PATHS[] = {
    "/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/system/sd/xbin/su",
    "/system/bin/failsafe/su", "/data/local/su", "/data/local/bin/su", "/data/local/xbin/su",
    "/system/app/Superuser.apk", "/system/xbin/daemonsu", "/system/etc/init.d/99SuperSUDaemon",
    "/dev/com.koushikdutta.superuser.daemon/", "/system/xbin/busybox", "/data/adb/magisk",
    "/data/adb/modules", "/sbin/.magisk", "/cache/.disable_magisk", "/system/bin/magisk",
    "/system/xbin/magisk", "/data/adb/ksu", "/data/adb/ap",
    // Frida (a hooking/instrumentation framework) artifacts — a frida-detection check probes these
    // exact paths. Hide them like the su/magisk paths so an access()/stat()/File.exists() finds nothing.
    "/data/local/tmp/frida-server", "/data/local/tmp/frida-gadget",
    "/data/local/tmp/re.frida.server", "/system/lib/libfrida-gadget.so",
    "/system/lib64/libfrida-gadget.so", "/data/local/tmp/frida",
};
// Root-owned directory PREFIXES. FPJS's native root check probes a ~200-entry list (encrypted, can't
// enumerate) — an exact-match denylist of 24 paths always loses to it. Prefix-matching the root-owned
// trees covers the WHOLE family (magisk db/service.d/post-fs-data.d/modules/riru, lspd, ksu, ap, the
// magisk mirror dirs, root-app data/app dirs) in one rule, so a stat of any sub-path returns ENOENT.
static const char *ROOT_PREFIXES[] = {
    "/data/adb/",            // magisk/ksu/ap root dir: modules, service.d, post-fs-data.d, magisk.db, lspd, riru
    "/sbin/.magisk",         // magisk mirror/mount tree
    "/dev/.magisk",
    "/cache/.disable_magisk",
    "/debug_ramdisk/.magisk",
    // Root-manager + common root-app package dirs (a native access("/data/data/<pkg>") probe). The Java
    // PackageManager filter doesn't cover the native filesystem path.
    "/data/data/com.topjohnwu.magisk", "/data/user/0/com.topjohnwu.magisk",
    "/data/data/eu.chainfire.supersu", "/data/data/com.koushikdutta.superuser",
    "/data/data/me.weishu.kernelsu", "/data/data/com.zachspong.temprootremovejb",
    "/data/data/org.lsposed.manager", "/data/data/io.github.lsposed.manager",
};
static bool is_root_path(const char *path) {
    if (!path) return false;
    for (auto p : ROOT_PATHS) if (strcmp(path, p) == 0) return true;
    for (auto pre : ROOT_PREFIXES) {
        size_t l = strlen(pre);
        if (strncmp(path, pre, l) != 0) continue;
        // Require a path-component boundary so "/data/data/com.topjohnwu.magisk" doesn't match a
        // legit "...magisker": the match must end the string, be followed by '/', or the prefix
        // itself already ends in '/' (e.g. "/data/adb/").
        if (path[l] == '\0' || path[l] == '/' || pre[l - 1] == '/') return true;
    }
    // "which su" style: any path ending in "/su"
    size_t n = strlen(path);
    if (n >= 3 && strcmp(path + n - 3, "/su") == 0) return true;
    return false;
}
// NOTE: /proc/self/maps cleaning was tried and REVERTED — ART reads its own maps during startup/GC, so
// handing it a filtered file crashed FPJS on launch (splash loop, process died). Hiding root/tamper from
// libfp.so's maps read needs a far more surgical approach (identify the libfp caller, or intercept only
// its specific read), out of scope for a quick pass. clean_maps_fd()/is_maps() kept but unused.
// The variadic `mode` arg is ONLY present when flags include O_CREAT or O_TMPFILE. Reading va_arg
// unconditionally (even for a plain O_RDONLY open) is undefined behavior — read it only when the flags
// actually carry it, else pass 0 (unused by the kernel when not creating). (codex-flagged latent UB.)
static int my_openat(int dirfd, const char *path, int flags, ...) {
    if (g_trace) trace_path("openat", path);
    if (path_is_hidden(path)) { errno = ENOENT; return -1; }
    const char *rp = redirect_path(path);
    if (rp != path) return orig_openat(AT_FDCWD, rp, O_RDONLY | O_CLOEXEC);
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return orig_openat(dirfd, path, flags, mode);
}
static int my_open(const char *path, int flags, ...) {
    if (g_trace) trace_path("open", path);
    if (path_is_hidden(path)) { errno = ENOENT; return -1; }
    const char *rp = redirect_path(path);
    if (rp != path) return orig_open(rp, O_RDONLY | O_CLOEXEC);
    mode_t mode = 0;
    if (flags & (O_CREAT | O_TMPFILE)) { va_list ap; va_start(ap, flags); mode = va_arg(ap, int); va_end(ap); }
    return orig_open(path, flags, mode);
}

// fopen is what libfp.so explicitly imports (readelf) to read /proc & /sys nodes; trace + redirect it.
using fopen_t = FILE *(*)(const char *, const char *);
static fopen_t orig_fopen = nullptr;
static FILE *my_fopen(const char *path, const char *mode) {
    if (g_trace) trace_path("fopen", path);
    if (path_is_hidden(path)) { errno = ENOENT; return nullptr; }
    const char *rp = redirect_path(path);
    if (rp != path) return orig_fopen(rp, mode);
    return orig_fopen(path, mode);
}

// access() is the most common root check (access("/system/xbin/su", F_OK)). Hide root paths.
using access_t = int (*)(const char *, int);
static access_t orig_access = nullptr;
static int my_access(const char *path, int mode) {
    if (path_is_hidden(path)) { errno = ENOENT; return -1; }
    return orig_access(path, mode);
}

// faccessat() is what bionic's access() actually calls on modern Android, and what a native root check
// (e.g. faccessat(AT_FDCWD, "/system/bin/su", F_OK, 0)) uses to BYPASS the access() hook. Cover it too,
// or a su-path probe via faccessat slips through and rootApps stays true. (faccessat2 raw-syscall variant
// is handled in my_syscall below.)
using faccessat_t = int (*)(int, const char *, int, int);
static faccessat_t orig_faccessat = nullptr;
static int my_faccessat(int dirfd, const char *path, int mode, int flags) {
    if (path_is_hidden(path)) { errno = ENOENT; return -1; }
    return orig_faccessat(dirfd, path, mode, flags);
}

// getauxval is imported by libfp.so — likely reads AT_HWCAP/AT_HWCAP2 (CPU feature bits, a hardware
// signal). Trace which keys it asks for; we don't spoof yet (would need coherent hwcaps per SoC).
// syscall tracer + redirect: libfp.so imports raw `syscall` (readelf), which BYPASSES our open/openat/
// fopen inline hooks. Intercept syscall(SYS_openat,...) so we both SEE and can redirect those reads.
#include <sys/syscall.h>
static const char *redirect_path(const char *path);   // defined with the openat hooks below
using syscall_t = long (*)(long, ...);
static syscall_t orig_syscall = nullptr;
static long my_syscall(long number, long a1, long a2, long a3, long a4, long a5, long a6) {
    if (number == __NR_openat) {
        const char *path = (const char *) a2;   // openat(dirfd, path, flags, mode)
        if (g_trace) trace_path("syscall.openat", path);
        if (path_is_hidden(path)) { errno = ENOENT; return -1; }
        const char *rp = redirect_path(path);
        if (rp != path)
            return orig_syscall(number, (long) AT_FDCWD, (long) rp, (long)(O_RDONLY | O_CLOEXEC), 0, a5, a6);
    }
    // Root checks done via RAW syscall bypass our libc-function hooks (access/faccessat/stat/statx). A
    // native root probe often does exactly this to dodge inline hooks. Cover the access + stat family:
    // faccessat(dirfd, path, mode, flags) / faccessat2(dirfd, path, mode, flags) — path is a2.
    else if (number == __NR_faccessat
#ifdef __NR_faccessat2
             || number == __NR_faccessat2
#endif
            ) {
        const char *path = (const char *) a2;
        if (g_trace) trace_path("syscall.faccessat", path);
        if (path_is_hidden(path)) { errno = ENOENT; return -1; }
    }
    // newfstatat(dirfd, path, statbuf, flags) / statx(dirfd, path, flags, mask, statbuf) — path is a2.
    else if (number == __NR_newfstatat
#ifdef __NR_statx
             || number == __NR_statx
#endif
            ) {
        const char *path = (const char *) a2;
        if (g_trace) trace_path("syscall.stat", path);
        if (path_is_hidden(path)) { errno = ENOENT; return -1; }
    }
    return orig_syscall(number, a1, a2, a3, a4, a5, a6);
}

// dlsym tracer: reveals exactly which native functions libfp.so resolves at runtime (sensors, mediadrm,
// egl, etc.) — the JNI/NDK signal surface the file/prop tracer can't see. Trace-only, no spoof.
using dlsym_t = void *(*)(void *, const char *);
static dlsym_t orig_dlsym = nullptr;
static void *my_dlsym(void *handle, const char *symbol) {
    if (g_trace && symbol &&
        (strstr(symbol, "Sensor") || strstr(symbol, "sensor") || strstr(symbol, "MediaDrm") ||
         strstr(symbol, "mediadrm") || strstr(symbol, "Camera") || strstr(symbol, "camera") ||
         strstr(symbol, "egl") || strstr(symbol, "gl") || strstr(symbol, "GL") ||
         strstr(symbol, "Choreographer") || strstr(symbol, "Configuration") ||
         strstr(symbol, "AAsset") || strstr(symbol, "getauxval") || strstr(symbol, "property")))
        __android_log_print(ANDROID_LOG_INFO, "SpecterTrace", "dlsym %s", symbol);
    return orig_dlsym(handle, symbol);
}

// True if an interface name is a VPN/tunnel iface a native detector looks for. Requires the tunnel prefix
// to be followed by a DIGIT (or end) so "tun0"/"wg0"/"ppp0" match but "wgbackup"/"pppmonitor" don't.
// Case-insensitive. The native counterpart of SpoofLogic.isTunnelIface (the Java NetworkInterface filter).
static bool is_tunnel_iface_native(const char *name) {
    if (!name) return false;
    char lo[32]; size_t i = 0;
    for (; name[i] && i < sizeof(lo) - 1; i++) lo[i] = (char) tolower((unsigned char) name[i]);
    lo[i] = 0;
    static const char *pre[] = {"tun", "ppp", "wg", "pptp", "ipsec", "l2tp"};
    for (auto p : pre) {
        size_t n = strlen(p);
        if (strncmp(lo, p, n) == 0) { char nxt = lo[n]; if (nxt == 0 || (nxt >= '0' && nxt <= '9')) return true; }
    }
    return false;
}

// getifaddrs() is the NATIVE interface-enumeration path (reads a netlink RTM_GETLINK socket — NOT /proc/net,
// which is SELinux-denied to apps anyway). An NDK VPN detector calls it directly, bypassing the Java
// NetworkInterface hook. We call the original, then UNLINK + FREE every tun/ppp/wg entry from the returned
// linked list before the caller sees it — so a scoped app enumerating interfaces natively finds no tunnel.
// Gated by g_hide_vpn.
//
// MEMORY SAFETY (verified against bionic libc/bionic/ifaddrs.cpp): each ifaddrs node is a SEPARATE malloc
// (an `ifaddrs_storage` per interface), and freeifaddrs() walks ifa_next calling free() on each. So freeing a
// node we've unlinked from the middle is CORRECT — it's a distinct allocation the caller's freeifaddrs would
// otherwise never reach (leaking it). This is EXACTLY what bionic's own resolve_or_remove_nameless_interfaces
// does (unlink via prev->ifa_next then free(addr)). No double-free: once unlinked, the caller's head-walk can
// never reach the freed node.
using getifaddrs_t = int (*)(struct ifaddrs **);
static getifaddrs_t orig_getifaddrs = nullptr;
static int my_getifaddrs(struct ifaddrs **ifap) {
    int rc = orig_getifaddrs(ifap);
    if (rc != 0 || !g_hide_vpn || !ifap || !*ifap) return rc;
    struct ifaddrs *head = *ifap, *prev = nullptr, *cur = head;
    while (cur) {
        if (is_tunnel_iface_native(cur->ifa_name)) {
            struct ifaddrs *drop = cur;
            if (prev) prev->ifa_next = cur->ifa_next;   // unlink from the middle
            else head = cur->ifa_next;                  // unlink the head
            cur = cur->ifa_next;
            free(drop);                                 // separate malloc per node (bionic) -> safe to free
            continue;
        }
        prev = cur;
        cur = cur->ifa_next;
    }
    *ifap = head;
    return rc;
}

#ifndef AT_HWCAP
#define AT_HWCAP 16
#endif
#ifndef AT_HWCAP2
#define AT_HWCAP2 26
#endif
using getauxval_t = unsigned long (*)(unsigned long);
static getauxval_t orig_getauxval = nullptr;
static bool g_spoof_hwcap = false;
static unsigned long my_getauxval(unsigned long type) {
    if (g_trace) __android_log_print(ANDROID_LOG_INFO, "SpecterTrace", "getauxval %lu", type);
    unsigned long v = orig_getauxval(type);
    // AT_HWCAP/AT_HWCAP2 are CPU feature bitmasks — a stable hardware signal. Clear a couple of
    // optional feature bits so the mask differs per identity but the CPU still looks valid (we only
    // ever REMOVE features, never invent ones the CPU can't have — that would be incoherent).
    if (g_spoof_hwcap && type == AT_HWCAP)  v &= ~((1UL << 21) | (1UL << 25));  // drop ASIMDDP, SVE-ish bits
    if (g_spoof_hwcap && type == AT_HWCAP2) v &= ~(1UL << 3);
    return v;
}

// ================= glGetString hook (GPU renderer — a hardware-characteristic signal) =================
// The GPU renderer/vendor string ("Adreno (TM) 640", "Qualcomm") is a strong, STABLE hardware signal a
// fingerprinter reads via libGLESv2's glGetString(GLenum) — a direct-JNI / native call the Xposed Java
// hook on android.opengl.GLES20 cannot reach in a native reader. Return the profile's coherent per-model
// value for GL_RENDERER/GL_VENDOR/GL_VERSION; pass everything else (extensions, etc.) straight through.
// Strings are stored in process-lifetime globals so the returned pointer stays valid after we return.
#define GL_VENDOR_ENUM   0x1F00
#define GL_RENDERER_ENUM 0x1F01
#define GL_VERSION_ENUM  0x1F02
#define GL_EXTENSIONS_ENUM   0x1F03
#define GL_NUM_EXTENSIONS    0x821D
static std::string g_gl_renderer, g_gl_vendor, g_gl_version;
static std::string g_gl_ext_joined;            // space-joined spoofed list (for legacy glGetString(GL_EXTENSIONS))
static bool g_gl_both_hooked = false;                  // true only if BOTH ext hooks are live
static std::vector<std::string> g_gl_ext_candidates;   // candidate ext list (built at specialize)
static void finalize_gl_extensions();         // forward decl: intersect candidates with the real list
using glGetString_t = const unsigned char *(*)(unsigned int);
static glGetString_t orig_glGetString = nullptr;
static const unsigned char *my_glGetString(unsigned int name) {
    if (g_trace) __android_log_print(ANDROID_LOG_INFO, "SpecterTrace", "glGetString 0x%x", name);
    if (name == GL_RENDERER_ENUM && !g_gl_renderer.empty())
        return reinterpret_cast<const unsigned char *>(g_gl_renderer.c_str());
    if (name == GL_VENDOR_ENUM && !g_gl_vendor.empty())
        return reinterpret_cast<const unsigned char *>(g_gl_vendor.c_str());
    if (name == GL_VERSION_ENUM && !g_gl_version.empty())
        return reinterpret_cast<const unsigned char *>(g_gl_version.c_str());
    // GL_EXTENSIONS via legacy glGetString: return the joined spoofed list (some readers use this path,
    // even though ES3 readers iterate glGetStringi). Finalize first so the list is intersected+built.
    if (name == GL_EXTENSIONS_ENUM && g_gl_both_hooked && !g_gl_ext_candidates.empty()) {
        finalize_gl_extensions();
        if (!g_gl_ext_joined.empty())
            return reinterpret_cast<const unsigned char *>(g_gl_ext_joined.c_str());
    }
    return orig_glGetString(name);
}

// ================= GL extension-list spoof (glGetStringi + glGetIntegerv(GL_NUM_EXTENSIONS)) =========
// PROVEN (2026-07-27 trace + two-rotation FPJS test): the Pro SDK's libfp.so reads the GPU EXTENSION list
// natively — it resolves glGetStringi + glGetIntegerv (never glGetString) — and that ~100-string list is
// a high-entropy, per-device signal that stays CONSTANT across our rotations (reads the real Adreno 640),
// so it anchors the visitorId even after the renderer string is spoofed. We make the list VARY per profile:
// from a real modern GLES-3.2 base pool, deterministically drop a fraction of the OPTIONAL extensions
// (seeded by android_id) and swap vendor-specific families to match the claimed GPU vendor. NO per-GPU
// database / cross-check is needed — a fraud SDK hashes the list, it does not verify it against the model
// (user call 2026-07-27). We keep only a well-formed SUBSET of REAL extension strings, so a dropped
// extension merely makes the reader take the same fallback path a real device lacking it would — safe.
static std::vector<std::string> g_gl_exts;     // per-profile spoofed extension list (process-lifetime)

// CORE: always keep (baseline every GLES-3.2 device exposes) — dropping these could look non-conformant.
static const char *GL_EXT_CORE[] = {
    "GL_OES_EGL_image", "GL_OES_EGL_image_external", "GL_OES_EGL_sync", "GL_OES_vertex_half_float",
    "GL_OES_framebuffer_object", "GL_OES_rgb8_rgba8", "GL_OES_texture_npot", "GL_OES_texture_3D",
    "GL_OES_texture_float", "GL_OES_texture_float_linear", "GL_OES_texture_half_float",
    "GL_OES_texture_half_float_linear", "GL_OES_element_index_uint", "GL_OES_depth24",
    "GL_OES_packed_depth_stencil", "GL_OES_depth_texture", "GL_OES_standard_derivatives",
    "GL_OES_vertex_array_object", "GL_OES_get_program_binary", "GL_OES_surfaceless_context",
    "GL_EXT_texture_filter_anisotropic", "GL_EXT_texture_format_BGRA8888", "GL_EXT_read_format_bgra",
    "GL_EXT_color_buffer_float", "GL_EXT_color_buffer_half_float", "GL_EXT_sRGB",
    "GL_EXT_copy_image", "GL_EXT_geometry_shader", "GL_EXT_tessellation_shader",
    "GL_EXT_texture_border_clamp", "GL_EXT_texture_buffer", "GL_EXT_texture_cube_map_array",
    "GL_EXT_draw_buffers_indexed", "GL_EXT_gpu_shader5", "GL_EXT_robustness", "GL_EXT_texture_norm16",
    "GL_EXT_discard_framebuffer", "GL_KHR_debug", "GL_KHR_texture_compression_astc_ldr",
    "GL_ANDROID_extension_pack_es31a", "GL_EXT_primitive_bounding_box",
};
// OPTIONAL: a subset is dropped per profile (variation source). Real strings from modern Adreno/Mali.
static const char *GL_EXT_OPTIONAL[] = {
    "GL_KHR_texture_compression_astc_hdr", "GL_OES_texture_compression_astc",
    "GL_EXT_texture_type_2_10_10_10_REV", "GL_EXT_texture_sRGB_decode",
    "GL_EXT_texture_format_sRGB_override", "GL_OES_texture_stencil8", "GL_EXT_shader_io_blocks",
    "GL_OES_shader_image_atomic", "GL_OES_sample_variables", "GL_EXT_EGL_image_external_wrap_modes",
    "GL_EXT_multisampled_render_to_texture", "GL_EXT_multisampled_render_to_texture2",
    "GL_OES_shader_multisample_interpolation", "GL_OES_texture_storage_multisample_2d_array",
    "GL_OES_sample_shading", "GL_KHR_blend_equation_advanced", "GL_KHR_blend_equation_advanced_coherent",
    "GL_EXT_YUV_target", "GL_EXT_sRGB_write_control", "GL_OVR_multiview", "GL_OVR_multiview2",
    "GL_EXT_texture_sRGB_R8", "GL_KHR_no_error", "GL_EXT_debug_marker", "GL_EXT_debug_label",
    "GL_OES_EGL_image_external_essl3", "GL_EXT_buffer_storage", "GL_EXT_external_buffer",
    "GL_EXT_blit_framebuffer_params", "GL_EXT_clip_cull_distance", "GL_EXT_protected_textures",
    "GL_EXT_shader_non_constant_global_initializers", "GL_EXT_memory_object", "GL_EXT_memory_object_fd",
    "GL_EXT_EGL_image_array", "GL_NV_shader_noperspective_interpolation",
    "GL_KHR_robust_buffer_access_behavior", "GL_EXT_EGL_image_storage", "GL_EXT_blend_func_extended",
    "GL_EXT_clip_control", "GL_OES_texture_view", "GL_EXT_shader_framebuffer_fetch",
    "GL_EXT_texture_mirror_clamp_to_edge", "GL_EXT_shader_group_vote", "GL_OES_shader_io_blocks",
    "GL_EXT_float_blend", "GL_OES_copy_image", "GL_EXT_draw_elements_base_vertex",
};
// Qualcomm/Adreno-family markers — kept only when the claimed vendor is Qualcomm.
static const char *GL_EXT_QCOM[] = {
    "GL_AMD_compressed_ATC_texture", "GL_QCOM_alpha_test", "GL_QCOM_tiled_rendering",
    "GL_QCOM_texture_foveated", "GL_QCOM_texture_foveated_subsampled_layout",
    "GL_QCOM_shader_framebuffer_fetch_noncoherent", "GL_QCOM_shader_framebuffer_fetch_rate",
    "GL_QCOM_motion_estimation", "GL_QCOM_validate_shader_binary", "GL_QCOM_YUV_texture_gather",
};
// ARM/Mali-family markers — kept only when the claimed vendor is ARM.
static const char *GL_EXT_ARM[] = {
    "GL_ARM_shader_framebuffer_fetch", "GL_ARM_shader_framebuffer_fetch_depth_stencil",
    "GL_ARM_mali_shader_binary", "GL_ARM_rgba8", "GL_ARM_mali_program_binary",
    "GL_EXT_shader_pixel_local_storage", "GL_EXT_shader_pixel_local_storage2",
    "GL_OES_depth_texture_cube_map", "GL_EXT_disjoint_timer_query",
};

// Small deterministic PRNG (splitmix64) seeded from the profile — same profile => same list every launch.
static uint64_t g_gl_rng = 0;
static uint64_t gl_next() {
    uint64_t z = (g_gl_rng += 0x9e3779b97f4a7c15ULL);
    z = (z ^ (z >> 30)) * 0xbf58476d1ce4e5b9ULL;
    z = (z ^ (z >> 27)) * 0x94d049bb133111ebULL;
    return z ^ (z >> 31);
}

using glGetStringi_t = const unsigned char *(*)(unsigned int, unsigned int);
using glGetIntegerv_t = void (*)(unsigned int, int *);
static glGetStringi_t orig_glGetStringi = nullptr;
static glGetIntegerv_t orig_glGetIntegerv = nullptr;

static std::atomic<bool> g_gl_ext_final(false);        // finalized (intersected with real) yet?
static std::mutex g_gl_ext_mtx;

// Build the per-profile CANDIDATE extension list from android_id + claimed vendor (specialize-time; the
// real driver isn't up yet, so we can't intersect here — that happens lazily in finalize_gl_extensions).
static void build_gl_extensions(const std::string &seed_src, const std::string &vendor) {
    if (seed_src.empty()) return;      // no seed => leave real list (safer than a constant fake)
    uint64_t h = 1469598103934665603ULL;                      // FNV-1a of the seed
    for (unsigned char c : seed_src) { h ^= c; h *= 1099511628211ULL; }
    g_gl_rng = h ? h : 0x1234567890abcdefULL;

    std::set<std::string> seen;                               // dedup: no extension appears twice
    auto add = [&](const char *e) { if (seen.insert(e).second) g_gl_ext_candidates.emplace_back(e); };

    for (auto e : GL_EXT_CORE) add(e);                        // always present
    for (auto e : GL_EXT_OPTIONAL) if ((gl_next() % 100) < 70) add(e);   // ~70% each (varies membership)
    // Vendor-family: add ONLY when the claimed vendor is KNOWN, so we never contradict the renderer
    // string. Unknown vendor (empty / PowerVR / Google / etc.) => no vendor-specific markers at all.
    bool arm  = vendor.find("ARM") != std::string::npos || vendor.find("Mali") != std::string::npos;
    bool qcom = vendor.find("Qualcomm") != std::string::npos || vendor.find("Adreno") != std::string::npos;
    const char **fam = arm ? GL_EXT_ARM : (qcom ? GL_EXT_QCOM : nullptr);
    size_t famN = arm ? (sizeof(GL_EXT_ARM) / sizeof(*GL_EXT_ARM))
                      : (qcom ? (sizeof(GL_EXT_QCOM) / sizeof(*GL_EXT_QCOM)) : 0);
    for (size_t i = 0; i < famN; i++) if ((gl_next() % 100) < 80) add(fam[i]);
}

// Finalize on the FIRST real GL query (driver is live now): keep only candidates the REAL driver actually
// supports — a strict subset, so a reader can never make us advertise an unsupported feature it then calls
// (the crash risk codex flagged) — then deterministically shuffle. Runs once, guarded. If the real list
// can't be read, keep the candidates (all are real, widely-supported extension strings). Never empty.
static void finalize_gl_extensions() {
    if (g_gl_ext_final.load(std::memory_order_acquire)) return;
    std::lock_guard<std::mutex> lk(g_gl_ext_mtx);
    if (g_gl_ext_final.load(std::memory_order_relaxed)) return;

    std::set<std::string> real;
    if (orig_glGetStringi && orig_glGetIntegerv) {
        int n = 0;
        orig_glGetIntegerv(GL_NUM_EXTENSIONS, &n);
        for (int i = 0; i < n && i < 4096; i++) {
            const unsigned char *s = orig_glGetStringi(GL_EXTENSIONS_ENUM, (unsigned) i);
            if (s) real.insert(reinterpret_cast<const char *>(s));
        }
    }
    g_gl_exts.clear();
    for (auto &e : g_gl_ext_candidates)
        if (real.empty() || real.count(e)) g_gl_exts.push_back(e);   // intersect (keep all if unreadable)
    if (g_gl_exts.empty()) g_gl_exts = g_gl_ext_candidates;          // never end up empty

    for (size_t i = g_gl_exts.size(); i > 1; i--) {                  // deterministic shuffle
        size_t j = gl_next() % i;
        std::swap(g_gl_exts[i - 1], g_gl_exts[j]);
    }
    g_gl_ext_joined.clear();
    for (size_t i = 0; i < g_gl_exts.size(); i++) {
        if (i) g_gl_ext_joined += ' ';
        g_gl_ext_joined += g_gl_exts[i];
    }
    g_gl_ext_final.store(true, std::memory_order_release);
}

// glGetStringi(GL_EXTENSIONS, i) — the ES3 indexed read libfp uses. Return our i-th spoofed extension.
// An out-of-range GL_EXTENSIONS index returns nullptr (NOT the real list), so a probe of index==count
// can't leak a real extension past our spoofed count. Only spoofs when BOTH hooks are live, so the count
// (glGetIntegerv) and the entries (glGetStringi) can never desync into a half-fake list.
static const unsigned char *my_glGetStringi(unsigned int name, unsigned int index) {
    if (g_trace) __android_log_print(ANDROID_LOG_INFO, "SpecterTrace", "glGetStringi 0x%x %u", name, index);
    if (name == GL_EXTENSIONS_ENUM && g_gl_both_hooked && !g_gl_ext_candidates.empty()) {
        finalize_gl_extensions();
        if (index < g_gl_exts.size())
            return reinterpret_cast<const unsigned char *>(g_gl_exts[index].c_str());
        return nullptr;   // past our (spoofed) count — do NOT fall through to the real driver
    }
    if (!orig_glGetStringi) return nullptr;
    return orig_glGetStringi(name, index);
}

// glGetIntegerv(GL_NUM_EXTENSIONS, *) — the count the reader uses to bound its glGetStringi loop. Must
// match our list size (finalize first so the intersection is applied) or the reader walks off the end /
// stops short. Only this one pname is intercepted; everything else (limits, etc.) passes straight through.
static void my_glGetIntegerv(unsigned int pname, int *data) {
    if (pname == GL_NUM_EXTENSIONS && data && g_gl_both_hooked && !g_gl_ext_candidates.empty()) {
        finalize_gl_extensions();
        if (g_trace) __android_log_print(ANDROID_LOG_INFO, "SpecterTrace", "glGetIntegerv NUM_EXT->%zu", g_gl_exts.size());
        *data = (int) g_gl_exts.size();
        return;
    }
    // orig null only if the trampoline failed (rare). Can't safely synthesize other pnames; do nothing
    // rather than deref null — the caller sees an unchanged buffer, which never crashes.
    if (!orig_glGetIntegerv) return;
    orig_glGetIntegerv(pname, data);
}

// ================= ASensor_getName / ASensor_getVendor (native sensor list) =================
// The tracer PROVED libfp reads the sensor list via libandroid's ASensorManager/ASensor NDK — a
// direct-JNI path the Java SensorManager hook cannot reach. Rather than fabricate ASensor structs
// (crash-risky), we RELABEL: hook the two accessors so each real sensor reports the profile's
// per-model name/vendor. Each distinct ASensor* is assigned the next (name,vendor) pair from the
// profile on first sight and remembered, so the mapping is stable within a process (a fingerprinter
// re-reading the same sensor gets the same spoofed label). We do NOT touch ASensorManager_getSensorList,
// so the COUNT and every real ASensor pointer stay valid — no allocation, no struct forgery, no crash.
static std::vector<std::pair<std::string, std::string>> g_sensor_labels;  // (name, vendor) from profile
static std::map<const void *, size_t> g_sensor_assign;    // ASensor* -> index into g_sensor_labels
static std::mutex g_sensor_mtx;

static const size_t SENSOR_REAL = (size_t) -1;   // sentinel: this ASensor* keeps its REAL label
static size_t sensor_index_for(const void *sensor) {
    // Assign each distinct ASensor* the NEXT label slot on first sight, stable thereafter — but only up
    // to g_sensor_labels.size() DISTINCT sensors. A real device has ~35 sensors while a profile lists ~5-7;
    // the old code did (count % size), which round-robined the few labels across all 35 sensors and made a
    // native reader see e.g. SEVEN identical "LSM6DSR Accelerometer" entries — an impossible multiset and a
    // hard tell. Instead we relabel only the first N (N = profile label count) and leave the rest REAL
    // (mostly composite/uncalibrated sensors with generic Android names), so the spoofed set has no
    // duplicates and the overall list stays a realistic size. (Fuller per-model sensor datasets, so ALL
    // sensors can be spoofed without duplication, are the eventual upgrade — see IDEAS.)
    std::lock_guard<std::mutex> lk(g_sensor_mtx);
    auto it = g_sensor_assign.find(sensor);
    if (it != g_sensor_assign.end()) return it->second;
    size_t idx = g_sensor_assign.size() < g_sensor_labels.size()
                     ? g_sensor_assign.size() : SENSOR_REAL;
    g_sensor_assign[sensor] = idx;
    return idx;
}

using ASensor_getName_t = const char *(*)(const void *);
static ASensor_getName_t orig_ASensor_getName = nullptr;
static const char *my_ASensor_getName(const void *sensor) {
    if (g_sensor_labels.empty() || sensor == nullptr) return orig_ASensor_getName(sensor);
    size_t idx = sensor_index_for(sensor);
    if (idx == SENSOR_REAL) return orig_ASensor_getName(sensor);   // overflow sensor — keep real name
    return g_sensor_labels[idx].first.c_str();
}

using ASensor_getVendor_t = const char *(*)(const void *);
static ASensor_getVendor_t orig_ASensor_getVendor = nullptr;
static const char *my_ASensor_getVendor(const void *sensor) {
    if (g_sensor_labels.empty() || sensor == nullptr) return orig_ASensor_getVendor(sensor);
    size_t idx = sensor_index_for(sensor);
    if (idx == SENSOR_REAL) return orig_ASensor_getVendor(sensor);  // overflow sensor — keep real vendor
    return g_sensor_labels[idx].second.c_str();
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
        // ro.build.version.sdk / ro.product.first_api_level leak the REAL device on the NATIVE path
        // (FingerprintJS reads both — proven via the trace). They can't go in the always-on map: ART/libc
        // read them during process init and spoofing then SIGSEGVs the zygote. So spoof them LATE — only
        // after g_props_ready flips (a detached ~1s timer below), which is long after init but well before
        // any user-triggered fingerprint read.
        // GATED on os_version_spoof_enabled: because these two ONLY spoof late, there's a startup window
        // where the native path returns the REAL host value. If the profile's claimed OS != the host's, that
        // window leaks a claimed-vs-host contradiction — so the Java layer (IdentityService) sets this flag
        // to "0" whenever the claimed sdk/first_api don't EXACTLY match the host, and we then leave these two
        // reporting the real host on BOTH paths (never spoofed). One flag, read by both native + HookEntry,
        // so the layers can never disagree. "1" (or absent, for older profiles) = spoof as before.
        auto osv = profile.find("os_version_spoof_enabled");
        bool spoof_os_version = (osv == profile.end()) || osv->second != "0";
        auto sdk = profile.find("build_sdk");
        if (spoof_os_version && sdk != profile.end() && !sdk->second.empty()) {
            // ro.build.version.sdk = the CURRENT OS the profile claims (build_sdk). The flag guarantees this
            // == the real host sdk, so the deferred-window read (real) and the post-arm read (claimed) agree.
            g_prop_spoof_late["ro.build.version.sdk"] = sdk->second;
            // ro.product.first_api_level = the device's LAUNCH API. It ALSO leaks real in the deferred window,
            // and the profile's claimed build_first_api may not equal the real host launch-API (we only gate
            // the flag on the SDK matching, not first_api — see IdentityService.osVersionMatchesHost). So pin
            // it to the REAL HOST value instead of the profile's claim: read it here via the standard libc
            // getter (our hook isn't installed yet at this point in postAppSpecialize, so this returns the
            // true value), guaranteeing the pre-arm (real) and post-arm (this same real value) reads agree.
            char host_fa[PROP_VALUE_MAX] = {0};
            if (__system_property_get("ro.product.first_api_level", host_fa) > 0 && host_fa[0])
                g_prop_spoof_late["ro.product.first_api_level"] = host_fa;
            // If the host value is unreadable, DON'T fall back to the profile's claim: that would make the
            // pre-arm window leak the real host while the post-arm read returns a fabricated value — the exact
            // contradiction we're avoiding. Omit the entry so first_api reports the real host on BOTH paths
            // (coherent by definition). (codex-flagged.)
        }
        // Verified-boot / lock-state props (native path). A rooted device leaks unlocked/orange/test-keys
        // here — a heavy root flag independent of the model spoof. OEM-agnostic device STATE (a stock
        // locked consumer phone reads the same for any model). Routed through the LATE map, not the
        // always-on one: some (verifiedbootstate, veritymode) are read during early init like SDK_INT, so
        // spoofing them at init risks the zygote SIGSEGV. The late map applies them after g_props_ready
        // (~3s), long after init but well before any user-triggered fingerprint read. Keep in lockstep
        // with HookEntry.STATIC_PROPS (Java path).
        g_prop_spoof_late["ro.boot.verifiedbootstate"] = "green";
        g_prop_spoof_late["ro.boot.vbmeta.device_state"] = "locked";
        g_prop_spoof_late["ro.boot.flash.locked"] = "1";
        g_prop_spoof_late["ro.boot.veritymode"] = "enforcing";
        g_prop_spoof_late["ro.debuggable"] = "0";
        g_prop_spoof_late["ro.secure"] = "1";
        // build.tags/build.type DERIVED from the profile fingerprint so they never contradict it; warranty
        // props ONLY for Samsung (their presence signals a Galaxy — a cross-OEM leak on a Pixel/LG). Keep
        // in lockstep with the Java derivation in HookEntry.hookSystemProperties.
        // Fingerprint tail is "...:<type>/<tags>" e.g. ":user/release-keys". tags = after the last '/'.
        auto fpit = profile.find("build_fingerprint");
        if (fpit != profile.end()) {
            const std::string &fp = fpit->second;
            size_t slash = fp.rfind('/');
            if (slash != std::string::npos && slash + 1 < fp.size())
                g_prop_spoof_late["ro.build.tags"] = fp.substr(slash + 1);   // "release-keys"
            if (fp.find(":user/") != std::string::npos)      g_prop_spoof_late["ro.build.type"] = "user";
            else if (fp.find(":userdebug/") != std::string::npos) g_prop_spoof_late["ro.build.type"] = "userdebug";
        }
        auto mfrit = profile.find("build_manufacturer");
        if (mfrit != profile.end()) {
            std::string mfr = mfrit->second;
            for (auto &c : mfr) c = (char) tolower((unsigned char) c);
            if (mfr == "samsung") {
                g_prop_spoof_late["ro.boot.warranty_bit"] = "0";
                g_prop_spoof_late["ro.warranty_bit"] = "0";
            }
        }
        auto ep = profile.find("factory_reset_epoch");
        if (ep != profile.end()) g_reset_epoch = strtol(ep->second.c_str(), nullptr, 10);

        // GPU renderer/vendor/version — the profile's coherent per-model values (GOAL 1.3). Read here;
        // the glGetString inline hook returns them. GL_VERSION is shaped like a real driver string.
        auto gr = profile.find("hw_gpu_renderer");
        if (gr != profile.end()) g_gl_renderer = gr->second;
        auto gv = profile.find("hw_gpu_vendor");
        if (gv != profile.end()) g_gl_vendor = gv->second;
        // A non-Qualcomm GPU vendor (ARM/Mali on Exynos/Tensor) means this device has NO Adreno kgsl node.
        // Hide the whole /sys/class/kgsl tree (ENOENT) so the host's real Adreno number can't leak under a
        // Mali GL_RENDERER, and so the node's mere existence doesn't contradict the claimed ARM GPU.
        g_hide_kgsl = (gv != profile.end() && gv->second != "Qualcomm" && !gv->second.empty());
        auto gl = profile.find("hw_gles_version");
        if (gl != profile.end() && !gl->second.empty())
            g_gl_version = "OpenGL ES " + gl->second + " V@0.0";
        // Per-profile GL extension list (varies the high-entropy native GPU signal that anchored the
        // FPJS visitorId). Seed from android_id; vendor-match the family markers to g_gl_vendor.
        {
            auto aidg = profile.find("android_id");
            if (aidg != profile.end())
                build_gl_extensions(aidg->second, g_gl_vendor);
        }

        // Sensor labels — hw_sensors is "name|vendor|type" rows joined by ';' (the native reads only
        // name+vendor; type is ignored here). Populated for the ASensor_getName/getVendor relabel hooks.
        auto se = profile.find("hw_sensors");
        if (se != profile.end() && !se->second.empty()) {
            const std::string &raw = se->second;
            size_t start = 0;
            while (start < raw.size()) {
                size_t semi = raw.find(';', start);
                std::string row = raw.substr(start, semi == std::string::npos ? std::string::npos : semi - start);
                size_t b1 = row.find('|');
                if (b1 != std::string::npos) {
                    std::string name = row.substr(0, b1);
                    size_t b2 = row.find('|', b1 + 1);
                    std::string vendor = row.substr(b1 + 1, b2 == std::string::npos ? std::string::npos : b2 - (b1 + 1));
                    if (!name.empty()) g_sensor_labels.emplace_back(name, vendor);
                }
                if (semi == std::string::npos) break;
                start = semi + 1;
            }
            // A real phone exposes ~30-40 sensors, but a profile lists only its ~5-7 PHYSICAL ones. If we
            // relabel only those, the ~25 real composite/derived sensors either leak (real path) or, if we
            // round-robined the few labels over all of them, produce an impossible multiset (e.g. 7 identical
            // accelerometers — a hard tell). Instead DERIVE the standard Android composite/uncalibrated
            // sensors from the physical ones, reusing each physical sensor's own chip name + vendor, so the
            // whole native list is coherently spoofed at a realistic size with NO duplicates. Every modern
            // device with an accel/gyro/mag exposes these exact derived sensors.
            size_t physical = g_sensor_labels.size();
            std::vector<std::pair<std::string, std::string>> derived;
            // Whole-set capability flags: a Rotation Vector fuses accel+gyro+mag, which live in SEPARATE
            // physical rows, so it can't be decided from one name — track presence across ALL physical
            // sensors and add it once after the loop (the per-name check never fired — codex + reviewer).
            bool set_accel = false, set_gyro = false, set_mag = false;
            std::string fusion_vendor;
            for (size_t i = 0; i < physical; i++) {
                const std::string &nm = g_sensor_labels[i].first;
                const std::string &vd = g_sensor_labels[i].second;
                // Case-INSENSITIVE keyword match: OEM sensor names differ in case — Samsung uses
                // "LSM6DSO Acceleration Sensor" but Pixels use "BMI160 accelerometer" (lowercase). A
                // case-sensitive find("Accel") matched Samsung but SILENTLY missed every Pixel, so the
                // whole google family derived ZERO composite sensors and shipped only ~6 physical ones —
                // a hard emulator/device-farm tell. Lowercase both sides so both brands derive the full set.
                std::string low = nm;
                std::transform(low.begin(), low.end(), low.begin(),
                               [](unsigned char c) { return std::tolower(c); });
                auto has = [&](const char *kw) { return low.find(kw) != std::string::npos; };
                if (has("accel")) {
                    derived.emplace_back(nm + "-Uncalibrated", vd);
                    derived.emplace_back("Gravity Sensor", vd);
                    derived.emplace_back("Linear Acceleration Sensor", vd);
                    derived.emplace_back("Significant Motion Detector", vd);
                    derived.emplace_back("Step Detector", vd);
                    derived.emplace_back("Step Counter", vd);
                    // Standard AOSP composite/gesture virtual sensors every modern accel-bearing phone
                    // exposes — real devices list ~30-40 total; without these the count sat at ~6-16 (a tell).
                    derived.emplace_back("Tilt Detector", vd);
                    derived.emplace_back("Pickup Gesture", vd);
                    derived.emplace_back("Motion Detect", vd);
                    derived.emplace_back("Stationary Detect", vd);
                    derived.emplace_back("Device Orientation", vd);
                    derived.emplace_back("Wake Up Motion", vd);
                    derived.emplace_back("Double Tap", vd);
                }
                if (has("gyro")) {
                    derived.emplace_back(nm + "-Uncalibrated", vd);
                    derived.emplace_back("Game Rotation Vector Sensor", vd);
                }
                if (has("magneto") || has("magnetic")) {
                    derived.emplace_back(nm + "-Uncalibrated", vd);
                    derived.emplace_back("Geomagnetic Rotation Vector Sensor", vd);
                }
                if (has("accel")) {
                    derived.emplace_back("Orientation Sensor", vd);
                    set_accel = true;
                    if (fusion_vendor.empty()) fusion_vendor = vd;
                }
                if (has("gyro")) set_gyro = true;
                if (has("magneto") || has("magnetic")) set_mag = true;
            }
            // TYPE_ROTATION_VECTOR fuses accel + gyro + mag (all three) — the gyro-only and accel+mag
            // variants are already emitted above as Game / Geomagnetic Rotation Vector. Added once across the
            // whole set, not per name.
            if (set_accel && set_gyro && set_mag)
                derived.emplace_back("Rotation Vector Sensor", fusion_vendor);
            // Append derived sensors, skipping any name already present (dedupe: no two identical entries).
            std::set<std::string> present;
            for (auto &s : g_sensor_labels) present.insert(s.first);
            for (auto &d : derived)
                if (present.insert(d.first).second) g_sensor_labels.push_back(d);
        }

        auto tr = profile.find("trace");
        if (tr != profile.end() && tr->second == "1") g_trace = true;
        // Hide root by default for every Specter target (a rooted-device flag is a strong linking signal).
        g_hide_root = true;
        auto hr = profile.find("hide_root");
        if (hr != profile.end() && hr->second == "0") g_hide_root = false;
        // Hide VPN/proxy by default (the fleet routes through one; a tunnel is a risk signal). Drives the
        // native getifaddrs filter — the counterpart of the Java NetworkInterface hook. Gate: hide_vpn=="0".
        g_hide_vpn = true;
        auto hv = profile.find("hide_vpn");
        if (hv != profile.end() && hv->second == "0") g_hide_vpn = false;
        // A writable dir we own, for temp files (cleaned maps etc.).
        if (!pkg.empty()) {
            g_files_dir = "/data/data/" + pkg + "/files";
            mkdir(g_files_dir.c_str(), 0700);
        }

        // Hide Magisk's bind-mounts from /proc/mounts + /proc/self/mountinfo (a strong root signal that
        // survives su-path hiding). Filtered copies built once here; redirect_path swaps the read.
        if (g_hide_root && !g_files_dir.empty()) {
            build_filtered_mounts("/proc/mounts", "mounts", g_mounts_path);
            build_filtered_mounts("/proc/self/mountinfo", "mountinfo", g_mountinfo_path);
            // SELinux enforce -> "1" (a Magisk device's permissive/patched SELinux reads as tampered).
            build_selinux_spoof();
        }

        // boot_id: a per-boot UUID FPJS reads (tracer-proven). Derive a stable per-identity UUID from
        // android_id and redirect the file. hwcap: drop-only feature-bit tweak so the CPU mask varies.
        auto aid = profile.find("android_id");
        if (aid != profile.end() && aid->second.size() >= 16 && !pkg.empty()) {
            const std::string &a = aid->second;
            char uuid[37];
            // Format the 16 hex chars of android_id (+ a fixed tail) into a UUID shape. Deterministic.
            snprintf(uuid, sizeof(uuid), "%.8s-%.4s-%.4s-%.4s-%.4s%.8s",
                     a.c_str(), a.c_str()+8, a.c_str()+12, a.c_str(), a.c_str()+4, a.c_str()+8);
            std::string dir = "/data/data/" + pkg + "/files";
            mkdir(dir.c_str(), 0700);
            std::string bpath = dir + "/.specter_bid";
            int bf = open(bpath.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
            if (bf >= 0) {
                std::string content = std::string(uuid) + "\n";
                if (write(bf, content.data(), content.size()) == (ssize_t) content.size())
                    g_bootid_path = bpath;
                close(bf);
            }
            g_spoof_hwcap = true;
        }

        // /proc/cpuinfo spoof (GOAL 1.3): profile carries `proc_cpuinfo` with \n and \t escaped (the flat
        // JSON is single-line). Decode it, write it to the app's own files dir (readable by the app uid),
        // and point the open/openat redirect at it.
        auto ci = profile.find("proc_cpuinfo");
        if (ci != profile.end() && !ci->second.empty() && !pkg.empty()) {
            std::string decoded;
            const std::string &e = ci->second;
            for (size_t i = 0; i < e.size(); i++) {
                if (e[i] == '\\' && i + 1 < e.size()) {
                    char n = e[++i];
                    decoded += (n == 'n') ? '\n' : (n == 't') ? '\t' : n;
                } else decoded += e[i];
            }
            std::string path = "/data/data/" + pkg + "/files/.specter_ci";
            // Best-effort: files/ may not exist yet; create it, then the file.
            std::string dir = "/data/data/" + pkg + "/files";
            mkdir(dir.c_str(), 0700);
            int f = open(path.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
            if (f >= 0) {
                if (write(f, decoded.data(), decoded.size()) == (ssize_t) decoded.size())
                    g_cpuinfo_path = path;
                close(f);
            }
        }

        // Per-SoC /sys hardware signals (cpu_capacity vector, KGSL gpu_model, cpu present range). Each
        // real sysfs node gets a spoof file under the app's files dir, and its exact path is redirected.
        // cpu_capacity is per-core: "261 261 ... 1024" -> one file per core cpuN/cpu_capacity.
        // Gated: the app writes "spoof_sysfs":"0" into the profile only when the user toggles it OFF.
        bool sysfs_off = profile.count("spoof_sysfs") && profile.at("spoof_sysfs") == "0";

        // /proc/version — the kernel banner. The Java os.version property hook does NOT cover a direct
        // /proc/version read (a known gap vs byedentity), so an app reading the file gets the REAL
        // kernel. Rebuild the AOSP banner from build_kernel_version and redirect the read. Gated with
        // spoof_sysfs (same hardware/kernel class of signal).
        if (!pkg.empty() && !sysfs_off) {
            auto kv = profile.find("build_kernel_version");
            if (kv != profile.end() && !kv->second.empty()) {
                // Match the real Pixel-4 banner shape; only the kernel-version token is identity-bearing.
                std::string banner = "Linux version " + kv->second +
                    " (android-build@abfarm) (Android clang version 11.0.1) "
                    "#1 SMP PREEMPT Wed Jun 30 09:33:45 UTC 2021\n";
                std::string dir = "/data/data/" + pkg + "/files";
                mkdir(dir.c_str(), 0700);
                std::string sp = dir + "/.specter_procver";
                int f = open(sp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
                if (f >= 0) {
                    if (write(f, banner.data(), banner.size()) == (ssize_t) banner.size())
                        g_sys_redirect["/proc/version"] = sp;
                    close(f);
                }
            }
        }
        if (!pkg.empty() && !sysfs_off) {
            std::string dir = "/data/data/" + pkg + "/files";
            mkdir(dir.c_str(), 0700);
            auto write_spoof = [&](const std::string &tag, const std::string &content,
                                   const std::string &sysPath) {
                std::string sp = dir + "/.specter_" + tag;
                int f = open(sp.c_str(), O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
                if (f < 0) return;
                // Loop over write() to handle EINTR + short writes — a single write() that returns fewer bytes
                // (or is interrupted) would else leave the redirect UNregistered and silently leak the real file
                // (codex gauntlet). Only register the redirect once the FULL content is on disk.
                size_t off = 0; bool ok = true;
                while (off < content.size()) {
                    ssize_t w = write(f, content.data() + off, content.size() - off);
                    if (w < 0) { if (errno == EINTR) continue; ok = false; break; }
                    if (w == 0) { ok = false; break; }
                    off += (size_t) w;
                }
                close(f);
                if (ok) g_sys_redirect[sysPath] = sp;
            };
            auto cap = profile.find("cpu_capacity");
            if (cap != profile.end() && !cap->second.empty()) {
                // split on spaces; core i -> /sys/devices/system/cpu/cpu<i>/cpu_capacity
                const std::string &v = cap->second;
                size_t i = 0, core = 0;
                while (i < v.size()) {
                    size_t j = v.find(' ', i);
                    if (j == std::string::npos) j = v.size();
                    std::string one = v.substr(i, j - i);
                    if (!one.empty())
                        write_spoof("cap" + std::to_string(core), one + "\n",
                                    "/sys/devices/system/cpu/cpu" + std::to_string(core) + "/cpu_capacity");
                    core++;
                    i = j + 1;
                }
            }
            // Per-core CPU max/min frequency (kHz) -> /sys/.../cpu<i>/cpufreq/cpuinfo_{max,min}_freq AND the
            // scaling_{max,min}_freq siblings some readers use. These leak the REAL SoC's core-frequency
            // signature otherwise (proven: a Pixel 4's SD855 1+3+4 layout read while the profile claimed an
            // LG G7 SD845 4+4 — the coherence tell that flagged an account). cpuinfo_* is the immutable HW
            // ceiling a fingerprinter reads; scaling_* is the current policy limit (usually == cpuinfo_*), so
            // redirect BOTH. Same per-core space-split as cpu_capacity.
            auto redirect_freq = [&](const std::string &key, std::initializer_list<const char *> leaves) {
                auto it = profile.find(key);
                if (it == profile.end() || it->second.empty()) return;
                const std::string &v = it->second;
                size_t i = 0, core = 0;
                while (i < v.size()) {
                    size_t j = v.find(' ', i);
                    if (j == std::string::npos) j = v.size();
                    std::string one = v.substr(i, j - i);
                    if (!one.empty()) {
                        std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(core) + "/cpufreq/";
                        int li = 0;
                        for (const char *leaf : leaves)
                            write_spoof(key + std::to_string(core) + "_" + std::to_string(li++),
                                        one + "\n", base + leaf);
                    }
                    core++;
                    i = j + 1;
                }
            };
            redirect_freq("cpu_max_freq", {"cpuinfo_max_freq", "scaling_max_freq"});
            redirect_freq("cpu_min_freq", {"cpuinfo_min_freq", "scaling_min_freq"});

            // Per-core CPU TOPOLOGY -> /sys/.../cpu<i>/topology/{physical_package_id,core_siblings_list,
            // cluster_cpus_list} + the top-level online/possible/kernel_max. These leak the real SoC's CLUSTER
            // grouping otherwise (proven: the Pixel 4 SD855 reads pkg 0/1/2 with sibling ranges 0-3,4-6,7 — a
            // 1+3+4 THREE-cluster layout — while a claimed LG G7 SD845 is 4+4 TWO clusters). The cluster
            // structure is fully determined by the cpu_capacity vector: a run of equal capacities = one cluster.
            // So we derive it from cpu_capacity (no extra profile field) and write coherent topology files.
            if (cap != profile.end() && !cap->second.empty()) {
                std::vector<std::string> caps;
                { size_t i = 0; const std::string &v = cap->second;
                  while (i < v.size()) { size_t j = v.find(' ', i); if (j == std::string::npos) j = v.size();
                      std::string one = v.substr(i, j - i); if (!one.empty()) caps.push_back(one); i = j + 1; } }
                int n = (int) caps.size();
                if (n > 0) {
                    // Assign each core a cluster id (increment when the capacity value changes from the previous
                    // core), and record each cluster's [start,end] core range for the sibling list.
                    std::vector<int> pkg(n);
                    std::vector<std::pair<int,int>> clusterRange;   // per cluster: first..last core
                    int cid = 0;
                    for (int c = 0; c < n; c++) {
                        if (c > 0 && caps[c] != caps[c-1]) cid++;
                        pkg[c] = cid;
                        if ((int) clusterRange.size() <= cid) clusterRange.push_back({c, c});
                        else clusterRange[cid].second = c;
                    }
                    auto rangeStr = [](int a, int b) {
                        return a == b ? std::to_string(a) : std::to_string(a) + "-" + std::to_string(b);
                    };
                    std::string full = "0-" + std::to_string(n - 1);   // all cores (used for L3 sharing + online/possible)

                    // Parse the per-core cache-size vectors (KB) for the FULL cache-tree spoof below. Each is
                    // space-separated per core, same length as caps (or empty -> skip that level).
                    auto splitVec = [&](const char *key) {
                        std::vector<std::string> out;
                        auto it = profile.find(key);
                        if (it == profile.end()) return out;
                        const std::string &v = it->second; size_t i = 0;
                        while (i < v.size()) { size_t j = v.find(' ', i); if (j == std::string::npos) j = v.size();
                            std::string one = v.substr(i, j - i); if (!one.empty()) out.push_back(one); i = j + 1; }
                        return out;
                    };
                    std::vector<std::string> l1i = splitVec("cpu_l1i"), l1d = splitVec("cpu_l1d"), l2 = splitVec("cpu_l2");
                    std::string l3kb; { auto it = profile.find("cpu_l3"); if (it != profile.end()) l3kb = it->second; }

                    for (int c = 0; c < n; c++) {
                        std::string base = "/sys/devices/system/cpu/cpu" + std::to_string(c) + "/topology/";
                        std::string sib = rangeStr(clusterRange[pkg[c]].first, clusterRange[pkg[c]].second);
                        write_spoof("toppkg" + std::to_string(c), std::to_string(pkg[c]) + "\n", base + "physical_package_id");
                        write_spoof("topsib" + std::to_string(c), sib + "\n", base + "core_siblings_list");
                        write_spoof("topclu" + std::to_string(c), sib + "\n", base + "cluster_cpus_list");

                        // Per-core cache-size spoof (size + level + shared_cpu_list). HOST-STRUCTURE-AWARE: we
                        // only redirect a cache file that ALREADY EXISTS on the host at the SAME index with a
                        // MATCHING type label (codex/subagent gauntlet) — so we never fabricate a nonexistent
                        // index, nor attach an L1i size to an index the host labels "Data". This makes the
                        // hard-coded index0=L1i/1=L1d/2=L2/3=L3 assumption self-correcting: if the host's layout
                        // differs, the type check simply skips the mismatched write (leaves it real) rather than
                        // creating a contradiction. L2 shared_cpu_list: PRIVATE (this core) on DynamIQ parts
                        // (those with an L3/DSU, cpu_l3>0 — SD845/855/865/888, modern Exynos, Tensor); cluster-
                        // shared only on pre-DynamIQ designs (no L3). L3 shared by all cores.
                        std::string cb = "/sys/devices/system/cpu/cpu" + std::to_string(c) + "/cache/index";
                        auto readReal = [](const std::string &p) -> std::string {
                            int fd = open(p.c_str(), O_RDONLY | O_CLOEXEC);
                            if (fd < 0) return "";
                            char buf[64]; ssize_t r = read(fd, buf, sizeof(buf) - 1); close(fd);
                            if (r <= 0) return "";
                            std::string s(buf, (size_t) r);
                            while (!s.empty() && (s.back() == '\n' || s.back() == ' ')) s.pop_back();
                            return s;
                        };
                        auto writeCache = [&](int idx, const std::string &kb, int level, const std::string &shared,
                                              const char *wantType) {
                            if (kb.empty() || kb == "0") return;
                            std::string ib = cb + std::to_string(idx) + "/";
                            // Only spoof an index the host actually exposes (size file present) AND whose type
                            // matches what we expect (so we never mislabel). If either check fails, leave it real.
                            if (access((ib + "size").c_str(), F_OK) != 0) return;
                            std::string realType = readReal(ib + "type");
                            if (!realType.empty() && realType != wantType) return;
                            std::string tag = "cache" + std::to_string(c) + "_" + std::to_string(idx);
                            write_spoof(tag + "s", kb + "K\n",              ib + "size");
                            write_spoof(tag + "l", std::to_string(level) + "\n", ib + "level");
                            write_spoof(tag + "h", shared + "\n",           ib + "shared_cpu_list");
                        };
                        std::string self = std::to_string(c);
                        // DynamIQ (has an L3/DSU) -> private per-core L2; pre-DynamIQ -> cluster-shared L2.
                        bool dynamiq = !(l3kb.empty() || l3kb == "0");
                        std::string l2shared = dynamiq ? self : sib;
                        if (c < (int) l1i.size()) writeCache(0, l1i[c], 1, self,     "Instruction");
                        if (c < (int) l1d.size()) writeCache(1, l1d[c], 1, self,     "Data");
                        if (c < (int) l2.size())  writeCache(2, l2[c],  2, l2shared, "Unified");
                        writeCache(3, l3kb, 3, full, "Unified");                    // L3 (all cores; skipped if 0)
                    }
                    // Top-level core-count files. present is already written from cpu_present above; add the
                    // siblings online/possible (0-(n-1)) and kernel_max (n-1) so the whole CPU-count picture is coherent.
                    write_spoof("cpuonline",   full + "\n", "/sys/devices/system/cpu/online");
                    write_spoof("cpupossible", full + "\n", "/sys/devices/system/cpu/possible");
                    write_spoof("cpukmax",      std::to_string(n - 1) + "\n", "/sys/devices/system/cpu/kernel_max");
                }
            }

            // /proc/modules — the loaded-kernel-module list. The REAL device's names leak its exact hardware
            // (proven on the Pixel 4: "ftm5" = its ST touchscreen driver, "heatmap", etc. — a claimed LG G7
            // would never have those). Redirect to a GENERIC list of modules common to most ARM Android so a
            // reader sees a plausible-but-non-identifying set instead of the host's device-specific drivers.
            // (Addresses are zeroed like a non-root read; sizes/refcounts are plausible constants.)
            // VENDOR-NEUTRAL list: no Qualcomm/Exynos/Tensor-specific driver names (those would themselves
            // reveal profile incoherence on a non-matching SoC — codex gauntlet). Just a minimal set of
            // generic Android modules present across vendors + a wlan driver, which any phone plausibly shows.
            {
                static const char *GENERIC_MODULES =
                    "wlan 6668672 0 - Live 0x0000000000000000 (O)\n"
                    "zram 32768 2 - Live 0x0000000000000000\n"
                    "cfg80211 1085440 1 wlan, Live 0x0000000000000000\n";
                write_spoof("procmodules", GENERIC_MODULES, "/proc/modules");
            }

            auto gm = profile.find("gpu_model");
            if (gm != profile.end() && !gm->second.empty())
                write_spoof("gpumodel", gm->second + "\n", "/sys/class/kgsl/kgsl-3d0/gpu_model");
            auto pr = profile.find("cpu_present");
            if (pr != profile.end() && !pr->second.empty())
                write_spoof("cpupresent", pr->second + "\n", "/sys/devices/system/cpu/present");

            // /proc/meminfo — its MemTotal line leaks the REAL device RAM even though ActivityManager.
            // totalMem (the Java path) is spoofed. FingerprintJS's demo reads /proc/meminfo directly
            // (tracer-proven), so a direct parse of MemTotal contradicts the claimed device's RAM. Redirect
            // it to a spoof file whose MemTotal (+ a coherent MemFree/MemAvailable) matches the profile's
            // total_ram. Other lines are plausible constants — only MemTotal is identity-bearing.
            auto ram = profile.find("total_ram");
            if (ram != profile.end() && !ram->second.empty()) {
                // Strict parse: reject trailing garbage / overflow (codex) — total_ram is always a clean
                // generated integer, but don't build a bogus meminfo from a malformed value.
                errno = 0;
                char *endp = nullptr;
                long bytes = strtol(ram->second.c_str(), &endp, 10);
                if (errno == 0 && endp && *endp == '\0' && bytes > 0) {
                    long totalKb = bytes / 1024;
                    long freeKb = totalKb / 3;             // ~33% free — plausible for a running device
                    long availKb = totalKb / 2;            // ~50% available
                    // A fuller field set than a bare MemTotal, so framework/SDK code that parses meminfo
                    // for other fields (Cached/Shmem/Slab/…) still finds them (codex robustness note).
                    char buf[1024];
                    int n = snprintf(buf, sizeof(buf),
                        "MemTotal:       %ld kB\n"
                        "MemFree:        %ld kB\n"
                        "MemAvailable:   %ld kB\n"
                        "Buffers:          65536 kB\n"
                        "Cached:         %ld kB\n"
                        "SwapCached:           0 kB\n"
                        "Active:         %ld kB\n"
                        "Inactive:       %ld kB\n"
                        "SwapTotal:      %ld kB\n"
                        "SwapFree:       %ld kB\n"
                        "Dirty:              128 kB\n"
                        "Writeback:            0 kB\n"
                        "AnonPages:      %ld kB\n"
                        "Mapped:          524288 kB\n"
                        "Shmem:           131072 kB\n"
                        "Slab:            262144 kB\n"
                        "KernelStack:      32768 kB\n"
                        "PageTables:       65536 kB\n"
                        "VmallocTotal:  263061440 kB\n",
                        totalKb, freeKb, availKb, totalKb / 4, totalKb / 3, totalKb / 4,
                        totalKb / 2, totalKb / 2, totalKb / 5);
                    if (n > 0 && n < (int) sizeof(buf))
                        write_spoof("meminfo", std::string(buf, n), "/proc/meminfo");
                }
            }
        }

        if (g_prop_spoof.empty() && g_reset_epoch == 0 && g_cpuinfo_path.empty() &&
            g_bootid_path.empty() && !g_spoof_hwcap && !g_trace && !g_hide_root && !g_hide_vpn &&
            !g_hide_kgsl && g_gl_renderer.empty() && g_gl_vendor.empty() && g_gl_version.empty() &&
            g_sensor_labels.empty() && g_sys_redirect.empty()) return;
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
        // The stat family carries BOTH the reset-marker mtime spoof AND the path-hiding (root/kgsl) ENOENT
        // check, so install the WHOLE family whenever either is needed. bionic's stat()/lstat() route through
        // fstatat, and a native detector often stats /system/bin/su (or the kgsl node) via fstatat/statx to
        // dodge the access() hooks — so fstatat + statx MUST be covered too, not just stat/lstat (codex).
        if (g_reset_epoch != 0 || g_hide_root || g_hide_kgsl) {
            applied += hookSym("stat",      (void *) my_stat,    (void **) &orig_stat);
            applied += hookSym("lstat",     (void *) my_lstat,   (void **) &orig_lstat);
            applied += hookSym("fstatat",   (void *) my_fstatat, (void **) &orig_fstatat);
            applied += hookSym("fstatat64", (void *) my_fstatat, (void **) &orig_fstatat);
            applied += hookSym("statx",     (void *) my_statx,   (void **) &orig_statx);
        }
        // File hooks: needed for cpuinfo/boot_id/sysfs redirects, the tracer, root-hiding, AND kgsl-hiding.
        if (!g_cpuinfo_path.empty() || !g_bootid_path.empty() || g_trace || g_hide_root || g_hide_kgsl
                || !g_sys_redirect.empty()) {
            applied += hookSym("openat", (void *) my_openat, (void **) &orig_openat);
            applied += hookSym("open",   (void *) my_open,   (void **) &orig_open);
            applied += hookSym("fopen",  (void *) my_fopen,  (void **) &orig_fopen);
        }
        if (g_hide_root) {
            applied += hookSym("access", (void *) my_access, (void **) &orig_access);
            applied += hookSym("faccessat", (void *) my_faccessat, (void **) &orig_faccessat);
        }
        if (g_spoof_hwcap || g_trace) {
            applied += hookSym("getauxval", (void *) my_getauxval, (void **) &orig_getauxval);
        }
        // Native VPN mask: filter tun/ppp/wg entries from getifaddrs() — the netlink-backed interface
        // enumeration an NDK detector uses (the /proc/net files are SELinux-denied to apps, so this is THE
        // native path). Counterpart of the Java NetworkInterface.getNetworkInterfaces() hook.
        if (g_hide_vpn) {
            applied += hookSym("getifaddrs", (void *) my_getifaddrs, (void **) &orig_getifaddrs);
        }
        // glGetString (GPU renderer/vendor/version). libGLESv2.so may not be loaded yet at
        // specialize-time (it loads when the app first touches GL), so dlopen it to resolve the symbol;
        // the inline patch then applies to the real function every later caller uses. Best-effort — if
        // the lib can't be opened the app just isn't a GL reader, and nothing is lost.
        if (!g_gl_renderer.empty() || !g_gl_vendor.empty() || !g_gl_version.empty() || !g_gl_ext_candidates.empty()) {
            void *glh = dlopen("libGLESv2.so", RTLD_NOW | RTLD_NOLOAD);
            if (!glh) glh = dlopen("libGLESv2.so", RTLD_NOW);
            // Returns true iff the inline hook is live AND its trampoline (*orig) was published. Publishing
            // orig before the caller can enter my_* is what keeps the null-orig window from ever mattering.
            auto hookGl = [&](const char *n, void *repl, void **orig) -> bool {
                void *sym = glh ? dlsym(glh, n) : dlsym(RTLD_DEFAULT, n);
                if (sym && !g_hooked_addrs.count(sym)) {
                    void *tramp = nullptr;
                    A64HookFunction(sym, repl, &tramp);
                    if (tramp) { g_hooked_addrs[sym] = true; *orig = tramp; applied++; return true; }
                    LOGD("A64HookFunction %s failed", n);
                } else if (!sym) LOGD("%s unresolved (no GL lib)", n);
                return false;
            };
            hookGl("glGetString", (void *) my_glGetString, (void **) &orig_glGetString);
            // Extension-list spoof: BOTH glGetStringi and glGetIntegerv must hook, or the count and the
            // entries desync into a detectable half-fake list (codex). Enable the spoof (g_gl_both_hooked)
            // only when both land; if either fails, neither my_* spoofs (they fall through to real).
            if (!g_gl_ext_candidates.empty()) {
                bool si = hookGl("glGetStringi",  (void *) my_glGetStringi,  (void **) &orig_glGetStringi);
                bool iv = hookGl("glGetIntegerv", (void *) my_glGetIntegerv, (void **) &orig_glGetIntegerv);
                g_gl_both_hooked = si && iv;
            }
        }
        // Native sensor list — libfp reads it via libandroid's ASensor_getName/getVendor (tracer-proven
        // direct JNI). Relabel those two accessors to the profile's per-model sensor names/vendors.
        // libandroid.so is typically already loaded; dlopen to be sure, then inline-hook, deduped by addr.
        if (!g_sensor_labels.empty()) {
            void *lah = dlopen("libandroid.so", RTLD_NOW | RTLD_NOLOAD);
            if (!lah) lah = dlopen("libandroid.so", RTLD_NOW);
            void *nSym = lah ? dlsym(lah, "ASensor_getName") : dlsym(RTLD_DEFAULT, "ASensor_getName");
            if (nSym && !g_hooked_addrs.count(nSym)) {
                void *tramp = nullptr;
                A64HookFunction(nSym, (void *) my_ASensor_getName, &tramp);
                if (tramp) { g_hooked_addrs[nSym] = true; orig_ASensor_getName = (ASensor_getName_t) tramp; applied++; }
                else LOGD("A64HookFunction ASensor_getName failed");
            } else if (!nSym) LOGD("ASensor_getName unresolved");
            void *vSym = lah ? dlsym(lah, "ASensor_getVendor") : dlsym(RTLD_DEFAULT, "ASensor_getVendor");
            if (vSym && !g_hooked_addrs.count(vSym)) {
                void *tramp = nullptr;
                A64HookFunction(vSym, (void *) my_ASensor_getVendor, &tramp);
                if (tramp) { g_hooked_addrs[vSym] = true; orig_ASensor_getVendor = (ASensor_getVendor_t) tramp; applied++; }
                else LOGD("A64HookFunction ASensor_getVendor failed");
            } else if (!vSym) LOGD("ASensor_getVendor unresolved");
        }
        if (g_trace) {
            applied += hookSym("dlsym", (void *) my_dlsym, (void **) &orig_dlsym);
        }
        // syscall: needed whenever we redirect files (libfp reads via raw syscall) or trace, incl. the
        // mount-file redirect (a root detector may read /proc/mounts via raw syscall(SYS_openat)).
        if (!g_cpuinfo_path.empty() || !g_bootid_path.empty() || g_trace || !g_sys_redirect.empty()
                || !g_mounts_path.empty() || !g_mountinfo_path.empty()) {
            applied += hookSym("syscall", (void *) my_syscall, (void **) &orig_syscall);
        }
        // In pure-trace mode prop_get may not be hooked yet (no props spoofed) — ensure it for the trace.
        if (g_trace && g_prop_spoof.empty()) {
            applied += hookSym("__system_property_get", (void *) my_prop_get, (void **) &orig_prop_get);
        }
        if (applied == 0) { LOGE("no hooks applied for %s", pkg.c_str()); return; }
        LOGD("hooks installed for %s (%d syms, props=%zu reset=%ld bootid=%d hwcap=%d trace=%d)",
             pkg.c_str(), applied, g_prop_spoof.size(), g_reset_epoch,
             !g_bootid_path.empty(), g_spoof_hwcap, g_trace);

        // Arm the late (init-unsafe) prop spoof ~3s from now. HEURISTIC (not a hard readiness proof):
        // ART/libc finish the init reads of sdk/first_api_level well within this window on a real device,
        // and any real fingerprinting read is user-triggered far later. A late dlopen/lazy-ctor on a very
        // slow/loaded device could theoretically read after the window (codex-flagged) — 3s is generous
        // margin; if a real crash ever recurs, gate on a concrete lifecycle event instead of time.
        // release store pairs with the acquire load in prop_spoof_lookup to publish the map writes.
        if (!g_prop_spoof_late.empty()) {
            std::thread([] {
                std::this_thread::sleep_for(std::chrono::milliseconds(3000));
                g_props_ready.store(true, std::memory_order_release);
            }).detach();
        }
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
    // OS safety: never serve a profile for the framework/system process (spoofing the OS itself is
    // dangerous + pointless). Target apps — including the income apps — ARE spoofable by design.
    if (is_core_os(pkg)) { uint32_t zero = 0; write(client, &zero, sizeof(zero)); return; }

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
