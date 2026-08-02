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

# Real device serials are NOT pure hex — broader uppercase-alnum alphabet, brand-specific length, and a
# fixed leading prefix (Samsung serials always start "R", 11 chars; a real Pixel serial is 14 alnum incl
# letters like Z/P absent from hex). hex16upper (16 pure-hex) is detectably synthetic for a Pixel/Galaxy.
# We replicate the FORMAT (prefix + length + alphabet), not decodable factory/date fields. Mirrors Java.
_SERIAL_ALPHABET = "0123456789ABCDEFGHJKLMNPQRSTUVWXYZ"  # 34 chars (no I, no O — confusables)

def _serial_spec_for_brand(brand):
    b = (brand or "").lower()
    if "samsung" in b:  return ("R", 11)     # Samsung: "R" + 10, 11 total
    if "google" in b:   return ("", 14)      # Pixel: 14 alnum (e.g. 9B151FFAZ00FPF)
    if "motorola" in b or b == "moto": return ("ZY", 12)
    if "lg" in b:       return ("", 15)
    return ("", 12)

def serial_for_brand(r, brand):
    """Brand-plausible serial: fixed prefix + alphanumeric body, correct per-brand length. Mirrors Java."""
    prefix, length = _serial_spec_for_brand(brand)
    out = prefix
    while len(out) < length:
        out += _SERIAL_ALPHABET[r(len(_SERIAL_ALPHABET))]
    return out

# Real 8-digit TAC (Type Allocation Code) prefixes by manufacturer. An IMEI's first 8 digits
# identify the make/model; a random TAC can be rejected by checks that validate TAC-against-brand.
# These are real, allocated TAC ranges (not model-exact, but brand-plausible).
_TAC_BY_BRAND = {
    "samsung":  ["35207609", "35316805", "35847909", "35692106"],
    "google":   ["35815807", "35854108", "35161511"],
    "motorola": ["35462106", "35404007"],   # dropped "35123456" (sequential filler, not a real GSMA TAC)
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

# Real Linux kernel base per SoC — an A11-era Snapdragon 855 ships 4.14 (device-proven on a real Pixel 4),
# never 5.15. The base was otherwise drawn uniformly at random, so a profile could claim an impossible
# kernel for its silicon/OS. Sourced: NIST CAVP (4.19 on SD750-class, 5.4 on SD888/Exynos2100), Sony SODP
# (4.14 on SD855/845/665/660), AOSP redbull (4.19 on redfin/sunfish). Unknown SoC -> keep the drawn base.
# MUST match Java KERNEL_BASE_BY_SOC.
_KERNEL_BASE_BY_SOC = {
    "sdm660": "4.4", "msm8998": "4.4", "sdm845": "4.9", "sdm670": "4.9",
    "sm6150": "4.14", "sm7150": "4.19", "lito": "4.19", "sdm665": "4.14",
    "msmnile": "4.14", "sdm855": "4.14", "kona": "4.19", "lahaina": "5.4",
    "taro": "5.10", "kalama": "5.15", "gs101": "5.10",
    "exynos9820": "4.14", "exynos9825": "4.14", "exynos990": "4.19", "exynos2100": "5.4",
    "exynos9610": "4.14", "exynos9611": "4.14", "exynos1280": "5.10", "exynos7884": "4.4",
    "exynos7885": "4.4", "exynos7904": "4.4", "exynos7870": "4.4", "exynos850": "4.14", "exynos9810": "4.9",
    "trinket": "4.14", "bengal": "4.19",
}

def kernel_version(r, release="13", soc=""):
    """os.version kernel string, e.g. '4.14.180-perf-g0a1b2c3' (mirrors Java kernelVersion).

    The '-androidN' branch tag must be COHERENT with the OS: a kernel can't be branched for a NEWER
    Android than the one running it. Keep the exact RNG draw order (base, patch, branch, tag-num, hex)
    for byte-parity, then CLAMP the drawn android tag to the profile release. If release < 10 (no
    -androidN tag exists there) fall back to a '-perf' kernel (common on Android <=9).
    """
    drawn_base = _KERNEL_BASES[r(len(_KERNEL_BASES))]   # keep the draw (byte-parity); SoC overrides below
    base = _KERNEL_BASE_BY_SOC.get(soc) or drawn_base
    patch = 50 + r(250)
    branch = r(2)            # 0 => -perf, 1 => -androidN
    tagnum = 10 + r(4)       # 10..13 (draw consumed regardless, for parity)
    try:
        rel = int(str(release).strip().split(".")[0])
    except (ValueError, TypeError):
        rel = 13
    if branch == 0 or rel < 10:
        tag = "-perf"
    else:
        tag = "-android" + str(min(tagnum, rel))   # never newer than the OS
    return f"{base}.{patch}{tag}-g" + hexs(r, 4)

def build_host(r):
    """Build.HOST — model-agnostic build-farm hostname (mirrors Java buildHost). Real value leaks the
    Google build host (e.g. 'abfarm-00902'), incoherent on a spoofed Samsung/Moto."""
    pre = ["abfarm", "wprd", "SWDG", "vf-build", "r-build", "prod"]
    return pre[r(len(pre))] + "-" + digits(r, 5)

# ---------- factory-reset timestamp ----------
# FPJS Pro reports `factoryReset` as a first-class smart signal, read from the mtime of directories
# written once at reset (/data/misc/profiles, /data/bootchart — readable without root). PROVEN
# 2026-07-25: it re-identified the device across three full identity rotations, so a real value here
# defeats every other field we spoof.
#
# Coherence, not just difference: the value must land AFTER the running build's security patch (a
# device cannot be reset before its own OS was built) and BEFORE now. So it is generated as an offset
# from the patch date rather than from a bare epoch — the patch date is already in the profile, which
# makes the pair coherent by construction instead of by luck.
#
# Deterministic in r() alone (no wall-clock inside the seeded path) so Java mirrors it byte-for-byte.
# RNG order: one draw for the day offset, one for the seconds-within-day.
FACTORY_RESET_MAX_DAYS_AFTER_PATCH = 540   # ~18 months: a plausible ownership window
SECONDS_PER_DAY = 86400


# Android release -> API level (Build.VERSION.SDK_INT / ro.build.version.sdk). A profile's claimed
# release and its SDK must agree (e.g. Android 11 -> SDK 30, Android 12 -> SDK 31) — a mismatch is itself
# a fingerprint. Pure
# function of the release string (no RNG), so Java mirrors it byte-for-byte. Handles "10"/"11" and
# older "9"/"8.1.0" forms; unknown -> a safe modern default.
_SDK_BY_RELEASE = {
    "15": 35, "14": 34, "13": 33, "12L": 32, "12": 31, "11": 30, "10": 29,
    "9": 28, "8.1.0": 27, "8.1": 27, "8.0.0": 26, "8.0": 26, "7.1.2": 25,
    "5.0.2": 21, "4.4.4": 19, "4.4.2": 19, "4.4": 19, "4.3": 18, "4.2.2": 17, "4.2": 17,
    "7.1.1": 25, "7.1": 25, "7.0": 24, "6.0.1": 23, "6.0": 23, "5.1.1": 22,
    "5.1": 22, "5.0.1": 21, "5.0": 21,
}


def gpu_hw_for(renderer):
    """GPU driver family (adreno/mali/powervr) behind ro.hardware.{egl,vulkan,gralloc}, derived from the
    GL renderer string. Coherent with the claimed device's GPU: a Mali renderer -> "mali", an Adreno
    renderer -> "adreno". Falls back to "adreno" (the common US-market Qualcomm case) when unknown, since
    every US device Specter picks is Qualcomm or Exynos. Pure lookup (no RNG) -> byte-parity safe."""
    r = (renderer or "").lower()
    if "mali" in r:
        return "mali"
    if "adreno" in r:
        return "adreno"
    if "powervr" in r:
        return "powervr"
    return "adreno"


def sdk_for_release(release):
    """API level for an Android release string. Falls back on the major version, then 30."""
    if not release:
        return 30
    if release in _SDK_BY_RELEASE:
        return _SDK_BY_RELEASE[release]
    major = release.split(".")[0]
    return _SDK_BY_RELEASE.get(major, 30)


# Real LAUNCH API level per model (the Android the device SHIPPED with), keyed on Build.MODEL. A device that
# launched on an older OS and updated has first_api_level < current sdk — first_api==sdk is a subtle coherence
# tell for an SDK that reads both. Sourced from GSMArena "OS: Android X (launch), upgradable to Y" (2026-07-28,
# Samsung set — high confidence, careful cases: Note8=25, Note9=27, S8=24, A6-family=26). Extend per brand.
# Missing model -> launch_api_for falls back to the current sdk (== the old behaviour).
_LAUNCH_API_BY_MODEL = {
    "SM-A013G": 29, "SM-A205W": 28, "SM-A405FN": 28, "SM-A505F": 28, "SM-A507FN": 28,
    "SM-A515F": 29, "SM-A525F": 30, "SM-A600F": 26, "SM-A605G": 26, "SM-A705FN": 28,
    "SM-A715F": 29, "SM-A750GN": 26, "SM-G970F": 28, "SM-G973F": 28, "SM-G975F": 28,
    "SM-G977B": 28, "SM-G960F": 26, "SM-G965F": 26, "SM-G950F": 24, "SM-G955F": 24,
    "SM-N960F": 27, "SM-N950F": 25, "SM-N975F": 28, "SM-N986B": 29, "SM-G770F": 29,
    "SM-G780F": 29, "SM-G781B": 29, "SM-G991B": 30, "SM-G988B": 29, "SM-M205F": 27,
    "SM-M215F": 29,
    # US-carrier Samsung flagships/mid (2026-07-31). Launch API = the Android the model SHIPPED with,
    # which for every Galaxy S is the release current at its announcement:
    #   S20 (Feb 2020) = Android 10/29 · S21 + S21 FE = Android 11/30 · S22 (Feb 2022) = Android 12/31
    #   S23 (Feb 2023) = Android 13/33 · A52 5G (Mar 2021) = 30 · A53 5G (Mar 2022) = 31 · A13 5G = 31
    "SM-G981U": 29, "SM-G986U": 29, "SM-G988U": 29,
    "SM-G991U": 30, "SM-G996U": 30, "SM-G998U": 30, "SM-G990U": 30,
    "SM-S901U": 31, "SM-S906U": 31, "SM-S908U": 31,
    "SM-S918U": 33,
    "SM-A526U": 30, "SM-A536U": 31, "SM-A136U": 31,
    # Xiaomi/Redmi/POCO, Motorola, OnePlus (2026-07-28, GSMArena-sourced, launch<current only). MIUI
    # traps handled: Mi A1=25, POCOPHONE F1=27, Redmi Note 5 Pro=25 (all shipped older Android than MIUI).
    "GM1900": 28, "GM1910": 28, "MI 9": 28,
    "Mi 8 Explorer": 27, "Mi 8 Pro": 27, "Mi 9T": 28,
    "Mi 9T Pro": 28, "Mi MIX 2": 25, "Mi MIX 2S": 26,
    "Moto G (4)": 23, "Moto G (5S) Plus": 25, "Moto Z2": 25,
    "Moto Z2 Play": 25, "Moto Z3 Play": 27, "ONEPLUS A3000": 23,
    "ONEPLUS A3003": 23, "ONEPLUS A5000": 25, "POCOPHONE F1": 27,
    "Redmi 6": 27, "Redmi 6A": 27, "Redmi 7": 28,
    "Redmi K20": 28, "Redmi K20 Pro": 28, "Redmi Note 5 Pro": 25,
    "Redmi Note 8 Pro": 28, "moto g pro": 29, "moto g(6)": 26,
    "moto g(6) plus": 26, "moto g(7)": 28, "moto x4": 25,
}


def launch_api_for(model, current_sdk):
    """ro.product.first_api_level: the device's LAUNCH API for known models, else the current sdk (so
    first_api==sdk, the prior behaviour, for anything not yet mapped). Clamp to <= current_sdk defensively —
    a launch API above the running OS is impossible. Pure lookup, no RNG (byte-parity safe)."""
    la = _LAUNCH_API_BY_MODEL.get(model)
    if la is None or la > current_sdk:
        return current_sdk
    return la


# Screen resolution + density (getResources().getDisplayMetrics(): widthPixels/heightPixels/densityDpi)
# — a stable, high-entropy signal FingerprintJS reads via a Java API (invisible to the native tracer),
# which leaked the REAL Pixel 4 (1080x2280@440) on every rotation. Known models use their real spec;
# unknown codenames map DETERMINISTICALLY into a pool of real common configs via a tiny portable hash
# (identical in Java) so the value is coherent, stable per identity, and byte-parity safe (no RNG).
_SCREEN_KNOWN = {
    "flame": (1080, 2280, 440), "coral": (1440, 3040, 560), "redfin": (1080, 2340, 440),
    "bramble": (1080, 2400, 400), "sunfish": (1080, 2340, 440), "barbet": (1080, 2400, 400),
    "oriole": (1080, 2400, 420), "raven": (1440, 3120, 560), "blueline": (1080, 2160, 440),
    "crosshatch": (1440, 2960, 560), "sargo": (1080, 2220, 440), "bonito": (1080, 2160, 400),
    "walleye": (1080, 1920, 420), "taimen": (1440, 2880, 560),
    "beyond1": (1440, 3040, 550), "beyond2": (1440, 3040, 526), "beyond0": (1080, 2280, 438),
    "o1s": (1080, 2400, 421), "t2s": (1080, 2400, 425), "p3s": (1440, 3200, 515),
    "a50": (1080, 2340, 403), "a50s": (1080, 2340, 403), "a70q": (1080, 2400, 393),
    "a30s": (720, 1560, 268), "a10": (720, 1520, 269), "a20": (720, 1560, 294),
    "m21": (1080, 2340, 411), "a51": (1080, 2400, 405), "a71": (1080, 2400, 393),
    # US-pool models that were falling to the random _SCREEN_POOL (their build_device wasn't listed) — e.g.
    # a Galaxy S21+ generated as 720x1520/295dpi, a budget-phone screen, a hard model tell. Real specs:
    # NOTE: "sofiap" is shared by the Moto G Pro (build_device sofiap_ao/sofiap_sprout) AND the Moto G Stylus
    # (sofiap_retail), which have different screens — but only the G Pro is a selectable A11 US pool device, so
    # this value only ever reaches the G Pro. If the Stylus ever becomes selectable, split by the full stem.
    "sofiap": (1080, 2300, 399),   # moto g pro. 6.4" FHD+ (1080x2300, 399ppi)
    "mh2lm": (1440, 3120, 564),    # LG G8 ThinQ (QHD+ POLED)
    "t2q": (1080, 2400, 394),      # Galaxy S21+ (SM-G996U)
    "p3q": (1440, 3200, 515),      # Galaxy S21 Ultra
    "r9q": (1080, 2340, 407),      # Galaxy S21 FE
    "r0q": (1080, 2340, 425),      # Galaxy S22
    "b0q": (1440, 3088, 500),      # Galaxy S22 Ultra
    "x1q": (1440, 3200, 563),      # Galaxy S20
    "y2q": (1440, 3200, 525),      # Galaxy S20+
    "z3q": (1440, 3200, 511),      # Galaxy S20 Ultra
}
_SCREEN_POOL = [
    (1080, 2340, 440), (1080, 2400, 408), (1080, 2280, 440), (1080, 2340, 403),
    (720, 1520, 295), (720, 1560, 269), (1080, 2160, 424), (1440, 3040, 550),
    (1080, 2400, 395), (1080, 1920, 401),
]


def _codename_hash(cn):
    """A tiny, portable, positive 32-bit hash of a codename. MUST match Java Generators.codenameHash."""
    h = 2166136261
    for ch in cn:
        h = ((h ^ ord(ch)) * 16777619) & 0xFFFFFFFF   # FNV-1a, 32-bit
    return h


# Real battery DESIGN capacity (mAh) per pool model, keyed by codename stem (longest-prefix, like the RAM
# and SoC maps). A hash-derived value is stable but wrong-for-the-model (a moto g pro is 5000mAh, not a
# hash bucket); a per-model DB is what a fingerprinter would need to catch a wrong-but-plausible value. These
# are the real retail capacities. Unmapped codenames fall back to the codename hash (stable + in-range).
# MUST match Java BATTERY_MAH_FOR_MODEL.
_BATTERY_MAH_FOR_MODEL = {
    # current US pool
    "bramble": 3885,   # Pixel 4a 5G
    "redfin": 4080,    # Pixel 5
    "barbet": 4680,    # Pixel 5a
    "sofiap": 4000,    # moto g pro (XT2043, KX50 cell)
    "mh2lm": 3500,     # LG G8 ThinQ
    "t2q": 4800,       # Galaxy S21+ (SM-G996U)
    # other real US models the pool can gain
    "flame": 2800, "coral": 3700,          # Pixel 4 / 4 XL
    "sunfish": 3140,                        # Pixel 4a
    "oriole": 4614, "raven": 5003,         # Pixel 6 / 6 Pro
    "o1s": 4000, "p3q": 5000, "r9q": 4500, # S21 / S21 Ultra / S21 FE
    "r0q": 3700, "b0q": 5000,              # S22 / S22 Ultra
    "sargo": 3000, "bonito": 3700,         # Pixel 3a / 3a XL
    "blueline": 2915, "crosshatch": 3430,  # Pixel 3 / 3 XL
}


def battery_uah_for(codename):
    """Battery DESIGN capacity in µAh (BatteryManager full-capacity read). The real per-model value when the
    codename is a known pool model, else a stable hash-derived plausible value. Pure, no RNG -> byte-parity
    safe. MUST match Java Generators.batteryUahFor."""
    cn = (codename or "").lower()
    best = None
    for stem in _BATTERY_MAH_FOR_MODEL:
        if cn.startswith(stem) and (best is None or len(stem) > len(best)):
            best = stem
    mah = _BATTERY_MAH_FOR_MODEL[best] if best else 2800 + (_codename_hash(cn) % 19) * 100
    return mah * 1000


def boot_count_for(android_id):
    """A plausible, per-device-STABLE boot count (Settings.Global.BOOT_COUNT). FingerprintJS/EXADPrinter
    read it as a high-entropy stable integer; leaving it real leaks the host device's true boot count. A
    real used phone has booted tens-to-hundreds of times, so map the android_id hash into [40, 460). Pure
    (no RNG) -> byte-parity safe. MUST match Java Generators.bootCountFor."""
    return 40 + (_codename_hash(android_id or "") % 420)


def screen_for_device(codename):
    """(width, height, densityDpi) for a device codename. Known -> real spec (LONGEST-prefix, since the
    build_device slot can carry a suffix like "sofiap_sprout" that must still match "sofiap"); else a
    deterministic pool pick. Never the real host device unless the profile legitimately claims it. MUST
    match Java screenForDevice."""
    cn = (codename or "").lower()
    if not cn:
        return _SCREEN_POOL[0]
    best = None
    for stem in _SCREEN_KNOWN:
        if cn.startswith(stem) and (best is None or len(stem) > len(best)):
            best = stem
    if best is not None:
        return _SCREEN_KNOWN[best]
    return _SCREEN_POOL[_codename_hash(cn) % len(_SCREEN_POOL)]


def factory_reset_epoch(r, security_patch=None):
    """Unix seconds of a plausible factory reset. Mirrors Java factoryResetEpoch.

    The reset lands 1..N days after the build's `security_patch`, where N is bounded so the value can
    NEVER exceed the patch date by more than ~18 months. There is deliberately NO wall-clock read: the
    result is a pure function of (r, security_patch), so Python (which generates the profile PC-side)
    and Java (which only re-derives it in the byte-parity test harness) always agree for the same seed.

    "Never in the future" holds by construction as long as the pool's patches are older than ~18 months,
    which is enforced by test_factory_reset_is_after_the_build_and_in_the_past. That test IS the guard:
    if the device pool ever gains a phone with a patch newer than ~18 months ago, it goes red and this
    bound (or the pool) must be revisited — a loud failure instead of a silent parity drift.
    """
    import calendar
    if security_patch:
        y, m, d = (int(x) for x in security_patch.split("-"))
        base = calendar.timegm((y, m, d, 0, 0, 0, 0, 0, 0))
    else:
        base = calendar.timegm((2023, 1, 1, 0, 0, 0, 0, 0, 0))
    days = 1 + r(FACTORY_RESET_MAX_DAYS_AFTER_PATCH)
    secs = r(SECONDS_PER_DAY)
    return str(base + days * SECONDS_PER_DAY + secs)


_SOC_BY_DEVICE = {
    "flame": "msmnile", "coral": "msmnile", "redfin": "lito", "bramble": "lito",
    "sunfish": "sm7150", "barbet": "lito", "oriole": "gs101", "raven": "gs101",
    # Corrected SoCs (2026-07-28 dataset audit, kernel-DT/teardown grounded): were mislabelled to the
    # sm6150 default. a71naxx=SD730(sm7150), bonito/sargo=SD670(sdm670), kiev=SD750G / nairo=SD765G (both lito).
    "a71naxx": "sm7150", "bonito": "sdm670", "sargo": "sdm670", "kiev": "lito", "nairo": "lito",
    "blueline": "sdm845", "crosshatch": "sdm845", "walleye": "msm8998",
    "sailfish": "msm8996", "marlin": "msm8996", "taimen": "msm8998",
    "h1": "msm8996", "elsa": "msm8996", "joan": "msm8998",
}
# Draw-free default SoC — a real mid-range Snapdragon. Used only when neither the hardware bundle nor
# the known-Pixel table has an entry (a non-selectable device, which generated profiles never pick).
_DEFAULT_SOC = "sm6150"


def soc_platform(product, hw_soc=None):
    """ro.board.platform (SoC codename), COHERENT with the device this identity claims to be.

    Prefers `hw_soc` — the SoC of the per-model hardware bundle (data/hardware.json) — so the reported
    SoC always matches the GPU/cpuinfo/etc. the same profile carries. Falls back to the known-Pixel
    table keyed on the PRODUCT codename (Build.PRODUCT, e.g. "flame", "h1_lra_us" — NOT the marketing
    device name), then a fixed default. PURE (no RNG): a real device's SoC is a fact of the model, not
    a random draw — the old random fallback produced INCOHERENT SoCs (a Galaxy S21 reporting a budget
    chip). Being draw-free also keeps Java↔Python byte-parity trivially (a constant shifts nothing)."""
    if hw_soc:
        return hw_soc
    if product:
        key = product.lower()
        known = _SOC_BY_DEVICE.get(key)
        if known is not None:
            return known
        us = key.find("_")                   # strip LG regional suffix: h1_lra_us -> h1
        if us > 0:
            known = _SOC_BY_DEVICE.get(key[:us])
            if known is not None:
                return known
    return _DEFAULT_SOC

_RADIO_PREFIXES = ["g8150", "g7250", "g6150", "M8998", "M8250", "MPSS.HI"]

# Real modem/baseband prefix per SoC. Build.getRadioVersion() otherwise drew a prefix uniformly at random,
# so ~5/6 of profiles reported a baseband that contradicts the claimed silicon (a Pixel 6/Tensor reporting
# the SD855 modem). Each SoC has ONE real modem family, so key the prefix on the SoC. MUST match Java
# RADIO_PREFIX_BY_SOC. Unknown SoC -> the generic-modern g7250 (Snapdragon X-series), never a mismatch.
_RADIO_PREFIX_BY_SOC = {
    "msmnile": "g8150", "sdm855": "g8150",              # SD855 modem
    "kona": "g7250",                                     # SD865 (X55)
    "lahaina": "g8350", "sm7150": "g7250", "lito": "g7250",  # SD888 / SD730G / SD765G
    "sm6150": "g7150", "sdm845": "M8998", "msm8998": "M8998", "sdm670": "g6150",
    "sdm660": "M8998", "sdm665": "g7150", "trinket": "g7150", "bengal": "g7150",
    "taro": "g8450", "kalama": "g8550",                  # SD8g1 / SD8g2
    "gs101": "g5123b",                                   # Google Tensor uses the Exynos g5123b modem
    "exynos9820": "g8090", "exynos9825": "g8090", "exynos990": "g5123", "exynos2100": "g5123",
    "exynos9610": "m8090", "exynos9611": "m8090", "exynos1280": "g5300", "exynos7884": "m7570",
    "exynos7885": "m7570", "exynos7904": "m7570", "exynos7870": "m7570", "exynos850": "m7570",
    "exynos9810": "g8090",
}
_RADIO_DEFAULT_PREFIX = "g7250"

def radio_version(r, soc=""):
    """Build.getRadioVersion() baseband string (mirrors Java radioVersion). Confirmed FP leak. The modem
    prefix is the SoC's real baseband; the rest of the string is per-unit random. RNG order is preserved: the
    old prefix-selection draw is kept (now discarded) so every downstream field's value is byte-identical."""
    r(len(_RADIO_PREFIXES))   # keep the draw at this position for byte-parity; prefix is now SoC-derived
    pre = _RADIO_PREFIX_BY_SOC.get(soc, _RADIO_DEFAULT_PREFIX)
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

_RAM_GB = [2, 3, 4, 6, 8, 12]   # index 0 = 2GB (budget); real phones ship these tiers
_STORAGE_GB = [32, 64, 128, 256]

# storage capacities that plausibly ship with each RAM tier (index-aligned to _RAM_GB); a 12GB
# flagship is never 32GB, a 3GB budget phone is never 512GB. Coherence matters — an incoherent
# RAM+storage combo is itself a fingerprint, so storage is DERIVED from the chosen RAM tier.
_STORAGE_FOR_RAM = [
    [16, 32],        # 2GB
    [32, 64],        # 3GB
    [32, 64, 128],   # 4GB
    [64, 128, 256],  # 6GB
    [128, 256],      # 8GB
    [128, 256, 512], # 12GB
]

# RAM tier INDICES (into _RAM_GB) that are REALISTIC for each SoC. A moto g7 play (SD632/trinket) is
# never 8-12GB; a Galaxy S20 (exynos990) is never 2-3GB. Keying RAM off the SoC kills the biggest
# hardware tell: totalMem that contradicts the device. Unknown SoC -> a safe mid range (3/4/6GB).
# MUST stay byte-identical to Java RAM_IDX_FOR_SOC.
_RAM_IDX_FOR_SOC = {
    # flagships: 6/8/12 GB
    "exynos9820": [3, 4, 5], "msmnile": [3, 4, 5], "exynos990": [4, 5], "exynos9825": [4, 5],
    "kona": [4, 5], "exynos2100": [4, 5], "lahaina": [4, 5], "sdm855": [3, 4, 5],
    "taro": [4, 5], "kalama": [4, 5],   # SD8g1/8g2 flagships (S22/S23) — 8/12GB; were missing -> 3-4GB default
    "exynos9810": [3, 4], "msm8998": [2, 3, 4], "sdm845": [2, 3, 4],
    # upper-mid: 4/6/8 GB
    "sm6150": [2, 3, 4], "sm7150": [2, 3, 4], "lito": [2, 3, 4], "gs101": [4], "exynos9610": [2, 3],
    "sdm670": [1, 2, 3],   # SD670 (Pixel 3a) — 4GB; was missing -> default (coincidentally same, now explicit)
    # mid: 3/4/6 GB
    "sdm660": [1, 2, 3], "exynos7904": [1, 2, 3], "exynos9611": [1, 2, 3], "exynos1280": [2, 3],
    # budget: 2/3/4 GB
    "trinket": [0, 1, 2], "bengal": [0, 1, 2], "exynos850": [0, 1, 2], "exynos7884": [0, 1, 2],
    "exynos7885": [0, 1, 2], "exynos7870": [0, 1], "sdm665": [1, 2, 3],
}
_RAM_IDX_DEFAULT = [1, 2, 3]   # unknown SoC -> 3/4/6 GB (safe modern mid)

# Per-MODEL RAM index override (into _RAM_GB=[2,3,4,6,8,12]). The SoC map above is a 2-3-wide spread because
# one SoC serves many SKUs, so ~72% of profiles claimed a RAM size the specific MODEL never shipped — itself
# a coherence tell (a real Pixel 5 is 8GB, full stop; never 4 or 6). Keyed on the product-stripped codename
# exactly like _SCREEN_KNOWN, this pins each real US-pool model to its true retail SKU(s). Checked BEFORE the
# SoC map; falls through to it for any codename not listed. Grounded in each model's real spec.
# MUST stay byte-identical to Java RAM_IDX_FOR_MODEL.
_RAM_IDX_FOR_MODEL = {
    "bramble": [3],       # Pixel 4a 5G — 6GB
    "redfin":  [4],       # Pixel 5 — 8GB
    "barbet":  [3],       # Pixel 5a — 6GB
    "sofiap":  [2],       # moto g pro — 4GB
    "mh2lm":   [3],       # LG G8 ThinQ — 6GB
    "t2q":     [4],       # Galaxy S21+ (SM-G996U) — 8GB
    "o1s": [4], "p3q": [4, 5], "r9q": [3, 4],   # S21 / S21 Ultra (12/16) / S21 FE — extra US S21 family
    "flame": [3], "coral": [3],                  # Pixel 4 / 4 XL — 6GB
    "oriole": [4], "raven": [5],                 # Pixel 6 (8GB) / 6 Pro (12GB)
}


def _ram_idx_for_model(codename):
    """RAM index set for a device by LONGEST-prefix match against _RAM_IDX_FOR_MODEL, or None. Pool
    codenames carry variant suffixes (t2qsqw, o1sxxx) while the table keys are clean stems (t2q, o1s), so an
    exact match misses — longest-prefix picks the right SKU (and 'a52xq' beats a shorter 'a52' stem). Pure,
    no RNG -> byte-parity safe. MUST match Java ramIdxForModel."""
    cn = (codename or "").lower()
    best = None
    for stem in _RAM_IDX_FOR_MODEL:
        if cn.startswith(stem) and (best is None or len(stem) > len(best)):
            best = stem
    return _RAM_IDX_FOR_MODEL[best] if best else None

# Real base storage capacity (GB) per pool model. The SKU is model-specific — a Pixel 5 is 128GB, full stop,
# never the 32/64/256 the RAM-tier storage pool allows. Longest-prefix on codename; None -> keep the pooled
# draw. MUST match Java storageGbForModel.
_STORAGE_GB_FOR_MODEL = {
    "bramble": 128, "redfin": 128, "barbet": 128, "sofiap": 128, "mh2lm": 128, "t2q": 128,
    "flame": 64, "coral": 64, "sunfish": 128,      # Pixel 4 base 64, 4a 128
    "oriole": 128, "raven": 128,                    # Pixel 6 / 6 Pro base 128
    "o1s": 128, "p3q": 128, "r9q": 128, "r0q": 128, "b0q": 128,
    "sargo": 64, "bonito": 64, "blueline": 64, "crosshatch": 64,
}


def _storage_gb_for_model(codename):
    """Real base storage GB for a device by longest-prefix, or None. Pure -> byte-parity safe. MUST match
    Java storageGbForModel."""
    cn = (codename or "").split("_")[0].lower()
    best = None
    for stem in _STORAGE_GB_FOR_MODEL:
        if cn.startswith(stem) and (best is None or len(stem) > len(best)):
            best = stem
    return _STORAGE_GB_FOR_MODEL[best] if best else None


def ram_storage_bytes(r, soc="", codename=""):
    """RAM+storage as one coherent pair, (ram_bytes, storage_bytes). Mirrors Java ramStorageBytes.
    RAM tier is constrained to what the MODEL (preferred) or its SoC realistically ships with — no 8GB budget
    phones, no 4GB Pixel 5. RNG order: ram-tier idx, ram-shave, storage-capacity idx, storage-fill."""
    idxs = _ram_idx_for_model(codename) or _RAM_IDX_FOR_SOC.get(soc, _RAM_IDX_DEFAULT)
    ram_idx = idxs[r(len(idxs))]
    ram_gb = _RAM_GB[ram_idx]
    ram_nominal = ram_gb * 1024 * 1024 * 1024
    ram_reported = ram_nominal - (ram_nominal * (3 + r(6)) // 100)
    ram = str((ram_reported // (1024 * 1024)) * 1024 * 1024)

    pool = _STORAGE_FOR_RAM[ram_idx]
    drawn = pool[r(len(pool))]                 # keep the draw (byte-parity); overridden below if the model pins it
    st_gb = _storage_gb_for_model(codename) or drawn
    st_nominal = st_gb * 1000 * 1000 * 1000
    storage = str(st_nominal * (90 + r(5)) // 100)
    return ram, storage

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

# Real, currently-assigned US geographic area codes (a broad geographic spread of major metros +
# states). A randomly-formed [2-9]XX is often an UNASSIGNED or non-geographic code (a tell); picking
# from real assigned codes makes the number plausible. Not exhaustive — a representative real set.
_US_AREA_CODES = [
    "212", "646", "917", "718",              # New York City
    "213", "323", "310", "424", "818",       # Los Angeles
    "312", "773", "872",                     # Chicago
    "281", "713", "832",                     # Houston
    "602", "480", "623",                     # Phoenix
    "215", "267",                            # Philadelphia
    "210", "726",                            # San Antonio
    "619", "858",                            # San Diego
    "214", "469", "972",                     # Dallas
    "408", "669",                            # San Jose
    "512", "737",                            # Austin
    "904", "407", "321", "305", "786", "813",# Florida (Jacksonville/Orlando/Miami/Tampa)
    "614", "216", "513",                     # Ohio (Columbus/Cleveland/Cincinnati)
    "704", "980", "919", "984",              # North Carolina (Charlotte/Raleigh)
    "317", "463",                            # Indianapolis
    "206", "425", "253",                     # Seattle
    "303", "720",                            # Denver
    "617", "857",                            # Boston
    "615", "629", "901",                     # Tennessee (Nashville/Memphis)
    "503", "971",                            # Portland OR
    "702", "725",                            # Las Vegas
    "404", "470", "678",                     # Atlanta
    "414", "262",                            # Milwaukee
    "505", "575",                            # New Mexico
    "801", "385",                            # Salt Lake City
    "816", "913", "314",                     # Kansas City / St. Louis
    "412", "878",                            # Pittsburgh
    "612", "651", "763",                     # Minneapolis / St. Paul
]

# Area-code -> US IANA timezone. A US device whose TimeZone.getDefault()/locale still report the HOST
# machine's region is an internal contradiction FingerprintJS DeviceState hashes; deriving the zone from
# the already-chosen phone area code makes phone + timezone + locale tell ONE coherent US-location story.
# Pure lookup (no RNG) -> byte-parity safe. Keep in exact lockstep with Java Generators.TZ_BY_AREA.
_TZ_BY_AREA = {
    # Eastern
    "212": "America/New_York", "646": "America/New_York", "917": "America/New_York", "718": "America/New_York",
    "215": "America/New_York", "267": "America/New_York", "904": "America/New_York", "407": "America/New_York",
    "321": "America/New_York", "305": "America/New_York", "786": "America/New_York", "813": "America/New_York",
    "614": "America/New_York", "216": "America/New_York", "513": "America/New_York", "704": "America/New_York",
    "980": "America/New_York", "919": "America/New_York", "984": "America/New_York", "317": "America/New_York",
    "463": "America/New_York", "617": "America/New_York", "857": "America/New_York", "404": "America/New_York",
    "470": "America/New_York", "678": "America/New_York", "412": "America/New_York", "878": "America/New_York",
    # Central
    "312": "America/Chicago", "773": "America/Chicago", "872": "America/Chicago", "281": "America/Chicago",
    "713": "America/Chicago", "832": "America/Chicago", "210": "America/Chicago", "726": "America/Chicago",
    "214": "America/Chicago", "469": "America/Chicago", "972": "America/Chicago", "512": "America/Chicago",
    "737": "America/Chicago", "615": "America/Chicago", "629": "America/Chicago", "901": "America/Chicago",
    "414": "America/Chicago", "262": "America/Chicago", "816": "America/Chicago", "913": "America/Chicago",
    "314": "America/Chicago", "612": "America/Chicago", "651": "America/Chicago", "763": "America/Chicago",
    # Mountain
    "602": "America/Phoenix", "480": "America/Phoenix", "623": "America/Phoenix",   # Arizona (no DST)
    "303": "America/Denver", "720": "America/Denver", "505": "America/Denver", "575": "America/Denver",
    "801": "America/Denver", "385": "America/Denver",
    # Pacific
    "213": "America/Los_Angeles", "323": "America/Los_Angeles", "310": "America/Los_Angeles",
    "424": "America/Los_Angeles", "818": "America/Los_Angeles", "619": "America/Los_Angeles",
    "858": "America/Los_Angeles", "408": "America/Los_Angeles", "669": "America/Los_Angeles",
    "206": "America/Los_Angeles", "425": "America/Los_Angeles", "253": "America/Los_Angeles",
    "503": "America/Los_Angeles", "971": "America/Los_Angeles", "702": "America/Los_Angeles",
    "725": "America/Los_Angeles",
}


def tz_for_area_code(area):
    """US IANA timezone for a NANP area code; America/New_York if the code isn't mapped. No RNG."""
    return _TZ_BY_AREA.get(area, "America/New_York")


def phone_us(r):
    # NANP: a REAL assigned area code + exchange [2-9]XX (never an N11 service code) + 4 digits, with a
    # leading country code 1. Draw order: area-code index, then exchange leading digit [2-9], exchange
    # 2nd/3rd digits, then the 4 subscriber digits. Mirrors Java phoneUs (byte-parity).
    area = _US_AREA_CODES[r(len(_US_AREA_CODES))]
    # exchange: leading [2-9], then two digits; reroll only the pattern by construction so it is never
    # N11 (e.g. 211/411/911 are service codes, never a real subscriber exchange).
    exch_first = str(2 + r(8))
    exch_rest = digits(r, 2)
    if exch_rest == "11":
        exch_rest = "12"                     # deterministic nudge off the N11 service code, no extra draw
    exch = exch_first + exch_rest
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
def _now_epoch():
    import calendar, datetime as _dt
    return calendar.timegm(_dt.datetime.now(_dt.timezone.utc).utctimetuple())


def validate(key, value):
    """Return True if value has the right format for key. Used by profile.validate()."""
    checks = {
        "android_id":          lambda v: bool(re.fullmatch(r"[0-9a-f]{16}", v)),
        # brand-plausible serials: Base34 (0-9 A-Z minus I,O), prefix optional, 11-15 chars per brand.
        "serial":              lambda v: bool(re.fullmatch(r"[0-9A-HJ-NP-Z]{11,15}", v)),
        "media_drm_id":        lambda v: bool(re.fullmatch(r"[0-9a-f]{32}|[0-9a-f]{64}", v)),
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
        # plausible past unix seconds: after 2015, before now (a future reset is impossible)
        "factory_reset_epoch": lambda v: bool(re.fullmatch(r"\d{10}", v)) and 1420070400 < int(v) < _now_epoch(),
        "gmail":               lambda v: bool(re.fullmatch(r"[a-z0-9]([a-z0-9._-]{0,30}[a-z0-9])?@(gmail\.com|outlook\.com|yahoo\.com|hotmail\.com|proton\.me|icloud\.com)", v)),
        "app_set_id":          lambda v: bool(re.fullmatch(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}", v)),
    }
    fn = checks.get(key)
    return True if fn is None else fn(value)
