# Specter — the goal and the work queue

**This file is the standing instruction set. Read it at session start. Work the queue top-down.
Don't stop to ask "what next?" — take the top unblocked item, ship it, tick it off, take the next.**

---

## The end state

Specter is a product someone would pay for:

1. **It is not detected.** It beats commercial fingerprinters — including FingerprintJS Pro — not just
   GeerGit's bar. Every identity looks like a genuinely different, genuinely ordinary US phone.
2. **It is coherent.** Never an incoherent combo. An implausible device is itself a fingerprint, so
   "spoofed" is not the bar — "plausible" is.
3. **It is a beautiful, trustworthy UX.** Fast, clear, no dead controls, no jargon leaking to the user.
4. **Later: it is a subscription product.** Deliberately deferred — see Phase 4. A product that gets
   detected cannot be sold, so detection and UX come first.

## How to work

- **Full TDD.** A test that fails before the fix and passes after. Python: `.venv/Scripts/python.exe -m
  pytest -q`. JVM: `cd xposed-module && bash run-jvm-tests.sh`. Both green before every commit.
- **Byte-parity is non-negotiable.** Any change touching a seeded draw must be proven with the
  Java-vs-Python dumper, not assumed. Constants consume no RNG and are parity-safe by construction.
- **Prove it on-device.** `scripts/verify_on_device.py` for the hook table; the dual-read probe for the
  native path; the FPJS demo for the end-to-end question. A claim without on-device evidence is a
  hypothesis and must be labelled one.
- **PRs: no merge gate.** Make reasonable, scoped PRs, run the bot loop + a code-reviewer subagent, fix
  real findings, and merge them yourself. Don't stop and wait for permission on ordinary work.
  Still surface: a genuine decision, a real finding, or a true blocker.
- **One concern per PR.** A byte-parity change never rides along with an unrelated fix.
- Keep `CHANGELOG.md` / `IDEAS.md` / `DECISIONS.md` / `CLAUDE.md` current in the SAME commit.

## Definition of done for "beats FPJS Pro"

The FPJS Pro demo, run twice with two different applied identities and a `pm clear` between, returns
**two different `visitorId`s**, and ideally `visitorFound: false` on a fresh one. Record the whole
`identification` block (`eventId` proves the call was fresh, `firstSeenAt` proves link age) — not just
the id string. Current status: **STILL FAILS — real root cause found: we don't spoof the HARDWARE-
CHARACTERISTIC signals at all (2026-07-25).** After the native layer (GOAL 1.2) closed the prop +
factory-reset blind spot, two different identities STILL returned the same visitorId. Reading the FPJS
Android SDK source (the Pro visitorId is a server-side fuzzy match over its signal set) shows why: our
generated profile has ZERO data for, and we hook NONE of, `/proc/cpuinfo`, the sensor list, the camera
list, GLES/GPU version, codec list, input devices, or core count. FPJS reads all of those straight off
the real Pixel 4 — identical every rotation — so its fuzzy match locks onto that stable real-hardware
cluster. The IP `datacenter_result` flag is a separate fraud smart-signal, NOT the identity anchor
(a datacenter IP alone can't collapse distinct devices to one visitorId). → the real GOAL 1.3.

---

## Queue

Status: `todo` · `in-progress` · `done` · `blocked (why)` · `dropped (why)`

### Phase 1 — Beat the fingerprinters (the thing that makes it a product)

- [x] **1.1 `factoryReset` leak — Java layer DONE + MERGED (PR #8); did NOT win alone.** `done`
  Shipped `factory_reset_epoch` (coherent: derived from the build's own security patch; byte-parity proven
  over 200 seeds) + hooks on `File.lastModified` AND `android.system.Os.stat/lstat`. Verified on-device:
  all 6 reset-marker dirs return the spoofed time via both Java paths.
  **Result: FPJS Pro STILL reports the real `1773120233` and the same `visitorId`.** By elimination it
  reads the reset time via native `stat()`. Two confirmed signals (properties AND filesystem metadata)
  now leak exclusively through the native path, so **1.2 is a prerequisite, not an option.**
  Kept anyway: the Java hooks close the paths other SDKs do use, and they cost nothing.

- [~] **1.2 The native read path — DEVICE-SIDE DONE + VERIFIED; did NOT move the visitorId.** `partial`
  Built the Zygisk native layer (`xposed-module/zygisk/`): a self-contained, per-app companion module
  that INLINE-hooks libc (`__system_property_read_callback` + `__system_property_get`, and
  `stat`/`lstat`/`fstatat`/`statx`) from the SAME profile the Xposed module reads. **PROVEN on-device:**
  the dual-read probe now shows native == Java for every property (19/19 spoofed; was 10/19 leaking), and
  the FPJS process is confirmed hooked. Fleet-safe by a hard companion denylist (verified: only the
  probe was ever hooked, never a fleet app).
  Mechanism notes (learned the hard way, see IDEAS/DECISIONS): PLT hooking does NOT intercept bionic's
  internal prop path; an INLINE hook (And64InlineHook, compiled in) does. ZygiskNext's builtin linker
  requires a self-contained `.so` (no external `DT_NEEDED`).
  **BUT the definition-of-done is NOT met, and the reason is NOT the native layer** — it's that we never
  spoofed the hardware-characteristic signals (see 1.3). Two fully different identities STILL returned the
  same `visitorId` (`confidenceScore 1.0`). Kept anyway: closing the native prop/reset path is real and
  necessary (other SDKs read natively), it just isn't sufficient on its own.

- [ ] **1.3 Spoof the HARDWARE-CHARACTERISTIC signals — the actual reason FPJS still wins.** `todo` · **highest value**
  ROOT CAUSE (found 2026-07-25 by reading the fingerprintjs-android SDK source + confirming our profile/
  hooks): the Pro `visitorId` is a server-side fuzzy match over ~50 signals; we spoof the identifier +
  build + RAM/storage subset but NONE of the stable hardware signals, and generate no data for them:
    - `/proc/cpuinfo` (procCpuInfo / procCpuInfoV2) — real SoC, cores, BogoMIPS, CPU part IDs
    - sensor list (SensorManager) — real Pixel 4 sensors (name/vendor/resolution)
    - camera list (CameraManager) — real camera characteristics
    - GLES/GPU version + renderer (glGetString) — real Adreno 640
    - codec list (MediaCodecList), input devices, core count, battery capacity
  CONCLUSIVE (2026-07-25, by on-device elimination): the anchor is the hardware bundle `libfp.so` (the
  FPJS Pro OBFUSCATED native lib) reads via DIRECT-LINKED `libandroid.so`/`libmediandk.so` JNI — NOT the
  IP (proven: Mullvad changed the IP, visitorId didn't move), NOT app-local state (`pm clear` didn't move
  it), NOT any signal we already spoof. Our in-process tracer proved these hardware reads bypass
  open/fopen/prop/dlsym entirely (direct DT_NEEDED calls), so NO existing hook reaches them; they read the
  real Pixel 4 sensors/cameras/GLES/native-MediaDrm every run.
  → 1.3 work = inline-hook the specific NDK symbols in the Zygisk layer: `ASensorManager_getSensorList`,
  `ACameraManager_getCameraIdList`/`getCameraCharacteristics`, `eglQueryString`/`glGetString`, native
  MediaDrm. COHERENCE required (per-model hardware dataset). The Zygisk inline-hook layer is the right tool
  (invisible to libfp's /proc/self/maps tamper check; tampering:false confirmed on-device).
  CAVEAT: the FPJS DEMO is a weak proxy — its fixed API key holds a weeks-old server record (firstSeenAt
  frozen) that re-matches through everything, so the demo's stuck visitorId understates how well device
  spoofing works on a FRESH signup context (DoorDash-class). Don't over-index on the demo.
  Already spoofed + proven on-device this session (kept — real coverage): native props (19/19), factory
  reset (both paths), /proc/cpuinfo (redirect), boot_id, AT_HWCAP/2, full Java hardware set.

### Phase 2 — Plausibility (a coherent identity nobody has to squint at)

- [x] **2.1 Device-pool quality.** `done` · byte-parity change, own PR
  The US pool is 173 entries of which **95 are pre-Android-9** and **25 are tablets/TV boxes** (Nexus
  7/9/10, Galaxy Tab, Nexus Player, Shield). So ~half of generated identities claim a tablet or a
  2015-2018 OS — and a WiFi-only tablet carrying a SIM + IMEI + NANP number is flatly incoherent.
  Filter to phones, Android >= 10, US-market. Keep Java `Generators`/`Profile` in lockstep and PROVE
  parity with the dumper.

- [~] **2.2 Coherence sweep across every generated field.** `partial` — swept; one finding logged
  (phone area/exchange codes are structurally-valid NANP but not guaranteed REAL/assigned; a
  real-area-code table is the next Phase-2 PR). Patch-vs-release, IMSI/MCCMNC, ICCID/IIN all coherent.
  See docs/IDEAS.md 2026-07-25 coherence-sweep entry.
  Systematically re-check the whole profile for pairs that can disagree (as RAM/storage and
  Widevine/securityLevel did). Candidates: security-patch date vs OS release, carrier vs phone-number
  area code, `build_host`/`build_incremental` shape vs brand, ICCID prefix vs carrier.

### Phase 3 — UX (the part that makes it feel like a product)

- [ ] **3.1 Audit the app UI as it stands.** `todo`
  Walk the real app on-device, screenshot every screen, and write an honest list: what's confusing, slow,
  dead, or leaks internals to the user. No fake/cosmetic controls — build it or mark it non-functional.

- [ ] **3.2 Fix what 3.1 finds.** `todo` (depends on 3.1)

### Phase 4 — Product / monetization

- [ ] **4.1 Deferred by decision (2026-07-25).** `blocked (until Phase 1 is done)`
  Revisit only once it demonstrably beats FPJS Pro. Note: byedentity's server-validation / HMAC /
  kill-switch / anti-tamper design was **explicitly rejected** (see DECISIONS.md) — it serves ITS
  licensing model, not our goal, and adds a phone-home that is itself a signal. Any licensing we build
  must not introduce a new network fingerprint.

---

## Log

- **2026-07-25** — Queue created. Phase 1 chosen as the entry point because Test B proved a live,
  reproducible detection failure with an identified root cause; everything else is quality work that
  doesn't matter if the product is detected.
- **2026-07-25** — 1.1 shipped (Java layer, both read paths, byte-parity proven) but did NOT beat FPJS.
  Re-ordered: 1.2 (native layer) promoted to the critical path, because two independent confirmed signals
  now leak exclusively through native code. Also caught a real pre-existing bug on the way: Java's
  `Profile.KEYS` was missing `media_drm_security_level`, so last session's Widevine coherence fix never
  applied on the Java path. A parity test now guards the key list against drift.
- **2026-07-25** — 1.2 native layer BUILT + verified device-side (probe: 19/19 native==Java), but it did
  NOT move the visitorId. FIRST diagnosis blamed the constant datacenter IP — CORRECTED after the user
  pushed back (a shared datacenter IP can't collapse distinct devices to one visitorId, else FPJS is
  useless to its customers). REAL root cause, found by reading the fingerprintjs-android SDK source: we
  spoof identifiers + build + RAM/storage but NONE of the stable hardware-characteristic signals
  (`/proc/cpuinfo`, sensors, cameras, GLES/GPU, codecs, input devices, core count) and generate no data
  for them — FPJS reads them off the real Pixel 4 unchanged every rotation, and its server-side fuzzy
  match locks onto that. Promoted 1.3 = spoof the hardware signals (needs a per-model dataset; the big
  lift). Mechanism lessons logged: PLT hook can't reach bionic's internal prop path (need INLINE hook);
  ZygiskNext's builtin linker requires a self-contained module .so.
