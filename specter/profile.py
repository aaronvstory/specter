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
import tempfile

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
        if os.path.exists(self.path):
            try:
                return json.load(open(self.path))
            except Exception:
                return {}
        return {}

    def _refresh_from_disk(self):
        """Reload disk state into memory (so collides() sees other processes' recent ids)."""
        self.data = self._read_disk()
        self._sets = {k: set(self.data.get(k, [])) for k in UNIQUE_KEYS}

    def collides(self, profile):
        return any(profile[k] in self._sets.get(k, set()) for k in UNIQUE_KEYS)

    def record(self, profile):
        """Atomically merge this profile's unique ids into the on-disk record under a lock."""
        with _file_lock(self.path):
            disk = self._read_disk()  # newest truth, incl. other processes
            for k in UNIQUE_KEYS:
                lst = disk.setdefault(k, [])
                if profile[k] not in set(lst):
                    lst.append(profile[k])
            _atomic_write_json(self.path, disk)
            self.data = disk
            self._sets = {k: set(self.data.get(k, [])) for k in UNIQUE_KEYS}

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
        os.replace(tmp, path)
    finally:
        if os.path.exists(tmp):
            os.unlink(tmp)


class _file_lock:
    """Cross-platform exclusive lock on a sidecar .lock file (msvcrt on Windows, fcntl elsewhere)."""
    def __init__(self, target_path):
        self.lockpath = target_path + ".lock"
        self.fh = None

    def __enter__(self):
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
        return self

    def __exit__(self, *exc):
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


def generate_unique(used_store, us_bias=True, seed=None, max_tries=1000):
    """Generate a validated, never-before-used profile. Records it atomically. Returns the profile."""
    devices = _load_devices()
    r = _seeded(seed) if seed is not None else _csprng
    for _ in range(max_tries):
        p = build_profile(r, devices, us_bias)
        ok, errs = validate(p)
        if not ok:
            continue
        if used_store is not None:
            used_store._refresh_from_disk()  # see other processes' latest ids before checking
            if used_store.collides(p):
                continue
            used_store.record(p)
        return p
    raise RuntimeError("could not generate a fresh valid profile in %d tries" % max_tries)
