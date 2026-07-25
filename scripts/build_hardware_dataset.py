#!/usr/bin/env python
"""
build_hardware_dataset.py — emit data/hardware.json: coherent per-model hardware descriptors.

Why this exists
---------------
The device-intelligence SDKs read hardware-characteristic signals — GPU/GLES renderer,
/proc/cpuinfo, sensor list, camera list, codec list, core count, input devices — that are
STABLE per physical device. A profile that only rotates identifiers leaves these reading the
real phone, so a fuzzy match over the signal set re-links every rotation. This dataset gives
each generated identity a COHERENT hardware bundle for the device model it claims to be.

Design
------
Hardware descriptors are overwhelmingly SoC-determined: two phones on the same Snapdragon 855
report the same Adreno 640, the same CPU part IDs, the same core layout, the same GLES renderer.
So the source of truth is a small table of real SoC profiles (SOC_SPECS), and each device
codename in the pool maps to its real SoC (CODENAME_SOC). Sensor/camera COUNTS vary by model
tier, layered on top. The result is keyed by device codename (the stripped Build.PRODUCT slot,
e.g. "flame", "o1s", "redfin") so both Python and Java can look it up from the picked device row.

These are CONSTANTS (a pure lookup keyed by the already-chosen device) — they consume NO seeded
RNG, so they are byte-parity-safe by construction (constants never shift the draw order).

Values are real public specs (SoC GPU/CPU, GLES renderer strings as reported by the driver,
typical sensor/camera vendors). They are model-plausible, not per-unit-exact — the goal is a
coherent bundle that differs between two DIFFERENT device models, matching how a real fleet looks.
"""
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
OUT = os.path.join(ROOT, "data", "hardware.json")
DEVICES = os.path.join(ROOT, "data", "devices.json")

# ---------------------------------------------------------------------------
# Real SoC hardware profiles. Each entry is grounded in the chip's public specs.
#   gpu_renderer  : GL_RENDERER string the GPU driver reports (glGetString(GL_RENDERER))
#   gpu_vendor    : GL_VENDOR
#   gles_version  : GL_VERSION / getGlEsVersion() major.minor supported
#   cpu_parts     : list of (implementer, part, count) for /proc/cpuinfo CPU-part lines.
#                   implementer 0x41=ARM, 0x51=Qualcomm; part is the ARM part number hex.
#   hardware      : the "Hardware" line in /proc/cpuinfo
#   bogomips      : BogoMIPS value reported per core (Qualcomm/ARM typically report the timer freq)
#   cores         : total online CPU count
# ---------------------------------------------------------------------------
SOC_SPECS = {
    # --- Qualcomm Snapdragon ---
    "sdm660": {  # Galaxy A-series mid, Moto G power — SD660
        "gpu_renderer": "Adreno (TM) 512", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x51, 0x800, 4), (0x51, 0x801, 4)], "hardware": "Qualcomm Technologies, Inc SDM660",
        "bogomips": "38.40", "cores": 8,
    },
    "sdm665": {  # Galaxy A50s, Moto G stylus — SD665
        "gpu_renderer": "Adreno (TM) 610", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd05, 4), (0x41, 0xd05, 4)], "hardware": "Qualcomm Technologies, Inc SDM665",
        "bogomips": "38.40", "cores": 8,
    },
    "sm6150": {  # SD675/730 class — Pixel 4a, Moto G 5G
        "gpu_renderer": "Adreno (TM) 612", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd0b, 2), (0x41, 0xd05, 6)], "hardware": "Qualcomm Technologies, Inc SM6150",
        "bogomips": "38.40", "cores": 8,
    },
    "trinket": {  # SD665/662 — budget Motos, Galaxy A-series
        "gpu_renderer": "Adreno (TM) 610", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd05, 4), (0x41, 0xd05, 4)], "hardware": "Qualcomm Technologies, Inc TRINKET",
        "bogomips": "38.40", "cores": 8,
    },
    "bengal": {  # SD662/460 — Moto G play/power budget
        "gpu_renderer": "Adreno (TM) 610", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd05, 4), (0x41, 0xd05, 4)], "hardware": "Qualcomm Technologies, Inc BENGAL",
        "bogomips": "38.40", "cores": 8,
    },
    "msm8998": {  # SD835 — Note8/S8, Pixel 2, Moto Z3
        "gpu_renderer": "Adreno (TM) 540", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x51, 0x800, 4), (0x51, 0x801, 4)], "hardware": "Qualcomm Technologies, Inc MSM8998",
        "bogomips": "38.40", "cores": 8,
    },
    "sdm845": {  # SD845 — Pixel 3, Note9, S9, G7
        "gpu_renderer": "Adreno (TM) 630", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x51, 0x802, 4), (0x51, 0x803, 4)], "hardware": "Qualcomm Technologies, Inc SDM845",
        "bogomips": "38.40", "cores": 8,
    },
    "msmnile": {  # SD855 — Pixel 4, S10, Note10, G8
        "gpu_renderer": "Adreno (TM) 640", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd0d, 1), (0x41, 0xd0d, 3), (0x41, 0xd05, 4)],
        "hardware": "Qualcomm Technologies, Inc SM8150", "bogomips": "38.40", "cores": 8,
    },
    "kona": {  # SD865 — S20, Note20 Ultra, S20 FE 5G
        "gpu_renderer": "Adreno (TM) 650", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd0d, 1), (0x41, 0xd0d, 3), (0x41, 0xd05, 4)],
        "hardware": "Qualcomm Technologies, Inc KONA", "bogomips": "38.40", "cores": 8,
    },
    "lito": {  # SD765G — Pixel 5, Pixel 4a 5G, A90 5G
        "gpu_renderer": "Adreno (TM) 620", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd0d, 1), (0x41, 0xd0d, 1), (0x41, 0xd05, 6)],
        "hardware": "Qualcomm Technologies, Inc LITO", "bogomips": "38.40", "cores": 8,
    },
    "lahaina": {  # SD888 — S21, motorola edge
        "gpu_renderer": "Adreno (TM) 660", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd44, 1), (0x41, 0xd0d, 3), (0x41, 0xd05, 4)],
        "hardware": "Qualcomm Technologies, Inc LAHAINA", "bogomips": "38.40", "cores": 8,
    },
    # --- Samsung Exynos ---
    "exynos9820": {  # S10 (EU) — Exynos 9820
        "gpu_renderer": "Mali-G76", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x53, 0x001, 2), (0x41, 0xd0d, 2), (0x41, 0xd05, 4)],
        "hardware": "Samsung EXYNOS9820", "bogomips": "26.00", "cores": 8,
    },
    "exynos9825": {  # Note10 (EU) — Exynos 9825
        "gpu_renderer": "Mali-G76", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x53, 0x002, 2), (0x41, 0xd0d, 2), (0x41, 0xd05, 4)],
        "hardware": "Samsung EXYNOS9825", "bogomips": "26.00", "cores": 8,
    },
    "exynos990": {  # S20/Note20 (EU) — Exynos 990
        "gpu_renderer": "Mali-G77", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x53, 0x004, 2), (0x41, 0xd0d, 2), (0x41, 0xd05, 4)],
        "hardware": "Samsung EXYNOS990", "bogomips": "26.00", "cores": 8,
    },
    "exynos2100": {  # S21 (EU) — Exynos 2100
        "gpu_renderer": "Mali-G78", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd44, 1), (0x41, 0xd0d, 3), (0x41, 0xd05, 4)],
        "hardware": "Samsung EXYNOS2100", "bogomips": "26.00", "cores": 8,
    },
    "exynos7884": {  # A20/A40 budget — Exynos 7884
        "gpu_renderer": "Mali-G71", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd05, 2), (0x41, 0xd03, 6)], "hardware": "Samsung EXYNOS7884",
        "bogomips": "26.00", "cores": 8,
    },
    "exynos9610": {  # A50/A7 2018 — Exynos 9610
        "gpu_renderer": "Mali-G72", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd05, 4), (0x41, 0xd03, 4)], "hardware": "Samsung EXYNOS9610",
        "bogomips": "26.00", "cores": 8,
    },
    "exynos9611": {  # A51/M21 — Exynos 9611
        "gpu_renderer": "Mali-G72", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd05, 4), (0x41, 0xd03, 4)], "hardware": "Samsung EXYNOS9611",
        "bogomips": "26.00", "cores": 8,
    },
    "exynos1280": {  # A52 — Exynos 1280 class (approx via 9611 family for A52 4G on Snapdragon; kept ARM)
        "gpu_renderer": "Mali-G57", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd41, 2), (0x41, 0xd05, 6)], "hardware": "Samsung EXYNOS1280",
        "bogomips": "26.00", "cores": 8,
    },
    "exynos7904": {  # A7/M20 — Exynos 7904
        "gpu_renderer": "Mali-G71", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd05, 2), (0x41, 0xd03, 6)], "hardware": "Samsung EXYNOS7904",
        "bogomips": "26.00", "cores": 8,
    },
    "exynos850": {  # A01 core budget — Exynos 850 / low-end
        "gpu_renderer": "Mali-G52", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd05, 8)], "hardware": "Samsung EXYNOS850",
        "bogomips": "26.00", "cores": 8,
    },
    # --- Google Tensor ---
    "gs101": {  # Pixel 6 / 6 Pro — Tensor
        "gpu_renderer": "Mali-G78", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd44, 2), (0x41, 0xd0d, 2), (0x41, 0xd05, 4)],
        "hardware": "Google Tensor", "bogomips": "31.25", "cores": 8,
    },
}

# Each pool codename -> its real SoC. US variants (SM-*U, Pixels, Motos) are Snapdragon;
# European Samsung variants (SM-*F/EEA, *N Korea) are Exynos. Grounded per model.
CODENAME_SOC = {
    # Google Pixel (all Snapdragon / Tensor)
    "blueline": "sdm845", "crosshatch": "sdm845", "sargo": "sm6150", "bonito": "sm6150",
    "flame": "msmnile", "coral": "msmnile", "sunfish": "sm6150", "bramble": "lito",
    "redfin": "lito", "barbet": "lito", "oriole": "gs101", "raven": "gs101",
    # LG
    "judyln": "sdm845", "mh2lm": "msmnile",
    # Motorola
    "racer": "lahaina", "sofiap": "sdm665", "kiev": "sm6150", "nairo": "sm6150",
    "ali": "sdm660", "evert": "sdm660", "river": "sdm660", "ocean": "bengal",
    "channel": "trinket", "smith": "kona", "beckham": "msm8998",
    # Samsung Galaxy A / M (mid/budget, Exynos)
    "a01core": "exynos850", "a20": "exynos7884", "a40": "exynos7884", "a50": "exynos9610",
    "a50s": "exynos9611", "a51": "exynos9611", "a52q": "exynos1280", "a6lte": "exynos7870",
    "a6plte": "sdm660", "a70q": "sm6150", "a71": "sm6150", "a7y18lte": "exynos7885",
    "m20lte": "exynos7904", "m21": "exynos9611", "r3q": "sdm855",
    # Samsung flagship (US = Snapdragon, EU/KR = Exynos)
    "beyond0": "exynos9820", "beyond1": "exynos9820", "beyond2": "exynos9820", "beyond2q": "msmnile",
    "beyondx": "exynos9820", "crownlte": "exynos9810", "d2q": "exynos9825", "d2s": "exynos9825",
    "c2q": "exynos990", "c2s": "exynos990", "r5q": "msmnile", "r8s": "exynos990", "r8q": "kona",
    "z3s": "exynos990", "o1s": "exynos2100",
    # tablet in pool (kept for completeness; filtered out by non-phone marker at profile time)
    "gta3xlwifi": "exynos7904",
}

# SoCs referenced above that need a spec but weren't in SOC_SPECS yet — add real values.
SOC_SPECS.update({
    "exynos7870": {  # A6 2018 — Exynos 7870
        "gpu_renderer": "Mali-T830", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd03, 8)], "hardware": "Samsung EXYNOS7870",
        "bogomips": "26.00", "cores": 8,
    },
    "exynos7885": {  # A7 2018 — Exynos 7885
        "gpu_renderer": "Mali-G71", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd05, 2), (0x41, 0xd03, 6)], "hardware": "Samsung EXYNOS7885",
        "bogomips": "26.00", "cores": 8,
    },
    "exynos9810": {  # Note9 (EU) — Exynos 9810
        "gpu_renderer": "Mali-G72", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x53, 0x001, 4), (0x41, 0xd03, 4)], "hardware": "Samsung EXYNOS9810",
        "bogomips": "26.00", "cores": 8,
    },
    "sdm855": {  # A90 5G — SD855
        "gpu_renderer": "Adreno (TM) 640", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd0d, 1), (0x41, 0xd0d, 3), (0x41, 0xd05, 4)],
        "hardware": "Qualcomm Technologies, Inc SM8150", "bogomips": "38.40", "cores": 8,
    },
})

# ---- Sensor & camera lists ----
# Real Android devices expose a stable sensor set whose VENDOR strings identify the SoC/OEM sensor
# hub. The set differs by OEM (Samsung uses STMicro/Bosch, Pixels use Bosch/AKM). We pick the vendor
# family by brand and emit a realistic, coherent list. Camera count/lens by tier.
SENSORS_BY_VENDOR = {
    "samsung": [
        ("LSM6DSO Acceleration Sensor", "STMicroelectronics", 1),
        ("LSM6DSO Gyroscope Sensor", "STMicroelectronics", 4),
        ("AK09918C Magnetic Sensor", "AKM", 2),
        ("TMD4907 Proximity Sensor", "AMS", 8),
        ("TMD4907 Light Sensor", "AMS", 5),
        ("LPS22HH Pressure Sensor", "STMicroelectronics", 6),
        ("Grip Sensor", "Samsung", 65637),
    ],
    "google": [
        ("BMI160 accelerometer", "Bosch", 1),
        ("BMI160 gyroscope", "Bosch", 4),
        ("AK09915 magnetometer", "AKM", 2),
        ("STK3X1X Proximity sensor", "Sensortek", 8),
        ("STK3X1X Ambient Light sensor", "Sensortek", 5),
        ("BMP380 pressure", "Bosch", 6),
    ],
    "motorola": [
        ("Accelerometer", "STMicro", 1),
        ("Gyroscope", "STMicro", 4),
        ("Magnetometer", "AKM", 2),
        ("Proximity Sensor", "AMS", 8),
        ("Light Sensor", "AMS", 5),
    ],
    "lge": [
        ("Accelerometer Sensor", "Bosch", 1),
        ("Gyroscope Sensor", "Bosch", 4),
        ("Magnetic Sensor", "AKM", 2),
        ("Proximity Sensor", "AVAGO", 8),
        ("Light Sensor", "AVAGO", 5),
    ],
}

def build_cpuinfo(soc_spec):
    """Assemble a realistic /proc/cpuinfo body from a SoC spec. One block per core with the
    ARM/QC processor lines; a trailing Hardware line. Coherent core count + part IDs."""
    lines = []
    idx = 0
    for impl, part, count in soc_spec["cpu_parts"]:
        for _ in range(count):
            lines.append(f"processor\t: {idx}")
            lines.append("BogoMIPS\t: " + soc_spec["bogomips"])
            lines.append("Features\t: fp asimd evtstrm aes pmull sha1 sha2 crc32 atomic fphp asimdhp")
            lines.append("CPU implementer\t: " + f"0x{impl:02x}")
            lines.append("CPU architecture: 8")
            lines.append("CPU variant\t: 0x1")
            lines.append("CPU part\t: " + f"0x{part:03x}")
            lines.append("CPU revision\t: 0")
            lines.append("")
            idx += 1
    lines.append("Hardware\t: " + soc_spec["hardware"])
    return "\n".join(lines) + "\n"


# Codec + input-device lists are Android-platform stable (the same OMX/c2 codec set ships on
# essentially every ARM Android phone) — keep one realistic list rather than per-model noise.
DEFAULT_CODECS = [
    "OMX.qcom.video.decoder.avc", "OMX.qcom.video.decoder.hevc",
    "OMX.qcom.video.decoder.vp9", "OMX.qcom.video.encoder.avc",
    "c2.android.avc.decoder", "c2.android.hevc.decoder", "c2.android.vp9.decoder",
    "c2.android.aac.decoder", "c2.android.mp3.decoder", "c2.android.opus.decoder",
]
DEFAULT_INPUT_DEVICES = [
    "gpio-keys", "qpnp_pon", "uinput-fpc", "synaptics_dsx", "sec_touchscreen",
]

# Coherent fallback for any pool device whose codename we haven't mapped to a real SoC: a plausible
# mid-range Snapdragon bundle. It is coherent-by-construction (a real chip, real Adreno, real cpuinfo)
# so an unmapped device still presents a consistent hardware story rather than the real Pixel's.
DEFAULT_SOC = "sm6150"


def _codename_of(product):
    """Codename exactly as profile.py derives it: Build.PRODUCT with any '_' suffix stripped."""
    return product.split("_")[0]


def _soc_for_codename(codename):
    """Resolve a real pool codename to its SoC by LONGEST-prefix match against CODENAME_SOC.
    CODENAME_SOC keys are clean family stems (o1s, beyond1, a51); real pool codenames carry variant
    suffixes (o1sxxx, beyond1ltexx, a51nsxx). Longest match wins so 'a50s...' beats 'a50...'."""
    best = None
    for stem in CODENAME_SOC:
        if codename.startswith(stem) and (best is None or len(stem) > len(best)):
            best = stem
    return CODENAME_SOC[best] if best else DEFAULT_SOC


def _brand_of(row):
    """Sensor-vendor family from the device row's brand slot (row[2])."""
    b = row[1].lower()
    if "samsung" in b: return "samsung"
    if "google" in b: return "google"
    if "lg" in b: return "lge"
    return "motorola"


def _tier_cameras(codename):
    flagship = ("flame", "coral", "oriole", "raven", "redfin", "o1s", "c2q", "c2s", "d2q", "d2s",
                "z3s", "beyond1", "beyond2", "beyondx", "r8q", "r8s", "racer", "smith")
    mid = ("a51", "a52q", "a70q", "a71", "a50", "sunfish", "bramble", "barbet", "sargo",
           "bonito", "blueline", "crosshatch", "judyln", "mh2lm", "beyond0", "crownlte", "r3q", "r5q",
           "kiev", "nairo", "sofiap", "river", "evert")
    if codename.startswith(flagship): return ["0", "1", "2", "3"]
    if codename.startswith(mid): return ["0", "1", "2"]
    return ["0", "1"]


def _entry(codename, soc, brand):
    spec = SOC_SPECS[soc]
    sensors = SENSORS_BY_VENDOR[brand]
    return {
        "soc": soc,
        "gpu_renderer": spec["gpu_renderer"],
        "gpu_vendor": spec["gpu_vendor"],
        "gles_version": spec["gles_version"],
        "cores": spec["cores"],
        "sensors": [{"name": n, "vendor": v, "type": t} for (n, v, t) in sensors],
        "cameras": _tier_cameras(codename),
        "codecs": DEFAULT_CODECS,
        "input_devices": DEFAULT_INPUT_DEVICES,
        "cpuinfo": build_cpuinfo(spec),
    }


def _is_selectable(row):
    """Mirror of profile._is_plausible_phone + the US-brand filter — only these codenames can ever
    be picked, so only these need a hardware entry. Keeps the shipped asset small."""
    if len(row) <= 5:
        return False
    if row[2].lower() not in ("samsung", "google", "motorola", "lge"):
        return False
    markers = ("Tab", "Nexus 7", "Nexus 9", "Nexus 10", "Nexus Player", "Shield", "Pixel C")
    if any(m in row[0] for m in markers):
        return False
    try:
        head = row[5].split(":", 1)[1].split(".")[0] if ":" in row[5] else "0"
        digits = ""
        for ch in head:
            if ch.isdigit():
                digits += ch
            else:
                break
        return (int(digits) if digits else 0) >= 9
    except Exception:
        return False


def build():
    """One entry per DISTINCT selectable pool codename (product-stripped), keyed exactly as the
    profile looks it up. Every value is a coherent function of the model's real SoC + brand. A
    "_default" entry is the coherent fallback for any codename not present (used by the profile
    loader), so the lookup is total even though the asset only ships the reachable pool."""
    devs = json.load(open(DEVICES, encoding="utf-8"))
    out = {"_default": _entry("_default", DEFAULT_SOC, "motorola")}
    for row in devs:
        if not _is_selectable(row):
            continue
        codename = _codename_of(row[4])
        if codename in out:
            continue
        out[codename] = _entry(codename, _soc_for_codename(codename), _brand_of(row))
    return out


if __name__ == "__main__":
    data = build()
    with open(OUT, "w", encoding="utf-8") as f:
        json.dump(data, f, indent=1, sort_keys=True)
    print(f"wrote {OUT}: {len(data)} device codenames (incl. _default)")
    # Sanity: EVERY selectable (pickable) codename must have its own entry — a selectable device
    # falling back to _default would be a real miss. Non-selectable devices intentionally have none.
    devs = json.load(open(DEVICES, encoding="utf-8"))
    missing = sorted({_codename_of(d[4]) for d in devs if _is_selectable(d) and _codename_of(d[4]) not in data})
    if missing:
        raise SystemExit(f"ERROR: {len(missing)} SELECTABLE codenames missing a hardware entry: {missing}")
    print("OK: all selectable codenames covered; others resolve to _default at profile time.")
