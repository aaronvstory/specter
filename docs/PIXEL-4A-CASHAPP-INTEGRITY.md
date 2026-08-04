# Pixel 4a — Cash App "device or software isn't supported" (root cause)

**Date:** 2026-08-03 · **Device:** Pixel 4a (sunfish, `17031JEC204747`), Android 11
(`google/sunfish/sunfish:11/RQ3A.211001.001`).

Cash App shows **"This device or software isn't supported. Cash App doesn't support the use of emulators
or other software programs."** at launch. This is a **tamper/integrity block**, NOT a fingerprint-coherence
problem — and the months we spent blaming incoherent fingerprints were chasing the wrong layer.

## Proof it's not coherence

The 4a's own Specter live-trace coverage of the failed launch (`specter-coverage-…txt`, 2026-08-03 20:02):

```
148 distinct signals · 14439 reads
19 spoofed · 0 leaking · No known leaks
```

Fingerprints are being spoofed **cleanly** — zero leaks — yet Cash still blocks. So the block happens at
a gate that runs *before* fingerprint matters. In the same trace, Cash reads **`/proc/self/maps` (×30),
`/proc/<pid>/maps`, `/proc/self/task`, `/system/etc/security/cacerts/*`** — classic **anti-tamper: scanning
its own address space for an injected hooking framework** (Zygisk/LSPosed) + cert-pinning.

## Root cause (device-proven, ranked)

Three sufficient failures stack on the 4a. Any one could trip the block; all are present.

**1. Cash App is being hooked by Specter via LSPosed — and that in-process hooking is itself the tamper
signal (most likely single cause).** PROVEN on-device: `com.squareup.cash` is in Specter's LSPosed scope
(mid 154, in `/data/adb/lspd/config/modules_config.db`) AND has an active profile at
`/data/local/tmp/specter/com.squareup.cash.json` (updated 2026-08-03 20:01). So LSPosed Java hooks are
injected into Cash's *own* process, and Cash's launch-time anti-tamper (the `/proc/self/maps` scan seen in
the trace) detects the Xposed/hook artifacts. This is why weak-detection apps (DoorDash) tolerate Specter
but Cash does not. **Design implication:** for Cash specifically, LSPosed Java-hooking is detectable — it
must be spoofed via the **Zygisk-native path only**, with Cash kept out of the LSPosed Java-hook scope.

**2. Play Integrity DEVICE fails — no attestation spoof at all.** PROVEN: no `playintegrityfix`, no
TrickyStore module, `/data/adb/tricky_store` doesn't exist; bootloader **unlocked**
(`ro.boot.verifiedbootstate=orange`, `vbmeta.device_state=unlocked`). Nothing fakes hardware attestation →
DEVICE fail → uncertified device. *Favorable nuance:* the 4a is **Android 11, `first_api_level=29` (NOT an
RKP/A13+ device)**, so a leaked **RSA-2048 keybox still yields DEVICE** and is unaffected by Google's
Feb–Apr 2026 RSA-4096 root migration. Easier to pass than a modern Pixel.

**3. Root not hidden.** PROVEN: Magisk DenyList **empty** and no Shamiko/HMA → Magisk/Zygisk/su fully
visible to Cash + GMS. Also stale identity (`security_patch=2021-10-01`, 2021 stock fingerprint).

Modules present: `hosts`, `specter_ota_block`, `specter_widevine_l3`, `specter_zygisk`, `zygisk_vector`
(LSPosed). Zygisk on. `/proc/version` is clean stock (ROM-string leak is NOT the cause).

This is an **integrity/tamper gate, not a fingerprint problem** — the trace shows fingerprints spoofing
clean (0 leaks) while Cash still blocks.

## Fix plan (evidence-based, ordered most-likely-first)

1. **Take Cash out of Specter's in-process LSPosed hook surface** (the single change most likely to clear
   the message): delete `/data/local/tmp/specter/com.squareup.cash.json` (Specter's Zygisk gates on the
   profile FILE, per [[zygisk-gates-on-profile-file-not-scope]]) AND remove `com.squareup.cash` from
   Specter's LSPosed scope (**mid 154 only** — never touch GeerGit's 101). Force-stop + clear Cash data,
   relaunch. If the message clears, LSPosed detection was the cause → the durable answer is native-only
   spoofing for Cash.
2. **Hide root from Cash + GMS:** install Shamiko, enable DenyList **enforce**, add `com.squareup.cash`,
   `com.google.android.gms` (esp. `com.google.android.gms.unstable`), `com.android.vending`.
3. **Pass Play Integrity DEVICE:** maintained PIF fork (KOWX712/PlayIntegrityFork) with a current un-burned
   Pixel 4a fingerprint + **TrickyStore v1.4.1** with an **unrevoked** keybox at
   `/data/adb/tricky_store/keybox.xml`, target `com.squareup.cash` + `com.google.android.gms` in
   `target.txt`. Verify the keybox serial isn't on Google's revocation CRL first. (A11 → leaked RSA-2048
   keybox works.)
4. **Refresh the stale 2021 fingerprint/patch** via the PIF `pif.json`.
5. **Clear Play Store + Play Services storage, reboot,** run the Play Integrity Checker
   (`gr.nikolasspyr.integritycheck`), confirm BASIC+DEVICE **green** *before* opening Cash; then clear Cash
   data once so it re-attests.
6. If it still blocks with a clean process + DEVICE green → capture launch `logcat | grep -iE
   'integrity|attest|root|xposed|tamper'`, and suspect a **server-side device/account flag** from prior
   tampered launches (fresh account and/or clean egress IP).
7. **Diff against the working Pixel 4** once it's on adb (needs USB-debug auth on its screen) — copy
   whatever it has that the 4a lacks (likely: Cash NOT in LSPosed scope, DenyList populated, TrickyStore+keybox).

## Note
Not a Specter spoofing bug — spoofing works (0 leaks). The 4a lacks the **root/integrity-hiding layer**
that must sit *under* Specter, and Cash is being LSPosed-hooked in a way Cash detects. Sources: the
`cashapp-android-integrity` research workflow (Play Integrity 2026 state — PIF alone no longer passes DEVICE
on A13+, needs TrickyStore+keybox; keyboxes revoked ~12h after leak; RKP RSA-4096 migration Feb–Apr 2026).

