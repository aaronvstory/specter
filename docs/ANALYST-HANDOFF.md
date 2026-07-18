# Specter — Analyst Handoff (v0.2.0, 2026-07-08)

Audience: an outside analyst / engineer who will review this project and may extend it. This is a
self-contained brief — you should be able to understand what Specter is, why it's built this way,
what's proven vs. unproven, and how to build and drive it, without prior context.

---

## 1. What Specter is

Specter is an **Android device-identity rotation tool**. It makes a single physical phone present a
different, coherent hardware identity to a chosen target app on each "signup," so that multiple
accounts created from one device don't look like the same device.

It exists to replace a closed-source app called **GeerGit** (a Flutter/Dart LSPosed app, distributed
as release APKs only) that the operator currently uses for a fleet of DoorDash driver accounts.
GeerGit works but is opaque and had a regression (v2.9.6) that reused a GSF id across accounts and
got accounts flagged as "coordinated." Specter reimplements the same identifier-rotation surface,
open and testable, with the reuse bug designed out.

**Two deliverables, both in this repo:**
1. A **standalone Android app** (`com.fleet.idrotate`) that is *both* an LSPosed/Xposed hook module
   *and* a launchable app — it generates identities on-device and self-applies them with **no PC**.
2. A **Python CLI/TUI** (`specter/`) that is the trusted reference implementation of the generation
   logic + a developer/QA tool (adb-push a profile, run an on-device verification questionnaire).

**Threat model / bar:** the target detector (DoorDash) checks device *identifiers*
(android_id, GSF id, IMEI, serial, advertising id, MACs, SIM, Build.*). GeerGit succeeds against it by
rotating those. Specter matches that surface. It does **not** attempt to defeat advanced behavioral/
hardware fingerprinting (see §8, Fingerprint Pro) — that's explicitly out of scope.

---

## 2. How it works (architecture)

### The injection mechanism (LSPosed hook)
`HookEntry.java` implements `IXposedHookLoadPackage`. When a *scoped* target app starts, LSPosed loads
this code **into the target app's process**. The hook reads a JSON profile from
`/data/local/tmp/specter/<pkg>.json` and replaces the values returned by the Android APIs that expose
each identifier:

| Identifier | Hooked API |
|---|---|
| Build.* + serial | `Build`/`Build.VERSION` static fields, `Build.getSerial()` |
| android_id | `Settings.Secure.getString`/`getStringForUser` (all overloads, arg-scanned) |
| GSF id | `Gservices.getString/getLong` + a `CursorWrapper` over `content://com.google.android.gsf.gservices` (covers getString/getLong/getBlob/copyStringToBuffer) |
| IMEI (dual-SIM) | `TelephonyManager.getImei/getDeviceId` (slot-aware) |
| IMSI/ICCID/line1/operator | `TelephonyManager.get{SubscriberId,SimSerialNumber,Line1Number,NetworkOperator,…}` |
| Wi-Fi mac/ssid/bssid | `WifiInfo.get{MacAddress,SSID,BSSID}` |
| Bluetooth mac | `BluetoothAdapter.getAddress` |
| Advertising id | `AdvertisingIdClient.getAdvertisingIdInfo(ctx)` static factory (swaps the whole `Info`) + `Info.getId` |
| MediaDRM widevine | `MediaDrm.getPropertyByteArray("deviceUniqueId")` |

### The write path — the one decision that shapes everything
The hook runs inside the **target app's** sandbox, so under SELinux it can only read files that the
target app's uid can read. `/data/local/tmp/` is world-readable (0644, `shell_data_file`) and app
domains may read it — which is why the profile lives there. An app's own `filesDir`/`getExternalFilesDir`
is labeled to the *writer* app's uid and is **not** cross-app readable, so the hook can't read a
UI-app-private file.

Therefore: the **UI app writes the profile to `/data/local/tmp/specter/` via Magisk `su`** (the device
is rooted). `RootWriter.java` runs `su -c 'mkdir -p … && cat > <pkg>.json && chmod 644 …'` with the
JSON piped over stdin (never on the command line) and only a validated package name interpolated. The
hook's read path is unchanged — this keeps the proven injection intact and requires no cross-process
file-permission gymnastics.

### On-device generation
`IdentityService.java` (the only Android-dependent glue): loads a bundled 499-device DB
(`assets/devices.json`), calls the pure generation core to build a coherent + globally-unique profile,
serializes it, and writes it via `RootWriter`. The **no-reuse ledger** lives in the app's *private*
`filesDir/used_ids.json` (not world-readable — the target app must never see the history) and **fails
closed** on corruption (quarantine + refuse, never silently treat as empty).

### The pure generation core (JVM-testable, no Android)
`gen/Generators.java`, `gen/Profile.java`, `gen/UsedStore.java` are a 1:1 port of the trusted Python
(`specter/generators.py`, `profile.py`). Kept Android-free so they run under a plain-javac test harness
(`run-jvm-tests.sh`) and can be diffed byte-for-byte against the Python at a fixed RNG seed.

---

## 3. What is PROVEN on-device (and what isn't)

Device: **Pixel 4 (`flame`), Android 11, Magisk root, LSPosed**. Test target: **DevInfo**
(`com.liuzh.deviceinfo`) — a diagnostics app that displays raw identifier values, ideal for read-back.

**Proven (observed on DevInfo's screen, not inferred):**
- The full identifier set spoofs: android_id, GSF, advertising_id, serial, Build.* all show the
  injected values on DevInfo's Device tab. (GSF displays as hex of the decimal we inject — verified
  the hex matches.)
- **Rotation**: pushing/generating a new profile changes every displayed value.
- **No-PC self-apply**: launching the Specter app → RANDOMIZE ALL → APPLY → relaunching DevInfo shows
  the app-generated identity. Verified a specific value (`5ae71b96125c98cb`) traveled UI → disk → screen.
- The app is launchable **and** still logs `[specter] active for com.liuzh.deviceinfo (27 fields)` as a
  module after adding the launcher Activity.
- The `su`-grant gate fails **loudly** (exit 13 → "grant this app in Magisk"), not silently.

**NOT proven / caveats:**
- Wi-Fi/Bluetooth MAC spoofing wasn't visually confirmed (DevInfo gates those behind a permission);
  the hooks exist and are unit-tested but not screen-verified.
- Nothing has been tested against DoorDash itself (fleet safety — see §7). Parity with GeerGit's
  *surface* is the argument, not a live Dasher test.
- Fingerprint Pro is **not** beaten (§8).

---

## 4. Repo layout

```
specter/                      # Python: trusted reference generators + CLI/TUI dev tool
  generators.py, profile.py, identifiers.py   # generation logic (the SPEC)
  cli.py, tui.py, theme.py    # CLI + questionnaire-menu dashboard
  device.py, validation.py    # adb layer + pkg-name shell-boundary guard
  verify.py                   # on-device verification questionnaire (reads back what the app stored)
xposed-module/                # the Android app + LSPosed module (one Gradle :app module)
  app/src/main/java/com/fleet/idrotate/
    HookEntry.java            # the LSPosed hooks (runs in the target app's process)
    SpoofLogic.java           # pure hook decision-logic (JVM-tested)
    gen/Generators,Profile,UsedStore,RootWriter,IdentityService.java
    ui/MainActivity, DebugActivity, IdentityFields.java
  app/src/main/assets/devices.json     # 499-device DB, bundled
  app/src/test/java/…                  # plain-JVM tests (hand-rolled asserts)
  build-apk.sh, run-jvm-tests.sh       # build + test (vendored JDK17 + gradle 8.7)
  .jdk/ .gradle-dist/                  # vendored toolchain (no network needed to build)
data/devices.json             # source device DB (copied to the app asset)
VERSION                       # 0.2.0 — single source of truth for the version
CHANGELOG.md, README.md
docs/                         # this file + STAGE1-INJECTION-COMPLETENESS.md + regression notes
```

---

## 5. Build & run

**Android module/app** (Windows, Git Bash):
```
cd xposed-module && bash build-apk.sh          # -> dist/specter-module-v0.2.0.apk (name from ../VERSION)
adb install -r ../dist/specter-module-v0.2.0.apk
# enable "Fleet ID Rotate" in LSPosed, scope it to com.liuzh.deviceinfo, reboot or restart the target
```
Toolchain is vendored (`xposed-module/.jdk/jdk-17*`, `.gradle-dist/gradle-8.7`), so no SDK download is
needed beyond the Android platform (compileSdk/targetSdk 36, minSdk 24).

**JVM logic tests** (fast, no device): `bash xposed-module/run-jvm-tests.sh`
→ SpoofLogic 19, Generators 28008, Profile+UsedStore 6017, RootWriter 17 (all pass).

**Python CLI/TUI** (`uv` preferred): `uv run --with rich --with questionary python -m specter.cli tui`
(or `launch.bat`/`launch.command`). `specter --version` → `specter 0.2.0`.
Python tests: `uv run --with pytest --with rich --with questionary python -m pytest -q` (75 pass).

**Driving the app headlessly** (how it was verified): `adb shell uiautomator dump` + `adb shell input tap`.
Note DevInfo ad-gates fresh launches (privacy-consent dialog after a data-clear, then Play/Temu
interstitials) — drive past with `KEYCODE_BACK` + `am force-stop com.android.vending`.

---

## 6. Security / ban-critical properties (what to scrutinize first)

**Hook-surface / scope design (read `docs/PAIRIP-CONSTRAINT.md`):** Prefer NARROW hooks (identifier
leaf-getters) and per-app scope; avoid `system` scope. Reasons are **fragility** (a smaller hook surface
survives app updates better) and **system-scope side effects** (system scope can leak a spoofed profile
into `PackageManager` → Play "not compatible"). NOT because of pairip — an earlier claim that pairip
crashes the app on broad hooks was **disproved**: a Dasher crash traced to a broken base-only install
(`libpairipcore.so` missing), and GeerGit runs broad + system-scope hooks on Dasher fine. Fleet safety
still holds: never scope Specter to Dasher/DoorDash/system while GeerGit owns the fleet (income), and only
test on Dasher with explicit user go.

These are the properties the whole tool depends on. If you audit anything, audit these:

1. **No reuse, ever.** Every globally-unique id (`Profile.UNIQUE_KEYS`: android_id, IMEIs, serial,
   advertising_id, gsf_id, media_drm, MACs, phone, IMSI, ICCID, gmail) must never be issued twice.
   Enforced by `UsedStore`: `generateUnique()` and `randomizeField()` both check-and-record under a
   static lock, and the ledger persists fail-closed. A per-field RANDOMIZE that skipped the ledger was
   the most dangerous bug found in review — fixed, with a regression test.
2. **Coherence.** IMEI passes Luhn with a brand-plausible TAC (shared across dual-SIM IMEIs); gsf_id is
   decimal ≤ `Long.MAX` so Java `parseLong`/`Cursor.getLong` never throws; IMSI/ICCID prefixes match the
   assigned US carrier; Build.* all come from one real device row and appear in the fingerprint. A
   fingerprint that fails these is itself a fraud signal.
3. **Shell-injection guard.** The only thing interpolated into the `su` command line is a package name
   validated against a strict Android-package regex; the JSON payload goes via stdin.
4. **Fail-loud / fail-closed.** su denial surfaces to the user; a corrupt ledger refuses rather than
   reusing ids; an empty/partial profile is never written (that would leak the *real* device ids).

Verification is `git diff main...HEAD`, `run-jvm-tests.sh`, and the Python suite. The JVM tests are
hand-rolled asserts (no framework) precisely so they run anywhere with just a JDK.

---

## 7. Fleet safety (operational — do not violate)

The operator runs **real, income-earning** DoorDash accounts on GeerGit right now. Two LSPosed modules
hooking the same app fight over the hooks and can corrupt the identity → banned account. Therefore:

- Specter is scoped to **DevInfo only** for all testing. The Python CLI/TUI/verify default target is
  DevInfo, never `com.doordash.driverapp` / `com.dd.doordash` / `system`.
- **Never** scope Specter to a real fleet app or system until the operator explicitly moves off GeerGit.

---

## 8. Open items / where an analyst could contribute

- **Fingerprint Pro** (`com.fingerprintjs.android.fpjs_pro_demo`, SDK v2.17.0) re-identifies the device
  at **100% confidence** after a full 27-field rotation — its Visitor ID is unchanged. It fingerprints
  via signals Specter doesn't touch (hardware/sensor entropy, GPU/GL renderer, build props, media
  codecs, IP, timing). Beating it is a hard, separate problem and is explicitly a *later stretch goal*.
  A high-value analysis: enumerate exactly which FPJS signals dominate its `visitorId` on Android and
  which are hookable. Automate its trigger headlessly with `adb shell input tap 540 1086`.
- **Location spoofing**: the app's Location tab is UI-only — there is no `LocationManager` hook yet.
- **Settings toggles** (Anti-Fingerprinting, Hide Mock Location, etc.) are GeerGit-parity UI that
  persists to prefs but has no hook behind most of them yet — marked with `// ponytail:` in code.
- **UI is Views, not Compose** (deliberate: zero new deps, guaranteed build against the vendored
  gradle). A Compose rewrite is possible but not necessary.
- **MAC spoofing** is unit-tested but not screen-verified (DevInfo permission-gates it) — worth an
  independent read-back via a different probe app.

---

## 9. Status & provenance

- Branch `feat/android-app`, 10 commits, open as **PR #2 → main** (github.com/aaronvstory/specter,
  private). The prior release (v0.1.0, PC-tethered) is already merged to main.
- Bot review: CodeRabbit passed; gemini findings all addressed (remaining are stale re-posts — verified
  in code); Kilo pending is CI-minutes exhaustion, not a defect. A `code-reviewer` subagent did two
  independent passes and caught the ban-critical ledger bug + a data race, both fixed.
- Tests: **34,050 JVM assertions + 75 Python tests** green; APK builds + installs as v0.2.0.
- The Python core is the trustworthy reference (73→75 tests, multiple review passes); the Java port is
  byte-parity-checked against it. When in doubt about intended generation behavior, read the Python.
