"""TDD for the iOS coherent-profile generator. Kept under ios/ so it's separate from the Android
suite. Run: `python -m pytest ios/core/test_profile.py -q` (or `cd ios/core && python -m pytest`)."""
import plistlib
import uuid

import profile as P


def test_catalog_every_device_is_self_coherent():
    """Each catalog row must generate a profile that passes its own coherence validator —
    guards against a bad hand-entry (wrong board/memsize/build for a model)."""
    cat = P.load_catalog()
    assert cat, "catalog is empty"
    for model in cat:
        prof = P.generate(model=model, catalog=cat)
        errs = P.validate(prof, cat)
        assert not errs, f"{model} generated an incoherent profile: {errs}"


def test_determinism_same_seed_same_profile():
    a = P.generate(seed=12345)
    b = P.generate(seed=12345)
    assert a == b, "same seed must yield identical profiles (needed for reproducibility)"


def test_seed_selects_stable_model_and_os():
    # model/os selection is part of the seeded stream, so it must also be deterministic
    a = P.generate(seed=999)
    b = P.generate(seed=999)
    assert (a["model"], a["os_version"]) == (b["model"], b["os_version"])


def test_idfv_rotates_across_seeds():
    idfvs = {P.generate(seed=s, model="iPhone12,8")["identifier_for_vendor"] for s in range(64)}
    assert len(idfvs) == 64, "IDFV collided across seeds — the rotating field must actually rotate"


def test_idfv_is_valid_uppercase_uuid():
    idfv = P.generate(seed=1)["identifier_for_vendor"]
    assert idfv == idfv.upper(), "IDFV must be uppercase (as UIDevice returns)"
    assert uuid.UUID(idfv).version == 4


def test_serial_is_modern_10char_no_ambiguous_letters():
    s = P.generate(seed=2)["serial_number"]
    assert len(s) == 10
    assert not (set("IOBS") & set(s)), "modern Apple serial alphabet omits ambiguous letters"


def test_validator_catches_board_model_mismatch():
    """An iPhone 12 board on an SE2 is an impossible device — the validator must reject it."""
    bad = P.generate(model="iPhone12,8", seed=7)
    bad["hw_model"] = "D53gAP"  # iPhone 13,2 board
    assert P.validate(bad), "validator failed to catch a board/model mismatch"


def test_validator_catches_bad_os_build_pairing():
    bad = P.generate(model="iPhone12,8", seed=8)
    bad["os_build"] = "99Z99"  # not the real build for this os_version
    assert P.validate(bad), "validator failed to catch an os_version/os_build mismatch"


def test_validator_catches_impossible_storage():
    bad = P.generate(model="iPhone12,8", seed=9)
    bad["storage_gb"] = 999  # not a real SKU
    assert P.validate(bad), "validator failed to catch a non-existent storage SKU"


def test_product_type_equals_hw_machine():
    p = P.generate(seed=3)
    assert p["product_type"] == p["hw_machine"], "MG ProductType and hw.machine must be identical"


def test_forced_os_version_must_exist_for_model():
    import pytest
    with pytest.raises(ValueError):
        P.generate(model="iPhone12,8", os_version="17.0")  # SE2 has no 17.0 build in catalog


def test_tweak_plist_roundtrips_and_has_coherent_keys(tmp_path):
    """The exported plist is what the tweak reads on-device — it must be valid and carry the
    coherent pair (ProductType==HWMachine, HWModelStr==HWModel)."""
    p = P.generate(model="iPhone14,6", seed=2026)
    d = P.to_tweak_plist(p)
    out = tmp_path / "com.specter.iostest.plist"
    with open(out, "wb") as f:
        plistlib.dump(d, f)
    with open(out, "rb") as f:
        loaded = plistlib.load(f)
    assert loaded["ProductType"] == loaded["HWMachine"]
    assert loaded["HWModelStr"] == loaded["HWModel"]
    assert isinstance(loaded["MemSize"], int) and loaded["MemSize"] > 0
    assert loaded["OSVersion"] == p["os_version"] and loaded["OSBuild"] == p["os_build"]
