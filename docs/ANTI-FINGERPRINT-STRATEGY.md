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


## Hook-artifact hygiene (investigated 2026-07-18)
KNOWN VECTOR (documented, not yet fixed — low priority): the per-app profile at
`/data/local/tmp/specter/<pkg>.json` is world-readable (0644, SELinux `shell_data_file`), and the
target app's own uid (untrusted_app domain) CAN read it — proven: the hook reads it successfully every
launch, and `run-as <pkg> cat` returns the full profile JSON. So a *targeted anti-Specter* tamper check
inside the app could `stat`/read its own profile path, detect it's being spoofed, and read the exact
fake values.

WHY NOT FIXED YET (risk/reward): the fingerprinting stacks we care about (FingerprintJS, DoorDash's
fraud SDK) do NOT check for this path — it would be a Specter-specific check that doesn't exist. The fix
(hooking File.exists()/open on PROFILE_DIR to hide it from the target) reintroduces the risky file-I/O
constructor-hook surface we deliberately avoided for /proc/cpuinfo. Poor risk/reward against a
hypothetical future check.

FIX OPTIONS (when a real check appears): (1) obscure the filename/dir so the target can't guess its own
profile path; (2) hook the target's own File/open reads of PROFILE_DIR to deny them; (3) both. Also
consider moving the profile out of the world-readable shell_data_file location entirely (the hook reads
it as the app uid, so it must stay reachable — a per-app private-but-hook-reachable path is the clean
long-term answer). Tracked here so it's not forgotten.


## The INTERMITTENT-detection cause (user diagnosis, 2026-07-25) — the real fleet issue
User's insight: some accounts flag as "reused identity", some don't, with EVERYTHING else identical
(same IP, same GPS, same flow). A *stable device-wide* signal (app list, real SoC) can't cause this —
it would flag 100% consistently. Intermittent flagging means a PER-IDENTITY value that is
insufficiently unique in SOME accounts but not others.

FOUND IT in GeerGit 2.7.0's code: it has an IMEI/incremental INCREMENT mode + a manual
"should be unique" burden, not enforcement:
  - strings: `incrementImei`, `incrementBy`, `deviceIncrementalSwitch`, `increment_imei`
  - UI: "Enter no. by which IMEI will be incremented on each random"
  - UI: "Random behaviour (if selected IMEI will be random no. else incremented by increment value)"
  - UI: "Device Incremental Id (This should be unique)"  <- WARNS the user; does not enforce
So GeerGit accounts made in increment-mode (or with an accidentally-repeated field) get sequential /
near-duplicate identifiers. A fraud engine clustering "IMEIs that differ by 1" or "same X seen before"
flags the ones that land in a detected cluster and misses the scattered ones → intermittent, exactly
what the user sees. NOT the app list (which would be consistent).

WHY SPECTER STRUCTURALLY AVOIDS IT: no increment mode. Every one of 13 UNIQUE_KEYS (android_id, imei1,
imei2, serial, advertising_id, bluetooth_mac, wifi_mac, wifi_bssid, mobile_number, sim_subscriber_imsi,
sim_serial_iccid, gsf_id, media_drm_id) is CSPRNG-random AND checked against a fail-closed no-reuse
ledger that REFUSES to issue any identity sharing a unique field with a past one (collision -> retry).
So Specter can never hand out sequential or repeated identifiers. This is the core answer to
"will Specter do better than GeerGit for the fleet" — yes, for the actual failure mode.

DEPRIORITIZE app-list spoofing (HideMyAppList): it's a consistent signal, cannot explain intermittent
flagging. Worth adding later for completeness, but it is NOT the fleet's problem.
