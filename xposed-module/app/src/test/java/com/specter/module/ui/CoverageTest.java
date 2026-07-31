package com.specter.module.ui;

/** JVM self-test for Coverage: identity props/files -> SPOOFED, generic -> NOISE, unknown -> UNKNOWN. */
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

        // Generic/non-identity props -> NOISE (we deliberately don't spoof)
        // vendor.debug.* is NOT blanket-noise: the EGL/driver keys under it name the GPU stack. Only the
        // audited keys are allowlisted; the rest stay honestly UNKNOWN (codex).
        eq(Coverage.of("prop", "vendor.debug.egl.swapinterval"), Coverage.State.UNKNOWN, "vendor.debug.egl (GPU stack)");
        eq(Coverage.of("prop", "debug.hwui.renderer"), Coverage.State.UNKNOWN, "debug.hwui (names the renderer)");
        eq(Coverage.of("prop", "cache_key.telephony"), Coverage.State.NOISE, "cache_key.* (IPC token)");
        eq(Coverage.of("prop", "ro.arch"), Coverage.State.NOISE, "ro.arch (universal)");
        eq(Coverage.of("prop", "ro.product.cpu.abilist64"), Coverage.State.NOISE, "abilist (universal)");
        eq(Coverage.of("prop", "sys.boot_completed"), Coverage.State.NOISE, "sys.*");
        // False-positive guards: props UNDER a spoofed prefix that we DON'T alias must read NOISE, not
        // "spoofed" (codex). ro.hardware.chipname IS aliased; ro.hardware.gralloc is NOT.
        // egl/vulkan aliased to gpu_hw (were leaking the real GPU vendor); gralloc left REAL (empty on real devices)
        eq(Coverage.of("prop", "ro.hardware.egl"), Coverage.State.SPOOFED, "ro.hardware.egl (aliased to gpu_hw)");
        eq(Coverage.of("prop", "ro.hardware.vulkan"), Coverage.State.SPOOFED, "ro.hardware.vulkan (aliased)");
        eq(Coverage.of("prop", "ro.hardware.gralloc"), Coverage.State.NOISE, "ro.hardware.gralloc (NOT aliased, empty on real)");
        eq(Coverage.of("prop", "ro.hardware.chipname"), Coverage.State.SPOOFED, "ro.hardware.chipname (aliased)");
        eq(Coverage.of("prop", "ro.hardware"), Coverage.State.SPOOFED, "ro.hardware (aliased)");
        eq(Coverage.of("prop", "ro.build.version.codename"), Coverage.State.NOISE, "codename (universal REL)");
        eq(Coverage.of("prop", "ro.build.version.preview_sdk"), Coverage.State.NOISE, "preview_sdk (universal 0)");
        // touch/input tuning props are not device-identifying -> NOISE (were falling through to UNKNOWN)
        eq(Coverage.of("prop", "ro.input.resampling"), Coverage.State.NOISE, "ro.input.* (input tuning)");
        // generic non-identity flags (audited from the Cash trace) — REAL, not UNKNOWN
        eq(Coverage.of("prop", "media.metrics.enabled"), Coverage.State.NOISE, "media.metrics (generic)");
        eq(Coverage.of("prop", "ro.vendor.graphics.memory"), Coverage.State.NOISE, "graphics.memory (empty on fleet)");
        eq(Coverage.of("prop", "ro.vendor.redirect_socket_calls"), Coverage.State.NOISE, "redirect_socket_calls (empty)");
        eq(Coverage.of("prop", "persist.input.velocitytracker.strategy"), Coverage.State.NOISE, "persist.input.*");

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

        // Non-identity files -> NOISE (a plain library LOAD; /proc/self/maps is asserted UNKNOWN below)
        eq(Coverage.of("open", "/system/lib64/libc.so"), Coverage.State.NOISE, "system lib");

        // Codex prefix-over-match guards: keys under a spoofed namespace we DON'T alias must NOT be SPOOFED.
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

        // --- NOISE: the ~99% of a real trace that carries no device identity. These must NEVER be counted
        // as leaks (a working spoof showing "256 real" is what made users think the app was broken).
        eq(Coverage.of("stat", "/system/fonts/Roboto-Regular.ttf"), Coverage.State.NOISE, "font ttf");
        eq(Coverage.of("stat", "/system/fonts/NotoColorEmoji.ttf"), Coverage.State.NOISE, "font emoji");
        eq(Coverage.of("open", "/system/etc/fonts.xml"), Coverage.State.NOISE, "font config");
        eq(Coverage.of("open", "/apex/com.android.art/lib64/libart.so"), Coverage.State.NOISE, "apex lib load");
        eq(Coverage.of("open", "/system/lib64/libc.so"), Coverage.State.NOISE, "system lib load");
        eq(Coverage.of("fstatat", "/system/framework/android.test.base.jar"), Coverage.State.NOISE, "framework jar");
        eq(Coverage.of("fstatat", "/system/framework/oat/arm64/org.apache.http.legacy.odex"), Coverage.State.NOISE, "odex");
        // GL/EGL entry-point binding — 260 of these in a measured Cash App run, zero identity.
        eq(Coverage.of("dlsym", "glDrawArraysInstanced"), Coverage.State.NOISE, "gl dlsym");
        eq(Coverage.of("dlsym", "eglSetBlobCacheFuncsANDROID"), Coverage.State.NOISE, "egl dlsym");
        eq(Coverage.of("dlsym", "__system_property_read_callback"), Coverage.State.NOISE, "libc internal dlsym");
        // but an arbitrary symbol lookup is not automatically harmless
        eq(Coverage.of("dlsym", "getSerialNumber"), Coverage.State.UNKNOWN, "unknown symbol stays UNKNOWN");
        // glGetStringi(GL_EXTENSIONS=0x1f03, i) — the ES3 indexed extension walk (~100 rows in a measured
        // Cash App run). The native layer rewrites the extension list, so these are a genuine SPOOFED win,
        // NOT noise: the trace was previously under-reporting them as unclassified.
        eq(Coverage.of("glGetStringi", "0x1f03 82"), Coverage.State.SPOOFED, "indexed GL_EXTENSIONS walk");
        eq(Coverage.of("glGetString", "0x1f03"), Coverage.State.SPOOFED, "legacy GL_EXTENSIONS read");
        eq(Coverage.of("glGetString", "0x1f00"), Coverage.State.SPOOFED, "GL_VENDOR");
        eq(Coverage.of("glGetString", "0x1f01"), Coverage.State.SPOOFED, "GL_RENDERER");
        eq(Coverage.of("glGetString", "0x1f02"), Coverage.State.SPOOFED, "GL_VERSION");
        eq(Coverage.of("glGetString", "0x8b8c"), Coverage.State.UNKNOWN, "an UNSPOOFED GL enum is not claimed");
        eq(Coverage.of("glGetString", "0x1f03 5"), Coverage.State.UNKNOWN, "non-indexed verb takes no index");
        eq(Coverage.of("glGetStringi", "0x1f0399 1"), Coverage.State.UNKNOWN, "enum prefix must not over-match");
        eq(Coverage.of("glGetStringi", "0x1f03 x"), Coverage.State.UNKNOWN, "index must be digits");
        eq(Coverage.of("open", "0x1f03 82"), Coverage.State.UNKNOWN, "a hex-looking FILE target is not a GL query");
        // The CA trust store is NOT noise: its contents vary by build/Conscrypt/OEM, so which certs are
        // present is itself a software fingerprint (codex).
        eq(Coverage.of("open", "/system/etc/security/cacerts/fd08c599.0"), Coverage.State.UNKNOWN, "cacert stays visible");
        // A VENDOR library filename names the SoC outright — never call it harmless.
        eq(Coverage.of("stat", "/vendor/lib64/hw/gralloc.msm8998.so"), Coverage.State.UNKNOWN, "vendor .so names the SoC");
        eq(Coverage.of("open", "/system/lib64/libc.so"), Coverage.State.NOISE, "AOSP lib load is noise");
        // runtime per-process scheduler bookkeeping (the 124 "unknown" rows in the audit)
        eq(Coverage.of("open", "/proc/12345/timerslack_ns"), Coverage.State.NOISE, "timerslack");
        eq(Coverage.of("open", "/proc/self/timerslack_ns"), Coverage.State.NOISE, "self timerslack");
        eq(Coverage.of("open", "/proc/9/oom_score_adj"), Coverage.State.NOISE, "oom_score_adj");
        eq(Coverage.of("open", "/proc/4321/task/4322/cgroup"), Coverage.State.NOISE, "per-thread cgroup");

        // --- NOISE must stay NARROW (codex): a whole-tree prefix would hide genuine hardware signals.
        // /vendor/lib* names the SoC's drivers; /product + /system_ext carry per-device overlays.
        eq(Coverage.of("stat", "/vendor/lib64/hw"), Coverage.State.UNKNOWN, "vendor driver DIR is not noise");
        eq(Coverage.of("stat", "/system/framework"), Coverage.State.UNKNOWN, "framework dir not auto-noise");
        eq(Coverage.of("open", "/product/etc/build.prop"), Coverage.State.UNKNOWN, "/product not blanket noise");
        // /proc/self/maps + status are injection/tamper-detection surfaces — never call them harmless.
        eq(Coverage.of("open", "/proc/self/maps"), Coverage.State.UNKNOWN, "self maps is NOT noise");
        eq(Coverage.of("open", "/proc/self/status"), Coverage.State.UNKNOWN, "self status (TracerPid) NOT noise");
        eq(Coverage.of("open", "/proc/123/task/456/maps"), Coverage.State.UNKNOWN, "per-thread maps NOT noise");
        eq(Coverage.of("open", "/proc/123/comm"), Coverage.State.UNKNOWN, "thread name NOT noise");
        eq(Coverage.of("open", "/proc/123/cmdline"), Coverage.State.UNKNOWN, "app enumeration NOT noise");
        // Prop namespaces are allowlisted key-by-key, not by prefix.
        eq(Coverage.of("prop", "vendor.some.unknown.key"), Coverage.State.UNKNOWN, "vendor.* not blanket noise");
        eq(Coverage.of("prop", "sys.some.unknown.key"), Coverage.State.UNKNOWN, "sys.* not blanket noise");
        eq(Coverage.of("prop", "persist.media.unknown"), Coverage.State.UNKNOWN, "persist.media.* not blanket");

        // Guard: NOISE must not swallow a SPOOFED path that happens to live under /proc or /sys.
        eq(Coverage.of("open", "/proc/cpuinfo"), Coverage.State.SPOOFED, "cpuinfo still spoofed");
        eq(Coverage.of("open", "/proc/sys/kernel/random/boot_id"), Coverage.State.SPOOFED, "boot_id still spoofed");
        // Guard: a NON-digit /proc segment is not runtime bookkeeping.
        eq(Coverage.of("open", "/proc/net/arp"), Coverage.State.UNKNOWN, "/proc/net not noise");

        // --- LEAK: identifying and NOT spoofed — the only alarm the screen should raise.
        eq(Coverage.of("prop", "ro.build.date.utc"), Coverage.State.LEAK, "build.date.utc leaks");
        eq(Coverage.of("prop", "ro.boot.bootdevice"), Coverage.State.LEAK, "bootdevice leaks");
        eq(Coverage.of("prop", "ro.build.user"), Coverage.State.LEAK, "build.user leaks");
        eq(Coverage.of("prop", "ro.build.expect.baseband"), Coverage.State.LEAK, "expect.baseband leaks");
        eq(Coverage.of("open", "/sys/devices/soc0/machine"), Coverage.State.LEAK, "soc0 machine leaks");
        eq(Coverage.of("open", "/proc/sys/kernel/osrelease"), Coverage.State.LEAK, "osrelease leaks");
        // Demoted to UNKNOWN (codex): boot-slot state and hostname aren't reliable device identifiers, and a
        // false alarm costs the screen its credibility just as much as a missed leak.
        eq(Coverage.of("prop", "ro.boot.slot_suffix"), Coverage.State.UNKNOWN, "slot_suffix is boot state");
        eq(Coverage.of("open", "/proc/sys/kernel/hostname"), Coverage.State.UNKNOWN, "hostname is generic");
        // SPOOFED and LEAK must stay disjoint — a key in both sets would mean the alarm contradicts coverage.
        for (String k : new String[]{"ro.build.date.utc", "ro.boot.bootdevice", "ro.build.user",
                "ro.build.version.base_os", "ro.build.expect.bootloader"}) {
            eq(Coverage.of("prop", k), Coverage.State.LEAK, "disjoint: " + k);
        }

        if (fails == 0) System.out.println("ALL PASS (Coverage)");
        else { System.out.println(fails + " FAILURE(S)"); System.exit(1); }
    }
}
