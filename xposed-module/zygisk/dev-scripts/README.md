# dev-scripts — on-device install/test helpers for the Zygisk native layer

Manual helpers used while iterating on-device (Pixel 4, serial `9B151FFAZ00FPF`). Not part of the build.

- **`reinstall.sh`** — after `build-zygisk.sh`, copies the freshly built module tree to the device,
  installs it under `/data/adb/modules/specter_zygisk/` (via `su -M` mount-master), reboots, waits for
  boot, wakes the screen. Run from anywhere: `bash xposed-module/zygisk/dev-scripts/reinstall.sh`.
- **`spz_install.sh`** — the root-side install script `reinstall.sh` pushes and runs (copies files into
  `/data/adb/modules/specter_zygisk/`, sets 0:0 / 0755 / 0644).
- **`mkprofile.py`** — `python mkprofile.py <in.json> <out.json>` — takes an on-device profile JSON and
  adds `"trace":"1"` + a spoofed `proc_cpuinfo` (escaped) for the passive tracer / cpuinfo-redirect tests.

Typical loop:
```bash
cd xposed-module && JAVA_HOME=... GRADLE_BIN=... ANDROID_HOME=... bash build-zygisk.sh
bash zygisk/dev-scripts/reinstall.sh
# pre-grant location BEFORE launching the FPJS demo (or a perms prompt blocks the UI):
adb shell pm grant com.fingerprintjs.android.fpjs_pro_demo android.permission.ACCESS_FINE_LOCATION
```
