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
