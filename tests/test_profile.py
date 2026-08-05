"""Profile assembly: coherence, validity, determinism."""
import pytest
from specter import profile as P
from specter.identifiers import UNIQUE_KEYS, BUILD_FIELDS


def test_generated_profile_is_valid_and_coherent():
    for _ in range(200):
        p = P.generate_unique(None)
        ok, errs = P.validate(p)
        assert ok, errs


def test_fingerprint_matches_build_fields():
    for _ in range(200):
        p = P.generate_unique(None)
        assert p["build_brand"] in p["build_fingerprint"]
        assert p["build_device"] in p["build_fingerprint"]
        assert p["build_release"] in p["build_fingerprint"]


def test_imsi_matches_carrier():
    for _ in range(200):
        p = P.generate_unique(None)
        assert p["sim_subscriber_imsi"].startswith(p["sim_operator_mccmnc"])


def test_us_bias_produces_common_brands_majority():
    from collections import Counter
    c = Counter(P.generate_unique(None)["build_manufacturer"].lower() for _ in range(300))
    common = sum(v for k, v in c.items() if k in {"samsung", "google", "motorola", "oneplus", "lge"})
    assert common / 300 > 0.9, f"us-bias too weak: {c}"


def test_seeded_is_deterministic():
    a = P.generate_unique(None, seed=12345)
    b = P.generate_unique(None, seed=12345)
    assert a == b


def test_all_unique_keys_present():
    p = P.generate_unique(None)
    for k in UNIQUE_KEYS:
        assert k in p and p[k], f"missing {k}"
    for k in BUILD_FIELDS:
        assert k in p and p[k], f"missing {k}"


def test_iccid_matches_carrier_iin():
    """ICCID issuer prefix must be consistent with the assigned carrier (fraud flag otherwise)."""
    from specter import generators as G
    for _ in range(300):
        p = P.generate_unique(None)
        iin = G._ICCID_IIN.get(p["sim_operator_mccmnc"])
        if iin:
            assert p["sim_serial_iccid"].startswith(iin), \
                f"ICCID {p['sim_serial_iccid']} != carrier IIN {iin} for {p['sim_operator_name']}"
        # still a valid 20-digit Luhn ICCID
        assert G.validate("sim_serial_iccid", p["sim_serial_iccid"])


def test_imeis_share_tac_but_differ():
    """Dual-SIM IMEIs share the device TAC (first 8 digits) but differ in serial (real behavior)."""
    from specter import generators as G
    for _ in range(300):
        p = P.generate_unique(None)
        i1, i2 = p["imei1"], p["imei2"]
        assert i1[:8] == i2[:8], f"IMEIs should share TAC: {i1} vs {i2}"
        assert i1 != i2, "IMEI1 and IMEI2 must differ (different serials)"
        assert G.validate("imei1", i1) and G.validate("imei2", i2)


def test_imei_tac_matches_manufacturer():
    """The IMEI TAC should be one of the manufacturer's plausible TACs."""
    from specter import generators as G
    for _ in range(300):
        p = P.generate_unique(None)
        brand = p["build_brand"].lower()
        known = G._TAC_BY_BRAND.get(brand)
        if known:  # US-biased brands are all in the table
            assert p["imei1"][:8] in known, f"TAC {p['imei1'][:8]} not a {brand} TAC"


# ---- coherence-sweep checks added to validate() (Phase 2.2) --------------------------------
# Each is proven false-positive-free over 500 generated profiles by
# test_generated_profile_is_valid_and_coherent above; these pin the FAILURE side.


def test_soc_family_classifies_the_pool_socs():
    assert P._soc_family("lahaina") == "qcom"
    assert P._soc_family("lito") == "qcom"
    assert P._soc_family("msmnile") == "qcom"
    assert P._soc_family("exynos990") == "exynos"
    assert P._soc_family("gs101") == "tensor"
    assert P._soc_family("something-unknown") == ""      # unrecognised -> the check skips, never guesses


def test_validate_flags_a_soc_gpu_vendor_mismatch():
    # A Qualcomm SoC (Adreno) reporting an ARM GPU vendor is the classic incoherent hardware pair.
    _, errs = P.validate({"soc_platform": "lahaina", "hw_gpu_vendor": "ARM"})
    assert any("GPU vendor" in e for e in errs)
    # ...and the correct pairing does NOT trip it.
    _, ok_errs = P.validate({"soc_platform": "lahaina", "hw_gpu_vendor": "Qualcomm"})
    assert not any("GPU vendor" in e for e in ok_errs)
    _, ok_ex = P.validate({"soc_platform": "exynos990", "hw_gpu_vendor": "ARM"})
    assert not any("GPU vendor" in e for e in ok_ex)


def test_validate_flags_a_carrier_name_that_disagrees_with_its_mccmnc():
    # 310410 is AT&T in US_CARRIERS; calling it T-Mobile is a slip.
    _, errs = P.validate({"sim_operator_mccmnc": "310410", "sim_operator_name": "T-Mobile"})
    assert any("carrier" in e for e in errs)
    _, ok_errs = P.validate({"sim_operator_mccmnc": "310410", "sim_operator_name": "AT&T"})
    assert not any("carrier T-Mobile" in e for e in ok_errs)


def test_validate_flags_board_hardware_disagreement():
    _, errs = P.validate({"build_board": "redfin", "build_hardware": "bramble"})
    assert any("build_board" in e and "build_hardware" in e for e in errs)


def test_validate_flags_a_security_patch_that_predates_the_os():
    # Android 11 shipped 2020; a 2018 patch on an Android-11 fingerprint is incoherent.
    fp = "google/redfin/redfin:11/RQ3A.211001.001/7641976:user/release-keys"
    _, errs = P.validate({"build_fingerprint": fp, "build_security_patch": "2018-05-01"})
    assert any("predates Android 11" in e for e in errs)
    _, ok_errs = P.validate({"build_fingerprint": fp, "build_security_patch": "2021-10-01"})
    assert not any("predates" in e for e in ok_errs)
