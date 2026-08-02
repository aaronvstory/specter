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
    "sm6150": {  # SD675/730 class — Moto G 5G. Kryo 4xx (Qualcomm impl 0x51), NOT generic ARM Cortex —
        # a real SD7xx phone reports 0x51 gold/silver (device-proven on a Pixel 4a, 2026-08-02), so the old
        # generic-ARM 0x41:0xd0b/0xd05 was an emulator tell. 2 gold (0x804) + 6 silver (0x805).
        "gpu_renderer": "Adreno (TM) 612", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x51, 0x804, 2), (0x51, 0x805, 6)], "hardware": "Qualcomm Technologies, Inc SM6150",
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
    "msmnile": {  # SD855 — Pixel 4, S10, Note10, G8. Kryo 485 (Qualcomm impl 0x51): 4 gold (0x804, the
        # prime+3 gold — same part id, distinguished only by clock in cpu_capacity) + 4 silver (0x805).
        # The old 0x41:0xd0d was Cortex-A77 (wrong core AND wrong scheme) — the Cash-App "emulator" tell.
        "gpu_renderer": "Adreno (TM) 640", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x51, 0x804, 4), (0x51, 0x805, 4)],
        "hardware": "Qualcomm Technologies, Inc SM8150", "bogomips": "38.40", "cores": 8,
    },
    "kona": {  # SD865 — S20, Note20 Ultra, S20 FE 5G. Kryo 585 is MIXED-implementer (kernel cputype.h note):
        # the 5XX Prime/Gold cores ID as ARM Cortex-A77 (0x41:0xd0d), the Silver as Qualcomm Kryo 4xx Silver
        # (0x51:0x805). So 4 gold=A77 + 4 silver. NOT the all-0x51 4xx family (that's SD855/730/765, Kryo 4xx).
        "gpu_renderer": "Adreno (TM) 650", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd0d, 4), (0x51, 0x805, 4)],
        "hardware": "Qualcomm Technologies, Inc KONA", "bogomips": "38.40", "cores": 8,
    },
    "sm7150": {  # SD730/730G — Pixel 4a, Galaxy A71. Real cpuinfo Hardware line is "SDMMAGPIE"
        # (the platform's internal name), NOT "SM7150" — pinned by test_known_device_socs. Kryo 4xx:
        # DEVICE-PROVEN on the real Pixel 4a (2026-08-02) — impl 0x51, 2x 0x804 gold + 6x 0x805 silver.
        "gpu_renderer": "Adreno (TM) 618", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x51, 0x804, 2), (0x51, 0x805, 6)],
        "hardware": "Qualcomm Technologies, Inc SDMMAGPIE", "bogomips": "38.40", "cores": 8,
    },
    "sdm670": {  # SD670 — Pixel 3a / 3a XL. Device tree is "qcom,sdm670" (NOT sm670). Kryo 360 is the 3XX
        # family (Gold=A75, Silver=A55): Qualcomm impl 0x51, part 0x802 (3xx gold) / 0x803 (3xx silver) — same
        # as SD845, NOT the 4xx 0x804/0x805 (that's SD855/730/765). 2 gold + 6 silver.
        "gpu_renderer": "Adreno (TM) 615", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x51, 0x802, 2), (0x51, 0x803, 6)],
        "hardware": "Qualcomm Technologies, Inc SDM670", "bogomips": "38.40", "cores": 8,
    },
    "lito": {  # SD765G — Pixel 5, Pixel 4a 5G, A90 5G. Kryo 475 = 4xx family (Qualcomm impl 0x51).
        # 2 gold (0x804: 1 prime + 1 gold, same part id) + 6 silver (0x805).
        "gpu_renderer": "Adreno (TM) 620", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x51, 0x804, 2), (0x51, 0x805, 6)],
        "hardware": "Qualcomm Technologies, Inc LITO", "bogomips": "38.40", "cores": 8,
    },
    "lahaina": {  # SD888 — S21, motorola edge. Cortex-based (ARM impl 0x41): 1x X1 (0xd44) + 3x A78 (0xd41)
        # + 4x A55 (0xd05). The old 0xd0d was Cortex-A77 — wrong; SD888 mid cores are A78 0xd41.
        "gpu_renderer": "Adreno (TM) 660", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd44, 1), (0x41, 0xd41, 3), (0x41, 0xd05, 4)],
        "hardware": "Qualcomm Technologies, Inc LAHAINA", "bogomips": "38.40", "cores": 8,
    },
    "taro": {  # SD 8 Gen 1 (SM8450) — Galaxy S22 family (US). 1x Cortex-X2 (0xd48) +
        # 3x Cortex-A710 (0xd47) + 4x Cortex-A510 (0xd46), Adreno 730.
        "gpu_renderer": "Adreno (TM) 730", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd48, 1), (0x41, 0xd47, 3), (0x41, 0xd46, 4)],
        "hardware": "Qualcomm Technologies, Inc TARO", "bogomips": "38.40", "cores": 8,
    },
    "kalama": {  # SD 8 Gen 2 (SM8550) — Galaxy S23 Ultra (US). 1x Cortex-X3 (0xd4e) +
        # 2x A715 (0xd4d) + 2x A710 (0xd47) + 3x A510 (0xd46), Adreno 740.
        "gpu_renderer": "Adreno (TM) 740", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd4e, 1), (0x41, 0xd4d, 2), (0x41, 0xd47, 2), (0x41, 0xd46, 3)],
        "hardware": "Qualcomm Technologies, Inc KALAMA", "bogomips": "38.40", "cores": 8,
    },
    # --- Samsung Exynos ---
    "exynos9820": {  # S10 (EU) — Exynos 9820: 2x Exynos-M4 (impl 0x53 part 0x003) + 2x A75 (0xd0a) + 4x A55.
        # Old 0xd0d was Cortex-A77 (wrong; 9820 big-mid is A75), and M4's Samsung part id is 0x003 not 0x001.
        "gpu_renderer": "Mali-G76", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x53, 0x003, 2), (0x41, 0xd0a, 2), (0x41, 0xd05, 4)],
        "hardware": "Samsung EXYNOS9820", "bogomips": "26.00", "cores": 8,
    },
    "exynos9825": {  # Note10 (EU) — Exynos 9825 (9820 shrink): same 2x M4 + 2x A75 + 4x A55.
        "gpu_renderer": "Mali-G76", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x53, 0x003, 2), (0x41, 0xd0a, 2), (0x41, 0xd05, 4)],
        "hardware": "Samsung EXYNOS9825", "bogomips": "26.00", "cores": 8,
    },
    "exynos990": {  # S20/Note20 (EU) — Exynos 990: 2x Exynos-M5 (0x53 part 0x004) + 2x A76 (0xd0b) + 4x A55.
        # Old 0xd0d was Cortex-A77 — wrong; 990's middle cluster is Cortex-A76 0xd0b.
        "gpu_renderer": "Mali-G77", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x53, 0x004, 2), (0x41, 0xd0b, 2), (0x41, 0xd05, 4)],
        "hardware": "Samsung EXYNOS990", "bogomips": "26.00", "cores": 8,
    },
    "exynos2100": {  # S21 (EU) — Exynos 2100: 1x X1 (0xd44) + 3x A78 (0xd41) + 4x A55. All ARM cores (no
        # Mongoose). Old 0xd0d was Cortex-A77 — wrong; the 2100 mid cluster is Cortex-A78 0xd41.
        "gpu_renderer": "Mali-G78", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd44, 1), (0x41, 0xd41, 3), (0x41, 0xd05, 4)],
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
    "gs101": {  # Pixel 6 / 6 Pro — Google Tensor: 2x X1 (0xd44) + 2x A76 (0xd0b) + 4x A55. Old 0xd0d was
        # Cortex-A77 — wrong; Tensor's middle pair is Cortex-A76 0xd0b (confirmed real Pixel 6 cpuinfo).
        "gpu_renderer": "Mali-G78", "gpu_vendor": "ARM", "gles_version": "3.2",
        "cpu_parts": [(0x41, 0xd44, 2), (0x41, 0xd0b, 2), (0x41, 0xd05, 4)],
        "hardware": "Google Tensor", "bogomips": "31.25", "cores": 8,
    },
}

# Each pool codename -> its real SoC. US variants (SM-*U, Pixels, Motos) are Snapdragon;
# European Samsung variants (SM-*F/EEA, *N Korea) are Exynos. Grounded per model.
CODENAME_SOC = {
    # Google Pixel (all Snapdragon / Tensor)
    # NOTE: these six were hand-corrected in data/hardware.json by the 2026-07-28 SoC audit but the map
    # below was never updated, so ANY regeneration silently reverted them (the "original sunfish bug" the
    # coherence test pins: 4a is SD730G/sm7150, not the sm6150 default). Fixed at the source 2026-08-01 —
    # tests/test_coherence.py::test_known_device_socs is the authority for these values.
    "blueline": "sdm845", "crosshatch": "sdm845", "sargo": "sdm670", "bonito": "sdm670",
    "flame": "msmnile", "coral": "msmnile", "sunfish": "sm7150", "bramble": "lito",
    "redfin": "lito", "barbet": "lito", "oriole": "gs101", "raven": "gs101",
    # LG
    "judyln": "sdm845", "mh2lm": "msmnile",
    # Motorola
    "racer": "lahaina", "sofiap": "sdm665", "kiev": "lito", "nairo": "lito",
    "ali": "sdm660", "evert": "sdm660", "river": "sdm660", "ocean": "bengal",
    "channel": "trinket", "smith": "kona", "beckham": "msm8998",
    # Samsung Galaxy A / M (mid/budget, Exynos)
    "a01core": "exynos850", "a20": "exynos7884", "a40": "exynos7884", "a50": "exynos9610",
    "a50s": "exynos9611", "a51": "exynos9611", "a52q": "exynos1280", "a6lte": "exynos7870",
    "a6plte": "sdm660", "a70q": "sm6150", "a71": "sm7150", "a7y18lte": "exynos7885",
    "m20lte": "exynos7904", "m21": "exynos9611", "r3q": "sdm855",
    # Samsung flagship (US = Snapdragon, EU/KR = Exynos)
    "beyond0": "exynos9820", "beyond1": "exynos9820", "beyond2": "exynos9820", "beyond2q": "msmnile",
    "beyondx": "exynos9820", "crownlte": "exynos9810", "d2q": "exynos9825", "d2s": "exynos9825",
    "c2q": "exynos990", "c2s": "exynos990", "r5q": "msmnile", "r8s": "exynos990", "r8q": "kona",
    "z3s": "exynos990", "o1s": "exynos2100",
    # US-carrier Samsung flagships (2026-07-31). US variants are SNAPDRAGON where the EU/KR twin is
    # Exynos — e.g. o1s (EU S21) is exynos2100 but o1q (US S21) is SD888/lahaina. SoC per model taken
    # from the device-telemetry corpus's own chipset_model column, not inferred:
    #   SM-G991U/G996U/G998U/G990U -> SM8350 (lahaina) · SM-S901U/S906U/S908U -> SM8450 (taro)
    #   SM-S918U -> SM8550 (kalama) · SM-G981U/G986U/G988U -> SM8250 (kona) · SM-A536U -> s5e8825
    "o1q": "lahaina", "t2q": "lahaina", "p3q": "lahaina", "r9q": "lahaina",
    "r0q": "taro", "g0q": "taro", "b0q": "taro",
    "dm3q": "kalama",
    "x1q": "kona", "y2q": "kona", "z3q": "kona",
    "a53x": "exynos1280",
    # A52 5G US (a52xq) is SM7225 / SD750G — the lito family, NOT the Exynos of the 4G a52q. Must be
    # mapped explicitly: longest-prefix would otherwise resolve "a52xq" via a shorter "a5.." stem.
    "a52xq": "lito",
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
    "sdm855": {  # A90 5G — SD855. Kryo 485 (Qualcomm impl 0x51): 4 gold (0x804) + 4 silver (0x805), same
        # as msmnile. Old 0x41:0xd0d (Cortex-A77) was the wrong scheme AND wrong core.
        "gpu_renderer": "Adreno (TM) 640", "gpu_vendor": "Qualcomm", "gles_version": "3.2",
        "cpu_parts": [(0x51, 0x804, 4), (0x51, 0x805, 4)],
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
    ARM/QC processor lines; a trailing Hardware line. Coherent core count + part IDs.

    Cluster order matters and is NOT free: a real big.LITTLE Android device enumerates the LITTLE
    cores first, so CPU0 is the efficiency core and the last CPU is the prime core — which is exactly
    what data/soc_topology.json encodes (cpu_capacity "286 286 286 286 851 851 851 1024", little->big).
    SOC_SPECS lists cpu_parts big-first (prime cluster first, the way chips are marketed), so emitting
    them in declaration order made /proc/cpuinfo claim CPU0 was the Cortex-X while cpu_capacity said
    CPU0 was the little core — one profile asserting two different things about the same core (codex).
    Reverse the cluster list here so both signals agree. Pre-existing for every SoC; fixed 2026-08-01.
    """
    lines = []
    idx = 0
    for impl, part, count in reversed(soc_spec["cpu_parts"]):
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


# A few platforms ship MORE THAN ONE Adreno across their device family, so the SoC alone doesn't fix the
# renderer string: lito covers SD750G (Adreno 619, Moto G 5G "kiev") and SD765G (Adreno 620, Pixel 5 /
# 4a5G / Moto One 5G "nairo"). Per-codename override, checked before the SoC default.
# Authority: tests/test_coherence.py::_KNOWN_DEVICE_SOC.
GPU_RENDERER_OVERRIDE = {
    "kiev": "Adreno (TM) 619",
}


def _brand_of(row):
    """Sensor-vendor family from the device row's brand slot (row[2])."""
    b = row[1].lower()
    if "samsung" in b: return "samsung"
    if "google" in b: return "google"
    if "lg" in b: return "lge"
    return "motorola"


def _tier_cameras(codename):
    flagship = ("flame", "coral", "oriole", "raven", "redfin", "o1s", "c2q", "c2s", "d2q", "d2s",
                "z3s", "beyond1", "beyond2", "beyondx", "r8q", "r8s", "racer", "smith",
                # US-carrier Samsung flagships: S20 (x1q/y2q/z3q), S21 (o1q/t2q/p3q), S22 (r0q/g0q/b0q),
                # S23 Ultra (dm3q). All quad-camera-class devices like their EU twins above.
                "x1q", "y2q", "z3q", "o1q", "t2q", "p3q", "r0q", "g0q", "b0q", "dm3q")
    mid = ("a51", "a52q", "a70q", "a71", "a50", "sunfish", "bramble", "barbet", "sargo",
           "bonito", "blueline", "crosshatch", "judyln", "mh2lm", "beyond0", "crownlte", "r3q", "r5q",
           "kiev", "nairo", "sofiap", "river", "evert",
           # S21 FE (r9q) is triple-camera, and the US A5x mid-range (a52xq/a53x) likewise.
           "r9q", "a52xq", "a53x")
    if codename.startswith(flagship): return ["0", "1", "2", "3"]
    if codename.startswith(mid): return ["0", "1", "2"]
    return ["0", "1"]


def _tier_sensors(codename, brand):
    """Sensor list for a device, tiered by model class. A barometer (pressure, type 6) and a grip/SAR sensor
    are flagship/mid features — a budget phone (2-camera tier) that reports them is incoherent. So for a
    budget device drop those rows from the vendor list; flagship/mid keep the full set."""
    base = SENSORS_BY_VENDOR[brand]
    if _tier_cameras(codename) == ["0", "1"]:   # budget tier
        drop = {6, 65637}   # pressure (barometer) + grip/SAR — not present on entry-level phones
        return [s for s in base if s[2] not in drop]
    return base


def _entry(codename, soc, brand):
    spec = SOC_SPECS[soc]
    sensors = _tier_sensors(codename, brand)
    renderer = spec["gpu_renderer"]
    for stem, gpu in GPU_RENDERER_OVERRIDE.items():
        if codename.startswith(stem):
            renderer = gpu
            break
    return {
        "soc": soc,
        "gpu_renderer": renderer,
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
