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

STRONG HYPOTHESIS (not proven — see caveat below). GeerGit 2.7.0's code HAS an IMEI/incremental INCREMENT mode + a manual
"should be unique" burden, not enforcement:
  - strings: `incrementImei`, `incrementBy`, `deviceIncrementalSwitch`, `increment_imei`
  - UI: "Enter no. by which IMEI will be incremented on each random"
  - UI: "Random behaviour (if selected IMEI will be random no. else incremented by increment value)"
  - UI: "Device Incremental Id (This should be unique)"  <- WARNS the user; does not enforce
IF a user runs increment-mode (or a field is accidentally repeated), GeerGit accounts get sequential /
near-duplicate identifiers, and a fraud engine clustering "IMEIs that differ by 1" or "same X seen before"
flags the ones that land in a detected cluster and misses the scattered ones → intermittent, exactly
what the user sees. NOT the app list (which would be consistent).

WHY SPECTER STRUCTURALLY AVOIDS IT: no increment mode. Every one of 13 UNIQUE_KEYS (android_id, imei1,
imei2, serial, advertising_id, bluetooth_mac, wifi_mac, wifi_bssid, mobile_number, sim_subscriber_imsi,
sim_serial_iccid, gsf_id, media_drm_id) is CSPRNG-random AND checked against a fail-closed no-reuse
ledger that REFUSES to issue any identity sharing a unique field with a past one (collision -> retry).
So Specter can never hand out sequential or repeated identifiers. This is a strong candidate answer to "will Specter do better", but it is NOT confirmed as THE cause.

CAVEAT — this is a HYPOTHESIS, not a proven root cause:
- We have NOT confirmed the fleet actually ran GeerGit in increment-mode, nor that the flagged accounts
  had sequential/duplicate IDs. It's a plausible mechanism that fits the symptom (intermittent, same
  signup), no more. Other candidates remain: a specific field GeerGit rotates weakly, a server-side
  value from its /api/v1 backend, a timing/behavioral signal, or a fingerprint signal that's only
  SOMETIMES read. To CONFIRM: capture the actual identifiers of a flagged vs a passed GeerGit account
  and diff them; or run Specter accounts and observe the flag rate directly. Until then, treat
  "Specter's enforced uniqueness helps" as a well-founded expectation, not a guarantee.

DEPRIORITIZE app-list spoofing (HideMyAppList): it's a consistent signal, cannot explain intermittent
flagging. Worth adding later for completeness, but it is NOT the fleet's problem.

## 2026-07-26 — FPJS Pro anchor: measured, layered findings (UA leak CLOSED; server-match dominates)

Ran the FPJS demo through the Server API + on-device syscall tracing (Specter's own Zygisk `g_trace`
on stat/open/prop). Findings, ordered PROVEN → HYPOTHESIS:

**PROVEN — the User-Agent leak is closed.** Before: two different profiles both reported the REAL
`Dalvik/2.1.0 (...; Android 11; Pixel 4 Build/RQ3A.211001.001)` to the server. After hooking
`System.getProperty("http.agent")` + `WebSettings.getDefaultUserAgent`, the server's
`browserDetails.{device,userAgent,osVersion}` now track the applied profile exactly (verified via the
Server API on two rotations: `Pixel 4a` then `moto g(7)`, each with the matching UA). This was a real
root cause and it is fixed.

**PROVEN — FPJS Pro's `FileTimestamps` raw signal reads the app's own APK install-times.** Decompiled
the SDK (v4.0.0-alpha, pure Java — no `.so`): the single provider under
`raw_signal_providers/file_timestamps` builds a `FileTimestamps(long,long,long)` from three paths.
On-device trace (Java `File.lastModified` + `Os.stat`) captured them: `/data/app/.../<pkg>-.../base.apk`
and `.../split_config.arm64_v8a.apk` (+ it even times the OTHER fingerprinting app, geergit's, apk).
These mtimes are the INSTALL time — set once, identical across every rotation. Now hooked: own-APK
mtimes are spoofed to a per-identity value derived from `factory_reset_epoch` (install lands ~5wk after
the reset; base/split spread 0–12s). Covers both `File.lastModified` and `Os.stat`.

**PROVEN — the dominant anchor is server-side, and it is NOT the IP.** With UA + FileTimestamps spoofed,
the visitorId still did not split across rotations in the user's workspace. `firstSeenAt` stayed pinned
to a record created when the app was installed. `confidenceScore` stays 1.0. So FPJS Pro's server
re-links via a FUZZY match over the stable signal subset (and/or the SDK's cached visitorId in
`fpjs_prefs_v2.xml`, an androidx EncryptedSharedPreferences file that survives `am force-stop` and
`push --no-clear`). The client-visible device fields are no longer the lever; the server match is.

**METHOD CAVEAT (important for future tests).** `pm clear` wipes the SDK's cached visitorId AND the
user's API keys. Once the keys are gone the demo falls back to its BUILT-IN public key, whose workspace
is SHARED by every demo user worldwide — a `firstSeenAt` of "17 days ago" and a stable visitorId there
is a shared-bucket artifact, NOT proof about this device. A valid rotation test MUST stay in the user's
own workspace (keys present) and read events via the Server API. Do not draw conclusions from the
demo's built-in-key workspace.

**NEXT (hypotheses to test, in the user's workspace).** (1) The SDK's cached visitorId in
`fpjs_prefs_v2.xml` — clear ONLY the SDK's cache entry per rotation (surgical, preserves the app),
not `pm clear`. (2) `rootApps=True` / `developerTools=True` still leak: `developerTools` reads
`Settings.Global.getString(adb_enabled/development_settings_enabled)` — Specter hooks these but the
server still flags it, so confirm the read path the SDK uses. (3) Installed-app entropy
(`InstalledAppsSignalGroupProvider` lists user+system apps) — a stable per-device set; hide-my-apps /
the module's own presence contributes.

## 2026-07-26 (cont.) — developerTools/rootApps are SERVER-SIDE Smart Signals, not client reads

Traced the demo's `Settings.Global.getString` reads on-device: the SDK reads
`development_settings_enabled` and `adb_enabled`, and Specter's hook is confirmed WORKING
(`hit=true final=0` — the SDK receives "0" for both). Yet the Server API still reports
`developerTools=True`. Conclusion (PROVEN by elimination): `developerTools` and `rootApps` are FPJS
**Pro Smart Signals computed SERVER-SIDE** from the full signal payload + the device's history — a
client cannot flip them by spoofing the two individual Settings.Global reads. The open-source SDK's
AdbEnabled/DevelopmentSettings signals FEED the server's decision but do not solely determine it.

Implication: these are a DIFFERENT class of problem from the client-readable UA leak. Beating them means
either (a) denying the server the corroborating evidence it correlates (installed-app set, root-path
probes the native layer still misses, mount/selinux state), or (b) accepting them as flags and competing
on the visitorId itself. The dev-settings hook stays (it's correct and necessary), but it is not
sufficient alone. `[specter][global]` trace (gated on "trace":"1") is left in place to re-check.
