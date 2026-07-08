# Specter resources — GeerGit APKs, decompiles & research

Everything gathered while diagnosing the GeerGit ban and building Specter. This is the reference
material for building our replacement — the UI to mirror, the identifier surface to cover, and the
proof of exactly what broke.

## `geergit-apks/` — all four GeerGit versions
| File | Status | Notes |
|---|---|---|
| `geergit-2.9.4.apk` | ✅ clean | Friend A runs it. GSF-clean. |
| `geergit-2.9.5.apk` | ✅ clean | Friend B runs it. **The version currently on the Pixel** (proven, stable). |
| `geergit-2.9.6-BROKEN.apk` | 🔴 broken | The version that banned the fleet — has the cached-static GSF bug. |
| `geergit-2.9.7-beta.3.apk` | ✅ GSF-clean but beta | Dev's fix; DM-only, unreleased. GSF bug gone but unproven. |

GeerGit is **closed-source** (a Flutter/Dart app). The GitHub repo `Xposed-Modules-Repo/com.pyshivam.geergit`
only hosts release APKs + metadata — no source. So these APKs + the decompiles below are the only way to
compare versions.

## `decompiles/` — extracted binaries + strings
- `libapp-<ver>.so` — the arm64 Dart AOT binary from each APK (this is where GeerGit's real logic lives; the
  Java/dex layer is a thin bridge). 2.9.4 and 2.9.5 are byte-identical.
- `dartstr-<ver>.txt` — identifier/toggle strings extracted from each libapp.so.
- `hooks-<ver>.txt` — the hooked-surface strings.
- `geergit-2.9.6-dex-java/` — jadx decompile of the dex bridge (if present; the real logic is in Dart, not here).

## `research/` — the analysis
- `GEERGIT-2.9.6-REGRESSION.md` / `GEERGIT-2.9.6-FINDINGS.md` — the full diagnosis.
- `geergit-2.9.5-ui-and-identifier-strings.txt` — GeerGit's UI labels + identifier toggle keys (the spec to
  mirror when building our app's UI: Identity/Settings/Location tabs, EDIT/RANDOMIZE, the toggles, etc.).

## `device-backups/`
- `geergit-2.9.6-data-backup.tgz` — a snapshot of GeerGit's on-device profile data (encrypted Hive boxes).

## THE key finding (how we caught the bug static analysis found but on-device testing missed)
The smoking gun is a single Dart symbol — **`_gsf@880098028`** (a lazily-initialized cached static for the
GSF id). Verified across all four versions:

```
grep -c "_gsf@880098028" in each libapp.so:
  2.9.4        → 0   (clean)
  2.9.5        → 0   (clean)
  2.9.6        → 2   (BROKEN — GSF cached once, never re-randomized → same fake GSF across signups → ban)
  2.9.7-beta   → 0   (fixed — reverted to the clean pattern)
```

**Why it banned accounts:** GeerGit 2.9.6 cached the fake GSF id as a static and stopped re-randomizing it on
subsequent wipes. Every account made on that version got the *same* fake GSF → DoorDash's fraud stack linked
them as one device → mass "coordinated / multiple accounts" bans.

**Why our on-device test missed it that night:** GeerGit's per-app profile is an *encrypted* Hive blob. The
wipe-test only proved "the blob changed" (hash) + "android_id reached Dasher" — it never checked the GSF field
specifically, and encryption hid the field-level staleness. The APK-diff (this symbol) is what actually caught
it. **Lesson for Specter's verify harness: read back EVERY identifier the target stored, not a sample.**

## To reproduce the version diff
```bash
for v in 2.9.4 2.9.5 2.9.6-installed 2.9.7-beta.3; do
  echo -n "$v: "; strings -n 4 decompiles/libapp-$v.so | grep -c "_gsf@880098028"
done
```
