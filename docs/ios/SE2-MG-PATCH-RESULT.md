# SE2 MobileGestalt patch — PROVEN result (2026-08-04)

**Device:** SE2 = iPhone12,8 (A13, arm64e), iOS 16.3.1 (20D67), RootHide Dopamine.

## Method (no Picasso, no KFD)
Picasso and every KFD-based MG editor are arm64e/A12+ **and** run their own kernel exploit — needless
here. RootHide's own root can write the cache directly:
`/var/containers/Shared/SystemGroup/systemgroup.com.apple.mobilegestaltcache/Library/Caches/com.apple.MobileGestalt.plist`
(owner `mobile:wheel`, mode 644 — root-writable over SSH). Backed up to `<same>.specterbak` first.

Edited the `CacheExtra` dict (obfuscated key = `base64(md5("MGCopyAnswer"+key))[:22]`, our
`ios/core/profile.py` formula). Spoofed the SE2 → iPhone 12 (a coherent catalog row):

| MG key | obfuscated | was | set |
|---|---|---|---|
| ProductType | `h9jDsbgj7xIVeIQ8S3/X3Q` | iPhone12,8 | **iPhone13,2** |
| HWModelStr | `/YYygAofPDbhrwToVsXdeA` | D79AP | **D53gAP** |
| HardwarePlatform | `5pYKlGnYYBzGvAlIU8RjEQ` | t8030 | **t8101** |

Wrote back (binary plist), `chown mobile:wheel` + `chmod 644`, `killall -9 SpringBoard` (respring —
no reboot, so RootHide survives). Device healthy, no bootloop.

## Verification (lock-independent)
`ios/tools/mgread.m` — a tiny signed CLI that `dlopen`s libMobileGestalt and prints `MGCopyAnswer` +
the `sysctl hw.*` counterparts from a **fresh process** (works while the screen is locked; no
SpringBoard, no frida). **Gotcha:** RootHide only execs a self-signed (`ldid -S`) binary from a jbroot
path (`/var/jb/usr/bin/`) — running it from `/var/root` gives `Killed: 9` (AMFI).

```
MG  ProductType   = iPhone13,2   ← spoofed        sysctl hw.machine = iPhone12,8  ← REAL
MG  HWModelStr    = D53gAP       ← spoofed        sysctl hw.model   = D79AP       ← REAL
MG  HardwarePlatform = t8101     ← spoofed
```

## What this proves — and its ceiling
- **PROVEN:** a no-injection, blacklist-safe, device-wide MobileGestalt model spoof via a plain root
  edit. Cash can stay RootHide-blacklisted (zero injection to detect) and still see a changed MG model.
- **CEILING (measured):** `sysctl hw.machine`/`hw.model` are kernel values, NOT in this cache — they
  still read the real device. Any reader that gets the model via `sysctl` (very common) is unaffected;
  only MobileGestalt-based reads change. Closing the gap needs either injection (SpecterTweak — blocked
  while blacklisted) or a kernel-level sysctl patch (device-wide, riskier).
- Device-wide only: one identity for the whole phone, no per-account rotation (Crane still handles the
  per-account data/IDFV isolation, independently).

## HYPOTHESIS — this route may also work on the A11 iPhone 8
The A11 exclusion was for **Picasso/KFD** (arm64e-only exploit). Our direct edit needs only RootHide
root + a writable MG cache — no KFD. So the same `CacheExtra` edit likely works on the iPhone 8 (A11)
too. UNVERIFIED — confirm the iPhone 8's MG cache is root-writable, then apply the same patch.

## Revert
`cp <cache>.specterbak <cache>` + `chown mobile:wheel` + `chmod 644` + respring. (Original also pulled to
the PC.)
