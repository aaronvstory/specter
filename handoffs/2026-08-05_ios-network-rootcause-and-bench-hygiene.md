# iOS test-bench: network root cause + device hygiene (2026-08-05)

Supersedes the network-cause conclusion in `2026-08-03_ios-devices-network-diagnosis.md`.
Device ops only — **no code changes.** Bench = SE2 (iPhone12,8), SE3 (iPhone14,6), iPhone 8 (iPhone10,2),
all RootHide Dopamine, SSH over USB (`ssh se2|se3|iphone8`).

## Symptom
In-app / Safari logins (Yahoo/Outlook/AT&T; mail.tm "rate limited") stalled on SE2 + SE3 — tap **Next**,
brief load flash, nothing. iPhone 8 (same iOS 16.3.1) worked. Prior session blamed "orphaned VPN tunnels
black-holing IPv6" and did a headless teardown that fixed nothing.

## Real root cause (PROVEN)
**NOT network/VPN/routing.** Installed `curl` on all three → SE2 and the working iPhone 8 egress the
**identical public IP** (`23.159.216.252`), same DNS, no system proxy/PAC, legitimate DigiCert TLS chain
(no MITM), and `curl` to the failing sites works identically on both. So the network path is byte-identical
between the "broken" and "working" phone. The `utun`/`ipsec` interfaces are Wi-Fi-Calling IMS + idle
tunnels, carrying no real traffic — the black-hole theory was a red herring.

The cause was a WebKit-injected tweak **`xyz.cypwn.webkitcompat` (WebKitCompat, author "Local", sideloaded
via RootHide patcher)**, present on **SE2 + SE3 only, absent on iPhone 8**. Its filter injected into
`com.apple.WebKit.WebContent` **and** `com.apple.WebKit.Networking` (+ MobileSafari/SafariViewService/every
WKWebView) and rewrote JavaScript on every request ("transpiling class static blocks"), mangling modern
login handlers so the button did nothing. iPhone 8 on the same iOS proves the "16.3 compat fix" is
unnecessary and was pure harm.

**Fix:** `dpkg -r xyz.cypwn.webkitcompat` on SE2 + SE3, cleared the `pkgmirror` copy (RootHide could else
resurrect it on a patch-reapply), kept the `.deb` in the RootHide patcher Inbox for reinstall. User
confirmed Safari logins recovered. Verify (WebKit-layer, not interface count):
`ssh <phone> 'cd /var/jb/Library/MobileSubstrate/DynamicLibraries; for p in *.plist; do grep -liE
"WebContent|WKWebView|mobilesafari" "$p"; done'` → must be empty.

## Bench hygiene pass (all three now verified clean, post reboot + re-JB)
- **frida-server** was auto-running on **SE2** (LaunchDaemon `re.frida.server`, `KeepAlive`, listening
  `127.0.0.1:27042` — the port fintech apps probe). `kill` alone respawns; disabled via `launchctl bootout`
  + renamed plist `re.frida.server.plist` → `.disabled` (no RunAtLoad on reboot) on SE2 **and** iPhone 8.
  SE3 has no frida. NOTE: a RootHide blacklist hides injection/JB files from an app but does **not** hide an
  open loopback TCP port — a blacklisted app can still detect a live 27042, so frida must be OFF, not just
  blacklisted. Disabling (not removing the pkg) suffices — a sandboxed App Store app can't scan the FS for
  the dormant binary.
- **"HTTP Toolkit CA"** — a trusted MITM root cert config profile — was installed on **all three** (iPhone 8
  cert id `4de7…`, SE2+SE3 `e92c…`; leftover from prior interception). Removed by user via Settings →
  General → VPN & Device Management → Remove Profile.
- **"appdb device link"** profile (appdb.to sideload service: `com.apple.vpn.managed` VPN + web-clip) on
  SE2 + SE3 — removed too.
- `127.10.10.10` lo0 alias = **vcamera loopback** (`com.vcam.loopback.plist`) — LEGIT on all phones, left in place.
- **SE2 also has three dormant VPN/proxy apps installed** — Potatso, OpenVPN, MullvadVPN (redundant with
  the router-level Mullvad; not routing). Flagged for optional deletion; not removed.

Verified after re-JB: `profile-*.stub` count = 0, 27042 closed, 0 WebKit injectors, no proxy, webkitcompat
absent; **Cash (`com.squareup.cash`) blacklisted = True on all three** (SE2 18 apps hidden, iPhone 8 16, SE3 9).

## Note for on-device recon
On-device `strings`/`plutil` choke on DER-signed config-profile stubs — `base64` the `.stub`, pull to PC,
`plistlib.loads` (scrape the embedded plist), flag `security.pem`/`security.root`/`vpn.managed`. Full detail
also mirrored to session memory `ios-test-bench-and-network-break`.
