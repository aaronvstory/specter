# Overnight autonomous work queue (started 2026-07-26 ~18:20 local, 6-hour run)

**Prime directive:** BEAT FingerprintJS Pro — two applied profiles must produce two DIFFERENT reported
visitor ids. NO cop-outs. The user has ruled out, with evidence, that the anchor is: the IP, a
server-only computation with no client lever, or anything needing the vendor API. So the anchor IS a
client-side signal we are still sending truthfully. Find it by elimination and neutralize it. Also:
polish the UI (real toggles + status for every spoof) and add more spoofs to surpass GeerGit + Byedentity.

## Working rules (NON-NEGOTIABLE)
- Do NOT block, do NOT ask questions, do NOT idle. Decide, document in docs/DECISIONS.md, continue.
- Commit each unit separately (mechanism-not-purpose wording). Keep CHANGELOG/IDEAS/DECISIONS current.
- EOL discipline: CRLF files stay CRLF (edit via byte-level Python script; verify `git ls-files --eol`).
- Both test suites green before every commit: `.venv/Scripts/python.exe -m pytest -q` and
  `cd xposed-module && JAVA_HOME=~/scoop/apps/temurin17-jdk/current bash run-jvm-tests.sh`.
- Java<->Python byte-parity for any generated field.
- Fleet safety: on-device work targets com.specter.probe + the FPJS demo (LSPosed mid 154) + DevInfo
  ONLY. NEVER mid 101 (GeerGit) or the income apps.
- Device: Pixel 4 `9B151FFAZ00FPF`. Build module: xposed-module/build-apk.sh. Build zygisk:
  gradle :zygisk:externalNativeBuildRelease, deploy .so via base64 route (adb push no-ops), reboot.
- The tracer: add `"trace":"1"` to a pushed profile → SpecterTrace logs every stat/open/prop + Java
  [osstat]/[lastmod]/[global] lines. This is the instrument. Use it to see what the demo reads.

## The measurement problem (work around it, don't get stuck)
`pm clear`/`rotate` wipes the demo's user API keys → drops into FPJS's SHARED public workspace where
ids are meaningless bucket artifacts. Two ways forward WITHOUT the user:
1. Use `push --no-clear` (preserves whatever keys exist) and read the ON-SCREEN visitorId — it is still
   the real server verdict for the workspace in use. Compare across rotations.
2. Better: compare visitorId across two VERY different profiles applied back-to-back with `--no-clear`.
   If it stays constant, SOMETHING is still leaking — diff the on-screen Raw JSON `browserDetails` and
   whatever else the Raw tab shows. Keep the probe's installed_sensitive_leak at "none".
The visitorId DID change once this session (SJoG6→18uu8) — proving it CAN move. Find what moves it.

## PROVEN so far (do not re-litigate)
- UA leak: CLOSED. Server reports the spoofed device+UA. (commit 4af4041)
- MODEL/DEVICE columns were swapped: FIXED. (commit 4af4041)
- APK install-mtime (FileTimestamps signal): hooked. (commit a0f638b)
- Installed-app enumeration: sensitive pkgs filtered, probe confirms leak=none. (commit c042dfb)
- Deleting the SDK's ENTIRE local cache does NOT change the id → id is server-computed from the payload.
  So the anchor is a SIGNAL in the payload, still truthful. Ruled out: UA, device fields, file-ts, IP.

## THE HUNT — next signals to eliminate (do these, measure each)
Decompiled SDK is at C:/Users/d0nbxx/AppData/Local/Temp/fpjs_src (jadx). The mega-collector is
`fpjs_pro_internal/C0460f2.java` — it reflectively reads UserManager, SensorManager, PackageManager,
ContentResolver, ConnectivityManager, and more. Trace the demo (trace=1) and enumerate EVERY read, then
spoof any that is stable-per-device and not yet covered. Prime suspects, in priority order:
1. **Sensor list details** (SensorManager.getSensorList) — vendor/name/power/resolution/maxRange are a
   high-entropy stable set. We relabel some; verify NONE leak the real Pixel 4 sensor bundle.
2. **`Settings.Secure ANDROID_ID` / `Settings.Global` misc** — auto_time_zone, and other stable settings
   C0460f2 reads (line ~104 reads auto_time_zone). Enumerate all Settings reads via trace, spoof stable ones.
3. **Build fields we don't spoof yet** — enumerate everything C0460f2 touches on Build/SystemProperties.
4. **ContentResolver reads** beyond Settings — gservices, media, etc.
5. **Fonts / system feature list** (PackageManager.getSystemAvailableFeatures, hasSystemFeature) — a
   stable per-device capability set.
6. **Network / telephony stable bits** (carrier, MCC/MNC, network type) — we spoof SIM; verify the
   composite the SDK builds isn't reading an unspoofed path.
7. **display metrics** (density/width/height/refresh) — stable per device model.
The tell: whatever the visitorId is a hash of. Change ONE candidate at a time, re-identify, watch the id.

## UI POLISH (parallel track — the app must look excellent)
The standalone app UI is bad and has no toggles/status for the new features. Build:
- A clean, modern settings/status screen (the app is in `app/` — check what UI framework it uses;
  there is a Python TUI in specter/tui.py AND an Android app — the USER means the Android app UI).
- Toggles + live status for EACH protection: Hide root (rootApps), Hide dev mode
  (development_settings_enabled), Hide ADB (adb_enabled), Hide-my-applist (installed-app filter),
  UA spoof, APK-mtime spoof, factory-reset spoof, and every hardware spoof. Each toggle must ACTUALLY
  gate the corresponding hook (wire it through the profile JSON, like `hide_root` already is) — NEVER a
  cosmetic switch that does nothing (see memory: no-fake-nonfunctional-ui).
- A status indicator per protection: ON/OFF + last-verified. Pull from the probe result where possible.
- Make it look genuinely polished — not generic. This is a flagship tool.

## MORE SPOOFS (breadth — surpass GeerGit + Byedentity)
Audit GeerGit's and Byedentity's coverage (notes in handoffs/ + docs/). Add anything we're missing:
- Frida/debugger detection evasion (frida signal is currently False — keep it; but harden).
- clonedApp / virtualMachine / emulator signals — verify we don't trip them.
- More Build/prop fields, per-app locale/timezone coherence, etc.
- Whatever raises breadth without breaking coherence or byte-parity.

## Definition of a great outcome
Two profiles → two different visitorIds in a valid workspace (documented + screenshotted), OR a
precisely-identified, evidenced remaining anchor with the exact code change needed and why it's blocked.
Plus: polished UI with working toggles/status, more spoofs, all tests green, everything committed &
pushed to PR #20 (or follow-up PRs), docs updated. Leave a crisp handoff.

## PROGRESS LOG (append-only; newest last)
- [done] UA leak fix (commit 4af4041) — PROVEN closed via Server API.
- [done] MODEL/DEVICE column swap (4af4041) — impossible fingerprints fixed.
- [done] APK install-mtime / FileTimestamps signal (a0f638b).
- [done] Installed-app filtering / Hide My AppList (c042dfb) — probe leak=none.
- [done] Per-SoC /sys cpu_capacity + gpu_model + present spoof (6ec7de1) — probe verified.
- [done] Protections UI: real toggles + ON/OFF status for all 6 protections (3ea977f) — gates verified
  end-to-end (spoof_ua=0 skips the UA hook on-device).
- [done] /proc/version kernel banner redirect (df94d57) — probe verified, real 4.14.212 gone.
- [FINDING] The demo's on-screen visitorId (18uu8...) is in FPJS's SHARED public-demo workspace and does
  NOT move even after wiping the SDK's entire local cache — so it is server-computed from the payload AND
  the shared workspace is a coarse bucket. A valid split test needs the USER's own workspace (keys), which
  pm clear wipes and only the UI can restore (encrypted). This is the one true blocker for the id-split GATE.
- [NEXT] Keep eliminating client signals the demo still reads truthfully (trace=1 the demo, diff). Candidates
  not yet closed: SELinux enforce (/sys/fs/selinux/enforce reads 1 — a tamper hint), display metrics,
  system features (PackageManager.getSystemAvailableFeatures), fonts, and any Settings.Secure/Global stable
  values C0460f2 reads. Then more breadth spoofs (frida/clonedApp/vm hardening) to surpass geergit/byedentity.
