# Specter — decisions log

One line per non-obvious call and WHY, so it isn't re-litigated. Newest first.

- **2026-07-26 · The FPJS demo is now measured via the Server API in the USER's own workspace, and the
  visitorId anchor is PROVEN to be the User-Agent, not the hardware bundle** — earlier docs waffled on
  whether the demo's stuck visitorId was stale server memory vs a real leak, and framed a fresh key as a
  "blocker needing signup". WRONG on both counts: (a) no key is a product dependency (Specter doesn't call
  FPJS's API); (b) the ambiguity was resolvable and is now resolved. Setup: user pasted their Public key
  into the demo's Settings ("Use your API keys" ON) so events land in THEIR workspace; their Secret key
  (AP/Mumbai) reads events back via `GET https://ap.api.fpjs.io/events/{id}` with header `Auth-API-Key`.
  In that CLEAN workspace, two different profiles STILL collapsed to one visitorId (confidence 1.0), and
  the raw response showed the server saw the REAL Pixel 4 UA/device/osVersion both times. So the anchor is
  the User-Agent (framework-built from Build.*, read by the SDK from a system/WebView path our in-app
  Build.* hooks don't cover), plus `rootApps=True`. The hardware layer (GPU/cpuinfo/sensors) is real and
  kept but was NOT the anchor. Fix = hook the UA + close root detection, then re-run the two-rotation test.
  OPERATIONAL: use `push --no-clear` (NOT `rotate`) against the demo — `pm clear` wipes the demo's API-key
  settings; `am force-stop` preserves them.
- **2026-07-26 · App versionName derives from the VERSION file; kept the honest Location placeholder (UX 3.1/3.2)** —
  `app/build.gradle` hardcoded `versionName "0.3.0"` while the repo VERSION was 0.5.0, so the in-app
  header under-reported the version and would drift every release. Wired it to read `../VERSION` (single
  source of truth) — verified on-device (header now v0.5.0). Chose to KEEP the Location tab rather than
  hide it: it's honestly labeled "UI only — no location hook yet (planned)", and location is a real
  roadmap item, so hiding it would hide the roadmap. Not fake UI (it claims nothing it doesn't do), so
  the "no fake controls" rule is satisfied; revisit if a paying user finds the empty tab jarring.
- **2026-07-26 · Native sensor spoof RELABELS the accessors, does NOT fabricate the sensor list** —
  libfp reads sensors via libandroid's ASensor NDK (direct JNI). The obvious hook is
  `ASensorManager_getSensorList`, but returning a fabricated `ASensor**` array means allocating and
  forging ASensor structs whose internal layout is undocumented and version-specific — a crash risk in
  a Zygisk companion that runs in EVERY app process. Instead we leave the real list (real count, real
  valid pointers) and hook only `ASensor_getName`/`ASensor_getVendor` to return the profile's per-model
  labels, assigning each real ASensor* a stable label slot on first sight. Same safety profile as the
  glGetString string-swap; the name/vendor is the signal that mattered. Camera list deferred for the
  same reason (it's an allocated struct) — measure whether a native reader bypasses the Java hook first.
- **2026-07-26 · Phone area codes come from a real-assigned table; N11 exchanges avoided (Phase 2.2)** —
  a random `[2-9]XX` area code is often UNASSIGNED (a fingerprint tell), and `X11` exchanges are service
  codes never used for subscriber lines. `phone_us` now picks the area code from a curated list of real
  assigned US codes and nudges an `11` exchange tail to `12` deterministically (no extra RNG draw). The
  draw COUNT changed (area is now 1 pick, not 3 digit-draws), which shifts the seeded order — so it is
  mirrored byte-for-byte in Java and proven with `scripts/prove_phone_parity.py` (500 seeds) + a 300-seed
  full-profile check. Area-code ↔ carrier region was deliberately NOT enforced: US numbers port across
  carriers and regions freely, so a mismatch there is normal, not a tell.
- **2026-07-26 · soc_platform derives from the hardware bundle and is PURE (no RNG) (Phase 2.2)** —
  it was returning a RANDOM SoC from a pool for 55/68 pool devices, which produced INCOHERENT combos
  (a Galaxy S21 could report a budget chip) — an internally inconsistent profile is itself a tell. Now
  it takes the SoC of the per-model hardware bundle (data/hardware.json), so ro.board.platform always
  agrees with the GPU/cpuinfo the same profile carries. Removing the random draw makes it pure, which
  ALSO keeps Java↔Python byte-parity trivially (a constant shifts no draw order). The old `_SOC_POOL`
  random fallback was deleted on both sides; the JVM test path (no dataset) falls back to the known-Pixel
  table then a fixed default, still draw-free. Verified on-device end-to-end.

- **2026-07-25 · Native layer = per-app Zygisk INLINE hook, not PLT and not root resetprop/touch** —
  PLT hooking (tried first, via the Zygisk API's own lsplt) does NOT intercept bionic's INTERNAL
  `__system_property_get`->`__system_property_read_callback` call (it never traverses libc's PLT), so it
  reported a valid backup yet spoofed nothing on-device. An INLINE hook rewrites the function in libc
  itself and catches every caller — the mechanism PlayIntegrityFork uses. Rejected root `resetprop`+`touch`
  (byedentity's way) because it is device-wide + irreversible and would change what the fleet apps see;
  the Zygisk companion is per-app and reads the one profile file, so a fleet app is never touched.
- **2026-07-25 · Vendored And64InlineHook (compiled in), NOT shadowhook/Dobby as a shared lib** —
  ZygiskNext's builtin linker refuses a module `.so` with an unresolved external `DT_NEEDED`
  (`open module ... with builtin linker failed: not preloaded`), so a shared shadowhook/libshadowhook.so
  can't load. And64InlineHook is a single MIT `.cpp`/`.hpp` compiled straight into our one self-contained
  `.so` — no external dep, links against system libs only. (shadowhook was tried and hit exactly this.)
- **2026-07-25 · Companion reads the profile as ROOT and streams it back; dedupe hooks by address** —
  the profile dir is `shell_data_file:s0`, which an `untrusted_app` cannot read (SELinux), so the root
  companion reads it and passes the JSON over the Zygisk socket. And64 hooks by address, and
  `fstatat`/`fstatat64` are the SAME libc address on arm64 LP64 — hooking it twice makes the 2nd trampoline
  jump into the 1st hook (infinite recursion → stack-overflow crash, observed). So hooks are deduped by
  resolved address.
- **2026-07-25 · Renamed module com.fleet.idrotate -> com.specter (namespace com.specter.module)** —
  the old applicationId leaked the internal codename in LSPosed's UI + notifications. applicationId is now
  `com.specter`; Java package `com.specter.module` (so generated R resolves); LSPosed entry
  `com.specter.module.HookEntry`. Removed the manifest `package=` attr (AGP takes namespace from gradle).
  ON-DEVICE this is a migration, not a rebrand: LSPosed registered it as a NEW module (mid 154), so scope
  had to be re-established (DevInfo + probe + fpjs). Old mid 25 stays until the new one is verified, then
  the old app is uninstalled. GeerGit (mid 101) never touched. scope_probe.py SPECTER_PKG updated to match.

- **2026-07-25 · Widevine coherence: return L3 (not a faked L1) alongside the spoofed deviceUniqueId** —
  probing PROVED the incoherence (spoofed id @ real L1 on the Pixel 4). Chose L3 because L3 = *software*
  Widevine, where a changing/derived device id is normal and expected; faking L1 while emitting a changing id
  would keep the contradiction (real L1 = fixed hardware id). Implemented as a Java getter hook on
  `getPropertyString("securityLevel")` — NO root, unlike byedentity's liboemcrypto bind-mount. Re-verified
  coherent on-device. The `media_drm_security_level` profile value is a CONSTANT ("L3") so it consumes no RNG
  → Java↔Python byte-parity is preserved automatically (no generator, no reorder).
- **2026-07-25 · byedentity adoption: probed the Widevine coherence hole FIRST, then fixed it (not blind)** —
  measured the mismatch on-device before committing the fix; HYPOTHESIS → PROVEN → fixed → proven-fixed, all
  on real hardware. The heavier root bind-mount (candidate #4) is unnecessary for this signal.
- **2026-07-25 · Do NOT adopt byedentity's server/anti-tamper stack** — its HMAC attestation, remote
  kill-switch (403→wipe local auth), public-IP telemetry, and native Frida gate serve byedentity's OWN
  licensing/control, not the user's anti-detection goal. Specter is deliberately stateless with no server
  leash. Adopt only the identity-coherence ideas (mask-preserving generators, DRM coherence, StatFs).
- **2026-07-25 · Port the mask-preserving-generator IDEA, not byedentity's native code** — its serial
  generators (buildMask/generateFromMask/generateLikePreservingBlocks) live in un-disassembled native
  (JNI names only = HYPOTHESIS on internals). Reimplement the concept (per-model format masks + prefixes)
  in generators.py with Java byte-parity + US-device templates; don't guess at their arithmetic.

- **2026-07-25 · Intermittent-detection finding is a HYPOTHESIS, not proven** — GeerGit HAS an
  IMEI-increment mode + manual-uniqueness burden (plausible cause of intermittent bans), but we have NOT
  confirmed it flags the fleet. Documented as hypothesis with a confirm-path; don't present as fact.
- **2026-07-25 · Deprioritize app-list spoofing** — it's a STABLE signal; can't cause the *intermittent*
  flagging the user reports. Completeness item, not the fleet fix.
- **2026-07-18 · Left CPU cores + SUPPORTED_ABIS real** — cores are physically fixed (faking breaks thread
  pools); ABI is near-constant arm64 and already coherent. Spoofing = risk with ~no entropy gain.
- **2026-07-18 · Did NOT hook /proc/cpuinfo** — hooking file-I/O constructors is the riskiest surface, the
  Xposed stub lacks hookAllConstructors, and ro.board.platform already spoofs the SoC name most tools read.
- **2026-07-18 · Profile-file hook-artifact left unfixed** — a targeted anti-Specter check could read
  `/data/local/tmp/specter/<pkg>.json`, but no real fingerprinting stack does; fix reintroduces file-I/O
  hook risk. Documented, deferred.
- **2026-07-18 · SoC/HARDWARE/BOARD/bootloader key on Build.PRODUCT (codename), not the device slot** —
  devices.json stores the marketing name ("Pixel 4") in the device slot for Google/LG; the real codename
  (flame) is in product. Keying on device silently produced incoherent SoCs. (Code-reviewer catch.)
- **2026-07-18 · USA-only** — removed UK/all other countries; US carriers (MCC 310-316), NANP phones,
  US-market brands (samsung/google/motorola/lge) only. Per user: everything US-focused.
- **2026-07-18 · Removed the 6 placeholder Settings toggles** — they were non-functional; never ship fake
  UI. Anti-fingerprinting is always-on (not a toggle); location deferred to a real later PR.
- **2026-07-18 · Narrow hooks / DevInfo-only scope justified by fragility + system-scope side effects**,
  NOT a "pairip kills broad hooks" law (that claim was disproved by device evidence — a broken base-only
  Dasher install, not a hook, caused the crash).
- **2026-07-25 · Spoof `ro.*` property aliases in the SAME hook, from the same profile values** — the
  dual-read probe proved `SystemProperties.get("ro.product.model")` returned the real `"Pixel 4"` while
  `Build.MODEL` was spoofed. Rather than a second hook, `hookSystemProperties` now builds a prop→value map
  (`PROP_ALIASES`) once per process and dispatches on lookup. Same one hot-path hook, no extra overhead,
  values identical to the fields (so coherent by construction) and no RNG draws (so byte-parity safe).
- **2026-07-25 · The native-read test MUST be in-process JNI, not `getprop`** — `getprop` execs a separate
  process that Specter never hooks, so it always shows real values regardless of whether our hooks work.
  It would "prove" a blind spot that might not exist. The probe calls libc `__system_property_get` inside
  the hooked process, which is what an NDK fingerprinting SDK actually does.
- **2026-07-25 · Native prop leak: documented, NOT yet fixed** — closing it needs a root layer that changes
  props at the source (`resetprop`), which mutates the whole device, not just the scoped app. That is a
  materially larger blast radius than a per-app Xposed hook and needs its own PR + coherence review.
  Logged as the top adoption candidate in `docs/IDEAS.md` instead of rushed in here.
- **2026-07-25 · The FPJS `factoryReset` leak is documented, NOT fixed in this PR** — proven that FPJS Pro
  re-identifies the device across three full identity rotations via a factory-reset timestamp read from
  directory mtimes (`/data/misc/profiles`, `/data/bootchart`, `/data/vendor`, `/data/dalvik-cache` — the
  first two readable without root). Two possible fixes, both needing their own review: (a) hook
  `java.io.File.lastModified()` for those paths only — our usual per-app mechanism, but `lastModified` is a
  very hot, very generic call and a too-broad match would break target apps; (b) root `touch` the dirs —
  device-wide, so it would also alter what GeerGit's fleet apps see, and it destroys the real value with no
  undo. Neither is a safe drive-by change, so the finding ships as evidence and the fix gets a dedicated PR.
- **2026-07-25 · `visitorFound:true` at `confidenceScore:1.0` is the metric that matters, not the visitorId
  string alone** — a rotating visitorId would be the pass signal; an unchanged one with `firstSeenAt` weeks
  in the past is proof of re-linking. Record the whole `identification` block (eventId to prove the call was
  fresh, firstSeenAt to prove the age of the link) when re-running this test, not just the id.
- **2026-07-25 · Hook BOTH `File.lastModified` and `Os.stat` for any filesystem-metadata signal** — the
  File-only hook was verified active on-device and the spoofed value verified returned, and FPJS Pro
  STILL read the real reset time, because it goes through `android.system.Os.stat().st_mtime`. One fact,
  two independent Java read paths; spoofing the obvious one is a cosmetic fix. The parity test now
  asserts both, so this can't regress. Generalises the same lesson as `Build.MODEL` vs `ro.product.model`.
- **2026-07-25 · `factory_reset_epoch` is derived from `build_security_patch`, not from a bare epoch** —
  coherence has to hold by construction, not by luck: an offset from the build's own patch date can
  never produce a device "reset before its OS existed". Cost is one extra generator argument; the
  alternative (random epoch + a validation retry loop) would burn RNG draws and complicate parity.
- **2026-07-25 · The new draw is appended LAST in the profile dict** — the seeded RNG is consumed in
  dict order, so inserting a draw anywhere else would change every subsequent field's value for the
  same seed and invalidate the no-reuse ledger. Appending is the only position that is parity-neutral
  for existing fields. Proven with a 200-seed Java-vs-Python dump diff, not assumed.
- **2026-07-25 · `factory_reset_epoch` reads NO wall clock — determinism beats a "never future" clamp**
  (code-review catch). The first cut clamped the value against `now()` sampled independently in Python
  and Java; a review proved that if the clamp ever fired, the two runtimes (different process, different
  instant) would diverge and break byte-parity — latent today (all pool patches are old enough) but a
  silent trap the moment the pool gains a recent phone. Fix: drop the clamp, make the value a pure
  function of (r, patch). "Never in the future" is now enforced by a TEST
  (test_factory_reset_is_after_the_build_and_in_the_past) that goes red — loudly — if a too-new patch
  enters the pool, instead of being silently patched over at runtime. A loud test failure is the right
  place for this invariant, not a clock read inside a parity-critical generator.
- **2026-07-25 · Android floor set to 9, not 10 (GOAL 2.1)** — an A10+ filter left only 51 US phones,
  too thin for uniqueness across many identities; A9 (2018) is still plausibly in-use and yields 68.
  The device DB tops out ~A12, so the floor can't go higher without starving the pool — revisit when
  devices.json gains newer phones. The plausibility predicate is a NAMED, mirrored function on both
  sides precisely because it changes the seeded pool: any drift between Python and Java silently breaks
  byte-parity, so it must be one logic expressed identically, proven by the 300-seed dumper.
- **2026-07-26 · Hardware descriptors are keyed by device codename and are CONSTANTS (GOAL 1.3)** —
  the per-model hardware bundle (`data/hardware.json`) is a pure lookup on the already-picked device
  codename (the stripped Build.PRODUCT), so it consumes NO seeded RNG. This is the deliberate parity
  choice: a constant never shifts the draw order, so byte-parity holds by construction (same as
  `media_drm_security_level` and the Build.* device fields), and no new dumper diff was needed for the
  generators. The fields are appended LAST in both `profile.py` and `Profile.KEYS`, mirroring the
  factory_reset_epoch convention. WHY not per-unit-exact values: the goal is a bundle that is coherent
  for the claimed model and DIFFERS between two different models — model-plausible, not serial-exact.
- **2026-07-26 · Hardware values are keyed by SoC, mapped from codename (GOAL 1.3)** — the signals a
  fingerprinter reads (GPU renderer, cpuinfo CPU-part IDs, core layout, GLES version) are
  SoC-determined, not model-determined: two phones on the same Snapdragon 855 report the same Adreno
  640. So the source of truth is a small table of real SoC specs, and each pool codename maps to its
  real SoC (longest-prefix match, since Samsung variants carry suffixes like `beyond1ltexx`). This
  keeps the dataset small and every value grounded in a real chip. Sensor/camera *counts* layer on by
  brand + model tier. Left the existing `soc_platform()` random-fallback UNCHANGED (fixing it removes
  an RNG draw → parity break); the hardware layer is independent of it and does not depend on it.
- **2026-07-26 · Java loads hardware.json from assets and renders flat; a built-in DEFAULT_HW covers
  the pure-JVM test path** — the on-device app path passes the loaded dataset into `Profile.build`
  the same way `devices` is passed; the pure-JVM test (which cannot load APK assets) uses a built-in
  coherent `DEFAULT_HW` bundle so every profile stays complete and valid. Parity for these fields is
  guaranteed by three things together: the KEYS-order test, the new asset-sync test (data/ == assets/
  byte-for-byte), and identical render logic on both sides — identical data + identical render.
- **2026-07-26 · The UA is rebuilt from existing profile fields, not stored as a new profile key** —
  `build_release` + `build_model` + `build_id` already describe the device the identity claims to be,
  and the UA is a pure function of them. Deriving it adds no profile key, consumes no RNG draw, and so
  cannot break Java<->Python byte-parity. It is also coherent by construction: the UA can never
  disagree with `Build.MODEL`, which a separately-generated field eventually would.
- **2026-07-26 · The WebView UA keeps the device's REAL Chrome version; only the device segment is
  swapped** — the Chrome/WebView version describes the installed WebView package, not the hardware,
  and page-side JS can observe it directly. Faking it would contradict what the WebView actually is,
  turning a spoof into a new inconsistency. A hardcoded fallback covers apps that cannot query the
  WebView provider.
- **2026-07-26 · `System.getProperty` is hooked ONCE with a key->value map** — `os.version` and
  `http.agent` both read through it and it is a hot path, so a second per-key hook would add overhead
  on every property read. Same pattern already used for `SystemProperties.get`.
- **2026-07-26 · MODEL/DEVICE column binding fixed at the generator, and the TEST FIXTURES were the
  root cause of it surviving** — `ProfileTest`'s inline device rows had MODEL and DEVICE transposed
  relative to the real `data/devices.json`, so the suite validated the generator against data shaped
  the way the bug expected. A fixture that disagrees with production data tests nothing. Fixtures now
  mirror the real dataset, and both suites assert the fingerprint's DEVICE slot is a codename
  (no spaces/parens) — the invariant that would have caught it originally.
- **2026-07-26 · /sys CPU/GPU signals spoofed via native redirect, keyed on SoC (data/soc_topology.json
  + embedded Java SOC_TOPOLOGY)** — FPJS reads /sys/.../cpu_capacity, kgsl gpu_model, cpu/present
  directly (tracer-proven); these leaked the real Pixel 4 every rotation. Chose a per-SoC lookup table
  (not per-model) because these are SoC-determined facts; keyed on the already-computed soc_platform so
  no new RNG draw and byte-parity holds. Java embeds the table (not an asset) to avoid an extra asset
  load; a parity test asserts the JSON and the embedded map agree. gpu_model empty for Exynos is correct
  (no KGSL node). The probe reads these back (native redirect applies to its libc reads) as the gate.
- **2026-07-26 · SDK level spoofed via Java Build.VERSION.SDK_INT ONLY, never the native prop layer** —
  adding ro.build.version.sdk / ro.product.first_api_level to the native PROP_ALIASES SIGSEGVs the
  zygote (ART/libc read these during process init, before the __system_property_get hook is safe;
  proven on-device: probe + demo both crash, props=33). The Java field hook runs after init and is safe.
  Accepted limitation: an app reading ro.build.version.sdk NATIVELY still sees the real value — not
  worth chasing into the crash. build_sdk is a pure release->API lookup (byte-parity mirrored in Java).
- **2026-07-26 · Protection toggles verified REAL end-to-end (no-fake-UI invariant)** — on-device matrix:
  spoof_ua=0 -> UA hook skipped (no [specter] UA log); hide_apps=0 -> installed_sensitive_leak shows
  com.specter.probe (leaks); spoof_sysfs=0 -> sys_cpu_capacity0 reads the REAL 261. Each toggle's OFF
  state leaves the corresponding signal REAL, proving the switch changes what the device reports (the gate
  key flows profile -> Java/native hook -> skipped). Default is ON for every protection.
- **2026-07-26 · The native `__system_property_get` blind spot is CLOSED (byedentity parity reached)** —
  the probe's dual read proves every aliased ro.* prop reads the SPOOFED value on BOTH the Java and native
  paths (model/hardware/serial/board/fingerprint/bootloader/baseband/soc, _java == _native). Byedentity's
  one claimed edge over Specter was "native-read reach" via a device-wide root resetprop; Specter reaches
  the same depth per-app via the Zygisk my_prop_get inline hook — no device-wide mutation, no root
  resetprop needed. Only ro.build.version.sdk / ro.product.first_api_level are Java-only by necessity
  (native intercept SIGSEGVs the zygote); a native read of those two still returns real. The old CLAUDE.md
  "resetprop layer not built yet" note was stale and is now corrected.
- **2026-07-26 · Full profile coherence re-audited across 500 profiles + real-app apply (0 issues)** — every
  new field (screen/sensor-rmp/soc-topology/sdk) is internally consistent: SDK matches release, cpu_present
  matches capacity length, screen is portrait, device codename in the fingerprint with no space in the
  device slot. Verified on DevInfo (a real device-info reader): a Galaxy S10 profile is coherent end-to-end
  (device=beyond1, soc=exynos9820, screen=1440x3040@550, cpu=260..1024 tri-cluster, fp well-formed). Added
  test_build_sdk_matches_the_android_release as the SDK<->release coherence guard.
- **2026-07-26 · FNV-1a codenameHash byte-parity Java<->Python STRESS-VERIFIED** — the screen-spec lookup
  hashes the device codename to pick a pool entry; Java (`h=(h^c)*16777619L; h&=0xFFFFFFFFL`) and Python
  (`h=((h^ord)*16777619)&0xFFFFFFFF`) must agree or the on-device profile picks a different screen than the
  PC one. Confirmed IDENTICAL across 13 cases incl. empty string, unicode (日本), 50-char strings, and edge
  chars — the per-step 32-bit mask keeps intermediate products bounded identically in both languages.
- **2026-07-26 · Magisk hidden from /proc/mounts + mountinfo via per-app filtered-copy redirect (NOT maps)**
  — real mount reads leak Magisk bind-mounts blatantly (tmpfs magisk overlays), a strong root signal a
  mount-reading detector catches past su-path hiding (the byedentity bind-mount vector). Chose a filtered
  per-process copy in the app files dir + redirect_path swap (same proven pattern as cpuinfo), gated by
  hide_root. Deliberately NOT applied to /proc/self/maps — ART reads its own maps during GC and a filtered
  copy crashes the app (tried+reverted earlier); mountinfo has no such reader so it's safe. Per-app scope
  (a non-hooked shell still sees real mounts) — no device-wide mutation.
- **2026-07-26 · su binary: access/stat/open hiding YES, readdir enumeration NOT hooked (deliberate)** —
  the su binary sits at /system_ext/bin/su (Magisk-placed). is_root_path catches any "/su"-suffixed path,
  so access()/stat()/open()/File.exists() on it return ENOENT (the COMMON root-check vector, covered). A
  more advanced detector could opendir("/system_ext/bin")+readdir and see the "su" entry — readdir/getdents
  are NOT hooked. Deliberately NOT implementing a getdents entry-filter: it re-packs a raw dirent byte
  buffer, and a bug corrupts EVERY directory read the app makes (breaks the app's own file access) — a
  large blast radius for a vector no observed detector uses (the FPJS demo doesn't readdir; traced). If a
  real target is later shown to enumerate dirs for su, revisit with a narrow, well-tested getdents filter.
  Recorded so it isn't mistaken for an oversight.
