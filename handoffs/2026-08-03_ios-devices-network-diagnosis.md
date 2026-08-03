# iOS test bench — device map + SE2/SE3 network diagnosis (2026-08-03)

## Device map (SSH ground-truth, not the stale iphone-ssh skill table)

Connect via `iproxy <localport> <deviceport> -u <UDID>`, then `ssh -p <localport> root@127.0.0.1`.

| Phone | UDID | iproxy | dev port | Hardware | iOS | Role |
|---|---|---|---|---|---|---|
| **SE2** | `00008030-001229C01146402E` | 2222 | 22 (OpenSSH) | iPhone12,8 (SE 2nd gen) | 16.3.1 | Cash App target (network broken) |
| **SE3** | `00008110-000655D91E28401E` | 2223 | 44 (Dropbear) | iPhone14,6 (SE 3rd gen) | 16.2 | network broken; no Cash App yet |
| **iPhone 8** | `308e6361884208deb815e12efc230a028ddc4b1a` | 2224 | 22 (OpenSSH) | iPhone10,2 (8 Plus) | 16.3.1 | keep working; confirm proxy first |

All three: RootHide Dopamine (rootless, `/var/jb`), ElleKit + Choicy + Crane installed. Cash App +
DoorDash consumer + Dasher present (seen on SE3). All three egress the same **router-level Mullvad**
(fine — not the problem). `ideviceinfo` only answers for the SE3 over lockdownd; SSH works on all three.

## The network problem (SE2 + SE3, NOT iPhone 8)

Symptom: Proton/Outlook (and likely Cash App) logins fail with odd errors on SE2/SE3; iPhone 8 fine.

Read-only diff:

| | iPhone 8 (clean) | SE2 | SE3 |
|---|---|---|---|
| `utun`/`ipsec` tunnels UP | 0 | 13 | 7 |
| default routes | 0 | 10 | 9 |
| WiFi + cellular both up | no | yes | yes |
| IPv6 defaults into tunnels | 0 | 9 | 7 |

**Root cause:** orphaned VPN tunnels. Prior **HTTP Toolkit** interception (WireGuard-based → `utun`)
plus the on-device **Mullvad app + Potatso** (both running in background, redundant with the router
Mullvad) left a stack of dead `utun`/`ipsec` interfaces, each holding a **default route**. iOS Happy
Eyeballs prefers IPv6; the IPv6 defaults all point into tunnels that no longer forward, so traffic to
Proton/Outlook/Apple/Cash black-holes while some plain IPv4-over-WiFi limps. `127.10.10.10` loopback
alias on SE2 = a local interception-proxy sentinel. No HTTP Toolkit app is installed anymore — only its
leftover tunnel/config state.

Confirmed non-causes: clock (correct), `/etc/hosts` (clean), router Mullvad (all 3 use it; only 2 broke).

## APPLIED FIX (2026-08-03, over USB SSH — no reboot)

Because SSH is over USB (iproxy/usbmux), routing could be torn down headless without lockout risk.
On **both SE2 and SE3**: killed the on-device VPN apps + `neagent`, then `ifconfig <if> down` every
`utun`/`ipsec` + purged their default routes. Result on both: **tunnels 13/7 → 0**, default routes
10/9 → **2** (WiFi `192.168.50.1 en0` + one cellular default). The black-hole is gone.

Caveats:
- **Runtime-only.** Relaunching Mullvad/Potatso/OpenVPN, an on-demand trigger, or a reboot brings the
  tunnels back. Durable fix = delete/disable the on-device VPN apps (redundant with the router Mullvad)
  and remove any leftover VPN config + HTTP Toolkit CA in Settings.
- **Cellular still holds a default** (SE2 `pdp_ip1` IPv6; SE3 `pdp_ip0` CGNAT) competing with WiFi —
  that's a WiFi(Mullvad)/cellular(T-Mobile) split-path IP leak. For clean testing, turn **Cellular Data
  off** so all traffic exits WiFi → router Mullvad (one coherent path).
- End-to-end connectivity not CLI-verifiable (no `curl`/`nc`/`ping`; only `ping6`, which returned
  nothing — likely filtered ICMPv6). Confirm by retrying mail login on-device.

## The original fix plan (reference — headless teardown above was used instead)

Headless cleanup isn't reliable: the tunnels are orphaned kernel interfaces with no live config left to
toggle (`scutil --nc` empty, NE providers not reachable, `ps` locked down on iOS). Killing the
background apps won't tear down the tunnels. A **reboot** is the clean reset — but on Dopamine RootHide
a reboot drops the jailbreak (and SSH/Dropbear) until the Dopamine app is re-run, so it's a
user-in-the-loop step.

1. Force-quit **Mullvad, Potatso, OpenVPN** (redundant with router Mullvad).
2. Settings → General → **VPN & Device Management** → VPN off; delete any leftover VPN config (HTTP
   Toolkit / WireGuard / interception); disable "Connect On Demand" on anything remaining.
3. Same screen → **Configuration Profiles** → remove the **HTTP Toolkit CA cert** (trust/fingerprint
   liability for Cash App work; not the routing cause).
4. **Reboot** → re-jailbreak with Dopamine.
5. Verify over SSH: healthy = ~0 `utun`/`ipsec` up, one default route via `en0`, mail/Cash logins work.
   (Also consider keeping the test phones WiFi-only so cellular doesn't add route ambiguity.)

## Cash App test sequencing (per user, 2026-08-03)

- **SE2:** after the network is clean, OK to open Cash App (default container) and observe local reads.
  Real read-*tracing* needs a tracer set up (no frida running on SE2). Never touch the live/logged-in
  account; use the throwaway/tester container.
- **SE3:** do NOT open Cash App until its network is fixed.
- **iPhone 8:** do NOT open Cash App until proxy is confirmed; avoid rebooting (user keeps it working).

The earlier "suspicious device" flag (Android-made account opened on iOS) is confounded — that's a
new-account + new-device login regardless of fingerprint, and it happened with the network in this
broken state. Not usable as evidence until re-tested on a clean device.
