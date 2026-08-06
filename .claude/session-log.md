
## 2026-08-06 overnight - activation, cogwheels, bulk-IP, R8, research
- **What changed:** Device-bound offline activation (Ed25519→P-256, proven on P4); settings cogwheel web+Android; bulk now accepts bare IPs; R8-obfuscated release (proven hooks via XC_MethodHook keep rule); fintech-signals exa research → ANTI-FINGERPRINT-STRATEGY; scope_probe.py base64 fix; CodeRabbit fixes (key perms, clamped remaining, baseline cache); IPQS key-scrub parity; distribution build uses release variant.
- **Why:** overnight §1/§2/§3 scope from handoff.
- **Verified:** pytest green (263), JVM green, activation proven on-device, R8 hooks proven (71 fields on DevInfo), probe all-spoofed after scope fix, web screenshots both themes, code-reviewer subagent CLEAN on all 5 risk areas.
- **Pending:** CI dead at account level (not code); CodeRabbit re-reviewing latest commits; merge decision awaiting CodeRabbit. 4a still on 0.27.0 (Lockito running, not rebooted per rule zero).

## 2026-08-06 ~10:15 - copy-chip clipboard verification
- **Verified:** the headline copy feature end-to-end via puppeteer clipboard read — bulk-checked a proxy
  with creds, expanded its detail, clicked host/port/user/pass/whole-line chips; every one wrote the correct
  value to navigator.clipboard (ALL COPIES OK: true, no page errors). The copy-guard test pins the
  selector↔markup match; this confirms the actual clipboard write, which the handoff asked to "confirm on
  screen". No code change — the feature works.
- Also this run: 4a Lockito GPS spoof found DOWN (flagged for re-arm); vault link-invariant confirmed
  test-covered; live Dasher round-trip deferred (needs user present).
