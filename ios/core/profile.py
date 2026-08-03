"""
ios/core/profile.py — assemble one coherent iOS device identity from the Apple device
catalog + a seeded RNG, and validate internal coherence.

Mirrors the Android Specter core (specter/profile.py): pick ONE real device row so every
hardware field comes from the same device and cannot contradict, then generate the per-install
unique identifiers deterministically from a seed (same seed -> same profile, for reproducibility
and later Swift/tweak parity).

Priority note (measured 2026-08-03): a sandboxed app is DENIED MobileGestalt UDID/SerialNumber
even on a jailbreak — Cash App read them as None. So the fields that actually reach a fingerprinter
are ProductType/HWModelStr, hw.machine/hw.model/hw.memsize/hw.ncpu, the GPU (IORegistry), the
screen, identifierForVendor, boot time, and device name. `identifier_for_vendor` is therefore the
highest-value field here; serial/udid are generated only for apps that CAN read them.

Run `python profile.py --demo` for a self-check (determinism + coherence over the whole catalog).
"""
import argparse
import json
import os
import random
import uuid

HERE = os.path.dirname(os.path.abspath(__file__))
CATALOG_PATH = os.path.join(HERE, "catalog.json")

# Apple serial alphabet omits I/O/etc. Modern (2021+) serials are 10 random alphanumerics.
_SERIAL_ALPHABET = "0123456789ACDEFGHJKLMNPQRSTUVWXYZ"


def load_catalog(path=CATALOG_PATH):
    with open(path, encoding="utf-8") as f:
        return json.load(f)["devices"]


def _rng(seed):
    """A seeded int source r(n) -> [0, n), matching the Android generators' RNG contract."""
    rnd = random.Random(seed)
    return lambda n: rnd.randrange(n)


def gen_idfv(r):
    """RFC-4122 v4 UUID, uppercased, as -[UIDevice identifierForVendor].UUIDString returns it."""
    b = bytearray(r(256) for _ in range(16))
    b[6] = (b[6] & 0x0F) | 0x40  # version 4
    b[8] = (b[8] & 0x3F) | 0x80  # variant 1
    return str(uuid.UUID(bytes=bytes(b))).upper()


def gen_serial(r):
    return "".join(_SERIAL_ALPHABET[r(len(_SERIAL_ALPHABET))] for _ in range(10))


def disk_total_bytes(gb):
    """Marketed GB -> total capacity a storage SKU reports (base-1000, as iOS/statfs does)."""
    return gb * 1000 * 1000 * 1000


def generate(model=None, os_version=None, seed=None, catalog=None):
    """Build one coherent profile. Deterministic in `seed`: same (model, os_version, seed) -> same output."""
    cat = catalog if catalog is not None else load_catalog()
    if seed is None:
        seed = uuid.uuid4().int & ((1 << 64) - 1)
    r = _rng(seed)

    keys = sorted(cat.keys())
    if model is None:
        model = keys[r(len(keys))]
    if model not in cat:
        raise ValueError(f"unknown model {model!r}; catalog has {keys}")
    dev = cat[model]

    builds = dev["os_builds"]
    ovkeys = sorted(builds.keys())
    if os_version is None:
        os_version = ovkeys[r(len(ovkeys))]
    if os_version not in builds:
        raise ValueError(f"{model} has no build for iOS {os_version}; has {ovkeys}")

    storage = dev["storage_gb"][r(len(dev["storage_gb"]))]

    return {
        "seed": seed,
        "model": model,
        # --- device-coherent hardware (all from the one catalog row) ---
        "product_type": dev["hw_machine"],
        "hw_machine": dev["hw_machine"],
        "hw_model": dev["hw_model"],
        "target_type": dev["target_type"],
        "hardware_platform": dev["hardware_platform"],
        "chip_id": dev["chip_id"],
        "cpu_family": dev["cpu_family"],
        "cpu_subtype": dev["cpu_subtype"],
        "marketing_name": dev["marketing_name"],
        "memsize_bytes": dev["memsize_bytes"],
        "ncpu": dev["ncpu"],
        "native_bounds": dev["native_bounds"],
        "native_scale": dev["native_scale"],
        "max_fps": dev["max_fps"],
        "biometry": dev["biometry"],
        "region": dev["region"],
        "os_version": os_version,
        "os_build": builds[os_version],
        "storage_gb": storage,
        "disk_total_bytes": disk_total_bytes(storage),
        # --- per-install unique (the values a rotation actually changes) ---
        "identifier_for_vendor": gen_idfv(r),
        "serial_number": gen_serial(r),
    }


def validate(p, catalog=None):
    """Return a list of coherence errors (empty == coherent). Catches a bad hand-entry or a
    profile whose fields don't all belong to one real device."""
    cat = catalog if catalog is not None else load_catalog()
    errs = []
    dev = cat.get(p.get("hw_machine"))
    if not dev:
        errs.append(f"unknown model {p.get('hw_machine')!r}")
        return errs
    for field, key in (
        ("hw_model", "hw_model"), ("target_type", "target_type"),
        ("hardware_platform", "hardware_platform"), ("chip_id", "chip_id"),
        ("cpu_family", "cpu_family"), ("memsize_bytes", "memsize_bytes"),
        ("ncpu", "ncpu"), ("marketing_name", "marketing_name"),
    ):
        if p.get(field) != dev[key]:
            errs.append(f"{field} {p.get(field)!r} != catalog {dev[key]!r} (hardware must match the one device)")
    if p.get("product_type") != p.get("hw_machine"):
        errs.append("product_type must equal hw_machine")
    if p.get("os_version") not in dev["os_builds"]:
        errs.append(f"os_version {p.get('os_version')!r} not a known build for {p['hw_machine']}")
    elif p.get("os_build") != dev["os_builds"][p["os_version"]]:
        errs.append(f"os_build {p.get('os_build')!r} != {dev['os_builds'][p['os_version']]!r} for iOS {p['os_version']}")
    if p.get("storage_gb") not in dev["storage_gb"]:
        errs.append(f"storage {p.get('storage_gb')} not a real SKU for {p['hw_machine']} ({dev['storage_gb']})")
    try:
        uuid.UUID(p.get("identifier_for_vendor", ""))
    except (ValueError, TypeError):
        errs.append("identifier_for_vendor is not a valid UUID")
    if len(p.get("serial_number", "")) != 10:
        errs.append("serial_number must be 10 chars (modern Apple format)")
    return errs


def _demo():
    cat = load_catalog()
    print(f"catalog: {len(cat)} devices -> {', '.join(sorted(cat))}\n")

    # 1. determinism: same seed -> identical profile
    a = generate(seed=42, catalog=cat)
    b = generate(seed=42, catalog=cat)
    assert a == b, "determinism broken: same seed produced different profiles"

    # 2. different seeds -> different IDFV (the rotating field actually rotates)
    idfvs = {generate(seed=s, model="iPhone12,8", catalog=cat)["identifier_for_vendor"] for s in range(50)}
    assert len(idfvs) == 50, "IDFV collision across 50 seeds"

    # 3. every catalog device generates a coherent profile
    for model in cat:
        p = generate(model=model, catalog=cat)
        errs = validate(p, cat)
        assert not errs, f"{model} generated an incoherent profile: {errs}"

    # 4. the validator actually catches incoherence (mutate one field)
    bad = generate(seed=7, model="iPhone12,8", catalog=cat)
    bad["hw_model"] = "D53gAP"  # an iPhone 12 board on an SE2 -> impossible device
    assert validate(bad, cat), "validator failed to catch a board/model mismatch"

    print("self-check OK: determinism, IDFV rotation, per-device coherence, validator catches mismatch\n")
    sample = generate(seed=2026, model="iPhone14,6", catalog=cat)
    print("sample profile (iPhone14,6, seed=2026):")
    print(json.dumps(sample, indent=2))


if __name__ == "__main__":
    ap = argparse.ArgumentParser(description="Coherent iOS device profile generator")
    ap.add_argument("--demo", action="store_true", help="run the self-check + print a sample")
    ap.add_argument("--model", help="force a model, e.g. iPhone14,6")
    ap.add_argument("--os", dest="os_version", help="force an iOS version, e.g. 16.2")
    ap.add_argument("--seed", type=int, help="seed for reproducible output")
    args = ap.parse_args()
    if args.demo:
        _demo()
    else:
        prof = generate(model=args.model, os_version=args.os_version, seed=args.seed)
        errs = validate(prof)
        print(json.dumps(prof, indent=2))
        if errs:
            print("\nCOHERENCE ERRORS:", errs)
            raise SystemExit(1)
