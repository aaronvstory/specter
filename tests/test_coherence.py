"""Cross-field coherence regression guard.

A spoofed identity must be internally consistent — an incoherent combo (e.g. a Pixel 6 reporting a
2016 Snapdragon 820, or a Galaxy A01 with a Galaxy S21 bootloader) is itself a fingerprint red flag,
worse than not spoofing. This test asserts every cross-field invariant over many generated profiles so
a future change can't silently break coherence (the exact failure mode that once made the SoC map dead
code for every Pixel/LG device). Runs against the real generator via generate_unique.
"""
import re
from specter import profile as P

N = 400  # profiles to check per invariant


def _profiles(n=N):
    return [P.generate_unique(None) for _ in range(n)]


def test_fingerprint_structure_and_tail():
    for p in _profiles():
        fp = p["build_fingerprint"]
        assert fp.startswith(f"{p['build_brand']}/{p['build_product']}/{p['build_device']}:{p['build_release']}/"), fp
        assert fp.endswith(":user/release-keys"), fp


def test_device_slot_is_a_codename_not_a_marketing_name():
    """The fingerprint's DEVICE slot is a codename ("flame"), never the marketing model ("Pixel 4").

    Regression: build_model and build_device were bound to the wrong dataset columns, so every profile
    emitted a fingerprint like "google/bramble/Pixel 4a (5G):11/..." — spaces and parentheses in a slot
    where no real Android build ever puts them, and a marketing name in Build.DEVICE. Verified against a
    real Pixel 4: MODEL="Pixel 4", DEVICE=PRODUCT="flame", fp="google/flame/flame:11/...".
    """
    for p in _profiles():
        dev = p["build_device"]
        assert re.fullmatch(r"[A-Za-z0-9_.-]+", dev), f"build_device is not a codename: {dev!r}"
        assert dev == p["build_fingerprint"].split("/")[2].split(":")[0], p["build_fingerprint"]
        # The marketing model is the human-readable one, so it is the slot allowed to hold spaces.
        assert p["build_model"], "build_model must be set"


def test_sim_identity_is_one_us_carrier():
    for p in _profiles():
        mccmnc = p["sim_operator_mccmnc"]
        assert mccmnc[:3] in ("310", "311", "312", "313", "314", "315", "316"), f"non-US MCC {mccmnc}"
        assert p["sim_subscriber_imsi"].startswith(mccmnc), "IMSI not carrier-coherent"
        assert re.fullmatch(r"1[2-9]\d{2}[2-9]\d{6}", p["mobile_number"]), p["mobile_number"]
        iccid = p["sim_serial_iccid"]
        assert len(iccid) == 20 and iccid.isdigit(), iccid


def test_hardware_board_are_the_codename():
    # HARDWARE/BOARD are the board codename (product, LG region suffix stripped) — NOT the marketing name.
    for p in _profiles():
        cn = p["build_product"].split("_")[0]
        assert p["build_hardware"] == cn, f"{p['build_hardware']} != {cn}"
        assert p["build_board"] == cn, f"{p['build_board']} != {cn}"


def test_bootloader_and_display_coherent():
    for p in _profiles():
        assert " " not in p["build_bootloader"], p["build_bootloader"]
        assert p["build_display"] == p["build_id"], "DISPLAY must equal build_id"


def test_dual_sim_imei_distinct_but_share_tac():
    for p in _profiles():
        assert p["imei1"] != p["imei2"], "dual-SIM IMEIs must differ"
        assert p["imei1"][:8] == p["imei2"][:8], "IMEIs must share the brand TAC"


def test_build_sdk_matches_the_android_release():
    """Build.VERSION.SDK_INT (build_sdk) must be the correct API level for the claimed Android release —
    a profile claiming Android 9 reporting SDK 30 is itself a fingerprint. Cross-checks the emitted
    field against the release->SDK map."""
    from specter import generators as G
    for p in _profiles():
        assert p["build_sdk"] == str(G.sdk_for_release(p["build_release"])), \
            f"SDK {p['build_sdk']} incoherent with release {p['build_release']}"
        assert p["build_sdk"].isdigit() and 19 <= int(p["build_sdk"]) <= 36, f"implausible SDK {p['build_sdk']}"


def test_first_api_level_is_launch_api_and_never_above_sdk():
    """ro.product.first_api_level (build_first_api) = the device's LAUNCH API. It must NEVER exceed build_sdk
    (a launch API above the running OS is impossible) and equals the real launch API for mapped models."""
    from specter import generators as G
    for p in _profiles():
        fa, sdk = int(p["build_first_api"]), int(p["build_sdk"])
        assert fa <= sdk, f"first_api {fa} > sdk {sdk} — impossible (model {p['build_model']})"
        assert fa == G.launch_api_for(p["build_model"], sdk), \
            f"first_api {fa} != launch_api_for({p['build_model']}, {sdk})"


def test_known_launch_apis_are_pinned():
    """Pin a few researched launch APIs so a regression (or a wrong dataset release) is caught: the launch
    API is a FACT of the model. Note8=25 (not 26), S8=24, A70=28 are the careful cases."""
    from specter import generators as G
    for model, want in [("SM-N950F", 25), ("SM-G950F", 24), ("SM-A705FN", 28),
                        ("SM-G970F", 28), ("SM-N960F", 27), ("SM-A600F", 26),
                        # Xiaomi/Moto/OnePlus MIUI-vs-Android traps + launch-OS facts (2026-07-28):
                        ("POCOPHONE F1", 27), ("Redmi Note 5 Pro", 25), ("Mi MIX 2", 25),
                        ("moto g(6)", 26), ("moto x4", 25), ("ONEPLUS A3000", 23), ("GM1900", 28)]:
        assert G.launch_api_for(model, 35) == want, f"{model} launch API should be {want}"
    # an unmapped model falls back to the current sdk (first_api == sdk)
    assert G.launch_api_for("Totally Unknown", 30) == 30


def test_every_dataset_release_has_a_real_sdk_mapping():
    """Guard against the self-consistency blind spot: a release NOT in the SDK map falls through to the
    default (30), so a KitKat device would report API 30 — incoherent. Assert EVERY distinct release
    string actually present in data/devices.json maps to a plausible, non-default SDK for that era.
    """
    from specter import generators as G
    devices = P._load_devices()
    releases = {row[5].split(":")[1] for row in devices if len(row) > 5 and ":" in row[5]}
    # rough era check: major version N should map to an SDK in a sane band, never the default fallback.
    era = {"4": (14, 20), "5": (21, 22), "6": (23, 23), "7": (24, 25), "8": (26, 27),
           "9": (28, 28), "10": (29, 29), "11": (30, 30), "12": (31, 32)}
    for rel in sorted(releases):
        assert rel in G._SDK_BY_RELEASE, f"release {rel!r} in devices.json has no explicit SDK mapping"
        sdk = G.sdk_for_release(rel)
        major = rel.split(".")[0]
        lo, hi = era.get(major, (1, 36))
        assert lo <= sdk <= hi, f"release {rel} -> SDK {sdk} is out of the Android {major}.x band {lo}-{hi}"


def test_soc_is_a_real_platform_codename():
    # SoC is always a real Qualcomm/Google platform token — never a made-up or space-containing string.
    for p in _profiles():
        soc = p["soc_platform"]
        assert re.fullmatch(r"[a-z0-9]{4,10}", soc), f"implausible SoC {soc!r}"


def test_soc_topology_signals_are_coherent():
    """cpu_capacity / gpu_model / cpu_present describe the SoC the profile claims — the /sys hardware
    signals FingerprintJS reads directly. Regression guard: they must exist, be well-formed, and
    cpu_present must match the capacity-vector length.
    """
    for p in _profiles():
        cap = p["cpu_capacity"]
        vals = cap.split()
        assert 1 <= len(vals) <= 16, f"implausible core count in cpu_capacity: {cap!r}"
        for v in vals:
            assert v.isdigit() and 1 <= int(v) <= 1024, f"cpu_capacity out of range: {v!r}"
        # Kernel capacities are normalized so the fastest core is 1024. Homogeneous SoCs (e.g. SD665,
        # all cores equal) legitimately peak below 1024 only when every core is the same; a
        # heterogeneous vector (distinct values) MUST include a 1024. Either way, never exceed 1024.
        caps = [int(v) for v in vals]
        if len(set(caps)) > 1:
            assert max(caps) == 1024, f"heterogeneous capacity vector must peak at 1024: {cap!r}"
        assert p["cpu_present"] == f"0-{len(vals) - 1}", f"present must match core count: {p['cpu_present']}"
        # Qualcomm SoCs expose a numeric KGSL gpu_model; Exynos/others have no kgsl node (empty is coherent).
        assert re.fullmatch(r"\d*", p["gpu_model"]), f"gpu_model must be numeric-or-empty: {p['gpu_model']!r}"
        # The /sys KGSL gpu_model number MUST equal the Adreno number in the GL renderer string — a
        # fingerprinter reading both paths flags any mismatch (e.g. gpu_model 618 while renderer says
        # "Adreno 612"). Only cross-check when both are present (Adreno devices).
        renderer = p.get("hw_gpu_renderer", "")
        m = re.search(r"Adreno.*?(\d{3})", renderer)
        if p["gpu_model"] and m:
            assert p["gpu_model"] == m.group(1), (
                f"gpu_model {p['gpu_model']!r} != renderer Adreno {m.group(1)!r} ({renderer!r})"
            )


def test_dataset_gpu_renderer_matches_soc_topology():
    """Dataset-level guard: for EVERY device in hardware.json, the Adreno number in its gpu_renderer must
    equal the gpu_model its SoC maps to in the topology, AND the /proc/cpuinfo "Hardware" line must not
    name a DIFFERENT SoC than the one claimed. This catches internal inconsistency across the three SoC
    read-paths (GL renderer, /sys gpu_model, /proc/cpuinfo). Non-Adreno (Mali/Exynos) devices have an
    empty gpu_model and skip the renderer check. (Note: an entry that is factually WRONG but internally
    self-consistent — every path agreeing on the wrong SoC — passes here; test_known_device_socs pins the
    real SoC for such cases.)
    """
    hardware = P._load_hardware()
    topo = P._load_soc_topology()
    # A few Qualcomm platforms serve MORE THAN ONE Adreno across their SKUs (e.g. "lito" = Adreno 619 on
    # SD750G / 620 on SD765G). For those the /sys gpu_model is derived from the per-model renderer at
    # generate time (Profile.socTopologyFields override), so the renderer's Adreno just has to be one the
    # SoC really ships — not the single topology default. This set records those legit multi-Adreno SoCs.
    MULTI_ADRENO_SOC = {
        "lito": {"618", "619", "620"},   # SD765G/750G/768G family
    }
    for codename, e in hardware.items():
        if codename.startswith("_"):
            continue
        renderer = e.get("gpu_renderer", "")
        m = re.search(r"Adreno.*?(\d{3})", renderer)
        if m:
            soc = e.get("soc", "")
            soc_entry = topo.get(soc)
            assert soc_entry is not None, f"{codename}: soc {soc!r} has no topology entry"
            if soc in MULTI_ADRENO_SOC:
                assert m.group(1) in MULTI_ADRENO_SOC[soc], (
                    f"{codename}: renderer says Adreno {m.group(1)} but SoC {soc!r} only ships "
                    f"{sorted(MULTI_ADRENO_SOC[soc])}"
                )
            else:
                gpu_model = soc_entry.get("gpu_model", "")
                assert gpu_model == m.group(1), (
                    f"{codename}: renderer says Adreno {m.group(1)} but SoC {soc!r} topology gpu_model is "
                    f"{gpu_model!r} — incoherent /sys-vs-GL (check the SoC mapping AND the renderer string)"
                )


# Authoritative device -> (soc_platform, Adreno gpu number) for models we've verified against a real
# device or the mainline kernel device tree. Pins the FACT, so a factually-wrong-but-self-consistent
# mislabel (all read-paths agreeing on the wrong SoC — the exact shape of the original sunfish bug) is
# caught. Extend as real devices are harvested/confirmed.
_KNOWN_DEVICE_SOC = {
    "sunfish": ("sm7150", "618"),   # Pixel 4a = SD730G — DT "qcom,sm7150", real cpuinfo "SDMMAGPIE"
    "flame":   ("msmnile", "640"),  # Pixel 4  = SD855
    # 2026-07-28 audit (kernel-DT/teardown grounded) — were mislabelled to the sm6150/Adreno-612 default:
    "a71naxx": ("sm7150", "618"),   # Galaxy A71 = SD730 (sm7150, Adreno 618)
    "bonito":  ("sdm670", "615"),   # Pixel 3a XL = SD670 — DT "qcom,sdm670" (NOT sm670)
    "sargo":   ("sdm670", "615"),   # Pixel 3a = SD670 — DT "sdm670-google-sargo.dts"
    "kiev":    ("lito", "619"),     # Moto G 5G = SD750G (lito platform, Adreno 619)
    "nairo":   ("lito", "620"),     # Moto One 5G = SD765G (lito platform, Adreno 620)
}

# SoCs whose topology gpu_model is a single default even though the platform ships multiple Adrenos — for
# these the renderer (not the topology default) is the authority, so skip the topology==gpu assert below.
_MULTI_ADRENO_TOPO = {"lito"}


def test_known_device_socs():
    """Pin the real SoC + GPU for verified models so a mislabel that is internally self-consistent (the
    original sunfish bug: sm6150/Adreno-612 everywhere when the 4a is really sm7150/Adreno-618) can't slip
    through.
    """
    hardware = P._load_hardware()
    topo = P._load_soc_topology()
    for codename, (want_soc, want_gpu) in _KNOWN_DEVICE_SOC.items():
        e = hardware.get(codename)
        assert e is not None, f"{codename} missing from hardware.json"
        assert e.get("soc") == want_soc, f"{codename}: soc is {e.get('soc')!r}, real device is {want_soc!r}"
        m = re.search(r"Adreno.*?(\d{3})", e.get("gpu_renderer", ""))
        assert m and m.group(1) == want_gpu, (
            f"{codename}: renderer {e.get('gpu_renderer')!r} != real Adreno {want_gpu}"
        )
        # For a multi-Adreno platform (lito) the topology gpu_model is one default; the renderer is what the
        # runtime uses (Profile.socTopologyFields override), so don't require the topology default to match.
        if want_soc not in _MULTI_ADRENO_TOPO:
            gpu_model = topo.get(want_soc, {}).get("gpu_model", "")
            assert gpu_model == want_gpu, f"{want_soc} topology gpu_model {gpu_model!r} != {want_gpu!r}"
        # The /proc/cpuinfo "Hardware" line must not name a chip the device isn't. It uses marketing
        # codenames (SD730G = "SDMMAGPIE", not "SM7150"), so we only assert it doesn't leak a KNOWN-wrong
        # SoC id — for sunfish, the old bug baked "SM6150" into cpuinfo. Guard against that regressing.
        ci = e.get("cpuinfo", "")
        if codename == "sunfish":
            assert "SM6150" not in ci, "sunfish cpuinfo still names SM6150 — the wrong SoC (real: SDMMAGPIE)"
            assert "SDMMAGPIE" in ci, "sunfish cpuinfo should name SDMMAGPIE (real SD730G codename)"


def test_screen_metrics_are_plausible():
    """screen_width/height/density (the getDisplayMetrics signal) must be plausible real values:
    portrait (height > width), sane resolution + density ranges. Keyed on the device codename, so a
    given identity always reports the same screen."""
    for p in _profiles():
        w, h, d = int(p["screen_width"]), int(p["screen_height"]), int(p["screen_density"])
        assert 480 <= w <= 1600, f"implausible screen width {w}"
        assert 800 <= h <= 3400, f"implausible screen height {h}"
        assert h > w, f"screen must be portrait (h>w): {w}x{h}"
        assert 120 <= d <= 640, f"implausible densityDpi {d}"


def test_factory_reset_is_after_the_build_and_in_the_past():
    """A device cannot be factory-reset before its own OS was built, nor in the future.

    FPJS Pro reports `factoryReset` as a first-class smart signal, so the value has to survive a human
    (or model) sanity check: reset time > security-patch date of the running build, and < now.
    A reset "before the phone existed" is a louder tell than not spoofing at all.
    """
    import datetime as dt
    now = int(dt.datetime.now(dt.timezone.utc).timestamp())
    for p in _profiles(200):
        e = int(p["factory_reset_epoch"])
        patch = dt.datetime.strptime(p["build_security_patch"], "%Y-%m-%d").replace(
            tzinfo=dt.timezone.utc)
        assert e > int(patch.timestamp()), (
            f"reset {e} predates the build's security patch {p['build_security_patch']} "
            f"({p['build_fingerprint']})")
        assert e < now, f"reset {e} is in the future"


def test_factory_reset_present_in_every_profile():
    for p in _profiles(50):
        assert p.get("factory_reset_epoch"), "every profile must carry a factory-reset time"


# ---- device plausibility (a phone signup from a 2012 tablet on Android 5 is itself a fingerprint) ----
def _release_major(p):
    return int(float(p["build_release"].split(".")[0]))


def test_no_tablet_or_tv_device_in_the_generation_pool():
    """We generate a phone number + SIM + IMEI, so the device must be a phone. Assert the FILTER at the
    source: no tablet/TV row survives `_is_plausible_phone`, so none can ever be generated."""
    import json
    devs = json.load(open(P.DEVICES_PATH, encoding="utf-8"))
    pool = [d for d in devs if len(d) > 2 and d[2].lower() in P.US_COMMON_BRANDS
            and P._is_plausible_phone(d)]
    assert pool, "plausible-phone pool must not be empty"
    for d in pool:
        assert not any(m in d[0] for m in P._NON_PHONE_MARKERS), "tablet/TV in pool: " + d[0]


def test_generated_os_is_plausibly_recent():
    """Every generated profile claims at least Android 11 (the coherence floor): claiming an OS older than
    the real host leaks a contradiction in the deferred-native-prop startup window (see the Cash App
    failure investigation). Pins P.MIN_ANDROID_MAJOR directly so a regression back to 9/10 can't sail
    through undetected."""
    for p in _profiles(300):
        assert _release_major(p) >= P.MIN_ANDROID_MAJOR, \
            "OS below the floor (%d): Android %s (%s)" % (
                P.MIN_ANDROID_MAJOR, p["build_release"], p["build_fingerprint"])
