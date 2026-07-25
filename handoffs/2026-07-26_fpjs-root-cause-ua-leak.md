# Handoff — FPJS root cause PROVEN: the User-Agent leaks the real Pixel 4 (2026-07-26)

**Read this, then `docs/IDEAS.md` (top entry) and `docs/GOAL.md` 1.3. This is the active problem.**

## TL;DR — what we finally proved
Specter does **NOT** beat FingerprintJS. We proved *why*, decisively, using FPJS's own Server API:
**the User-Agent string still reports the real Pixel 4**, and that is the visitorId anchor. It is a
software-readable leak (hookable), NOT an unspoofable hardware-attestation wall. The fix is half-started.

## How we proved it (the setup is reusable — don't rebuild it)
1. The user pasted their **Public** FPJS key into the demo app's Settings → "Use your API keys" = ON.
   So identifications now land in the **user's own workspace** (clean — no stale record of this Pixel).
2. We read events back with the user's **Secret** key via the Server API:
   `curl -s -H "Auth-API-Key: zTZsBALjWuvpfyMI3Kvm" https://ap.api.fpjs.io/events/<eventId>`
   (region **AP/Mumbai** — other regions return `403 WrongRegion`).
3. Ran the **clean two-rotation test**:
   - identity 2 (Moto G6 profile) → visitorId `SJoG6j4i4vS9DoH6EM90`, `visitorFound:false` (fresh)
   - identity 4 (Galaxy Tab profile — a TOTALLY different device) → **same `SJoG6...`**, `visitorFound:true`, `confidence:1.0`
4. Diffed the raw signals of both events. The server saw the **identical real device** both times:
   ```
   browserDetails.device    = "Pixel 4"          (both)
   browserDetails.osVersion = "11"               (both)   ← real (profiles were Android 9/10)
   browserDetails.userAgent = "Dalvik/2.1.0 (Linux; U; Android 11; Pixel 4 Build/RQ3A.211001.001)"  (both)
   rootApps.result          = True               (both)   ← Magisk detected
   factoryReset.timestamp   = 0                  (both)   ← our spoof works here
   ```
So: no stale-record excuse (clean workspace, visitorFound was false on the first), no IP excuse
(a shared VPN IP can't collapse distinct devices — and the raw data confirms it's the device fields).

## Root cause (mechanism)
The FPJS Android SDK reads device identity from the **User-Agent** (`WebSettings.getDefaultUserAgent()`
and/or `System.getProperty("http.agent")`), which the Android **framework builds from
Build.MODEL + Build.VERSION.RELEASE + Build.ID in a system/WebView process** — NOT the in-app `Build.*`
field reads our Xposed hooks intercept. Our probe shows `Build.MODEL` spoofed *in-process*, but the SDK
reads the real UA from a path we never hooked. That one string is the dominant anchor.

## The fix (NEXT SESSION — start here)
In `xposed-module/app/src/main/java/com/specter/module/HookEntry.java`, near `hookBuildFields` (~line 87):
1. **Hook the User-Agent** — rebuild it from the spoofed Build fields so it reads e.g.
   `Dalvik/2.1.0 (Linux; U; Android <build_release>; <build_model> Build/<build_id>)`:
   - `android.webkit.WebSettings.getDefaultUserAgent(Context)` → return the rebuilt UA.
   - `System.getProperty("http.agent")` (System.getProperty is ALREADY hooked for `os.version` in
     `hookKernelVersion` ~line 133 — add an `http.agent` branch there, same pattern).
   - Also the `http.agent` system property via the SystemProperties/`PROP_ALIASES` path if present.
   - There may be a `WebView.getSettings().getUserAgentString()` instance path too — cover it.
   - COHERENCE: build the UA from `build_release` + `build_model` + `build_id` already in the profile
     (no new generated field, no RNG, byte-parity-safe). Match the real Dalvik UA shape exactly.
2. **Close `rootApps=True`** — FPJS's Android root check is finding Magisk. The native layer hides some
   root paths but clearly misses FPJS's method. Investigate what the SDK checks (likely `which su`,
   Magisk package/paths, `ro.debuggable`/`ro.secure`, mount flags). May need native + Java coverage.
3. **Re-run the two-rotation test** (procedure below). Success = two different profiles → two DIFFERENT
   visitorIds (and ideally `visitorFound:false` on a fresh one).

## The exact test procedure (memorize — the app-key persistence is finicky)
- Device: Pixel 4, serial `9B151FFAZ00FPF`. Demo pkg: `com.fingerprintjs.android.fpjs_pro_demo`.
- Demo is in Specter's LSPosed scope (mid **154** = `com.specter`; NEVER touch mid 101 = GeerGit).
- Apply a new identity WITHOUT wiping the demo's API keys:
  `python -m specter.cli rotate --pkg com.fingerprintjs.android.fpjs_pro_demo --no-clear`
  (**`--no-clear` is mandatory** — `pm clear`/plain `rotate` wipes the demo's encrypted key prefs, and
  the user then has to re-enter them by hand. `am force-stop` preserves them.)
- Force-stop + relaunch (fresh process so Zygisk/Xposed re-hook): `am force-stop ...` then
  `monkey -p ... -c android.intent.category.LAUNCHER 1`. Wait ~4s.
- If a location dialog appears, tap "While using the app" (~x539 y1183 on this 1080×2280 screen).
  Location permission being denied blocks the demo UI.
- Tap the **fingerprint icon** (center, ~x539 y1408), NOT the "Tap to begin" text. Wait ~8s.
- Tap **Raw** tab (~x809 y1389) to read the eventId, or read the on-screen visitorId.
- Pull raw signals: `curl -s -H "Auth-API-Key: zTZsBALjWuvpfyMI3Kvm" https://ap.api.fpjs.io/events/<id>`.
  If it `404`s, the event went to the DEMO's workspace (keys weren't active) — check the toggle is ON.

## Tooling now in place (reusable)
- **Server API via curl** (above). Secret key `zTZsBALjWuvpfyMI3Kvm`, region **ap**. NOTE: this key was
  pasted in chat, so it should be rotated in the dashboard once convenient (low risk — read-only on the
  user's own events).
- **MCP server** `fingerprint-server-api` added to `~/.claude.json` (region `ap`, runs
  `npx github:JuroUhlar/fingerprint-mcp-server server-api`) — **live after a Claude restart**. Auto-exposes
  every Server API endpoint (get-event, search-events) as MCP tools. Repo cloned at
  `C:/claude/MCPs/fingerprint-mcp-server` too. Public key: `4I2a5GaXgzwc27TmMMGk`.

## State of the repo (all clean, main is at the overnight work)
- This session's overnight work (hardware layer 1.3, area codes, SoC coherence, native sensor hooks, UX
  audit + version fix, concurrency hardening) is all MERGED to main (PRs #12–#18). Tests green.
- These findings are on branch `docs/fpjs-root-cause-ua-leak` (docs only) — commit + push + merge it, or
  fold the doc edits into the first fix PR. Files touched: docs/{GOAL,IDEAS,DECISIONS}.md, CHANGELOG.md,
  CLAUDE.md. The UA-hook code change was NOT started in code yet (only planned).
- Build/verify commands, EOL rules, fleet-safety (mid 25/101/154), and the FPJS Server API workflow are
  all documented in `CLAUDE.md`.

## Do-NOT-repeat mistakes from this session
- Don't declare "the gate is met" from the probe alone — the probe proves in-process spoofing, NOT what
  the SDK's real read path sees. The Server API is the ground truth.
- Don't frame the FPJS key as a "blocker needing signup" — it's not a product dependency, and the user
  has keys set up now. Don't use `rotate` (it `pm clear`s and wipes the demo's keys) — use `push --no-clear`.
- The hardware layer (GPU/cpuinfo/sensors) is real and correct, but it was NOT the anchor. The UA is.
