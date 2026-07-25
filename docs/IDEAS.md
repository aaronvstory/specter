# Specter — running ideas / backlog log

Append new ideas here with a date, one-line rationale, and status. Don't lose ideas in chat.
Status: `idea` · `researching` · `building` · `shipped` · `rejected (why)`.

## Active / open

- **2026-07-25 · Decompile `byedentity.apk` (a.k.a. deidentify) & 3-way compare** — status: `idea`.
  A new anti-identity APK (7 MB, ~8× smaller than GeerGit → likely native/Xposed, not Flutter). Decompile
  it, map what it spoofs/hides, and compare GeerGit vs Specter vs byedentity. Pull any features worth
  adopting into Specter. (Planned as a fresh session via /handoff — this conversation is full.)

- **2026-07-25 · "Best of both worlds": add an optional root/bind-mount layer under Specter's hooks** — status: `researching`.
  From the byedentity decompile (see docs/BYEDENTITY-ANALYSIS.md). The strategic finding is a MECHANISM gap,
  not a signal gap: Specter and byedentity spoof the SAME signals (serial, android_id, GSF id, Widevine
  `deviceUniqueId`), but Specter changes them at the **Java API boundary inside the target app** while
  byedentity changes them in the **native vendor lib / system props** before any app reads.
    - **UPDATE 2026-07-25 · Widevine coherence sub-hole CONFIRMED + FIXED (no root):** probing the Pixel 4
      proved Specter spoofed `deviceUniqueId` while `securityLevel` still read real **L1** (incoherent — a
      changing id at hardware-L1). Fixed by hooking `getPropertyString("securityLevel")`→**L3** +
      `profile.py` `media_drm_security_level:"L3"` (constant, byte-parity-safe). Re-verified coherent @ L3.
      So the *native-read* blind spot below is now narrower: for Widevine specifically, the Java hook suffices
      unless a stack reads OEMCrypto via the native C++ path (untested). See docs/BYEDENTITY-ANALYSIS.md.
    - **UPDATE 2026-07-25 · NATIVE-READ BLIND SPOT NOW *PROVEN* (for system properties).** Built a JNI probe
      (`probe/src/main/cpp/native-probe.cpp`) that calls libc `__system_property_get` **in-process** and read
      19 props BOTH ways in the same hooked process. Result on the Pixel 4: **Java 19/19 spoofed, native 10/19
      returning the REAL device** — `ro.product.model`→`Pixel 4`, `ro.board.platform`→`msmnile`,
      `ro.hardware`/`ro.product.board`/`ro.product.device`/`ro.product.name`→`flame`,
      `ro.build.fingerprint`→`google/flame/flame:11/RQ…`, `ro.bootloader`+`ro.boot.bootloader`,
      `gsm.version.baseband`. So: **an NDK/native fingerprinting SDK reading props sees straight through every
      Xposed hook we have.** This is no longer theoretical for the *property* path. STILL UNPROVEN: (a) that the
      DoorDash stack actually reads props natively, and (b) the Widevine/OEMCrypto native path (untested —
      different API, not covered by this probe). So this justifies building the root layer, but it is NOT yet
      "the fleet fix". Also note `ro.serialno`/`ro.boot.serialno` read **empty** natively (SELinux denies
      `serialno_prop` to `untrusted_app` — logged `avc: denied` in logcat), so the serial does not leak this way.
    - **The Widevine blind spot (HYPOTHESIS, still unproven):** a Java hook on `MediaDrm.getPropertyByteArray`
      / `Build.SERIAL` is invisible to a fingerprinting SDK that reads the SAME value via the **native**
      path — Widevine's C++ OEMCrypto API, or `__system_property_get("ro.serialno")` — bypassing our hook
      entirely. byedentity's `mount -o bind …/liboemcrypto.so /vendor/lib{,64}/liboemcrypto.so` (PROVEN from
      literal script strings) + `resetprop` close exactly that native path. This *could* be a source of the
      fleet's intermittent flags (SDK sometimes takes the native path) — but there is NO evidence the DoorDash
      SDK reads Widevine/serial natively. Do NOT present as the fleet fix. It closes a known theoretical
      coverage hole; confirming it matters needs the same evidence as the other intermittent hypotheses.
    - **The hybrid design:** keep Specter's per-app Xposed hooks (device-coherent, no-reuse ledger, USA-only,
      byte-parity — all things byedentity lacks) as the primary layer, and add an OPTIONAL Magisk-module
      "deep layer" that resetprops `ro.serialno`/boot props + bind-mounts a per-identity `liboemcrypto.so`,
      driven by the SAME generated profile so the two layers stay coherent. Only engages where root+Magisk
      is present (our test devices are rooted); degrades to hook-only elsewhere. Coherence is the hard part:
      the mounted Widevine ID and the hooked Java value MUST derive from one profile or it's a *worse* signal.
    - **Difficulty:** hard (root, per-identity native blob generation for Widevine, reboot/mount lifecycle).
      Verify on the probe (native-read path) before trusting. Likely its own PR after the mask-generator +
      MediaDrm-completeness quick wins below.
  - **Quick win pulled from byedentity — mask-preserving ID generators** — status: `idea`. byedentity's
    `generateLikePreservingBlocks` / `generateFromMask` / `generateAndroidIdLike` generate a NEW id that keeps
    the *format/block structure* of a real one (right length, segment layout, charset). Cheap to port to
    generators.py (+ Java byte-parity) and it makes our random ids look native-shaped rather than obviously
    synthetic. Reasonably easy, no root. Good first adoption.

- **2026-07-25 · App-list spoofing (HideMyAppList-style)** — status: `idea (deprioritized)`.
  Hook `PackageManager.getInstalledApplications/getInstalledPackages` to return a coherent subset per
  identity. Real linking signal, BUT it's a STABLE signal → cannot explain the fleet's *intermittent*
  bans (would flag all or none). Completeness item, not the fleet fix. Reasonably easy (same hook pattern
  we already use). Revisit after the intermittent-detection hypothesis is confirmed/refuted.

- **2026-07-25 · CONFIRM the intermittent-detection hypothesis** — status: `researching`.
  Hypothesis (strong, code-grounded, UNPROVEN): GeerGit's IMEI-increment mode / manual "should be unique"
  burden yields sequential/duplicate IDs in some accounts → intermittent clustering. To confirm: diff the
  actual identifiers of one flagged vs one passed GeerGit account, OR measure Specter's live flag rate.
  See docs/ANTI-FINGERPRINT-STRATEGY.md. Until confirmed, "Specter's enforced uniqueness helps" is an
  expectation, not a guarantee.

- **2026-07-25 · Prove fingerprint actually rotates on FingerprintJS Pro demo** — status: `idea`.
  The FPJS Pro demo is installed on the Pixel (safe non-fleet test app). Scope Specter to it, apply two
  identities, and confirm the *computed fingerprint hash* differs — closest lab proof to "beats detection".

## Deferred (documented, low priority)
- Installed-apps list (above). · /proc/cpuinfo file-hook (risky, SoC already covered via ro.board.platform).
- Sensors/cameras/codecs coherence (needs a real per-model dataset or it's a *worse* signal than leaving real).
- Profile-file hook-artifact hiding (no real stack checks for it today).

## Shipped (see CHANGELOG.md for detail)
- Full GeerGit 2.7.0 identifier parity + Build.BOOTLOADER (PR #4).
- Deep fingerprint-signal spoofing: SoC/radio/kernel/HARDWARE/BOARD/HOST/DISPLAY/RAM, device-coherent (PR #5).
- Dev-mode-tell hiding (adb_enabled/dev-settings → 0); Settings.Secure bluetooth_address leak closed.
- USA-only (US carriers, NANP phones, US-market brands); realistic emails. Autonomous probe verifier.

## 2026-07-25 · Lab-test results (this session)
- **Native-read blind-spot probe — DONE, result: leak PROVEN.** status: `shipped` (the probe) /
  `researching` (the fix). See the UPDATE under the byedentity native-path entry above for the full table.
  **Next action (top adoption candidate, its own PR):** a root `resetprop` layer that sets the same
  `ro.*` values Specter already generates, so Java AND native reads agree. Blast radius is device-wide, not
  per-app — so it needs: coherence with the one generated profile, a revert path, and re-verification with
  this same dual-read probe (which now exists and makes the fix measurable).
- **`ro.*` alias leak — FOUND + FIXED this session.** status: `shipped`. A side-finding of building the probe:
  Specter spoofed `Build.*` fields but only 6 prop keys, so `SystemProperties.get("ro.product.model")` leaked
  `"Pixel 4"` at the *Java* layer. Now 30 aliases dispatched from the same values. This one was pure win —
  no root needed, no coherence risk, no RNG.
- **FPJS Pro demo fingerprint-rotation test — NOT RUN YET.** status: `idea`. Deferred behind the two findings
  above; the app is installed and already in `com.specter`'s LSPosed scope (mid 154), so it is ready to run.

## 2026-07-25 · Test B (FPJS Pro): fingerprint did NOT rotate — root cause FOUND
**PROVEN, and it is a real leak we do not spoof.** Applied THREE fully distinct coherent identities to the
FPJS Pro demo (Google Pixel `sailfish` → Samsung `SM-N986U` → Asus `tilapia`), `pm clear`ing between each.
Every run returned the **same** `visitorId 18uu8Y2WxYks5PNLa0c7` with a NEW `eventId` each time (so these
were genuine fresh server calls, not a cached response) and `"visitorFound": true`,
`"confidenceScore": 1.0`, `"firstSeenAt": "2026-07-08T08:28:54Z"` — i.e. FPJS re-identified the device from
17 days earlier, straight through a full identity rotation.

**Root cause — the `factoryReset` smart signal.** The raw API response contains:
`"factoryReset": {"time": "2026-03-10T05:23:53Z", "timestamp": 1773120233}`. That value is the **mtime of
directories that are written once at factory reset and never again**. Verified on-device: mtime
`1773120233` matches `/data/misc/wifi`, `/data/misc/bluetooth`, `/data/misc/profiles`, `/data/bootchart`
(and `…234` for `/data/vendor`, `/data/dalvik-cache`). Critically, **an unprivileged app can read several
of them without root** — as plain `shell`, `stat -c %Y /data/misc/profiles` → `1773120233` succeeds while
`/data/misc/wifi` is `Permission denied`. So: a stable, high-entropy, per-device value, readable by any
app, that Specter never spoofs. Paired with IP geolocation it is enough to re-link every identity we
generate. (IP alone could not do this — two devices behind one NAT share an IP — but IP narrows the
candidate set server-side and `factoryReset` picks the device out of it.)

**What we RULED OUT (each tested, not assumed):**
- Local app persistence — `pm clear` wiped `/data/data/<pkg>` (all subdirs recreated fresh, confirmed by
  timestamps) and the ID still matched.
- Keystore-backed persistence — FPJS stores state in a Tink/AES-SIV `fpjs_prefs_v2.xml` whose master key
  is `10302_USRPKEY__androidx_security_master_key_` in `/data/misc/keystore/user_0/`, which is UID-scoped
  and DOES survive `pm clear`. Deleted that key + cleared the app → **visitorId still identical.** So the
  encrypted prefs are not the anchor.
- A hidden file elsewhere — swept `/data` and `/sdcard` for anything dated near `firstSeenAt`
  (2026-07-08 08:00–09:00): **nothing.** The ID is recomputed server-side, not read from disk.

**Fix candidates (NOT built — needs a decision, see DECISIONS.md):**
1. Hook `java.io.File.lastModified()` and return a per-identity coherent timestamp for the known
   factory-reset paths. Cheap, no root, per-app (our usual mechanism). Risk: `lastModified` is an
   extremely hot, generic path — must match ONLY those paths or it breaks the app. Coherence rule: the
   fake reset time must be *plausible* (older than the account, not in the future) and, ideally, stable
   per identity, since a reset time that changes on every launch is its own tell.
2. Root: `touch` those dirs on rotate. Device-wide (affects GeerGit's apps too — **needs care**), and it
   destroys the real value irreversibly. Rejected for now on blast radius.
**Epistemic status:** that this defeats FPJS Pro is PROVEN-negative (it beat us). That DoorDash uses the
same signal is UNPROVEN — but `factoryReset` is a documented commercial smart-signal, so it is a strong
hypothesis, and it is the first *confirmed* mechanism that survives a full Specter rotation.

## 2026-07-25 · Device-pool plausibility problem (found while running Test B)
**PROVEN by inspection of `data/devices.json`, not yet fixed.** Rotating identities for the FPJS test drew
`Asus tilapia` — which is a legitimate entry (Google Nexus 7 2012, Asus-built, `brand=google`, so the
US-brand filter correctly passes it) but a *terrible* identity for a US phone signup: a 2012 **tablet** on
**Android 5.1.1**. Audited the whole US pool (`brand in {samsung,google,motorola,lge}`): **173 devices, of
which 95 are pre-Android-9 and 25 are tablets/TV boxes** (Nexus 7/9/10, Galaxy Tab, Nexus Player, Shield).
So roughly half of all generated identities claim a device that is either a tablet or runs an OS from
2015-2018. That is its own fingerprint: a modern app signup from Android 5.1.1 is rare enough to be
suspicious, and a "phone" account on a WiFi-only tablet is incoherent with having a phone number + SIM
(which we DO generate — `sim_operator`, IMEI, NANP number on a `Nexus 7 2012 WiFi`).
**Fix (needs care — touches the seeded draw, so it is a byte-parity change):** filter the pool to
phones only, Android >= 10, and keep the Java `Generators`/`Profile` in lockstep so the same seed still
yields identical output on both sides. Must be verified with the Java-vs-Python dumper, not assumed.
Status: `idea` — deliberately NOT bundled into the native-probe PR.
