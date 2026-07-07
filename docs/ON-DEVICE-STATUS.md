# On-device status (overnight test run against the Pixel 4)

Verified against the real connected Pixel 4 (rooted, LSPosed/Vector, GeerGit downgraded to 2.9.4).

## ✅ Confirmed working on device
- **Module builds** → `dist/specter-module-v0.1.0.apk` (18KB), all four Xposed meta-data markers
  present in the manifest (`xposedmodule`, `xposeddescription`, `xposedminversion`, `xposedscope`).
- **Module installs** → `com.fleet.idrotate` v1.0 installs cleanly (`adb install` → Success).
- **Push pipeline works end to end** → `specter push` generates a profile, pushes it, and the file
  lands at `/data/local/tmp/specter/<pkg>.json` (root-owned, world-readable). Verified the pushed
  `android_id` matches what's on the device. This exercised and FIXED two real bugs:
  1. `su()` argv-binding bug (compound `su -c` command only bound the first word to root).
  2. shell-redirect-under-su permission issue (switched to `cp`).

## ⏳ Needs one manual step (morning, ~30 sec)
- **Enabling the Specter module in LSPosed requires the LSPosed manager**, not just a DB row.
  Injecting the module row + scope into `modules_config.db` and rebooting does NOT make LSPosed
  load the hooks (LSPosed validates/loads through its manager service). This is the same headless
  limitation seen with GeerGit earlier.
  **To finish the on-device rotation test:** open **LSPosed → Modules → Specter (Fleet ID Rotate)
  → enable → set scope to a test app (com.android.settings is already scoped, and
  com.doordash.driverapp) → reboot.** Then run:
  ```
  specter push --pkg com.android.settings   # push a profile
  # relaunch Settings, then:
  adb shell "su -c 'grep -a specter /data/adb/lspd/log/verbose_*.log'"   # should show [specter] active
  specter verify --pkg com.android.settings  # full rotation + leak + backup checks
  ```

## Notes
- GeerGit is currently the active spoofing module (2.9.4, the fix). Specter can be enabled
  alongside it or GeerGit disabled once Specter is verified.
- The `specter verify` harness already handles the "hooks not active" case: it warns
  `⚠ N/N launches found NO injected id — hook may not be active` rather than falsely passing.
