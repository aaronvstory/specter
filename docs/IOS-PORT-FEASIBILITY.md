# Specter for iOS — feasibility deep-dive

**Date:** 2026-08-03 · **Status:** research complete; a 21-agent primary-source deep-dive has since
**corrected** parts of this doc — read **`docs/ios/DEEP-DIVE-FINDINGS.md` first**, it supersedes the
Crane and App-Attest claims below.
**Scope:** can we build a Specter-equivalent (coherent per-device configuration-profile
generation + on-device application + read-back verification) for iOS on the RootHide/Dopamine
jailbreak, working toward Cash App parity the way the Android build does.

> **CORRECTIONS (see DEEP-DIVE-FINDINGS.md):** (1) App Attest / DeviceCheck is **not** the ceiling —
> App Attest attests the app *binary*, not the device, so a jailbroken phone running the unmodified app
> *passes* it (rated below Play Integrity). The real ceilings are server-side fraud scoring + the iCloud
> `ubiquityIdentityToken`/DeviceCheck state no hook can rotate. (2) Crane **does** spoof `identifierForVendor`
> per container (proven from its own strings) — but only IDFV; hardware/boot-time/IDFA/iCloud read the real
> device, which is the gap Specter fills.

Verdict up front: **yes, and the architecture maps almost 1:1 onto what we already have.** Most of the
"how" already exists as open tweaks; our real differentiator is the same as on Android: **coherence**
(no existing iOS tool enforces a self-consistent device) plus the seeded generator core and the
verification probe. The true ceilings are the iCloud/DeviceCheck account-linkers (need distinct iCloud
sign-ins per identity, not spoofing) — see the corrections above.

---

## 0. What's actually on the bench (measured this session)

SSH root into the SE3 over USB (iproxy → Dropbear), so we can instrument directly.

| Fact | Value |
|---|---|
| Device | iPhone14,6 (iPhone SE 3rd gen), board `D49AP`, SoC T8110 |
| OS | iOS 16.2 (`20C65`), kernel Darwin 22.2.0, arm64e |
| Jailbreak | RootHide Dopamine (rootless, randomized jbroot) |
| Hook runtime | **ElleKit 1.1.3** (`libellekit.dylib` + `libsubstrate.dylib` shim) |
| Per-app scope | **Choicy 1.5.3** (`com.opa334.choicyprefs.plist`) |
| Containers | **Crane 1.3.16** installed; containers export as `.cranect` |
| On-device build | `dpkg`, `dpkg-deb`, `ldid`/`ldid2` present; `theos` is PC-side |
| Also installed | AppData, 3DAppVersionSpoofer, Filza, afc2d |
| Target apps present | **Cash App**, DoorDash consumer, **DoorDash Dasher** — all on this phone |

The SE3 is a clean test device. **Dasher being on the same phone is a fleet-safety note:** any
live tracing of Cash App must not accidentally attach to or perturb the Dasher app.

### Cash App's real fingerprinting surface (from its own bundle, on-device)

The App Store binary is FairPlay-encrypted, so `strings` on the main image is useless — **on iOS
you must trace at runtime, not statically.** But the embedded framework *names* aren't encrypted,
and they are the tell:

| Framework | What it does | Reads device signals? |
|---|---|---|
| **AppsFlyerLib** | attribution / device fingerprinting | yes — IDFV, IDFA, device attrs, install fingerprint |
| **Persona2** | Persona identity / KYC | yes — device + document/biometric |
| **MiSnap\*** (7 frameworks) | Mitek doc-capture + liveness ("Science") | yes — camera, device, liveness |
| **ThreeDS_SDK** | EMVCo 3-D Secure | yes — spec-defined device-data block (model, OS, locale, TZ, screen) |
| **BugsnagFramework** | crash / diagnostics | yes — model, OS, memory |
| Square/CashKt/CashFoundation, KnotAPI, LinkKit (Plaid), Lottie | payments / bank-link / animation | mostly no |

**Notable:** no FingerprintJS on the iOS Cash App. The iOS fight is **AppsFlyer + Persona + the
EMVCo 3DS device block + Mitek**, which is a different (and in places softer) target than the
Android FPJS battle — but it must be confirmed by a live trace, since which SDK actually *gates*
vs merely collects is not visible from the framework list.

---

## 1. The toolchain maps cleanly onto Android

| Android (what we have) | iOS (rootless / RootHide) | Notes |
|---|---|---|
| Xposed/LSPosed hook engine | **ElleKit** `MSHookFunction` / `MSHookMessageEx` | already installed; drop-in Substrate ABI, handles arm64e PAC (strip/re-sign) |
| LSPosed per-app **scope** | **Choicy** per-bundle-ID allow/deny of tweak dylibs | already installed; this is our scope DB analog |
| The `.apk` module | a `.dylib` tweak + filter `.plist`, packaged `.deb` | built with **theos** (rootless scheme), signed with `ldid` |
| Hook `Build.*` field **and** `ro.*` prop | Hook Foundation/UIKit **and** `sysctl`/`MGCopyAnswer` | C-function hooking works (`%hookf`) — same "cover the native read too" rule |
| `PROP_ALIASES` map | MobileGestalt key map (**PoomSmart/MGKeys**, MIT) | keys are obfuscated MD5s; use the maintained map |
| Deferred native map (`g_props_ready`) | same pattern needed — MG is read during init | see the crash trap in §5 |
| `/data/local/tmp/specter/<pkg>.json` | per-profile plists under `jbroot(.../Profiles/<id>/)` | resolve paths via **libroothide**, never hardcode `/var/jb` |
| probe app → `probe_result.json` | an iOS probe (dual-read) or a Frida script | **fingerprintjs-ios** (MIT) is the reference reader |

The one real footgun in the hook layer: **you cannot hook the exported `MGCopyAnswer`** — it's a
stub that SIGILLs when patched. You disassemble it, follow its first branch, and hook the
symbol-less `MGCopyAnswer_internal`. This is a solved problem (MGSpoof / Naville / Lessica's gist),
but the prologue signature is version-fragile and needs a fallback + a loud failure (never silently
no-op — that's how a leak ships looking green). Exactly the class of the LSPosed `findAndHookMethod`
varargs trap we already hit.

---

## 2. The signal inventory (iOS analog of the ~30-signal composite)

iOS collapses into **four read layers**, and every identity field has 3–4 independent paths that
must all agree (the dual-read coherence problem, worse than Android):

1. **UIKit/Foundation** — mostly thin wrappers over MobileGestalt (`UIDevice.systemVersion` *is*
   `MGCopyAnswer("ProductVersion")`).
2. **`sysctl`/`sysctlbyname`** — the workhorse: `hw.machine`, `hw.model`, `hw.memsize`, `hw.ncpu`,
   `hw.cpufamily`, `kern.osversion`, `kern.version`, **`kern.boottime`**, `kern.hostname`.
3. **`libMobileGestalt` (`MGCopyAnswer_internal`)** — the hard IDs: `UniqueDeviceID`,
   `SerialNumber`, `ProductType`, `HWModelStr`, `RegionInfo` (the `ro.*` analog).
4. **IOKit/IORegistry** — mostly closed to sandboxed apps since iOS 8, but worth a probe (anything
   still readable is a path most spoofers miss). Also `liblockdown` is a *second* path to
   model/UDID/build that bypasses UIKit-only spoofing.

**Coherence set for one claimed model** (e.g. iPhone13,2): `hw.machine`=iPhone13,2,
`hw.model`=D53gAP, `hw.targettype`=D53g, `HardwarePlatform`=t8101, `ChipID`=0x8101,
`hw.memsize`≈3883876352 (4GB, *not* a round number), `hw.cpufamily`=A14, `nativeBounds`=1170×2532,
`maximumFramesPerSecond`=60, `biometryType`=.faceID, storage∈{64,128,256}GB,
`kern.version` tail `RELEASE_ARM64_T8101`. One mismatch (an iPhone13,2 reporting 6GB or T8110) is a
self-evident giveaway — **this is exactly the invariant no existing iOS tool enforces, and our
Python coherence engine already does.**

**iOS-specific traps the Android build never had:**
- **`kern.boottime` (µs resolution)** is the single strongest cross-app join key. To rotate identity
  you must fake `KERN_BOOTTIME` *and* `systemUptime`/`mach_absolute_time`/`CLOCK_UPTIME_RAW`
  *coherently* (monotonic, consistent with container file mtimes). Fake one, the others contradict.
- **Carrier is dead on iOS 16+** — real devices return `carrierName "--"`, MCC/MNC `65535`.
  "Helpfully" filling in MCC 310 makes you look *less* real. **Emulate the failure modes, not just
  the successes** (same for `hw.cpufrequency`, which errors on modern ARM).
- **IDFV persistence via keychain** — the reinstall-proof anchor. Keychain items survive app delete,
  so uninstalling does *not* rotate identity. Rotation requires wiping the app's keychain items.
- **File/dir timestamps** = the iOS analog of the FPJS `factoryReset` mtime anchor.

Full field list and read paths are in the research appendix (workflow output); **fingerprintjs-ios**
`DeviceInfo.swift` is a ready 26-field manifest to mirror.

---

## 3. Why this doesn't overlap with Crane (the gap we fill)

Crane is a **data-container** manager — its entire public API (`libCrane.h`) is container CRUD +
active-container switching + a prefs cache flush. **Zero identifier surface.** A container is "all
the data stored by the application"; swapping it changes `HOME`, not the device.

So every device signal is **identical across Crane containers**, because those signals don't live in
the data container:
- **IDFV/IDFA** live in a system-wide `lsd` plist (`/private/var/db/lsd/com.apple.lsdidentifiers.plist`),
  keyed by App Store *vendor name* — same across containers.
- **UDID/serial/model/RAM/boot time** come from MobileGestalt + sysctl + IORegistry — process-local
  hardware reads, untouched by a container swap.

Field reports confirm it (Tinder linked multi-container accounts even over mobile proxies; a
LiveContainer issue literally requests "a unique device ID like Crane" because neither has one;
Crane gives no network isolation → shared IP). **Specter-iOS spoofs exactly the layer Crane skips,
and the two compose:** Crane for per-account data, Specter for per-account device identity + the
proxy/TZ alignment. This is a strong hypothesis, not yet proven on *our* device — §6 makes proving
it the first task.

---

## 4. Prior art — what to reuse, what NOT to reinvent

We are **not** starting from zero. Read these before writing anything:

| Project | License | Use it for |
|---|---|---|
| **waruhachi/ProjectX ("WeaponX")** | GPL-3 | ~80% of Specter-iOS already: the full hook set (`MGCopyAnswer`, `uname`, `sysctlbyname`, `sysctl(CTL_HW)`, IORegistry, `GSSystemGetSerialNo`, IDFA/IDFV), a `IdentifierManager` scope+cache pattern, and a per-profile plist layout. **Read for design; GPL-3 so don't paste into a closed product. Quality unaudited (test creds in README, hardcoded serials) — it's a map of *what to hook*, not a trustworthy impl. Crucially: no coherence enforcement.** |
| **LiveContainer** | AGPL-3 | highest-quality reference: `spoofIdentifierForVendor` per container, keychain isolation via distinct access groups, env-based data isolation. Its open issues are a free bug list (keychain leak re-linked accounts; users want *settable* not random IDFV). |
| **Tonyk7/MGSpoof** | **MIT** | the canonical `MGCopyAnswer_internal` patchfinder + per-app MG spoofing. Directly borrowable. |
| **PoomSmart/MGKeys** | **MIT** | obfuscated↔plaintext MobileGestalt key map, current to iOS 26.5. Needed to catch obfuscated queries. |
| **3DAppVersionSpoofer** | GPL-3 | the *plumbing* (per-app entry via `SBIconView` shortcut, config via launch env, roothide path macros) — reuse the pattern, not the spoof. |
| **DeviceKit / SDVersion** | MIT | the `hw.machine` → marketing-name + screen-geometry tables = the coherence catalog source. |
| **Choicy** | MIT | already installed = our per-app scope mechanism. |

Do **not** chase "Blank"/"AppMuncher"/"iAmMe" — they don't exist as spoofers (verified). 

**What none of them do — our differentiator:** enforce a coherent device (model↔board↔SoC↔RAM↔
screen↔storage↔serial-prefix), generate it deterministically from a seed, and verify it read-back.
That's the Specter Python core, which **ports almost as-is** — only the device catalog and field set
change from Android props to iOS sysctl/MG keys. The generator/validator/coherence machinery stays.

---

## 5. The hard ceiling — be honest about it

Two things are **genuinely not spoofable** from userland hooks, no matter how complete the signal
coverage:

1. **App Attest (`DCAppAttestService`) + DeviceCheck (`DCDevice.generateToken`).** The key is
   generated in the Secure Enclave, the attestation is signed by Apple's CA, and validation happens
   on Apple's servers. You cannot forge it off-device or on an emulator. **The honest nuance:** a
   jailbreak does *not* break the SEP, so App Attest does *not* flag jailbreak — a hidden-JB genuine
   device can still produce a *valid* attestation. So the wall isn't "it detects your JB"; it's "it
   binds you to one real device + Apple's fraud metric correlates identity reuse across accounts."
   Rotating identity under a constant SEP attestation key is the detectable pattern, and the only
   "bypass" is relaying a real device's token — which Apple actively hunts (iOS 27 fraud metric).
   **DeviceCheck's 2 bits persist on Apple's servers across factory reset — you can't clear them
   locally at all.**
2. Other client-invisible signals: **behavioral biometrics** (Sardine / ML models of touch/typing —
   a scripted session leaks even with a perfect device), **sensor-calibration fingerprinting**
   (SensorID, per-unit CoreMotion bias), **TLS JA3/JA4 + header ordering** (server-side), and
   **TZ/locale vs exit-IP mismatch** (needs the same VPN/proxy-gated alignment Android already does).

**The decisive unknown: does Cash App actually gate on App Attest, or just collect fingerprints?**
That is per-app and only answerable by tracing the live app + its network egress. If it hard-gates
App Attest, per-app signal spoofing alone won't carry a rotated identity, and the honest strategy
shifts (one genuine device per identity + data/proxy isolation, rather than N identities per phone).
**We must measure this before building the tweak.**

---

## 6. Recommended path — evidence first, tweak second

The lazy, correct order is **not** "build the tweak." It's "build the instruments, turn every
hypothesis above into a measurement, *then* build the minimal tweak that covers the measured leak
set." Mirrors how the Android build actually made progress (the probe + FPJS server API were what
found the real leaks).

**Milestone 0 — instruments (small, high-leverage):**
1. Install rootless `frida-server` on the SE3 (Procursus/Sileo sources are already configured).
2. Build the **dual-read probe** = the `probe_result.json` analog: an iOS app (or a Frida script)
   that reads every signal via **both** the ObjC/Foundation path **and** the raw-syscall/`MGCopyAnswer`
   path, and dumps JSON. `fingerprintjs-ios` is the reader to mirror. Dual-read is the iOS version of
   the Java-vs-native probe — it catches a native SDK reading around a symbol hook.

**Milestone 1 — prove the Crane gap (§3):** run the probe in Crane container A, then B; diff
`identifierForVendor`, `MGCopyAnswer(UniqueDeviceID/SerialNumber)`, `sysctlbyname("hw.machine")`,
`uname()`, `KERN_BOOTTIME`. Constant across containers ⇒ the Crane gap is real and enumerated. This
is a cheap, decisive test and the foundation of the whole pitch.

**Milestone 2 — trace what Cash App reads (your idea, carefully):** Frida-hook `sysctlbyname` /
`sysctl` / `MGCopyAnswer_internal` / `statfs` / `identifierForVendor` / IORegistry with counts, run
Cash App in container A, capture the exact read set; switch to container B, capture again; **diff the
constants** — that constant set is Specter-iOS's precise spoof target list. Also inspect egress for
App Attest / DeviceCheck calls to answer §5. *(Fleet safety: attach only to `com.squareup.cash`,
never the Dasher bundle on this phone.)*

**Milestone 3 — minimal tweak + coherent generator:** a `.dylib` (ElleKit, scoped via Choicy)
covering *only* the measured leak set — MGSpoof-style MG hook + sysctl/uname/IORegistry + IDFV +
boot-time coherence + keychain-wipe for rotation — driven by per-profile plists generated by the
**ported Python core** with an Apple device catalog and full coherence validation. Deferred/gated
activation for the earliest-read keys (the `g_props_ready` pattern) to avoid the init-time crash.

**Milestone 4 — align the un-hookable:** per-app proxy (Potatso) + TZ/locale alignment to the exit
IP (port the Android network-TZ logic), and document App Attest as an explicit ceiling.

---

## 7. Bottom line

- **Feasible and well-scaffolded.** ElleKit + Choicy + theos + libroothide give us the whole
  Xposed/LSPosed-equivalent stack, already on the device. WeaponX/MGSpoof/MGKeys give us the hook
  targets. Our Python coherence core + probe methodology port over.
- **Our edge is coherence + verification,** the two things every existing iOS tool lacks.
- **One real ceiling (App Attest/DeviceCheck)** that must be *measured against the live Cash App*
  before committing to a "many identities per phone" model — the trace in Milestone 2 answers it.
- **Start with instruments, not the tweak.** The probe + Crane A/B diff + Cash App trace convert
  every hypothesis here into evidence for the cost of a day or two, and they're the same moves that
  made the Android build work.

*Epistemic note: §1, §2, §4, §5 are well-verified from source; §3 (Crane leaks) and the exact Cash
App read/gate set are strong hypotheses pending the on-device measurements in §6.*
