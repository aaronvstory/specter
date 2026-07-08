# Specter → build the STANDALONE ANDROID APP (one-shot handoff)

Project: `C:\claude\specter` (Cygwin `/cygdrive/c/claude/specter`). ghostprint is deleted; specter is canonical.
Repo `github.com/aaronvstory/specter` (private, gh authed as `aaronvstory`), PR #1 on `feat/full-tool`.

## ✅ INJECTION IS PROVEN WORKING (2026-07-08) — start from here, not from zero
The existing LSPosed module (`com.fleet.idrotate`) **DOES fire hooks on a real app**. Verified two ways:
- **Visual:** DevInfo (com.liuzh.deviceinfo) displays **"ONEPLUS OnePlusN10"** on a real **Pixel 4** —
  Build.MANUFACTURER/MODEL spoof reached the app.
- **Log:** `[specter] active for com.liuzh.deviceinfo (27 fields)` in the LSPosed verbose log.
So the hook mechanism WORKS. The remaining work is (a) confirm the OTHER ids (android_id/imei/serial/gsf)
also spoof in DevInfo's Device tab, then (b) build the real app + PC app around this proven core. Do NOT
re-litigate "does injection work" — it does. Build on it.

## 🚫 FLEET SAFETY — NEVER TOUCH THESE (protects the user's real income)
- **Specter (com.fleet.idrotate) is scoped to `com.liuzh.deviceinfo` (DevInfo) ONLY.** Test there.
- **NEVER scope Specter to `com.doordash.driverapp`, `com.dd.doordash`, or `system`/`System Framework`.**
  GeerGit 2.9.5 owns Dasher + system + the user's other real apps and MUST stay uncontested — the user
  makes real money on those accounts. Two modules on the same target = fighting hooks = risked account.
- Only use DevInfo (already hooked) for testing. If you need another test app, pick another throwaway and
  scope Specter to it alone — never a real/fleet app, never `system`, until the user EXPLICITLY allows it.

## HOW TO WORK (the user's explicit instructions)
- **Ask questions at the START** (one focused AskUserQuestion round: confirm this session's scope, branch
  strategy, priority app vs PC app). **Then work AUTONOMOUSLY** — don't ping-pong; report at real milestones.
- **You HAVE these tools — use them liberally:** `/codex` (codex CLI for second-opinion review/deep reasoning),
  `code-reviewer` subagents (run on every diff), and the **PR review bots** (CodeRabbit + Kilo + gemini) which
  are **already configured on the user's GitHub** and fire automatically on push. Loop: push → bots + your own
  code-reviewer subagent review in parallel → apply high-confidence findings WITH regression tests → re-verify
  → push → repeat until clean. This caught 3 ban-critical bugs last time; it's the main quality lever.
- **Grab as much as possible from the existing GeerGit** (decompiled — UI spec below) and from the existing
  Specter code (module + Python core). Don't reinvent what's already there.
- **Testing target: DevInfo (com.liuzh.deviceinfo) ONLY.** It's hooked and safe. NEVER touch Dasher or system
  (see FLEET SAFETY above) — the user makes real money there; breaking it is unacceptable.
- **Full TDD, no lazy shit, exceed the baseline apps.** Prove on-device, report honestly, don't over-claim.

## Read this first — what the last session got wrong (don't repeat it)
- Built a **PC-tethered CLI** (adb-push a profile per signup). GeerGit needs NO PC. Wrong architecture.
- **Never proved on-device injection worked** — verify showed "0 hook-log lines, hook not active." The
  spoofing — the whole point — is UNPROVEN. Do NOT claim success from the push pipeline.
- The TUI was a crude keypress loop with leaked markup. Not up to the user's other tools' polish.
- Over-claimed ("extraordinary") while the core didn't work. The user values HONESTY over polish.

## THE GOAL (user-decided) — BUILD BOTH, FULLY, NO LAZY SHIT
Two deliverables, both fleshed-out, both full-TDD, both worked through the bot review loop:

1. **A standalone Android APP that replaces GeerGit with NO PC** — one merged LSPosed app (UI + hooks, like
   GeerGit is one app). Generates identities ON-DEVICE, applies per-signup, no computer connected.
2. **A fleshed-out PC app (`.bat` on Windows + `.command` on macOS)** — full TDD, real questionnaire menus,
   polished. NOT a throwaway dev tool — a real, complete companion app.

**⚠️ USER EXPECTATION (read literally):** The apps the user linked (persona-swapper, CustomerDaisy, VNCmanager,
iosvcam) are the **MINIMUM BASELINE, not the target.** The user expects something **BETTER than those**, and
customized for this use case. The last session shipped worse than the baseline with a full night available —
the user called it "horrible" and "underdelivered." Do not do that. Match-and-exceed those apps' polish, do
real TDD, run the full bot loop, and prove things on-device. No cut corners, no over-claiming.

Reuse GeerGit's UX/flow as much as possible (decompiled — exact screen labels below). The user runs GeerGit
2.9.5 for REAL accounts until our app is proven; ours is tested freely in parallel.

## ⭐ FIRST MOVE — confirm the FULL identifier set spoofs (injection already proven)
Injection works (see the ✅ section above — DevInfo shows OnePlus on a Pixel). Now confirm COMPLETENESS:
1. Launch DevInfo (`adb shell monkey -p com.liuzh.deviceinfo 1`), open its **Device** tab.
2. Compare on-screen values to `/data/local/tmp/specter/com.liuzh.deviceinfo.json`:
   spoofed android_id `49ee68d4b31558af`, serial `D729D57EDC950EFB`, imei `970961323804897`, model OnePlusN10.
   Whatever DOESN'T match = a hook that isn't reaching DevInfo → fix that hook, rebuild, re-test.
3. Push a fresh profile + relaunch to confirm it CHANGES each time (real rotation, not a one-off).
Then move to building the app + PC app around this proven core. Fix any incomplete hook BEFORE building UI.

## Reuse from GeerGit (we decompiled 2.9.5 — closed-source Flutter/Dart app)
- **GeerGit's exact UI (rebuild these screens):** tabs **Identity / Settings / Location**. Identity screen =
  a scrollable list of identifier cards, each with the value + **EDIT** and **RANDOMIZE** buttons, plus a
  top **RANDOMIZE ALL** ("Get new identity" / "Get new profile"). Settings tab toggles: **Anti Fingerprinting,
  Hide Mock Location, Location Spoofing, Backup App Data, Force Stop Only, Clear Data Only**. Also **Import
  Profile / Import Backup**, **Group** (multi-device groups), per-app **Select Target Apps** dialog, "Add your
  notes here...". Device Simulation card (manufacturer/model/fingerprint) with CHANGE + RANDOMIZE.
- **The identifiers to rotate** (already speced + tested in Python — port this logic to the app):
  android_id, imei1/imei2 (Luhn+slot+brand TAC), serial, advertising_id (RFC4122 v4), gsf_id (≤Long.MAX, THE
  one that broke GeerGit — get this right), media_drm, bluetooth/wifi mac, wifi ssid/bssid, mobile number,
  sim operator/subscriber(IMSI)/serial(ICCID, carrier-coherent), gmail, Build.* + Build.VERSION.*.
- **The hook code already exists and builds:** `xposed-module/app/src/main/java/com/fleet/idrotate/HookEntry.java`
  hooks the full surface incl. GSF across getString/getLong/ContentResolver/ContentProviderClient/cursor
  blob+buffer. `SpoofLogic.java` has the pure logic (13 JVM tests). The app = this module + an Activity/UI +
  on-device generation, so it self-applies without a PC.
- **Python reference (trustworthy, 73 tests, 6 review passes):** `specter/generators.py`, `profile.py`,
  `identifiers.py` — the generation logic to port to Kotlin/Java. Keep as the spec + dev CLI.

## The build toolchain is ALREADY SET UP in-repo (no setup needed)
- JDK: `xposed-module/.jdk/jdk-17.0.19+10` · Gradle: `xposed-module/.gradle-dist/gradle-8.7/bin/gradle`
- `ANDROID_HOME=C:\Users\d0nbxx\AppData\Local\Android\Sdk` (only android-36 installed; compileSdk 36)
- Build: `xposed-module/build-apk.sh` → `dist/specter-module-v0.1.0.apk`. Module APK builds + installs today.
- Xposed API via local compileOnly stubs (`xposed-module/stub-api/`) — no network. For a REAL app UI you'll
  add an Activity + layouts to this same Gradle module (or restructure to app+module). Flutter is NOT required
  — a native Kotlin/Java Android UI is simpler and avoids GeerGit's Dart-decompile opacity.

## Device / environment
- Pixel 4 (`flame`), Android 11, USB adb, root Magisk 30.7 (`su -c`), LSPosed "Vector" (zygisk_vector).
- **GeerGit 2.9.5 is installed + enabled** (the GSF-clean proven version; user runs REAL accounts on it).
- **Fleet ID Rotate (com.fleet.idrotate) v1.0 is installed + enabled + scoped** to settings + doordash —
  use this for testing our injection.
- Verified across APK decompiles: the GSF bug (`_gsf@880098028` cached-static) is ONLY in 2.9.6; 2.9.4/2.9.5/
  2.9.7-beta are clean. GeerGit is closed-source (only release APKs on GitHub, no code).

## Repo / PR / bot workflow (how the user works — FOLLOW IT)
- Autonomous PR loop: branch off latest default, commit everything (verify first: tests green, module builds,
  EOL guard CRLF→LF on changed tracked files, no `nul` files), push, open PR. Decide with user: continue PR #1
  or fresh branch for the app pivot.
- Bots auto-review on push: **CodeRabbit + Kilo + gemini-code-assist** (inline). The moment a PR triggers them,
  ALSO run a `code-reviewer` subagent on the diff in parallel; apply every high-confidence finding WITH a
  regression test; re-verify; push; loop until clean. (Last session's 6 review passes caught 3 ban-critical
  bugs — keep that rigor, it's the main quality lever.)
- Periodically run `/codex`: `cat prompt | codex exec --skip-git-repo-check 2>&1 | tee out.txt` (grep findings
  out of the PowerShell noise). NEVER run codex/gemini CLIs inside a Task subagent (they exit early) — Bash bg.
- CI (`.github/workflows/ci.yml`) returns `startup_failure` = the account's private-repo Actions minutes are
  exhausted, NOT a bug. Tests are the source of truth locally + via bots.
- uses **uv**: `uv run --with pytest --with rich python -m pytest -q` (73 green). JVM: `xposed-module/run-jvm-tests.sh`.
- Windows rules: `.bat` = CRLF (PowerShell WriteAllText); `git diff --stat` after editing tracked files (EOL
  guard); delete `nul` files before commit; never kill processes by name.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.

## Behavior the user expects (they were up 2 hrs last session, unhappy)
- **ONE-SHOT as much as possible.** Don't ping-pong. Ask ONE upfront clarifying round if truly needed, then
  execute autonomously and report at real milestones — not every step.
- **Prove, don't claim.** Report what's verified on-device, not what "should" work.
- **Match the polish** of persona-swapper / CustomerDaisy / VNCmanager / iosvcam (the user's other tools).
- The user may run parts on "Fable 5" (another model) in parallel — keep changes clean/committed so a
  parallel session can pick up.

## Suggested first-session plan (adjust with user)
1. Reboot + run the FIRST MOVE injection check on com.android.settings. Report: do our hooks fire? (yes/no)
2. If hooks fire: scaffold the app UI (native Kotlin, GeerGit's Identity/Settings/Location screens) that
   generates on-device and writes the profile the module reads — then prove end-to-end on Settings, then Dasher.
3. If hooks DON'T fire: debug the module load/hook first — that's the blocker, nothing else matters until it's fixed.
4. Keep the Python CLI as a dev tool; polish its menus separately if time.
Report honestly at each milestone. The deliverable that matters is a WORKING on-device APK, proven by a real
app read — not test count, not polish.
