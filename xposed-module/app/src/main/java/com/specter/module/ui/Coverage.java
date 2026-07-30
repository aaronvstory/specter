package com.specter.module.ui;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Pure (Android-free) heuristic for the live-trace viewer: given a signal a scoped app READ (a prop key or
 * a file path), does Specter SPOOF it? Drives a "spoofed / real" badge so the user sees which reads are
 * protected. To avoid MISLEADING the user with a false "spoofed", coverage is decided by EXACT match
 * against the actual key/path set Specter hooks (mirror of HookEntry.PROP_ALIASES + STATIC_PROPS + the
 * deferred/derived props + the native redirect targets) — never a loose namespace prefix (which would
 * over-claim siblings like ro.hardware.gralloc or ro.build.date.utc that we don't touch). Returns REAL for
 * reads we deliberately leave (non-identifying), UNKNOWN otherwise. Testable off the hook path.
 */
public final class Coverage {
    private Coverage() {}

    public enum State { SPOOFED, REAL, UNKNOWN }

    /** EXACT prop keys Specter spoofs — mirror of HookEntry.PROP_ALIASES column 0 + STATIC_PROPS +
     *  the deferred (sdk/first_api_level) + derived (build.tags/type, warranty) keys. Keep in sync. */
    private static final Set<String> SPOOFED_PROPS = new HashSet<>(Arrays.asList(
        // radio / kernel / soc
        "gsm.version.baseband", "ril.baseband", "os.version", "ro.board.platform", "ro.soc.model",
        "ro.chipname", "ro.mediatek.platform",
        // boot.* identity + lock state
        "ro.boot.bootloader", "ro.boot.hardware", "ro.boot.hardware.platform", "ro.boot.serialno",
        "ro.boot.flash.locked", "ro.boot.vbmeta.device_state", "ro.boot.verifiedbootstate",
        "ro.boot.veritymode", "ro.boot.warranty_bit",
        // build.* + fingerprints (all partitions)
        "ro.bootimage.build.fingerprint", "ro.bootloader", "ro.build.description", "ro.build.display.id",
        "ro.build.fingerprint", "ro.build.flavor", "ro.build.host", "ro.build.id", "ro.build.product",
        "ro.build.version.incremental", "ro.build.version.release", "ro.build.version.security_patch",
        "ro.build.version.sdk", "ro.build.tags", "ro.build.type", "ro.debuggable", "ro.secure",
        "ro.warranty_bit", "ro.odm.build.fingerprint", "ro.product.build.fingerprint",
        "ro.system.build.fingerprint", "ro.system_ext.build.fingerprint", "ro.vendor.build.fingerprint",
        // hardware
        "ro.hardware", "ro.hardware.chipname",
        "ro.hardware.egl", "ro.hardware.vulkan",   // GPU driver family (aliased to gpu_hw); NOT gralloc (empty on real devices)
        // product.* (all partitions)
        "ro.product.board", "ro.product.brand", "ro.product.build.id", "ro.product.build.version.incremental",
        "ro.product.build.version.release", "ro.product.device", "ro.product.manufacturer",
        "ro.product.model", "ro.product.name", "ro.product.first_api_level",
        "ro.product.odm.brand", "ro.product.odm.device", "ro.product.odm.manufacturer",
        "ro.product.odm.model", "ro.product.odm.name", "ro.product.product.brand",
        "ro.product.product.device", "ro.product.product.manufacturer", "ro.product.product.model",
        "ro.product.product.name", "ro.product.system.brand", "ro.product.system.device",
        "ro.product.system.manufacturer", "ro.product.system.model", "ro.product.system.name",
        "ro.product.system_ext.brand", "ro.product.system_ext.device", "ro.product.system_ext.manufacturer",
        "ro.product.system_ext.model", "ro.product.system_ext.name", "ro.product.vendor.brand",
        "ro.product.vendor.device", "ro.product.vendor.manufacturer", "ro.product.vendor.model",
        "ro.product.vendor.name",
        "ro.serialno"
    ));

    /** EXACT files/sysfs nodes the native layer redirects to a spoofed copy. */
    private static final Set<String> SPOOFED_FILES = new HashSet<>(Arrays.asList(
        "/proc/cpuinfo", "/proc/version", "/proc/meminfo",
        "/proc/mounts", "/proc/self/mountinfo",
        "/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/devices/system/cpu/present",
        // core-count files the native layer now redirects (were leaking the real core layout)
        "/sys/devices/system/cpu/online", "/sys/devices/system/cpu/possible",
        "/sys/devices/system/cpu/kernel_max",
        "/proc/modules",   // generic module list (real names leak the device's specific drivers)
        "/proc/sys/kernel/random/boot_id", "/sys/fs/selinux/enforce"
    ));

    public static State of(String verb, String target) {
        if (target == null || target.isEmpty()) return State.UNKNOWN;
        if ("prop".equals(verb)) {
            if (SPOOFED_PROPS.contains(target)) return State.SPOOFED;
            // Non-identity props we deliberately don't spoof (universal / generic) -> REAL.
            if (target.startsWith("vendor.") || target.startsWith("debug.") || target.startsWith("sys.")
                    || target.startsWith("cache_key.") || target.equals("ro.arch")
                    || target.startsWith("ro.product.cpu.abilist") || target.startsWith("ro.hardware.")
                    || target.startsWith("ro.input.") || target.startsWith("persist.input.")
                    // generic non-identity flags a fingerprinter also touches — not device-identifying (empty
                    // or a universal boolean on real devices; audited from the Cash trace).
                    || target.startsWith("media.metrics.") || target.startsWith("persist.media.")
                    || target.equals("ro.boringcrypto.hwrand") || target.equals("ro.vendor.graphics.memory")
                    || target.equals("ro.vendor.redirect_socket_calls")
                    || target.equals("ro.build.version.codename") || target.equals("ro.build.version.preview_sdk"))
                return State.REAL;   // touch/input tuning + generic props — not device-identifying
            return State.UNKNOWN;
        }
        // Files: exact spoofed-file matches, plus the strict per-core cpu_capacity / cpufreq / topology family.
        if (SPOOFED_FILES.contains(target)) return State.SPOOFED;
        if (isCpuCapacityPath(target)) return State.SPOOFED;
        if (isPerCoreCpuPath(target)) return State.SPOOFED;
        // A read of the app's OWN /proc or a system lib is not device-identifying.
        if (target.startsWith("/proc/self/") || target.startsWith("/system/")
                || target.startsWith("/apex/") || target.startsWith("/vendor/lib")) return State.REAL;
        return State.UNKNOWN;
    }

    /** Strictly /sys/devices/system/cpu/cpu&lt;digits&gt;/cpu_capacity (one per-core node we redirect) — not any
     *  path merely containing "cpu" and ending in cpu_capacity (codex). */
    static boolean isCpuCapacityPath(String path) {
        return isPerCoreCpuLeaf(path, "/cpu_capacity");
    }

    /** The per-core cpufreq + topology files the native layer redirects (freq ceiling + cluster grouping —
     *  both leak the real SoC otherwise). Matches /sys/devices/system/cpu/cpu&lt;digits&gt;/&lt;one of the leaves&gt;. */
    static boolean isPerCoreCpuPath(String path) {
        return isPerCoreCpuLeaf(path, "/cpufreq/cpuinfo_max_freq")
                || isPerCoreCpuLeaf(path, "/cpufreq/cpuinfo_min_freq")
                || isPerCoreCpuLeaf(path, "/cpufreq/scaling_max_freq")
                || isPerCoreCpuLeaf(path, "/cpufreq/scaling_min_freq")
                || isPerCoreCpuLeaf(path, "/topology/physical_package_id")
                || isPerCoreCpuLeaf(path, "/topology/core_siblings_list")
                || isPerCoreCpuLeaf(path, "/topology/cluster_cpus_list")
                || isCachePath(path);   // .../cpu<N>/cache/index<K>/{size,level,shared_cpu_list} (full tree spoofed)
    }

    /** /sys/devices/system/cpu/cpu&lt;digits&gt;/cache/index&lt;digits&gt;/{size,level,shared_cpu_list} — the native layer
     *  redirects the full per-index cache tree (size+level+sharing together) to the claimed SoC's cache. */
    static boolean isCachePath(String path) {
        final String pre = "/sys/devices/system/cpu/cpu", mid = "/cache/index";
        if (path == null || !path.startsWith(pre)) return false;
        if (!(path.endsWith("/size") || path.endsWith("/level") || path.endsWith("/shared_cpu_list"))) return false;
        int m = path.indexOf(mid, pre.length());
        if (m < 0) return false;
        String core = path.substring(pre.length(), m);
        if (core.isEmpty()) return false;
        for (int i = 0; i < core.length(); i++) if (!Character.isDigit(core.charAt(i))) return false;
        // the index<digits> segment must be all digits after "/cache/index"
        String afterIdx = path.substring(m + mid.length());
        int slash = afterIdx.indexOf('/');
        if (slash <= 0) return false;
        String idx = afterIdx.substring(0, slash);
        if (idx.isEmpty()) return false;
        for (int i = 0; i < idx.length(); i++) if (!Character.isDigit(idx.charAt(i))) return false;
        // The remainder after "index<K>/" must be EXACTLY one spoofed leaf — not ".../index2/anything/size"
        // (codex: a nested path would else false-match SPOOFED though nothing redirects it).
        String leaf = afterIdx.substring(slash + 1);
        return leaf.equals("size") || leaf.equals("level") || leaf.equals("shared_cpu_list");
    }

    /** True iff path == /sys/devices/system/cpu/cpu&lt;digits&gt;&lt;suffix&gt; (the &lt;digits&gt; segment must be non-empty
     *  and all digits — so a sibling like .../cpuidle/ never false-matches). */
    private static boolean isPerCoreCpuLeaf(String path, String suffix) {
        final String pre = "/sys/devices/system/cpu/cpu";
        if (path == null || !path.startsWith(pre) || !path.endsWith(suffix)) return false;
        String mid = path.substring(pre.length(), path.length() - suffix.length());
        if (mid.isEmpty()) return false;
        for (int i = 0; i < mid.length(); i++) if (!Character.isDigit(mid.charAt(i))) return false;
        return true;
    }
}
