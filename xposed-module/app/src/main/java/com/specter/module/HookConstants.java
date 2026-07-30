package com.specter.module;

/**
 * Plain constants shared between the Xposed hook entry point ({@link HookEntry}) and the standalone UI app.
 * Deliberately has ZERO Xposed imports: {@code HookEntry implements IXposedHookLoadPackage}, so merely
 * loading HookEntry's class (to read one static field) forces the JVM to resolve that Xposed interface —
 * which throws NoClassDefFoundError in a process LSPosed hasn't injected the Xposed stub into (a scope-load
 * race, a replug/reboot hiccup, or simply com.specter's own process when it isn't self-hooked). The UI
 * reads these constants for a heartbeat comparison; it must never crash just because that class-load can't
 * safely happen right now. HookEntry itself also reads from here, so there's exactly one source of truth.
 */
public final class HookConstants {
    private HookConstants() {}

    // where the push .bat drops per-app profiles
    static final String PROFILE_DIR = "/data/local/tmp/specter/";
    /** Public view of the profile dir for other module classes (e.g. PmsHook's framework heartbeat). */
    public static final String HEARTBEAT_DIR_PARENT = PROFILE_DIR;
    /** Where the framework gate (PmsHook, in system_server) drops its boot heartbeat — system-writable, unlike
     *  the root-owned profile dir; the UI reads it via su. Public so both PmsHook and the UI reference one path. */
    public static final String FRAMEWORK_HB_PATH = "/data/system/specter_hb_framework";
    /** This module's version, compiled in — the attestation heartbeat carries it so the status screen can
     *  reject a heartbeat written by OLD module code still loaded in a process after an APK update. */
    public static final String MODULE_VERSION = safeVersion();
    private static String safeVersion() {
        try { return com.specter.module.BuildConfig.VERSION_NAME; } catch (Throwable t) { return "?"; }
    }
}
