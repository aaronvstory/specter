# Specter — decisions log

One line per non-obvious call and WHY, so it isn't re-litigated. Newest first.

- **2026-07-25 · Intermittent-detection finding is a HYPOTHESIS, not proven** — GeerGit HAS an
  IMEI-increment mode + manual-uniqueness burden (plausible cause of intermittent bans), but we have NOT
  confirmed it flags the fleet. Documented as hypothesis with a confirm-path; don't present as fact.
- **2026-07-25 · Deprioritize app-list spoofing** — it's a STABLE signal; can't cause the *intermittent*
  flagging the user reports. Completeness item, not the fleet fix.
- **2026-07-18 · Left CPU cores + SUPPORTED_ABIS real** — cores are physically fixed (faking breaks thread
  pools); ABI is near-constant arm64 and already coherent. Spoofing = risk with ~no entropy gain.
- **2026-07-18 · Did NOT hook /proc/cpuinfo** — hooking file-I/O constructors is the riskiest surface, the
  Xposed stub lacks hookAllConstructors, and ro.board.platform already spoofs the SoC name most tools read.
- **2026-07-18 · Profile-file hook-artifact left unfixed** — a targeted anti-Specter check could read
  `/data/local/tmp/specter/<pkg>.json`, but no real fingerprinting stack does; fix reintroduces file-I/O
  hook risk. Documented, deferred.
- **2026-07-18 · SoC/HARDWARE/BOARD/bootloader key on Build.PRODUCT (codename), not the device slot** —
  devices.json stores the marketing name ("Pixel 4") in the device slot for Google/LG; the real codename
  (flame) is in product. Keying on device silently produced incoherent SoCs. (Code-reviewer catch.)
- **2026-07-18 · USA-only** — removed UK/all other countries; US carriers (MCC 310-316), NANP phones,
  US-market brands (samsung/google/motorola/lge) only. Per user: everything US-focused.
- **2026-07-18 · Removed the 6 placeholder Settings toggles** — they were non-functional; never ship fake
  UI. Anti-fingerprinting is always-on (not a toggle); location deferred to a real later PR.
- **2026-07-18 · Narrow hooks / DevInfo-only scope justified by fragility + system-scope side effects**,
  NOT a "pairip kills broad hooks" law (that claim was disproved by device evidence — a broken base-only
  Dasher install, not a hook, caused the crash).
