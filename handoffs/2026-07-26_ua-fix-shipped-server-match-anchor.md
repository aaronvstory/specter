# Handoff — UA leak CLOSED, APK-mtime CLOSED, MODEL/DEVICE bug fixed; anchor is server-side match

**Read this, then `docs/ANTI-FINGERPRINT-STRATEGY.md` (bottom section, dated 2026-07-26) for the measured
detail. The remaining blocker needs ONE manual UI step from the user (below).**

## What shipped this session (all committed to `feat/ua-spoof`, tests green)
1. **User-Agent spoofing — PROVEN root cause, now fixed.** `System.getProperty("http.agent")` and
   `WebSettings.getDefaultUserAgent` are rebuilt from the profile's build_release/model/id. Confirmed via
   the FPJS Server API: `browserDetails.{device,userAgent,osVersion}` now track the applied profile
   (was leaking the real Pixel 4 on every rotation). Commit 4af4041.
2. **MODEL/DEVICE were bound to the wrong dataset columns** — every profile shipped an impossible
   fingerprint like `google/bramble/Pixel 4a (5G):11/...` (spaces/parens in the DEVICE slot). Fixed on
   both sides, byte-parity re-proven over 195 fields, verified on-device. Commit 4af4041.
3. **APK install-mtime spoofing — closes FPJS's `FileTimestamps` raw signal.** Decompiled the SDK +
   traced on-device: it reads `lastModified()` on the app's own `/data/app/.../base.apk` +
   `split_config.*.apk`, whose mtimes are the constant install time. Now hooked (own-APK only,
   per-identity value from factory_reset_epoch). Commit a0f638b.

## The decisive finding (why the visitorId still didn't split)
With UA + FileTimestamps + all fields spoofed, the visitorId did NOT change across rotations. I then
**deleted the SDK's entire local cache** (`fpjs_prefs_v2.xml` + `files/datastore`) and re-identified:
**the server returned the identical visitorId.** So the visitorId is computed 100% SERVER-SIDE from the
signal payload — it is not client-cached. The anchor is a signal (or signal-set) the client still sends
truthfully. UA, device fields, and file timestamps are NOT it (all now spoofed/deleted, ID unchanged).

## THE METHOD BLOCKER (must fix before more measurement is trustworthy)
`pm clear` (what `rotate` runs) wipes the demo's user-entered API keys. Without them the demo falls back
to its BUILT-IN public key, whose workspace is SHARED by every FPJS-demo user worldwide. A stable
visitorId there (`18uu8...`, firstSeen "17 days ago") is a shared-bucket artifact — it does NOT prove
anything about whether OUR spoof split the device identity. **Every conclusion about "did the id change"
is only valid in the USER'S OWN workspace.**

### The one manual step the user must do (cannot be scripted — encrypted prefs)
Open the demo → Settings → "Use your API keys" ON → paste the Public key `4I2a5GaXgzwc27TmMMGk`.
Then test with `push --no-clear` ONLY (NEVER `rotate`/`pm clear`, which wipes it again). Read events with
the Secret key `zTZsBALjWuvpfyMI3Kvm` at `https://ap.api.fpjs.io/events/<id>` (AP region).

## NEXT (once keys are back in the user's workspace)
Hunt the server-match anchor by elimination — apply identity A (`push --no-clear`), identify, pull raw
signals; toggle ONE signal group off; identify again; diff. Prime suspects, in order:
1. **Installed-apps entropy** — `InstalledAppsSignalGroupProvider` sends the user+system app list, a
   large stable device-unique set. Hide-my-apps / the module's own presence feed it. Not yet hooked.
2. **`rootApps=True` / `developerTools=True`** — still leaking. `developerTools` reads
   `Settings.Global.getString(adb_enabled/development_settings_enabled)`; Specter hooks these yet the
   server still flags it → confirm the exact read path (may be a ContentResolver query we miss).
3. Deep hardware signals the composite still hashes (GPU/sensor lists exist; verify none leak real).

## Tooling / state
- Device Pixel 4 `9B151FFAZ00FPF`. Demo `com.fingerprintjs.android.fpjs_pro_demo`, LSPosed mid **154**
  (`com.specter`). NEVER touch mid 101 (GeerGit). Confirmed in scope this session.
- **Syscall tracer is wired**: add `"trace":"1"` to a pushed profile → Zygisk logs every stat/open/prop
  (tag `SpecterTrace`) and the Java hooks log `[specter][osstat]`/`[lastmod]`. This is how the APK-mtime
  anchor was found. Harmless when trace is absent/0.
- Zygisk `.so` on-device is rebuilt WITH the stat-trace (base64-deployed + md5-verified; `adb push`
  no-ops for the .so — use the base64 route). A reboot reloads it.
- Branch `feat/ua-spoof` is ahead of main by 2 commits; not yet PR'd/merged. Python 103 pass, JVM 61,588.
