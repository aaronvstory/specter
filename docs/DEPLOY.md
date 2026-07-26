# Deploy Specter to a device (fleet-ready)

Specter v0.5.0 is usable and testable now. Two artifacts install it; then you generate + apply identities
from the PC (`python -m specter.cli`) or on-device from the app UI.

## Prerequisites on the target device
- **Rooted with Magisk** (Zygisk ON) — this is the same setup as the Pixel 4 test device.
- **LSPosed installed** (the Xposed manager). The module APK is an LSPosed module.
- USB debugging on; `adb` reachable from the PC.

## The two artifacts (both in `dist/`, rebuilt from the current branch)
1. `dist/specter-module-v0.5.0.apk` — the LSPosed module (Java hooks: Build/IDs/SIM/UA/display/sensors/
   installed-apps/SDK/dev-settings…).
2. `dist/specter-zygisk-v0.5.0.zip` — the flashable Magisk/Zygisk module (native layer: /sys, /proc/version,
   /proc/mounts+mountinfo Magisk hiding, Frida/su path hiding, native `__system_property_get` parity,
   cpuinfo/boot_id, GPU/sensor NDK reads).

## Install (once per device, ~5 min)
```
# 1. Install the LSPosed module APK
adb -s <SERIAL> install -r dist/specter-module-v0.5.0.apk

# 2. Flash the Zygisk zip (via Magisk app: Modules -> Install from storage -> pick the zip -> reboot)
#    OR push+install it headless the way dev-scripts/reinstall.sh does:
adb -s <SERIAL> push dist/specter-zygisk-v0.5.0.zip /data/local/tmp/
adb -s <SERIAL> shell "su -c 'magisk --install-module /data/local/tmp/specter-zygisk-v0.5.0.zip'"
adb -s <SERIAL> reboot

# 3. In LSPosed, enable the "Specter" module and set its SCOPE to the TARGET app(s) you'll test.
#    (LSPosed UI, or edit the scope DB — see CLAUDE.md. Specter is a distinct module id per device.)
```

## Use it
- **From the PC (headless, scriptable):**
  ```
  python -m specter.cli rotate --pkg <target.package>     # new identity + apply + clear the app
  python -m specter.cli push   --pkg <target.package> --no-clear   # re-apply current, keep app data
  python scripts/verify_on_device.py <SERIAL>             # probe read-back, per-field ✅/❌ table
  ```
- **From the on-device app** (`com.specter`): RANDOMIZE ALL → pick target app → APPLY. The Settings tab
  has per-protection toggles (Hide root/dev/applist, Spoof UA/install-time/hardware) with live status.
- Relaunch the target app after APPLY so the hooks re-read the new profile.

## Verify it's working
`python scripts/verify_on_device.py <SERIAL>` seeds the probe from the applied profile and prints a
per-field ✅/❌ table (expect ~29 spoofed / 0 hard leaks). The probe reads every spoofable surface both
the Java and native way, so a green table means the target app sees the spoofed device, not the real one.

## What it does / doesn't guarantee
- **Does:** every device-identifier + hardware/OS signal a fingerprinter reads on-device is spoofed,
  coherent (one real device model), per-identity, and globally unique (ban-critical no-reuse ledger).
  Root/Magisk/Frida hidden per-app. Survives reboot (profiles persist in /data/local/tmp/specter).
- **Server-side smart signals** (a vendor's `rootApps`/`developerTools`/visitorId) are computed on the
  vendor's server; the client sends the spoofed values, but a vendor's own account history / IP reputation
  is outside a client tool's control. For a clean measurement of a vendor's reported id, use that vendor's
  own workspace (see `scripts/fpjs_split_test.py` for the FingerprintJS case).

## Fleet safety (NON-NEGOTIABLE, from CLAUDE.md)
Scope Specter ONLY to the apps you intend to spoof. NEVER scope/apply against the income apps
(GeerGit / DoorDash driver/consumer) — GeerGit owns those and the native companion hard-denylists them.
