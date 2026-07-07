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
