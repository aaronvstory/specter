package com.specter.module.ui;

/** JVM self-test for Coverage: identity props/files -> SPOOFED, generic -> REAL, unknown -> UNKNOWN. */
public final class CoverageTest {
    static int fails = 0;
    static void eq(Coverage.State got, Coverage.State want, String m) {
        if (got != want) { System.out.println("FAIL: " + m + " got=" + got + " want=" + want); fails++; }
    }

    public static void main(String[] args) {
        // Identity props -> SPOOFED
        eq(Coverage.of("prop", "ro.product.model"), Coverage.State.SPOOFED, "ro.product.model");
        eq(Coverage.of("prop", "ro.build.fingerprint"), Coverage.State.SPOOFED, "fingerprint");
        eq(Coverage.of("prop", "ro.boot.verifiedbootstate"), Coverage.State.SPOOFED, "verifiedboot");
        eq(Coverage.of("prop", "ro.serialno"), Coverage.State.SPOOFED, "serialno");
        eq(Coverage.of("prop", "gsm.version.baseband"), Coverage.State.SPOOFED, "baseband");
        eq(Coverage.of("prop", "ro.board.platform"), Coverage.State.SPOOFED, "board.platform");
        // SoC-codename siblings now aliased (v0.18.2) — the whole SoC-name set is coherent.
        eq(Coverage.of("prop", "ro.chipname"), Coverage.State.SPOOFED, "ro.chipname (aliased)");
        eq(Coverage.of("prop", "ro.mediatek.platform"), Coverage.State.SPOOFED, "ro.mediatek.platform (aliased)");

        // Generic/non-identity props -> REAL (we deliberately don't spoof)
        eq(Coverage.of("prop", "vendor.debug.egl.swapinterval"), Coverage.State.REAL, "vendor.debug");
        eq(Coverage.of("prop", "ro.arch"), Coverage.State.REAL, "ro.arch (universal)");
        eq(Coverage.of("prop", "ro.product.cpu.abilist64"), Coverage.State.REAL, "abilist (universal)");
        eq(Coverage.of("prop", "sys.boot_completed"), Coverage.State.REAL, "sys.*");
        // False-positive guards: props UNDER a spoofed prefix that we DON'T alias must read REAL, not
        // "spoofed" (codex). ro.hardware.chipname IS aliased; ro.hardware.gralloc is NOT.
        eq(Coverage.of("prop", "ro.hardware.gralloc"), Coverage.State.REAL, "ro.hardware.gralloc (not aliased)");
        eq(Coverage.of("prop", "ro.hardware.chipname"), Coverage.State.SPOOFED, "ro.hardware.chipname (aliased)");
        eq(Coverage.of("prop", "ro.hardware"), Coverage.State.SPOOFED, "ro.hardware (aliased)");
        eq(Coverage.of("prop", "ro.build.version.codename"), Coverage.State.REAL, "codename (universal REL)");
        eq(Coverage.of("prop", "ro.build.version.preview_sdk"), Coverage.State.REAL, "preview_sdk (universal 0)");
        // touch/input tuning props are not device-identifying -> REAL (were falling through to UNKNOWN)
        eq(Coverage.of("prop", "ro.input.resampling"), Coverage.State.REAL, "ro.input.* (input tuning)");
        eq(Coverage.of("prop", "persist.input.velocitytracker.strategy"), Coverage.State.REAL, "persist.input.*");

        // Spoofed files -> SPOOFED
        eq(Coverage.of("open", "/proc/cpuinfo"), Coverage.State.SPOOFED, "cpuinfo");
        eq(Coverage.of("open", "/proc/meminfo"), Coverage.State.SPOOFED, "meminfo");
        eq(Coverage.of("open", "/proc/version"), Coverage.State.SPOOFED, "version");
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu3/cpu_capacity"), Coverage.State.SPOOFED, "cpu_capacity");
        // per-core cpufreq + topology now redirected (the SD855-vs-SD845 coherence leak)
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu7/cpufreq/cpuinfo_max_freq"), Coverage.State.SPOOFED, "cpuinfo_max_freq");
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu0/cpufreq/scaling_min_freq"), Coverage.State.SPOOFED, "scaling_min_freq");
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu4/topology/physical_package_id"), Coverage.State.SPOOFED, "physical_package_id");
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu6/topology/core_siblings_list"), Coverage.State.SPOOFED, "core_siblings_list");
        eq(Coverage.of("open", "/sys/devices/system/cpu/online"), Coverage.State.SPOOFED, "cpu/online");
        eq(Coverage.of("open", "/sys/devices/system/cpu/possible"), Coverage.State.SPOOFED, "cpu/possible");
        // guard: a non-per-core cpu path (cpuidle) must NOT false-match the per-core matcher
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpuidle/current_driver"), Coverage.State.UNKNOWN, "cpuidle (not per-core)");
        eq(Coverage.of("open", "/proc/modules"), Coverage.State.SPOOFED, "/proc/modules (generic)");
        // full cache tree now spoofed (size + level + shared_cpu_list together)
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu0/cache/index2/shared_cpu_list"), Coverage.State.SPOOFED, "cache shared");
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu7/cache/index2/size"), Coverage.State.SPOOFED, "cache size");
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu0/cache/index0/level"), Coverage.State.SPOOFED, "cache level");
        // guard: cache 'type' / 'ways_of_associativity' are NOT spoofed (left real) — must not false-match
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu0/cache/index2/type"), Coverage.State.UNKNOWN, "cache type (not spoofed)");
        // guard: a NESTED path under index<K> must NOT false-match (codex) — only the exact leaf spoofs
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu0/cache/index2/anything/size"), Coverage.State.UNKNOWN, "nested cache path (not spoofed)");
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu0/cache/index2/coherency_line_size"), Coverage.State.UNKNOWN, "coherency_line_size (ends _size not /size)");
        eq(Coverage.of("open", "/proc/mounts"), Coverage.State.SPOOFED, "mounts (filtered)");

        // Non-identity files -> REAL
        eq(Coverage.of("open", "/proc/self/maps"), Coverage.State.REAL, "self maps");
        eq(Coverage.of("open", "/system/lib64/libc.so"), Coverage.State.REAL, "system lib");

        // Codex prefix-over-match guards: keys under a spoofed namespace we DON'T alias must NOT be SPOOFED.
        eq(Coverage.of("prop", "ro.build.date.utc"), Coverage.State.UNKNOWN, "ro.build.date.utc (not aliased)");
        eq(Coverage.of("prop", "ro.boot.slot_suffix"), Coverage.State.UNKNOWN, "ro.boot.slot_suffix (not aliased)");
        eq(Coverage.of("prop", "ro.board.foo"), Coverage.State.UNKNOWN, "ro.board.foo (only .platform aliased)");
        eq(Coverage.of("prop", "ro.bootloader.fake"), Coverage.State.UNKNOWN, "ro.bootloader.fake (exact only)");
        eq(Coverage.of("prop", "ro.serialno.foo"), Coverage.State.UNKNOWN, "ro.serialno.foo (exact only)");
        eq(Coverage.of("prop", "os.version.extra"), Coverage.State.UNKNOWN, "os.version.extra (exact only)");
        // Strict cpu_capacity: only cpu<digits> redirected; bogus paths are NOT spoofed.
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu999/cpu_capacity"), Coverage.State.SPOOFED, "cpu999 (digits ok)");
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpuXYZ/cpu_capacity"), Coverage.State.UNKNOWN, "cpuXYZ (non-digit)");
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu0/cache/cpu_capacity"), Coverage.State.UNKNOWN, "nested cache path");

        // Unknowns -> UNKNOWN (never over-claim)
        eq(Coverage.of("prop", "some.random.prop"), Coverage.State.UNKNOWN, "unknown prop");
        eq(Coverage.of("stat", "/data/data/com.x/files/thing"), Coverage.State.UNKNOWN, "unknown file");
        eq(Coverage.of("prop", null), Coverage.State.UNKNOWN, "null");

        if (fails == 0) System.out.println("ALL PASS (Coverage)");
        else { System.out.println(fails + " FAILURE(S)"); System.exit(1); }
    }
}
