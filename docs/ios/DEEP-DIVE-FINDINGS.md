# Crane / RootHide / iOS-tweaks — deep-dive findings (primary-source)

**Date:** 2026-08-03 · **Method:** 21-agent source-diving investigation (Crane-Resources, RootHide,
WeaponX/ProjectX, LiveContainer, MGSpoof, Choicy source + reverse-eng + forums) with an adversarial
verify pass on every load-bearing claim. Every conclusion below is tagged PROVEN (source-confirmed,
survived verification) or UNVERIFIED (needs an on-device test, which is named).

This replaces the earlier speculative Crane/attestation claims. **It corrects three things we had wrong.**

## Corrections to earlier beliefs (important)

1. **Crane DOES spoof `identifierForVendor` per container — I previously said it likely leaked.**
   PROVEN from Crane's own `Localizable.strings`: each non-default container defaults its IDFV to that
   container's UUID; the user can set a custom 32-hex UUID or generate one. Changing the *default*
   container's IDFV is even persisted into the OS launch-services (lsd) store and survives with Crane
   unloaded / non-jailbroken. So two Crane containers do **not** share IDFV. (Corroborated: tweakdoor
   changelog, LiveContainer issue #218.)

2. **"App Attest is the ceiling" (in IOS-PORT-FEASIBILITY.md) was WRONG as stated.** App Attest attests
   **app-binary** integrity, not device/environment — a jailbroken device running the *unmodified* app
   binary **passes** attestation, and it does not per-request flag jailbreak (only a delayed probabilistic
   server-side fraud metric). Guardsquare rates it 3/8, **below** Play Integrity (4/8), with documented
   TrollStore/CoreTrust bypasses. The *real* ceilings are (a) server-side fraud/risk SDK scoring and
   (b) OS-level `ubiquityIdentityToken` + server-side DeviceCheck state that **no on-device hook can
   rotate** — those need account/device *management* (a distinct iCloud sign-in per identity, a fresh
   device), not spoofing.

3. **A container manager does NOT broadly spoof device identity.** Crane and LiveContainer touch **only
   IDFV**. Model, serial, UDID, IMEI, sysctl/MobileGestalt hardware, boot time, IDFA, and iCloud identity
   read the **real device across every container**. Specter-iOS is the coherent-hardware layer on top —
   exactly as Specter-Android is to LSPosed.

## What Crane actually does (PROVEN)

- Spoofs **IDFV per container** (the one device-identity signal it touches); persists a default-container
  IDFV into the lsd store (survives unload / no-JB). Custom IDFV = 32-hex UUID.
- Isolates the **data container**; optional **Separate Keychains** toggle (access-group-per-container —
  mechanism plausible, not independently confirmed).
- Per-container **APNs token** (CraneSupport.dylib into apsd/pkd), **Apple-ID/system-account** redirect
  (App-Store-focused, unreliable on iOS 16+), optional per-container **Game Center**.
- App-side hooks (Prevent Sandbox Lookups, Container Protection) via libundirect/libSandy/altlist +
  cranehelperd XPC.

## What Crane does NOT touch → the Specter-iOS job (PROVEN)

No hardware identifier at all: **no model, serial, UDID, IMEI, board-id, `hw.machine`/`hw.model` sysctl,
MobileGestalt keys, boot time, or IDFA.** No `ubiquityIdentityToken` / CloudKit user-record. The public
`libCrane.h` surface is purely container/prefs/path management (multi-source negative confirmed).

Also: even Crane's IDFV spoof is at the **UIDevice level only** — the lower `LSApplicationProxy.
deviceIdentifierForVendor` / liblockdown path can bypass a UIDevice swizzle. A complete IDFV reset must
also cover that path (on a jailbreak: write the lsd store so every read path agrees).

## The Specter-iOS spoof surface (source-proven read paths)

Ordered by value. Each is a read path Crane/RootHide/LiveContainer do **not** cover.

| Signal | Read path | Tweak coverage |
|---|---|---|
| **MobileGestalt** (model/serial/UDID/IMEI/board/HWModel/ProductType/DeviceName) | `MGCopyAnswer` → 8-byte thunk → `MGCopyAnswer_internal` | **Cannot hook the exported symbol (SIGILLs).** Scan prologue for first unconditional `B` (`word & 0xFC000000 == 0x14000000`), decode imm26, on arm64e wrap `make_sym_readable`/`make_sym_callable` (PAC strip/sign), `MSHookFunction` the internal worker; deobfuscate iOS-15+ keys via a MGKeys table. |
| **sysctl / sysctlbyname** (hw.machine/model/ncpu/memsize/cpu.brand, kern.osversion/boottime) | C funcs | `%hookf`/MSHookFunction on both string and numeric-MIB paths |
| **uname(2)** | C func | rewrite `utsname.machine` |
| **IORegistry** (IOPlatformSerialNumber/UUID, MAC, board-id) | `IORegistryEntryCreateCFProperty` **and `...Properties` (plural)** | hook both |
| **IDFV** | `-[UIDevice identifierForVendor]` **+** `LSApplicationProxy.deviceIdentifierForVendor`/liblockdown | swizzle UIDevice (Crane covers this one) AND cover the lower path |
| **IDFA** | `ASIdentifierManager.advertisingIdentifier` | ObjC hook, per-profile UUID |
| **GSSystemGetSerialNo** (private) | C func | MSHookFunction (second serial path) |
| **storage** | `statfs`/`statfs64`/`getfsstat` | model-coherent tiers (NOT a fixed fallback — a ProjectX smell) |
| **boot time** | `NSProcessInfo.systemUptime`, `kern.boottime` | cached value ≤ real uptime (WeaponX UptimeManager pattern) |
| **device name** | `gethostname`, MG `ComputerName`/`UserAssignedDeviceName` | per-profile (NEVER a constant like WeaponX's literal "SpoofedDevice") |

**Our actual edge — the COHERENCE ENGINE:** ProjectX (WeaponX) proves per-signal hooking but generates
serial, storage, and IMEI **independently** of the model — that incoherence is the exact gap. Specter-iOS
derives every hardware field from one per-model table (the `ios/core` catalog + generator, already built).

**Scoping (LSPosed-scope analog):** ship an ElleKit Filter plist listing only the target Bundles +
a `%ctor` bundle-ID bail guard so the dylib self-disables if loaded into the wrong process.

## RootHide reality (PROVEN)

RootHide hides the JB by **selective absence** — a hidden app spawns into a genuinely clean process
(zero injected dylibs), so client-side JB checks pass. But the jailbreak stays fully active at the kernel
and for other processes, and **hardware/server signals are untouched.** It defeats process-level
path/URL-scheme/dylib-scan detection, **not** kernel-level, behavioral, or hardware-attestation signals.

## Detection facts that matter (PROVEN, OWASP MASTG)

- A sandboxed iOS 16 app **cannot** enumerate other processes (`sysctl KERN_PROC_ALL` → EPERM since iOS 9)
  **but CAN `connect()` to `127.0.0.1:27042` and D-Bus-probe frida-server.** → leftover frida-server IS a
  real detector; our tracer must run on a **non-default port** (or frida-server injected mode / ElleKit).
- The dyld image scan catches Frida Gadget + ElleKit/Substrate/Substitute (DYLD-injected) but **not**
  frida-server injected mode.

## Still UNVERIFIED — the decisive on-device tests (ranked)

1. **Instrument one real login** of the target app: hook `open`/`stat`/`access`, `canOpenURL`,
   `_dyld_get_image_name`, `sysctl`, `MGCopyAnswer_internal`, IORegistry getters, and `connect()` — logs
   the exact spoof-and-hide surface and settles process-level vs server-side detection in one pass.
2. **Isolate the iPhone-8 loop trigger** by A/B: frida-server on default 27042 vs a non-default port; hook
   `connect()` to blackhole `127.0.0.1:27042`; ElleKit (dyld-visible) vs frida injected. Whichever change
   stops the loop names the detector.
3. **App Attest enforcement:** hook `DCAppAttestService.attestKey`/`generateAssertion` at login — is it
   called, does failing it change the reject? (attestation-driven vs generic JB/Frida detection).
4. **iCloud linking leak:** print `FileManager.ubiquityIdentityToken` + `CKContainer` userRecordID in two
   containers on the same device+account — if identical, no container/spoof tool closes it (needs distinct
   iCloud sign-ins per identity).
5. **MGCopyAnswer hook on the target build:** dump first 16 bytes at `dlsym(_MGCopyAnswer)`, verify the
   internal-worker resolve (scan-for-first-B + PAC re-sign) before committing the tweak's most fragile code.

Full agent-by-agent findings + verification verdicts: workflow `wf_d5c48307-257`
(`tasks/whhkouf6n.output`).
