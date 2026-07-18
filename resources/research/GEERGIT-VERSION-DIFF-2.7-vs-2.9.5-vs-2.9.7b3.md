# GeerGit version diff — 2.7.0 vs 2.9.5 vs 2.9.7-beta.3

Static string-diff of the arm64 `libapp.so` (Dart AOT binary — GeerGit's real logic) from each APK.
Method: `strings -n 4 libapp.so | sort -u`, then filtered to the feature/identifier/endpoint surface and
`comm`-diffed. Same pipeline that caught the 2.9.6 `_gsf@` regression.

## TL;DR — what the dev is actually doing
- **2.7.0 is NOT the newest.** By capability it's the *oldest* of the three (fewest features, fewest
  toggles). versionCode `20700 < 20907` (2.9.7-beta.3). If the dev called it "newest", the number and the
  binary both disagree — it's a **downgrade** from what was on the phone.
- **The dev's real 2.9.5 → 2.9.7-beta.3 work is two things, neither of them "better emails":**
  1. **Profile Transfer / sharing** — a paid ("Alpha membership") feature: transfer profiles between users
     via a server backend (`/api/v1/profiles/share`, `/shared`, `/shared/accept`, `/shared/sent`).
  2. **Localization + 3 new spoof toggles** — app language switching (incl. Russian) and three new device
     spoofs: **Hide Airplane Mode**, **Randomize Battery Level**, **Spoof Battery Cycle Count**.
- **"More normal emails" is server-side, confirmed again.** Email-generation strings
  (`randomGmail`, `randomize_gmail`, `gmail_switch`) are **byte-identical across all three versions**. The
  only new email strings in the beta (`recipientEmail`, `senderEmail`) belong to profile-transfer, not gen.
  Email realism comes from GeerGit's `/api/v1/` backend, which we can't mirror from the binary.
- **GSF regression is absent in all three** — `_gsf@` cached-static symbol count = 0 for 2.7.0 / 2.9.5 /
  2.9.7b3. (The ban bug was 2.9.6-only.) All three are GSF-clean.

## Capability surface (identifier/spoof `*_switch` toggles)

| Spoof toggle | 2.7.0 | 2.9.5 | 2.9.7b3 |
|---|:---:|:---:|:---:|
| android_id, gsf_id, advertising_id, imei, serial | ✅ | ✅ | ✅ |
| sim_operator, sim_subscriber, sim_card_serial, mobile_number | ✅ | ✅ | ✅ |
| wifi mac/bssid/ssid, bluetooth_mac, media_drm | ✅ | ✅ | ✅ |
| gmail, device_spoof, anti_fingerprinting, legit_device | ✅ | ✅ | ✅ |
| hide_mock_location | ✅ | ✅ | ✅ |
| **hide_airplane_mode** | ❌ | ❌ | ✅ **NEW** |
| **randomize_battery_level** | ❌ | ❌ | ✅ **NEW** |
| **spoof_battery_cycle_count** | ❌ | ❌ | ✅ **NEW** |
| **language_switch** (app i18n) | ❌ | ❌ | ✅ **NEW** |

The core identifier-spoofing surface (what actually makes a device look distinct to DoorDash) is
**unchanged** from 2.7.0 → 2.9.7b3. Every version spoofs the same device IDs.

## Group / lifecycle machinery: 2.7.0 is behind

2.9.5 added a large group/lifecycle layer that **2.7.0 lacks** (`is_active`, `is_clear_cache`,
`is_clear_data_only`, `is_disable_backup_restore`, `is_force_stop_only`, `is_group_member`,
`is_part_of_group`, `is_preserve_permissions`, `is_undetectable`). 2.7.0 has none of these — it predates
the group-management + per-app apply-mode work. This is the clearest proof 2.7.0 is an **earlier** build.

## NEW in 2.9.7-beta.3 vs 2.9.5 (raw diff)
```
Profile Transfer (Alpha/paid, server-backed):
  /api/v1/profiles/share, /shared, /shared/accept, /shared/sent
  Transfer Profiles / Transferred Profiles / Profile Transfers
  "You need Alpha membership to transfer profiles"
  recipientEmail, senderEmail, pendingTransferToEmail, transferredProfileIds
  accept_transferred_profiles, transfer_profiles

Localization / i18n:
  Language / Language Suggestions / "Russian language is available"
  language_switch, language_channel, locale_preference, open_language_settings
  firstLaunchLanguageCheckPending, one-time language switch suggestions

New spoof toggles:
  hide_airplane_mode_switch  / is_hide_airplane_mode
  is_randomize_battery_level
  is_spoof_battery_cycle_count

Validation hardening:
  "IMEI 1/2 must be 15 digits", "IMEI 1/2 cannot be empty when enabled"
```

## Implications for Specter
- **Ignore 2.7.0 as a spec source** — it's an older build; 2.9.5 and the 2.9.7b3 beta are ahead of it.
- **Nothing here changes our identifier coverage** — GeerGit's spoof surface is stable; we already cover it.
- **The beta's new spoofs (airplane-mode / battery level / battery cycle count)** are candidate future work
  for Specter (already logged as out-of-scope in the analyst handoff). They're cosmetic anti-fingerprint
  additions, not identity-linkage fixes.
- **Profile Transfer + i18n are product/monetization features** (server backend, Alpha membership) — not
  applicable to Specter (no backend, single-user).
- **"Normal emails" stays a Specter-independent feature** — it's GeerGit's server, not client logic; our
  realistic-email generator (v0.3.0) is the right call, built independently.

Artifacts: `resources/decompiles/dartstr-2.7.0.txt` (new). Raw `.so` files stay gitignored.
