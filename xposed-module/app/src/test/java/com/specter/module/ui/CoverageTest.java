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

        // Generic/non-identity props -> REAL (we deliberately don't spoof)
        eq(Coverage.of("prop", "vendor.debug.egl.swapinterval"), Coverage.State.REAL, "vendor.debug");
        eq(Coverage.of("prop", "ro.arch"), Coverage.State.REAL, "ro.arch (universal)");
        eq(Coverage.of("prop", "ro.product.cpu.abilist64"), Coverage.State.REAL, "abilist (universal)");
        eq(Coverage.of("prop", "sys.boot_completed"), Coverage.State.REAL, "sys.*");

        // Spoofed files -> SPOOFED
        eq(Coverage.of("open", "/proc/cpuinfo"), Coverage.State.SPOOFED, "cpuinfo");
        eq(Coverage.of("open", "/proc/meminfo"), Coverage.State.SPOOFED, "meminfo");
        eq(Coverage.of("open", "/proc/version"), Coverage.State.SPOOFED, "version");
        eq(Coverage.of("open", "/sys/devices/system/cpu/cpu3/cpu_capacity"), Coverage.State.SPOOFED, "cpu_capacity");
        eq(Coverage.of("open", "/proc/mounts"), Coverage.State.SPOOFED, "mounts (filtered)");

        // Non-identity files -> REAL
        eq(Coverage.of("open", "/proc/self/maps"), Coverage.State.REAL, "self maps");
        eq(Coverage.of("open", "/system/lib64/libc.so"), Coverage.State.REAL, "system lib");

        // Unknowns -> UNKNOWN (never over-claim)
        eq(Coverage.of("prop", "some.random.prop"), Coverage.State.UNKNOWN, "unknown prop");
        eq(Coverage.of("stat", "/data/data/com.x/files/thing"), Coverage.State.UNKNOWN, "unknown file");
        eq(Coverage.of("prop", null), Coverage.State.UNKNOWN, "null");

        if (fails == 0) System.out.println("ALL PASS (Coverage)");
        else { System.out.println(fails + " FAILURE(S)"); System.exit(1); }
    }
}
