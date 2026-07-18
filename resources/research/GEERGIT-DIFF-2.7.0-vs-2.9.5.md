# GeerGit 2.7.0 vs 2.9.5 — focused diff (the two builds we run, not the beta)

Static string-diff of arm64 `libapp.so` (Dart AOT). Same pipeline as the 3-way doc.
**Bottom line: 2.7.0 is strictly OLDER. 2.9.5 removed nothing and added a whole feature layer.**
(2.7.0 → 2.9.5 = +68 feature strings, −0 real strings; the "only in 2.7.0" hits are just
truncation-byte variants of the same strings: `Importing backup...j` vs `Importing backup...`.)

## What 2.9.5 ADDED over 2.7.0

### 1. Backup & Restore (the big one — absent in 2.7.0)
Whole subsystem. Save a target app's data, restore it later, rename/delete backup files, import a raw
backup blob, per-target "Disable Backup & Restore" and "exclusive/skip" modes.
```
Restore Backup / Loading backups... / No backups found / Delete selected backups
Save app data and restore later if needed. Backup data is stored locally only.
Disable Backup & Restore  ·  Skip any backup or restore operations for this target.
Backup skipped: exclusive mode enabled  ·  BACKUP_RESTORE_DISABLED
getAllBackupFiles / renameBackupFile / restore_backup_page / is_disable_backup_restore
group_disable_backup_restore_toggle
```

### 2. Location Spoofing (Alpha/paid — absent in 2.7.0)
```
Location Spoofing / Start Spoofing / Pause Spoofing / Location spoofing (on/off)
"You are not an alpha member. Please get the alpha membership to use location spoofing."
locationSpoofSwitch / location_spoofing_toggle / _toggled / _updated
/api/v1/places/search   <-- NEW endpoint (place lookup for the fake GPS)
```

### 3. Group / multi-app management (absent in 2.7.0)
2.7.0 predates groups entirely. 2.9.5 adds:
```
is_active · is_group_member · is_part_of_group · is_preserve_permissions · is_undetectable
is_clear_cache · is_clear_data_only · is_force_stop_only
"Force stops the app only; no backup, restore, or data clear is performed."
```
→ per-app apply modes (force-stop-only / clear-data-only / clear-cache) + group membership +
preserve-permissions + an "undetectable" flag. None of this exists in 2.7.0.

### 4. Offline sync queue (absent in 2.7.0)
2.9.5 queues profile create/update/delete and syncs when back online:
```
Profile queued for sync. It will be synced when online.
Profile deletion queued. / Profile update queued for sync.
Error queuing profile creation/deletion/update:
create-profiles · existingProfileIds · profile_data
```

### 5. Misc 2.9.5 additions
- Profile folders ("Move selected profiles to folder", "Creating folder and moving profile...")
- Locale/date formatting init (`initializeDateFormatting`, `SetApplicationLocale`) — groundwork the
  beta later builds full i18n on.
- `init:_mNc@200490068` — a lazily-cached MNC static (note: this is the *same class of pattern* as the
  2.9.6 `_gsf@` regression, but on MNC not GSF, and 2.9.5 was never implicated in the ban).

## Identifier-spoof surface: IDENTICAL
Every device-ID spoof toggle in 2.9.5 is already in 2.7.0 and vice-versa:
`android_id, gsf_id, advertising_id, imei, serial, sim_operator, sim_subscriber, sim_card_serial,
mobile_number, wifi mac/bssid/ssid, bluetooth_mac, media_drm, gmail, device_spoof,
anti_fingerprinting, legit_device, hide_mock_location`.
**2.9.5 added zero new device identifiers.** Everything it added is lifecycle/product
(backup, location, groups, sync), not new spoof coverage.

## GSF-clean
`_gsf@` cached-static count: 2.7.0 = 0, 2.9.5 = 0. Both clean (the ban bug was 2.9.6-only).

## So what is 2.7.0?
An **early build** with the core spoof engine but **none** of the surrounding product layer:
no backup/restore, no location spoofing, no groups, no offline sync, no folders. If the dev handed
you 2.7.0 as "newest," it's the opposite — it's the leanest, oldest of everything we hold. The only
reason to run it is if you specifically want a **minimal GeerGit with just identifier spoofing and no
group/backup/sync machinery** — but you lose backup/restore and location spoofing to get there.

## Implication for Specter
- Our identifier coverage already matches GeerGit's stable spoof set (same in 2.7.0 and 2.9.5).
- 2.9.5's real additions (backup/restore, location spoofing, groups, offline sync) are **product
  features**, not identity-linkage improvements. Location spoofing is the only one with anti-detection
  value; the rest are convenience/monetization. Candidate future work, none urgent.

Artifacts: `resources/decompiles/dartstr-2.7.0.txt`. Raw `.so` gitignored.
