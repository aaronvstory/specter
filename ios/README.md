# Specter for iOS

An iOS port of Specter, kept **fully separate from the Android tree** (all under `ios/`). Same idea as
Android — generate one *coherent* device identity and enforce it consistently on-device — but built on
the iOS jailbreak stack (ElleKit + Choicy + theos) instead of Xposed/LSPosed.

**Read first:** `../docs/ios/DEEP-DIVE-FINDINGS.md` — the primary-source investigation that grounds this
(what Crane does/doesn't spoof, the real read paths, the ceilings). `../docs/IOS-PORT-FEASIBILITY.md` for
the overall plan; `../docs/ios/CASHAPP-READ-TRACE.md` for measured Cash App reads.

## Layout

| Dir | What | Runs where |
|---|---|---|
| `core/` | Coherent Apple device **catalog + profile generator + validator** (Python) | PC |
| `tweak/` | **SpecterTweak** — the ElleKit/Substrate dylib that does the spoofing | device |
| `probe/` | **SpecterProbe** — an app that reads every spoofable signal → JSON (efficacy instrument) | device |
| `trace/` | Frida device-read tracer + ID probe (research/measurement) | device (test only) |
| `verify.py` | Diffs the probe readout against the applied profile → per-signal PASS/FAIL | PC |

The **coherence engine is the differentiator** — every hardware field comes from one real device row, so a
rotated identity can never be an impossible device. No surveyed iOS tool (Crane, WeaponX, LiveContainer)
enforces this.

## How it fits together

```
core/profile.py --emit-plist  ->  /var/mobile/Library/Specter/<bundleid>.plist   (the profile)
                                            │
SpecterTweak (scoped via Filter) reads it and returns those values on every read path:
   UIDevice.identifierForVendor/name/systemVersion · sysctlbyname · sysctl(MIB) · uname · MGCopyAnswer_internal
                                            │
SpecterProbe reads all the same signals back  ->  probe_result.json
                                            │
verify.py  (profile vs probe_result)  ->  per-signal ✅/❌
```

Two safety gates on the tweak: the **Filter plist** (which bundles it injects into) *and* the **profile
file** (no `<bundleid>.plist` ⇒ the tweak stays inert). So injection without a profile is harmless.

## Build (Windows + WSL)

Prereqs: WSL Ubuntu with theos at `~/theos` (toolchain `toolchain/linux/iphone`, an SDK in `sdks/`).
Set up once with `../<scratchpad>/setup_theos.sh` (clones theos, fetches the swift toolchain + iPhoneOS SDK).

```bash
wsl -d Ubuntu -- bash /mnt/f/claude/specter/ios/build.sh all     # -> ios/dist/*.deb
```
Builds in the WSL home (native fs) and copies the `.deb`s back to `ios/dist/`. `THEOS_PACKAGE_SCHEME=rootless`,
`ARCHS = arm64 arm64e`. Depends on `ellekit` on-device.

## Efficacy test (prove it actually spoofs)

On the **SE2 test device** (never a live-account device — Cash detects Frida/instrumentation; see the
deep-dive), using a benign target app added to `tweak/SpecterTweak.plist`:

```bash
# 1. install probe + tweak
scp ios/dist/*.deb root@device: && ssh root@device 'dpkg -i *.deb && killall -9 SpringBoard'

# 2. BASELINE — no profile yet -> probe reads the REAL device
ssh root@device 'rm -f /var/mobile/Library/Specter/com.specter.iosprobe.plist'
#   launch SpecterProbe, then:
ssh root@device 'cat /var/mobile/Library/Specter/probe_result.json' > baseline.json

# 3. APPLY a coherent profile for the probe, relaunch, read back
python ios/core/profile.py --model iPhone14,6 --seed 7 --emit-plist /tmp/p.plist
scp /tmp/p.plist root@device:/var/mobile/Library/Specter/com.specter.iosprobe.plist
#   relaunch SpecterProbe, then:
ssh root@device 'cat /var/mobile/Library/Specter/probe_result.json' > spoofed.json

# 4. VERDICT
python ios/verify.py --profile /tmp/p.plist --probe spoofed.json --baseline baseline.json
```
Green table + exit 0 = every read path flipped from the real value to the coherent spoof. Any ❌ names the
exact leaking read path (e.g. a MobileGestalt key the internal-worker hook missed on this iOS build).

## Tests (TDD)

```bash
python -m pytest ios/core/test_profile.py -q     # generator determinism, coherence, validator, plist export
python ios/core/profile.py --demo                # quick self-check + sample profile
```

## Status / ceilings

Coverage today (each hook complete): UIDevice IDFV/name/systemVersion · sysctlbyname · sysctl(MIB) · uname
· MobileGestalt (internal-worker hook). TODO (marked in `tweak/Tweak.xm`): IORegistry, GSSystemGetSerialNo,
statfs storage tiers, boot-time cache, IDFA. **Not spoofable by any hook (account-management, not spoofing):**
iCloud `ubiquityIdentityToken` + server-side DeviceCheck — need a distinct iCloud sign-in per identity.
