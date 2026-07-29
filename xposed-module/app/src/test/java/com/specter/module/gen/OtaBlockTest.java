package com.specter.module.gen;

/** Plain-JVM tests for the OTA-block Magisk-module builder (hosts overlay + live layers, atomic install). */
public class OtaBlockTest {
    static int passed = 0, failed = 0;
    static void check(boolean cond, String name) {
        if (cond) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        // module.prop: distinct id so it installs/removes independently of the other Specter Magisk modules.
        String prop = OtaBlock.moduleProp();
        check(prop.contains("id=specter_ota_block"), "module.prop id");
        check(prop.contains("versionCode="), "module.prop has versionCode");
        check(prop.contains("name=") && prop.contains("description="), "module.prop name+desc");

        // hosts overlay: keeps the localhost lines + one 127.0.0.1 line per OTA CDN host.
        String hosts = OtaBlock.hostsFile();
        check(hosts.contains("127.0.0.1 localhost"), "hosts keeps localhost");
        check(hosts.contains("::1 localhost"), "hosts keeps ipv6 localhost");
        for (String h : OtaBlock.BLACKHOLE_HOSTS) {
            check(hosts.contains("127.0.0.1 " + h), "hosts blackholes " + h);
        }

        // install: builds the module in a STAGING dir, writes module.prop + system/etc/hosts via heredoc, sets
        // perms, atomically mv's into place with a .bak rollback, THEN applies the live layers.
        String ins = OtaBlock.buildInstallScript();
        check(ins.contains("set -e"), "install aborts on any failing (module-build) step");
        check(ins.contains(".stage/system/etc/hosts"), "install writes the hosts overlay into staging");
        check(ins.contains(".stage/module.prop"), "install writes module.prop into staging");
        check(ins.contains("mkdir -p /data/adb/modules/specter_ota_block.stage/system/etc"),
                "install makes the system/etc path in staging");
        check(ins.contains("mv /data/adb/modules/specter_ota_block.stage /data/adb/modules/specter_ota_block"),
                "install mv's staging into place");
        check(ins.contains(".bak") && ins.contains("mv $BAK"), "install backs up + rolls back on mv failure");
        // the backup-move is FATAL (no `|| true`) so a failed move-aside can't let staging nest in the old dir
        check(ins.contains("if [ -d /data/adb/modules/specter_ota_block ]; then mv /data/adb/modules/specter_ota_block $BAK; fi"),
                "backup move is fatal (no || true swallow)");
        check(ins.indexOf("mv ") > ins.indexOf(".stage/module.prop"), "mv happens after files are staged");
        check(ins.contains("<<'SPECTER_EOF'"), "install uses a quoted heredoc (no premature expansion)");
        // live layers: framework auto-update off, staged payload purged, GMS update components disabled
        check(ins.contains("settings put global ota_disable_automatic_update 1"), "install sets ota_disable flag");
        check(ins.contains("rm -f /data/ota_package/*"), "install purges staged payload");
        for (String c : OtaBlock.GMS_UPDATE_COMPONENTS) {
            check(ins.contains("pm disable " + c), "install disables " + c);
        }
        // the live layers are best-effort (|| true) so a device missing one doesn't fail the whole install...
        check(ins.contains("ota_disable_automatic_update 1 2>/dev/null || true"), "settings layer is best-effort");
        // ...but they run AFTER the atomic module swap (module in place first, then live tweaks)
        check(ins.indexOf("settings put global ota_disable_automatic_update 1") > ins.indexOf("mv $BAK") ||
              ins.indexOf("settings put global ota_disable_automatic_update 1") > ins.lastIndexOf("mv /data/adb/modules/specter_ota_block.stage"),
              "live layers run after the module swap");

        // uninstall: removes the module + re-enables everything, tolerant of already-absent state.
        String uni = OtaBlock.buildUninstallScript();
        check(uni.contains("rm -rf /data/adb/modules/specter_ota_block"), "uninstall removes the module dir");
        check(uni.contains("settings put global ota_disable_automatic_update 0"), "uninstall clears the ota_disable flag");
        for (String c : OtaBlock.GMS_UPDATE_COMPONENTS) {
            check(uni.contains("pm enable " + c), "uninstall re-enables " + c);
        }
        check(uni.contains("|| true"), "uninstall tolerates already-absent state");
        // uninstall VERIFIES the module is gone (a masked rm failure would else report false success)
        check(uni.contains("if [ -d /data/adb/modules/specter_ota_block ]; then echo rm_failed >&2; exit 5; fi"),
                "uninstall verifies the module dir was actually removed");
        // uninstall must remove the module BEFORE (or independent of) re-enabling — no ordering trap, but the
        // module removal is what drops the hosts overlay on reboot.
        check(uni.indexOf("rm -rf /data/adb/modules/specter_ota_block") < uni.indexOf("pm enable"),
                "uninstall removes module before re-enabling components");

        System.out.println("OtaBlockTest: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
