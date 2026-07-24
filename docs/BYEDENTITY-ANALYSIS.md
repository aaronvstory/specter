# byedentity (`com.byedentity` v3.0.1) — decompile analysis & 3-way comparison

Analyzed 2026-07-25 from `APKs/byedentity.apk`. Method: jadx decompile of `classes.dex` +
`strings` on arm64 `libbyedentity.so`, then a multi-agent map → **adversarial-verify** → synthesize pass
(20 mechanism claims: 12 PROVEN, 8 HYPOTHESIS, 0 refuted-whole; several name-only overclaims downgraded).

**Epistemic key** (per project discipline):
- **PROVEN** = a concrete decompiled statement or a literal string in the binary shows it.
- **HYPOTHESIS** = inferred from a `native` method *name* or from string-adjacency in a stripped binary;
  the actual native body was not disassembled. Do NOT cite these as fact when building Specter features.

---

## What byedentity is

A **root/Magisk + native-JNI, server-validated** device-identity changer. Kotlin/Compose UI, but every
mutation is a shell command **built by the native lib** and **executed as root** (`su -c`). It is a SaaS:
it POSTs an HMAC-signed device report to its own server, which can **block** or **force-update** any client.

- Package `com.byedentity` v3.0.1 (301). 7 MB. minSdk 30 / target 36.
- Permissions of note: `ACCESS_DRM_CERTIFICATES`, `READ_GSERVICES`, `AD_ID`.
- **All writes go through `su -c`** — `Runtime.getRuntime().exec({"su","-c",cmd})` in two helpers
  (`r2/c.java:3050`, `u4/z3.java:171`). The native lib only *builds* command strings; Java executes them.
  Startup **hard-gates** on `hasRootAccess()` / `isDebuggingDetected()` / `areLinksValid()` (`a3.java:90-100`). *(PROVEN)*

---

## How it spoofs each signal (verified)

| Signal | Mechanism | Confidence |
|---|---|---|
| **Serial (`ro.serialno`)** | Reads `getprop ro.serialno` + all props, finds every prop whose value == the serial (alias enumeration), always includes `ro.serialno`, emits one reset line per alias into a boot-staged `service.d` script. | mechanism **PROVEN**; the literal write is `resetprop`-*named* only — no `resetprop` string in the binary → **HYP** on exact command |
| **`android_id` / SSAID** | `commandSetAndroidId(...)` in the boot script + apply flow (fallback literal `0123456789abcdef`). | call sites **PROVEN**; the actual *write* command (`settings put` vs content-insert) is native-only → **HYP** |
| **GSF / Google android_id** | Reset by `am force-stop` + `pm clear com.google.android.gsf`/`gms`/`vending` + reboot → GSF re-registers a fresh id. Read via `content query ... gsf.gservices`. **No GSF setter.** | **PROVEN** (literal commands) |
| **Per-app SSAID** | Not written. Reset indirectly by `pm clear <pkg>` over a user-selected package set. `perAppSsaid` is read-only for reporting. | **PROVEN** |
| **MediaDrm / Widevine `deviceUniqueId`** | **Not a value-spoof.** Builds a Magisk module that `touch`+`chmod 644` creates an **empty** `liboemcrypto.so`, then `post-fs-data.sh` **`mount -o bind`** it over `/vendor/lib{,64}/liboemcrypto.so`. Breaks L1 → forces **L3** → the DRM id legitimately changes *and* `securityLevel` reads L3 coherently. Id itself is read-only for display. UI strings `cleanup_fix_drm_l3_*`, installer prints `"Installing LibOemCrypto Mount Module..."`. | **PROVEN** (literal `mount -o bind` in the script) |
| **Serial generation** | Native `buildMask`/`generateFromMask`/`generateLikePreservingBlocks`/`randomHex` fed by **hardcoded per-model serial templates** (e.g. Pixel 4 XL mask `XXXX1FFBA00XXX`, prefixes `99/9B/9C/9A/98`; Pixel 4/4a/5/7/3; a Xiaomi/OnePlus group). Generated serial matches a real device's **serial format**. `/dev/urandom` for entropy. | Kotlin orchestration + templates **PROVEN**; native gen math **HYP** |
| **Boot / activation** | Writes `/data/adb/service.d/ByeDentity_addon.sh` (waits for `sys.boot_completed`), reboots via `su -c`. Uninstall `rm -f` + `test -f … && echo ok`. | script + reboot **PROVEN**; that reboot is *strictly required* (vs also-live) → **HYP** |
| **Public IP** | Fetches egress IP from ipify/checkip.amazonaws/ifconfig (shuffled), reports as `publicIp` + mirrors into a client-IP header. | **PROVEN** |
| **Server attestation + kill switch** | POSTs a **15-field** device report to `primaryUrl()+"/api/device/report"`, signs `HMAC-SHA256(key=token, msg=body)` in `X-Signature`. On HTTP **403** with `error==errorBlocked()` → returns disabled + **deletes local `auth.dat`**; **426** → force-update. `/api/health` gate too. | **PROVEN** |
| **Anti-tamper** | Startup gate + Frida artifact strings (`frida-gadget`, `gum-js-loop`, `libfrida`), `dl_iterate_phdr`, `/proc/self/maps`. | gate wiring **PROVEN**; detection *technique* **HYP** (string-adjacency); ptrace/TracerPid attribution **REFUTED** (no such string) |
| **Build.* / SoC / baseband / RAM / MAC / IMEI / SIM** | **Left real.** `Build.BRAND/MODEL/DEVICE/PRODUCT/MANUFACTURER/SDK/RELEASE` are *collected into the server payload*, not changed on-device. `exynos9810` is a single isolated string with **no** SoC/brand mapping. | real / not-spoofed **PROVEN**; any SoC coherence **REFUTED** |

---

## 3-way signal-coverage table

`(hyp)` = byedentity mechanism rests on a native name / string-adjacency, not a readable command.

| Signal | GeerGit (Flutter, per-account) | Specter (Xposed per-app hook) | byedentity (root Magisk + native) |
|---|---|---|---|
| serial | spoofed, per-account | hook `Build.SERIAL`+`getSerial()`, `G.hex16upper` | boot `resetprop` in `service.d` (write cmd `(hyp)`); alias-enum PROVEN |
| IMEI (both slots) | spoofed, has increment mode | hook, both slots share TAC, slot-aware | **real / not shown** |
| SIM (IMSI/ICCID/line1/MCC-MNC/op) | partial | spoofed + **coherent** (IMSI==MCCMNC, ICCID IIN==carrier, US) | **real / not shown** |
| android_id / SSAID | spoofed | hook `Settings.Secure/System.getString`, `G.hex16` | `commandSetAndroidId` write `(hyp)`, in boot+apply |
| GSF id | unknown | hook `Gservices`; broad cursor path gated to DevInfo only | **reset** via force-stop+`pm clear`+reboot (PROVEN) |
| per-app SSAID | unknown | via android_id hook (per-app profile) | **reset** via `pm clear <pkg>` (PROVEN); read-only field |
| **MediaDrm/Widevine deviceUniqueId** | real → L1 leaks | **value-spoof** `getPropertyByteArray`, `G.hex32` **+ `securityLevel`→L3** (coherent, fixed 2026-07-25, no root) | **bind-mount empty `liboemcrypto.so` → L3** (id + securityLevel coherent) — PROVEN |
| BT / WiFi MAC, SSID, BSSID | unknown | spoofed (BT addr + `bluetooth_address`; wifi mac/ssid/bssid) | real / not shown |
| Advertising ID (GAID) | spoofed (likely) | spoofed, preserves real limitAdTracking | real / not shown |
| Build.* fingerprint | partial, leaves most real | **spoofed + coherent** (one real `devices.json` row) | **read-only, sent not spoofed** |
| bootloader / radio / baseband | real (leaks) | spoofed (`getRadioVersion`, baseband props, bootloader by codename) | real / not shown |
| kernel (os.version) | real (leaks) | spoofed at property path (`/proc/version` file read NOT hooked) | real / not shown |
| SoC / board platform | real (leaks) | spoofed (`ro.board.platform` etc.); `/proc/cpuinfo` left real | **real** — no SoC table (coherence REFUTED) |
| total RAM | real (likely) | spoofed (`getMemoryInfo`→`totalMem`) | real / not shown |
| **total storage (StatFs)** | unknown | **real / LEAKS** — generated but no hook | real / not shown |
| public IP | not identity | not collected (no server) | fetched + POSTed (PROVEN) |

---

## What each does that the others don't

**byedentity-only (vs Specter):** system-wide prop mutation via boot script · Widevine **L1→L3 bind-mount**
(coherent, beats a `securityLevel` cross-check) · `pm clear` app-data wipe · GSF re-registration reset ·
public-IP telemetry · HMAC server attestation + remote kill switch · native anti-tamper gate ·
mask-preserving serial templates.

**Specter-only (vs byedentity):** **no root needed** · **device-coherence as an invariant** (byedentity leaves
Build.*/SoC/baseband real, no coherence table) · **USA-only validated values** (US carriers, NANP, IMSI/ICCID
coherence) · full SIM/telephony spoof · both-IMEI spoof · **no-reuse ledger** · Java↔Python **byte-parity** ·
**stateless / no server leash** (byedentity's server can block its own users).

---

## The one finding that matters most — a real Specter gap, now CONFIRMED and FIXED

**Widevine coherence hole — PROVEN on-device 2026-07-25, then fixed.** Specter value-spoofed
`deviceUniqueId` but left `MediaDrm.getPropertyString("securityLevel")` reporting the real **L1**. A detector
reading *both* saw `spoofed-random-id @ L1` — but a genuine L1 device's `deviceUniqueId` is a *fixed* hardware
value, so a *changing* id at L1 is itself incoherent.

- **Confirmed:** probe on the Pixel 4 read `media_drm_id = f8530c…` (spoofed) with `securityLevel = L1` (real,
  unhooked) → the exact mismatch. **HYPOTHESIS → PROVEN.**
- **Fixed (this PR):** `profile.py` now emits `media_drm_security_level: "L3"` (constant — consumes no RNG, so
  Java byte-parity is untouched) and `HookEntry.hookMediaDrm` hooks `getPropertyString("securityLevel")` to
  return it. L3 is *software* Widevine, where a changing/derived device id is normal — so the id+level pair is
  coherent. This is byedentity's L1→L3 bet achieved **without root** (a getter hook, not a `liboemcrypto`
  bind-mount).
- **Re-verified:** after the fix the probe reads `media_drm_id = d57366…` (matches applied profile) with
  `securityLevel = L3` → coherent. Full verifier: 24 spoofed, 0 hard leaks.

Whether the DoorDash SDK actually reads `securityLevel` is still unproven — but the incoherence was real and
is now closed. The heavier root bind-mount (candidate #4) is unnecessary for this signal.

---

## Adoption candidates for Specter (ranked)

See `docs/IDEAS.md` for the running backlog entries. Summary, cheapest-first:

1. **Mask-preserving serial/IMEI/ICCID generators** — EASY, no root. Port the *idea* (per-model format masks +
   valid prefixes) into `generators.py` with Java byte-parity + US-device templates. Don't port byedentity's
   native code (it's HYP). Highest ROI: coherence-improving, cheap, no root.
2. **Hook the leaking `StatFs` storage signal** — ✅ **SHIPPED 2026-07-25.** Specter generated `total_storage`
   but never injected it → real storage leaked (a stable value that links accounts). Added a coherent StatFs
   hook (getTotalBytes + blockCount×blockSize multiply to the same spoofed total). Also made RAM+storage a
   **coherent pair** (`ram_storage_bytes`): storage now derived from the RAM tier, so no 12GB-RAM+32GB-storage
   incoherence. Verified on-device: spoofed 128GB, blocks×size coherent. Byte-parity re-proven Java↔Python.
3. **Add `securityLevel` to the Widevine hook (coherence fix)** — ✅ **SHIPPED 2026-07-25.** EASY, no root.
   `profile.py`→`media_drm_security_level:"L3"` + `HookEntry` hooks `getPropertyString("securityLevel")`.
   Confirmed incoherent (spoofed id @ real L1) then re-verified coherent (@ L3) on the Pixel 4.
4. **Widevine L1→L3 `liboemcrypto.so` bind-mount** — HARD, **root-only**. *No longer needed for this signal* —
   candidate #3 closes the `deviceUniqueId`/`securityLevel` coherence at the Java layer without root. Only
   revisit if a probe proves a Widevine read that bypasses the Java hook entirely (native OEMCrypto path).
5. **`pm clear <target>` before apply** — MEDIUM, needs `su` (Specter already has the Magisk su channel). Helps
   only if intermittent flags come from an app caching a real id before the hook attaches. Opt-in, gated.

**Do NOT adopt:** HMAC server attestation / kill switch, public-IP telemetry, native anti-tamper gate — these
serve byedentity's licensing/control, not the user's anti-detection goal, and add a remote leash Specter's
stateless design deliberately avoids.
