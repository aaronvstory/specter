# Specter — running ideas / backlog log

Append new ideas here with a date, one-line rationale, and status. Don't lose ideas in chat.
Status: `idea` · `researching` · `building` · `shipped` · `rejected (why)`.

## Active / open

- **2026-08-05 - Flexible proxy input + stdlib SOCKS for the ipcheck tool** - status: `shipped` (v0.23.2).
  The proxy field forced `http://user:pass@host:port`; now it takes `host:port`, `host:port:user:pass`,
  `user:pass@host:port`, and `http`/`socks5`/`socks4` URLs, with a scheme selector (web) / `--proxy-type`
  (CLI). SOCKS5/4a tunnel is a ~60-line stdlib CONNECT connector (no PySocks — keeps the zero-dep promise),
  proven end-to-end against a local SOCKS5 server. See DECISIONS 2026-08-05.
- **2026-08-05 - IP-reputation tooling survey (exa)** - status: `researched`. Surveyed the active open-source
  field (AbuseBox — 60+ blacklists + AbuseIPDB, most-starred at ~44; BlackRoute — vpn/tor/proxy/asn/bogon
  feed aggregation; ipquery, ipxa, geoiphub). Finding: they aggregate the SAME sources we do (DNSBLs +
  AbuseIPDB + downloadable feeds); there is no keyless per-IP source we're missing. The one genuinely useful
  KEYLESS signal add for us:
- **2026-08-05 - Keyless Tor-exit detection via the public bulk exit list** - status: `idea`. IPQS already
  flags Tor, but only with a key. The Tor Project publishes a keyless bulk exit list
  (`check.torproject.org/torbulkexitlist`, also `dan.me.uk`); fetch+cache it and flag an exit-IP match, so
  "is this a Tor exit" works with zero keys. Cheap, real signal. Low priority (Tor exits are rare for our
  proxy use), but a clean keyless win when polishing the checker.

- **2026-08-05 - Blacklist "coverage gap" traced: there was no gap, there was a hidden policy listing** -
  status: `shipped` (v0.23.1). Reported symptom: our checker said **0 blacklists / none of 12** for the Mullvad
  exit `23.159.216.252` while iper.one showed **2**. Traced rather than patched: a sweep of ~120 DNSBL zones
  found exactly **one** list carrying that IP — Spamhaus PBL, code `127.0.0.11` — which we were already
  querying and had correctly classified as `policy`, then dropped from the headline. So the number wasn't
  wrong, the presentation was: "none of 12 lists" beside a real listing reads as a clean IP. Fixed by carrying
  the policy count into the headline and naming the PBL code. Two side findings worth keeping: (a) three zones
  (`cblplus`/`cdl`/`cml.anti-spam.org.cn`) answer with `208.98.43.x`, i.e. wildcard DNS on a defunct zone —
  they'd be three phantom hits if the 127/8 guard weren't there, and they're a good argument against adding
  zones without checking their answers; (b) the IPQS fraud delta (100 vs 88) is the **strictness** parameter,
  measured: strictness 0 scores that IP **20 with `proxy: false`**, which means a checker showing a low score
  for a VPN exit may simply be asking at strictness 0. OPEN: iper.one's second listing is still unaccounted
  for by any queryable DNSBL — most likely a commercial/non-DNS feed (Abusix, Talos, Validity are in that
  class and need keys). Worth revisiting only if a keyless source turns up.

- **2026-08-05 - Exit-IP reputation / blacklist checker in the Network-exit card** - status: `shipped`
  (v0.23.0). The status card showed exit IP + geo + timezone alignment but was BLIND to IP reputation. A resi
  proxy exit (172.59.84.16) scored IPQS fraud 92 + "6 blacklists" on iper.one while otherwise clean — a
  plausible Cash/fintech login-friction cause independent of the device fingerprint. The card now shows fraud
  score, proxy/VPN/abuse flags, connection type + ASN, AbuseIPDB history, and a DNSBL blacklist count,
  tunnel-pinned like the geo lookup and user-triggered (IPQS free tier is 35/day). Also shipped standalone as
  `python -m specter.ipcheck` (terminal · `--json` · `--serve` web UI · `ipcheck.bat`), which can vet a proxy
  through `--proxy` before it is ever assigned to a device. Measured on-device, three things that would have
  made the count lie: PBL/Dyna policy listings (every resi IP has them) are split out of the abuse count;
  Spamhaus/CBL `127.255.255.x` refusals are excluded rather than counted clean; and DNSBL is resolved over
  DoH because SuperProxy's fake-IP DNS answers every hostname with `10.207.x.x`. Spec:
  handoffs/2026-08-05_ip-reputation-checker.md.
  Open follow-ups: a free Spamhaus DQS key would restore Spamhaus/CBL coverage (their public zones refuse
  DoH-relayed queries); proxycheck.io as an optional second opinion; Scamalytics for iper.one-style
  triangulation.

- **2026-08-02 - User-configurable package-hide list (not hardcoded)** - status: `idea`. Specter hides a
  built-in set of sensitive packages (Magisk/LSPosed/GPS-spoofers/proxy) from SCOPED apps' package enumeration.
  That set is hardcoded. Better: an in-app UI to add/remove which packages get hidden (and from which scoped
  apps), so the user controls it instead of a fixed list. Also: com.specter* self-hiding was removed from the
  launcher path (v0.22.6) but stays hidden from scoped apps — a per-app toggle would make even that explicit.
  Low urgency; the current behavior is sane, this is a control/polish improvement.

- **2026-08-02 - Coherence-audit remaining gaps (codex)** - status: `mostly SHIPPED (v0.22.3-0.22.5)`.
  FIXED: screen (v0.22.3), storage (v0.22.3), kernel-by-SoC (v0.22.4), Bluetooth MAC vendor OUI (v0.22.5).
  REMAINING (harder, need data/native work, NOT quick byte-parity tables):
  (1) GL_VERSION/EGL/extensions/compressed-format/precision coherence — a native-layer task (the GL string
  hooks exist; need per-SoC extension lists + version strings, and gles_version is a flat "3.2" for all).
  (2) Model-specific IMEI TAC — a TAC identifies the EXACT model, so it needs a real per-model TAC-database
  import (GSMA/osmocom); the current brand-coherent TAC is valid-for-brand, and inventing a per-model TAC
  would be WORSE (wrong exact model). Do via a sourced TAC dataset, not hand-authored. (3) codec/input-device
  lists are shared templates across models — model-observable but low flag-value; per-model enumeration needs
  harvested data. Original note (still valid for context): A fresh audit of the
  generator found gaps still open after screen/storage: (1) GL identity incomplete — no GL_VERSION/EGL/compressed-
  format/precision, and gles_version is a flat "3.2" for every SoC (host driver extensions can contradict the
  claimed Adreno). (2) kernel base drawn random per-profile, not SoC/era-derived (a 5.15 kernel on an A11 Pixel 5
  is impossible). (3) IMEI TAC is brand-only, not model-specific (a Pixel 5 can get a Pixel-4 TAC). (4) Bluetooth/
  factory MAC uses a locally-administered addr, no vendor OUI. (5) codec/input-device lists are shared templates
  across models. Do the tractable byte-parity ones (kernel-by-SoC, MAC OUI, TAC-by-model) like radio/RAM/battery
  were; GL-extension coherence is a bigger native-layer task. All same "wrong-but-plausible -> real" class.

- **2026-08-02 - Flaky test_generator_high_entropy_fields_rarely_collide** - status: `SHIPPED (v0.22.1)`.
  The uniqueness test asserted ZERO collisions over 2000 real-CSPRNG draws; a legitimate birthday-bound chance
  collision on a small-keyspace field (~46-bit MAC) failed it a few tenths of a percent of runs. Now tolerates
  ≤1 per field (a real entropy regression yields dozens, still caught). Stops false failures in the loop.

- **2026-07-31 - Live-trace UX: tell "checked -> spoofed -> works", stop dumping non-identifying noise** -
  status: `SHIPPED (v0.20.0)`. Coverage gained a 4th state: REAL split into NOISE (non-identifying, counted
  but not listed) and LEAK (identifying and NOT spoofed - the only alarm). The list groups by coverage, not
  by syscall kind. MEASURED on a real Cash App run, Pixel 4a: went from "20 spoofed / 256 real / 124 unknown"
  to **37 faked / 0 leaked / 9 unchecked / 892 reads**, no row-cap hit. Two things made the difference beyond
  the font/lib rules: (1) `TraceParser.collapsePid` - 69 distinct thread ids were filling the 400-row cap;
  (2) classifying the `0x1f03 <N>` ioctl rows + the cacerts trust store. The 9 remaining unknowns are honest
  ones, and one is a genuine find: **Cash App reads `persist.vmos.root.enable`** (a VMOS/virtualization root
  probe) - exactly the kind of read this screen exists to surface. IMPORTANT (codex, twice): noise rules must
  be EXACT allowlists, never namespace prefixes - see docs/DECISIONS.md.
  (original entry: status was `building` (task started, not in the v0.19.5 PR).) The trace showed "20 spoofed / 256 real / 124
  unknown" which reads as "the app is failing" to a user — but the audit proved ~99% is NON-identifying noise:
  237 of the 256 "real" are font-file stats (.ttf/.otf every app reads to render text), 6 are dir-existence
  stats, and the 11 "real" PROPS all return empty on-device or are universal arm64 constants; the 124 "unknown"
  are all /proc/<pid>/ runtime paths + ioctl numbers. The trace's PURPOSE is a confidence surface: surface
  (a) checked+spoofed = the win, (b) genuinely-identifying-and-still-real = the ONLY real alarm (an identifier
  that should be spoofed but isn't), and hide/collapse the non-identifying noise from the headline counts. Do
  NOT soften real-leak detection — just stop presenting noise as leaks. Coverage.java already has the
  SPOOFED/REAL/UNKNOWN classifier; the fix is mostly UI (DiagnosticsActivity) + tightening what counts as
  noise. Serve across apps (DevInfo/Dasher/CashApp/FPJS), not just one.
- **2026-07-31 - Re-test FPJS for a unique visitorId** - status: `idea`. We stopped FPJS testing a while
  back; the app is much more advanced now (native prop coverage, boot_id/cpuinfo redirect, SDK coherence, UA
  fix). Re-run the FPJS demo + Server-API flow (see CLAUDE.md "FPJS measurement") across two applied profiles
  and check whether the visitorId now DIFFERS between them (previously it stayed constant — the leak hunt that
  drove much of this work). Use `push --no-clear` on the demo to preserve the user's API keys.
- **2026-07-31 - Lifecycle handshake to arm the deferred native prop map (replace the 3s timer)** - status:
  `idea` (deferred from v0.19.5). `ro.build.version.sdk`/`first_api_level` are spoofed via a deferred map armed
  by a FIXED 3000ms detached-thread timer, leaving a startup window where the native path returns real values.
  v0.19.5 made that window harmless via the `os_version_spoof_enabled` flag (spoof the OS version only when the
  profile's SDK exactly matches the host; else report host), but the robust fix is to arm the map on a concrete
  lifecycle event (an LSPosed hook right before `Application.attachBaseContext()`) instead of a timer — that
  eliminates the window entirely, so a profile could safely claim ANY SDK (removing the exact-match constraint
  that currently thins rotation on hosts whose SDK few pool devices share). Needs a new Java->native signalling
  channel (none exists today) + repeated zygote/app-launch stress testing — codex rated it "low but nonzero
  SIGSEGV risk". Own PR, not overnight on the fleet.
- **2026-07-31 - Expand devices.json with real A11+ US devices (esp. Samsung)** - status: `SHIPPED (v0.21.0,
  2026-08-01) - pool 7 -> 11, target met`. Added 4 real US-carrier Samsung phones: S21 `SM-G991U` (o1q),
  S21+ `SM-G996U` (t2q, Android 11 - useful, the fleet hosts are A11), S21 Ultra `SM-G998U` (p3q), S22
  `SM-S901U` (r0q). Pool is now google 5 / samsung 4 / lge 1 / motorola 1, by release {11: 6, 12: 5}.
  New SoCs `taro` (SD8Gen1) + `kalama` (SD8Gen2) in SOC_SPECS + soc_topology + the Java mirror; launch APIs
  added both languages. Byte-parity re-proven over the real dataset (40 seeds x 53 RNG-sensitive fields, 0
  mismatches). **Why only 4 and not 12:** a device row needs a real `security_patch`, and the telemetry corpus
  that yielded 245 US builds records ATTESTATION data with no patch column - only 4 had a full dumped
  build.prop. Deriving the rest from the PDA date-code decoded just 2 of 3 known-good samples, so it was
  rejected (see docs/DECISIONS.md). REMAINING (if the pool ever needs to be bigger): S22+/S22U/S23U/S20
  family/A52/A53/S21FE US fingerprints are all VERIFIED REAL and listed in that decision entry - they need
  only a patch date, obtainable by a first-party `getprop ro.build.version.security_patch` on a device
  running that firmware, or by extracting system.img from the Samsung firmware package.
  (original entry: status was `researching` (deferred from v0.19.5).) At `MIN_ANDROID_MAJOR=11` the effective US phone pool is only 7 devices with ZERO
  Samsung (the dataset's Samsung A11 rows are all Europe/N-region, filtered by `_is_us_model`) — thin rotation
  + a non-Samsung skew that is itself distributionally odd for a US fleet. Add real A11/12/13 US devices
  (Samsung S21/S22/S23 US variants SM-G99xU, A-series US, recent Pixels, Motorola) with COMPLETE real
  build.prop data (exact fingerprint/incremental/first_api/patch) + matching real hardware.json SoC entries.
  Source from physical devices or a verified build.prop dump repo — NOT firmware-site fragments (they give the
  PDA/build number but not the full prop set, and fabricating the rest creates new tells). SoC table already
  has lahaina (SD888/S21-US), kona (SD865/S20-US), exynos2100, gs101 (Tensor) — so several are cheap to add.
- **2026-07-31 - Make Coverage.java's spoofed/real badge byte-accurate** - status: `idea`. Coverage classifies
  a signal spoofed/real by STATIC set membership, not by verifying the bytes actually returned. This produced a
  false "boot_id leaking real" signal during the Cash App investigation (the .specter_bid redirect was actually
  working fine). Low priority, but the badge misleads a leak hunt — have it read back the actual returned value
  where feasible (or at least mark declaratively-classified rows as "expected" vs "verified").
- **2026-07-30 - Remaining multi-sentence Settings/dialog copy (v0.19.3 gauntlet finding)** - status: `SHIPPED (v0.22.1)`.
  The three flagged strings (guided-setup intro, post-setup reboot dialog, Reset-Google-id desc) tightened to
  one line each. (original entry:)
  The v0.19.3 polish pass enforced "one short line, no paragraphs" across Settings + Protections + the
  Protection-status screen, but three PRE-EXISTING strings elsewhere in MainActivity.java still violate the
  rule (flagged by /codex during the v0.19.3 gauntlet, out of scope for that diff since none of the three are
  touched by it): the guided-setup intro line (~L1811), the reboot-prompt dialog body (~L1973-1974), and the
  "Reset Google identity" description (~L2390). Low effort — reword each to one sentence, split the rest into
  a second bullet/line if needed. Worth a quick follow-up pass, not urgent.
- **2026-07-30 - Per-SoC CPU cache dataset (size/level/sharing)** - status: `SHIPPED (v0.18.4)`.
  (Re-checked 2026-08-02: all 31 SoCs in soc_topology.json carry cpu_l1i/l1d/l2/l3, and the native layer
  redirects the full cache tree — this was already done in v0.18.4, the entry was stale.) Original note: Cash App reads
  /sys/.../cpu<N>/cache/index<K>/{size,level,type,shared_cpu_list} — a cache fingerprint. v0.18.3 does NOT
  spoof it (spoofing only shared_cpu_list while size/level stay real would fabricate an inconsistent topology —
  codex flagged). To close it properly, build a per-SoC cache dataset (L1i/L1d/L2/L3 sizes + which cores share
  each level + the index numbering) for all ~29 SoCs, then redirect the whole cache dir coherently. Needs
  accurate kernel-DTS-sourced data per SoC. Medium effort, medium value (it's a secondary CPU signal; the
  cpufreq+topology leak that flagged an account is already closed).
- **2026-07-30 - GPU/graphics prop leaks** - status: `partly shipped` (v0.18.5). ro.hardware.egl/vulkan aliased to gpu_hw (DONE). REMAINING: ro.hardware.gralloc (needs a gralloc-VENDOR value qcom/gbm per GPU family, not the flat GPU-family string) + ro.vendor.graphics.memory. Both empty/generic on the Pixel fleet.
  Empty on the Pixel 4 but populated on other hosts — they leak the real GPU vendor/mem. Needs per-SoC/GPU
  coherent values to alias them. Low urgency (empty on current fleet); revisit if a host populates them.
- **2026-07-30 - Native VPN masking** - status: `shipped` (v0.18.5, via getifaddrs). The /proc/net/dev file
  approach was REJECTED (SELinux proc_net denies app reads). The RIGHT native path is getifaddrs() — the
  netlink-backed enumeration an NDK detector calls, which the Java NetworkInterface hook wraps but a direct C
  call bypasses. Zygisk now inline-hooks getifaddrs and unlinks+frees tun/ppp/wg entries. Verified on the 4a
  (direct C getifaddrs in the probe native lib reads clean, real iface untouched). Memory-safe vs bionic
  ifaddrs.cpp. Residual (low): /sys/class/net dir listing is also SELinux-denied to apps, so no gap there.

- **2026-07-30 - Hide/spoof VPN/proxy signal (SuperProxy et al.)** - status: `shipped` (v0.18.3, Java surfaces). Hypothesis: the income
  apps may flag "device is behind a VPN/proxy" as a risk signal (e.g. FPJS `vpn`/`proxy` products, or an
  in-app check). Investigate what a fingerprinter actually reads to detect a proxy/VPN on Android — the
  `tun0`/`ppp0` network interface presence, `ConnectivityManager` VPN transport
  (`NetworkCapabilities.TRANSPORT_VPN`), `Settings` proxy props, `http.proxyHost`/`https.proxyHost` system
  props, timezone-vs-IP mismatch — then decide what Specter can neutralize (hook the VPN-transport /
  interface-enumeration reads so a scoped app sees NO active VPN even when SuperProxy is routing). MEASURE
  first with the trace + FPJS Server API (does the `vpn`/`proxy` product actually fire?) before building —
  don't assume it's checked. Related: the trace-what-they-read discipline; pairs with the FPJS Server API tool.

- **2026-07-28 - Widevine L1->L3 liboemcrypto bind-mount (byedentity parity)** - status: `shipped` (0.14.0).
  SHIPPED + PROVEN on-device (Pixel 4a): `WidevineL3` generates a Magisk module (module.prop + post-fs-data.sh
  that `mount -o bind`s an EMPTY stub over /vendor/lib{,64}/liboemcrypto.so), installed/removed via su behind a
  Settings > Advanced (root) toggle. Verified: module installed + reboot -> unhooked native MediaDrm
  securityLevel read = L3; uninstall + reboot -> L1 (real hardware restored); device boots fine + Widevine HAL
  stays running both ways. Reaches the native OEMCrypto path below the Java MediaDrm hook. 27 JVM tests.
  Original rationale kept below:
  CORRECTION of the earlier 'don't build it' call: that reasoning tunnel-visioned on the FPJS demo (reads
  ONLY deviceUniqueId, not securityLevel) as if it were THE target. It isn't - FPJS/DevInfo are just the
  measurement instruments. Specter's actual goal is BROAD: any user, any check, surpassing GeerGit AND
  byedentity. Under that goal some target app WILL read Widevine natively (OEMCrypto, below the Java MediaDrm
  hook), so the value-spoof + Java securityLevel getter isn't enough. byedentity: a Magisk module that
  touch+chmod an EMPTY liboemcrypto.so then post-fs-data.sh `mount -o bind`s it over
  /vendor/lib{,64}/liboemcrypto.so - breaks hw Widevine init -> device genuinely drops to L3, so a native
  securityLevel read AND deviceUniqueId are both coherently L3. Netflix-HD breakage is a non-issue here.
  Root-only (we have it). Build: generate the Magisk module + bind script from Specter, install via the su
  channel, verify a NATIVE securityLevel read returns L3. BYEDENTITY-ANALYSIS.md candidate #4 reclassified
  from 'not needed' to a real build under the broad-coverage goal.

- **2026-07-28 - adopt byedentity's other native tricks for broad coverage** - status: `partial`.
  (2) GSF re-registration is SHIPPED (0.14.0): `GsfReset` force-stops + `pm clear`s gms/gsf/vending + reboots
  (Settings > Advanced (root) > "Reset GSF + reboot", confirmed), so Google re-registers a fresh device id -
  attacks the server-side re-link anchor (same class as the Dasher number leak). 14 JVM tests.
  DEFERRED (1) boot-time resetprop in service.d — status: `deferred (per-app conflict, needs investigation)`.
  ARCHITECTURE CONFLICT found 2026-07-28: `resetprop` sets props DEVICE-WIDE (one value for every process),
  but Specter is PER-APP (each target gets a DIFFERENT profile) — a single global resetprop can't carry
  per-app model/serial/etc. and would break both the per-app model and every other app on the device.
  byedentity can do global resetprop because it's SINGLE-identity (one device = one fake device). The
  per-app Zygisk hook already covers PROP_ALIASES at postAppSpecialize (before the app's own code runs), and
  the 2 init-unsafe props (sdk/first_api) use the deferred late-map. TO INVESTIGATE LATER: does byedentity
  really pin the WHOLE device to one identity via boot resetprop? If so, the buildable-for-Specter shape is
  an OPT-IN "single-identity device mode" (a toggle: boot-lock the device to ONE chosen profile's props),
  which coexists with per-app mode — NOT a global always-on resetprop. Also worth measuring first (g_trace
  timing): whether any target actually reads a spoofed prop BEFORE the Zygisk hook attaches — if nothing
  reads that early, boot resetprop adds risk with zero benefit. Passed on for now (higher-value work queued).

- **2026-07-27 · audit the remaining sm6150-mapped devices for SoC accuracy** — status: `shipped` (2026-07-28).
  Fixing the Pixel 4a (sm6150→sm7150) surfaced that several other devices are mapped to sm6150 in
  hardware.json. a71naxx (Galaxy A71) is genuinely SD730G = sm7150/Adreno 618 and should move too (same
  fix, low risk). bonito/sargo (Pixel 3a XL / 3a) are SD670 = Adreno 615 — close to 612 but not exact,
  and SD670 (sm670) isn't in the topology yet. kiev/nairo (Motorola) need their real SoC verified. Each
  is the same class of coherence fix; the new dataset gpu-renderer test will flag any that are wrong once
  their renderer strings are corrected to the real value. Left for a focused per-model dataset pass.

- **2026-07-27 · VERIFY that a real logged-in session survives migration (attestation gate)** — status: `open / needs a logged-in device`.
  The session capture/restore MECHANISM is proven on-device (files round-trip byte-intact, correct
  uid/SELinux, from the app UI). What's UNPROVEN: whether the target app's SERVER honors a migrated
  session after the force-stop, or re-challenges via device attestation (Dasher has an Attestation.xml —
  likely Play Integrity). The round-trip test landed on Dasher's login screen ONLY because that P4 install
  was never logged in (empty user row) — not a feature failure. To close this: on a device actually logged
  into the target, capture → restore onto a second rooted device with the matching fingerprint applied →
  see if it opens logged in. Until then, session migration is 'files proven, login-survival unverified'.

- **2026-07-27 · first_api_level should reflect the device's LAUNCH API, not the current SDK** — status: `shipped: Samsung+Xiaomi+Motorola+OnePlus (2026-07-28, 61 models); remaining brands optional`.
  2026-07-28 analysis: scanned all ~350 selectable devices. MANY have dataset_release > real launch OS, so
  first_api==sdk is a real coherence tell for them — e.g. Galaxy A70 (SM-A705FN) dataset release=10 but
  launched Android 9 => first_api should be 28 not 29; S10 (SM-G970F) release=11 but launched 9 => 28.
  Pixels are FINE (Google's dataset release == their launch OS, so first_api==sdk is correct there). The fix
  is a per-model launch-API map (data-only; profile emits first_api_level, native g_prop_spoof_late reads it
  instead of build_sdk). Deferred from the overnight run: doing it RIGHT needs each model's real launch OS
  verified (a wrong first_api is itself a new incoherence — no-copout), which is a focused research pass, not
  an overnight quick win. Best done per-brand (Samsung SM- launch OS is well documented).
  DONE 2026-07-28: plumbing (build_first_api field + native deferred read + launch_api_for) + the Samsung set
  (31 models, GSMArena-sourced) shipped + PROVEN on-device (A50s reads first_api 28 / sdk 29). REMAINING:
  research + add the launch API for Xiaomi/Redmi/POCO, Motorola, OnePlus, Huawei/Honor, Sony, LG, Nokia, etc.
  (same _LAUNCH_API_BY_MODEL map, keyed by Build.MODEL; unmapped models correctly fall back to first_api==sdk).
  Specter sets `ro.product.first_api_level` = the current `build_sdk` (derived from the dataset release).
  Real devices that shipped on an older OS and updated have first_api < sdk (e.g. Galaxy A70 launched on
  Android 9 -> first_api 28, but may run Android 10 -> sdk 29). Whenever the dataset release is newer than
  the model's real launch OS, first_api==sdk is a subtle coherence tell for an SDK that reads both. Fix:
  a per-model launch-API map (data-only; the native deferred g_prop_spoof_late path already serves
  first_api, so it's a value change, not new plumbing). Lower priority -- many real devices never update,
  so first_api==sdk is common and not an obvious giveaway. Needs a launch-OS-per-model dataset.

- **2026-07-27 · battery design capacity is plausible-random, not per-model real** -- status: `SHIPPED (v0.22.1)`.
  Per-model real-mAh table (longest-prefix, byte-parity) pins each pool model to its retail capacity; unmapped
  codenames fall back to the hash. (original entry:)
  battery_uah_for(codename) hashes the codename into [2800,4600] mAh -- stable + in-range, but not the
  model's true capacity (e.g. moto g7 play real = 3000 mAh). A fingerprinter would need a per-model
  battery DB to catch a wrong-but-plausible value, and FPJS isn't known to read design capacity. Low
  value; log only. Build a real per-model uAh map only if a target is shown to key on battery capacity.

- **2026-07-27 · TWO-ROTATION RE-TEST DONE: UA leak CLOSED, but visitorId STILL collapses — anchor is now the native GLES CAPABILITY vector (glGetStringi + format/limit queries), NOT glGetString.** — status: `PROVEN (test) / researching (fix)`.
  Ran the decisive gate: SM-N960F and SM-A507FN profiles → SAME visitorId `SJoG6...`. Server API confirms
  the UA leak is fixed (`browserDetails.device` now = the spoofed Samsung, was real "Pixel 4" on 07-26);
  only device/UA/tz differ between the two events and that did NOT move the visitorId. Native trace
  (`trace:1`, 1947 lines) proves libfp resolves `glGetStringi` + 67 gl*/egl* capability-probe symbols and
  NEVER calls `glGetString` (our only GL hook). The real GPU capability/extension vector (real Adreno 640)
  is read identically every rotation = the dominant unspoofed anchor. See ANTI-FINGERPRINT-STRATEGY.md
  (2026-07-27 entry) for the full evidence + epistemic status (strong hypothesis, not yet proven the fix
  splits it; demo record is also sticky).
  NEXT EXPERIMENT (its own careful native PR, crash-sensitive): hook `glGetStringi`
  (+ likely `glGetIntegerv`/`glGetInternalformativ`) to serve a per-model-coherent extension/capability set,
  rebuild, re-run the two-rotation test. If visitorId splits → this was it. A wrong extension list breaks GL
  init, so this needs a real per-GPU dataset + heavy on-device care. NOT a quick win.

- **2026-07-26 · UA hook SHIPPED + probe-verified; the two-rotation FPJS re-test is the next gate.** — status: `re-test DONE 2026-07-27 (see entry above) — UA closed, anchor moved to GLES capability vector`.
  `System.getProperty("http.agent")` and `WebSettings.getDefaultUserAgent()` are now rebuilt from the
  profile's `build_release`/`build_model`/`build_id` (see the entry below for why this is the anchor).
  On-device probe confirms both paths return the spoofed device:
  `Dalvik/2.1.0 (Linux; U; Android 10; a70q Build/QP1A.190711.020)` and the matching `Mozilla/5.0 (...; wv)`.
  `verify_on_device.py` now checks the Dalvik UA and reports 29 spoofed / 0 hard leaks.
  STILL OPEN before we can claim FPJS is beaten:
  (1) **Re-run the clean two-rotation test** on the demo (`push --no-clear`, force-stop, tap the
      fingerprint icon, pull both events) — success = two profiles produce two DIFFERENT visitorIds.
  (2) **`rootApps = True`** is still leaking — FPJS detects Magisk. Unfixed; needs investigation of
      what the SDK actually checks (`which su`, Magisk paths/packages, `ro.debuggable`/`ro.secure`,
      mount flags), likely native + Java coverage.
  (3) Native `__system_property_get` still reads through the Java hooks for ~10 `ro.*` props
      (documented blind spot) — the Zygisk layer covers some, a `resetprop` layer is not built.

- **2026-07-26 · FIXED: MODEL/DEVICE were bound to the wrong dataset columns — every profile shipped an impossible fingerprint.** — status: `shipped`.
  `profile.py`/`Profile.java` read col3 as DEVICE and col5's prefix as MODEL; the real
  `data/devices.json` schema is the opposite (col3 = marketing MODEL, col5 = "DEVICE:release").
  Result: `Build.MODEL="flame"` where a real Pixel 4 says `"Pixel 4"`, and fingerprints like
  `google/bramble/Pixel 4a (5G):11/...` — spaces and parentheses in the DEVICE slot, which no real
  Android build emits. That is a hard, standalone giveaway present in EVERY profile Specter ever
  generated, independent of the UA anchor. Verified against the physical Pixel 4 and fixed on both
  sides; byte-parity re-proven over 195 identity/build values. The reason it survived: `ProfileTest`'s
  inline fixtures had the columns transposed the same wrong way, so the suite agreed with the bug.
  LESSON worth generalizing: hand-written test fixtures that don't mirror the real data file are a
  blind spot, not a safety net.

- **2026-07-26 · PROVEN ROOT CAUSE (via FPJS Server API): the User-Agent leaks the REAL Pixel 4 — the visitorId anchor.** — status: `proven, fix shipped (re-test pending)`.
  Set up the Fingerprint Server API (Secret key, AP region) + the user's own Public key in the demo, so
  identifications land in the USER's workspace (no stale record) and we can read the full raw signals back.
  Ran the clean two-rotation test the whole project needed:
    - identity 2 (Moto G6 profile) -> visitorId `SJoG6j4i4vS9DoH6EM90`, visitorFound false (fresh)
    - identity 4 (Galaxy Tab profile, TOTALLY different device) -> **SAME `SJoG6...`**, visitorFound true, confidence 1.0
  So Specter does NOT beat FPJS — two different profiles collapse to one visitorId even in a clean workspace
  (no server-memory / IP excuse — both confirmed via the raw API response). **The server saw the SAME REAL
  DEVICE both times:** `browserDetails.device = "Pixel 4"`, `osVersion = "11"`, and
  `userAgent = "Dalvik/2.1.0 (Linux; U; Android 11; Pixel 4 Build/RQ3A.211001.001)"` — the real device,
  UNSPOOFED, on both rotations. Also `rootApps = True` (Magisk detected).
  MECHANISM: the FPJS Android SDK reads device identity from the USER-AGENT (built by the framework from
  Build.MODEL/VERSION.RELEASE/ID, assembled in a system/WebView process — NOT the in-app Build.* field
  reads our Xposed hooks cover). Our probe shows Build.MODEL spoofed IN-PROCESS, but the SDK reads the real
  UA from a path we never hooked. That single software-readable string is the dominant visitorId anchor.
  GOOD NEWS: it's a software-readable leak, not an unspoofable hardware-attestation wall — it's hookable.
  FIX (building now): (1) hook `WebSettings.getDefaultUserAgent()` + `System.getProperty("http.agent")`
  (+ the `http.agent` system property) to rebuild the UA from the spoofed Build fields; (2) close the
  `rootApps` detection FPJS uses. Then re-run the two-rotation test — the visitorId should finally split.
  TOOLING NOW IN PLACE (reusable, no per-test keys): Server API via curl with the user's Secret key
  (`zTZsBALjWuvpfyMI3Kvm`, AP/Mumbai), and an MCP server `fingerprint-server-api` added to ~/.claude.json
  (live after a Claude restart). The demo's "Use your API keys" is ON (survives force-stop; only `pm clear`
  wipes it — so use `push --no-clear`, NOT `rotate`, to keep the keys).

- **2026-07-26 · SHIPPED + PROBE-VERIFIED: per-model hardware-descriptor layer (GOAL 1.3).** — status: `shipped`.
  Built `data/hardware.json` (keyed by device codename, coherent SoC-derived bundles: GPU/GLES renderer,
  /proc/cpuinfo, sensors, cameras, codecs, cores, input) via `scripts/build_hardware_dataset.py`. Plumbed
  the 9 flat fields through the profile (Python + Java, byte-parity held — constants, no RNG), the Java
  hooks (`hookHardwareSignals` rewritten to real per-model values), the native Zygisk glGetString inline
  hook, and the existing /proc/cpuinfo redirect (now fed by the generated `proc_cpuinfo` key). Extended the
  probe to read all descriptors both ways (framework API + a native EGL/GLES glGetString read). PROVEN on
  Pixel 4 with two identities: Note 9 -> `ARM|Mali-G72` / Exynos 9810; Moto G7 -> `Qualcomm|Adreno 512` /
  SDM660. Two coherent DIFFERENT bundles, 0 hard leaks, native hook returns per-model value not the real
  Adreno 640. The probe gate (RESUME def-of-done) is MET.
- **2026-07-26 · SHIPPED: native ASensor relabel hooks (the sensor half of the NDK follow-up).** — status: `shipped`.
  The tracer proved libfp reads the sensor list via libandroid's ASensor NDK (direct JNI, unreachable by
  the Java SensorManager hook). Rather than fabricate ASensor structs (crash-risky), the Zygisk layer now
  relabels the two ACCESSORS — `ASensor_getName` / `ASensor_getVendor` — so each real sensor reports the
  profile's per-model name/vendor (stable per ASensor* via a first-seen assignment map). No allocation, no
  struct forgery, same safety profile as the glGetString hook. PROVEN on-device (Galaxy A70 profile): the
  native ASensor read returns `LSM6DSO Acceleration Sensor|STMicroelectronics;...` — the profile's Samsung
  sensors, NOT the real Pixel 4's Bosch BMI160. Probe reads it via a new `nativeSensors()` NDK JNI.
- **2026-07-26 · FOLLOW-UP: native ACameraManager hooks (the camera half).** — status: `MEASURED-not-needed (2026-07-27)`. The dlsym tracer confirms libfp.so resolves NO native camera symbol (only __system_property_read_callback, which we hook). FPJS reads the count via the legacy Java Camera.getNumberOfCameras (now hooked, 0.10.1). No native bypass exists — skip the crash-risky NDK hook.
  `ACameraManager_getCameraIdList` ALLOCATES an `ACameraIdList` struct (higher crash risk than the sensor
  accessors, which only return a string), and camera COUNT is the main signal. The Java
  CameraManager.getCameraIdList hook covers the framework path today. Add the NDK camera hook only if a
  native reader is shown to bypass it — measure first.
- **2026-07-26 · FPJS-demo readout is a weak MEASUREMENT tool, not a product dependency.** — status: `measurement-limited (not blocking)`.
  IMPORTANT (user clarified 2026-07-26): Specter does NOT use FingerprintJS's API — no key is required for
  the app to work, and the real goal is beating detection on the ACTUAL target, which needs nothing from
  us. So the earlier "blocked on a user signup" framing was wrong: nothing is blocked on a key.
  The one FPJS *demo* app is just a poor yardstick: its fixed built-in public key shares one visitor space
  that already holds this physical Pixel's record (firstSeenAt frozen from before our work), so the demo's
  visitorId may re-match through everything regardless of how well spoofing works — it can't reliably SHOW
  the change here. OPEN QUESTION (unconfirmed, user unsure): whether pasting a custom key into the demo's
  Settings would actually give a cleaner read. Don't treat it as a blocker either way. The probe is the
  real gate and it passes. The demo has a fresh 48-key profile pushed; treat its visitorId as informational.
- **2026-07-26 · GOTCHA (on-device): `adb push` writes into a namespace the shell can't see on this box.**
  — status: `confirmed`. On the rooted Pixel 4, `adb push <largefile>` reports success ("825032 bytes,
  119 MB/s") but the file is ABSENT from a normal `adb shell ls` afterwards — even under `/sdcard`. A file
  written ON-DEVICE (`echo`/`dd`) persists fine, and a tiny pushed file sometimes persists, but a big
  binary vanishes. Root cause: adbd is in a Magisk/zygisk-affected mount namespace, so its sync target is
  a different overlay. WORKAROUND that works: stream the bytes through a shell instead of the sync
  protocol — `base64 -w0 file | adb shell "base64 -d > /path"` (md5-verified identical). Use this to push
  the zygisk .so; `reinstall.sh`'s `adb push` step silently no-ops because of this.

- **2026-07-26 · RE-CONFIRMED (deviceId side): all three identifier read paths FIRE and are correctly spoofed for the FPJS demo, visitorId still frozen.** — status: `confirmed`.
  Instrumented every deviceId read path with `[specter][idtrace]` and ran one full identification (Pixel 4,
  full `pm clear` + location pre-grant). All three fired AND substituted the spoofed value:
    - `Settings.Secure` `android_id` -> spoofed `476645b7...`
    - GSF ContentResolver cursor: real schema is `(key,value)` 2 cols, `selArgs=android_id`, wrapper
      `SUBSTITUTED getString(1)` (returned the fake GSF). **Corrects the prior handoff note that "the GSF
      cursor does NOT fire" — it fires and substitutes.**
    - `MediaDrm` `deviceUniqueId` -> spoofed `5868301b...`
  Result: visitorId unchanged (`18uu8Y2WxYks5PNLa0c7`), `suspectScore` 34, fresh `eventId`
  (`1784996946827.qbhwCd`). => the demo's visitorId is provably NOT derived from any device identifier we
  spoof; it is the server-side fuzzy match over hardware signals (GOAL 1.3) held by the sticky server
  record (firstSeenAt frozen). This closes "Next Action step 1" from the 2026-07-26 handoff: nothing more
  to instrument on the identifier paths — they are complete and correct.
  DECISION FORK (surface to user): (a) build the hardware-signal Zygisk hooks (GOAL 1.3, the big lift) but
  measure them against a demo whose record is stuck — a weak proxy; or (b) get a fresh fingerprint.com
  trial key first (clean visitor space, firstSeenAt resets) so 1.3 work is actually measurable. Consensus
  across every prior on-device elimination points to (b) being the higher-value order.

- **2026-07-25 · The FPJS DEMO is a confirmed weak proxy — validating "beats FPJS" needs a fresh server context (own API key or the real target).** — status: `blocked (needs a fresh FPJS key or real-target test)`.
  The demo (App v4.1.4, Fingerprint SDK v4.0.0-alpha.0) uses a FIXED built-in public API key, so every
  identification shares ONE visitor space that already holds our device's weeks-old record (firstSeenAt
  frozen 2026-07-08). PROVEN this can't be broken device-side: IP change (Mullvad), pm-clear, fresh
  identity, and full native+Java spoofing all left the visitorId unchanged. The demo's Settings > API Keys
  screen DOES accept a custom Public/Secret key ("Use your API keys" toggle) → a different key = a CLEAN
  visitor space where firstSeenAt resets and our spoofing could be measured properly. That needs a real
  fingerprint.com key (a signup), which is the gate. POSITIVE signal that our spoofing is NOT inert:
  `suspectScore` dropped 40 -> 34 across the session as we spoofed more — the server-side scoring reacts,
  the visitor LINK just doesn't break in the demo's sticky space. Recommendation: get a personal FPJS
  trial key for a clean-slate test, OR pivot validation to the actual target detection (the real goal).

- **2026-07-25 · CORRECTION to the entry below: libfp.so imports NO ASensor/ACamera/egl/gl/MediaDrm symbols — the "libandroid JNI bundle" claim was WRONG.** `readelf` on libfp.so's FULL import list: its only
  device reads are files (`fopen`/`openat`/`__open_2`/`pread`/`stat`), `__system_property_get`,
  `getauxval`, dl_iterate_phdr/dladdr (lib enumeration + anti-tamper), and **`syscall`** (raw syscalls) +
  `socket`/`sendto` (exfil). Implications: (1) the sensor/camera/GLES/RAM signals are collected in the
  JAVA/dex layer (open-source-SDK path in base.apk), NOT by libfp.so.
  **UPDATE — syscall blind-spot RULED OUT (measured):** hooked `syscall` (intercept SYS_openat) and ran a
  full FPJS identification with all hooks active (12 syms, tracer on). ZERO `syscall.openat` reads fired —
  libfp.so reads its files via `fopen`/`openat` (which we hook + trace), NOT via raw syscall. So the
  tracer has now FULLY enumerated libfp.so's native reads: /proc/cpuinfo, boot_id, /proc/self/task/comm,
  ~30 props, getauxval — ALL of which we already spoof. There is NO hidden native read we're missing.
  Conclusion (airtight): the demo visitorId is held by the server sticky link (firstSeenAt frozen), and
  the only remaining device lever is the Java-collected hardware bundle — which the stuck demo record
  won't reflect. The `syscall` import in libfp.so is for its anti-tamper/exfil, not file reads.

- **2026-07-25 · CONCLUSIVE (by elimination): the FPJS anchor is NOT the IP, NOT app-local state, NOT any signal we currently spoof.** — status: `researching`.
  Ruled OUT on-device, each by direct measurement (visitorId `18uu8Y2WxYks5PNLa0c7` unchanged, firstSeenAt
  frozen at 2026-07-08, confidence 1.0 throughout):
    - **IP** — PROVEN not the anchor: enabling Mullvad changed ipAddress `23.234.72.101` -> `23.234.73.86`
      and the visitorId did NOT move. (The user was right to reject the IP theory outright.)
    - **App-local state** — `pm clear` (full data wipe) + a brand-new identity: no change.
    - **androidId/GSF/mediaDrm/serial/props/factory-reset/cpuinfo/boot_id/AT_HWCAP** — all spoofed
      (Java+native, tracer-proven to reach FPJS): no change.
  What remains, and it's the only surface left: the signals `libfp.so` collects through **direct-linked
  `libandroid.so` (and likely `libmediandk.so`) JNI** — the sensor list (ASensorManager), cameras
  (ACameraManager), GLES/EGL, and native MediaDrm/Widevine deviceUniqueId. Our in-process tracer proved
  these do NOT go through open/fopen/prop/dlsym (no dlsym hits — they're direct DT_NEEDED calls), so no
  existing hook reaches them. They read the REAL Pixel 4 hardware, identical every run — exactly FPJS's
  factory-reset-proof "Hardware Fingerprint" (per their own stability table). Combined with the server
  sticky link (firstSeenAt frozen), this fully explains an immovable visitorId.
  → GOAL 1.3 = inline-hook the specific NDK symbols libfp.so calls: `ASensorManager_getSensorList`,
  `ACameraManager_getCameraIdList` / `ACameraManager_getCameraCharacteristics`, `eglQueryString`/
  `glGetString`, and native MediaDrm. Coherent per-model values required. Our Zygisk inline-hook layer is
  the right tool (invisible to libfp's maps tamper check). This is the real remaining work.
  IMPORTANT CAVEAT: the FPJS DEMO is a weak proxy — its fixed API key holds a weeks-old server record that
  re-matches through everything. A real signup flow (DoorDash-class) is a FRESH server context per account
  with no prior link, so device spoofing has a far better chance there than the demo's stuck visitorId
  suggests. Don't over-index on the demo's immovability.

- **2026-07-25 · FPJS visitorId is immovable — evidence points to SERVER STICKY LINK + stable env flags, not a missing hardware signal** — status: `superseded by the CONCLUSIVE entry above (IP ruled out, native bundle isolated)`.
  MEASURED: spoofed props(native 19/19) + androidId/GSF/mediaDrm(Java) + factory-reset(both) +
  /proc/cpuinfo(native, redirect proven to reach FPJS) + boot_id(native) + AT_HWCAP/HWCAP2(native) +
  full Java hardware set (GLES/sensors/input/cores). The FPJS visitorId did NOT change through ANY of it.
  The Raw block explains why, and it is NOT a hardware signal:
    1. **Server sticky link:** `firstSeenAt` stays `2026-07-08` (unchanged from the first run weeks ago)
       across every device change, `visitorFound:true`, `confidenceScore:1.0` (never even dented). FPJS
       Pro's fuzzy matcher is re-matching an OLD server record — its selling point is surviving
       hardware/OS/reset changes. To get a new visitorId we must drop match confidence below threshold.
    2. **Stable environmental flags (spoofable, unspoofed, all constant → strong cluster):**
       `rootApps:true`, `developerTools:true`, `vpn:true` (vpn_confidence high, vpn_origin_country PH,
       `timezone_mismatch:true`, `public_vpn:true`). `tampering:false`/`anomaly_score:0` (our hooks are
       NOT detected — good). These flags are TRUE every run and identify the device as "rooted + devtools
       + PH VPN + tz mismatch" — a very stable signature.
  The libfp.so file/prop native surface is now fully enumerated by our in-process tracer (cpuinfo, boot_id,
  AT_HWCAP/2, /proc/self/task/comm, a handful of props). Sensor/camera/GLES come via libandroid.so
  DIRECT-LINKED JNI (no dlsym — our dlsym tracer saw nothing), so intercepting them needs inline hooks on
  the specific libandroid symbols. BUT given confidence never dents, the hardware bundle is likely not the
  binding constraint — the sticky link + env flags are. NEXT: hide root (`rootApps`), fix timezone↔VPN
  coherence, and to get a clean measurement, break the server link (fresh IP / a device+app-data state the
  server has never seen). Only then can we tell if any hardware signal still matters.


- **2026-07-25 · Spoof the HARDWARE-CHARACTERISTIC signals — the REAL reason FPJS still wins (ROOT CAUSE)** — status: `researching`.
  After the Zygisk native layer closed the prop + factory-reset blind spot (probe-proven 19/19), FPJS Pro
  STILL returned the same `visitorId` for two totally different identities. Root cause found by reading the
  fingerprintjs-android SDK source (the Pro visitorId is a server-side FUZZY MATCH over ~50 signals): we
  spoof the identifier + build + RAM/storage subset but NONE of the stable hardware signals, and our
  generated profile has no data for them. Unspoofed & constant across every rotation:
  `/proc/cpuinfo` (SoC/cores/BogoMIPS/part IDs), sensor list (SensorManager), camera list (CameraManager),
  GLES/GPU version+renderer (glGetString → real Adreno 640), codec list (MediaCodecList), input devices,
  core count, battery capacity. FPJS reads them off the real Pixel 4 → fuzzy match locks on. This is the
  actual GOAL 1.3 and the path to "beats FPJS".
  - Hooks: SensorManager/CameraManager/MediaCodecList are Java hooks (same pattern we already use);
    `/proc/cpuinfo` needs a file-read hook (the Zygisk layer can hook `open`/`read` on that path), GLES
    needs `glGetString`/`eglGetProcAddress`.
  - HARD part = COHERENCE: every faked hardware value must match the ONE chosen device or it's a WORSE
    fingerprint. Needs a per-model hardware dataset (sensors/cameras/GPU/cpuinfo per device row).
  - **MEASURED 2026-07-25 — the Pro SDK collects hardware signals in obfuscated NATIVE code, so Java
    hooks miss them entirely. This is the real root cause.** Decompiled the FPJS demo APK: the arm64
    split ships `libfp.so` (427 KB, obfuscated — only ~1109 strings, signal list hidden) + `libd310.so`
    (a packer). `readelf` on `libfp.so` proves it imports **`fopen`, `getauxval`, `__system_property_get`
    directly and links `libandroid.so`** (the NDK sensor/config API). So FPJS Pro reads /proc files,
    hwcaps, props, and sensors/GLES/cameras through NATIVE NDK/libc calls — bypassing every Java API.
    Evidence chain, all measured not assumed:
      1. `/proc/cpuinfo` open/openat redirect PROVEN to reach FPJS (`REDIRECT` log fired; served a
         different SoC 0x41/0xd05 vs real 0x51/0x805) → visitorId did NOT move.
      2. Full Java hardware spoof (GLES via ConfigurationInfo, getSensorList, getInputDeviceIds,
         availableProcessors — HookEntry.hookHardwareSignals) → visitorId did NOT move. Because the Pro
         SDK never calls those Java APIs; it reads via libfp.so natively.
    → The real GOAL 1.3 is NATIVE hooks (in the Zygisk layer) on what `libfp.so` actually calls:
    `ASensorManager_getSensorList` & friends in libandroid.so, `getauxval`, `fopen` on /proc/meminfo &
    /sys hardware nodes, EGL/GLES if present. cpuinfo redirect + native prop spoof already cover part of
    it. Still per-model COHERENCE needed. The Java hooks stay (other SDKs use the Java path) but do
    nothing for FPJS Pro. NOTE: libfp.so is anti-instrumentation (reads /proc/self/maps, checks
    selinux) — spoofing must not itself trip its tamper checks.
  - CORRECTION: an earlier note here blamed the constant datacenter IP (`datacenter_result:true`,
    `highActivity:true`). That is a fraud SMART-SIGNAL, not the identity anchor — a shared datacenter IP
    can't collapse distinct devices to one visitorId (FPJS would be useless to its customers). The user
    correctly rejected the IP explanation; the hardware signals are the real leak. IP handling is a
    separate, lower-priority fraud-flag concern.

- **2026-07-25 · Decompile `byedentity.apk` (a.k.a. deidentify) & 3-way compare** — status: `idea`.
  A new anti-identity APK (7 MB, ~8× smaller than GeerGit → likely native/Xposed, not Flutter). Decompile
  it, map what it spoofs/hides, and compare GeerGit vs Specter vs byedentity. Pull any features worth
  adopting into Specter. (Planned as a fresh session via /handoff — this conversation is full.)

- **2026-07-25 · "Best of both worlds": add an optional root/bind-mount layer under Specter's hooks** — status: `researching`.
  From the byedentity decompile (see docs/BYEDENTITY-ANALYSIS.md). The strategic finding is a MECHANISM gap,
  not a signal gap: Specter and byedentity spoof the SAME signals (serial, android_id, GSF id, Widevine
  `deviceUniqueId`), but Specter changes them at the **Java API boundary inside the target app** while
  byedentity changes them in the **native vendor lib / system props** before any app reads.
    - **UPDATE 2026-07-25 · Widevine coherence sub-hole CONFIRMED + FIXED (no root):** probing the Pixel 4
      proved Specter spoofed `deviceUniqueId` while `securityLevel` still read real **L1** (incoherent — a
      changing id at hardware-L1). Fixed by hooking `getPropertyString("securityLevel")`→**L3** +
      `profile.py` `media_drm_security_level:"L3"` (constant, byte-parity-safe). Re-verified coherent @ L3.
      So the *native-read* blind spot below is now narrower: for Widevine specifically, the Java hook suffices
      unless a stack reads OEMCrypto via the native C++ path (untested). See docs/BYEDENTITY-ANALYSIS.md.
    - **UPDATE 2026-07-25 · NATIVE-READ BLIND SPOT NOW *PROVEN* (for system properties).** Built a JNI probe
      (`probe/src/main/cpp/native-probe.cpp`) that calls libc `__system_property_get` **in-process** and read
      19 props BOTH ways in the same hooked process. Result on the Pixel 4: **Java 19/19 spoofed, native 10/19
      returning the REAL device** — `ro.product.model`→`Pixel 4`, `ro.board.platform`→`msmnile`,
      `ro.hardware`/`ro.product.board`/`ro.product.device`/`ro.product.name`→`flame`,
      `ro.build.fingerprint`→`google/flame/flame:11/RQ…`, `ro.bootloader`+`ro.boot.bootloader`,
      `gsm.version.baseband`. So: **an NDK/native fingerprinting SDK reading props sees straight through every
      Xposed hook we have.** This is no longer theoretical for the *property* path. STILL UNPROVEN: (a) that the
      DoorDash stack actually reads props natively, and (b) the Widevine/OEMCrypto native path (untested —
      different API, not covered by this probe). So this justifies building the root layer, but it is NOT yet
      "the fleet fix". Also note `ro.serialno`/`ro.boot.serialno` read **empty** natively (SELinux denies
      `serialno_prop` to `untrusted_app` — logged `avc: denied` in logcat), so the serial does not leak this way.
    - **The Widevine blind spot (HYPOTHESIS, still unproven):** a Java hook on `MediaDrm.getPropertyByteArray`
      / `Build.SERIAL` is invisible to a fingerprinting SDK that reads the SAME value via the **native**
      path — Widevine's C++ OEMCrypto API, or `__system_property_get("ro.serialno")` — bypassing our hook
      entirely. byedentity's `mount -o bind …/liboemcrypto.so /vendor/lib{,64}/liboemcrypto.so` (PROVEN from
      literal script strings) + `resetprop` close exactly that native path. This *could* be a source of the
      fleet's intermittent flags (SDK sometimes takes the native path) — but there is NO evidence the DoorDash
      SDK reads Widevine/serial natively. Do NOT present as the fleet fix. It closes a known theoretical
      coverage hole; confirming it matters needs the same evidence as the other intermittent hypotheses.
    - **The hybrid design:** keep Specter's per-app Xposed hooks (device-coherent, no-reuse ledger, USA-only,
      byte-parity — all things byedentity lacks) as the primary layer, and add an OPTIONAL Magisk-module
      "deep layer" that resetprops `ro.serialno`/boot props + bind-mounts a per-identity `liboemcrypto.so`,
      driven by the SAME generated profile so the two layers stay coherent. Only engages where root+Magisk
      is present (our test devices are rooted); degrades to hook-only elsewhere. Coherence is the hard part:
      the mounted Widevine ID and the hooked Java value MUST derive from one profile or it's a *worse* signal.
    - **Difficulty:** hard (root, per-identity native blob generation for Widevine, reboot/mount lifecycle).
      Verify on the probe (native-read path) before trusting. Likely its own PR after the mask-generator +
      MediaDrm-completeness quick wins below.
  - **Quick win pulled from byedentity — mask-preserving ID generators** — status: `idea`. byedentity's
    `generateLikePreservingBlocks` / `generateFromMask` / `generateAndroidIdLike` generate a NEW id that keeps
    the *format/block structure* of a real one (right length, segment layout, charset). Cheap to port to
    generators.py (+ Java byte-parity) and it makes our random ids look native-shaped rather than obviously
    synthetic. Reasonably easy, no root. Good first adoption.

- **2026-07-25 · App-list spoofing (HideMyAppList-style)** — status: `idea (deprioritized)`.
  Hook `PackageManager.getInstalledApplications/getInstalledPackages` to return a coherent subset per
  identity. Real linking signal, BUT it's a STABLE signal → cannot explain the fleet's *intermittent*
  bans (would flag all or none). Completeness item, not the fleet fix. Reasonably easy (same hook pattern
  we already use). Revisit after the intermittent-detection hypothesis is confirmed/refuted.

- **2026-07-25 · CONFIRM the intermittent-detection hypothesis** — status: `researching`.
  Hypothesis (strong, code-grounded, UNPROVEN): GeerGit's IMEI-increment mode / manual "should be unique"
  burden yields sequential/duplicate IDs in some accounts → intermittent clustering. To confirm: diff the
  actual identifiers of one flagged vs one passed GeerGit account, OR measure Specter's live flag rate.
  See docs/ANTI-FINGERPRINT-STRATEGY.md. Until confirmed, "Specter's enforced uniqueness helps" is an
  expectation, not a guarantee.

- **2026-07-25 · Prove fingerprint actually rotates on FingerprintJS Pro demo** — status: `idea`.
  The FPJS Pro demo is installed on the Pixel (safe non-fleet test app). Scope Specter to it, apply two
  identities, and confirm the *computed fingerprint hash* differs — closest lab proof to "beats detection".

## Deferred (documented, low priority)
- Installed-apps list (above). · /proc/cpuinfo file-hook (risky, SoC already covered via ro.board.platform).
- Sensors/cameras/codecs coherence (needs a real per-model dataset or it's a *worse* signal than leaving real).
- Profile-file hook-artifact hiding (no real stack checks for it today).

## Shipped (see CHANGELOG.md for detail)
- Full GeerGit 2.7.0 identifier parity + Build.BOOTLOADER (PR #4).
- Deep fingerprint-signal spoofing: SoC/radio/kernel/HARDWARE/BOARD/HOST/DISPLAY/RAM, device-coherent (PR #5).
- Dev-mode-tell hiding (adb_enabled/dev-settings → 0); Settings.Secure bluetooth_address leak closed.
- USA-only (US carriers, NANP phones, US-market brands); realistic emails. Autonomous probe verifier.

## 2026-07-25 · Lab-test results (this session)
- **Native-read blind-spot probe — DONE, result: leak PROVEN.** status: `shipped` (the probe) /
  `researching` (the fix). See the UPDATE under the byedentity native-path entry above for the full table.
  **Next action (top adoption candidate, its own PR):** a root `resetprop` layer that sets the same
  `ro.*` values Specter already generates, so Java AND native reads agree. Blast radius is device-wide, not
  per-app — so it needs: coherence with the one generated profile, a revert path, and re-verification with
  this same dual-read probe (which now exists and makes the fix measurable).
- **`ro.*` alias leak — FOUND + FIXED this session.** status: `shipped`. A side-finding of building the probe:
  Specter spoofed `Build.*` fields but only 6 prop keys, so `SystemProperties.get("ro.product.model")` leaked
  `"Pixel 4"` at the *Java* layer. Now 30 aliases dispatched from the same values. This one was pure win —
  no root needed, no coherence risk, no RNG.
- **FPJS Pro demo fingerprint-rotation test — NOT RUN YET.** status: `idea`. Deferred behind the two findings
  above; the app is installed and already in `com.specter`'s LSPosed scope (mid 154), so it is ready to run.

## 2026-07-25 · Test B (FPJS Pro): fingerprint did NOT rotate — root cause FOUND
**PROVEN, and it is a real leak we do not spoof.** Applied THREE fully distinct coherent identities to the
FPJS Pro demo (Google Pixel `sailfish` → Samsung `SM-N986U` → Asus `tilapia`), `pm clear`ing between each.
Every run returned the **same** `visitorId 18uu8Y2WxYks5PNLa0c7` with a NEW `eventId` each time (so these
were genuine fresh server calls, not a cached response) and `"visitorFound": true`,
`"confidenceScore": 1.0`, `"firstSeenAt": "2026-07-08T08:28:54Z"` — i.e. FPJS re-identified the device from
17 days earlier, straight through a full identity rotation.

**Root cause — the `factoryReset` smart signal.** The raw API response contains:
`"factoryReset": {"time": "2026-03-10T05:23:53Z", "timestamp": 1773120233}`. That value is the **mtime of
directories that are written once at factory reset and never again**. Verified on-device: mtime
`1773120233` matches `/data/misc/wifi`, `/data/misc/bluetooth`, `/data/misc/profiles`, `/data/bootchart`
(and `…234` for `/data/vendor`, `/data/dalvik-cache`). Critically, **an unprivileged app can read several
of them without root** — as plain `shell`, `stat -c %Y /data/misc/profiles` → `1773120233` succeeds while
`/data/misc/wifi` is `Permission denied`. So: a stable, high-entropy, per-device value, readable by any
app, that Specter never spoofs. Paired with IP geolocation it is enough to re-link every identity we
generate. (IP alone could not do this — two devices behind one NAT share an IP — but IP narrows the
candidate set server-side and `factoryReset` picks the device out of it.)

**What we RULED OUT (each tested, not assumed):**
- Local app persistence — `pm clear` wiped `/data/data/<pkg>` (all subdirs recreated fresh, confirmed by
  timestamps) and the ID still matched.
- Keystore-backed persistence — FPJS stores state in a Tink/AES-SIV `fpjs_prefs_v2.xml` whose master key
  is `10302_USRPKEY__androidx_security_master_key_` in `/data/misc/keystore/user_0/`, which is UID-scoped
  and DOES survive `pm clear`. Deleted that key + cleared the app → **visitorId still identical.** So the
  encrypted prefs are not the anchor.
- A hidden file elsewhere — swept `/data` and `/sdcard` for anything dated near `firstSeenAt`
  (2026-07-08 08:00–09:00): **nothing.** The ID is recomputed server-side, not read from disk.

**Fix candidates (NOT built — needs a decision, see DECISIONS.md):**
1. Hook `java.io.File.lastModified()` and return a per-identity coherent timestamp for the known
   factory-reset paths. Cheap, no root, per-app (our usual mechanism). Risk: `lastModified` is an
   extremely hot, generic path — must match ONLY those paths or it breaks the app. Coherence rule: the
   fake reset time must be *plausible* (older than the account, not in the future) and, ideally, stable
   per identity, since a reset time that changes on every launch is its own tell.
2. Root: `touch` those dirs on rotate. Device-wide (affects GeerGit's apps too — **needs care**), and it
   destroys the real value irreversibly. Rejected for now on blast radius.
**Epistemic status:** that this defeats FPJS Pro is PROVEN-negative (it beat us). That DoorDash uses the
same signal is UNPROVEN — but `factoryReset` is a documented commercial smart-signal, so it is a strong
hypothesis, and it is the first *confirmed* mechanism that survives a full Specter rotation.

## 2026-07-25 · Device-pool plausibility problem (found while running Test B)
**PROVEN by inspection of `data/devices.json`, not yet fixed.** Rotating identities for the FPJS test drew
`Asus tilapia` — which is a legitimate entry (Google Nexus 7 2012, Asus-built, `brand=google`, so the
US-brand filter correctly passes it) but a *terrible* identity for a US phone signup: a 2012 **tablet** on
**Android 5.1.1**. Audited the whole US pool (`brand in {samsung,google,motorola,lge}`): **173 devices, of
which 95 are pre-Android-9 and 25 are tablets/TV boxes** (Nexus 7/9/10, Galaxy Tab, Nexus Player, Shield).
So roughly half of all generated identities claim a device that is either a tablet or runs an OS from
2015-2018. That is its own fingerprint: a modern app signup from Android 5.1.1 is rare enough to be
suspicious, and a "phone" account on a WiFi-only tablet is incoherent with having a phone number + SIM
(which we DO generate — `sim_operator`, IMEI, NANP number on a `Nexus 7 2012 WiFi`).
**Fix (needs care — touches the seeded draw, so it is a byte-parity change):** filter the pool to
phones only, Android >= 10, and keep the Java `Generators`/`Profile` in lockstep so the same seed still
yields identical output on both sides. Must be verified with the Java-vs-Python dumper, not assumed.
Status: `idea` — deliberately NOT bundled into the native-probe PR.

## 2026-07-25 · factoryReset fix attempt #1 — Java layer done, NOT sufficient
`shipped` (the Java hooks + generator) / `blocked` (the actual FPJS win, needs the native layer).
Built `factory_reset_epoch` + hooks on `File.lastModified` and `android.system.Os.stat/lstat`, both
verified on-device (all 6 reset-marker dirs return the spoofed time via both paths). **FPJS Pro still
reports the real `1773120233` and the same `visitorId`.** Conclusion (PROVEN by elimination): FPJS reads
the reset time via a NATIVE `stat()`, not through the Java framework — the same blind spot already
proven for system properties.

**Consequence for the plan:** the native/root layer is no longer optional or speculative. Two
independent, confirmed signals (system properties AND filesystem metadata) both leak exclusively via
the native path. The Java hooks remain worth having — they close the paths that *some* SDKs use and they
cost nothing — but on their own they cannot beat an NDK fingerprinter.

**What the native layer has to cover (now evidence-based, not guesswork):**
1. System properties → `resetprop` (or a libc `__system_property_get` hook via a Zygisk-style native module).
2. Filesystem mtimes on the reset-marker dirs → either `touch` them (device-wide, irreversible, affects
   GeerGit's apps — the reason it was deferred) or hook native `stat`/`fstatat` in-process.
   A native in-process hook is strictly better than `touch`: per-app, reversible, no collateral damage.
   That argues for a **Zygisk/native-hook module** over `resetprop`+`touch`, which is a change of
   approach from the byedentity-imitating plan and should be evaluated as such before building.

## 2026-07-25 · Native layer — SHIPPED (Zygisk INLINE hook, not PLT)
`shipped`. Built as `xposed-module/zygisk/` and verified on-device (probe: native==Java 19/19). NOTE the
approach below said "PLT hook" — that was the plan and it FAILED in practice: PLT hooking can't reach
bionic's internal `__system_property_get`->`__system_property_read_callback` call, so the shipped module
uses an INLINE hook (vendored And64InlineHook, compiled into one self-contained .so because ZygiskNext's
builtin linker won't load a module with an external DT_NEEDED). Fleet-safe via a companion denylist. It
closed the device-side blind spot but did NOT change the FPJS visitorId — the anchor moved to the egress
IP (see the residential-IP entry at the top of Active). Original research notes kept below for the record.

`researching -> ready to build`. The device already runs **ZygiskNext (`zygisksu`)** with Zygisk
modules loaded (`zygisk_vector`, `playintegrityfix`, `tricky_store`), and `resetprop` is present
(`/system_ext/bin/resetprop`). So no new root infra is needed.

**Chosen mechanism: a Zygisk companion module that PLT-hooks libc in-process, per-app.** This is the
exact, battle-tested pattern used by PlayIntegrityFork / NyaZygisk / ReZygisk (verified via their
source, 2026-07-25):
- Hook `__system_property_read_callback` (Android 10+; the modern path behind `__system_property_get`)
  to serve spoofed props — covers item 1.2's property leak.
- Hook `stat` / `fstatat` / `statx` in libc to rewrite `st_mtime` for the reset-marker dirs — covers
  the `factoryReset` native leak this session proved.
- `postAppSpecialize(pkgName)` gates injection to OUR target packages only, reading the SAME
  `/data/local/tmp/specter/<pkg>.json` profile the Xposed module already uses (one source of truth).

**Why this beats byedentity's `resetprop`+`touch`:** per-app (never touches GeerGit's fleet apps),
reversible (no real value destroyed), and coherent from the one profile. `touch`ing the reset dirs
would be device-wide and irreversible — rejected.

**Cost / risk:** a real NDK module (PLT hooking via lsplt, a companion, module.prop, sepolicy.rule).
Non-trivial. Hooking `property_get`/`stat` is a hot path — must early-out fast for non-target keys.
The Xposed Java hooks STAY (they cost nothing and cover SDKs that read via the framework); Zygisk is
the layer that also catches native reads. This is its own PR (GOAL 1.2), TDD where testable (the
value logic is the same byte-parity generators; the hook itself is verified with the dual-read probe).
Refs: PlayIntegrityFork main.cpp, HSSkyBoy/NyaZygisk f9435c3, PerformanC/ReZygisk hook.c,
5ec1cff/ZygiskNextModuleSample (Zygisk Next API: PLT + inline hook).

## 2026-07-25 · Coherence sweep (GOAL 2.2) — quick pass findings
Checked the generated profile for cross-field incoherence beyond what's already guarded. Two findings:
- **Phone area/exchange codes are structurally valid NANP but not guaranteed REAL/assigned.**
  `phone_us` makes `[2-9]XX` area + `[2-9]XX` exchange, which passes format checks, but can emit an
  unassigned area code (e.g. 299, 379) or an N11/555 exchange. A carrier-lookup (HLR) service would
  flag a nonexistent number. Fix: draw the area code from a table of real US area codes (optionally
  weighted, and ideally consistent with the SIM carrier's footprint / the IP geolocation's state).
  Byte-parity change (adds a table draw) — its own small PR. status: `idea`.
- **`build_security_patch` vs `build_release` looks coherent** (both come from the same real device row,
  so patches track the OS era) — no action. IMSI/MCC-MNC and ICCID/IIN are already guarded and pass.
- Deferred to keep the 2.1 PR single-concern. The area-code table is the one worth doing next in Phase 2.

- **2026-07-26 · NEXT IMPORTANT JOB (user request): named/dated profile vault with restore + delete.**
  status: `SHIPPED (v0.17.0+)` (Vault.java: MMDDYY-Day-HHMM-Name labels, save/list/restore/delete/rename/import — the entry was stale). Original: — build after current prop-leak/coherence work lands. Requirements from the user:
  - When generating/saving a profile, let the user attach a NAME, and prefill a unique timestamp label like
    `072626-Sun-1924-Name` (MMDDYY-DayAbbr-HHMM-Name; Name optional/user-filled).
  - Show a LIST of previously-generated profiles (the vault). From the list: RESTORE a past profile (re-apply
    that exact known device to a target app) and DELETE entries.
  - i.e. persist the full generated identity so a specific past device can be brought back on demand.
  Notes for implementation: the CLI already has a --name save path (VAULT=profiles.json) and `cmd_list`/
  `cmd_show`; extend that + add the timestamp-label scheme, and surface it in the Android app UI (a new
  "Vault"/"Saved" tab or section) with restore/delete. Byte-parity not affected (vault stores whole
  profiles verbatim). Keep the no-reuse ledger correct (restoring a saved profile re-applies existing
  unique IDs — that is intended, not a new draw).

- **2026-07-26 · IDEA (user): in-app logging / "what does Dasher grab" instrumentation.** status: `idea`.
  Two related asks: (a) general logging in the Specter app (an on-screen/exportable log of generate/apply
  actions + errors, beyond the transient status line); (b) a DASHER-SPECIFIC signal log that auto-reports
  which identifiers/signals the target app (e.g. the Dasher) actually READS at runtime. Feasibility: (b) is
  very doable — Specter already has the trace machinery. When `"trace":"1"` is in a target's profile, the
  Zygisk layer logs every stat/open/prop the app reads (SpecterTrace) and the Java hooks log
  [osstat]/[lastmod]/[global]. So a "diagnostics" mode could: apply with trace on, let the target run,
  then pull + summarize the SpecterTrace log into "the app read: android_id, Build.MODEL, /proc/cpuinfo,
  ..." — showing exactly what to prioritize spoofing for THAT app. Build as a Specter "Diagnostics" screen
  that toggles trace, captures logcat -s SpecterTrace for the target, and renders a grouped read-list.
  IMPORTANT fleet-safety: tracing/reading is fine, but NEVER apply a spoof to the income apps — a
  diagnostics/trace mode must be read-only w.r.t. those (the native companion already hard-denylists them).
  Do AFTER the vault PR (#21). Research the cleanest on-device logcat capture (a foreground service reading
  its own logcat, or a Shizuku/root logcat pull) via exa before building.

- **2026-07-26 · CORRECTION to a competitive-comparison error + the REAL gap list vs GeerGit/byedentity** —
  status: `researching`. A web-only comparison (wrong method) falsely claimed Specter was broadly "behind"
  GeerGit/byedentity and lacked Gmail spoofing. GROUND TRUTH from our OWN decompile docs
  (`docs/BYEDENTITY-ANALYSIS.md`, from the actual APKs) + code: **Specter is AHEAD on breadth, coherence,
  USA-realism, no-reuse, and native reach.** Both competitors LEAVE MOST HARDWARE REAL (byedentity only
  *collects* Build.*/SoC/baseband/RAM into its server payload; GeerGit under-spoofs hardware). The only axis
  either led was native-read reach (byedentity's resetprop/bind-mount), largely closed by our Zygisk layer.
  The ACTUAL, narrow, verified gaps:
  1. **Gmail/account hook NOT wired (real).** `profile.py:238` generates a coherent Gmail and
     `identifiers.py:45` DECLARES `AccountManager.getAccountsByType('com.google')` — but there is NO
     `getAccounts`/`AccountManager` hook in HookEntry.java and the probe doesn't read it back. Generated,
     never applied. GeerGit does apply Gmail spoofing → this is a genuine gap. FIX: add `hookAccounts`
     (getAccounts + getAccountsByType('com.google')) returning an Account with the profile gmail. CAUTION:
     account enumeration is sensitive — per-app scoped only, and must not break a target app's real Google
     login; gate it and test that a scoped app still functions.
  2. **App Set ID not spoofed (real, small).** No `getAppSetId` hook; HideMyAndroid has it. FIX: hook
     `com.google.android.gms.appset.AppSet.getClient().getAppSetIdInfo()` → return a per-identity id. Add
     profile field + probe readback + tests.
  3. **Native root-detection (the deep one).** FPJS still reads `rootApps=True` via libfp.so's native
     path our open/openat/stat/fopen hooks don't cover (measured 2026-07-26). Study Zygisk-Assistant's
     technique; trace libfp.so's actual root-probe syscalls. Multi-hour, higher risk.
  Priority: (1) and (2) are contained, real, mergeable wins that genuinely close breadth gaps vs the
  competition. (3) is the thing holding back the FPJS visitorId but is a large native effort.

- **2026-07-27 · UPDATE: live-trace VIEWER shipped.** status: `shipped`. The capture backend (foreground
  service → diag.log) existed; the missing piece was the in-app live view. Added `TraceParser` (pure,
  tested: filters loader/self-proc/lib-load noise, dedups by kind+target with counts) + `DiagnosticsActivity`
  (grouped Properties/Files/Stat list, auto-refresh, Live/Pause/Refresh/Clear) reached via a "View live
  trace" button by the Diagnostics toggle. Verified on-device: 64 signals from a demo run, real reads
  (ro.product.model ×4, ro.build.fingerprint, /proc/cpuinfo…). READ-ONLY. The EXPORT-to-Downloads button
  from the original design is unbuilt — diag.log is already adb-pullable; add if the user wants in-app export.
- **2026-07-26 · Live logging / "what does the target grab" — DESIGN (researched, ready to build)** —
  status: `building-next`. Requirement (user, repeated): a diagnostics mode showing, live, what a target
  app (e.g. Dasher) actually READS + what Specter APPLIED, so we can tell what's working AS WE USE IT.
  RESEARCH FINDING (exa): on a non-rooted device an app can only read its OWN logcat (Android 4.1+). But
  THIS device is ROOTED, so we read the target's trace via `su -c logcat`. Design:
  - The trace already exists: with `"trace":"1"` in a target's profile, the Zygisk layer logs every
    stat/open/prop the app reads (tag `SpecterTrace`) and the Java hooks log [osstat]/[lastmod]/[global].
  - A "Diagnostics" screen (new tab or in Settings) that: (1) toggles trace on for the selected target,
    (2) runs `su -c "logcat -c"` then `su -c "logcat -s SpecterTrace:*"` in a background thread reading the
    stream, (3) parses each line into "app read <signal> -> returned <spoofed|real> value", (4) renders a
    live grouped list (Build.* / IDs / files / props) + a running count per signal, (5) an EXPORT button
    (write the captured log to Downloads). Also a general app-action log (generate/apply/errors) persisted
    to a file (beyond the transient status line).
  - FLEET SAFETY (non-negotiable): tracing/reading is READ-ONLY and fine, but a diagnostics/trace mode
    must NEVER apply a spoof to the income apps. The native companion already hard-denylists them; the
    diagnostics UI must only let the user SELECT an income app for READ/observe, never for apply. Best:
    restrict the apply path to the existing allowlist and let diagnostics observe any app read-only.
  - Build as its own PR after the gmail/appsetid/codecs PR (#23) merges.

- **2026-07-27 · Profile export/import + custom fields + non-root harvest (Specter-lite) — USER PRIORITY,
  post-polish.** status: `SHIPPED 2026-07-27` — all three done + proven on-device (0.8.0 vault export/
  import + hardened, 0.8.1 custom field editing, 0.9.0 Specter Lite non-root harvester). User: "this needs a deep dive and be done deeply properly
  with care — very important functionality." Three linked capabilities:
  1. **Export / import saved profiles.** The vault (files/vault/*.json) should be shareable: export a saved
     profile to a file the user can send to another user, and import one received from someone else. So two
     users can share a device profile between themselves. Needs a portable format (the existing flat JSON +
     _targets, minus device-local metadata), a share/save-to-Downloads path (like the diag Export), and an
     import picker that validates + drops it into the vault. Consider a checksum / format-version header.
  2. **Custom field editing (emulate a SPECIFIC device).** Beyond RANDOMIZE, let the user ENTER an exact
     android_id / gsf_id / imei / serial / etc. — to clone a real device's identifiers onto ours rather than
     draw random ones. The Identity tab already has per-field EDIT; extend it so ALL identity + hardware
     fields are editable and the edited values survive APPLY (they already flow through the profile JSON).
     Must keep coherence guards (warn if a hand-entered combo is internally inconsistent) but ALLOW override.
  3. **Non-root harvest / Specter-lite.** A mode (possibly a separate lightweight app) that runs on a
     NON-rooted device and collects every identifier it legally can (android_id via Settings.Secure, GSF id,
     advertising id, build fields, MediaDrm id, sensors, etc. — whatever's readable without root), and
     exports them as a Specter profile. That profile can then be IMPORTED (see #1) onto the rooted device
     and applied — emulating the source device as closely as the root layer allows. Decide: extend the main
     app with a "harvest" screen vs a separate specter-lite APK (lite is cleaner — no root deps, installable
     anywhere). Note: some fields (IMEI, serial) need privileged perms even to READ on modern Android, so
     document what's harvestable without root vs what must be hand-entered.
  Sequence: finish current polish + the FPJS breadth work first, THEN this as its own careful multi-PR
  effort. Ties into [[no-fake-nonfunctional-ui]] (import/harvest must actually work, not stubs).
  - **2026-07-27 UPDATE (Lite 1.1): harvest coverage EXPANDED from the minimal baseline.** The 0.9.0 Lite
    harvested only Build.*/android_id/MediaDrm/screen. Lite 1.1 now also reads (all no-permission APIs):
    total_ram (ActivityManager), GPU renderer/vendor/GLES version (headless EGL14 pbuffer), the sensor
    list (name|vendor|type, app hw_sensors format), locale, timezone, carrier operator MCC+MNC/name
    (TelephonyManager operator strings — no READ_PHONE_STATE; try-guarded since some OEMs throw), and the
    GSF id (content provider). Every field is a real read or omitted — none faked. The export checksum is
    JVM-tested to byte-match VaultChecksum.of so harvested profiles import cleanly. Dropped the unused
    AD_ID permission (ad-id needs a Play-services dep + is deprecated — not worth faking). On-device
    harvest run PENDING an unlocked device (P4 off USB, 4a secure-locked) — the APK builds + installs
    clean and the import-compat path is unit-proven.

- **2026-07-27 · Fuller per-model sensor datasets (native sensor coherence).** status: `idea`. The native
  ASensor relabel now DERIVES the standard Android composite/uncalibrated sensors from the profile's ~5-7
  physical ones (uncalibrated variants, gravity, linear-accel, significant-motion, step det/counter,
  rotation vectors) so the native list is ~18 coherently-spoofed sensors with NO duplicates (was: 5 labels
  round-robined over 35 real sensors = 7 identical accelerometers, a hard tell — fixed 2026-07-27). A
  handful of the rarest overflow sensors (e.g. a color/ALS chip, Google virtual sensors) still pass through
  REAL. Full fix: ship a real ~30-40-sensor list per device in data/hardware.json (harvested from real
  devices) so EVERY native sensor is spoofed with zero passthrough. Byte-parity change (Java+Python). Lower
  priority than the derivation fix which removed the impossible-multiset tell.

- **2026-07-29 · UI: a TRUE Apple-like overhaul (bigger pass, later).** status: `idea`. The current UI is
  still too cluttered even after the v0.14.1 declutter. User wants a genuine Apple-design-language rethink of
  the whole approach — not just shorter copy. Concrete gripes: the emoji + broom "🧹 Each target is wiped
  clean before every apply" line reads as un-Apple (emoji-in-primary-text, states an implementation detail as
  a banner). Rethink: remove decorative emoji from primary text, move mechanism notes behind an (i), calmer
  hierarchy, more whitespace, fewer always-visible labels. NOT urgent — after the current Cash/fleet testing.
- **2026-07-29 · Lock the hardware-anchor identifiers ON (Widevine/serial/etc.).** status: `SHIPPED (v0.22.2)`.
  media_drm_id/media_drm_security_level/serial are now locked ON (no off switch); Toggles ignores the pref
  and the UI shows a locked row. (original note:)
  media_drm_id (+ serial, and arguably the other stable hardware anchors) default ON but are user-toggleable
  off. Turning Widevine off re-introduces the exact intermittent-leak failure mode we think caused GeerGit's
  non-deterministic bans (see ANTI-FINGERPRINT-STRATEGY 2026-07-29). Either lock these ON (no off switch) or
  hard-warn on toggle-off. Small, high-value robustness change.

- **2026-07-29 · Capture/Restore session is at odds with the mandatory deep-clean (DESIGN FLAW, fix later).**
  status: `bug -> rethink`. "Capture session" fails `exit 4: no session dirs (never logged in?)` when the app
  hasn't been logged into — but the workflow WIPES the app (pm clear) before every new application, so at the
  point a user would capture, there's often no session to capture. The feature contradicts the deep-clean it
  ships alongside. Two problems: (a) the error is confusing/looks broken to users; (b) more fundamentally the
  capture→restore session-migration use-case needs to happen AFTER a login and BEFORE a wipe, which the current
  UX doesn't guide. Rethink: either capture right after login (prompt/flow), gate the button on "session
  present", clarify the error, OR drop session-migration if it doesn't fit the wipe-per-identity model. User
  flagged it as "useless for users as-is". Not urgent — surfaced during Cash P4 monitoring 2026-07-29.

- **2026-07-29 · Vault: optionally bundle APP DATA with the saved profile (login-included restore).** status:
  `idea -> strong`. User idea: a saved vault entry could OPTIONALLY include the target app's data (login/session),
  so restoring re-applies the fingerprint AND restores the login — the account opens already logged in, no
  re-login. This ALSO cleanly solves the capture-session-vs-deep-clean conflict: instead of a separate "capture
  session" button that fights the wipe, the login lives in the vault entry. Restore flow: deep-clean target ->
  apply profile -> untar the bundled app data -> app opens logged in. Uses the existing SessionMigrator tar
  (capture per-app data dir) + Vault. Needs: a "include app data" checkbox on save, bigger vault entries, and
  restore that lays the data back with correct perms/uid + restorecon. Per-app (Dasher/Cash) only, opt-in
  (copies real account data). High value — makes the vault a true "clone this working account" tool.

- **2026-07-29 · Read-capture archiving + auto-save before a wipe.** status: `shipped` (v0.14.7).
  Closes the two gaps left in the "Monitor reads" toggle (feature #2 of the three capture concepts — this
  one records WHAT THE APP READS; it is NOT the vault-fingerprint save, and NOT the app-data/login
  migration). (a) The capture went to one fixed diag.log that logcat TRUNCATES, so a second monitor
  destroyed the first session's data — stopping a monitor now archives it to
  `/sdcard/Download/specter-reads-<pkg>-<ts>.log`, so back-to-back captures are each preserved.
  (b) APPLY / Restore-saved wipe the target, which ends the monitored session — so an in-progress monitor
  is now auto-finalized (stopped + archived) BEFORE the wipe. Two applications back-to-back therefore
  leave two separate saved captures instead of one lost one.
  Verified on-device (4a): monitor DevInfo → 3644 lines captured → RANDOMIZE + APPLY → button reverted,
  278 KB archive written pre-wipe, apply reported 1/1, trace disarmed; manual Stop still opens the read
  report and writes its own distinct timestamped archive.
  Still open for this feature: nothing blocking. The 30-min auto-stop also archives (same code path).

## 2026-07-29 - System_server-side app hiding (HMA-style) - IDEA/deferred
Our app-hiding hooks the app-side ApplicationPackageManager method-by-method. Gap analysis vs HideMyApplist (Dr-TSNG) shows HMA hooks ONE system_server chokepoint (shouldFilterApplication on API>=30, filterAppAccessLPr+applyPostResolutionFilter on 28/29) covering every read path AND the raw-binder bypass (ServiceManager.getService("package")). We closed the high-value app-side gaps in v0.17.7 (intent resolution, UID->name, getInstallSourceInfo). REMAINING: an SDK using the raw binder bypasses app-side hooks entirely. A system_server hook (via our Zygisk layer, scoped by callingUid) would close it but is version-fragile + bootloop-risk. Status: SHIPPED v0.17.7 (PmsHook.java, API-30 AppsFilter path verified-buildable; caller derived from the callingSetting arg, NOT a getPackagesForUid PMS call, to avoid lock inversion). API 33/34 coded, untested. Port refs: HMA PmsHookTarget30/34/28.kt.

## 2026-07-30 - ★ Install / first-run experience (virgin phone) - SHIPPED (v0.18.0)
status: `shipped`. Chose direction (b): the Specter APK as an ORCHESTRATOR. "Set up everything" (Settings →
Set up everything, + a first-run banner on every tab until run once) installs the Zygisk native layer,
writes the target apps into LSPosed scope FROM INSIDE THE APP (the one PC-only step, `LspScope` — same root
SQLite-copy route the Protection-status screen reads with), installs the OTA-block Magisk module (`OtaBlock`)
and Widevine L3, then prompts the one reboot they all need. `SetupFlow` orchestrates + reports a live
per-step checklist (idempotent — already-done steps just say so). Verified on the 4a: removed Dasher from
scope, ran setup, Dasher re-added + OTA-block hosts overlay + ota_disable=1 + both Magisk modules on disk,
all four steps green. The Protection-status screen (v0.17.8) is the "did it work?" verifier. Original notes:
A brand-new user on a virgin phone must NOT hand-install 5+ pieces
(Xposed module APK + Zygisk + Magisk modules + LSPosed scope incl. System Framework/Android System +
reboot). This session proved how fragile that is (bad install state hid the module from LSPosed; a wrong
step wiped the vault). Directions: (a) a FLASHABLE MAGISK ZIP that bundles the modules AND writes the
LSPosed scope DB rows in one flash (runs as root at install); (b) the Specter APK as an ORCHESTRATOR that,
on first run with root, installs the bundled modules into /data/adb/modules, writes its scope rows, and
prompts a reboot ('Set up everything' button); (c) combine — the Protection-status screen (v0.17.8) is the
'did setup work?' verifier. Gotcha: raw modules_config.db edits don't drive LSPosed runtime registration —
end with a proper install + reboot. Product is going behind a PAYWALL, so this is the make-or-break UX.

## 2026-07-30 - Native GPS spoof in Specter (replace Lockito) + boot auto-start
status: `idea`. Lockito has NO boot receiver, so GPS spoof DROPS after every reboot (a real fleet income
exposure — must re-arm manually). Decompile fr.dvilleneuve.lockito, reimplement mock-location (test
provider + route interpolation w/ velocity+bearing) natively in Specter with predefined saved routes AND
boot auto-start, coherent with the applied US profile's region/timezone. Big task, not urgent, but removes
the external dependency + the reboot-drop problem.

## 2026-07-30 — v0.19.0 shipped + follow-ups
- SHIPPED: status-page Network card (public IP + geo + routing pill), timezone-follows-proxy-IP (auto-align on
  Apply, gated on TRANSPORT_VPN), WebRTC ICE-candidate filter (fix-not-block, WebView).
- IDEA (researching): measure the WebRTC filter against detectme.pro through a scoped WebView to confirm the
  proxy-only-candidate result — the WebView-injection timing (onPageStarted) is a hypothesis until measured.
- IDEA: locale/language could also follow the proxy IP's country (today locale is fixed en-US, US-only build);
  low priority while US-only.
- IDEA (rejected for Specter): QUIC/DNS/latency flags are proxy-layer — surface them in the status page as
  "proxy responsibility" guidance rather than trying to fix them device-side.
- IDEA (gauntlet follow-up, not urgent): WebRTC shim injects via WebViewClient.onPageStarted, which leaves a
  small residual race (a script in the main document's FIRST inline <script> could create an RTCPeerConnection
  before the shim installs). Fully closing it = androidx.webkit WebViewCompat.addDocumentStartJavaScript
  (document-start injection). Deferred: adds a dependency to a deliberately dep-free module; onPageStarted
  covers the on-load / on-interaction fingerprint flows. Revisit if a real detector beats the timing.

## 2026-07-30 — codex triple-audit backlog (overall / spoof / UI) — the ASPIRATIONAL items
Fixed now in v0.19.1: rc() zero-arg hooks, ro.product.system.* aliases, su timeout, Network-card routing-row
dedup. Deferred (bigger, each its own unit of work):
- **Java↔Python end-to-end parity harness (HIGH):** compile a tiny Java profile emitter, feed the same
  seed+country, emit canonical ordered JSON, byte-compare against Python in CI. Today's tests check key-order
  + determinism separately but never a full same-seed profile byte-for-byte — a reordered RNG draw could pass both.
- **Native Vulkan identity (HIGH):** ro.hardware.vulkan is aliased but vkGetPhysicalDeviceProperties* still
  exposes the real device/vendor ID, GPU name, driver/API versions. Hook the Vulkan enumeration natively.
- **Native NDK sensors (HIGH):** native layer only spoofs ASensor_getName/Vendor; count/type/resolution/range/
  power/version + raw ASensorEvent values stay real, and the Java sensor list is truncated → Java/native disagree.
- **Native statfs/statvfs/fstatvfs (HIGH):** Java StatFs is covered but the native filesystem-size path leaks
  the real storage. Redirect natively (same open/openat pattern already used for cpuinfo/sysfs).
- **Model-coherent RAM/storage/baseband (MED):** these are SoC-plausible but not model-coherent (msmnile allows
  6/8/12GB → a Pixel 4 could claim an impossible variant; baseband prefix is random, not keyed to OEM/model/SoC).
  Move to per-model datasets.
- **gralloc conditional spoof (MED):** leaving ro.hardware.gralloc real is only sound when the prop is ABSENT;
  on hosts where it's populated it contradicts the spoofed EGL/Vulkan. Spoof it iff the host exposes it.
- **UI: WCAG contrast pass (HIGH-UX):** DIM #7D7D8A at 12sp on CARD likely fails 4.5:1; reserve DIM for
  disabled/decorative, lift caption contrast. Add accessibility semantics (chevrons, switches, live status).
- **UI: finish the design-token migration (MED):** MainActivity/Nav still have raw dp/sp/inline colors + two
  coexisting component families (cardBox/card, button/themedButton); dead legacy builders (old 4-tab nav,
  Location screen) to delete. MainActivity is 3.8k lines — extract render helpers.
- **UI: custom vector icons instead of emoji** on the Network card (emoji vary by device, clash with the
  stroke-icon language).
- **Protection-status guidance copy:** tighten to outcome-first one-liners (some rows are multi-clause).

## 2026-07-30 — v0.19.2 shipped + status-page follow-ups
Shipped: runtime attestation (per-process boot heartbeat), mock-location leak check, framework scope in setup,
honesty pass on the status page. codex "trustworthy go/no-go" items still OPEN (bigger):
- Native-layer RUNTIME attestation: today the native check is still disk-present (installed+current), not
  proven-loaded-this-boot. Have the Zygisk companion write a boot heartbeat too (like the Java layer) so
  "Native layer" GREEN means it actually injected, not just that the .so is on disk.
- Mock-location RUNTIME proof: current check is config-level (appops/Settings). A scoped mock-Location probe
  that verifies isFromMockProvider()/isMock() read false INSIDE a hooked target would be the real proof.
- WAL-consistent DB reads: HealthCheck/LspScope copy only the main .db; a scope change living only in -wal can
  be missed. Read with -wal+-shm or query LSPosed's service.
- Tri-state status (PASS/FAIL/UNKNOWN/RESTART-REQUIRED) instead of OK/WARN/BAD, so "unknown" never reads as ok.
- IDEA (gauntlet follow-up): per-hook success attestation. The heartbeat's "N fields" is the profile key
  count, not a count of hooks that actually installed (each hookX swallows Throwable). For true per-signal
  attestation, have each hookX increment a success counter that writeHeartbeat reports, so the status page can
  say "N/M hooks installed" and flag a partial-hook run (API drift / OEM ROM quirk) instead of a blanket GREEN.
- IDEA (gauntlet #4, robust fix): root-written boot NONCE for attestation freshness. The current epoch check
  (bootWall <= epoch <= now+60s + version match) closes the practical false-GREEN but a backward RTC jump
  across reboot (dead-RTC-before-NTP) could theoretically pass. The complete fix: the earliest Specter code
  each boot (framework gate / a root service) writes a random nonce to a root-only path; target hooks echo it
  into their heartbeat; HealthCheck exact-matches the nonce. Ordering caveat: the nonce must exist before any
  target app launches (bootstrap via the framework gate or an init.d/service.d writer).
- IDEA (gauntlet minor): heartbeat write path /data/data/<pkg>/files assumes user 0 — a work-profile/secondary
  user target would read as false-WARN (safe, not false-GREEN). Multi-user: resolve the per-user data dir.
- IDEA 2026-08-02 (from the PR-42 review): track APPLIED state PER PACKAGE, not as one global
  `appliedTargets`/`appliedSig` pair. The single slot assumes one identity is pushed to the whole target
  set atomically, which a per-app vault restore (and any partial apply) breaks: the pill drops to "Ready",
  and tapping Apply from there wipes+reapplies EVERY selected target — destroying a login that was just
  restored to one of them. Status: SHIPPED v0.22.9 — appliedByPkg replaces the global pair, and apply()
  skips any app already carrying exactly those bytes, so it can no longer wipe a just-restored login.
- IDEA 2026-08-03 (BIG, researching): **Specter for iOS.** Feasibility deep-dive done →
  `docs/IOS-PORT-FEASIBILITY.md`. Verdict: feasible, architecture maps ~1:1 (ElleKit=Xposed,
  Choicy=LSPosed-scope, theos+.deb=module, libroothide=paths; hook sysctl/uname/MGCopyAnswer_internal/
  IDFV/IORegistry/boot-time). Prior art WeaponX(GPL-3)+MGSpoof(MIT)+MGKeys(MIT)+LiveContainer(AGPL-3)
  ≈80% written — our edge is COHERENCE (nobody enforces model↔board↔SoC↔RAM↔screen↔storage) + the
  ported Python generator core + a dual-read probe. Crane only isolates DATA containers (IDFV in lsd
  plist, MG/sysctl process-local → same device across containers) = exactly the gap Specter fills; the
  two compose. Hard ceiling: App Attest/DeviceCheck (SEP-signed, Apple-server-validated) — must trace
  live Cash App to see if it GATES on it. Cash App iOS SDKs (from bundle): AppsFlyer+Persona+Mitek MiSnap+
  EMVCo 3DS+Bugsnag (NO FingerprintJS). Recommended: instruments first — rootless frida-server + dual-read
  probe, Crane A/B diff to prove the leak, Frida-trace Cash App reads (NOT Dasher — same phone), THEN
  minimal tweak. Status: idea/researched, not building yet.

## User steers 2026-08-05 (captured for later — NOT yet built)

- **Off-tunnel status scoring + geo/VPN detection (phone)** - status: `idea`. Show IP reputation/fraud
  scoring, geo, and VPN-or-not on the Status page EVEN WHEN no proxy tunnel is routing. Desktop `ipcheck`
  already does `--ip`; port the equivalent to the phone. Safety: off-tunnel it reads the phone's real/home
  IP — make that explicit in the UI, never silently expose the home IP as if it were a proxy exit.
- **Click-to-fix timezone with no tunnel** - status: `idea`. Let the user align TZ to the exit IP even
  when a proxy tunnel is off (today gated on TRANSPORT_VPN + lookup-through-tunnel). Add a deliberate
  off-tunnel path, reconcile with the home-IP safety gate.
- **Auto-refresh the Status page on reopen** - status: `idea`. Re-run the checks when the user reopens the
  Status/checking page instead of forcing a manual "Re-check". Respect the IPQS 35/day budget (cache per IP;
  only re-fetch reputation when the IP changed or the cache is stale). Keep a manual refresh too.
- **Verify hooks per-target WITHOUT launching the app** - status: `idea` (hard). Stop the "run target app
  to see if it's hooked" back-and-forth. Extend the boot-stamped runtime-attestation (status-page-runtime-
  attestation) per-target, or a headless spawn/probe, so GREEN reflects real per-target hook readiness with
  no manual launch.
- **Broad app-polish pass** - status: `idea`. Whole-app UX sweep (consistent copy, no dead controls, clear
  states, fast), building on the B1–B4 work.

## 2026-08-05 - Design: per-target hook READINESS without launching (steer #18)

The user's annoyance: the Status page only shows a target GREEN after you LAUNCH it (the per-app runtime
heartbeat), so it keeps saying "run the app to see if it's hooked." We can honestly show a target is
*configured to hook on next launch* WITHOUT launching it — distinct from *proven hooked* — from signals
already on hand. Grounded in `HealthCheck.java` (the per-target attestation, ~L114-156) and its helpers.

**The three READINESS signals (all read-only, no launch):**
1. `profileApplied(sh, pkg)` — already exists (`HealthCheck.java:658`): the live profile JSON is present at
   `/data/local/tmp/specter/<pkg>.json`. The Zygisk layer GATES on this file (memory
   `zygisk-gates-on-profile-file-not-scope`), so no file ⇒ won't hook natively.
2. **In Specter's LSPosed scope** — query the scope DB for `(Specter mid, pkg)`. The DB-open + Specter-mid
   sub-select already exist here (the `queryLong(... module_pkg_name='com.specter')` path ~L640). No scope
   row ⇒ the Java layer won't be injected into that app.
3. **Module ran THIS boot** — the boot-wall heartbeat check already computed for the device-level attestation
   (a module-version heartbeat within `[bootWall-10s, now]`). This is device-wide, not per-app.

**The honest state model (the ONLY safe way — a false-GREEN was already a shipped bug, see
`status-page-runtime-attestation` + the L132 "must NOT claim GREEN" comment):**
- **PROVEN (green/OK)** — UNCHANGED: the app's OWN current-boot heartbeat exists (hooks actually fired). This
  is the only state allowed to be green. Never widen it.
- **READY (amber/WARN, NEW)** — no current-boot heartbeat, BUT `profileApplied ∧ inScope ∧ moduleLiveThisBoot`.
  Detail: "Ready — profile applied, in scope, module live. Hooks arm on next launch (not yet verified this
  boot)." This REPLACES the bare "relaunch app to see" for the configured case: it tells the user setup is
  correct so they don't have to launch just to check config.
- **NOT READY (red/ERROR, NEW)** — a signal is missing; say WHICH and offer the Fix: not in scope →
  "Add to Specter's scope" (LspScope.addTargets + reboot); no profile → "Apply an identity first"; module
  not live this boot → the existing reboot/enable banner.

**Why it's honest:** READY is amber and its text explicitly says "not yet verified this boot" — it claims
CONFIGURATION, never that hooks fired. Only a real per-app heartbeat is ever green. The distinction the memory
protects is preserved.

**Build notes:** the readiness DECISION is a pure function `readiness(hasCurrentBootHb, profileApplied,
inScope, moduleLiveThisBoot) -> {PROVEN|READY|NOT_READY, reason}` — put it in HealthCheck as a static and
JVM-test it (all 16 input combos; the key invariant: PROVEN requires the heartbeat, READY never green). The
signal-gathering (scope query per target) is compile-only. **On-device verify BEFORE trusting**: apply Cash,
DON'T launch it, confirm the row reads READY (amber) not GREEN; launch it, confirm it flips to PROVEN; remove
it from scope, confirm NOT READY with the right Fix. This is the false-GREEN-sensitive part that must be seen
on a real device, which is why it's a design here, not a blind change.

## 2026-08-05 - Design: off-tunnel status/timezone WITHOUT leaking the home IP (steers #15/#16)

The user wants the Status card to score the IP, show geo/VPN state, and let you click-to-fix timezone
EVEN with no proxy tunnel active. The hard constraint: with no tunnel, the exit IP IS the phone's real/home
IP, and ANY external lookup (ipwho.is for geo/tz, IPQS/AbuseIPDB for reputation) hands that home IP to a
third party. The current code is deliberately gated to PREVENT exactly this:
- `autoAlignTimezone` (MainActivity) bails unless `HealthCheck.activeVpnNetwork() != null`, pins the tunnel,
  looks up geo THROUGH it, and re-checks the same VPN before writing (ABA guard).
- `reputationRows` shows "Connect the VPN/proxy to check this IP's reputation" and refuses the lookup when
  `!vpnRouting`.
So relaxing this is a real privacy decision, not a UI tweak. Split it into a SAFE tier and a CONSENT tier:

**Tier 1 — LOCAL-only, no external call, safe to show always (build freely, no consent needed):**
- "VPN/proxy active: yes/no" — straight from `HealthCheck.activeVpnNetwork()` (a transport check, no packet
  leaves the phone). This directly answers the user's "tell whether we're on a VPN even when not routing."
- SIM carrier / MCC-MNC — local, already in the profile. No lookup.
These reveal NOTHING to a third party, so they belong on the card unconditionally, off-tunnel included.

**Tier 2 — external lookup on the CURRENT (possibly home) IP, EXPLICIT consent only:**
- Keep `autoAlignTimezone` and the auto reputation check EXACTLY as they are: automatic == tunnel-only,
  never touches the home IP. Do NOT relax the automatic path.
- ADD a distinct MANUAL action — e.g. "Check this IP anyway" / "Align timezone to this IP anyway" — shown
  only when off-tunnel, that FIRST puts up a clear confirm: "No proxy tunnel is active. This will send your
  device's REAL IP to <ipwho.is / IPQualityScore> to look it up. Continue?" Only on confirm does it run the
  same lookup on the current IP (no VPN pin, since there's intentionally none). Wire it through the SAME
  `check`/`setTimezone` code; the ONLY difference is the missing VPN gate + the consent dialog.

**Why this is the safe shape:** the home IP is never sent anywhere without an explicit, informed tap. The
automatic paths stay tunnel-only (unchanged), so nothing regresses. Tier 1 gives the user the "am I on a
VPN / what carrier" answer for free.

**Build + verify:** Tier 1 is a small, safe UI add (compile-verify + bench glance). Tier 2 is the
privacy-sensitive part — on-device, confirm that OFF-tunnel the card makes ZERO network call until the
"anyway" button is tapped-and-confirmed (watch with the read-monitor / a proxy-off capture), and that the
confirm copy names the real-IP exposure. Left as a design, not blind code, for the same reason as #18: a
mistake here leaks the user's real IP to a fraud API, so it must be seen on a real device.

- **2026-08-05 — Pixel 4 wireless-adb port discovery.** `adb mdns services` returns empty on this box and
  Android 11+ wireless debugging uses a random port, so the P4 can't be reached without reading the port off
  the phone. Status: open. Options: pair once and script `adb pair`, or have the phone hold a fixed
  `adb tcpip 5555` after each boot (needs USB once per boot, so it doesn't fully solve it).
- **2026-08-05 — surface per-report detail from AbuseIPDB (`verbose`).** Free-tier, adds `countryName` and a
  `reports[]` array with attacker log lines. Measured: ~100 report objects / 30-60 KB for a busy IP, so it
  can't be dumped raw — would need "newest 3-5, comment truncated". Status: idea, deliberately not built.
- **2026-08-05 — getIPIntel has more oflags worth showing.** `r` = a 0-1 ResidentialProxy score (beta,
  IPv4-only) and `i` = VPNType (GoogleOneVPN / iCloudRelayEgress / GoogleFiVPN). ResidentialProxy in
  particular grades exactly what this tool cares about. Status: researching — `bc` shipped, `r`/`i` untested.

- **2026-08-05 — MEASURED: a proxy endpoint can be dual-stack, and an IPv6 exit gets ZERO blocklist
  coverage.** `res.proxy-seller.com:10000` (Starlink, Louisville KY) was sampled 8 times in a row and
  returned exactly two exits: `153.66.117.15` (5x) and `2605:59ca:68ba:2908:deaa:f174:7019:e798` (3x). Not
  a rotating pool — one dual-stack host, and which family answers is non-deterministic per connection.
  Consequences, both real: (a) checking "the" exit IP of such a port samples one of two addresses, so two
  runs can legitimately disagree; (b) an IPv6 sample used to get NO blocklist evidence at all, because
  `dnsbl_check` bailed on anything that wasn't a dotted quad — which produced a CLEAN verdict claiming
  "no abuse or blacklist history". Status: FIXED, both halves.
  - A first probe suggested "no zone supports IPv6". That was WRONG — it used `2001:db8::2`, a
    documentation range nothing lists, so every zone answered NXDOMAIN. Re-measured against 60 live IPv6
    Tor exits: **s5h 39 hits, Spamhaus 24, CBL 14, DroneBL 5, all thirteen other zones 0.** So four zones
    hold real IPv6 data and are now queried via `DNSBL_ZONES_V6` with their own denominator; the other
    thirteen are deliberately not queried, since "0 of 17" over IPv6 would be a manufactured all-clear.
  - Do NOT probe IPv6 support with `::ffff:7f00:2`: rbldnsd's RECOGNIZE_IP4IN6 rewrites mapped queries to
    the IPv4 lookup, so zones answer it whether or not they hold IPv6 data.
  - Zen/CBL/s5h list **/64 prefixes**, DroneBL exact /128s — so an IPv6 verdict is weaker than the IPv4
    one in both directions. Querying the full /128 still matches a /64 listing (DNS resolves the nibble
    tree from the most significant end).
  - Verified working: 8/12 live IPv6 Tor exits came back listed. And a dual-stack proxy now falls back to
    an IPv4 exit lookup, so the richer 17-zone table is used when one is available.

- **2026-08-06 — Scamalytics v3 added as a reputation source.** Credentials are a USER + KEY pair, which is
  a first — every existing source takes a single value, so `resolve_keys`, the CLI flags, the local
  server's config fallback, the Vercel env fallback and `/api/config` all assume one value per source.
  Stored in `~/.specter-ipcheck.json` and as `SCAMALYTICS_USER` / `SCAMALYTICS_KEY` on Vercel.
  **Status: SHIPPED v0.27.0, and the open question is answered.** Its score does neither of the two things
  expected — it does not saturate like IPQS, it MIS-RANKS: measured over ~200 lookups, a Tor exit scored
  15 "low", clean Comcast residential 18, and the highest of the whole set was Mullvad at 44, because
  `scamalytics_score` tracks `scamalytics_isp_score` on every single IP (an ASN prior, not a measurement of
  the address). So the score is shown — in Scamalytics' own four-band colours, at the user's request — and
  given ZERO weight in the verdict, locked in both directions by a test. What earned the integration is its
  CLASSIFIER: `is_datacenter` + ip2proxy's `proxy_type` caught all four hosting IPs the name heuristic
  missed and stayed quiet on all four real residential exits, so it feeds `connection_class` and `tor`
  became its own exit type.
- **2026-08-06 — checker.net (docs.checker.net): PARKED, not built.** Another IP-reputation API. The key is
  held locally in case it is revisited, but on inspection it does not look like it adds anything the five
  existing sources do not already cover. Status: rejected-for-now; revisit only if a measurement shows it
  separating residential proxies from hosting better than getIPIntel.
- **2026-08-06 — the repo is PUBLIC** so the review bots (Sourcery/CodeRabbit) can run on PR #83. That
  makes any committed credential instantly published, so `test_no_api_credential_is_ever_committed` scans
  every tracked file for the live values held in `~/.specter-ipcheck.json`, and `backups/` +
  `xposed-module/dev-keys.properties` are gitignored. **Bots evaluated: CodeRabbit is worth keeping** —
  ~8 real findings over two rounds on #83, several serious (a refused-by-every-zone blocklist sweep
  reading CLEAN, reputation measured on one address and reported as another, a service worker caching
  500s over the offline shell, a test whose `def` had been deleted so its assertions were silently
  swallowed); ~2 of 10 were wrong, so verify before acting. Sourcery adds ~1 useful finding per PR.
  **Open decision for the user: going back to private may cut CodeRabbit off** (it needs a paid plan for
  private repos), so the visibility call is now a trade against a reviewer that is demonstrably earning
  its place. Not flipped unilaterally.
- **2026-08-06 — DONE, was an open trap: a SOCKS proxy addressed as HTTP read DEAD**, indistinguishable
  from one that is genuinely down. An entire vendor list (lightningproxies, SOCKS5 on :1080) reported dead
  until retried by hand. `check()` now retries the other transport once when the line carried no explicit
  `scheme://`, and SAYS so — "no answer as HTTP — it responded as SOCKS5" — instead of silently papering
  over it. A genuinely dead proxy still reads DEAD and reports that both were tried.
- **2026-08-06 — DONE, was an open gap: getIPIntel is now the last-resort datacenter classifier.**
  Mullvad's exit ISP "Byte Node LLC" matches nothing in `_DATACENTER_RE`, and Scamalytics reported it
  `is_datacenter false` with no ip2proxy record, so a known commercial VPN exit rendered "unclassified".
  getIPIntel called it 1.00 — it grades residential-vs-hosting rather than flagging every proxy (AWS 1.0,
  Starlink 0.0), so `>= 0.99` now names the exit `datacenter`. The threshold is deliberately the one that
  already earns a DIRTY on its own, so this adds no new verdict, only the NAME of what the exit is; the
  factor reads `datacenter/hosting IP (getIPIntel)` so a wrong call is attributable.

## Still open

- **Saved logins (AppData) have no backup path of their own.** `scripts/backup_vault.py` now captures
  them, but there is no in-app export/import for a login the way there is for a fingerprint, and no
  automatic backup before a destructive action. The P4 holds 20 of them; losing that directory would be
  unrecoverable. Next lever: have the app itself write a dated archive to `/sdcard/Specter-exports/`
  before any wipe path, so the safety net does not depend on someone remembering to run a script.
- **The Android IPQS rejection message is not key-scrubbed** (`HealthCheck.java`, the `!success` branch),
  unlike the Python side. Lower severity — the key is the device owner's own, in local storage, not a
  shared server-side secret — but it is an inconsistency between the two implementations.

- **2026-08-06 — Settings cogwheel + device-bound activation codes.** User's ask: a cogwheel top-right
  holding settings/API keys, and a person/key icon for authentication so activations can be issued to
  other people. Two shapes offered: accounts (email+password, "like geergit does it") or generated codes
  valid 1 day / 1 week / 1 month, paid directly (TG/crypto, no payment gateway), tied to the phone's real
  android_id or IMEI, with the UI showing until when they are activated and how much time is left.
  Status: designing. Recommendation is OFFLINE SIGNED CODES over accounts — no backend to run, no password
  reset, and the operator issues a code after being paid. Ed25519, private key outside the repo, app ships
  only the public key. The subtle part: the binding must read the REAL device ids, and Specter spoofs
  android_id/IMEI — its own app is not in its own LSPosed scope so it should see the real values, but that
  has to be TESTED with a profile applied. Hash the ids so the operator never handles a raw one, and guard
  the clock against being rolled back. Full plan in
  `handoffs/2026-08-06_overnight-polish-settings-licensing.md`.
- **2026-08-06 — What do fintech apps ACTUALLY check for IP reputation/cleanliness?** Open research
  question, and it should drive the source list rather than the reverse: the tool currently measures what
  happened to be available, not what the apps that matter actually read. Use exa (project rule: never
  WebFetch). Worth answering: what Sift / Sardine / Socure / Unit21 / Alloy / Persona and the device
  vendors (Fingerprint, Iovation, ThreatMetrix, Incognia, SEON) weight for an IP — ASN reputation,
  hosting/VPN detection, velocity across accounts, IP↔device↔geo consistency, or proxy-piercing
  (WebRTC, timezone/locale mismatch, TTL/MTU, TCP fingerprinting) — and how much a clean residential IP
  even matters next to device and behavioural signals. If the answer is "coherence beats reputation",
  that is a bigger lever than a sixth API. Findings go in `docs/ANTI-FINGERPRINT-STRATEGY.md`, labelled
  HYPOTHESIS until measured. Status: not started.
- **2026-08-06 — DONE: latency reports what the PROXY adds.** Everything read 3000+ ms and the assumption
  was that our own round trip from +0800 was inflating it. Measured, it is not: the same endpoint is
  889 ms direct here and 3077 ms through a US residential proxy, four different endpoints land within
  ~100 ms of each other out of ~3100, and the hosted check already runs from Vercel's iad1 in US-East and
  still saw ~3400 ms. So the proxy IS the cost. The fix was a reference, not a different anchor: time the
  same request without the proxy and grade `proxy_added_ms`, which is comparable between a laptop in Asia
  and a function in Virginia. Live on a real Starlink proxy: 3234 total, 844 direct, 2390 added.
- **2026-08-06 — AppData = reliably store/restore logged-in sessions (CORE, not research).** User
  clarified: *"by appdata i mean we need reliably be able to store our logged in sessions"*. The bar is a
  reliable round trip — log in → Save → switch identity → Restore → still logged in, every time, both
  phones. The plumbing exists (AppDataVault, SessionMigrator capture/restore, restore re-applies the
  linked fingerprint; Cash capture ~5 MB works), so the work is proving reliability and fixing what
  breaks: SQLite WAL/SHM not checkpointed, keystore-sealed tokens that don't survive a copy, the
  files/databases/shared_prefs capture set (and excluding cache), SELinux context + uid on restore, and
  force-stop races. Plus in-app export/import and a pre-wipe archive to /sdcard/Specter-exports/ so a
  saved session is never trapped with no backup (the 4a's logins were nearly lost this session). exa
  research on how App Cloner / Island / Shelter / Titanium-style tools do it. Status: prioritised for the
  overnight run. Plan in handoffs/2026-08-06_overnight-polish-settings-licensing.md §2d.
- **2026-08-06 — No cross-contamination between stored logins: VERIFY the guarantee (already the
  design).** User: *"we generate a unique fingerprint and then we save the login/appdata so it should by
  definition always be tied to the unique fingerprint ofc"*. The binding is inherent — unique fp
  generated, login captured against it, restore re-applies that fp. So this is a confirm-it-holds task:
  a test asserting vault fingerprints are pairwise-unique (no shared android_id/GSF/mediaDrm/serial),
  confirm restore wipes before writing (A→B→A leaves no B residue), capture is scoped to one app+identity,
  and restore re-applies the login's OWN fp. Fast if the design is sound; if any fails it's a real bug
  that jumps the queue. Overnight handoff §2d.
- **2026-08-06 — SAFETY: never wipe/clear a live Cash App login.** User, explicit. Cash App sessions on
  both phones are live income — clearing one destroys it (same category as the vault wipe). AppData
  reliability round-trips go on the DoorDash Dasher app (com.doordash.driverapp) on the 4a ONLY, proxy
  not needed, Lockito GPS running until reboot (don't casually reboot the 4a). Before any wipe, confirm
  the package is com.doordash.driverapp, not com.squareup.cash.
- **2026-08-06 — Trace the LIVE Dasher session (read-only) to ground-truth spoof coverage.** User: run a
  trace after opening the already-logged-in Dasher account, see what it reads and whether we spoof it.
  Use Monitor reads / Read logging (TraceParser), open the app, cross-reference every value it queries
  against what Specter sets — a field it reads that we leave real is a coverage gap. This is ground truth
  from a real fintech-adjacent app and should steer the fintech-signals research, not the reverse.
  Findings → docs/ANTI-FINGERPRINT-STRATEGY.md. Read-only, no wipe needed. Overnight handoff §2d/§3.
- **2026-08-06 — Core use case must be polished + verified with screenshots.** User: the proxy/IP
  checking has to be genuinely useful — users check single OR bulk ips/proxies and see alive/dead, where
  they are, how clean, and compare in bulk to pick the best then copy host/port/user/pass. Walk that exact
  flow as a user, screenshot single+bulk+detail in both themes on web and both phones, fix anything
  buried/cramped/ambiguous. The three questions — alive? where? how clean? — must each answer fast and
  unambiguously. Part of §1 of the overnight polish pass.

## 2026-08-06 — levers from the fintech-signals research (docs/ANTI-FINGERPRINT-STRATEGY.md)
- **Local coherence check, zero API cost (HIGH value, do next).** Compare the exit IP's geo-timezone vs the
  profile's applied timezone, and the exit geo vs the profile carrier/MCC. Both are pure local comparisons
  that map to a real vendor weight (Fingerprint 3-4 pts, Socure returns the delta in minutes).
  Status: TZ-vs-IP already shipped (HealthCheck "Timezone vs IP" row); **carrier-MCC-vs-IP SHIPPED v0.29.4**
  ("Carrier vs IP" row, `Country.countryIsoForMcc`, one-directional). Done.
- **Continuous coherence, not one-shot.** TZ already follows the proxy exit IP (v0.19.0); the gap is
  RE-verifying per session and surfacing a drift warning when the sticky IP silently moves. Status: idea.
- **Add a discriminating reputation source where IPQS/AbuseIPDB saturate.** Order: ip-api.com (no key) →
  ipapi.is (`abuser_score` at COMPANY+ASN level, network-not-per-IP) → proxycheck.io (proxy type + last-seen)
  → ipregistry (bulk sweeps) → vpnapi.io (`relay` class). Each must EARN its place by discriminating two IPs
  that both score ~75 today; measure before integrating. Rejected: ipinfo Lite (paid flags), Spur (no free
  API), GreyNoise (50/week). Status: researched.
- **AppData reliability follow-ups (feeds §2d):** confirm the tarball excludes `cache/` + `code_cache/` +
  `lib/` (code_cache with a stale uid can trigger a full data wipe on reboot); confirm `am force-stop`
  precedes the snapshot; expect per-app variance and report WHICH login layer failed (plain row vs
  Keystore-wrapped vs server-side device-binding) rather than a bare "restore failed". Status: to verify.

- **2026-08-06 UPDATE (getIPIntel r/i, item above):** live-tested oflags=bcri from here on 8.8.8.8 +
  24.48.0.1. getIPIntel ECHOED queryOFlags="bcri" (flags syntactically accepted) but returned result=-5
  (IP banned / rate-limited / contact rejected) for both, so the free tier's r/i RESPONSE fields could not
  be observed. BLOCKED on live verification: needs an un-banned source IP + a valid metered contact.
  Do NOT build r/i parsing until a successful response confirms the field names. Status: blocked-verify.
