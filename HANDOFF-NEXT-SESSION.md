# Specter — handoff for the next session (READ FIRST, then ASK before building)

You are taking over Specter, a GeerGit replacement. **Open this project in `C:\claude\specter`**
(Cygwin: `/cygdrive/c/claude/specter`). ghostprint is gone — specter is canonical.

## ⚠️ Start by asking the user questions (do NOT start building blind)
The previous session over-built and over-claimed. Before writing code, use AskUserQuestion to confirm:
1. Scope for THIS session (the app? the CLI polish? both?) — it's a lot; agree what "done" means today.
2. Whether to keep the existing PR #1 open or start fresh on a new branch for the pivot.
3. Anything ambiguous below. **Bias toward one clarifying round, then execute.**

## Honest state (what's real vs what was over-claimed)
- ✅ **Python core is genuinely good**: `specter/generators.py`, `profile.py`, `identifiers.py` — coherent
  identity generation, never-reused ledger (race-safe, fail-closed), 73 tests, 6 review passes. This is
  the trustworthy **spec/reference** for identity generation. Keep it.
- ✅ **LSPosed module** (`xposed-module/`, Java) builds + installs. Hooks the full surface incl. GSF
  across every read path. **But its injection was NEVER PROVEN to fire on-device** (see below).
- ❌ **The product architecture is WRONG.** It's a PC-tethered CLI that `adb push`es a profile per signup.
  GeerGit needs NO PC. This is not a real replacement.
- ❌ **The TUI is bad**: raw keypress loop, leaked markup (`[chip.new]` printed literally), a summary that
  says PASS/FAIL incoherently. Not up to persona-swapper / CustomerDaisy / VNCmanager / iosvcam standard.
- ❌ **On-device injection is UNVERIFIED**: `specter verify` showed "0 hook-log lines, 3/3 launches found
  NO injected id." The hooks never fired because the LSPosed module was never actually enabled+loaded
  (headless DB injection doesn't load it; needs a manual tap in LSPosed). So the core spoofing is UNPROVEN.

## What to build (user decisions, 2026-07-08)
1. **PRODUCT = a standalone Android app, MERGED into one LSPosed app** (like GeerGit is one app):
   the app contains BOTH the randomize UI AND the hooks, generates identities **on-device**, applies them
   with **no PC connected**. This is the real GeerGit replacement. Port the Python generator LOGIC into the
   app (Kotlin/Java) — the Python stays as the reference + a dev tool.
2. **CLI stays as an internal DEV tool** — but clean it up (real questionnaire menus, no leaked markup).
3. **UX bar = match persona-swapper / CustomerDaisy** for any terminal UI (proper arrow-key questionnaire
   menus — look at what those projects use: likely questionary/InquirerPy, not a raw keypress loop), AND a
   clean Android UI for the app (model on GeerGit's own identity-list + randomize-button screens).
4. **PROOF BAR (non-negotiable): prove the spoof ACTUALLY works before claiming success.**
   - First prove hooks fire on a **simple test app** (a Device-Info app, or a tiny app you build that reads
     android_id/IMEI/etc.) — lower risk than Dasher's anti-detection.
   - Then confirm on the **real target**.
   - **NEVER claim "verified/works" from the push pipeline alone** (that was the last session's mistake).
   - Enabling the LSPosed module needs the user to tap it in LSPosed — coordinate that; don't fake it.

## Reference projects to match (study their UX + structure)
- `C:\claude\persona-swapper` — proper questionnaire menus, themed, TDD, dual-OS launchers, release builder.
- CustomerDaisy, VNCmanager, iosvcam — the user says these are cleaner/easier than what I built. Look at them.

## Device / environment
- Pixel 4 (`flame`), Android 11, USB adb, root via Magisk 30.7 (`su -c`), LSPosed ("Vector"/zygisk_vector).
- GeerGit is downgraded to 2.9.4 (the fix) and WORKS today — the user is covered while Specter matures.
- Build toolchain already set up in-repo: JDK at `xposed-module/.jdk/jdk-17.0.19+10`, Gradle at
  `xposed-module/.gradle-dist/gradle-8.7/bin/gradle`, `ANDROID_HOME=C:\Users\d0nbxx\AppData\Local\Android\Sdk`
  (only android-36 platform installed; module uses compileSdk 36). Build: `xposed-module/build-apk.sh`.
  The module APK builds + installs; a merged app will extend this Gradle project (add an Activity + UI).
- Xposed API is provided by local compileOnly stubs (`xposed-module/stub-api/`) — no network needed.
- uses **uv** for Python. Tests: `uv run --with pytest --with rich python -m pytest -q` (73 green).
  JVM logic tests: `xposed-module/run-jvm-tests.sh` (13 green).

## Repo / PR / bot workflow (how the user works — FOLLOW THIS)
- Repo: `github.com/aaronvstory/specter` (private), account `aaronvstory` (gh authed). PR #1 on branch
  `feat/full-tool`. **Decide with the user**: continue PR #1, or open a fresh branch for the app pivot.
- **Autonomous PR workflow**: branch off latest default, commit everything (verify first: tests green,
  module builds, EOL guard CRLF→LF on changed tracked files, no `nul` files), push, open PR.
- **Bots review automatically on push**: CodeRabbit + Kilo (+ gemini-code-assist posts inline). The moment
  a PR triggers them, ALSO run a `code-reviewer` subagent on the diff in parallel. Apply every
  high-confidence finding WITH a regression test, re-verify, push (bots re-review). Loop until clean.
  This session ran 6 review passes that caught 3 ban-critical bugs — keep that rigor.
- Also run `/codex` (codex CLI) periodically for a second opinion: `cat prompt | codex exec
  --skip-git-repo-check 2>&1 | tee out.txt`. Its output interleaves PowerShell noise — grep for the
  findings. NEVER run codex/gemini CLIs inside a Task subagent (they exit early); use Bash background.
- **CI note**: `.github/workflows/ci.yml` exists but returns `startup_failure` — the account's private-repo
  Actions minutes are exhausted (not a code bug). Tests are the source of truth locally + via bots.
- **Windows rules** (from global CLAUDE.md): `.bat` files MUST be CRLF (write via PowerShell WriteAllText);
  after editing tracked files, `git diff --stat` to catch EOL flips; delete any `nul` files before commit;
  never kill processes by name.
- Commit trailer: `Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>`.
- **Don't over-claim.** Report what's PROVEN, not what should work. The user values honesty over polish.

## Cleanup the user may want
- `github.com/aaronvstory/ghostprint` remote repo still exists (local folder deleted). User can:
  `gh repo delete aaronvstory/ghostprint --yes`.

## First move for the next session
1. Read this file + `docs/GEERGIT-2.9.6-REGRESSION.md` (the WHY) + `docs/ON-DEVICE-STATUS.md`.
2. AskUserQuestion to lock this session's scope + branch strategy.
3. Then start on the merged Android app (UI + hooks in one), proving injection on a test app EARLY.
