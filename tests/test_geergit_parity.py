"""
Parity: assert our identifier surface covers everything GeerGit hooks.

Missing an identifier GeerGit covers = the exact class of leak that bans accounts
(an un-rotated surface). We assert against GeerGit's own Dart string pool, extracted
to docs/geergit-dart-strings-2.9.6.txt.
"""
import os
import pytest
from specter.identifiers import GEERGIT_COVERED, SPECS

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DART = os.path.join(ROOT, "docs", "geergit-dart-strings-2.9.6.txt")

# GeerGit's toggle keys (…_switch) map to identifier surfaces. This is the authoritative
# list of what GeerGit rotates, taken from its dart strings.
GEERGIT_SWITCHES = {
    "android_id_switch": "android_id",
    "imei1_switch": "imei1",
    "imei2_switch": "imei2",
    "serial_switch": "serial",
    "sim_operator_switch": "sim_operator",
    "advertising_id_switch": "adsid",
    "bluetooth_mac_switch": "bmac",
    "wifimac_switch": "wmac",
    "wifissid_switch": "wssid",
    "wifibssid_switch": "wbssid",
    "mobile_number_switch": "mob",
    "sim_subscriber_switch": "subid",
    "sim_card_serial_switch": "simcs",
    "gsf_id_switch": "gsfid",
    "gmail_switch": "email",
    "media_drm_switch": "media_drm",
    "device_spoof_switch": "device_spoof",
}


@pytest.mark.skipif(not os.path.exists(DART), reason="dart strings not present")
def test_every_geergit_switch_is_in_dart_strings():
    text = open(DART, encoding="utf-8", errors="replace").read()
    missing = [s for s in GEERGIT_SWITCHES if s not in text]
    assert not missing, f"switches not found in GeerGit dart (spec drift?): {missing}"


def test_we_cover_every_geergit_switch():
    """Our GEERGIT_COVERED set must include every switch GeerGit exposes."""
    geergit_surfaces = set(GEERGIT_SWITCHES.values())
    uncovered = geergit_surfaces - GEERGIT_COVERED
    assert not uncovered, f"identifiers GeerGit rotates but we DON'T: {uncovered}"


def test_gsf_is_covered_and_unique():
    """The regressed identifier must be present AND marked unique."""
    gsf = [s for s in SPECS if s.key == "gsf_id"]
    assert gsf, "gsf_id missing from spec"
    assert gsf[0].unique, "gsf_id must be unique=True (this is the ban bug)"
