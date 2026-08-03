# Cash App iOS — device-read trace (first capture)

**Date:** 2026-08-03 · **Device:** SE2 (iPhone12,8, iOS 16.3.1, RootHide Dopamine, tester-se2 container)
**Method:** frida-server 16.1.4 (rootless) + `ios/trace/trace_cashapp.js`, spawn mode, 40s, **pre-login
splash only (no interaction, no account)**. Artifact: `ios/trace/captures/cashapp_se2_prelogin_2026-08-03.json`.

This is the launch/pre-login surface — the account-independent device reads that fire before any UI
interaction. The heavier fraud stack (Persona KYC, MiSnap liveness, App Attest *calls*) only fires as
the login/verification flow is driven, which was not done here.

## What Cash App read at launch (PROVEN, on-device)

| Signal | Read path | Value seen | Requested by |
|---|---|---|---|
| Model | `sysctlbyname hw.machine` | `iPhone12,8` | CoreFoundation |
| Model | `MGCopyAnswer ProductType` | `iPhone12,8` | UIKitCore |
| Device class | `MGCopyAnswer DeviceClassNumber` | `1` (iPhone) | UIKitCore |
| RAM | `sysctlbyname hw.memsize` | `3103604736` | AGXMetalA13 (GPU stack) |
| CPU count | `sysctlbyname hw.ncpu` / `hw.activecpu` | `6` | dyld / CoreData |
| CPU features | `sysctlbyname hw.optional.arm.FEAT_SHA512` | `1` | dyld |
| OS version | `sysctlbyname kern.osproductversion` | `16.3.1` | libswiftCore |
| **Kernel security** | `sysctlbyname kern.secure_kernel` | `1` | libsystem_c | 
| GPU model | `IORegistry MetalPluginName` / `MetalPluginClassName` | `AGXMetalA13` / `AGXA13Device` | **ThreeDS_SDK** |
| Product id | `IORegistry product-id` | 20-byte blob | libMobileGestalt |
| Build flags | `MGGetBoolAnswer InternalBuild` + 2 obfuscated keys | null/false | UIKitCore, BackBoard |
| Release type | `MGCopyAnswer ReleaseType` | null | BaseBoard/UIKitCore |
| Kernel `sysctl` mib[1.14.1.<pid>] | `sysctl KERN_PROC_PID` | (buf) | CoreFoundation |

## Headline findings

1. **App Attest + DeviceCheck are LINKED.** `DCDevice` and `DCAppAttestService` Objective-C classes are
   present in the process — Cash App bundles Apple's DeviceCheck.framework. That means the **attestation
   ceiling is real for Cash App** (per the feasibility doc §5). Caveat: "class present" proves it *links*
   the framework (so almost certainly uses it), not that it *called* `attestKey`/`generateToken` — those
   calls weren't observed at pre-login and need the flow driven to confirm active enforcement. This is the
   single most important thing to confirm next, because if Cash hard-gates App Attest server-side, per-app
   signal spoofing alone won't carry a rotated identity.

2. **The EMVCo 3DS SDK (`ThreeDS_SDK`) is an active device fingerprinter**, not just a payment-auth
   component — it reads the GPU identity out of IORegistry at launch. Per the EMVCo spec it also collects
   model/OS/locale/TZ/screen. This is a concrete, hookable target.

3. **`kern.secure_kernel` is read** — a jailbreak/kernel-integrity probe. On a healthy JB-hidden device it
   returns `1` (secure); a spoofer must keep it consistent with a non-jailbroken story. (Value here = 1,
   i.e. RootHide is presenting a secure kernel.)

4. **The launch read-set maps exactly onto our coherence set** (model↔board↔RAM↔CPU↔GPU↔OS). Every value
   read (`iPhone12,8`, 3GB-class memsize, 6 cores, A13 GPU, 16.3.1) must stay mutually consistent — which
   is precisely the invariant Specter-iOS enforces and no surveyed tweak does.

## Not yet captured (needs the flow driven, on a throwaway)
- Whether App Attest / DeviceCheck actually *fire* (`generateKey`/`attestKey`/`generateToken`).
- AppsFlyer, Persona, Mitek MiSnap, Bugsnag reads (collect after consent/login, not at splash).
- IDFV/IDFA/keychain reads (fire on first identity use, typically post-consent).
- `kern.boottime`, screen/battery/locale/TZ/carrier (not observed at bare splash — expected later).

## Identifier probe — what Cash App gets in one container (tester-se2, measured)

Active read via `ios/trace/probe_ids.js` (not passive) — the values a fingerprinter would receive:

| Identifier | Value in tester-se2 | Notes |
|---|---|---|
| identifierForVendor | `7D388688-8F26-4A54-921E-D6137B2A40F4` | the per-vendor device ID — **the primary cross-container linker** |
| iCloud ubiquityIdentityToken | `0xbd0548d2aa84ffa113ec129d6e1944df…` | present → iCloud signed in; secondary linker |
| advertisingIdentifier | `00000000-…` | ATT not authorized (expected) |
| MG UniqueDeviceID / SerialNumber | **None — access denied** | sandboxed Cash is refused these even jailbroken; NOT a leak to worry about |
| hw.machine / hw.model | iPhone12,8 / D79AP | hardware, shared across containers |
| hw.memsize / ncpu | 3103604736 / 6 | hardware |
| kern.boottime | 1785083121.926027 | µs boot instant — shared across all containers this boot |
| device name | iPhoneSE2 | shared |

Container-A baseline saved to `ios/trace/captures/ids_tester-se2_2026-08-03.json`. **The decisive A/B test is still pending:** switch Crane to a second container, re-run the probe, and diff — that proves whether Crane's "Separate System Accounts" already rotates IDFV / the iCloud token, or whether Specter-iOS must. (Being resolved from source in the Crane/RootHide deep-dive.)

## iPhone-8 login-loop investigation (2026-08-03)

Symptom: real account on iPhone 8 — email → OTP → PIN → secure link → "Welcome to Cash App" → **bounces back to the login screen**, repeatedly.

Ruled out, in order:
- **Not us / not this session** — iPhone 8 was untouched; but a *prior* session had left `frida-server` running as a boot daemon since Jul 31. Stopped + disabled it. **Loop persisted.**
- **Not the network** — user confirmed OTP email + sign-in link work; detectme.pro showed IP **Clean / Residential Home ISP** (74.139.71.166). The red items (WSS-latency, TCP/IP-fingerprint mismatch) are transport-level proxy tells that (a) can't be fixed from the phone and (b) fintech apps rarely check. TZ mismatch was "Minor" — not causal.
- **Not the container** — looped in a fresh Crane container too.

**Conclusion (high confidence):** the loop is a **server-side reject of the account itself** — auth succeeds, then the risk layer refuses to seat the session. Consistent with the account having been flagged when it was created on Android. Nothing device-side un-flags a server-tagged account; it travels with the account, not the device. (Open sub-question the deep-dive addresses: whether Cash *also* soft-rejects on Frida/JB detection — relevant because it means our tracer must never run on a real-account device.)

## Reproduce
`ios/README.md` has setup + run steps. Same harness works on any of the three phones; capture only on a
tester/throwaway container. The tracer hooks are the shortlist of paths Specter-iOS must cover.
