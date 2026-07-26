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
#include <cstdarg>
#include <cstdio>
#include <cerrno>
#include <string>
#include <map>
#include <vector>
#include <mutex>
#include <utility>
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
// Props that CRASH the zygote if spoofed during process init (ART/libc read them before our hook state
// is safe — proven SIGSEGV for ro.product.first_api_level / ro.build.version.sdk). We spoof them ONLY
// after init is done: g_props_ready flips true ~1s post-specialize (a detached thread), long before any
// user-triggered fingerprinting read. So init-time reads pass through REAL (no crash), runtime reads
// (e.g. FingerprintJS at fingerprint time) get the spoofed value.
static std::map<std::string, std::string> g_prop_spoof_late;
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
static bool is_root_path(const char *path);              // defined below with the file hooks

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
    if (g_hide_root && is_root_path(path)) { errno = ENOENT; return -1; }
    int r = orig_stat(path, st);
    if (r == 0 && is_reset_marker(path)) spoof_stat(st);
    return r;
}
static int my_lstat(const char *path, struct stat *st) {
    if (g_trace) trace_path("lstat", path);
    if (g_hide_root && is_root_path(path)) { errno = ENOENT; return -1; }
    int r = orig_lstat(path, st);
    if (r == 0 && is_reset_marker(path)) spoof_stat(st);
    return r;
}
static int my_fstatat(int dirfd, const char *path, struct stat *st, int flags) {
    if (g_trace) trace_path("fstatat", path);
    int r = orig_fstatat(dirfd, path, st, flags);
    // The reset markers are absolute paths, so dirfd is irrelevant when the path matches.
    if (r == 0 && is_reset_marker(path)) spoof_stat(st);
    return r;
}
static int my_statx(int dirfd, const char *path, int flags, unsigned int mask, struct statx *stx) {
    if (g_trace) trace_path("statx", path);
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
static int my_openat(int dirfd, const char *path, int flags, ...) {
    if (g_trace) trace_path("openat", path);
    if (g_hide_root && is_root_path(path)) { errno = ENOENT; return -1; }
    const char *rp = redirect_path(path);
    if (rp != path) return orig_openat(AT_FDCWD, rp, O_RDONLY | O_CLOEXEC);
    va_list ap; va_start(ap, flags); mode_t mode = va_arg(ap, int); va_end(ap);
    return orig_openat(dirfd, path, flags, mode);
}
static int my_open(const char *path, int flags, ...) {
    if (g_trace) trace_path("open", path);
    if (g_hide_root && is_root_path(path)) { errno = ENOENT; return -1; }
    const char *rp = redirect_path(path);
    if (rp != path) return orig_open(rp, O_RDONLY | O_CLOEXEC);
    va_list ap; va_start(ap, flags); mode_t mode = va_arg(ap, int); va_end(ap);
    return orig_open(path, flags, mode);
}

// fopen is what libfp.so explicitly imports (readelf) to read /proc & /sys nodes; trace + redirect it.
using fopen_t = FILE *(*)(const char *, const char *);
static fopen_t orig_fopen = nullptr;
static FILE *my_fopen(const char *path, const char *mode) {
    if (g_trace) trace_path("fopen", path);
    if (g_hide_root && is_root_path(path)) { errno = ENOENT; return nullptr; }
    const char *rp = redirect_path(path);
    if (rp != path) return orig_fopen(rp, mode);
    return orig_fopen(path, mode);
}

// access() is the most common root check (access("/system/xbin/su", F_OK)). Hide root paths.
using access_t = int (*)(const char *, int);
static access_t orig_access = nullptr;
static int my_access(const char *path, int mode) {
    if (g_hide_root && is_root_path(path)) { errno = ENOENT; return -1; }
    return orig_access(path, mode);
}

// faccessat() is what bionic's access() actually calls on modern Android, and what a native root check
// (e.g. faccessat(AT_FDCWD, "/system/bin/su", F_OK, 0)) uses to BYPASS the access() hook. Cover it too,
// or a su-path probe via faccessat slips through and rootApps stays true. (faccessat2 raw-syscall variant
// is handled in my_syscall below.)
using faccessat_t = int (*)(int, const char *, int, int);
static faccessat_t orig_faccessat = nullptr;
static int my_faccessat(int dirfd, const char *path, int mode, int flags) {
    if (g_hide_root && is_root_path(path)) { errno = ENOENT; return -1; }
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
        if (g_hide_root && is_root_path(path)) { errno = ENOENT; return -1; }
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
        if (g_hide_root && is_root_path(path)) { errno = ENOENT; return -1; }
    }
    // newfstatat(dirfd, path, statbuf, flags) / statx(dirfd, path, flags, mask, statbuf) — path is a2.
    else if (number == __NR_newfstatat
#ifdef __NR_statx
             || number == __NR_statx
#endif
            ) {
        const char *path = (const char *) a2;
        if (g_trace) trace_path("syscall.stat", path);
        if (g_hide_root && is_root_path(path)) { errno = ENOENT; return -1; }
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
static std::string g_gl_renderer, g_gl_vendor, g_gl_version;
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
    return orig_glGetString(name);
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

static size_t sensor_index_for(const void *sensor) {
    // Assign this ASensor* the next label slot on first sight; stable thereafter. Caller holds nothing.
    std::lock_guard<std::mutex> lk(g_sensor_mtx);
    auto it = g_sensor_assign.find(sensor);
    if (it != g_sensor_assign.end()) return it->second;
    size_t idx = g_sensor_assign.size() % g_sensor_labels.size();
    g_sensor_assign[sensor] = idx;
    return idx;
}

using ASensor_getName_t = const char *(*)(const void *);
static ASensor_getName_t orig_ASensor_getName = nullptr;
static const char *my_ASensor_getName(const void *sensor) {
    if (g_sensor_labels.empty() || sensor == nullptr) return orig_ASensor_getName(sensor);
    return g_sensor_labels[sensor_index_for(sensor)].first.c_str();
}

using ASensor_getVendor_t = const char *(*)(const void *);
static ASensor_getVendor_t orig_ASensor_getVendor = nullptr;
static const char *my_ASensor_getVendor(const void *sensor) {
    if (g_sensor_labels.empty() || sensor == nullptr) return orig_ASensor_getVendor(sensor);
    return g_sensor_labels[sensor_index_for(sensor)].second.c_str();
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
        // any user-triggered fingerprint read. Value = the profile's build_sdk (the claimed Android API).
        auto sdk = profile.find("build_sdk");
        if (sdk != profile.end() && !sdk->second.empty()) {
            g_prop_spoof_late["ro.build.version.sdk"] = sdk->second;
            g_prop_spoof_late["ro.product.first_api_level"] = sdk->second;
        }
        auto ep = profile.find("factory_reset_epoch");
        if (ep != profile.end()) g_reset_epoch = strtol(ep->second.c_str(), nullptr, 10);

        // GPU renderer/vendor/version — the profile's coherent per-model values (GOAL 1.3). Read here;
        // the glGetString inline hook returns them. GL_VERSION is shaped like a real driver string.
        auto gr = profile.find("hw_gpu_renderer");
        if (gr != profile.end()) g_gl_renderer = gr->second;
        auto gv = profile.find("hw_gpu_vendor");
        if (gv != profile.end()) g_gl_vendor = gv->second;
        auto gl = profile.find("hw_gles_version");
        if (gl != profile.end() && !gl->second.empty())
            g_gl_version = "OpenGL ES " + gl->second + " V@0.0";

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
        }

        auto tr = profile.find("trace");
        if (tr != profile.end() && tr->second == "1") g_trace = true;
        // Hide root by default for every Specter target (a rooted-device flag is a strong linking signal).
        g_hide_root = true;
        auto hr = profile.find("hide_root");
        if (hr != profile.end() && hr->second == "0") g_hide_root = false;
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
                if (f >= 0) {
                    bool ok = write(f, content.data(), content.size()) == (ssize_t) content.size();
                    close(f);
                    if (ok) g_sys_redirect[sysPath] = sp;
                }
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
            auto gm = profile.find("gpu_model");
            if (gm != profile.end() && !gm->second.empty())
                write_spoof("gpumodel", gm->second + "\n", "/sys/class/kgsl/kgsl-3d0/gpu_model");
            auto pr = profile.find("cpu_present");
            if (pr != profile.end() && !pr->second.empty())
                write_spoof("cpupresent", pr->second + "\n", "/sys/devices/system/cpu/present");
        }

        if (g_prop_spoof.empty() && g_reset_epoch == 0 && g_cpuinfo_path.empty() &&
            g_bootid_path.empty() && !g_spoof_hwcap && !g_trace && !g_hide_root &&
            g_gl_renderer.empty() && g_gl_vendor.empty() && g_gl_version.empty() &&
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
        if (g_reset_epoch != 0) {
            applied += hookSym("stat",      (void *) my_stat,    (void **) &orig_stat);
            applied += hookSym("lstat",     (void *) my_lstat,   (void **) &orig_lstat);
            applied += hookSym("fstatat",   (void *) my_fstatat, (void **) &orig_fstatat);
            applied += hookSym("fstatat64", (void *) my_fstatat, (void **) &orig_fstatat);
            applied += hookSym("statx",     (void *) my_statx,   (void **) &orig_statx);
        }
        // File hooks: needed for cpuinfo/boot_id/sysfs redirects, the tracer, AND root-hiding.
        if (!g_cpuinfo_path.empty() || !g_bootid_path.empty() || g_trace || g_hide_root
                || !g_sys_redirect.empty()) {
            applied += hookSym("openat", (void *) my_openat, (void **) &orig_openat);
            applied += hookSym("open",   (void *) my_open,   (void **) &orig_open);
            applied += hookSym("fopen",  (void *) my_fopen,  (void **) &orig_fopen);
        }
        if (g_hide_root) {
            applied += hookSym("access", (void *) my_access, (void **) &orig_access);
            applied += hookSym("faccessat", (void *) my_faccessat, (void **) &orig_faccessat);
            // stat/lstat carry the root-hide check too; install them if not already (reset path installs them).
            if (g_reset_epoch == 0) {
                applied += hookSym("stat",  (void *) my_stat,  (void **) &orig_stat);
                applied += hookSym("lstat", (void *) my_lstat, (void **) &orig_lstat);
            }
        }
        if (g_spoof_hwcap || g_trace) {
            applied += hookSym("getauxval", (void *) my_getauxval, (void **) &orig_getauxval);
        }
        // glGetString (GPU renderer/vendor/version). libGLESv2.so may not be loaded yet at
        // specialize-time (it loads when the app first touches GL), so dlopen it to resolve the symbol;
        // the inline patch then applies to the real function every later caller uses. Best-effort — if
        // the lib can't be opened the app just isn't a GL reader, and nothing is lost.
        if (!g_gl_renderer.empty() || !g_gl_vendor.empty() || !g_gl_version.empty()) {
            void *glh = dlopen("libGLESv2.so", RTLD_NOW | RTLD_NOLOAD);
            if (!glh) glh = dlopen("libGLESv2.so", RTLD_NOW);
            void *sym = glh ? dlsym(glh, "glGetString") : dlsym(RTLD_DEFAULT, "glGetString");
            if (sym && !g_hooked_addrs.count(sym)) {
                void *tramp = nullptr;
                A64HookFunction(sym, (void *) my_glGetString, &tramp);
                if (tramp) { g_hooked_addrs[sym] = true; orig_glGetString = (glGetString_t) tramp; applied++; }
                else LOGD("A64HookFunction glGetString failed");
            } else if (!sym) LOGD("glGetString unresolved (no GL lib)");
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

        // Arm the late (init-unsafe) prop spoof ~1.5s from now. By then ART/libc have finished the init
        // reads of sdk/first_api_level that would SIGSEGV if spoofed, and any real fingerprinting read is
        // user-triggered far later. A detached thread flips the flag; the hooks read it lock-free.
        if (!g_prop_spoof_late.empty()) {
            std::thread([] {
                std::this_thread::sleep_for(std::chrono::milliseconds(1500));
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
