# Specter-iOS — working directory

Start of the iOS port. See `docs/IOS-PORT-FEASIBILITY.md` for the full plan and
`handoffs/2026-08-03_ios-devices-network-diagnosis.md` for the device bench + network fixes.

## `trace/` — live device-read tracer (the iOS analog of the Android probe)

Instruments a target app at runtime and records every device-identity read (sysctl, MobileGestalt,
IORegistry, IDFV/IDFA, keychain, attestation) to a JSON artifact we reuse instead of re-coordinating
each session. Captures on a **tester/throwaway container** — never a live account.

### One-time setup (per phone)
frida-server (rootless) from the RootHide repo:
```
ssh -p <port> root@127.0.0.1 'apt-get install -y --allow-unauthenticated re.frida.server'
```
It auto-starts as a launchd daemon. PC client MUST match the server major version
(device ships 16.1.4 → `uv pip install "frida==16.1.4"`; a 17.x client will NOT connect).

### Run a capture
```
# forward the frida port from the device over USB
iproxy 27042 27042 -u <UDID> &
# spawn the app fresh (captures launch-time reads) and trace for N seconds
python trace/run_trace.py com.squareup.cash out.json 40
# or attach to an already-running instance (misses launch reads; may fail on arm64e)
python trace/run_trace.py com.squareup.cash out.json 40 --attach
```
Output JSON has `distinct` (deduped kind/key/value + which modules/SDKs requested each) and `raw`.

### Notes / gotchas
- **Spawn vs attach:** attach-to-running fails on arm64e with "probing dyld" for a process launched
  before frida; **spawn** works and is what we want (earliest reads). Spawn may bypass Crane's
  container redirect (runs in default container) — irrelevant for device reads, which are
  container-independent.
- The app idles at the pre-login splash after ~5s (launch reads fire once, then it waits for input).
  Deeper checks (Persona KYC, MiSnap liveness, App Attest calls) only fire as the flow is driven.
- frida-agent is detectable; only trace throwaway containers.

## `captures/` — saved read-traces
- `cashapp_se2_prelogin_2026-08-03.json` — Cash App launch/pre-login on SE2 (iPhone12,8, iOS 16.3.1).
  See `docs/ios/CASHAPP-READ-TRACE.md` for the analysis.
