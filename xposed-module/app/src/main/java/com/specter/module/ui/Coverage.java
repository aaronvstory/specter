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
 * over-claim siblings like ro.hardware.gralloc or ro.build.date.utc that we don't touch).
 *
 * <p>The other states answer "does this read even MATTER?". A trace is ~99% reads that carry zero device
 * identity — font files every app stats to render text, libc/framework loads, the app's own /proc, universal
 * arm64 constants. Those are {@link State#NOISE}: they must NOT be counted or shown as if a value leaked
 * (a screen reading "256 real" makes a working spoof look broken). {@link State#LEAK} is the opposite and is
 * the only alarm: a read that IS device-identifying and we are NOT spoofing. {@link State#UNKNOWN} is an
 * honest "we can't classify this" — shown, but never claimed as either safe or leaking.
 */
public final class Coverage {
    private Coverage() {}

    /** SPOOFED = we return a fake value. LEAK = identifying and unspoofed (the alarm). NOISE = read carries
     *  no device identity (collapse it). UNKNOWN = unclassified (show, claim nothing). */
    public enum State { SPOOFED, LEAK, NOISE, UNKNOWN }

    /** EXACT prop keys Specter spoofs on EVERY profile — mirror of HookEntry.PROP_ALIASES column 0 +
     *  STATIC_PROPS + the deferred (sdk/first_api_level) and derived (build.tags/type) keys. Keep in sync.
     *
     *  <p>Only keys aliased UNCONDITIONALLY belong here. {@code ro.boot.warranty_bit} / {@code
     *  ro.warranty_bit} are deliberately absent: HookEntry only sets them when the profile's manufacturer is
     *  Samsung, so listing them would claim "faked" on every non-Samsung profile while the real value is
     *  what the app actually reads. They stay UNKNOWN — an honest unknown, not a false win. Any key added
     *  here must be verified unconditional in HookEntry first. */
    private static final Set<String> SPOOFED_PROPS = new HashSet<>(Arrays.asList(
        // radio / kernel / soc
        "gsm.version.baseband", "ril.baseband", "os.version", "ro.board.platform", "ro.soc.model",
        "ro.chipname", "ro.mediatek.platform",
        // boot.* identity + lock state
        "ro.boot.bootloader", "ro.boot.hardware", "ro.boot.hardware.platform", "ro.boot.serialno",
        "ro.boot.flash.locked", "ro.boot.vbmeta.device_state", "ro.boot.verifiedbootstate",
        "ro.boot.veritymode",
        // build.* + fingerprints (all partitions)
        "ro.bootimage.build.fingerprint", "ro.bootloader", "ro.build.description", "ro.build.display.id",
        "ro.build.fingerprint", "ro.build.flavor", "ro.build.host", "ro.build.id", "ro.build.product",
        "ro.build.version.incremental", "ro.build.version.release", "ro.build.version.security_patch",
        "ro.build.version.sdk", "ro.build.tags", "ro.build.type", "ro.debuggable", "ro.secure",
        "ro.odm.build.fingerprint", "ro.product.build.fingerprint",
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

    /** Props proven non-identifying, key by key. Sourced from the audited Cash App trace on the Pixel 4a:
     *  9 of these return an EMPTY string on the device (the prop doesn't exist), and the rest are universal
     *  constants identical across every device in the pool. Deliberately an EXACT allowlist rather than a
     *  namespace prefix — {@code ro.hardware.*} for instance contains gralloc/egl/vulkan, which name the GPU
     *  vendor, so a prefix pass there would hide a genuine hardware signal. */
    private static final Set<String> NOISE_PROPS = new HashSet<>(Arrays.asList(
        // Universal arm64 constants — same on every device we can claim to be.
        "ro.arch", "ro.product.cpu.abilist", "ro.product.cpu.abilist32", "ro.product.cpu.abilist64",
        "ro.product.cpu.abi", "ro.build.version.codename", "ro.build.version.preview_sdk",
        // Empty on the real fleet devices (audited) — reading them returns "" regardless of device.
        "ro.boringcrypto.hwrand", "ro.vendor.redirect_socket_calls", "ro.input.resampling",
        "vendor.gralloc.use_system_heap_for_sensors", "ro.hardware.gralloc", "ro.vendor.graphics.memory",
        "vendor.gralloc.disable_ubwc", "vendor.gralloc.disable_ahardware_buffer",
        // Runtime/boot polling every app does; no device identity.
        "sys.boot_completed", "sys.usb.config", "heapprofd.enable",
        // Input/media tuning flags — behaviour knobs, not hardware identity.
        "ro.input.noresample", "persist.input.velocitytracker.strategy",
        "media.metrics.enabled", "media.metrics.log_interval"
    ));

    /** Reads that ARE device-identifying but that we do NOT spoof — the only thing worth alarming about.
     *  Keep this list honest: adding a key here says "a fingerprinter can tell this device apart by it".
     *  A key must be removed from here the moment it's added to {@link #SPOOFED_PROPS}. */
    private static final Set<String> LEAK_PROPS = new HashSet<>(Arrays.asList(
        "ro.boot.bootdevice",        // eMMC/UFS controller address — per-model constant
        "ro.boot.boot_devices",
        // Build timestamps: these must MATCH the claimed fingerprint. We rewrite the fingerprint but not
        // these, so a reader that compares them sees the real build's date against a spoofed build — a
        // contradiction that's worse than either value alone.
        "ro.build.date.utc", "ro.build.date", "ro.vendor.build.date.utc",
        "ro.build.user",             // Android build-farm user — per-vendor, part of the real build identity
        "ro.build.version.base_os",
        // Expected baseband/bootloader for the real build — contradicts the spoofed radio/bootloader values.
        "ro.build.expect.baseband", "ro.build.expect.bootloader"
    ));

    /** File/sysfs reads that ARE device-identifying but not redirected. */
    private static final Set<String> LEAK_FILES = new HashSet<>(Arrays.asList(
        "/sys/class/dmi/id/product_name", "/sys/firmware/devicetree/base/model",
        "/sys/devices/soc0/machine", "/sys/devices/soc0/soc_id", "/sys/devices/soc0/serial_number",
        "/proc/device-tree/model", "/proc/device-tree/compatible",
        "/sys/block/mmcblk0/device/cid", "/sys/block/mmcblk0/device/serial",
        "/sys/block/sda/device/vendor", "/sys/block/sda/device/model",
        // kernel release string — /proc/version IS redirected but this sibling isn't, so it contradicts it
        "/proc/sys/kernel/osrelease"
    ));

    /** Classify a parsed row. Same as {@link #of(String, String)}, memoised on the row so a render pass
     *  (summary loop + one pass per group) classifies each row once instead of four times. */
    public static State of(TraceParser.Row r) {
        if (r == null) return State.UNKNOWN;
        if (r.coverage == null) r.coverage = of(r.verb, r.target);
        return r.coverage;
    }

    public static State of(String verb, String target) {
        if (target == null || target.isEmpty()) return State.UNKNOWN;
        // A dlsym of an OpenGL/EGL entry point is the GL driver binding its own function table — 260 of them
        // in a single measured Cash App run, and not one carries device identity. (The GPU STRINGS the driver
        // then returns ARE identity, and those are spoofed natively; looking up the function is not.)
        if ("dlsym".equals(verb)) {
            return target.startsWith("gl") || target.startsWith("egl") || target.startsWith("__")
                    ? State.NOISE : State.UNKNOWN;
        }
        // GL string queries (vendor/renderer/version/extensions). A measured Cash App run emitted ~400 of
        // these — the app walking the GPU extension list one index at a time. They are NOT noise: the GPU
        // string set fingerprints the hardware, which is exactly why the native layer rewrites it. These are
        // genuine SPOOFED wins the screen should be claiming, not clutter to hide.
        if (isGlStringQuery(verb, target)) return State.SPOOFED;
        if ("prop".equals(verb)) {
            if (SPOOFED_PROPS.contains(target)) return State.SPOOFED;
            if (LEAK_PROPS.contains(target)) return State.LEAK;
            if (NOISE_PROPS.contains(target)) return State.NOISE;
            // ONE namespace is safe wholesale: the framework's own IPC caches (cache_key.*), which are
            // invalidation tokens, not device data. Everything else — vendor.*, sys.*, debug.*,
            // ro.hardware.* (gralloc/egl/vulkan name the GPU vendor), persist.media.* — is allowlisted
            // key-by-key above; a whole-namespace pass would hide a real hardware signal (codex).
            if (target.startsWith("cache_key.")) return State.NOISE;
            return State.UNKNOWN;
        }
        // Files: exact spoofed-file matches, plus the strict per-core cpu_capacity / cpufreq / topology family.
        if (SPOOFED_FILES.contains(target)) return State.SPOOFED;
        if (isCpuCapacityPath(target)) return State.SPOOFED;
        if (isPerCoreCpuPath(target)) return State.SPOOFED;
        if (LEAK_FILES.contains(target)) return State.LEAK;
        if (isNonIdentifyingPath(target)) return State.NOISE;
        return State.UNKNOWN;
    }

    /** The four GL string enums the native layer rewrites — GL_VENDOR (0x1f00), GL_RENDERER (0x1f01),
     *  GL_VERSION (0x1f02) and GL_EXTENSIONS (0x1f03). Traced as {@code "glGetString <enum>"} or, for the
     *  ES3 indexed extension walk, {@code "glGetStringi 0x1f03 <index>"}. main.cpp's my_glGetString /
     *  my_glGetStringi return the spoofed GPU strings for all of them, so these reads ARE covered — the
     *  screen was previously reporting ~400 genuine wins as unclassified. Matched strictly on the GL verb
     *  plus an exact enum so no other GL query is claimed. */
    static boolean isGlStringQuery(String verb, String target) {
        boolean indexed = "glGetStringi".equals(verb);
        if (!indexed && !"glGetString".equals(verb)) return false;
        if (target == null) return false;
        int sp = target.indexOf(' ');
        String glEnum = sp < 0 ? target : target.substring(0, sp);
        // GL_EXTENSIONS via the LEGACY (non-indexed) glGetString falls back to the real list unless BOTH
        // glGetStringi and glGetIntegerv hooked successfully (main.cpp my_glGetString) — and the trace line
        // is emitted before that branch, so we can't tell from the log which happened. Don't claim it.
        if (glEnum.equals("0x1f03") && !indexed) return false;
        if (!glEnum.equals("0x1f00") && !glEnum.equals("0x1f01")
                && !glEnum.equals("0x1f02") && !glEnum.equals("0x1f03")) return false;
        if (sp < 0) return true;
        // A trailing token is only expected for the indexed walk, and must be the loop index.
        String idx = target.substring(sp + 1);
        if (!indexed || idx.isEmpty()) return false;
        for (int i = 0; i < idx.length(); i++) if (!Character.isDigit(idx.charAt(i))) return false;
        return true;
    }

    /** A path whose read says nothing about WHICH device this is. Dominates every real trace: font files
     *  (237 of 256 "real" reads in the audited Cash App run), the linker's lib loads, and per-process
     *  scheduler bookkeeping. Checked AFTER the spoofed matchers so a redirected path under /sys or /proc
     *  still reports SPOOFED.
     *
     *  <p>Deliberately NARROW. Whole-tree prefixes are NOT safe to call noise (codex): {@code /vendor/lib*}
     *  names the SoC's driver set, {@code /product} and {@code /system_ext} carry per-device overlays, and
     *  {@code /apex} exposes module/WebView versions. Anything not proven harmless stays UNKNOWN — an honest
     *  "we don't know" is fine; a wrong "harmless" hides a real leak, which is the one thing this screen
     *  must never do. */
    static boolean isNonIdentifyingPath(String path) {
        if (path == null || path.isEmpty()) return false;
        // Font files — read to RENDER text. Every app stats these; they carry no device identity.
        if (path.startsWith("/system/fonts/") || path.startsWith("/product/fonts/")) {
            return path.endsWith(".ttf") || path.endsWith(".otf") || path.endsWith(".ttc")
                    || path.equals("/system/fonts/") || path.equals("/product/fonts/");
        }
        // NOTE: the CA trust store (/system/etc/security/cacerts/…) is deliberately NOT listed here. Its
        // CONTENTS vary by Android build, Conscrypt update and OEM, so which cert hashes are present is
        // itself a software-configuration fingerprint (codex). It stays UNKNOWN.
        // Font CONFIG (the fontconfig xml the text stack parses on startup).
        if (path.equals("/system/etc/fonts.xml") || path.equals("/system/etc/font_fallback.xml")
                || path.equals("/product/etc/fonts_customization.xml")) return true;
        // The linker loading a STOCK AOSP artifact. Deliberately excludes /vendor and /system_ext (codex):
        // a vendor library FILENAME names the SoC outright — "gralloc.msm8998.so" is the chipset in plain
        // text — and OEM packages under /system_ext identify the vendor's build. Only the AOSP framework and
        // apex trees, whose artifact names are the same on every Android of that release, are harmless.
        if (path.endsWith(".so") || path.endsWith(".oat") || path.endsWith(".vdex") || path.endsWith(".art")
                || path.endsWith(".jar") || path.endsWith(".odex") || path.endsWith(".apk")) {
            return path.startsWith("/system/lib/") || path.startsWith("/system/lib64/")
                    || path.startsWith("/system/framework/") || path.startsWith("/apex/com.android.");
        }
        // Per-process scheduler/bookkeeping. NOT /proc/self/maps or status — those are injection/tamper
        // detection surfaces (TracerPid, RWX hook pages), so they stay UNKNOWN and visible.
        return isRuntimeProcPath(path);
    }

    /** Per-process runtime bookkeeping under {@code /proc/self/} or {@code /proc/<digits>/} — the leaves that
     *  describe SCHEDULING, not the device or the process's security state. The 124 "unknown" rows in the
     *  audited trace were all timerslack/comm.
     *
     *  <p>Strictly leaf-matched. {@code maps}, {@code status}, {@code mountinfo}, {@code attr/*}, {@code fd/*}
     *  and the per-thread {@code task/<tid>/{maps,stack,syscall,wchan}} files are EXCLUDED: anti-tamper code
     *  reads exactly those, so calling them harmless would hide the most interesting reads on the screen. */
    static boolean isRuntimeProcPath(String path) {
        final String pre = "/proc/";
        if (!path.startsWith(pre)) return false;
        int slash = path.indexOf('/', pre.length());
        if (slash < 0) return false;
        String who = path.substring(pre.length(), slash);
        // "<pid>" is TraceParser's collapsed form (many thread ids rendered as one row).
        if (!who.equals("self") && !who.equals("<pid>")) {
            if (who.isEmpty()) return false;
            for (int i = 0; i < who.length(); i++) if (!Character.isDigit(who.charAt(i))) return false;
        }
        String leaf = path.substring(slash + 1);
        // A per-thread path: allow only the same safe leaves after task/<tid>/.
        if (leaf.startsWith("task/")) {
            int t = leaf.indexOf('/', "task/".length());
            if (t < 0) return false;
            String tid = leaf.substring("task/".length(), t);
            if (tid.isEmpty()) return false;
            for (int i = 0; i < tid.length(); i++) if (!Character.isDigit(tid.charAt(i))) return false;
            leaf = leaf.substring(t + 1);
        }
        return leaf.equals("timerslack_ns") || leaf.equals("oom_score_adj") || leaf.equals("oom_adj")
                || leaf.equals("sched") || leaf.equals("cgroup");
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
