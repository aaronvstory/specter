# Specter — the goal and the work queue

**This file is the standing instruction set. Read it at session start. Work the queue top-down.
Don't stop to ask "what next?" — take the top unblocked item, ship it, tick it off, take the next.**

---

## The end state

Specter is a product someone would pay for:

1. **It is not detected.** It beats commercial fingerprinters — including FingerprintJS Pro — not just
   GeerGit's bar. Every identity looks like a genuinely different, genuinely ordinary US phone.
2. **It is coherent.** Never an incoherent combo. An implausible device is itself a fingerprint, so
   "spoofed" is not the bar — "plausible" is.
3. **It is a beautiful, trustworthy UX.** Fast, clear, no dead controls, no jargon leaking to the user.
4. **Later: it is a subscription product.** Deliberately deferred — see Phase 4. A product that gets
   detected cannot be sold, so detection and UX come first.

## How to work

- **Full TDD.** A test that fails before the fix and passes after. Python: `.venv/Scripts/python.exe -m
  pytest -q`. JVM: `cd xposed-module && bash run-jvm-tests.sh`. Both green before every commit.
- **Byte-parity is non-negotiable.** Any change touching a seeded draw must be proven with the
  Java-vs-Python dumper, not assumed. Constants consume no RNG and are parity-safe by construction.
- **Prove it on-device.** `scripts/verify_on_device.py` for the hook table; the dual-read probe for the
  native path; the FPJS demo for the end-to-end question. A claim without on-device evidence is a
  hypothesis and must be labelled one.
- **PRs: no merge gate.** Make reasonable, scoped PRs, run the bot loop + a code-reviewer subagent, fix
  real findings, and merge them yourself. Don't stop and wait for permission on ordinary work.
  Still surface: a genuine decision, a real finding, or a true blocker.
- **One concern per PR.** A byte-parity change never rides along with an unrelated fix.
- Keep `CHANGELOG.md` / `IDEAS.md` / `DECISIONS.md` / `CLAUDE.md` current in the SAME commit.

## Definition of done for "beats FPJS Pro"

The FPJS Pro demo, run twice with two different applied identities and a `pm clear` between, returns
**two different `visitorId`s**, and ideally `visitorFound: false` on a fresh one. Record the whole
`identification` block (`eventId` proves the call was fresh, `firstSeenAt` proves link age) — not just
the id string. Current status: **FAILS** — same visitorId across three rotations (2026-07-25).

---

## Queue

Status: `todo` · `in-progress` · `done` · `blocked (why)` · `dropped (why)`

### Phase 1 — Beat the fingerprinters (the thing that makes it a product)

- [x] **1.1 `factoryReset` leak — Java layer DONE + MERGED (PR #8); did NOT win alone.** `done`
  Shipped `factory_reset_epoch` (coherent: derived from the build's own security patch; byte-parity proven
  over 200 seeds) + hooks on `File.lastModified` AND `android.system.Os.stat/lstat`. Verified on-device:
  all 6 reset-marker dirs return the spoofed time via both Java paths.
  **Result: FPJS Pro STILL reports the real `1773120233` and the same `visitorId`.** By elimination it
  reads the reset time via native `stat()`. Two confirmed signals (properties AND filesystem metadata)
  now leak exclusively through the native path, so **1.2 is a prerequisite, not an option.**
  Kept anyway: the Java hooks close the paths other SDKs do use, and they cost nothing.

- [ ] **1.2 The native read path — now the critical path, not an optional extra.** `todo` · **highest value**
  PROVEN twice over: (a) libc `__system_property_get` returns the REAL device for 10 of 19 props while the
  Java path returns spoofed; (b) FPJS Pro reads the factory-reset mtime natively, straight through two
  verified-working Java hooks. **Every signal we have failed to hide leaks exclusively via native code.**
  Two candidate mechanisms — evaluate before building, the choice is not obvious:
    - **Zygisk / native in-process hook** of `__system_property_get` + `stat`/`fstatat`/`statx`. Per-app,
      reversible, no collateral damage to GeerGit's fleet apps, and it can serve per-identity values from
      the same profile. Strictly better on blast radius than the byedentity-style approach.
    - **Root `resetprop` + `touch`** (what byedentity does). Simpler to write, but device-wide,
      irreversible for the real mtimes, and it changes what the fleet apps see. Needs a revert path.
  Either way: values come from the ONE generated profile (never a second source of truth), and it is
  re-verified with the dual-read probe + the FPJS test, not assumed.
  Done when: the probe shows native == Java for every dual-read field, AND the FPJS demo yields two
  different `visitorId`s across two identities.

- [ ] **1.3 Re-audit for the NEXT leak after 1.2 lands.** `todo`
  When the visitorId rotates, don't declare victory — re-run the FPJS test on a third and fourth identity
  and read the whole smartSignals block for the next anchor. The pattern this session: each fix reveals
  the next signal. Also still untested: the Widevine/OEMCrypto **native** path (different API, not covered
  by the property probe).

### Phase 2 — Plausibility (a coherent identity nobody has to squint at)

- [ ] **2.1 Device-pool quality.** `todo` · byte-parity change, own PR
  The US pool is 173 entries of which **95 are pre-Android-9** and **25 are tablets/TV boxes** (Nexus
  7/9/10, Galaxy Tab, Nexus Player, Shield). So ~half of generated identities claim a tablet or a
  2015-2018 OS — and a WiFi-only tablet carrying a SIM + IMEI + NANP number is flatly incoherent.
  Filter to phones, Android >= 10, US-market. Keep Java `Generators`/`Profile` in lockstep and PROVE
  parity with the dumper.

- [ ] **2.2 Coherence sweep across every generated field.** `todo`
  Systematically re-check the whole profile for pairs that can disagree (as RAM/storage and
  Widevine/securityLevel did). Candidates: security-patch date vs OS release, carrier vs phone-number
  area code, `build_host`/`build_incremental` shape vs brand, ICCID prefix vs carrier.

### Phase 3 — UX (the part that makes it feel like a product)

- [ ] **3.1 Audit the app UI as it stands.** `todo`
  Walk the real app on-device, screenshot every screen, and write an honest list: what's confusing, slow,
  dead, or leaks internals to the user. No fake/cosmetic controls — build it or mark it non-functional.

- [ ] **3.2 Fix what 3.1 finds.** `todo` (depends on 3.1)

### Phase 4 — Product / monetization

- [ ] **4.1 Deferred by decision (2026-07-25).** `blocked (until Phase 1 is done)`
  Revisit only once it demonstrably beats FPJS Pro. Note: byedentity's server-validation / HMAC /
  kill-switch / anti-tamper design was **explicitly rejected** (see DECISIONS.md) — it serves ITS
  licensing model, not our goal, and adds a phone-home that is itself a signal. Any licensing we build
  must not introduce a new network fingerprint.

---

## Log

- **2026-07-25** — Queue created. Phase 1 chosen as the entry point because Test B proved a live,
  reproducible detection failure with an identified root cause; everything else is quality work that
  doesn't matter if the product is detected.
- **2026-07-25** — 1.1 shipped (Java layer, both read paths, byte-parity proven) but did NOT beat FPJS.
  Re-ordered: 1.2 (native layer) promoted to the critical path, because two independent confirmed signals
  now leak exclusively through native code. Also caught a real pre-existing bug on the way: Java's
  `Profile.KEYS` was missing `media_drm_security_level`, so last session's Widevine coherence fix never
  applied on the Java path. A parity test now guards the key list against drift.
