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
import secrets

from . import generators as G
from .identifiers import BUILD_FIELDS, UNIQUE_KEYS

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
DEVICES_PATH = os.path.join(ROOT, "data", "devices.json")

US_COMMON_BRANDS = {"samsung", "google", "motorola", "oneplus", "lge"}

# real US carriers (MCC 310/311) so the SIM identity is coherent for a US driver
US_CARRIERS = [
    ("310260", "T-Mobile"), ("311480", "Verizon"), ("310410", "AT&T"),
    ("310120", "Sprint"), ("311580", "US Cellular"), ("310030", "AT&T"),
    ("310160", "T-Mobile"), ("311870", "Boost Mobile"),
]


def _load_devices():
    return json.load(open(DEVICES_PATH, encoding="utf-8"))


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


def _pick_device(r, devices, us_bias):
    if us_bias:
        pool = [d for d in devices if len(d) > 2 and d[2].lower() in US_COMMON_BRANDS]
        if pool:
            return pool[r(len(pool))]
    return devices[r(len(devices))]


def build_profile(r, devices, us_bias=True):
    dev = _pick_device(r, devices, us_bias)
    manufacturer, brand, device, product = dev[1], dev[2], dev[3], dev[4]
    model_rel = dev[5]
    model = model_rel.split(":")[0]
    release = model_rel.split(":")[1] if ":" in model_rel else "11"
    build_id = dev[6] if len(dev) > 6 else "RQ3A.211001.001"
    incremental = dev[7] if len(dev) > 7 else G.digits(r, 7)
    patch = dev[8] if len(dev) > 8 else "2021-01-01"
    fingerprint = f"{brand}/{product}/{device}:{release}/{build_id}/{incremental}:user/release-keys"

    mccmnc, carrier = US_CARRIERS[r(len(US_CARRIERS))]

    return {
        "android_id": G.hex16(r),
        "imei1": G.imei(r),
        "imei2": G.imei(r),
        "serial": G.hex16upper(r),
        "advertising_id": G.uuid(r),
        "gsf_id": G.gsf(r),
        "media_drm_id": G.hex32(r),
        "bluetooth_mac": G.mac_upper(r),
        "wifi_mac": G.mac_upper(r),
        "wifi_bssid": G.mac_lower(r),
        "wifi_ssid": G.ssid(r),
        "mobile_number": G.phone_us(r),
        "sim_operator_mccmnc": mccmnc,
        "sim_operator_name": carrier,
        "sim_subscriber_imsi": G.imsi(r, mccmnc),
        "sim_serial_iccid": G.iccid(r),
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
    }


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
    return (len(errors) == 0, errors)


class UsedStore:
    """Persistent record of every unique id ever issued — guarantees no reuse across signups."""
    def __init__(self, path):
        self.path = path
        self.data = {}
        if os.path.exists(path):
            try:
                self.data = json.load(open(path))
            except Exception:
                self.data = {}
        self._sets = {k: set(self.data.get(k, [])) for k in UNIQUE_KEYS}

    def collides(self, profile):
        return any(profile[k] in self._sets.get(k, set()) for k in UNIQUE_KEYS)

    def record(self, profile):
        for k in UNIQUE_KEYS:
            self._sets.setdefault(k, set()).add(profile[k])
            self.data.setdefault(k, []).append(profile[k])

    def save(self):
        json.dump(self.data, open(self.path, "w"), indent=2)

    def count(self):
        return len(self.data.get("gsf_id", []))


def generate_unique(used_store, us_bias=True, seed=None, max_tries=1000):
    """Generate a validated, never-before-used profile. Records it. Returns the profile."""
    devices = _load_devices()
    r = _seeded(seed) if seed is not None else _csprng
    for _ in range(max_tries):
        p = build_profile(r, devices, us_bias)
        ok, errs = validate(p)
        if not ok:
            continue
        if used_store is not None and used_store.collides(p):
            continue
        if used_store is not None:
            used_store.record(p)
        return p
    raise RuntimeError("could not generate a fresh valid profile in %d tries" % max_tries)
