# Specter — decisions log

One line per non-obvious call and WHY, so it isn't re-litigated. Newest first.

- **2026-07-25 · Widevine coherence: return L3 (not a faked L1) alongside the spoofed deviceUniqueId** —
  probing PROVED the incoherence (spoofed id @ real L1 on the Pixel 4). Chose L3 because L3 = *software*
  Widevine, where a changing/derived device id is normal and expected; faking L1 while emitting a changing id
  would keep the contradiction (real L1 = fixed hardware id). Implemented as a Java getter hook on
  `getPropertyString("securityLevel")` — NO root, unlike byedentity's liboemcrypto bind-mount. Re-verified
  coherent on-device. The `media_drm_security_level` profile value is a CONSTANT ("L3") so it consumes no RNG
  → Java↔Python byte-parity is preserved automatically (no generator, no reorder).
- **2026-07-25 · byedentity adoption: probed the Widevine coherence hole FIRST, then fixed it (not blind)** —
  measured the mismatch on-device before committing the fix; HYPOTHESIS → PROVEN → fixed → proven-fixed, all
  on real hardware. The heavier root bind-mount (candidate #4) is unnecessary for this signal.
- **2026-07-25 · Do NOT adopt byedentity's server/anti-tamper stack** — its HMAC attestation, remote
  kill-switch (403→wipe local auth), public-IP telemetry, and native Frida gate serve byedentity's OWN
  licensing/control, not the user's anti-detection goal. Specter is deliberately stateless with no server
  leash. Adopt only the identity-coherence ideas (mask-preserving generators, DRM coherence, StatFs).
- **2026-07-25 · Port the mask-preserving-generator IDEA, not byedentity's native code** — its serial
  generators (buildMask/generateFromMask/generateLikePreservingBlocks) live in un-disassembled native
  (JNI names only = HYPOTHESIS on internals). Reimplement the concept (per-model format masks + prefixes)
  in generators.py with Java byte-parity + US-device templates; don't guess at their arithmetic.

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
