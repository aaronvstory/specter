"""Unit tests for every identifier generator: format + validity."""
import secrets
import pytest
from specter import generators as G


def r(n):
    return secrets.randbelow(n)


def test_luhn_check_and_validate_agree():
    for _ in range(200):
        body = G.digits(r, 14)
        full = body + G.luhn_check_digit(body)
        assert G.luhn_valid(full), f"{full} failed luhn"


def test_imei_is_15_digits_luhn_valid():
    for _ in range(500):
        v = G.imei(r)
        assert len(v) == 15 and v.isdigit()
        assert G.luhn_valid(v)
        assert G.validate("imei1", v)


def test_iccid_20_digits_luhn():
    for _ in range(300):
        v = G.iccid(r)
        assert len(v) == 20 and v.isdigit()
        assert G.luhn_valid(v)
        assert G.validate("sim_serial_iccid", v)


def test_android_id_16_hex():
    for _ in range(300):
        v = G.hex16(r)
        assert G.validate("android_id", v)


def test_serial_16_hex_upper():
    for _ in range(300):
        assert G.validate("serial", G.hex16upper(r))


def test_media_drm_32_hex():
    for _ in range(300):
        assert G.validate("media_drm_id", G.hex32(r))


def test_mac_is_locally_administered_unicast():
    for _ in range(300):
        m = G.mac_upper(r)
        first = int(m.split(":")[0], 16)
        assert first & 0x02, "not locally administered"
        assert not (first & 0x01), "must be unicast"
        assert G.validate("bluetooth_mac", m)


def test_uuid_format():
    for _ in range(200):
        assert G.validate("advertising_id", G.uuid(r))


def test_phone_us_nanp_rules():
    for _ in range(500):
        p = G.phone_us(r)
        assert G.validate("mobile_number", p)
        # area & exchange first digit must be 2-9
        assert p[1] in "23456789"
        assert p[4] in "23456789"


def test_imsi_starts_with_mccmnc_and_15_long():
    for mccmnc in ("310260", "311480"):
        for _ in range(100):
            v = G.imsi(r, mccmnc)
            assert v.startswith(mccmnc)
            assert G.validate("sim_subscriber_imsi", v)


def test_gsf_positive_19_digits():
    for _ in range(500):
        v = G.gsf(r)
        assert G.validate("gsf_id", v)
        assert int(v) > 0


def test_gmail_format():
    for _ in range(200):
        assert G.validate("gmail", G.gmail(r))


def test_validate_rejects_garbage():
    assert not G.validate("android_id", "NOTHEX")
    assert not G.validate("imei1", "123")           # wrong length
    assert not G.validate("imei1", "123456789012345")  # right length, bad luhn (almost always)
    assert not G.validate("sim_serial_iccid", "8901")
