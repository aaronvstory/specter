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

- [ ] **1.1 `factoryReset` leak — the reason FPJS re-links us.** `todo` · **highest value**
  PROVEN 2026-07-25: FPJS Pro reported `factoryReset.timestamp = 1773120233`, which is the mtime of
  `/data/misc/profiles`, `/data/bootchart`, `/data/misc/wifi`, `/data/misc/bluetooth`. The first two are
  readable by an unprivileged app. This is a stable per-device value we never spoof, and it survived
  `pm clear`, app-data deletion, and deleting the UID-scoped Keystore master key.
  Approach: hook `java.io.File.lastModified()` (and `stat`-family reachable equivalents) and return a
  per-identity coherent timestamp for those paths ONLY. Add `factory_reset_epoch` to the profile.
  Coherence rules: must be *older* than the account it's used for, never in the future, plausibly spaced
  from the build's `build_security_patch`, and STABLE per identity (a reset time that changes per launch
  is its own tell). Danger: `lastModified` is a hot, generic path — a too-broad match breaks target apps,
  so match an explicit path set and pass everything else through untouched.
  Done when: the FPJS demo yields two different visitorIds across two identities.

- [ ] **1.2 Native prop-read blind spot.** `todo`
  PROVEN 2026-07-25: libc `__system_property_get` returns the REAL device for 10 of 19 props while the
  Java path returns spoofed. An NDK fingerprinter reads through every hook we have. Fix needs a root
  `resetprop` layer setting the same values Specter already generates, so Java and native agree.
  Blast radius is device-wide (it also changes what GeerGit's fleet apps see) and it destroys the real
  values, so: needs a revert path, must derive from the ONE generated profile, and must be re-verified
  with the dual-read probe. Sequence AFTER 1.1 — no evidence yet that DoorDash reads props natively,
  whereas `factoryReset` is a confirmed live signal.

- [ ] **1.3 Re-audit for the NEXT leak after 1.1 lands.** `todo`
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
