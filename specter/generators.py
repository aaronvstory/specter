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

_KERNEL_BASES = ["4.9", "4.14", "4.19", "5.4", "5.10", "5.15"]

def kernel_version(r):
    """os.version kernel string, e.g. '4.14.180-perf-g0a1b2c3' (mirrors Java kernelVersion)."""
    base = _KERNEL_BASES[r(len(_KERNEL_BASES))]
    patch = 50 + r(250)
    tag = "-perf" if r(2) == 0 else "-android" + str(10 + r(4))
    return f"{base}.{patch}{tag}-g" + hexs(r, 4)

def build_host(r):
    """Build.HOST — model-agnostic build-farm hostname (mirrors Java buildHost). Real value leaks the
    Google build host (e.g. 'abfarm-00902'), incoherent on a spoofed Samsung/Moto."""
    pre = ["abfarm", "wprd", "SWDG", "vf-build", "r-build", "prod"]
    return pre[r(len(pre))] + "-" + digits(r, 5)

_SOC_BY_DEVICE = {
    "flame": "msmnile", "coral": "msmnile", "redfin": "lito", "bramble": "lito",
    "sunfish": "sm6150", "barbet": "lito", "oriole": "gs101", "raven": "gs101",
    "blueline": "sdm845", "crosshatch": "sdm845", "walleye": "msm8998",
    "h1": "msm8996", "RS988": "msm8996",
}
_SOC_POOL = ["msmnile", "lito", "sdm845", "msm8998", "msm8996", "sm8250",
             "sm8350", "sm6150", "kona", "lahaina", "trinket", "bengal"]

def soc_platform(r, device):
    """ro.board.platform (SoC codename) — device-coherent where known, else a real-SoC-pool pick
    (mirrors Java socPlatform). Never a made-up string, never more-wrong than the real leak."""
    known = _SOC_BY_DEVICE.get(device or "")
    if known is not None:
        return known
    return _SOC_POOL[r(len(_SOC_POOL))]

_RADIO_PREFIXES = ["g8150", "g7250", "g6150", "M8998", "M8250", "MPSS.HI"]

def radio_version(r):
    """Build.getRadioVersion() baseband string (mirrors Java radioVersion). Confirmed FP leak."""
    pre = _RADIO_PREFIXES[r(len(_RADIO_PREFIXES))]
    return f"{pre}-{digits(r,5)}-{digits(r,6)}-" + chr(ord('A')+r(6)) + f"-{digits(r,7)}"

def bootloader(r, brand, device):
    """Build.BOOTLOADER — DEVICE-coherent, generic-shaped (mirrors Java bootloader). Derived from the
    picked device codename so it can never imply a different model than the one being spoofed."""
    b = (brand or "").lower()
    dev = device or "device"
    if b == "google":
        return f"{dev.lower()}-{1 + r(3)}.{r(9)}-{digits(r, 7)}"
    if b == "samsung":
        code = dev.replace("SM-", "").upper()
        return code + "XXU" + str(1 + r(9)) + chr(ord('A')+r(26)) + chr(ord('A')+r(26)) + chr(ord('A')+r(26))
    if b == "motorola":
        return f"MBM-{digits(r, 2)}.{digits(r, 2)}-{digits(r, 3)}"
    if b == "lge":
        return f"LGE-{dev.upper()}-{digits(r, 4)}"
    return "BL" + chr(ord('A')+r(26)) + f"{digits(r, 2)}.{digits(r, 4)}-{digits(r, 4)}"

_RAM_GB = [3, 4, 6, 8, 12]
_STORAGE_GB = [32, 64, 128, 256]

def total_ram_bytes(r):
    """total RAM in bytes as ActivityManager.MemoryInfo.totalMem reports (mirrors Java totalRamBytes)."""
    gb = _RAM_GB[r(len(_RAM_GB))]
    nominal = gb * 1024 * 1024 * 1024
    reported = nominal - (nominal * (3 + r(6)) // 100)
    return str((reported // (1024 * 1024)) * 1024 * 1024)

def total_storage_bytes(r):
    """total internal storage in bytes (mirrors Java totalStorageBytes)."""
    gb = _STORAGE_GB[r(len(_STORAGE_GB))]
    nominal = gb * 1000 * 1000 * 1000
    return str(nominal * (90 + r(5)) // 100)

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

def phone_for_country(r, kind):
    # USA-only build: always NANP. (kept for the build_profile call signature)
    return phone_us(r)

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
    "310004": "89148000",  # Verizon
    "310090": "89014104",  # AT&T
    "312530": "89011201",  # Sprint
    "311882": "89014103",  # Mint Mobile (T-Mobile MVNO)
    "310240": "89014103",  # T-Mobile
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

FIRST_NAMES = [
    "james","john","robert","michael","david","william","richard","joseph","thomas","charles",
    "mary","patricia","jennifer","linda","elizabeth","susan","jessica","sarah","karen","emily",
    "daniel","matthew","anthony","mark","paul","steven","andrew","joshua","kevin","brian",
    "amanda","ashley","stephanie","nicole","laura","megan","hannah","olivia","emma","sophia",
    "chris","ryan","jacob","tyler","aaron","nathan","adam","justin","brandon","sean",
    "rachel","lauren","victoria","natalie","grace","chloe","zoe","ella","lily","mia",
]
LAST_NAMES = [
    "smith","johnson","williams","brown","jones","garcia","miller","davis","rodriguez","martinez",
    "hernandez","lopez","gonzalez","wilson","anderson","thomas","taylor","moore","jackson","martin",
    "lee","perez","thompson","white","harris","sanchez","clark","ramirez","lewis","robinson",
    "walker","young","allen","king","wright","scott","torres","nguyen","hill","flores",
    "green","adams","nelson","baker","hall","rivera","campbell","mitchell","carter","roberts",
]
EMAIL_PROVIDERS = [
    "gmail.com","gmail.com","gmail.com","outlook.com","outlook.com","yahoo.com","hotmail.com","icloud.com",
]

def gmail(r):
    """Realistic-looking email: real first/last name in a common pattern + provider."""
    first = FIRST_NAMES[r(len(FIRST_NAMES))]
    last = LAST_NAMES[r(len(LAST_NAMES))]
    provider = EMAIL_PROVIDERS[r(len(EMAIL_PROVIDERS))]
    pattern = r(6)
    if pattern == 0:
        local = f"{first}.{last}"
    elif pattern == 1:
        local = f"{first}{last}"
    elif pattern == 2:
        local = f"{first}_{last}"
    elif pattern == 3:
        local = f"{first}{last[0]}"
    elif pattern == 4:
        local = f"{first}.{last}{digits(r, 2)}"
    else:
        local = f"{first}{last}{1970 + r(40)}"
    return f"{local}@{provider}"

email = gmail

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
        "gmail":               lambda v: bool(re.fullmatch(r"[a-z0-9]([a-z0-9._-]{0,30}[a-z0-9])?@(gmail\.com|outlook\.com|yahoo\.com|hotmail\.com|proton\.me|icloud\.com)", v)),
    }
    fn = checks.get(key)
    return True if fn is None else fn(value)
