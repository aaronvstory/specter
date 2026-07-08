"""
generators.py — individual identifier value generators + validators.

Each generator is a pure function taking a random-int source r(n)->[0,n). Kept separate
from profile assembly so every one is independently unit-tested (format + validity).

Validators (validate_*) are the runtime checks: an identifier that fails its own validator
is a bug (e.g. an IMEI that fails Luhn, an ICCID of wrong length). Tests assert both.
"""
import re

# ---------- primitives ----------
def hexs(r, nbytes):
    return "".join("0123456789abcdef"[r(16)] for _ in range(nbytes * 2))

def digits(r, n):
    return "".join(str(r(10)) for _ in range(n))

def luhn_check_digit(num: str) -> str:
    s = 0
    for i, ch in enumerate(reversed(num)):
        d = int(ch)
        if i % 2 == 0:  # positions relative to the check digit being appended
            d *= 2
            if d > 9:
                d -= 9
        s += d
    return str((10 - s % 10) % 10)

def luhn_valid(num: str) -> bool:
    s = 0
    for i, ch in enumerate(reversed(num)):
        d = int(ch)
        if i % 2 == 1:
            d *= 2
            if d > 9:
                d -= 9
        s += d
    return s % 10 == 0

# ---------- generators ----------
def hex16(r):        return hexs(r, 8)                 # android_id: 16 hex chars
def hex16upper(r):   return hexs(r, 8).upper()         # serial
def hex32(r):        return hexs(r, 16)                # media_drm deviceUniqueId (16 bytes)

# Real 8-digit TAC (Type Allocation Code) prefixes by manufacturer. An IMEI's first 8 digits
# identify the make/model; a random TAC can be rejected by checks that validate TAC-against-brand.
# These are real, allocated TAC ranges (not model-exact, but brand-plausible).
_TAC_BY_BRAND = {
    "samsung":  ["35207609", "35316805", "35847909", "35692106"],
    "google":   ["35815807", "35854108", "35161511"],
    "motorola": ["35462106", "35404007", "35123456"],
    "oneplus":  ["86293403", "86891303", "86651004"],
    "lge":      ["35295406", "35878705"],
    "xiaomi":   ["86412604", "86734703"],
    "huawei":   ["86188403", "86544603"],
    "sony":     ["35643606", "35128907"],
    "asus":     ["35316906", "35847008"],
    "oppo":     ["86234503"],
    "poco":     ["86412604"],
    "redmi":    ["86734703"],
}

def _tac_for_brand(r, brand):
    tacs = _TAC_BY_BRAND.get((brand or "").lower(), ["35000000"])
    return tacs[r(len(tacs))]

def imei(r, tac=None):
    """15-digit Luhn-valid IMEI. If a TAC is given, use it as the first 8 digits (brand-coherent)."""
    if tac and len(tac) == 8 and tac.isdigit():
        body = tac + digits(r, 6)                      # TAC(8) + serial(6) = 14, then check digit
    else:
        body = digits(r, 14)
    return body + luhn_check_digit(body)               # 15-digit, Luhn-valid

def uuid(r):
    # RFC 4122 v4: set version nibble to 4 and variant bits to 10xx. Some ad-id validators
    # reject a UUID without these, so build them explicitly.
    b = [r(256) for _ in range(16)]
    b[6] = (b[6] & 0x0F) | 0x40   # version 4
    b[8] = (b[8] & 0x3F) | 0x80   # variant 10xx
    h = "".join(f"{x:02x}" for x in b)
    return f"{h[0:8]}-{h[8:12]}-{h[12:16]}-{h[16:20]}-{h[20:32]}"

def mac_upper(r):
    b = [r(256) for _ in range(6)]
    b[0] = (b[0] & 0xFE) | 0x02                        # locally-administered, unicast
    return ":".join(f"{x:02X}" for x in b)

def mac_lower(r):
    return mac_upper(r).lower()

def phone_us(r):
    # NANP: area code [2-9]XX, exchange [2-9]XX, 4 digits. Prefix country code 1.
    area = str(2 + r(8)) + digits(r, 2)
    exch = str(2 + r(8)) + digits(r, 2)
    return "1" + area + exch + digits(r, 4)

def phone_e164(number11):
    """+1XXXXXXXXXX form. getLine1Number often returns E.164; provide both."""
    return "+" + number11 if not number11.startswith("+") else number11

def imsi(r, mccmnc):
    return mccmnc + digits(r, 15 - len(mccmnc))        # IMSI = MCC+MNC+MSIN, 15 digits

# Real US carrier ICCID issuer-identifier prefixes (after 89 = telecom, 01 = US country code).
# Keyed by MCC+MNC so the SIM serial is coherent with the assigned carrier.
_ICCID_IIN = {
    "310260": "89014103",  # T-Mobile
    "310160": "89014103",  # T-Mobile
    "311480": "89148000",  # Verizon
    "310410": "89014104",  # AT&T
    "310030": "89014104",  # AT&T
    "310120": "89011201",  # Sprint
    "311580": "89011580",  # US Cellular
    "311870": "89011870",  # Boost
}

def iccid(r, mccmnc=None):
    """20-digit ICCID, Luhn-valid, with an issuer prefix consistent with the carrier when given."""
    iin = _ICCID_IIN.get(mccmnc, "890114")        # generic US telecom prefix as fallback
    body = iin + digits(r, 19 - len(iin))         # pad to 19 digits before the check digit
    return body + luhn_check_digit(body)          # 20-digit Luhn

LONG_MAX = 9_223_372_036_854_775_807  # Java signed 64-bit max

def gsf(r):
    # GSF android_id is a signed 64-bit long rendered as decimal. Must stay <= Long.MAX,
    # else Java Long.parseLong()/Cursor.getLong() throws. Range: [1e18, Long.MAX].
    lo = 1_000_000_000_000_000_000
    return str(lo + r(LONG_MAX - lo))

def gmail(r):
    first = "".join("abcdefghijklmnopqrstuvwxyz"[r(26)] for _ in range(3 + r(5)))
    return f"{first}{digits(r,3)}@gmail.com"

def ssid(r):
    nets = ["NETGEAR", "ATT", "xfinitywifi", "Linksys", "TP-Link_", "SpectrumSetup-"]
    return nets[r(len(nets))] + digits(r, 2)

# ---------- validators (runtime correctness checks) ----------
def validate(key, value):
    """Return True if value has the right format for key. Used by profile.validate()."""
    checks = {
        "android_id":          lambda v: bool(re.fullmatch(r"[0-9a-f]{16}", v)),
        "serial":              lambda v: bool(re.fullmatch(r"[0-9A-F]{16}", v)),
        "media_drm_id":        lambda v: bool(re.fullmatch(r"[0-9a-f]{32}", v)),
        "imei1":               lambda v: len(v) == 15 and v.isdigit() and luhn_valid(v),
        "imei2":               lambda v: len(v) == 15 and v.isdigit() and luhn_valid(v),
        "advertising_id":      lambda v: bool(re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", v)),
        "bluetooth_mac":       lambda v: bool(re.fullmatch(r"([0-9A-F]{2}:){5}[0-9A-F]{2}", v)),
        "wifi_mac":            lambda v: bool(re.fullmatch(r"([0-9A-F]{2}:){5}[0-9A-F]{2}", v)),
        "wifi_bssid":          lambda v: bool(re.fullmatch(r"([0-9a-f]{2}:){5}[0-9a-f]{2}", v)),
        "mobile_number":       lambda v: bool(re.fullmatch(r"1[2-9]\d{2}[2-9]\d{6}", v)),
        "sim_subscriber_imsi": lambda v: len(v) == 15 and v.isdigit(),
        "sim_serial_iccid":    lambda v: len(v) == 20 and v.isdigit() and luhn_valid(v),
        "gsf_id":              lambda v: v.isdigit() and 0 < int(v) <= LONG_MAX,
        "gmail":               lambda v: bool(re.fullmatch(r"[a-z]{3,}\d{3}@gmail\.com", v)),
    }
    fn = checks.get(key)
    return True if fn is None else fn(value)
