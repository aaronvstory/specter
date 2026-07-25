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
