package com.specter.module.ui;

/**
 * Pure (Android-free) heuristic for the live-trace viewer: given a signal a scoped app READ (a prop key or
 * a file path), does Specter SPOOF it? This drives a "spoofed / real" badge so the user sees at a glance
 * which reads are protected. It's a HEURISTIC over the categories Specter actually covers (kept in sync
 * with HookEntry.PROP_ALIASES/STATIC_PROPS + the native redirect targets), not a per-key mirror — it errs
 * toward SPOOFED only for families we genuinely cover, and UNKNOWN for everything else (so it never
 * over-claims). Testable off the hook path.
 */
public final class Coverage {
    private Coverage() {}

    public enum State { SPOOFED, REAL, UNKNOWN }

    /** Files/sysfs nodes the native layer redirects to a spoofed copy. */
    private static final String[] SPOOFED_FILES = {
        "/proc/cpuinfo", "/proc/version", "/proc/meminfo",
        "/proc/mounts", "/proc/self/mountinfo",              // Magisk filtered out
        "/sys/class/kgsl/kgsl-3d0/gpu_model", "/sys/devices/system/cpu/present",
        "/proc/sys/kernel/random/boot_id", "/sys/fs/selinux/enforce",
    };

    /** Prop-key PREFIXES that are device-identity props we alias to the profile (Build.* family, radio,
     *  SoC, serial, bootloader, lock-state). Matches the domains in HookEntry.PROP_ALIASES + STATIC_PROPS. */
    private static final String[] SPOOFED_PROP_PREFIXES = {
        "ro.product.", "ro.build.", "ro.boot.", "ro.bootimage.", "ro.odm.build.", "ro.system.build.",
        "ro.system_ext.build.", "ro.vendor.build.", "ro.bootloader", "ro.hardware", "ro.board.",
        "ro.serialno", "ro.soc.", "gsm.version.baseband", "ril.baseband", "os.version",
    };

    public static State of(String verb, String target) {
        if (target == null || target.isEmpty()) return State.UNKNOWN;
        if ("prop".equals(verb)) {
            // Generic/non-identity props (vendor debug, egl, cache keys, sys.*, ABI list, arch) — not
            // device-identifying, so "real" is fine (we deliberately don't spoof them). Checked FIRST so a
            // universal prop like ro.product.cpu.abilist64 isn't mis-flagged by the ro.product. prefix.
            if (target.startsWith("vendor.") || target.startsWith("debug.") || target.startsWith("sys.")
                    || target.startsWith("cache_key.") || target.equals("ro.arch")
                    || target.startsWith("ro.product.cpu.abilist")) return State.REAL;
            for (String pre : SPOOFED_PROP_PREFIXES)
                if (target.equals(pre) || target.startsWith(pre)) return State.SPOOFED;
            return State.UNKNOWN;
        }
        // Files: exact spoofed-file matches, plus the per-core cpu_capacity family.
        for (String f : SPOOFED_FILES) if (target.equals(f)) return State.SPOOFED;
        if (target.startsWith("/sys/devices/system/cpu/cpu") && target.endsWith("/cpu_capacity"))
            return State.SPOOFED;
        // A read of the app's OWN /proc or a system lib is not device-identifying.
        if (target.startsWith("/proc/self/") || target.startsWith("/system/")
                || target.startsWith("/apex/") || target.startsWith("/vendor/lib")) return State.REAL;
        return State.UNKNOWN;
    }
}
