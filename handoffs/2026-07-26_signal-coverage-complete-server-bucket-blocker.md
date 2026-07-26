# Handoff — client signal coverage is now comprehensive; the id-split gate is blocked ONLY by the shared-workspace confound

**Read this first, then `docs/OVERNIGHT-QUEUE.md` (progress log) and `docs/ANTI-FINGERPRINT-STRATEGY.md`.**
Branch `feat/ua-spoof`, PR #20. All work committed + pushed. Tests: Python 106, JVM 61,602 — green.

## What this session closed (all PROVEN on-device, 29 spoofed / 0 hard leaks)
Every client-readable signal FingerprintJS's demo was seen to read (via on-device syscall tracing) is
now spoofed and verified on the probe:
1. **User-Agent** (http.agent + WebView) — server now reports the applied device, not the real Pixel 4.
2. **MODEL/DEVICE dataset columns** were swapped — fixed; fingerprints are now well-formed.
3. **APK install-mtime** (FPJS FileTimestamps raw signal) — own-APK mtimes spoofed.
4. **Installed-app list** — root/hook/anti-fp packages filtered (probe: leak=none). Markers narrowed to
   avoid false-positives on legit apps.
5. **/sys CPU/GPU** — cpu_capacity vector, gpu_model, cpu present redirected per-SoC (data/soc_topology.json).
6. **/proc/version** — kernel banner rebuilt from build_kernel_version.
7. **Build.VERSION.SDK_INT** — coherent with the release (Android 10 -> 29). Java-only (see gotcha).
8. **Protections UI** — real per-protection toggles + ON/OFF status, each gating its hook.

## The ONE blocker for the visitorId-split gate (this is the whole story)
The demo's on-screen visitorId (`18uu8...`, firstSeenAt 2026-07-08) DOES NOT MOVE across rotations, but
this is NOT because a signal is leaking. PROVEN by elimination:
- Deleting the SDK's ENTIRE local cache (fpjs_prefs_v2.xml + datastore) does NOT change the id → it is
  computed 100% server-side, not client-cached.
- Every client signal the demo reads is now spoofed (traced + probe-verified) yet the id holds.
- `firstSeenAt` is 2026-07-08 — BEFORE any of this work — and the id lives in FPJS's **shared
  public-demo workspace** (the built-in API key), which every fpjs-demo user worldwide shares. A stable
  id there is a COARSE BUCKET artifact, not a per-device verdict. Many spoofed-but-plausible Android
  devices land in the same bucket.

**So the id-split can only be observed in the USER's OWN isolated workspace.** `pm clear`/`rotate` wipes
the user's API keys (encrypted prefs) → falls back to the shared workspace. The keys can ONLY be
re-entered via the demo's Settings UI (androidx EncryptedSharedPreferences, device-bound master key —
unscriptable). This is the single manual step that unblocks a valid measurement.

### The exact test once keys are back (do NOT pm clear / rotate after)
`push --no-clear` identity A → identify → read visitorId; `push --no-clear` a VERY different identity B →
identify → read visitorId. In the user's own workspace, with all the above spoofs, they should now differ.
Read raw signals with the Secret key at `https://ap.api.fpjs.io/events/<id>` (AP region) if available.

## Remaining server-side Smart Signals (may flip once client evidence is reduced)
`rootApps=True`, `developerTools=True`, `suspectScore=34` are FPJS PRO Smart Signals computed
server-side. The client-side hooks (hide_root native, hide_dev Settings.Global — both verified working)
feed but don't solely decide them. Reducing corroborating evidence (done: installed-apps, /proc, /sys)
may lower them in the user's workspace — measure there.

## Build/deploy notes (hard-won)
- Native .so: `gradle :zygisk:externalNativeBuildRelease` → the FRESH artifact is under
  `build/intermediates/cmake/release/obj/arm64-v8a/` (the stripped_native_libs copy can be STALE — check
  mtime/md5). Deploy: base64-stream to /data/local/tmp (adb push no-ops), then `su -M -c cp` into the
  module, md5-verify, reboot. Current device .so = md5 60ed96e4 (matches HEAD).
- NEVER add ro.build.version.sdk / ro.product.first_api_level to the NATIVE PROP_ALIASES — it SIGSEGVs
  the zygote (ART reads them at init). Documented in CLAUDE.md. SDK is Java-only.
- test_module_parity now asserts the Java↔native PROP_ALIASES stay in lockstep, and the
  data/soc_topology.json ↔ Java SOC_TOPOLOGY table match.

## NEXT (autonomous)
More breadth to surpass GeerGit/Byedentity: verify emulator/frida/clonedApp/VM signals stay clean; audit
any remaining Build/prop the demo reads natively (ro.debuggable, ro.product.first_api_level — accept the
native-read ones per the gotcha); harden. Keep the UI polished. And whenever the user re-enters the demo
keys, run the two-rotation split test in their workspace — that's the definitive gate.
