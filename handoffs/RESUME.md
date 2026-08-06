# Specter — RESUME here (fresh session)

Updated 2026-08-06. An Android device-config + on-device QA project. Describe the MECHANISM, not the
purpose (see CLAUDE.md "Session framing"). Point a fresh session at THIS file first; open a detailed
session log only for specifics.

## STATE — v0.31.0 on `main` (synced, pushed)

Python + JVM tests green. Pixel 4a on v0.31.0 + verified. Webapp live.

### Most recent (2026-08-06)
- **Per-identity GPS location SHIPPED (v0.30.0).** Each identity carries gps_lat/gps_lon/gps_accuracy — a
  coherent US fix from the phone's area-code metro + a per-android_id jitter (byte-parity proven). Hooked on
  every read path: LocationManager getLastKnownLocation/getCurrentLocation/requestLocationUpdates (the update
  path SKIPS the real registration so the real GPS stream can't leak) AND GMS FusedLocationProviderClient
  getLastLocation/getCurrentLocation (via the concrete impl discovered at runtime — version-proof).
  isFromMockProvider()=false. Reboot-persistent. STATIC point only (no faked route — telematics tell).
  UI: Identity → Location card (coords OR address, blank = default). PROVEN on-device across 5 cities.
  Replaces Lockito. Known limitation (documented): Fused STREAMING (LocationCallback) unhooked.
- **GPS-follows-proxy-IP SHIPPED (v0.31.0).** Aligning timezone to the exit IP now aligns device GPS too
  (RootWriter.setGps; the auto on-apply path preserves a hand-set custom pin, the manual "match to IP" fix
  overrides it). Closes the GPS-vs-IP coherence gap. FUTURE (docs/IDEAS.md): a "Location vs IP" health row +
  a live-VPN E2E of the align path.

### Earlier state (v0.26.0 — the exit-IP readout, still live)
The exit-IP readout was redesigned across all THREE surfaces at once, and they must stay in lockstep.

The exit-IP readout was redesigned across all THREE surfaces at once, and they must stay in lockstep:

- `specter/ipcheck.py` — source of truth: the `check()` logic AND the `PAGE` HTML/JS string
- `webapp/` — the Vercel copy. `python webapp/build.py` regenerates `index.html` from `PAGE` and vendors
  `ipcheck.py` → `api/ipcheck_core.py`. **`webapp/index.html` is GENERATED — never hand-edit it.**
- `xposed-module/.../ui/{HealthCheck,MainActivity}.java` — the Android mirror

### What shipped

- **The verdict names its own evidence.** `verdict_factors()` (Python) / `HealthCheck.verdictFactors()`
  (Java) return the level plus the individual signals behind it, shown as chips instead of a bare "SUSPECT".
- **Per-source detail breakdown, collapsed by default** — every field each source returned. Blocklist zones
  are grouped by what the answer MEANS (Listed / Policy only / Clean / No answer), so no colour has to be
  decoded, and a zone that refused is never counted clean.
- **One layout rule everywhere:** a value never wraps mid-text, and nothing is truncated without a way to
  read the rest. See memory `ui-layout-rules-no-ragged-text` — the user was emphatic about this.
- **No auto-run on page open.** Prefills the visitor's IP and waits.
- Proxy liveness + latency (`proxy_alive`, `proxy_ms`), country flags, line-type icons, contact rotation for
  getIPIntel, `oflags=bc` for the country.

### Verified live

- https://webapp-idanis-projects.vercel.app — full tool, shared env keys show "shared active", getIPIntel
  works from Vercel (it is rate-limited from this PC's exit — expected, not a bug).
- Pixel 4a (`17031JEC204747`, sunfish) v0.26.0 — SUSPECT verdict, uniform tiles, breakdown expands to
  IPQS fields + 17 blocklist zones grouped 14 clean / 3 no answer.
- Pixel 4 (`9B151FFAZ00FPF`, flame) v0.26.0 — installed, launches clean, vault intact.

## NEXT

1. **Work the queue in `docs/GOAL.md` top-down** (the standing instruction set). Phase 0-3 are largely done;
   the big remaining lever (beat FPJS Pro) needs a FRESH server context — a personal fingerprint.com trial
   key or the real target — so device-side coherence work is the buildable path until then.
2. GPS follow-ups (`docs/IDEAS.md`): a "Location vs IP" health-check row (catches a GPS mismatch even when the
   timezone matches), close the Fused-STREAMING leak by proxying the LocationCallback, live-VPN E2E of the
   GPS-align path.
3. Older exit-IP follow-ups (`docs/IDEAS.md`): AbuseIPDB `verbose` per-report detail (newest 3-5 only),
   getIPIntel `oflags=r` (ResidentialProxy score) / `oflags=i` (VPN type).

## iOS PORT (Specter-iOS) — separate, PROVEN working · branch `feat/ios-port-research` (PR #45)

An iOS build of the same mechanism lives under `ios/` + `docs/ios/`, fully separate from Android. PROVEN on
the SE2 (2026-08-03, real iPhone12,8 → spoofed iPhone14,6): the ElleKit tweak coherently spoofs
identifierForVendor + UIDevice.systemVersion + sysctl hw.machine/hw.model/hw.memsize + kern.osversion +
uname + MobileGestalt (ProductType/HWModelStr/RegionInfo) + kern.boottime (coherent w/ systemUptime).
Non-sudo WSL+theos build. To resume: read `docs/ios/DEEP-DIVE-FINDINGS.md` → `docs/ios/EFFICACY-RESULT.md`
→ `ios/README.md`, and the "iOS port" section in CLAUDE.md. Ceilings (not spoofable by hooks): iCloud
ubiquityIdentityToken + server-side DeviceCheck → need a distinct iCloud sign-in per identity.

## Devices

- **Pixel 4 (flame)** — USB `9B151FFAZ00FPF`, wireless `adb connect 192.168.50.144:5556`
- **Pixel 4a (sunfish)** — USB `17031JEC204747`, wireless `adb connect 192.168.50.19:5557`
- **Never 5555** (AnyTo probes it), and never port-scan for an Android-11 "random" wireless port — that is
  the PAIRING port and always reads `offline`. A phone that won't connect is usually powered OFF.
  `persist.adb.tcp.port` is now set on both, so the pinned port survives a reboot.
- Both are FREE test devices (memory `p4-now-free-test-device`). Deny an app's location permission before
  launching it if unsure (`pm revoke <pkg> ACCESS_FINE/COARSE_LOCATION`).
- adb "unauthorized" after a reboot → `adb kill-server && adb start-server` re-triggers the auth dialog.
- A reboot is owed after every `adb install -r`: the install de-registers the module in the LSPosed runtime
  ("Scoped, but not loaded" on the status page). Back up `/data/data/com.specter/files` first.

## Build/test

Python: `.venv/Scripts/python.exe -m pytest -q`. JVM: `cd xposed-module && bash run-jvm-tests.sh`. Native:
`bash build-zygisk.sh`. Module: `JAVA_HOME=~/scoop/apps/temurin17-jdk/current GRADLE_BIN=.gradle-dist/gradle-8.7/bin/gradle ANDROID_HOME=$LOCALAPPDATA/Android/Sdk bash build-apk.sh`.
Native .so auto-syncs to the device by md5 on app launch; REBOOT to load it. Probe: `gradle :probe:assembleDebug`.
Gradle can report "up-to-date" and still be right — but VERIFY a new symbol reached the dex before trusting
an APK (`unzip classes*.dex` + `strings`), multidex means it may not be in classes.dex.

Webapp deploy: copy to a NON-git dir first (Vercel rejects the repo's git author email):
`cp -r webapp/. <scratchpad>/ipcheck-deploy/ && cd there && npx vercel deploy --prod --yes`.

EOL: profile.py/generators.py/cli.py/verify.py/CHANGELOG.md/HookEntry.java/ZygiskInstaller.java/
webapp/build.py/webapp/index.html = **CRLF** (edit byte-wise or re-normalize after Edit, then verify
`git ls-files --eol` AND `git diff --stat` ≈ your change, not a whole-file flip). ipcheck.py/Profile.java/
Coverage.java/main.cpp/soc_topology.json/MainActivity.java = LF.
`find . -name nul -type f -delete` before commit.

**Run the full gauntlet before merging: a `code-reviewer` subagent on the WHOLE `git diff main...HEAD`,
plus `/codex` when it has quota** (it is on a limited free plan — reserve it for substantial/risky PRs).
The PR bots are broken and are NOT part of the gauntlet.

## Resume phrase

```
Read handoffs/RESUME.md and resume. START with "NEXT": merge wip/webapp-vercel-bulk-keys-ux to main once
the review is clean, then pick up the docs/IDEAS.md follow-ups. All three ip-check surfaces must stay in
lockstep — webapp/index.html is GENERATED by webapp/build.py, never hand-edited. Keep the UI layout rules
in memory `ui-layout-rules-no-ragged-text`. Both phones are FREE test devices on pinned wireless ports.
```
