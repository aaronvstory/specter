"""Unit tests for every identifier generator: format + validity."""
import secrets
import pytest
from specter import generators as G


def r(n):
    return secrets.randbelow(n)


def test_screen_is_the_models_real_resolution():
    # A pooled model must report its REAL screen (w,h,densityDpi), not a random pool pick — a Galaxy S21+
    # was generating as 720x1520 (a budget-phone screen), a hard model tell. Longest-prefix on build_device
    # (which can carry a suffix like "sofiap_sprout"). Real specs:
    real = {
        "sofiap_sprout": (1080, 2300, 399),   # moto g pro (suffix must still resolve)
        "mh2lm": (1440, 3120, 564),           # LG G8
        "t2q": (1080, 2400, 394),             # Galaxy S21+
        "bramble": (1080, 2400, 400),         # Pixel 4a 5G
        "redfin": (1080, 2340, 440),          # Pixel 5
    }
    for dev, spec in real.items():
        assert G.screen_for_device(dev) == spec, f"{dev}: screen {G.screen_for_device(dev)} != real {spec}"


def test_kernel_base_is_the_socs_real_family():
    # The kernel base must be the SoC's real Linux kernel, not a random pick — a 5.15 kernel on an A11
    # Snapdragon 855 is impossible. Device-proven: a real Pixel 4 (SD855) reads 4.14. Sourced per SoC.
    from specter import profile as _P
    want = {
        "msmnile": "4.14", "sdm855": "4.14",   # SD855 (device-proven)
        "lito": "4.19", "sm7150": "4.19",       # SD765G/730G (AOSP redbull)
        "lahaina": "5.4",                        # SD888 (NIST)
        "sdm665": "4.14",                        # SD665
    }
    for soc, base in want.items():
        for s in range(20):
            kv = G.kernel_version(_P._seeded(s * 13 + 1), "11", soc)
            assert kv.split("-")[0].rsplit(".", 1)[0] == base, f"{soc}: kernel {kv} base != {base}"


def test_storage_is_the_models_real_sku():
    # A pooled model reports its real base storage SKU, not a RAM-tier-random 32/64/256. All current-pool
    # models are 128GB; a rounded reported capacity lands ~115-122GB after the format reserve.
    from specter import profile as P
    known_128 = {"Pixel 4a (5G)", "Pixel 5", "Pixel 5a", "moto g pro", "LM-G850l", "SM-G996U"}
    seen = set()
    for _ in range(400):
        p = P.generate_unique(None)
        m = p["build_model"]; seen.add(m)
        if m in known_128:
            gb = round(int(p["total_storage"]) / 1e9)
            assert 112 <= gb <= 122, f"{m}: storage {gb}GB not ~128 (SKU)"
    assert known_128 <= seen, f"unpinned pool models: {seen - known_128}"


def test_battery_capacity_is_the_models_real_mAh():
    # A known pool model must report its REAL battery design capacity, not a hash bucket — a per-model DB is
    # what a fingerprinter needs to catch a wrong-but-plausible value. Real retail capacities:
    real_mah = {
        "bramble": 3885, "redfin": 4080, "barbet": 4680, "sofiap": 4000, "mh2lm": 3500, "t2q": 4800,
    }
    for stem, mah in real_mah.items():
        got = G.battery_uah_for(stem + "xyz")   # a variant suffix must still resolve via longest-prefix
        assert got == mah * 1000, f"{stem}: battery {got} uAh != real {mah} mAh"
    # an unmapped codename still falls back to a stable, in-range plausible value
    fb = G.battery_uah_for("some_unknown_codename") // 1000
    assert 2800 <= fb <= 4600, f"fallback battery {fb} mAh out of plausible range"


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


def test_serial_brand_plausible():
    # brand-shaped serials: Samsung "R"+10 (11), Pixel 14, Moto "ZY"+.. (12), generic — all Base34.
    for brand, prefix, length in [("Samsung", "R", 11), ("Google", "", 14),
                                  ("Motorola", "ZY", 12), ("LGE", "", 15), ("Sony", "", 12)]:
        for _ in range(100):
            s = G.serial_for_brand(r, brand)
            assert G.validate("serial", s), f"{brand} serial {s} failed validation"
            assert len(s) == length, f"{brand} serial {s} wrong length"
            assert s.startswith(prefix), f"{brand} serial {s} missing prefix {prefix}"
            assert s == s.upper() and "I" not in s and "O" not in s

def test_old_hex16_serial_now_rejected():
    # regression guard: pure-hex 16-char serials are detectably synthetic and must NOT validate.
    assert not G.validate("serial", G.hex16upper(r))


def test_media_drm_32_hex():
    for _ in range(300):
        assert G.validate("media_drm_id", G.hex32(r))


def test_ram_matches_soc_tier():
    # RAM must be constrained to what the SoC realistically ships with — no 8GB budget phones, no 2GB
    # flagships. Verify each SoC only ever yields a RAM tier from its allowed index set.
    from specter import profile as _P
    for soc, idxs in G._RAM_IDX_FOR_SOC.items():
        allowed_gb = {G._RAM_GB[i] for i in idxs}
        for s in range(30):
            ram_bytes, _ = G.ram_storage_bytes(_P._seeded(s * 7 + 3), soc)
            gb = round(int(ram_bytes) / (1024 ** 3))
            # reported RAM is nominal minus a 3-8% shave, so it rounds to the nominal tier
            assert gb in allowed_gb or (gb + 1) in allowed_gb, \
                f"soc {soc}: ram {gb}GB not in allowed {sorted(allowed_gb)}"
    # a budget SoC never yields a flagship RAM tier
    for s in range(50):
        ram_bytes, _ = G.ram_storage_bytes(_P._seeded(s), "trinket")   # SD665/moto g7 play class
        assert int(ram_bytes) / (1024 ** 3) <= 4.5, "trinket (budget) must not get >4GB RAM"


def test_ram_matches_the_actual_generated_model():
    # Model-grounded (not self-referential): over real generated profiles, the reported RAM must round to a
    # size the CLAIMED MODEL actually shipped. The old test validated the SoC table against itself and stayed
    # green while ~72% of profiles claimed a RAM size the model never sold. Real retail RAM SKUs per model:
    from specter import profile as P
    real_ram_gb = {
        "Pixel 4a (5G)": {6}, "Pixel 5": {8}, "Pixel 5a": {6}, "moto g pro": {4},
        "LM-G850l": {6}, "SM-G996U": {8},   # LG G8 6GB · Galaxy S21+ 8GB
    }
    seen = set()
    for _ in range(600):
        p = P.generate_unique(None)
        m = p["build_model"]
        seen.add(m)
        want = real_ram_gb.get(m)
        if want is None:
            continue   # only assert models whose real SKU we've pinned
        gb = round(int(p["total_ram"]) / (1024 ** 3))
        # reported totalMem is nominal minus a 3-8% kernel/reserved shave, so an 8GB device reports ~7-8.
        assert gb in want or (gb + 1) in want, f"{m}: reported {gb}GB, real SKU is {sorted(want)}GB"
    # the whole current US pool is covered by the pin above (guards against a model slipping through unpinned)
    assert real_ram_gb.keys() >= seen, f"unpinned models generated: {seen - real_ram_gb.keys()}"


def test_every_hardware_soc_has_a_ram_tier():
    # The RAM fallback was SILENT: a SoC present in hardware.json but absent from _RAM_IDX_FOR_SOC hit the
    # 3/4/6GB default, so a Galaxy S22 (taro) generated as low as 3.8GB. Assert every SoC the pool can pick
    # is mapped explicitly (fail-closed), so a newly-added device can't silently mis-size its RAM.
    from specter import profile as P
    hw = P._load_hardware()
    pool_socs = {e["soc"] for cn, e in hw.items() if cn != "_default"}
    missing = sorted(s for s in pool_socs if s not in G._RAM_IDX_FOR_SOC)
    assert not missing, f"SoCs in hardware.json with no RAM tier (fall to default): {missing}"


def test_kernel_version_android_tag_coherent_with_release():
    # The kernel's -androidN branch tag can never be NEWER than the OS release running it. release<10
    # devices get a -perf kernel (no -androidN tag). Verify across many seeds + releases.
    import re as _re
    from specter import profile as _P
    for s in range(400):
        for rel in ("9", "10", "11", "12", "13"):
            kv = G.kernel_version(_P._seeded(s), rel)
            m = _re.search(r"-android(\d+)", kv)
            if m:
                n = int(m.group(1))
                assert n <= int(rel), f"kernel {kv} tag android{n} > release {rel}"
                assert int(rel) >= 10, f"-androidN tag on release {rel}: {kv}"


def test_media_drm_accepts_32_and_64_hex():
    # Real Widevine PROPERTY_DEVICE_UNIQUE_ID is 16 OR 32 bytes (32/64 hex) depending on device, so a
    # harvested/hand-entered id of either length must validate; other lengths + non-hex must be rejected.
    assert G.validate("media_drm_id", "0" * 32)
    assert G.validate("media_drm_id", "a" * 64)
    assert G.validate("media_drm_id", "2dece312ff2b0e4d6de43551804fe42462f078cd542ef7418d250be4dd0e0739")  # real Pixel 4a
    assert not G.validate("media_drm_id", "0" * 16)   # too short
    assert not G.validate("media_drm_id", "0" * 48)   # in-between length
    assert not G.validate("media_drm_id", "0" * 65)   # too long
    assert not G.validate("media_drm_id", "Z" * 32)   # non-hex


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
        # area code must be a REAL assigned US code (not a random structurally-valid one)
        assert p[1:4] in G._US_AREA_CODES, f"area code {p[1:4]} not in real-area-code table"
        # exchange is never an N11 service code (211/411/911 etc.)
        assert p[5:7] != "11", f"N11 service code as exchange: {p}"


def test_us_area_codes_are_well_formed_and_unique():
    # every entry is a valid NANP area code ([2-9] then 2 digits) and the list has no duplicates
    import re as _re
    assert len(G._US_AREA_CODES) == len(set(G._US_AREA_CODES)), "duplicate area codes"
    for ac in G._US_AREA_CODES:
        assert _re.fullmatch(r"[2-9]\d\d", ac), f"malformed area code {ac}"
        assert ac[1:] != "11", f"N11 is not a valid area code: {ac}"


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


def test_gsf_within_java_long_max():
    """GSF must never exceed Long.MAX (else Java Long.parseLong/getLong throws)."""
    LONG_MAX = 9_223_372_036_854_775_807
    for _ in range(5000):
        v = int(G.gsf(r))
        assert 0 < v <= LONG_MAX, f"gsf {v} exceeds Long.MAX"
        assert G.validate("gsf_id", str(v))


def test_advertising_id_is_rfc4122_v4():
    for _ in range(500):
        v = G.uuid(r)
        assert v[14] == "4", f"version nibble not 4: {v}"
        assert v[19] in "89ab", f"variant bits wrong: {v}"
        assert G.validate("advertising_id", v)


# ---- factory-reset timestamp (the FPJS Pro `factoryReset` smart signal) ----
# FPJS re-identified the device across three full identity rotations via the factory-reset time,
# read from the mtime of dirs written once at reset (/data/misc/profiles, /data/bootchart).
# Spoofing it must produce a value that is stable per identity and PLAUSIBLE, not just different.

def test_factory_reset_epoch_is_a_plausible_past_unix_time():
    import time
    now = int(time.time())
    for _ in range(500):
        v = G.factory_reset_epoch(r)
        assert v.isdigit(), f"must be a digit string, got {v!r}"
        e = int(v)
        # never in the future — a device reset tomorrow is impossible and is itself a tell
        assert e < now, f"{e} is in the future (now={now})"
        # and not absurdly old: Android device, so within the last ~6 years
        assert e > now - 6 * 365 * 86400, f"{e} is implausibly old"


def test_factory_reset_epoch_validates():
    for _ in range(200):
        assert G.validate("factory_reset_epoch", G.factory_reset_epoch(r))


def test_factory_reset_epoch_rejects_bad_values():
    for bad in ("", "abc", "-1", "0", "99999999999999", "1.5"):
        assert not G.validate("factory_reset_epoch", bad), f"{bad!r} should be rejected"


def test_factory_reset_epoch_varies():
    """Distinct identities must get distinct reset times, or it becomes a shared linking signal."""
    seen = {G.factory_reset_epoch(r) for _ in range(200)}
    assert len(seen) > 150, f"too few distinct values ({len(seen)}/200) — weak entropy"


def test_factory_reset_epoch_is_deterministic_no_wallclock():
    """The value must be a pure function of (r, patch) — no wall-clock read — or Python (which
    generates the profile) and Java (which re-derives it only in the parity harness) can silently
    diverge. Regression guard for the clamp-branch parity bug: a recent patch that WOULD have tripped
    the old wall-clock clamp must still be reproducible bit-for-bit from the same seed."""
    from specter import profile as P
    recent = "2025-06-01"  # newer than any real pool device — the case the old clamp mishandled
    a = G.factory_reset_epoch(P._seeded(42), recent)
    b = G.factory_reset_epoch(P._seeded(42), recent)
    assert a == b, "not deterministic for a recent patch"
    # and the offset stays within the documented 1..540-day window above the patch
    import calendar
    base = calendar.timegm((2025, 6, 1, 0, 0, 0, 0, 0, 0))
    off = int(a) - base
    assert 0 < off <= (G.FACTORY_RESET_MAX_DAYS_AFTER_PATCH + 1) * G.SECONDS_PER_DAY, off
