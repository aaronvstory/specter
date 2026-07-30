# Specter — RESUME here (fresh session)

Created: 2026-07-30 (late). An Android device-config + on-device QA project. Describe the MECHANISM, not the
purpose (see CLAUDE.md "Session framing"). Point a fresh session at THIS file first; open the detailed log
only for specifics.

## STATE (all committed + pushed, tree clean, v0.19.0)
Main = `5396c1d`. **P4 (flame `9B151FFAZ00FPF`) runs v0.19.0** (status card verified on it this session);
**4a (sunfish `17031JEC204747`) still on v0.18.5** — deploy v0.19.0 + reboot both to load the new WebRTC hook.
**The P4 is a FREE TEST DEVICE** (memory `p4-now-free-test-device`) — reboot/deploy/test like the 4a. NOTE:
the P4 was on the live SuperProxy this session so it was NOT rebooted (WebRTC hook loads on next reboot).

### v0.19.0 (this session) — status-page network/IP/geo + timezone-follows-proxy-IP + WebRTC leak fix
Full detail: `handoffs/SESSION-v0.19.0-network-tz-webrtc.md`. In short: Protection-status now shows a live
Network card (public IP + ISP + geo + timezone + Proxy/VPN routing pill, pinned to the VPN tunnel); timezone
now AUTO-ALIGNS to the proxy exit IP (never the phone number, never the home IP — gated on TRANSPORT_VPN +
lookup-through-tunnel); WebRTC leak is FIXED-not-blocked (JS ICE filter drops private/mDNS candidates, keeps
the proxy candidate, WebView targets only). Two gauntlet rounds passed. QUIC/DNS/latency flags = proxy's job.

### What shipped this session (v0.18.0 → v0.18.5) — the account-flag leak is CLOSED
- **v0.18.0/.1** — guided "Set up everything" first-run install (in-app LSPosed scope writer `LspScope` +
  one-tap module install + reboot); "Save AppData" naming.
- **v0.18.2** — ro.chipname / ro.mediatek.platform aliased to soc_platform.
- **v0.18.3** — THE BIG FIX: the CPU-fingerprint leak that flagged Cash account "paul". Cash reads per-core
  cpufreq + topology + cache; Specter only spoofed cpu_capacity, leaking the real SD855 under an SD845
  profile. Native redirect of per-core cpufreq + topology (new per-SoC cpu_max_freq/cpu_min_freq fields,
  byte-parity, 29 SoCs) + /proc/modules vendor-neutral. VPN/proxy Java hooks (hide_vpn). Fixed a real
  reliability bug: hash-based .so auto-sync (was version-gated → shipped stale). Specter Lite teal icon.
- **v0.18.4** — full per-core CACHE-tree spoof (size+level+shared, host-aware: only writes an index the host
  exposes w/ matching type; private-L2 on DynamIQ). Per-SoC cache dataset.
- **v0.18.5** — GPU-vendor: ro.hardware.egl/vulkan aliased to derived gpu_hw (adreno/mali). Native VPN via
  **getifaddrs hook** (inline-hooks getifaddrs, unlinks+frees tun/ppp/wg — the /proc/net/dev idea was
  REJECTED, SELinux blocks app reads; getifaddrs is the real native path). Fixed latent open/openat va_arg UB.
- **P4 profiles backfilled in place**: added CPU/GPU fields to the LIVE Dasher + Cash profiles WITHOUT
  changing identity (android_id/IMEI preserved) — old profiles predate the fields. Pattern: pull the JSON,
  add soc_topology fields keyed on the existing soc_platform, push back via base64 (adb push no-ops on rooted).

### Gauntlet + verification
Every spoof-logic change ran /gauntlet (code-reviewer subagent + /codex); all HIGH findings fixed. Full
zero-host-leak regression check passed. The probe (`ProbeActivity`) self-checks every signal
(sys_cpu_max_freq_sig, pkg_sig, cache sizes, egl/vulkan, vpn_native_iface via a direct C getifaddrs, etc.).

### Workflow rules learned
- Deny an app's location perm before launching if unsure (`pm revoke <pkg> ACCESS_FINE/COARSE_LOCATION`) —
  simpler than Lockito, no GPS leak. Memory `p4-now-free-test-device`.
- adb "unauthorized" after reboots → `adb kill-server && adb start-server` re-triggers the auth dialog (user taps).

---

## NEXT — START HERE
The status-page + timezone + WebRTC work from the last handoff is DONE + merged (v0.19.0). Remaining:
1. **Deploy v0.19.0 to the 4a**, then **reboot both phones** so the new WebRTC hook + native layer load
   (the WebRTC filter is Java-hook, needs a fresh app process / reboot to take effect in scoped apps).
2. **MEASURE the WebRTC filter against detectme.pro through a scoped WebView** — the current
   onPageStarted-timing injection is a HYPOTHESIS until measured; confirm the ICE result shows only the proxy
   candidate. Specter's scope has the FPJS demo + DevInfo + com.specter(.probe); detectme.pro runs in a
   browser/WebView — pick a scoped WebView-based target to test, or note that native Chrome is out of scope.
3. Optional (docs/IDEAS.md): if a real detector beats the onPageStarted timing, add androidx.webkit
   `addDocumentStartJavaScript` for true document-start injection (deferred to keep the module dep-free).

### detectme.pro — Specter's domain vs the proxy's (state plainly, don't overpromise)
- ✅ FIXED device-side (v0.19.0): **Timezone Mismatch** (TZ auto-aligns to proxy IP, gated on VPN routing),
  **WebRTC Leak** (fix-not-block ICE filter, WebView), VPN/proxy interface hiding (v0.18.5).
- ❌ PROXY's job, NOT Specter: **WSS/TCP latency**, **HTTP/3 QUIC** (needs UDP forwarding), **DNS resolver**
  (use home/ISP DNS not Google/CF), **Datacenter-IP reputation**, **RDP/VM**. A device-config module can't
  change packet timing / DNS / QUIC / IP reputation — that's proxy/IP selection. Full write-up in
  docs/ANTI-FINGERPRINT-STRATEGY.md.

## Build/test
Python: `.venv/Scripts/python.exe -m pytest -q`. JVM: `cd xposed-module && bash run-jvm-tests.sh`. Native:
`bash build-zygisk.sh`. Module: `JAVA_HOME=~/scoop/apps/temurin17-jdk/current GRADLE_BIN=.gradle-dist/gradle-8.7/bin/gradle ANDROID_HOME=$LOCALAPPDATA/Android/Sdk bash build-apk.sh`.
Native .so auto-syncs to the device by md5 on app launch; REBOOT to load it. Probe: `gradle :probe:assembleDebug`.
EOL: profile.py/generators.py/cli.py/verify.py/CHANGELOG.md/HookEntry.java/ZygiskInstaller.java = CRLF (edit
byte-wise or re-normalize after Edit + verify `git ls-files --eol`); Profile.java/Coverage.java/main.cpp/
soc_topology.json = LF. `find . -name nul -type f -delete` before commit. /gauntlet before merging.

## Resume phrase
```
Read handoffs/RESUME.md and resume. START with "NEXT": deploy v0.19.0 to the 4a + reboot both phones to load
the new WebRTC hook, then MEASURE the WebRTC ICE filter against detectme.pro through a scoped WebView (the
onPageStarted-timing result is a hypothesis until measured). Both P4 + 4a are FREE test devices (reboot/deploy
freely; deny an app's location perm before launch if unsure). /gauntlet before merging.
```
