"""
profile.py — assemble one coherent device identity from the device DB + generators,
and enforce global uniqueness via a used-id store.

Coherence rules (a fingerprint that fails these is a fraud flag, per the GeerGit analysis):
  - Build.* fields all come from ONE real device row (fingerprint matches brand/device/release)
  - IMSI carrier prefix == SIM operator MCC/MNC
  - US-market device + US carrier (a US DoorDash driver on a Belarus carrier is a flag)
"""
import json
import os
import re
import secrets
import tempfile
import threading

from . import generators as G
from .identifiers import BUILD_FIELDS, UNIQUE_KEYS

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEVICES_PATH = os.path.join(ROOT, "data", "devices.json")
HARDWARE_PATH = os.path.join(ROOT, "data", "hardware.json")
SOC_TOPOLOGY_PATH = os.path.join(ROOT, "data", "soc_topology.json")

US_COMMON_BRANDS = {"samsung", "google", "motorola", "lge"}

# Samsung sells REGION-specific models: an "F"/"FN"/"M"/"B"/"G" suffix is international (Euro/Asia/LATAM),
# while US carrier units end in U/U1/V/A/T/P (W=Canada). A US-only profile pairing an intl Samsung model
# with a US carrier is an internal coherence tell, so for Samsung we keep only US-market model numbers.
# Other US brands (Google/Motorola/LGE) do not carry this US-vs-intl carrier-suffix split, so they pass.
_US_SAMSUNG_MODEL = __import__("re").compile(r"\d(U1?|U2|U3|V|A|T|P|W)$")
def _is_us_model(brand, model):
    if (brand or "").lower() != "samsung":
        return True
    return bool(_US_SAMSUNG_MODEL.search(model or ""))

# real US carriers (MCC 310/311) so the SIM identity is coherent for a US driver
US_CARRIERS = [
    ("310260", "T-Mobile"), ("311480", "Verizon"), ("310410", "AT&T"),
    ("310120", "Sprint"), ("311580", "US Cellular"), ("310030", "AT&T"),
    ("310160", "T-Mobile"), ("311870", "Boost Mobile"),
    ("310004", "Verizon"), ("310090", "AT&T"), ("312530", "Sprint"),
    ("311882", "Mint Mobile"), ("310240", "T-Mobile"),
]

# USA-only build. One coherent US market: US carriers + NANP phones + US-market device brands.
COUNTRIES = {
    "US": {"name": "United States", "carriers": US_CARRIERS, "phone": "nanp",
           "brands": {"samsung", "google", "motorola", "lge"}},
}

def country_of(code):
    return COUNTRIES["US"]


def _load_devices():
    return json.load(open(DEVICES_PATH, encoding="utf-8"))


_HARDWARE_CACHE = None
_HARDWARE_LOCK = threading.Lock()
_SOC_TOPO_CACHE = None
_SOC_TOPO_LOCK = threading.Lock()


def _load_soc_topology():
    """Per-SoC CPU-capacity vector + GPU model (data/soc_topology.json), keyed by SoC codename.
    These are the values behind /sys/.../cpu_capacity and /sys/class/kgsl/kgsl-3d0/gpu_model, which
    FingerprintJS reads directly and which leaked the REAL device on every rotation. Pure constant
    lookup keyed on the already-picked SoC (no RNG) -> byte-parity safe."""
    global _SOC_TOPO_CACHE
    if _SOC_TOPO_CACHE is None:
        with _SOC_TOPO_LOCK:
            if _SOC_TOPO_CACHE is None:
                _SOC_TOPO_CACHE = json.load(open(SOC_TOPOLOGY_PATH, encoding="utf-8"))
    return _SOC_TOPO_CACHE


def _soc_topology_fields(soc, topo=None):
    """cpu_capacity / gpu_model / cpu_present for a SoC. Falls back to "_default" so the lookup is
    total. Coherent by construction: the values describe the SoC the profile already claims."""
    t = (topo or _load_soc_topology())
    e = t.get(soc) or t["_default"]
    cap = e["cpu_capacity"]
    n = len(cap.split())
    out = {
        "cpu_capacity": cap,
        "gpu_model": e.get("gpu_model", ""),
        "cpu_present": "0-%d" % (n - 1),
    }
    # Per-core max/min CPU frequency (kHz), behind /sys/.../cpufreq/cpuinfo_{max,min}_freq. These leak the
    # REAL SoC's core-frequency signature otherwise (e.g. a Pixel 4's SD855 1+3+4 layout while the profile
    # claims an LG G7 SD845 4+4) — a hardware-coherence tell. Emitted only when the SoC table carries them
    # (all do via _default); a constant lookup keyed on the already-picked SoC, so byte-parity safe.
    if e.get("cpu_max_freq"):
        out["cpu_max_freq"] = e["cpu_max_freq"]
    if e.get("cpu_min_freq"):
        out["cpu_min_freq"] = e["cpu_min_freq"]
    # Per-core CPU cache sizes (KB): L1i/L1d (index0/1), per-tier L2 (index2), shared L3 (index3). The native
    # layer redirects the FULL cache tree (size+level+shared_cpu_list) coherently from these — leaks the real
    # SoC's cache fingerprint otherwise. Constant per-SoC lookup, byte-parity safe.
    for k in ("cpu_l1i", "cpu_l1d", "cpu_l2", "cpu_l3"):
        if e.get(k):
            out[k] = e[k]
    return out


def _load_hardware():
    """Per-model hardware descriptors (data/hardware.json), keyed by device codename. Cached under a
    lock so it is read exactly once even when many threads generate profiles concurrently (an unlocked
    lazy read has each thread parse the 200KB file, which perturbs timing and wastes work). It's a
    constant lookup table. See scripts/build_hardware_dataset.py for how it's generated."""
    global _HARDWARE_CACHE
    if _HARDWARE_CACHE is None:
        with _HARDWARE_LOCK:
            if _HARDWARE_CACHE is None:   # double-checked: another thread may have loaded it
                _HARDWARE_CACHE = json.load(open(HARDWARE_PATH, encoding="utf-8"))
    return _HARDWARE_CACHE


def _hw_fields(codename, hardware=None):
    """Flat string->string hardware fields for a device codename, ready for the flat-JSON transport
    both the Java and native hooks read. Lists are delimiter-joined; cpuinfo keeps its real newlines
    (device.py json.dumps handles the escaping; the native layer decodes 
/	).

    These are CONSTANTS keyed on the already-picked device — they consume NO seeded RNG, so they are
    byte-parity-safe by construction (a constant never shifts the draw order). Missing codenames fall
    back to the coherent "_default" bundle so the lookup is total."""
    hw = (hardware or _load_hardware())
    e = hw.get(codename) or hw["_default"]
    sensors = ";".join("%s|%s|%d" % (x["name"], x["vendor"], x["type"]) for x in e["sensors"])
    return {
        "hw_gpu_renderer": e["gpu_renderer"],
        "hw_gpu_vendor": e["gpu_vendor"],
        "hw_gles_version": e["gles_version"],
        "hw_cores": str(e["cores"]),
        "hw_sensors": sensors,
        "hw_cameras": ",".join(e["cameras"]),
        "hw_codecs": ",".join(e["codecs"]),
        "hw_input_devices": ",".join(e["input_devices"]),
        "proc_cpuinfo": e["cpuinfo"],
    }


def _csprng(n):
    return secrets.randbelow(n)


def _seeded(seed):
    import hashlib
    state = {"h": hashlib.sha256(str(seed).encode()).digest(), "i": 0}
    def r(n):
        state["i"] += 1
        d = hashlib.sha256(state["h"] + state["i"].to_bytes(8, "big")).digest()
        return int.from_bytes(d[:8], "big") % n
    return r


# Minimum plausible Android major version. We never claim to be a device older than Android 11 (2020):
# an Android-9/10 profile on a real Android-11+ host is itself a giveaway (the SIGSEGV-sensitive
# ro.build.version.sdk / ro.product.first_api_level leak the REAL host SDK during a brief startup
# window before the native late-map arms, so a claimed SDK 28 vs a real 30 is a detectable contradiction
# — see the Cash App failure investigation). Kept as a named constant so the coherence test and both
# language generators agree on the floor. devices.json is stocked with real A11+ US devices to keep the
# pool from starving at this floor.
MIN_ANDROID_MAJOR = 11

# Marketing-name substrings that identify a tablet / TV box. We generate a phone number + SIM + IMEI,
# so a WiFi tablet or TV box is incoherent. Matched against the device row's NAME (row[0]).
_NON_PHONE_MARKERS = ("Tab", "Nexus 7", "Nexus 9", "Nexus 10", "Nexus Player", "Shield", "Pixel C")


def _release_major_of(dev):
    """Android major version from a device row's "model:release" slot (row[5]). 0 if unparseable.
    Parses ONLY leading digits of the major component — kept in exact lockstep with Java
    releaseMajorOf (Integer.parseInt of the digit run), so no float/locale path can ever diverge."""
    try:
        head = dev[5].split(":", 1)[1].split(".")[0] if ":" in dev[5] else "0"
        digits = ""
        for ch in head:
            if ch.isdigit():
                digits += ch
            else:
                break
        return int(digits) if digits else 0
    except Exception:
        return 0


def _is_plausible_phone(dev):
    """A device we'd credibly sign up from: a phone (not tablet/TV) on a recent-enough OS."""
    if len(dev) <= 5:
        return False
    if any(m in dev[0] for m in _NON_PHONE_MARKERS):
        return False
    return _release_major_of(dev) >= MIN_ANDROID_MAJOR


def _pick_device(r, devices, us_bias, brands=None):
    if us_bias:
        pool_brands = brands if brands is not None else US_COMMON_BRANDS
        pool = [d for d in devices
                if len(d) > 3 and d[2].lower() in pool_brands and _is_plausible_phone(d)
                and _is_us_model(d[2], d[3])]
        if pool:
            return pool[r(len(pool))]
    return devices[r(len(devices))]


def build_profile(r, devices, us_bias=True, country="US", hardware=None):
    cc = country_of(country)
    dev = _pick_device(r, devices, us_bias, cc["brands"])
    # Dataset row: [name, manufacturer, brand, MODEL, PRODUCT, "DEVICE:release", id, incremental, patch].
    # col3 is the MARKETING model (Build.MODEL, "Pixel 4"), col5's prefix is the DEVICE CODENAME
    # (Build.DEVICE, "flame") — verified against a real Pixel 4: MODEL="Pixel 4", DEVICE=PRODUCT="flame",
    # fingerprint "google/flame/flame:11/...". Binding them the other way round produced fingerprints
    # like "google/bramble/Pixel 4a (5G):11/..." — a DEVICE slot containing spaces and parentheses,
    # which no real Android build ever emits, i.e. a hard giveaway in every profile.
    manufacturer, brand, model, product = dev[1], dev[2], dev[3], dev[4]
    device_rel = dev[5]
    device = device_rel.split(":")[0]
    release = device_rel.split(":")[1] if ":" in device_rel else "11"
    build_id = dev[6] if len(dev) > 6 else "RQ3A.211001.001"
    incremental = dev[7] if len(dev) > 7 else G.digits(r, 7)
    patch = dev[8] if len(dev) > 8 else "2021-01-01"
    fingerprint = f"{brand}/{product}/{device}:{release}/{build_id}/{incremental}:user/release-keys"

    mccmnc, carrier = cc["carriers"][r(len(cc["carriers"]))]

    # One TAC per device (from the manufacturer), shared by both IMEIs — real dual-SIM devices
    # share the TAC and differ only in the serial portion. imei1 != imei2 (different serials).
    tac = G._tac_for_brand(r, brand)

    # Board/platform CODENAME lives in the product slot ("flame" for a Pixel 4), not the marketing
    # device name ("Pixel 4"). LG products carry a region suffix (h1_lra_us) — strip it.
    codename = product.split("_")[0]
    # Samsung derives its bootloader from the SM- marketing model; others from the codename.
    bl_base = model if brand.lower() == "samsung" else codename
    # Look up the per-model hardware bundle ONCE — its SoC drives soc_platform (so the reported SoC is
    # coherent with the GPU/cpuinfo the same profile carries), and the whole entry is reused for the
    # hardware fields appended at the end. Missing codename -> the coherent _default bundle.
    _hw = (hardware or _load_hardware())
    _hw_entry = _hw.get(codename) or _hw["_default"]

    p = {
        "android_id": G.hex16(r),
        "imei1": G.imei(r, tac),
        "imei2": G.imei(r, tac),
        "serial": G.serial_for_brand(r, brand),
        "advertising_id": G.uuid(r),
        "gsf_id": G.gsf(r),
        "media_drm_id": G.hex32(r),
        # L3 (software Widevine): coherent with a spoofed/changing deviceUniqueId. A genuine L1
        # device has a FIXED hardware id, so a changing id at L1 is a red flag (confirmed on-device).
        # Constant -> consumes no RNG -> Java byte-parity order is unchanged. See docs/BYEDENTITY-ANALYSIS.md.
        "media_drm_security_level": "L3",
        "bluetooth_mac": G.mac_upper(r),
        "wifi_mac": G.mac_upper(r),
        "wifi_bssid": G.mac_lower(r),
        "wifi_ssid": G.ssid(r),
        "mobile_number": G.phone_for_country(r, cc["phone"]),
        "sim_operator_mccmnc": mccmnc,
        "sim_operator_name": carrier,
        "sim_subscriber_imsi": G.imsi(r, mccmnc),
        "sim_serial_iccid": G.iccid(r, mccmnc),
        "gmail": G.gmail(r),
        "build_manufacturer": manufacturer,
        "build_brand": brand,
        "build_device": device,
        "build_product": product,
        "build_model": model,
        "build_release": release,
        "build_id": build_id,
        "build_incremental": incremental,
        "build_fingerprint": fingerprint,
        "build_security_patch": patch,
        "build_bootloader": G.bootloader(r, brand, bl_base),
        "build_hardware": codename,
        "build_board": codename,
        "build_kernel_version": G.kernel_version(r, release),
        "build_radio": G.radio_version(r),
        # walrus keeps the RNG draw AT this position (between radio and host) to preserve Java parity
        "total_ram": (_ram_storage := G.ram_storage_bytes(r, _hw_entry.get("soc", "")))[0],
        "total_storage": _ram_storage[1],
        "build_host": G.build_host(r),
        "build_display": build_id,
        "soc_platform": G.soc_platform(product, _hw_entry.get("soc")),
        # Factory-reset time — the signal FPJS Pro used to re-link three rotated identities (PROVEN
        # 2026-07-25). Derived from this build's security patch so the pair is coherent by construction
        # (a device can't be reset before its own OS was built). LAST in the dict, so the draw is
        # appended to the end of the RNG order and every existing field's value is unchanged.
        "factory_reset_epoch": G.factory_reset_epoch(r, patch),
        # App Set ID (com.google.android.gms.appset) — a per-app-scoped install id apps read for
        # analytics. A UUID like the advertising id. LAST RNG draw so existing fields' draw order
        # (and Java byte-parity) is unchanged.
        "app_set_id": G.uuid(r),
    }
    # Per-model hardware descriptors (GPU/GLES, sensors, cameras, codecs, input, core count,
    # /proc/cpuinfo) — a coherent bundle for the device model this identity claims to be. Constant
    # lookup keyed on the picked device codename; consumes no RNG (byte-parity safe). LAST so every
    # existing field's draw order is unchanged.
    p.update(_hw_fields(codename, _hw))
    # Per-SoC CPU-capacity vector + GPU model — the /sys hardware signals FPJS reads directly.
    # Keyed on the already-computed soc_platform; pure constant, no RNG (byte-parity safe).
    p.update(_soc_topology_fields(p["soc_platform"]))
    # The /sys KGSL gpu_model IS the device's actual GPU, which the GL renderer names. When a SoC
    # platform serves MULTIPLE Adreno models (e.g. "lito" = Adreno 619 on SD750G kiev AND 620 on SD765G
    # nairo), the per-SoC topology gpu_model can't distinguish them — so derive gpu_model from the
    # per-model renderer string when it names an Adreno number, keeping gpu_model == renderer coherent.
    # Pure constant (regex over a constant string), no RNG — byte-parity safe.
    _m = re.search(r"Adreno.*?([0-9]{3})", p.get("hw_gpu_renderer") or "")
    if _m:
        p["gpu_model"] = _m.group(1)
    # gpu_hw = the GPU driver family (adreno / mali / powervr) behind ro.hardware.{egl,vulkan,gralloc}. These
    # leak the REAL device's GPU vendor otherwise (proven: a Samsung/Exynos profile with a Mali renderer still
    # read ro.hardware.egl=adreno on a Pixel host — a direct GPU contradiction). Derived from the renderer
    # string (constant lookup, no RNG -> byte-parity safe). Mirrored in Java Generators.gpuHwFor().
    p["gpu_hw"] = G.gpu_hw_for(p.get("hw_gpu_renderer") or "")
    # API level coherent with the claimed Android release (Build.VERSION.SDK_INT /
    # ro.build.version.sdk / ro.product.first_api_level). Pure, no RNG (byte-parity safe).
    p["build_sdk"] = str(G.sdk_for_release(release))
    # ro.product.first_api_level = the device's LAUNCH API (when it shipped), which is <= build_sdk for a
    # device updated past its launch OS. launch_api_for(model, build_sdk) returns the real launch API for
    # known models, else build_sdk (so first_api==sdk, the prior behaviour, for anything not yet mapped).
    # Pure lookup, no RNG (byte-parity safe).
    p["build_first_api"] = str(G.launch_api_for(model, int(p["build_sdk"])))
    # Screen resolution + density (getDisplayMetrics signal). Keyed on the device codename;
    # pure lookup/hash, no RNG (byte-parity safe).
    _sw, _sh, _sd = G.screen_for_device(device)
    p["screen_width"] = str(_sw)
    p["screen_height"] = str(_sh)
    p["screen_density"] = str(_sd)
    # ro.build.flavor / ro.build.description composites (they leak the real device otherwise).
    # flavor = "<device>-<type>"; description = "<device>-<type> <release> <id> <incr> <tags>".
    # release-keys/user by construction. Pure (no RNG), byte-parity safe.
    p["build_flavor"] = device + "-user"
    p["build_description"] = device + "-user " + release + " " + build_id + " " + incremental + " release-keys"
    # Settings.Global.BOOT_COUNT — a per-device-stable integer FPJS/EXADPrinter hash. Derived from the
    # android_id so it's stable per profile but not the host's real count. Pure, no RNG (byte-parity safe).
    p["boot_count"] = str(G.boot_count_for(p.get("android_id", "")))
    # Battery design capacity in µAh (BatteryManager full-capacity signal). Per-device-stable, derived
    # from the codename. Pure, no RNG (byte-parity safe).
    p["battery_uah"] = str(G.battery_uah_for(device))
    # US timezone derived from the phone's area code (US number = "1"+area(3)+exch(3)+sub(4)), so phone +
    # timezone + locale tell one coherent US-location story. Locale is always en-US (US-only build). Pure
    # lookup, no RNG (byte-parity safe). Gate on the NANP phone format ONLY (not the raw `country` arg,
    # which country_of() has already resolved to US for every input) so the condition byte-matches the Java
    # side (Profile.java gates on the same phone format) and the fields are never silently dropped.
    _ph = p.get("mobile_number", "")
    if len(_ph) == 11 and _ph.startswith("1"):
        p["timezone"] = G.tz_for_area_code(_ph[1:4])
        p["locale"] = "en-US"
    return p


def validate(profile):
    """Return (ok, [errors]). Checks per-field format + cross-field coherence."""
    errors = []
    for k, v in profile.items():
        if not G.validate(k, str(v)):
            errors.append(f"invalid format: {k}={v}")
    # coherence
    fp = profile.get("build_fingerprint", "")
    for f in ("build_brand", "build_device"):
        if profile.get(f) and profile[f] not in fp:
            errors.append(f"incoherent: {f}={profile.get(f)} not in fingerprint")
    if profile.get("sim_operator_mccmnc") and not profile.get("sim_subscriber_imsi", "").startswith(profile["sim_operator_mccmnc"]):
        errors.append("incoherent: IMSI does not start with SIM MCC/MNC")
    # ICCID issuer prefix should match the carrier (when we have a known IIN for it)
    mccmnc = profile.get("sim_operator_mccmnc")
    iccid = profile.get("sim_serial_iccid", "")
    expected_iin = G._ICCID_IIN.get(mccmnc)
    if expected_iin and not iccid.startswith(expected_iin):
        errors.append(f"incoherent: ICCID {iccid[:8]} does not match carrier IIN {expected_iin}")
    return (len(errors) == 0, errors)


class UsedStoreCorrupt(RuntimeError):
    """Raised when the used-id ledger exists but is unreadable — we fail closed, never open."""


class UsedStore:
    """
    Persistent record of every unique id ever issued — guarantees no reuse across signups.

    Concurrency-safe: record() re-reads the on-disk state under an exclusive OS file lock,
    merges the new id into it, and atomically replaces the file. A stale in-memory snapshot
    can therefore never erase ids another concurrent process recorded. This is the
    ban-critical property — the whole tool exists to never reuse an identifier.
    """
    def __init__(self, path):
        self.path = path
        self.data = self._read_disk()
        self._sets = {k: set(self.data.get(k, [])) for k in UNIQUE_KEYS}

    def _read_disk(self):
        # A concurrent record() does os.replace(tmp, path); on Windows a reader can momentarily hit a
        # TRANSIENT share violation (PermissionError) OR see the file absent mid-rename (FileNotFoundError).
        # NEITHER is proof of a fresh/empty ledger — an eager `os.path.exists()` check races the same gap
        # and could return {} for a ledger that has content (ban-critical: every issued id becomes reusable,
        # codex 2026-07-27). So we NEVER short-circuit on absence: we retry open() through the whole budget,
        # and only conclude "genuinely fresh ledger" ({}) when the file stays absent for the ENTIRE retry
        # window (a persistent FileNotFoundError). A persistent NON-absence OS error fails closed (raises).
        # Only a real JSON ValueError means the ledger is corrupt and is quarantined (fail closed).
        import time as _t
        last_err = None
        for _attempt in range(50):
            try:
                with open(self.path, encoding="utf-8") as f:
                    data = json.load(f)
            except FileNotFoundError as e:
                last_err = e            # possibly the transient replace gap — retry; may reappear
                _t.sleep(0.02)
                continue
            except OSError as e:        # PermissionError (share violation) + other transient I/O — retry
                last_err = e
                _t.sleep(0.02)
                continue
            except ValueError as e:
                # Genuinely malformed JSON — FAIL CLOSED: an empty ledger would let every previously
                # issued id be reused. Quarantine the bad file and refuse rather than continue with {}.
                quarantine = self.path + ".corrupt"
                try:
                    os.replace(self.path, quarantine)
                except OSError:
                    quarantine = "(could not move)"
                raise UsedStoreCorrupt(
                    f"used-id ledger at {self.path} is unreadable ({e}); quarantined to {quarantine}. "
                    "Refusing to continue — an empty ledger would allow reusing already-issued ids. "
                    "Restore a good ledger or start fresh deliberately."
                ) from e
            if not isinstance(data, dict):
                raise UsedStoreCorrupt(f"used-id ledger at {self.path} is not a JSON object")
            return data
        # Retry budget (~1s) exhausted. Only NOW decide: a file that was absent the WHOLE time is a genuine
        # fresh ledger ({}); anything else (persistent PermissionError/other I/O) is a real problem — raise
        # rather than silently hand back an empty ledger that would allow id reuse.
        if isinstance(last_err, FileNotFoundError):
            return {}
        raise UsedStoreCorrupt(
            f"used-id ledger at {self.path} could not be read after retries ({last_err})"
        ) from last_err

    def _refresh_from_disk(self):
        """Reload disk state into memory (so collides() sees other processes' recent ids)."""
        self.data = self._read_disk()
        self._sets = {k: set(self.data.get(k, [])) for k in UNIQUE_KEYS}

    def collides(self, profile):
        return any(profile[k] in self._sets.get(k, set()) for k in UNIQUE_KEYS)

    def record(self, profile):
        """
        Atomically claim this profile's unique ids under the file lock.

        Returns True if THIS call actually claimed the ids (they were all new on disk), or
        False if ANY of them was already present — meaning a concurrent caller claimed the same
        value in the window between our collides() check and now. The caller MUST treat False as
        a collision and retry, otherwise two callers could be handed the identical profile even
        though the disk stays correct. This is the ban-critical reuse guard.
        """
        with _file_lock(self.path):
            disk = self._read_disk()  # newest truth, incl. other processes
            # If any unique id is already on disk, someone else claimed it first — reject.
            for k in UNIQUE_KEYS:
                if profile[k] in set(disk.get(k, [])):
                    self.data = disk
                    self._sets = {kk: set(disk.get(kk, [])) for kk in UNIQUE_KEYS}
                    return False
            for k in UNIQUE_KEYS:
                disk.setdefault(k, []).append(profile[k])
            _atomic_write_json(self.path, disk)
            self.data = disk
            self._sets = {k: set(self.data.get(k, [])) for k in UNIQUE_KEYS}
            return True

    def save(self):
        """No-op kept for API compatibility — record() already persists atomically."""
        return

    def count(self):
        return len(self.data.get("gsf_id", []))


def _atomic_write_json(path, obj):
    """Write to a temp file in the same dir, then os.replace — never leaves a partial file."""
    d = os.path.dirname(os.path.abspath(path)) or "."
    fd, tmp = tempfile.mkstemp(dir=d, suffix=".tmp")
    try:
        with os.fdopen(fd, "w") as f:
            json.dump(obj, f, indent=2)
            f.flush()
            os.fsync(f.fileno())   # durable BEFORE the rename, so a concurrent reader that sees the
                                   # replaced name never reads stale/empty content (Windows visibility gap)
        # os.replace fails with ERROR_ACCESS_DENIED (PermissionError) on Windows when another
        # thread/process has the TARGET open for reading (a share violation) — a TRANSIENT condition,
        # not a real failure. Without a retry it would bubble out of record()/generate_unique() and
        # silently drop that caller (a lost ledger update under concurrency). Retry briefly.
        import time as _t
        for _attempt in range(50):
            try:
                os.replace(tmp, path)
                break
            except PermissionError:
                _t.sleep(0.02)
        else:
            os.replace(tmp, path)   # final attempt: let a persistent failure raise (real problem)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


# In-process locks keyed by lockpath. The OS file lock (msvcrt/fcntl) guards CROSS-PROCESS access,
# but msvcrt byte-range locks are NOT reliably exclusive between THREADS of one process (each thread
# opens its own handle) — proven by a flaky tiny-keyspace concurrency test. This per-path threading
# lock serializes threads in-process; the file lock still serializes across processes. Belt+suspenders.
_INPROC_LOCKS = {}
_INPROC_LOCKS_GUARD = threading.Lock()


def _inproc_lock_for(lockpath):
    with _INPROC_LOCKS_GUARD:
        lk = _INPROC_LOCKS.get(lockpath)
        if lk is None:
            lk = threading.Lock()
            _INPROC_LOCKS[lockpath] = lk
        return lk


class _file_lock:
    """Exclusive lock across BOTH processes (msvcrt/fcntl on the sidecar .lock file) and threads
    (a per-path in-process threading.Lock, since msvcrt locks don't reliably exclude sibling threads)."""
    def __init__(self, target_path):
        self.lockpath = target_path + ".lock"
        self.fh = None
        self._tlock = _inproc_lock_for(self.lockpath)

    def __enter__(self):
        self._tlock.acquire()                       # serialize threads first (msvcrt is per-handle)
        try:
            self.fh = open(self.lockpath, "a+")
            try:
                import fcntl
                fcntl.flock(self.fh.fileno(), fcntl.LOCK_EX)
            except ImportError:
                import msvcrt
                self.fh.seek(0)
                while True:
                    try:
                        msvcrt.locking(self.fh.fileno(), msvcrt.LK_LOCK, 1)
                        break
                    except OSError:
                        import time as _t
                        _t.sleep(0.05)
        except BaseException:
            self._tlock.release()                   # never leak the thread lock on an open/lock failure
            raise
        return self

    def __exit__(self, *exc):
        try:
            try:
                import fcntl
                fcntl.flock(self.fh.fileno(), fcntl.LOCK_UN)
            except ImportError:
                import msvcrt
                try:
                    self.fh.seek(0)
                    msvcrt.locking(self.fh.fileno(), msvcrt.LK_UNLCK, 1)
                except OSError:
                    pass
            self.fh.close()
        finally:
            self._tlock.release()                   # always release the in-process lock


def generate_unique(used_store, us_bias=True, seed=None, max_tries=1000, country="US"):
    """Generate a validated, never-before-used profile. Records it atomically. Returns the profile."""
    devices = _load_devices()
    r = _seeded(seed) if seed is not None else _csprng
    # Refresh from disk ONCE up front (see other processes' latest ids) rather than on every
    # retry — record() does the authoritative locked re-read+reject, and a failed record()
    # updates the in-memory set, so per-retry disk reads would be redundant O(n^2) work.
    if used_store is not None:
        used_store._refresh_from_disk()
    for _ in range(max_tries):
        p = build_profile(r, devices, us_bias, country)
        ok, errs = validate(p)
        if not ok:
            continue
        if used_store is not None:
            if used_store.collides(p):
                continue
            # record() returns False if a concurrent caller claimed these ids first — retry then,
            # so two callers can never be handed the same profile.
            if not used_store.record(p):
                continue
        return p
    raise RuntimeError("could not generate a fresh valid profile in %d tries" % max_tries)
