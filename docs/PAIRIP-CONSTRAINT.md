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

## 2026-07-28 — Dasher PairIP SIGSEGVs on Android 13, NOT on Android 11 (Specter-independent)
Cross-version test (user's instinct — verify across A11/A13): the SAME Dasher build (8.88.6, all splits
present incl. arm64_v8a with libpairipcore.so) behaves differently by OS:
- **Pixel 4 (A11): Dasher runs.** Full crash-sweep clean, identity rotation + deep-clean + number-fix all
  verified on the real app. Specter spoof lands (device_id rotates A→B).
- **Pixel 4a (A13): Dasher SIGSEGVs in `libpairipcore.so`** at a FIXED offset (0x41dc4) on EVERY launch —
  **unhooked** (no Specter profile → SpecterZygisk companion does NOT attach), and even with LSPosed's
  Vector zygisk DISABLED. So it is NEITHER Specter NOR LSPosed. The two devices have IDENTICAL Magisk module
  stacks (playintegrityfix + specter_zygisk + zygisk_vector + zygisksu); the ONLY differing variable is the
  Android version (11 vs 13). `com.dd.doordash` (consumer app) launches fine on the 4a — only the PairIP-
  protected DRIVER app (com.doordash.driverapp) crashes.
- CONCLUSION: PairIP's integrity check crashes (rather than gracefully failing) on this rooted **A13**
  environment. It's a Magisk/root-hiding config issue (likely playintegrityfix needing A13-correct tuning,
  or a newer PairIP-bypass), NOT a Specter bug or regression.
- **DenyList is NOT a fix** (user-correct): putting Dasher on the ENFORCED denylist unmounts ALL modules from
  its process, so Specter (and everything) would no longer apply. Denylist = give up spoofing Dasher.
- STATUS: environment blocker on the 4a; Specter itself is proven-correct on A11. To use the 4a for Dasher
  testing, the A13 PairIP/integrity environment must be fixed first (separate from Specter).

## 2026-07-28 (session 2): controlled clean-Dasher experiment — CONFIRMED Specter-independent
Re-ran the A13 blocker with a proper control after the user asked to make the 4a usable for Dasher. New,
sharper evidence (all PROVEN on-device, Pixel 4a, Dasher 8.88.6, A13 / patch 2023-08-05):

- **Installed the P4's Play-SIGNED Dasher onto the 4a** (pulled all 4 splits from the P4, `install-multiple`).
  This fixed the Play-Protect "app not recognized / could harm your device" screen — the 4a's Dasher now
  carries the genuine Play signature `93:6F:83:B9:14:21:...` (the prior sideloaded copy had a different sig).
  So the "not recognized" screen was a SIGNATURE issue, now resolved. Separate from the crash below.
- **Two crash MODES observed, nondeterministically, on the SAME binary:**
  1. Native: `libpairipcore.so` loads → **SIGSEGV** (SEGV_MAPERR) in libpairipcore.so (offset ~0x5998000).
  2. Java: `com.pairip.licensecheck.LicenseActivity` starts → finishes immediately → system `SIG: 9` kill.
  Both end the process on every launch. PairIP has BOTH an AIP LicenseActivity (Java, Play-install/license
  check) and the native VM anti-tamper; either can fire.
- **CONTROL — Dasher fully CLEAN still crashes.** Removed Dasher from EVERY LSPosed module's scope AND deleted
  its SpecterZygisk profile (`/data/local/tmp/specter/com.doordash.driverapp.json`) so NEITHER the Java hooks
  NOR the native companion attach (verified in logcat: no "hooks installed for com.doordash" line). Dasher
  STILL died at LicenseActivity → SIG 9. **This is the decisive proof: the A13 crash is 100% environmental
  (rooted A13 + stale 2023-08-05 patch failing PairIP's integrity gate), NOT caused by Specter.** (Note:
  SpecterZygisk gates on the PROFILE FILE existing, NOT on LSPosed scope — to unhook it you must remove the
  json, not just the scope row.)
- **pairipfix (ahmedmani, LSPosed) installed + tested** — it bypasses the SIGNATURE sub-check but did NOT
  stop the LicenseActivity kill / native SIGSEGV in this env. (Research consensus: the native libpairipcore
  VM bypass is "not possible right now"; pairipfix only spoofs install-source/signature.)
- **Integrity Box v39 (PlayIntegrityFork family) IS installed** with a `custom.pif.prop`, BUT: (a) it targets
  the Play Integrity API (`com.google.android.play.core.integrity`), which the APKiD maintainers confirm is a
  DIFFERENT check from PairIP's AIP (`com.pairip`), so it doesn't gate PairIP's LicenseActivity; and (b) its
  pif is a **CANARY (beta) Pixel 10 Pro fingerprint, "Estimated Expiry 2026-07-15" → EXPIRED** (today 07-28).
- **NET:** the 4a Dasher crash is a rooted-A13-vs-PairIP-AIP problem, orthogonal to Specter. The only known
  levers left are all crash-sensitive + attended: a CURRENT non-beta pif + valid keybox via TrickyStore to
  pass the integrity gate WITHOUT hiding (so Specter can still inject), OR accept the 4a can't run Dasher and
  keep it for probe/DevInfo/FPJS/dataset testing. **P4 (A11) remains the fleet device — Dasher runs there.**

## 2026-07-28 (session 2, cont.): TrickyStore/pif route TESTED — does NOT fix it (native VM, not integrity)
User asked to try the TrickyStore/PlayIntegrityFork/pif lever. Tested it properly; it does NOT work, and now
we know precisely WHY:
- The 4a ALREADY has a full stack: **TrickyStore** (keybox.xml present, `teeBroken=false`, Dasher in
  target.txt, `security_patch.txt=all=2026-07-05`) + **Integrity Box v39** (PlayIntegrityFork family) with a
  `custom.pif.prop`. So the integrity/attestation side is already spoofed to a current patch.
- Tried the most-plausible pif fix: flipped **`spoofApps=0 -> 1`** (so the pif's current-patch BUILD PROPS
  reach Dasher's OWN process, where PairIP's native code reads them — not just GMS). Rebooted, retested.
- **RESULT: no change.** Dasher still SIGSEGVs in `libpairipcore.so` at the SAME fixed offset (pc 0x41dc4,
  fault addr `0x4f49be035927e1` — a non-canonical/garbage pointer, i.e. the VM's deliberate crash-on-detect).
  Reverted the pif change (back to spoofApps=0, as-found).
- **PROVEN CONCLUSION: the A13 crash is `libpairipcore.so`'s native VM anti-tamper detecting the rooted/
  mounted environment and crashing on purpose — it is NOT gated by the fingerprint, security_patch, keybox,
  or Play Integrity verdict.** Fingerprint/pif/TrickyStore tuning cannot fix it (they address a DIFFERENT
  check). This matches the reversing-community consensus that the libpairipcore VM bypass is "not currently
  possible." The only thing that hides mounts from Dasher's process is DenyList/Shamiko — which also unmounts
  Specter (the documented dead-end).
- **FINAL STATUS (A13 / Pixel 4a):** Dasher cannot run with root+modules mounted, independent of Specter and
  independent of integrity spoofing. Options that remain are all heavy/out-of-scope: (a) wait for a public
  libpairipcore VM bypass, (b) a non-rooted / differently-rooted A13, or (c) accept it. **Fleet stays on the
  Pixel 4 (A11), where Dasher runs.** The 4a is best used for probe/DevInfo/FPJS/dataset/A11-vs-A13 tests.
