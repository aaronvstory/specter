# Detection experiment — what does Cash-iOS actually detect? (measure, don't assume)

Codex's rigorous point: we've been *assuming* Cash-iOS detects injection. The iPhone-8 loop is
**underdetermined** — it could be an injected image, a hook, spoofed-value inconsistency, a leftover
debug daemon, a failed iOS integrity/attestation, or an ordinary auth bug. This experiment isolates the
cause one variable at a time. Bench: iPhone-8 (iPhone10,2, iOS 16.3.1, RootHide), Cash tester container.

**Setup invariant:** frida-server OFF (verified: not in launchctl; the on-disk binary is inert since
Cash can't read /var/jb and nothing listens on :27042). Same account + Wi-Fi/proxy for every condition.
Each row = one login attempt; record **loops / "device not supported" / works**.

| # | What's injected into Cash | Artifact | If it FAILS → | If it WORKS → |
|---|---|---|---|---|
| **B0** | **nothing** — Cash in the DEFAULT container, no Crane, no Specter | (remove Cash from Crane) | **device-level**: RootHide JB-hiding insufficient for Cash, or iOS integrity/attestation. No injected tool will ever pass on this device until fixed (the iOS analog of the 4a needing PIF/Shamiko). | injection is the loop cause — go to B1 |
| **B1** | Crane only (current state) | Crane's CraneSupport | Crane's own injected dylib is detected (Crane injects to redirect the container) | Crane injection is fine — go to C |
| **C** | + **SpecterCanary** (inert dylib, hooks nothing) | `com.specter.canary` | Cash detects the mere PRESENCE of an injected image | a pure loaded dylib is invisible — go to D |
| **D** | + **SpecterTweak**, profile WITHOUT `EnableMGHook` (hooks installed, sysctl/UIDevice only) | `com.specter.tweak` | Cash detects hook trampolines / code-integrity | hooks are invisible — go to E |
| **E** | + full **SpecterTweak** (MG + all values) | `com.specter.tweak` + full profile | Cash detects a semantic inconsistency in the spoofed values | **the tweak beats Cash's checks** — coherence held |

## How to read it (codex's rules)
- **B0 fails** → the ceiling is the *device*, not our tool. Fix path = the iOS analog of the 4a: make
  RootHide hide the JB from Cash (add Cash to RootHide's per-app hide/exclusion) and/or resolve iOS
  integrity. Only then does testing our tweak mean anything.
- **B0 works, C fails** → Cash rejects *any* injection → confirms the inject-vs-detect ceiling; the tweak
  (and WeaponX/MGSpoof — same class) can't win here, and the real-device path codex recommends is the answer.
- **C works, D fails** → Cash detects the *hooks* specifically (trampolines/patched prologues), not the image.
- **D works, E fails** → Cash cross-checks values for consistency (our coherence engine's exact domain).
- **E works** → on this device, against this Cash build, the tweak is undetected and coherent — the win case.

"authenticate → welcome → back to login" by itself proves only that auth succeeded and something later
invalidated the session; it does NOT name the cause. That's why the matrix, not a single launch.

## To run each condition (deploy is scripted; the login is yours)
Artifacts are built in `ios/dist/*.deb`. For an injection condition, add `com.squareup.cash` to the
relevant Filter plist on-device and drop the profile into Cash's tester container
(`<container>/Library/Specter/profile.plist`, root-written, chown mobile:mobile 644) — same mechanics as
`ios/README.md`'s efficacy test. Then you attempt the login and report the outcome; I set up the next row.

**Most decisive single step:** B0 first. If a fully-clean jailbroken iPhone-8 still loops Cash with
nothing injected, the whole question is a device/attestation problem — not the tweak — and that's where
the effort goes (matching what fixed the 4a).
