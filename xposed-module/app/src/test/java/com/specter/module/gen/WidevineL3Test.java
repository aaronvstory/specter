package com.specter.module.gen;

/** Plain-JVM tests for the Widevine L1->L3 Magisk-module builder + install/uninstall behaviour. */
public class WidevineL3Test {
    static int passed = 0, failed = 0;
    static void check(boolean cond, String name) {
        if (cond) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        // module.prop: valid Magisk metadata with the distinct id (installed/removed independently of zygisk).
        String prop = WidevineL3.moduleProp();
        check(prop.contains("id=specter_widevine_l3"), "module.prop id");
        check(prop.contains("versionCode="), "module.prop has versionCode");
        check(prop.contains("name=") && prop.contains("description="), "module.prop name+desc");

        // post-fs-data.sh: bind-mounts the empty stub over BOTH vendor arch paths, each guarded by -f so a
        // device missing one arch is a no-op, not a failure.
        String pfd = WidevineL3.postFsDataScript();
        check(pfd.contains("mount -o bind"), "post-fs-data does a bind-mount");
        check(pfd.contains("/vendor/lib64/liboemcrypto.so"), "covers lib64 oemcrypto");
        check(pfd.contains("/vendor/lib/liboemcrypto.so"), "covers lib oemcrypto");
        check(pfd.contains("if [ -f \"/vendor/lib64/liboemcrypto.so\" ]"), "lib64 mount guarded by -f");
        check(pfd.contains("if [ -f \"/vendor/lib/liboemcrypto.so\" ]"), "lib mount guarded by -f");
        check(pfd.startsWith("#!/system/bin/sh"), "post-fs-data has a shebang");
        // the stub it mounts is the module's own file, resolved from MODDIR (Magisk-relative), not a fixed path
        check(pfd.contains("MODDIR=${0%/*}") && pfd.contains("$MODDIR/liboemcrypto.so"), "stub resolved from MODDIR");

        // install script: builds the module in a STAGING dir, atomically mv's it into place (never leaves a
        // half-written module for Magisk to load), writes all three files via heredoc, sets perms, applies the
        // mount LIVE, and refuses on a device with no oemcrypto to shadow.
        String ins = WidevineL3.buildInstallScript();
        check(ins.contains(".stage/module.prop"), "install writes module.prop into the staging dir");
        check(ins.contains(".stage/post-fs-data.sh"), "install writes post-fs-data.sh into staging");
        check(ins.contains(": > /data/adb/modules/specter_widevine_l3.stage/liboemcrypto.so"), "install truncates an EMPTY stub in staging");
        check(ins.contains("mv /data/adb/modules/specter_widevine_l3.stage /data/adb/modules/specter_widevine_l3"),
                "install mv's staging into place");
        // any prior module is moved to a .bak FIRST and restored if the rename fails (never left with no module)
        check(ins.contains(".bak") && ins.contains("mv $BAK"), "install backs up + rolls back the prior module on mv failure");
        // the mv (atomic swap) must come AFTER all files are written, and the live mount AFTER the mv
        check(ins.indexOf("mv ") > ins.indexOf(".stage/module.prop"), "mv happens after files are staged");
        // the LIVE mount is the LAST 'mount -o bind' (an earlier one lives inside the embedded post-fs-data.sh body)
        check(ins.lastIndexOf("mount -o bind") > ins.indexOf("mv "), "live mount happens after the module is in place");
        check(ins.contains("chmod 0755") && ins.contains("post-fs-data.sh"), "install makes post-fs-data executable");
        check(ins.contains("set -e"), "install aborts on any failing step");
        // refuse on a device with no oemcrypto (a 'success' there would be a lie)
        check(ins.contains("no_oemcrypto_on_device") && ins.contains("exit 3"), "install refuses when no oemcrypto exists");
        // heredoc bodies are quoted ('SPECTER_EOF') so $ / backticks inside the scripts aren't expanded at write time
        check(ins.contains("<<'SPECTER_EOF'"), "install uses a quoted heredoc (no premature expansion)");
        // reports whether the live mount actually took (vs reboot-needed) — success != silently-nothing
        check(ins.contains("mount | grep -q liboemcrypto.so"), "install verifies the live mount took");

        // uninstall script: drops the live mounts (so L1 returns without a reboot for those paths) then removes
        // the module dir. Never fails on an already-absent mount/module.
        String uni = WidevineL3.buildUninstallScript();
        check(uni.contains("umount \"/vendor/lib64/liboemcrypto.so\""), "uninstall umounts lib64");
        check(uni.contains("umount \"/vendor/lib/liboemcrypto.so\""), "uninstall umounts lib");
        check(uni.contains("rm -rf /data/adb/modules/specter_widevine_l3"), "uninstall removes the module dir");
        check(uni.contains("|| true"), "uninstall tolerates already-absent mount/module");
        // uninstall must umount BEFORE rm -rf, or the live mount lingers with no module backing it
        check(uni.indexOf("umount") < uni.indexOf("rm -rf"), "uninstall umounts before removing the dir");

        // install() surfaces a denied/failed su as a loud exception (never a silent no-op), via the exit code.
        boolean threw = false;
        try { WidevineL3.install((cmd, stdin) -> 1); } catch (WidevineL3.WidevineException e) { threw = true; }
        check(threw, "install throws on non-zero su exit");
        boolean ok = true;
        try { WidevineL3.install((cmd, stdin) -> 0); } catch (WidevineL3.WidevineException e) { ok = false; }
        check(ok, "install succeeds on zero exit");
        boolean uThrew = false;
        try { WidevineL3.uninstall((cmd, stdin) -> 7); } catch (WidevineL3.WidevineException e) { uThrew = true; }
        check(uThrew, "uninstall throws on non-zero su exit");
        // an exception from the shell (root not granted) is wrapped, not leaked raw
        boolean wrapped = false;
        try {
            WidevineL3.install((cmd, stdin) -> { throw new RuntimeException("no su"); });
        } catch (WidevineL3.WidevineException e) { wrapped = true; }
        check(wrapped, "install wraps a shell exception");

        System.out.println("WidevineL3: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
