# Specter — project instructions

Specter is an LSPosed/Xposed module + standalone Android app + Python reference core that generates
coherent random **US** device identities on-device and self-applies them (Magisk `su`) so app signups
look like distinct devices. Replaces the closed-source GeerGit. Repo: `aaronvstory/specter` (private).

## Fleet safety (NON-NEGOTIABLE)
- On-device work targets **DevInfo (`com.liuzh.deviceinfo`)** and **`com.specter.probe`** ONLY.
- NEVER scope/apply/test against `com.doordash.driverapp`, `com.dd.doordash`, `com.pyshivam.geergit`,
  `system`, or `android`. GeerGit owns the fleet apps and the user makes real income there.
- LSPosed scope DB: `/data/adb/lspd/config/modules_config.db`. Specter = mid **25**, GeerGit = mid **101**.
  Only ever edit mid 25's scope. Never touch 101.
- Test on Dasher ONLY when the user explicitly green-lights it.

## Build (Windows)
- JDK: `~/scoop/apps/temurin17-jdk/current` — set `JAVA_HOME` to it.
- Gradle: vendored `xposed-module/.gradle-dist/gradle-8.7/bin/gradle` (set `GRADLE_BIN`).
- Android SDK: `$LOCALAPPDATA/Android/Sdk` (aapt2 in build-tools/36.1.0).
- Build the module: `cd xposed-module && JAVA_HOME=... GRADLE_BIN=... ANDROID_HOME=... bash build-apk.sh`
  → `dist/specter-module-v<VERSION>.apk`. build-apk.sh now clean-compiles.
- Build the probe: `gradle :probe:assembleDebug` → `probe/build/outputs/apk/debug/probe-debug.apk`.
- **CLEAN-build before trusting on-device behavior** — incremental Gradle can mask a compile error with
  stale `.class` files (once shipped a broken APK). Verify a new symbol is in the APK dex (multidex —
  check classes2/3/4.dex, not just classes.dex). Xposed stub only has `setStaticObjectField`; set
  instance fields via plain reflection (`clazz.getField(n).setLong(obj,v)`), NOT `setLongField`.

## Xposed hook gotcha
`XposedHelpers.findAndHookMethod(cls, "name", callback)` with NO explicit param types throws
`NoSuchMethodError` against LSPosed's obfuscated XposedHelpers (its varargs overload isn't resolvable).
For zero-arg / overload-agnostic methods use `XposedBridge.hookAllMethods(cls, "name", callback)`.
This silently broke getSerial/getRadioVersion/os.version hooks until the probe caught it.

## Verify on-device (autonomous, no clicking)
- `python scripts/scope_probe.py [serial]` — one-time: adds the probe to Specter's LSPosed scope
  (PC-side SQLite edit, then reboot). Never touches GeerGit's scope.
- Apply an identity in Specter (RANDOMIZE ALL — wait ~5s for off-thread gen — then APPLY).
- `python scripts/verify_on_device.py [serial]` — seeds the probe from the DevInfo profile, relaunches
  it, reads what the hooks actually returned, prints a per-field ✅/❌ table. Exit 0 = all spoofed.
- The probe (`xposed-module/probe/`) reads every spoofable API → world-readable JSON. Deterministic,
  covers everything, enables GeerGit-vs-Specter side-by-side. Use this, NOT DevInfo UI screenshots.

## Tests (TDD, both must be green before commit)
- Python: `.venv/Scripts/python.exe -m pytest -q`
- JVM: `cd xposed-module && bash run-jvm-tests.sh` (javac + hand-rolled asserts, no framework).
- Java↔Python **byte-parity** is required: generators must consume the seeded RNG in the IDENTICAL
  order so the same seed yields identical output. Verify by compiling a tiny Java main + comparing.

## EOL discipline (Windows)
CRLF-committed files must STAY CRLF: `specter/generators.py`, `specter/profile.py`, `cli.py`,
`verify.py`, `CHANGELOG.md`, `HookEntry.java`. Edit them via a Python byte-level script (normalize
`\r\n`→`\n`, replace, restore `\r\n`) — the Edit tool can flip EOL. After every edit run
`git ls-files --eol <f>` and `git diff --stat <f>` (diff ≈ your change, not a whole-file flip).
LF files (`identifiers.py`, `Generators.java`, `Profile.java`, tests, `*.gradle`) use normal edits.
No `nul` files: `find . -name nul -type f -delete` before every commit.

## Anti-fingerprinting (the point)
FingerprintJS-class SDKs compute deviceId (GSF/mediaDrm/androidId — we spoof) AND a fingerprint =
hash of ~30 hardware/OS signals. GeerGit leaves most of those real → "sometimes detected". Specter
spoofs the fingerprint-hash signals too (bootloader, radio/baseband, kernel, HARDWARE, BOARD, RAM),
all DEVICE-COHERENT. Coherence is non-negotiable: an incoherent combo (e.g. Galaxy A01 + S21
bootloader) is itself a red flag. Every hardware field must match the one picked device. See
`docs/ANTI-FINGERPRINT-STRATEGY.md`. USA-only: brands samsung/google/motorola/lge, US carriers
(MCC 310-316), NANP phones.

## Workflow
Version-bump everywhere (VERSION drives it). Commit + push each unit as it completes (always commit
work). Bot loop on the PR (CodeRabbit/Kilo/gemini + a code-reviewer subagent); apply high-confidence
findings with tests. Never ship cosmetic/non-functional UI — build it or clearly mark it non-functional.

## Project structure & docs to keep updated (NON-NEGOTIABLE)
Keep these current as work happens — they are the project's memory:
- **`CHANGELOG.md`** — every user-facing change under a version heading (Keep-a-Changelog style:
  Added / Changed / Fixed). Update it in the SAME commit as the change, not later. It's CRLF-committed.
- **`docs/IDEAS.md`** — the running ideas/backlog log. When a feature, hypothesis, or "we could also…"
  comes up, append it here with a date and a one-line rationale + status (idea / researching / building /
  shipped / rejected-because). Don't lose ideas in chat — they go here.
- **`docs/ANTI-FINGERPRINT-STRATEGY.md`** — the anti-detection thinking + signal-coverage audit. Append
  findings; mark hypotheses AS hypotheses (not proven fact) until confirmed on-device or with real data.
- **`docs/DECISIONS.md`** — one line per non-obvious call and WHY (e.g. "left cpuinfo real — file-I/O
  hook too risky; ro.board.platform already covers the SoC name"). So a decision isn't re-litigated.
- **`CLAUDE.md`** (this file) — when a build/hook/EOL gotcha or a new invariant is discovered, add it here
  so the next session doesn't rediscover it.

Cadence: at the end of any non-trivial unit, before the final commit, ask "did CHANGELOG / IDEAS /
DECISIONS need a line?" and add it. A finding is a hypothesis until proven — say so; don't overclaim.

## Epistemic discipline
Distinguish PROVEN (verified on-device or by test) from HYPOTHESIS (plausible, code-grounded, unconfirmed)
from ASSUMPTION. Label them as such in docs and reports. A strong hypothesis is still a hypothesis until
it's confirmed with real evidence (e.g. diffing a flagged vs passed account, or measuring a live flag rate).
