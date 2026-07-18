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

US_COMMON_BRANDS = {"samsung", "google", "motorola", "lge"}

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


def _pick_device(r, devices, us_bias, brands=None):
    if us_bias:
        pool_brands = brands if brands is not None else US_COMMON_BRANDS
        pool = [d for d in devices if len(d) > 2 and d[2].lower() in pool_brands]
        if pool:
            return pool[r(len(pool))]
    return devices[r(len(devices))]


def build_profile(r, devices, us_bias=True, country="US"):
    cc = country_of(country)
    dev = _pick_device(r, devices, us_bias, cc["brands"])
    manufacturer, brand, device, product = dev[1], dev[2], dev[3], dev[4]
    model_rel = dev[5]
    model = model_rel.split(":")[0]
    release = model_rel.split(":")[1] if ":" in model_rel else "11"
    build_id = dev[6] if len(dev) > 6 else "RQ3A.211001.001"
    incremental = dev[7] if len(dev) > 7 else G.digits(r, 7)
    patch = dev[8] if len(dev) > 8 else "2021-01-01"
    fingerprint = f"{brand}/{product}/{device}:{release}/{build_id}/{incremental}:user/release-keys"

    mccmnc, carrier = cc["carriers"][r(len(cc["carriers"]))]

    # One TAC per device (from the manufacturer), shared by both IMEIs — real dual-SIM devices
    # share the TAC and differ only in the serial portion. imei1 != imei2 (different serials).
    tac = G._tac_for_brand(r, brand)

    return {
        "android_id": G.hex16(r),
        "imei1": G.imei(r, tac),
        "imei2": G.imei(r, tac),
        "serial": G.hex16upper(r),
        "advertising_id": G.uuid(r),
        "gsf_id": G.gsf(r),
        "media_drm_id": G.hex32(r),
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
        "build_bootloader": G.bootloader(r, brand),
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
        if not os.path.exists(self.path):
            return {}  # absent file == a fresh ledger, legitimately empty
        try:
            with open(self.path, encoding="utf-8") as f:
                data = json.load(f)
            if not isinstance(data, dict):
                raise ValueError("used-id ledger is not a JSON object")
            return data
        except Exception as e:
            # FAIL CLOSED: a corrupt/unreadable ledger must NOT be treated as empty, or the
            # no-reuse guarantee silently dies (every previously-issued id becomes reusable).
            # Quarantine the bad file and refuse rather than continue with {}.
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
