# Anti-fingerprinting strategy — why spoofs get "sometimes detected", and how Specter beats it

Evidence-based, from the FingerprintJS Android SDK source (deepwiki-verified against
`fingerprintjs/fingerprintjs-android`). This is the "hide better than GeerGit" plan.

## The core finding: two IDs, and everyone only spoofs one of them

FingerprintJS (the fraud-detection SDK class DoorDash-style stacks use) computes **two** identifiers:

### 1. `deviceId` — from GSF → mediaDrmId → androidId (first available, in that order)
Specter **already spoofs all three** (gsf_id, media_drm_id, android_id). So the `deviceId` rotates
correctly. ✅ This is the part GeerGit and Specter both get right.

### 2. `fingerprint` — a MurmurHash of ~30 hardware + OS + device-state signals
This is the part that leaks. The `fingerprint` is a hash of the **combined string of every signal
below**. Specter (and GeerGit) currently spoof only ~4 of them — the rest stay the REAL device.

**THIS is the "sometimes detected" root cause.** When you rotate identifiers, `deviceId` changes but the
`fingerprint` hash is dominated by real hardware signals that never change. Across signups the
`fingerprint` stays ~stable (same physical phone) → a fraud stack correlating on `fingerprint` (or
deviceId+fingerprint together) re-links the "new" device to the old one. Intermittent, because it
depends on which signal the detector keys on.

## Signal coverage audit — what Specter spoofs vs. what FingerprintJS reads

| Signal (FingerprintJS reads) | Source | Specter today | Priority to add |
|---|---|:---:|:---:|
| Manufacturer, Model | Build | ✅ | — |
| Build fingerprint | Build | ✅ | — |
| Android version, SDK | Build.VERSION | ✅ | — |
| **Kernel version** | `/proc/version`, `os.version` | ❌ | **HIGH** (cheap, high-entropy) |
| **Total RAM** | ActivityManager.MemoryInfo | ❌ | **HIGH** |
| **Total internal storage** | StatFs | ❌ | **HIGH** |
| **CPU info / ABI / cores** | `/proc/cpuinfo`, Build.SUPPORTED_ABIS | ❌ | **HIGH** |
| **Sensor list** | SensorManager.getSensorList | ❌ | MED (list must stay device-coherent) |
| **Installed-apps list** | PackageManager.getInstalledApplications | ❌ | **HIGH** (very high entropy, per-user unique) |
| Input devices, cameras, codecs, GPU/GLES | various managers | ❌ | MED (coherence-sensitive) |
| Battery health / capacity | BatteryManager | ❌ | LOW (GeerGit beta added this) |
| Settings.Global (adb, dev-settings, http_proxy, anim scales, roaming) | Settings.Global | ❌ | MED |
| Settings.Secure (accessibility, default IME, RTT, touch-explore) | Settings.Secure | partial (getString hooked) | MED |
| Settings.System (font scale, screen timeout, date format, 12/24h) | Settings.System | partial (getString hooked) | LOW |
| Timezone, locale, region, language | DevicePersonalization | ❌ | MED (must match US) |
| Encryption status, security providers, PIN-security | DeviceSecurity | ❌ | LOW |

## Strategy: spoof the fingerprint, coherently, per-identity

The win is not "spoof everything" — it's **spoof the high-entropy signals that dominate the hash, keep
them coherent with the spoofed device, and rotate them per-identity** so the `fingerprint` actually
changes each signup. Priority order (payoff × entropy ÷ risk):

1. **Installed-apps list** — highest entropy, per-user unique, trivially readable. Hook
   `PackageManager.getInstalledApplications/getInstalledPackages` to return a stable, device-coherent
   subset (system apps + a seeded plausible set) per identity. HIGH payoff.
2. **RAM + storage + CPU cores + ABI + kernel version** — cheap static reads, high entropy, must be
   coherent with the spoofed MODEL (a Galaxy S20 reports ~8GB, specific ABI, specific SoC). Bundle these
   into the device DB row so they're coherent, and hook the getters. HIGH payoff.
3. **Sensor / camera / codec lists** — high entropy but MUST match the model exactly or it's a *stronger*
   detection signal than leaving them real. Only do these with a real per-model dataset. MED, risk-gated.
4. **Settings.Global/Secure state** (adb, dev-settings, proxy, anim scales) — MED entropy; also doubles as
   an anti-detection hygiene win (hide that dev options / adb are on).

## Coherence is non-negotiable
An incoherent spoof (Galaxy S20 model + 2GB RAM + x86 ABI) is a *bigger* red flag than no spoof. Every
hardware signal added MUST come from the same real device profile as the Build.* fields. This means
extending the device DB rows with {ram, storage, abi, cores, kernel, sensors...} per real device — a data
effort, but it's what makes Specter's fingerprint rotation actually coherent where GeerGit's is partial.

## Why this beats GeerGit
GeerGit spoofs the same ~4 Build signals + the deviceId trio. Its `fingerprint` hash leaks the same real
hardware. Specter's edge = **spoofing the fingerprint's dominant hardware/apps signals coherently and
per-identity**, so both `deviceId` AND `fingerprint` rotate together. That closes the correlation channel
that makes GeerGit "sometimes detected."

## Test harness
FingerprintJS Pro demo (`com.fingerprintjs.android.fpjs_pro_demo`) is installed on the Pixel — the exact
detector. After each hardware-signal hook: scope Specter to the FPJS demo (it's a safe non-fleet test
app, like DevInfo), read its reported fingerprint before/after, and confirm the fingerprint CHANGES on
re-randomize and STAYS coherent. This is the measurable proof "hide better" is real.
