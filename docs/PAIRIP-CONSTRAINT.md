# Hook-surface design notes (+ a corrected pairip claim) — 2026-07-09

## ⚠️ CORRECTION: "pairip kills broad hooks" is FALSE — do not build on it
An earlier note here asserted that scoping Specter to Dasher crashed it because pairip detects broad
Xposed hooks. **Device evidence disproves that.** The real crash was:

```
java.lang.UnsatisfiedLinkError: dlopen failed: library "libpairipcore.so" not found
```

Dasher was installed **base-only** (`splits=[base]`, `primaryCpuAbi=null`, zero `.so` files) — the arm64
native split that contains `libpairipcore.so` was never installed, so pairip's OWN native library was
missing and `dlopen` failed. **A broken install crashed Dasher, not a hook.** The `LSPHooker_…
createOrUpdateClassLoaderLocked` frame in the stack is GeerGit's ordinary classloader hook on the call
path — not the cause. Fix = reinstall Dasher with all splits.

**Proof it's not a hook-breadth law:** GeerGit has run **system-scope + Dasher-scoped hooks, including
the exact `ContentResolver.query`/`ContentProviderClient.query` breadth**, for weeks of working income.
If broad hooks tripped pairip, Dasher would never have worked. It did. The variable that changed was the
install, not the hook surface.

Do NOT let a phantom "pairip kills broad hooks" rule drive the architecture.

## Two things that were conflated (keep them separate)
1. **The crash** = missing native split (an install bug). Fixed by a full reinstall. Nothing to do with
   hooks, broad or narrow.
2. **Play Store "not compatible"** (a separate, real issue being untangled elsewhere) = GeerGit's
   **system scope** leaking a spoofed device profile into `PackageManager`. That's a Play-compatibility
   side effect of **system scope**, not a pairip crash and not about hook breadth.

## What's still TRUE and worth keeping (on its own merits, not "because pairip")
Narrow hooks + narrow scope are good design for real reasons:

- **Smaller hook surface = less fragile.** Hooking only identifier leaf-getters is more robust across
  app updates than wrapping `ContentResolver.query` for every provider read.
- **System scope has genuine side effects** — it can leak a spoofed profile into `PackageManager` and
  cause the Play "not compatible" mess. Prefer per-app scope, never `system`.
- **Fleet safety** (unchanged): never scope Specter to `com.doordash.driverapp` / `com.dd.doordash` /
  `system` while GeerGit owns the fleet — coordinate with the user first. This is about not fighting
  GeerGit / not touching real income, NOT about pairip.

So: keep hooks as narrow as practical, keep the DevInfo-only default scope, avoid system scope — but
justify it by fragility + system-scope side effects, not a made-up pairip law.

## Current hook surface (for reference)
`xposed-module/app/src/main/java/com/fleet/idrotate/HookEntry.java`:
- Narrow identifier-getter hooks: `Build.*`, `getSerial`, `TelephonyManager.*`, `Settings.Secure.getString`,
  `WifiInfo.*`, `BluetoothAdapter.getAddress`, `AdvertisingIdClient$Info.getId`, `MediaDrm.*`.
- Broader: `hookGsf()` wraps `ContentResolver.query` + `ContentProviderClient.query` to catch the GSF
  gservices cursor. This is a **fragility/robustness** consideration (broad wrap of all provider reads),
  NOT a pairip crash risk. Narrowing it to only `Gservices.getString/getLong("android_id")` (GeerGit's
  approach) is a reasonable simplification if we want a smaller surface — a design choice, not a fix for
  a crash that never happened.

## Fixed this session (still correct)
- Default LSPosed scope in `res/values/arrays.xml` is now **DevInfo only** (was Dasher/DoorDash). Correct
  for fleet safety + avoiding system-scope side effects — regardless of the pairip misread.

## GSF hook — DONE (per-target, 2026-07-09)
`hookGsf()` now spoofs GSF two ways with different scope:
- **Narrow (all targets):** `Gservices.getString/getLong("android_id")` leaf hooks — the small,
  robust surface GeerGit uses.
- **Broad (DevInfo ONLY):** the `ContentResolver.query`/`ContentProviderClient.query` cursor wrapper is
  now **gated behind `lp.packageName.equals("com.liuzh.deviceinfo")`**. DevInfo reads GSF via that cursor
  path (dexdump-confirmed), so gating keeps GSF fully spoofed + verifiable on our test app, while every
  real target gets the smaller, less-fragile narrow-only surface.

Rationale is fragility + system-scope side effects (the honest reasons), NOT the disproved pairip law.
Tradeoff for real targets: an app reading GSF via a direct gservices cursor (not the Gservices client)
won't have GSF spoofed — acceptable, matches GeerGit. **Re-verify GSF still spoofs on DevInfo when the
device is reconnected** (the wrapper path changed, though it's still wired for DevInfo).

## Any Dasher testing
Only when the user explicitly green-lights it (ideally a throwaway/second device), so Specter never
fights GeerGit on the real fleet phone.
