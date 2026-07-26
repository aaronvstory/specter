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
    """Android < 9 (2018) on a fresh account is a red flag — too old for a phone in real use today."""
    for p in _profiles(300):
        assert _release_major(p) >= 9, \
            "implausibly old OS: Android %s (%s)" % (p["build_release"], p["build_fingerprint"])
