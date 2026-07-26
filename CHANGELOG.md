# Changelog

All notable changes to Specter are documented here.
Format: [Keep a Changelog](https://keepachangelog.com/). Versioning: [SemVer](https://semver.org/).

## [0.9.3] — 2026-07-27

### Fixed
- **/proc/meminfo RAM leak.** ActivityManager.totalMem (the Java path) was spoofed, but a DIRECT read
  of /proc/meminfo's MemTotal leaked the real device RAM — a contradiction with the claimed device.
  Found by an empirical audit of the FPJS demo's trace (it reads /proc/meminfo directly). The native
  layer now redirects /proc/meminfo to a spoof file whose MemTotal (+ coherent Free/Available) matches
  the profile's total_ram. PROVEN on-device: real 5,596,800 kB, scoped app now reads 11,701,248 kB
  (matching an ~11.4 GB profile). Reuses the existing sysfs-redirect mechanism.

## [0.9.2] — 2026-07-27

### Added
- **Battery capacity spoofing.** BatteryManager.getIntProperty/getLongProperty(BATTERY_PROPERTY_CHARGE_COUNTER)
  exposes the battery's full/design capacity (a stable per-model hardware signal FingerprintJS reads).
  The profile now carries a battery_uah derived from the device codename (2800-4600 mAh, byte-parity), and
  the battery hook returns it. PROVEN on-device: host real charge counter 1,777,000 µAh, scoped app
  reads the spoofed 3,500,000 µAh (3500 mAh) for the moto g 5G profile. Live CAPACITY %% left real.

## [0.9.1] — 2026-07-27

### Added
- **Boot-count spoofing.** Settings.Global.BOOT_COUNT is a per-device-stable integer that FingerprintJS/
  EXADPrinter hash; leaving it real leaks the host's true boot count. Now the profile carries a
  boot_count derived from the android_id (stable per identity, plausible 40-460 range) and the settings-
  global hook returns it. PROVEN on-device: host real boot_count 110, scoped app reads spoofed 405.
  Byte-parity Java<->Python (pure lookup, no RNG).

## [0.9.0] — 2026-07-27

### Added
- **Specter Lite (non-root harvester).** A tiny separate APK (:lite module, ~12KB, no root/Xposed/
  native) that runs on ANY device and harvests every identifier + device field readable WITHOUT root
  (android_id, Build.*, MediaDrm device id, screen metrics), exporting a Specter profile envelope. Copy
  it to a rooted device's Download and import it in Specter to clone the harvested device. IMEI/serial/
  IMSI (need root/privileged perms to read) are honestly left for hand-entry, never fabricated. PROVEN
  end-to-end: harvested the real Pixel 4 -> imported into the main app with matching android_id + model
  (the lite checksum byte-matches the app's VaultChecksum, so cross-app import validates).

## [0.8.1] — 2026-07-27

### Added
- **Custom field editing (clone a specific device).** Every identity + device field is editable to an
  EXACT value (not just RANDOMIZE) — so you can clone a real device's android_id / gsf / imei / serial /
  etc. onto a profile. Identifiers edit freely (independent, format-validated). Device fields (model/
  brand/device/fingerprint/carrier) are coupled, so editing one shows a coherence warning that the
  others won't auto-update — allowed, but flagged. Edited values survive APPLY (same path as randomize).

## [0.8.0] — 2026-07-27

### Added
- **Vault export / import (share profiles between users).** Any saved profile can be exported (Share button) to /sdcard/Download as a portable, checksummed envelope (specter-profile-*.json:
  format-version + SHA-256 + the flat identity). Another user drops that file in their Download and
  imports it (validated + checksum-verified, corruption rejected) into their own vault to apply. So two
  users can share an exact device profile. PROVEN end-to-end on-device: export -> import round-trips the
  identity faithfully (android_id matches), metadata stripped, checksum guards integrity. Storage-
  permission-free (routed through su, like the diagnostics export).

## [0.7.2] — 2026-07-27

### Added
- **Hide mock-location flag.** A driver/fraud SDK (Incognia/SEON — the exact income-app case)
  reads Location.isFromMockProvider() / isMock() to detect a spoofed GPS. Both now report false for
  scoped targets (gated with the other anti-tamper protections). Full GPS-coordinate spoofing is a
  planned separate feature; this closes the cheap mock-detection tell in the meantime.

## [0.7.1] — 2026-07-27

### Added
- **Locale / timezone coherence.** A US device profile whose TimeZone.getDefault()/Locale.getDefault()
  still reported the HOST machine's region was an internal contradiction FingerprintJS DeviceState
  hashes. The profile now carries a US IANA timezone DERIVED from the phone's area code (so phone +
  timezone + locale tell one coherent US-location story) plus locale en-US, and hooks getDefault() on
  both. PROVEN on-device: a Miami (786) number -> America/New_York + en_US, while the host device's
  real America/Chicago no longer leaks. Byte-parity Java<->Python (pure lookup, no RNG).

## [0.7.0] — 2026-07-27

### Added
- **SENSORID — per-profile sensor calibration transform (flagship anti-fingerprint win).** The raw
  accelerometer/gyroscope/magnetometer value stream carries each phone's factory-calibration error, a
  stable ~57-bit fingerprint that SURVIVES factory reset and was IDENTICAL across every profile on the
  one physical device (relabeling the sensor LIST never touched it). Now a profile-seeded affine
  transform (per-axis scale within ±2%, small bias) is applied to SensorEvent.values[] at the dispatch
  choke point, so each profile presents a different, physically-plausible calibration. PROVEN on-device
  (phone held still, seed rotated): the averaged accel vector shifted by 0.04–0.20 per axis between
  profiles — ~50-70x the same-profile noise floor — while gravity magnitude stayed ~9.8 (physics intact).

## [0.6.0] — 2026-07-27

### Added
- **Verified-boot / lock-state prop spoofing.** A rooted device leaks `ro.boot.verifiedbootstate=orange`,
  `ro.boot.vbmeta.device_state=unlocked`, `ro.boot.flash.locked=0`, `ro.build.tags=test-keys`, `ro.debuggable=1`
  — a direct "unlocked + modified device" tell weighted by every root/fraud SDK, independent of the model
  spoof. Now reports a stock, locked consumer device (green/locked/1/release-keys/user/0) on BOTH the Java
  (SystemProperties.get) and native (libc) paths. Native values route through the deferred late-map (same
  mechanism as SDK_INT) to avoid the zygote-init SIGSEGV; PROVEN on-device via the probe's native late-read.
- **Diagnostics logging** — a Settings toggle (default OFF) that continuously captures what each
  Specter-scoped app READS (props/files/IDs, via the SpecterTrace trace) and the value returned, to
  /data/local/tmp/specter/diag.log (rotating, 32MB cap). A background foreground-service runs
  `logcat -f`; the file is adb-pullable so you can verify spoofs are landing as you use it — no manual
  export. READ-ONLY (applies nothing; safe on any scoped app).
- **Live trace viewer** — a "View live trace" button next to the Diagnostics-logging toggle opens a
  full-screen viewer that parses diag.log into a deduped, counted, grouped list (Properties / Files /
  Stat-access) of the device signals a scoped target actually read — e.g. `ro.product.model ×4`,
  `/proc/cpuinfo`, `ro.build.fingerprint`. Loader/linker noise (getauxval/dlsym, lib/jar/ART loads,
  self-`/proc/<pid>`) is filtered so only fingerprint-relevant reads show. Auto-refreshes every 2s,
  with Live/Pause, Refresh, and Clear-log. READ-ONLY.
- **Google-account + media-codec spoofing default ON, individually toggleable.** Leaving the REAL device
  Gmail / real OMX.qcom.* codec set visible to a scoped app is itself a spoofing leak, so both mask by
  default like every other signal. Gmail's control is its inline switch on the Identity tab (next to its
  value); codecs' toggle is in Settings. Account masking relabels the REAL com.google account's name in
  place (never fabricates — so app logins with their own credentials are unaffected; only a Google-SSO
  account-picker would notice). Turn either off for a specific target only if that app misbehaves.
- **Media-codec list spoofing** (`MediaCodecList.getCodecInfos`). The codec-name set (e.g.
  `OMX.qcom.video.decoder.avc` reveals Qualcomm) is a stable per-SoC signal that was GENERATED into
  every profile (`hw_codecs`) but never applied â the real ~40-codec device list leaked (the probe only
  read a count, so it was never caught). Now `getCodecInfos()` returns the real infos capped to the
  profile codec count, each 1:1 relabeled to a profile codec name (no duplicates, count == names,
  capabilities preserved). Proven on-device: probe `hw_codecs` == the profile set (10 codecs, matched).
- **Gmail account spoofing is now actually APPLIED** (was generated-but-dropped). Every profile
  generated a coherent Gmail and the UI showed it as spoofed, but NO `AccountManager` hook existed â
  so an app reading `getAccountsByType("com.google")`/`getAccounts()` got the REAL Google account (a
  strong cross-account linker). New `hookAccounts` rewrites the enumeration result to the profile's
  Gmail (a synthetic `com.google` Account); auth-token paths untouched (masking model, like GeerGit).
  Proven on-device: the probe reads `google_accounts` == the profile gmail. Closes a false-coverage gap.
- **App Set ID spoofing** (`com.google.android.gms.appset.AppSetIdInfo.getId`). A per-app-scoped install
  id apps read for analytics â now generated (a UUID, byte-parity JavaâPython) and hooked to return the
  profile value. Closes a breadth gap vs HideMyAndroid.
- **On-device profile vault** — save a generated identity under a date/time label and re-apply that
  EXACT device later (same unique IDs), or delete it. New **Saved** tab: an opt-in "Save to vault
  after RANDOMIZE ALL" checkbox (default off — profiles are entirely skippable), a "Save current to
  vault" button, and a **searchable, date-grouped, collapsible** list of saved profiles. Each entry
  is one `files/vault/<label>.json` (label `MMDDYY-DayAbbr-HHMM[-Name]`, name optional). Search filters
  by name or device (case-insensitive) and auto-expands matches; date groups ("Sun 07/26/26 (2)")
  collapse/expand on tap. Same-minute saves disambiguate with a `-2`/`-3` suffix (no silent overwrite).
  Verified on-device end-to-end: save A -> generate+apply C -> restore A re-applies A's exact android_id.
- **Hide Frida artifacts** (a hooking/instrumentation-framework detection vector). This device had a
  leftover `/data/local/tmp/frida-server` binary that a `File.exists()`/`access()` frida check would
  find. Added the frida artifact paths (frida-server, frida-gadget, re.frida.server, libfrida-gadget)
  to the native root/hook-hiding path list so those reads return ENOENT for a hooked app, and added
  frida/gadget/thread-name markers to the maps/mount filter. Gated by `hide_root`. Verified on the
  probe: `frida_server_visible` reads `clean` while a non-hooked shell still sees the binary (per-app).
- **Hide Magisk from `/proc/mounts` + `/proc/self/mountinfo`** (a byedentity-relevant root vector).
  Real reads leak Magisk unambiguously — `tmpfs magisk` overlays on `/system_ext/bin`,
  `/debug_ramdisk/.magisk` lines — which a mount-reading root detector catches even when the su/
  magisk BINARY paths are already hidden. The Zygisk layer now builds a filtered per-process copy
  (drops any line naming magisk / a hook framework / `/data/adb`) and redirects the read to it,
  gated by `hide_root`. Unlike `/proc/self/maps` (which ART reads during GC — filtering crashes the
  app), mountinfo is safe to filter. Verified on the probe: both files read `clean` (no magisk) in
  the hooked app while a non-hooked shell still sees the real mounts (per-app, not device-wide).

### Fixed
- **Search box Enter submits** (dismisses keyboard) instead of inserting a newline.
- **Income apps are now spoofable** — removed the native hard denylist that refused to serve profiles
  to DoorDash/GeerGit (that was dev-only overcaution; spoofing target apps is the product's purpose).
  The native layer now only blocks the OS framework itself (android/system) via is_core_os.
- **Removed all in-app "fleet/system" warnings/limits** — the tester-vs-fleet distinction is a workflow
  choice, not something to surface or restrict in the app. The only warning kept is the useful one:
  “not enabled in LSPosed” when a selected target app isn't actually scoped.
- **Target-app UX:** Identity tab shows each selected app as its own SEPARATED card (icon + name + red
  square ✕), matching the picker; onResume re-renders so the selection is never stale.
- **Vault: only APPLIED identities are saved** (saving un-applied profiles was pointless/misleading) —
  the save prompt fires after APPLY, records which apps it reached, and the Saved row shows “Applied to:
  <apps>”. Saved date-groups now COLLAPSE by default.
- **Target-app selection UX** — fixed the Identity tab showing stale targets (it only re-rendered on
  the Settings tab, so after picking apps from the Identity "Change" button the card still showed the old
  selection). Now: onResume re-renders the current tab; the Identity card lists each selected app by NAME
  with a quick ✕ remove and a "not enabled in LSPosed" warning if an app isn't actually scoped; the picker
  pins a SELECTED section to the top (checked apps first, easy to uncheck) above ALL APPS. Removed the
  "fleet/system" emoji labels — the system/income caution is now a plain, neutral toast on add.
- **ro.build.version.sdk / ro.product.first_api_level now spoofed on the NATIVE path** (they leaked the
  real device to a native fingerprinter like FingerprintJS). Adding them to the always-on native prop map
  SIGSEGVs the zygote (ART reads them during init); fixed by a DEFERRED map that only spoofs them ~1.5s
  after process start — past the dangerous init window, before any runtime fingerprint read. Proven
  on-device: an early read returns real, a post-1.5s read returns the profile value; no crash.
- **Native root/tamper detection hardened** — traced what FingerprintJS's libfp.so actually probes and
  closed the gaps a native check used to bypass the libc-function hooks: now also hook `faccessat` and
  raw `syscall(faccessat/faccessat2/newfstatat/statx)` for root paths; `is_root_path` PREFIX-matches
  root-owned trees (`/data/adb/`, `/sbin/.magisk`, root-app data dirs) instead of an exact 24-path list;
  and `/sys/fs/selinux/enforce` is redirected to "1" (enforcing) so a Magisk device's SELinux reads clean.
  MEASURED: FPJS's `tampering` signal flipped from high to FALSE, `frida`/`emulator` clean, and every
  path FPJS probed now returns ENOENT. (`rootApps`/`developerTools` still fire via a deeper native path
  — see docs/ANTI-FINGERPRINT-STRATEGY.md.)
- **Input-device names leaked the real device** (`InputManager` / `InputDevice`). The SDK reads every
  `getInputDevice(id).getName()`+`getVendorId()` (decompiled `C0465h` case 4) as a stable hardware
  anchor. The hook faked only the device COUNT (`getInputDeviceIds`), so the real Pixel-4 touchscreen
  (`fts`) and PMIC (`qpnp_pon`) names still went out on every read. Now `getInputDevice(int)` is also
  hooked: each returned InputDevice's `mName` is relabeled to the profile's input-device list and
  `mVendorId`/`mProductId` zeroed (what internal touchscreens report). Fixes a stable per-device signal.
  Advertised input-device ids are now capped to the REAL resolvable ids (was 0..n-1), so the device
  COUNT matches the number of readable names — no "5 ids but 3 names" mismatch tell — and a
  malformed empty `hw_input_devices` value can no longer divide-by-zero (both found by the /gauntlet).
- **Remaining build props leaked the real device.** A full prop sweep found ro.build.product (=flame),
  ro.build.flavor (=flame-user), ro.build.description (=flame-user 11 RQ3A...), and
  ro.bootimage.build.fingerprint (=google/flame/...) all leaked the real Pixel 4. Aliased product/
  bootimage-fingerprint to build_device/build_fingerprint, and added two computed profile fields
  build_flavor (<device>-user) + build_description (<device>-user <release> <id> <incr> release-keys)
  aliased to ro.build.flavor / ro.build.description. Verified on the probe: a moto g(7) profile
  reports channel / channel-user / motorola/channel/... — no flame leak. 29 spoofed / 0 hard leaks.
- **Per-partition product props leaked the real device (significant).** Android 10+ exposes
  Build.MODEL/BRAND/DEVICE/MANUFACTURER/PRODUCT and the build fingerprint on multiple partitions
  (system/vendor/odm/product/system_ext). Specter aliased only ro.product.* + ro.product.vendor.*,
  so ro.product.odm.model / ro.product.product.model / ro.product.system_ext.model / and the
  ro.{product,odm,system,system_ext}.build.fingerprint props all leaked the REAL Pixel 4
  (ro.product.odm.model=Pixel 4, ro.product.build.fingerprint=google/flame/...). Added all the
  partition variants to the Java + native PROP_ALIASES (lockstep test passes). Verified on the probe:
  a Galaxy Note20 profile reports SM-N986U / samsung/c2qsqw/c2q on every partition, not the real device.
- **`ro.boot.hardware` + `ro.boot.hardware.platform` leaked the real device.** These props read the
  real Pixel 4 (`flame` / `sm8150`) while `ro.hardware` / `ro.board.platform` were spoofed — both a
  leak AND an internal inconsistency (two hardware props disagreeing). Added them to the Java + native
  PROP_ALIASES (`ro.boot.hardware`->build_hardware, `ro.boot.hardware.platform`->soc_platform);
  lockstep test still passes. Verified on the probe: a Pixel 5 profile now reports
  `ro.boot.hardware=redfin` / `.platform=lito`, not the real values, with no zygote crash (unlike the
  init-time SDK props, these are safe to intercept natively).
- **`build_sdk` was incoherent for pre-Lollipop devices** (code-review finding). Five release
  strings present in `data/devices.json` (`4.2.2`/`4.3`/`4.4.2`/`4.4.4`/`5.0.2`) were missing from
  the release->SDK map, so they fell through to the default SDK 30 — a KitKat device reporting
  Android 11's API level, an internally inconsistent giveaway. Added the correct mappings (17/18/
  19/19/21) to both the Python and Java maps, and added a coherence test asserting EVERY release in
  the dataset has an explicit, era-plausible SDK (not just self-consistency with the buggy function).
- **`getInstallerPackageName` was hooked to throw the wrong exception** (code-review finding). It was
  in the installed-app-hiding `notFound` list that throws the checked `NameNotFoundException`, but
  its real not-found contract returns `null` / throws the UNCHECKED `IllegalArgumentException`. A
  caller catching `IllegalArgumentException` for a hidden package would be hit by an undeclared
  checked exception. Now returns `null` (benign "unknown installer") for a hidden package instead.

## [0.5.0] — 2026-07-26

### Investigation (2026-07-26) — root cause of "FPJS still wins" PROVEN
- Wired up the Fingerprint **Server API** (user's Public key in the demo -> events in the user's own
  clean workspace; Secret key + AP/Mumbai region -> read raw signals back via curl). Ran the clean
  two-rotation test: two totally different device profiles BOTH returned the same visitorId
  (`SJoG6...`, confidence 1.0) even with no stale record. The raw API response proves WHY: the server
  saw the **real Pixel 4** both times — `device="Pixel 4"`, `osVersion="11"`,
  `userAgent="Dalvik/2.1.0 (...; Android 11; Pixel 4 Build/RQ3A.211001.001)"`, `rootApps=True`.
  The **User-Agent** (framework-built from Build.*, read by the SDK from a system/WebView path our
  in-app Build.* hooks don't cover) is the visitorId anchor — NOT the hardware bundle. Fix in progress:
  hook `WebSettings.getDefaultUserAgent()` + `System.getProperty("http.agent")` + close `rootApps`
  detection, then re-run the two-rotation test. See docs/IDEAS.md + docs/GOAL.md 1.3.

### Added
- **Sensor resolution / maxRange / power spoofed (the high-entropy sensor fields).** The sensor-list hook
  already relabeled name+vendor, but left `mResolution`/`mMaxRange`/`mPower`/`mVersion` REAL — which leak
  the exact Pixel-4 sensor chip (what FingerprintJS actually hashes). Now set to coherent per-sensor-type
  values (SpoofLogic.sensorRmp, pure + tested). Verified on the probe: the accelerometer reports
  78.4532/0.0023928226/0.17, not the real device's values.
- **Display metrics spoofed (`getDisplayMetrics`: width/height/densityDpi).** Decompiling the FPJS SDK
  found it reads the screen via `getResources().getDisplayMetrics()` — a Java-API signal the native
  tracer can't see, which leaked the real Pixel 4's `1080x2280@440` on every rotation. New
  `screen_width`/`screen_height`/`screen_density` fields keyed on the device codename (known models use
  their real spec; unknown codenames map deterministically into a pool of real configs via an FNV-1a
  hash mirrored byte-for-byte in Java). `Resources.getDisplayMetrics` + `Display.getMetrics`/
  `getRealMetrics` are hooked to return them. Gated by the CPU/GPU-/sys toggle. Verified on the probe: a
  Galaxy A7 profile reports `720x1520@295`, real `1080x2280@440` gone.
- **`Build.VERSION.SDK_INT` spoofed coherent with the Android release.** A profile claiming Android 9
  used to still report SDK 30 (the real Pixel 4) via `Build.VERSION.SDK_INT` — a mismatch that is itself
  a fingerprint. New `build_sdk` profile field (release -> API level, pure lookup, byte-parity mirrored
  in Java's `sdkForRelease`) drives a reflection write to the int field. Verified: an Android-10 profile
  reports SDK_INT 29. NOTE: `ro.build.version.sdk` is spoofed via Java only, NOT the native prop layer —
  intercepting it natively SIGSEGVs the zygote (ART reads it during init); documented in CLAUDE.md.
- **`/proc/version` kernel banner spoofed (closes a byedentity-comparison gap).** Specter spoofed the
  `os.version` property but a direct `/proc/version` read got the REAL kernel (the Pixel 4's
  `4.14.212-...`). The Zygisk layer now redirects `/proc/version` to a banner rebuilt from the profile's
  `build_kernel_version`, so a file-reading collector sees the applied kernel. Gated by the CPU/GPU /sys
  toggle. Verified on the probe: reports `5.15.294-android12-...`, real `4.14.212` no longer leaks.
- **Protections UI — real toggles + live ON/OFF status for every anti-detection feature.** The app's
  Settings tab now has a Protections section with a working switch for each of: Hide root, Hide
  developer mode (ADB + dev options), Hide My AppList (installed-app filter), Spoof User-Agent, Spoof
  install time (APK mtime), and Spoof CPU/GPU /sys. Each toggle is REAL — it writes a gate key
  (`hide_root`/`hide_dev`/`hide_apps`/`spoof_ua`/`spoof_apktime`/`spoof_sysfs` = "0" when off) into the
  applied profile, and the Java + native hooks read that key to skip the protection and leave the signal
  real. No cosmetic switches. Every protection defaults ON, so existing behavior is unchanged.
- **Per-SoC /sys hardware signals spoofed (cpu_capacity vector, KGSL gpu_model, cpu present range).**
  On-device tracing of the FPJS demo showed it reads `/sys/devices/system/cpu/cpu<N>/cpu_capacity`,
  `/sys/class/kgsl/kgsl-3d0/gpu_model`, and `/sys/devices/system/cpu/present` directly — a high-entropy,
  stable, real-hardware signature (the Pixel 4's `261 261 261 261 871 871 871 1024`) that leaked on every
  rotation. Added `data/soc_topology.json` (per-SoC capacity vectors + GPU model, mirrored in Java's
  embedded `SOC_TOPOLOGY` with a byte-parity test) and three new profile fields (`cpu_capacity`,
  `gpu_model`, `cpu_present`) keyed on the profile's SoC — pure constants, no RNG, byte-parity safe. The
  Zygisk layer writes a spoof file per node and redirects the exact sysfs paths. Verified on the probe:
  a Galaxy S21 (exynos2100) profile now reports `215...1024`, not the real Pixel 4's `261...1024`.
- **Installed-app list filtering — hides the instrumentation from FPJS's app-enumeration signal.** The
  installed-app list is a raw signal FingerprintJS collects (PackageManager enumeration); leaving
  `com.specter`, the probe, Magisk/LSPosed managers, or a hide-my-app tool in it both raises the device's
  entropy and is a direct "this device is instrumented" tell. `getInstalledApplications`/
  `getInstalledPackages`/`getInstalledModules` (+ AsUser variants) now drop packages matching a
  root/hooking/anti-fingerprint marker list, and a direct `getPackageInfo`/`getApplicationInfo` lookup of
  a hidden package throws `NameNotFound` (as if not installed). Verified on the probe:
  `installed_sensitive_leak: none` (was leaking 3 packages). The probe now reports the installed-app
  count and any sensitive leak so a regression fails the check.
- **APK install-time spoofing — closes FingerprintJS Pro's `FileTimestamps` raw signal.** Decompiling
  the SDK showed a single raw-signal provider that reads three file timestamps; on-device tracing
  proved they are the mtimes of the app's own `/data/app/.../base.apk` + `split_config.*.apk` — the
  INSTALL time, constant across every rotation. `File.lastModified()` and `android.system.Os.stat/lstat`
  are now hooked to return a per-identity install time derived from `factory_reset_epoch` (install ~5
  weeks after the reset; base/split spread 0–12s) for the target's own APKs only. No new profile field,
  no RNG — byte-parity safe.
- **User-Agent spoofing — closes the PROVEN FingerprintJS visitorId anchor.** The default HTTP
  User-Agent (`System.getProperty("http.agent")`) and the WebView UA
  (`WebSettings.getDefaultUserAgent`) are now rebuilt from the profile's own
  `build_release`/`build_model`/`build_id`, so they report the device the identity claims to be.
  The framework builds both strings at zygote/WebView init from the REAL `Build.*` values, before any
  in-app field hook runs — which is why two completely different profiles previously both reported
  `Dalvik/2.1.0 (Linux; U; Android 11; Pixel 4 Build/RQ3A.211001.001)` to the FPJS Server API and
  collapsed to one visitorId. Derived from existing fields: no new profile key, no RNG draw, so
  Java<->Python byte-parity is unchanged. `System.getProperty` is now hooked once and dispatched from
  a map (it also serves `os.version`) rather than per-key on a hot path. Verified on-device: the probe
  reads the spoofed UA on both paths.
- The probe reports `http_agent` and `webview_ua`, and `verify_on_device.py` checks the Dalvik UA
  against the expectation derived from the applied profile — so a UA regression fails the table.

### Fixed
- **`Build.MODEL` and `Build.DEVICE` were bound to the wrong dataset columns (coherence leak).**
  Every generated profile reported the device CODENAME as the marketing model and vice-versa,
  producing fingerprints like `google/bramble/Pixel 4a (5G):11/...` — a DEVICE slot containing spaces
  and parentheses, which no real Android build emits, plus `Build.MODEL="flame"` where a real Pixel 4
  says `"Pixel 4"`. Verified against the physical device (`MODEL="Pixel 4"`, `DEVICE=PRODUCT="flame"`,
  `fp="google/flame/flame:11/..."`). Fixed identically in `profile.py` and `Profile.java`; the Samsung
  bootloader base follows the marketing model as before. Java<->Python parity re-proven byte-for-byte
  over 195 identity/build values. The bug survived because `ProfileTest`'s inline fixtures had the two
  columns transposed relative to the real `data/devices.json` — the fixtures now mirror production
  data, and both suites assert the fingerprint's DEVICE slot is a codename.
- **`tests/test_jvm_logic.py` was silently skipping on every run** (it referenced the pre-rename
  package `com/fleet/idrotate` and only searched a non-existent vendored JDK), so the Java logic was
  never exercised from the Python suite. It now resolves the JDK from `JAVA_HOME`/PATH, asserts the
  sources exist rather than skipping, and runs all four JVM test mains.

- **UsedStore concurrency hardening (ban-critical no-reuse ledger).** Three real defects surfaced by
  a flaky concurrency test, all fixed at the root: (1) `_atomic_write_json` now `fsync`s before
  `os.replace`, so a concurrent reader that sees the renamed file can never read stale/empty content;
  (2) an in-process `threading.Lock` per ledger path serializes threads (Windows `msvcrt` byte-range
  locks are per-handle and don't exclude sibling threads); (3) `os.replace` is retried on Windows
  `ERROR_ACCESS_DENIED` (a transient share violation when a reader has the target open) instead of
  bubbling out and silently dropping that caller's ledger update. The disk ledger was always
  reuse-free; these close a handout-accounting race so the concurrency tests are deterministic.
- **In-app version no longer drifts (UX 3.1/3.2).** `app/build.gradle`
  now derives `versionName`/`versionCode` from the repo `VERSION` file, so the header (which showed a
  stale `v0.3.0`) always matches the shipped module. Also refreshed the Settings ANTI-FINGERPRINTING
  copy to list the hardware layer (SoC, GPU/GLES, /proc/cpuinfo, sensors), and added a UX audit
  (`docs/UX-AUDIT.md`).

### Added
- **Native sensor relabel (ASensor NDK hooks).** The tracer proved a native fingerprinter reads the
  sensor list via libandroid's `ASensor_getName`/`ASensor_getVendor` (direct JNI, unreachable by the
  Java SensorManager hook). The Zygisk layer now relabels those two accessors so each real sensor
  reports the profile's per-model name/vendor — no ASensor struct fabrication (crash-safe), stable per
  sensor pointer. Verified on-device: with a Galaxy A70 profile the native ASensor read returns the
  profile's Samsung sensors (LSM6DSO / STMicroelectronics), not the real Pixel 4's Bosch BMI160. The
  probe reads it via a new `nativeSensors()` NDK JNI. (Camera NDK hooks remain a follow-up — the camera
  id list is an allocated struct, higher risk; the Java CameraManager hook covers that path today.)
- **Real US area codes for generated phone numbers (Phase 2.2 coherence).** `phone_us` now draws the
  area code from a table of real, currently-assigned US area codes (broad metro/state spread) instead
  of a random structurally-valid `[2-9]XX` (many of which are unassigned — a tell), and never emits an
  N11 service code (211/411/911) as the exchange. Byte-parity proven Java↔Python over 500 seeds
  (`scripts/prove_phone_parity.py`, now checked in) plus a 300-seed full-profile check.
- **Coherent `ro.board.platform` (SoC) per model (Phase 2.2).** `soc_platform` was returning a RANDOM
  SoC for most pool devices (a Galaxy S21 could report a budget chip). It now derives the real SoC from
  the per-model hardware bundle, so the reported platform agrees with the GPU/`/proc/cpuinfo` the same
  profile carries. Made PURE (no RNG) — a real SoC is a fact of the model, not a draw — which also keeps
  byte-parity trivial. Verified on-device: Moto Z3 Play reports msm8998 across soc_platform, the native
  GPU (Adreno 540), and cpuinfo (MSM8998), all coherent.

### Fixed
- **Thread-safe hardware-dataset cache.** `_load_hardware()` cached the dataset with an unlocked lazy
  read; under concurrent profile generation each thread could parse the 200KB JSON, perturbing timing
  (it surfaced a flaky concurrency test). Now loaded exactly once under a lock (double-checked). No
  behavior change to generated profiles.
- **Per-model hardware-descriptor layer — the profile now carries a coherent hardware bundle
  (GOAL 1.3, data + generation).** A new `data/hardware.json` (built by
  `scripts/build_hardware_dataset.py`) maps each selectable device codename to real, coherent
  hardware descriptors — GPU/GLES renderer, `/proc/cpuinfo`, sensor list, camera list, codec list,
  input devices, core count — grounded in the model's actual SoC (e.g. Pixel 4 -> Adreno 640 /
  SM8150; Galaxy S10e -> Mali-G76 / Exynos 9820). `specter/profile.py` and the Java `Profile.build`
  now inject these 9 flat fields into every generated profile, keyed on the picked device codename.
  They are CONSTANTS (a lookup, no seeded RNG), so byte-parity is preserved by construction; the
  Java `Profile.KEYS` order still matches the Python dict (guarded by the parity test), and a new
  asset-sync test asserts `data/*.json` == the bundled APK assets so the PC and on-device paths can
  never read different data. The Java hooks (`hookHardwareSignals`) return these per-model values
  (GLES version, GPU renderer via GLES20.glGetString, core count, camera ids, sensor relabel); the
  native Zygisk layer inline-hooks `glGetString` for the direct-JNI GPU read; and the existing
  /proc/cpuinfo redirect is now fed by the generated `proc_cpuinfo` key. The verification probe reads
  every descriptor both ways (framework API + a native EGL/GLES read). PROVEN on-device (Pixel 4, two
  identities): a Galaxy Note 9 reports Mali-G72 / Exynos 9810 and a Moto G7 reports Adreno 512 /
  SDM660 — two coherent, DIFFERENT bundles read back on the probe, NOT the real Pixel 4's Adreno 640 /
  SM8150, with 0 hard leaks. Native sensor/camera NDK inline hooks and the FPJS-demo end-to-end
  readout (confounded by the demo's fixed-key server record) remain follow-ups; see docs/IDEAS.md.
- **Zygisk native layer — closes the native read paths (GOAL 1.2).** A new self-contained Zygisk
  companion module (`xposed-module/zygisk/`) that INLINE-hooks libc per-app, from the SAME
  `/data/local/tmp/specter/<pkg>.json` the Xposed module reads (one source of truth). It spoofs:
  (a) system properties via `__system_property_read_callback` AND `__system_property_get`, and
  (b) the factory-reset mtime via `stat`/`lstat`/`fstatat`/`statx`. PROVEN on-device: the dual-read
  probe now shows native == Java (19/19 props spoofed) where before 10/19 leaked the real device.
  This closes the libc blind spot the property probe (PR #7) and the FPJS factoryReset test (PR #8)
  proved Xposed's Java-only hooks could not reach.
  - **Mechanism:** PLT hooking was tried first and does NOT work — bionic's internal
    `__system_property_get`->`__system_property_read_callback` call never goes through libc's PLT, so
    a PLT hook reports a backup yet intercepts nothing (proven on-device). Switched to an INLINE hook
    (vendored And64InlineHook, single-file MIT) — the same class of hook PlayIntegrityFork uses. Must
    be self-contained: ZygiskNext's builtin linker refuses a module with an unresolved external
    `DT_NEEDED` (`open module with builtin linker failed: not preloaded`), so the hooker is compiled in.
  - **Fleet safety (NON-NEGOTIABLE):** a hard denylist in the root companion refuses to serve a profile
    for `com.doordash.driverapp` / `com.dd.doordash` / `com.pyshivam.geergit` / `android` / `system`
    even if a stray profile file exists, so the native hooks can NEVER touch a GeerGit fleet app.
    Verified on-device: only `com.specter.probe` was hooked; no fleet app ever was.
  - Build: `bash build-zygisk.sh` -> `dist/specter-zygisk-v<VERSION>.zip` (flashable module). Logic
    unit-tested via `run-zygisk-tests.sh` (cross-compiled for arm64, run on-device).

### Known limitation
- **Native spoofing did NOT change the FPJS Pro `visitorId` — root cause is unspoofed HARDWARE
  signals, not the native layer or the IP (GOAL 1.2 device-side done; end-to-end not).** With props +
  factory-reset spoofed natively AND via Java, two fully different identities (Motorola kiev ->
  Samsung o1s) STILL returned the same `visitorId` (`confidenceScore 1.0`). Reading the
  fingerprintjs-android SDK source shows why: the Pro visitorId is a server-side FUZZY MATCH over ~50
  signals, and we spoof none of the stable HARDWARE-characteristic ones — `/proc/cpuinfo`, sensor list,
  camera list, GLES/GPU version, codec list, input devices, core count — nor generate any data for them.
  FPJS reads them off the real Pixel 4 unchanged every rotation, so the match locks on. (The IP
  `datacenter_result:true` flag is a separate fraud smart-signal, NOT the identity anchor.) Spoofing the
  hardware signals coherently is the real next step — see GOAL 1.3 / IDEAS.md.

### Changed
- **Device pool filtered to plausible phones (GOAL 2.1).** Generation now excludes tablets/TV boxes
  (Galaxy Tab, Nexus 7/9/10, Nexus Player, Shield, Pixel C) and any device below Android 9 — a fresh
  account claiming a WiFi-only tablet with a SIM + IMEI, or a 2015-era OS, is itself a fingerprint. The
  US-brand pool goes from 173 rows (95 pre-A9, 26 tablets/TV) to 68 real phones on Android 9-12. Filter
  logic (`_is_plausible_phone` / Java `isPlausiblePhone`, floor `MIN_ANDROID_MAJOR = 9`) is mirrored
  byte-for-byte on both sides; this changes the seeded device draw, so it is a byte-parity change —
  RE-PROVEN identical over 300 seeds with the standalone Java-vs-Python dumper.


### Added
- **`factory_reset_epoch` — spoof the FPJS Pro `factoryReset` smart signal.** New generator
  (`factory_reset_epoch`, Java `factoryResetEpoch`) producing a plausible reset time, derived as a
  1..540-day offset from the profile's own `build_security_patch` so the pair is coherent by
  construction — a device cannot be reset before its own OS was built. It reads NO wall clock (a
  code-review catch: an earlier clamp sampled `now()` independently in Python and Java, which would
  silently break byte-parity if it ever fired); "never in the future" is instead enforced by a test
  that fails loudly if the device pool gains a too-recent patch. Byte-parity PROVEN against Python
  across 200 seeds with a standalone Java dumper.
  Appended LAST in the profile dict, so the new draw does not shift any existing field's value.
- **`HookEntry.hookFactoryResetTime`** spoofs the reset time on BOTH Java read paths:
  `java.io.File.lastModified()` AND `android.system.Os.stat/lstat` (rewriting `st_mtime`/`st_ctime`/
  `st_atime` on the returned `StructStat`). Matches an EXPLICIT path set (`isResetMarker`, exact
  equality — never a prefix) so target apps' own file bookkeeping is untouched. Verified on-device:
  all 6 reset-marker dirs return the spoofed time via both paths (real `1773120233` → `1636101883`).
- **Probe: `mtime_*` / `osstat_*` pairs** — reads each reset-marker dir via `File.lastModified` AND
  `Os.stat`, which is what isolated the leak to `Os.stat` after the File-only hook proved insufficient.

### Fixed
- **`media_drm_security_level` was missing from the Java side entirely** (caught by a new
  `test_module_keys_match_python_profile_keys` parity guard). `profile.py` emitted it but Java's
  `Profile.KEYS`/`build()` did not, so a Java-generated profile carried no `L3` field and last
  session's Widevine coherence fix silently did not apply on that path — leaving the exact
  incoherence (a changing DRM id at hardware L1) it was meant to close. Both keys now mirror Python.

### Known-unfixed (measured, not speculation)
- **FPJS Pro still does not rotate.** With both Java read paths provably spoofed in-process (probe
  confirms `Os.stat().st_mtime` → spoofed), FingerprintJS Pro still reports the REAL
  `factoryReset: 1773120233` and the same `visitorId`. It therefore reads the reset time **natively**
  (`stat()` via NDK), the same blind spot already PROVEN for system properties. This makes the root
  `resetprop`/native layer a prerequisite rather than an optional extra — see `docs/GOAL.md` item 1.2.


### Changed
- **Module renamed `com.fleet.idrotate` → `com.specter`** (Java package `com.specter.module`, LSPosed entry
  `com.specter.module.HookEntry`). The old id leaked the internal codename in LSPosed's UI and update
  notifications. On-device this is a migration (LSPosed sees a new module id), so scope is re-established;
  GeerGit's LSPosed module is never touched. `scope_probe.py` updated to the new package.

### Added
- **FPJS Pro lab test run (Test B) — result: the fingerprint does NOT rotate, root cause identified.**
  Applied three fully distinct coherent identities (Google Pixel → Samsung Note 20 Ultra → Nexus 7) to the
  FingerprintJS Pro demo, `pm clear`ing between runs. All three returned the SAME `visitorId` with a fresh
  `eventId` per call, `visitorFound: true`, `confidenceScore: 1.0`, and `firstSeenAt` 17 days earlier.
  Root cause: the `factoryReset` smart signal — a timestamp Specter does not spoof, readable from
  directory mtimes (`/data/misc/profiles`, `/data/bootchart` are readable WITHOUT root; verified the
  reported `1773120233` matches them exactly). Ruled out local persistence (`pm clear`), Keystore-backed
  encrypted prefs (deleted `10302_USRPKEY__androidx_security_master_key_`, ID unchanged), and any
  file dated near `firstSeenAt`. Documented in `docs/IDEAS.md`; the fix is deliberately a separate PR
  (see `docs/DECISIONS.md` for why hooking `File.lastModified` vs root `touch` both need their own review).

- **Native-read blind-spot probe** (`probe/src/main/cpp/native-probe.cpp`, NDK 27 + CMake). A JNI function
  calls libc `__system_property_get` **in-process**, so the probe reads 19 system properties BOTH ways —
  Java `SystemProperties.get` (which Specter hooks) and native libc (which it does not) — and
  `verify_on_device.py`-style comparison shows exactly where the two disagree. `getprop` via exec is a
  false proxy for this (separate, unhooked process); the read must be in-process JNI.
  **Result (PROVEN on-device, Pixel 4):** the Java side returns the spoofed value for all 19 props while
  the native side returns the REAL device value for 10 of them (`ro.product.model` → `Pixel 4`,
  `ro.board.platform` → `msmnile`, `ro.hardware`/`ro.product.board`/`ro.product.device`/`ro.product.name`
  → `flame`, `ro.build.fingerprint` → `google/flame/flame:11/RQ…`, `ro.bootloader`/`ro.boot.bootloader`,
  `gsm.version.baseband`). An NDK-based fingerprinter reading props natively sees the real hardware.
  This is the one axis byedentity's root `resetprop` layer beats us on — see `docs/IDEAS.md`.

- **byedentity 3-way analysis** (`docs/BYEDENTITY-ANALYSIS.md`): decompiled `com.byedentity` v3.0.1 and
  compared GeerGit vs Specter vs byedentity. byedentity is a root/Magisk + native-JNI, server-validated
  changer that spoofs system-wide via `resetprop` + `pm clear` + a Widevine `liboemcrypto.so` bind-mount
  (L1→L3). Findings carry PROVEN/HYPOTHESIS labels (adversarially verified). Adoption candidates in
  `docs/IDEAS.md`; do-not-adopt calls (server/kill-switch/anti-tamper) in `docs/DECISIONS.md`.
- **Probe: Widevine `securityLevel`** — the probe now reads `MediaDrm.getPropertyString("securityLevel")`
  alongside `deviceUniqueId`, and `verify_on_device.py` prints a Widevine-coherence line.

### Fixed
- **`ro.*` property aliases leaked the real device (PROVEN, found by the new dual-read probe).** Specter
  spoofed each `Build.*` field but only 6 property keys (`os.version`, baseband, SoC). Every other Build
  field has a `ro.*` property alias that a fingerprinter can read directly, and those returned the real
  hardware: `SystemProperties.get("ro.product.model")` → `"Pixel 4"` and `("ro.boot.bootloader")` →
  the real bootloader, while `Build.MODEL`/`Build.BOOTLOADER` were correctly spoofed. `HookEntry` now
  dispatches a `PROP_ALIASES` table covering 30 keys (model/brand/manufacturer/device/name/board/hardware/
  bootloader/serialno/fingerprint/id/display/release/incremental/security_patch/host, plus the `vendor.`
  variants) from the SAME profile values as the fields — coherent by construction, consumes no RNG, so
  Java↔Python byte-parity is unaffected. Verified on-device: **19/19 props spoofed, 0 Java-layer leaks**
  (was 2). Note this closes the *Java* path only; native `__system_property_get` still reads real (above).
- **Widevine DRM coherence (no root).** Specter value-spoofed `deviceUniqueId` but left `securityLevel`
  reporting the real **L1** — a *changing* device id at hardware-L1 is itself a fingerprint. Confirmed
  on-device (Pixel 4: spoofed id @ L1), then fixed: `profile.py` emits `media_drm_security_level: "L3"`
  (software Widevine, where a changing id is coherent) and `HookEntry` hooks
  `getPropertyString("securityLevel")` to return it. Re-verified coherent on-device (@ L3). The value is a
  constant → consumes no RNG → Java↔Python byte-parity unchanged. Achieves byedentity's L1→L3 outcome
  without its root `liboemcrypto` bind-mount.
- **StatFs storage leak closed + RAM/storage made coherent (device-linking signal).** `total_storage` was
  generated but never injected, so real internal storage leaked — a stable value that links accounts. Added
  a coherent `StatFs` hook (`getTotalBytes` and `getBlockCountLong`×`getBlockSizeLong` multiply to the same
  spoofed total; available/free ~35-55%). Also replaced the independent RAM and storage draws with a single
  coherent pair (`ram_storage_bytes`): storage is derived from the chosen RAM tier, so an incoherent combo
  (e.g. 12GB RAM + 32GB storage) can no longer occur. Java↔Python byte-parity re-proven; verified on-device.
- **Brand-plausible serial format (was detectably synthetic).** `serial` was `hex16upper` — 16 pure-hex
  chars, but a real Pixel serial is 14 alphanumeric incl non-hex letters (`9B151FFAZ00FPF`) and a Samsung
  is `R`+10 (11 chars). Added `serial_for_brand` (Base34 alphabet, brand prefix + correct length for
  Samsung/Google/Motorola/LGE). Java↔Python byte-parity proven; verified on-device (Pixel profile →
  `A6X71GDYHX9WC3`). A device claiming to be a Pixel no longer reports an impossible pure-hex serial.

## [0.3.0] — 2026-07-08

UI/UX polish + real multi-app targeting, per-country SIM, and realistic emails. The app now
carries one name (Specter), a logo, and the warm-dark charcoal theme.

### Added
- **Multi-app targeting**: an app picker (PackageManager) with a Show-system-apps toggle
  (user apps by default), search, Select/Deselect all, and multi-select. APPLY writes the
  profile to every selected target. Fleet-safety warning on Dasher/system packages.
- **Per-country SIM**: USA + UK, with an extensible Country structure (carriers, phone format,
  ICCID IIN, brand bias). Settings has a country picker; UK generates EE/O2/Vodafone/Three
  carriers + +44 7 phone numbers. `specter --country` on the CLI.
- **Realistic emails**: real first/last names in common patterns (first.last, firstlast+year, …)
  across gmail/outlook/yahoo/hotmail/icloud, replacing the old random-letter `xxxx###@gmail.com`.
  (GeerGit's own 'normal emails' are server-side and byte-identical across its versions — this is
  an independent implementation, not a port.)
- **Per-identifier on/off toggles**: each id card has a switch; disabled ids are omitted from the
  applied profile so the hook leaves them REAL (GeerGit's *_switch parity).
- **Branding**: gold ghost logo (vector) as header mark + adaptive launcher icon; charcoal/gold
  theme via a single Theme token class; version shown small next to the wordmark.

### Fixed
- **Tab active-state**: switching Identity/Settings/Location now highlights the active tab (gold).
  Previously the tab bar was built once and never re-tinted.
- One name only: the LSPosed module + app label is **Specter** (was 'Fleet ID Rotate').

### Notes
- Java + Python generation stay byte-parity (same seeded emails/phones). JVM 44,063 asserts +
  Python 75 tests green.
- Deferred (2.9.7-beta parity, later): Hide Airplane Mode, Randomize Battery Level, Spoof Battery
  Cycle Count, i18n, profile-transfer (server feature).

## [0.2.0] — 2026-07-08

Pivot from a PC-tethered CLI to a **standalone, no-PC Android app**: Specter now generates
identities on-device and self-applies them via Magisk `su`, with a native 3-tab UI — the same
package is BOTH an LSPosed module and a launchable app (like GeerGit). Proven end-to-end on a
real Pixel 4 against the DevInfo test app.

### Added
- **Standalone Android app** (`com.fleet.idrotate.ui.MainActivity`): on-device identity
  generation + native Identity/Settings/Location UI (RANDOMIZE ALL, per-card EDIT/RANDOMIZE,
  APPLY). No PC required.
- **On-device generation core** ported from the Python reference to pure Java
  (`gen/Generators`, `gen/Profile`, `gen/UsedStore`) — byte-parity with Python at a fixed seed;
  34k+ JVM assertions.
- **`RootWriter`**: writes the profile to `/data/local/tmp/specter/<pkg>.json` via `su`
  (shell-injection-guarded, JSON via stdin), fail-loud on root denial.
- **`IdentityService`**: bundled 499-device asset, on-device no-reuse ledger in app-private
  storage (fail-closed on corruption), thread-safe generate/randomize.
- PC TUI upgraded to a questionnaire menu (questionary + rich fallback); `specter --version`.
- Version surfaced everywhere: app header, TUI header, APK badging, `VERSION` single-source file.

### Fixed
- **android_id + advertising_id now actually reach the target app.** Prior builds spoofed
  Build.*/serial/GSF but leaked the REAL android_id and ad id (proven via DevInfo + two
  dexdumps). Now hook all `Settings.Secure`/`System` getString overloads and the
  `AdvertisingIdClient` static factory.
- **Ban-critical**: per-field RANDOMIZE now records to the no-reuse ledger (a randomized-then-
  applied id could previously be reissued to another account).
- Ledger thread-safety (static lock), fail-closed persistence (checked `renameTo`), empty-
  profile guards on APPLY/EDIT/RANDOMIZE (an empty APPLY would leak real ids), missing-key
  validation, and a data race on the shared profile map.

### Changed
- Fleet-safety: CLI/TUI/verify default target is now DevInfo (`com.liuzh.deviceinfo`), never a
  real fleet app. The Python CLI/TUI is retained as the trusted spec + dev tool.

### Known / out of scope
- Fingerprint Pro (`com.fingerprintjs.android.fpjs_pro_demo`) re-identifies at 100% confidence
  after a full rotation — it fingerprints via hardware/sensor/IP signals this module does not
  hook. Deprioritized to a later stretch goal; GeerGit identifier-level parity is the bar and is met.

## [0.1.0] — 2026-07-08

First complete release: builds, installs, push-verified on device; 73 tests; 6 review passes applied.

### Added
- Hook coverage hardening: ContentProviderClient.query, cursor getBlob + copyStringToBuffer.
- IMEI TAC coherence (brand-plausible TAC, shared across dual-SIM IMEIs).
- Fail-closed used-id ledger (quarantine + refuse on corruption).
- Build.VERSION.* spoofing; Gservices.getLong; executable JVM tests for hook logic.
- CI workflow; release builder (dist zip); dual-OS launchers.
- Polished README (feature table, quality section, project layout).
- Deepened `verify` questionnaire: pre-flight device/module summary, module-active detection,
  per-check error isolation, and a final results summary table.
- RFC-4122 v4 advertising IDs; GSF clamped to Java Long.MAX; slot-aware IMEI; GSF cursor getLong.
- Core identity generator: coherent, US-biased device profiles from a 499-device DB.
- Per-identifier generators with validators (Luhn IMEI/ICCID, MAC bits, 19-digit GSF).
- Global used-id ledger — no identifier is ever reused across signups (the anti-ban core).
- Named profile vault: save / list / reuse identities (backup a good one, reload later).
- `device.py` adb layer: push profile, clear app, read live identifiers + hook log.
- CLI: `new` · `push` · `rotate` · `list` · `show` · `stats` · `verify` · `tui`.
- Rich TUI dashboard (light/dark safe): active identity, vault, issued-ledger, device status.
- **Deep on-device verification harness** (`verify.py`, questionnaire-driven): coverage,
  rotation (launch N×, confirm fresh identity each), backup/reload round-trip, leak audit.
- LSPosed module (`xposed-module/`) hooking the full identifier surface incl. GSF.
- `scripts/compare_with_geergit.py`: on-device coverage + rotation comparison vs GeerGit.
- 49 tests: generators, coherence, uniqueness, GeerGit parity, device, CLI, TUI, verify, module parity.

### Context
- Built after diagnosing GeerGit 2.9.6's GSF-rotation regression (`docs/GEERGIT-2.9.6-REGRESSION.md`)
  that reused a stale fake GSF across signups → DoorDash coordinated-account bans.
