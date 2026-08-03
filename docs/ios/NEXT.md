# Specter-iOS — the honest ceiling + what's left

## The inject-vs-detect tension (the real open question against Cash)

Everything proven so far (`EFFICACY-RESULT.md`, ALL SPOOFED ✅) was measured against **SpecterProbe** and a
frida read — neither of which scans its own process for injected code. **Cash App does.** On Android its
trace showed repeated `/proc/self/maps` reads; the iOS equivalent is scanning the **dyld image list**
(`_dyld_get_image_name` / `_dyld_image_count`), which — per the deep-dive — **catches DYLD-injected tweaks
(ElleKit/Substrate)**. So:

> To spoof Cash we must inject `SpecterTweak.dylib` into it. But an injected dylib is visible in Cash's own
> image list. **Hooking Cash and staying invisible to Cash's tamper scan are in tension** — the same
> "hook XOR hide" wall the Pixel-4a hit (the workflow's PIF agent confirmed it for Android: Shamiko hides by
> *not injecting*, so a denylisted app can't also be LSPosed-hooked; you choose passing OR hooking).

**This is the #1 unverified question for the actual goal.** What "ALL SPOOFED" proves: the spoofing
*mechanism* is correct and complete for the readable signal set. What it does NOT yet prove: that Cash-iOS
launches and trusts the session *with the tweak injected*. That needs an on-device test against real Cash
in a throwaway container, watching whether Cash blocks on finding the dylib.

Mitigations to try if Cash detects the injection (in order):
1. Rename `SpecterTweak.dylib` to something innocuous + strip the `Specter` symbol/log strings (defeats
   name-based detection; not "unexpected dylib" heuristics).
2. Lean on RootHide's per-process controls / a stealthier load so the dylib isn't in the standard image list.
3. If Cash hard-checks its image list no matter what: the injection approach can't beat Cash's tamper gate,
   and the honest strategy is per-account real devices + data/proxy isolation (Crane) rather than one phone
   spoofed N ways. Measure before concluding.

## Remaining hooks (low value; vetted code exists)

The finish-workflow (`wq2k6i2wi` output) has ready code for these — add if/when needed:
- **statfs / statfs64 / fstatfs** — model-coherent storage tiers (weak signal, ~2-3 bits).
- **IDFA** (`ASIdentifierManager`) — opt-in; real devices return all-zeros without ATT, so spoofing to a
  non-zero UUID when ATT is denied is itself a tell. Only spoof when ATT-authorized.
- **mach_absolute_time / clock_gettime coherence** — boot-time is currently coherent across `KERN_BOOTTIME`
  + `NSProcessInfo.systemUptime`, but a reader computing uptime from raw `mach_absolute_time` would see the
  unshifted value. High-frequency hook = perf/stability risk; only add if a target cross-checks it.
- IORegistry `IOPlatformSerialNumber`/`IOPlatformUUID`, `GSSystemGetSerialNo` — **SKIP**: the probe proved
  these are entitlement-denied to sandboxed App Store apps (return nil), so spoofing them is moot vs Cash.

## Productization (the path to a shippable tool)

- **Management app** (the Android app analog): pick device/OS, generate a coherent profile, deploy it into
  each target app's container, toggle scope — the UI over `ios/core` + the container-profile mechanism.
- **libSandy central profile store** so the tweak can read one central profile instead of a per-container
  file copy (Crane uses libSandy for exactly this sandbox-extension need).
- **One-tap install** (ElleKit dep + tweak + probe + scope), mirroring the Android one-tap-installer goal.
