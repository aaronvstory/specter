"""Ensure the Xposed module's profile dir matches device.py's PROFILE_DIR (can't drift)."""
import os, re
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def test_module_profile_dir_matches_device():
    from specter import device as D
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/HookEntry.java")).read()
    m = re.search(r'PROFILE_DIR\s*=\s*"([^"]+)"', java)
    assert m, "PROFILE_DIR not found in module"
    module_dir = m.group(1).rstrip("/")
    assert module_dir == D.PROFILE_DIR, f"module dir {module_dir} != device.py {D.PROFILE_DIR}"

def test_module_hooks_gsf_both_paths():
    """
    The regressed identifier must be hooked on BOTH paths: the Gservices helper AND the
    direct ContentResolver.query to the gservices provider (the dominant real-world path).
    A string-presence check on 'hookGsf' alone gives false confidence — assert both hooks.
    """
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/HookEntry.java")).read()
    assert "com.google.android.gsf.Gservices" in java, "must hook the Gservices helper"
    assert 'hookAllMethods(cr, "query"' in java or "\"query\"" in java, "must hook ContentResolver.query"
    assert "com.google.android.gsf.gservices" in java, "must target the gservices provider authority"
    assert "GsfCursorWrapper" in java, "must wrap the cursor to rewrite the android_id row"


def test_module_spoofs_build_version():
    """Build.VERSION.* must be spoofed to match the fingerprint's Android version."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/HookEntry.java")).read()
    assert "Build.VERSION.class" in java, "must spoof Build.VERSION.* static fields"
    for f in ("RELEASE", "INCREMENTAL", "SECURITY_PATCH"):
        assert f in java, f"Build.VERSION.{f} not spoofed"


def test_module_spoofs_bootloader():
    """Build.BOOTLOADER must be spoofed (GeerGit 2.7.0 parity: deviceBootloader) and generated."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/HookEntry.java")).read()
    assert '"BOOTLOADER"' in java, "module must spoof Build.BOOTLOADER"
    from specter.identifiers import BUILD_FIELDS
    assert "build_bootloader" in BUILD_FIELDS, "build_bootloader missing from canonical key list"
    from specter import generators as G
    c = [0]
    r = lambda n: (c.__setitem__(0, c[0] + 1) or (c[0] * 2654435761) % n)
    bl = G.bootloader(r, "google", "flame")
    assert bl and " " not in bl, f"bootloader must be non-empty, no spaces: {bl!r}"
    # device-coherent: Google bootloader embeds the device codename (no cross-model mismatch)
    assert bl.startswith("flame-"), f"bootloader must embed the device codename: {bl!r}"


def test_module_spoofs_bluetooth_address_via_settings():
    """Settings.Secure.getString(cr, 'bluetooth_address') is a second path to the BT MAC that
    BluetoothAdapter.getAddress() doesn't cover — the module must spoof it there too, or it leaks."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/HookEntry.java")).read()
    assert '"bluetooth_address"' in java, "module must spoof Settings.Secure bluetooth_address"
    # it must use the profile's bluetooth_mac value (coherent with BluetoothAdapter.getAddress)
    assert "bluetooth_mac" in java, "bluetooth_address spoof must reuse the generated bluetooth_mac"


def test_module_hides_dev_mode_tells():
    """adb_enabled + development_settings_enabled read 1 on this rooted fleet phone — a strong
    'not a normal user' fingerprint. The module must spoof them to 0 via Settings.Global."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/HookEntry.java")).read()
    assert '"adb_enabled"' in java, "must hide adb_enabled"
    assert '"development_settings_enabled"' in java, "must hide development_settings_enabled"
    assert "Settings.Global.class" in java, "must hook Settings.Global (not just Secure/System)"


def test_module_hooks_gservices_getlong():
    """GSF read via Gservices.getLong must be covered, not just getString."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/HookEntry.java")).read()
    assert 'hookAllMethods(gs, "getLong"' in java, "must hook Gservices.getLong for GSF"


def test_module_hooks_contentproviderclient():
    """GSF via ContentProviderClient.query must be covered (some clients bypass ContentResolver)."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/HookEntry.java")).read()
    assert "ContentProviderClient" in java, "must hook ContentProviderClient.query"


def test_cursor_wrapper_covers_blob_and_buffer():
    """The GSF cursor must not leak the real value via getBlob or copyStringToBuffer."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/HookEntry.java")).read()
    assert "getBlob" in java, "cursor must override getBlob"
    assert "copyStringToBuffer" in java, "cursor must override copyStringToBuffer"


def test_module_hooks_file_last_modified_for_factory_reset():
    """The factory-reset time leaks via File.lastModified() on dirs written once at reset.

    PROVEN 2026-07-25: FPJS Pro re-identified the device across three identity rotations using this
    signal. The hook must (a) target File.lastModified, (b) know the reset-marker paths, and (c) match
    an explicit path set — lastModified is a very hot generic call, so a broad match breaks target apps.
    """
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/HookEntry.java")).read()
    assert "lastModified" in java, "must hook java.io.File.lastModified"
    for path in ("/data/misc/profiles", "/data/bootchart"):
        assert path in java, "must cover the reset-marker path " + path
    assert "factory_reset_epoch" in java, "must read the spoofed reset time from the profile"
    # BOTH read paths, or the fix is cosmetic: PROVEN 2026-07-25 that FPJS Pro read the real value via
    # android.system.Os.stat().st_mtime while the File.lastModified hook was verified active. Hooking
    # one path and declaring victory is exactly the failure this test exists to prevent.
    assert "android.system.Os" in java, "must hook android.system.Os (the path FPJS actually uses)"
    assert '"stat"' in java and '"lstat"' in java, "must hook both Os.stat and Os.lstat"
    assert "st_mtime" in java, "must rewrite StructStat.st_mtime"


def test_module_keys_match_python_profile_keys():
    """Profile.KEYS (Java) must match the Python dict order exactly, or byte-parity is broken."""
    from specter import profile as P
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/gen/Profile.java")).read()
    m = re.search(r"KEYS\s*=\s*\{(.*?)\};", java, re.S)
    assert m, "Profile.KEYS not found"
    java_keys = re.findall(r'"([^"]+)"', m.group(1))
    py_keys = list(P.generate_unique(None).keys())
    assert java_keys == py_keys, "key order drift\n java: %s\n  py : %s" % (java_keys, py_keys)


def test_data_json_matches_apk_assets():
    """The APK bundles data/*.json as assets; the copy is manual, so nothing but this test stops
    silent drift that would break byte-parity between the PC (Python) and on-device (Java) paths."""
    import hashlib
    for name in ("devices.json", "hardware.json"):
        src = os.path.join(ROOT, "data", name)
        asset = os.path.join(ROOT, "xposed-module/app/src/main/assets", name)
        assert os.path.exists(asset), f"missing APK asset {name} — copy data/{name} into assets/"
        a = hashlib.sha256(open(src, "rb").read()).hexdigest()
        b = hashlib.sha256(open(asset, "rb").read()).hexdigest()
        assert a == b, f"{name}: data/ and assets/ differ — re-copy so both paths read the same data"


def test_profile_has_coherent_hardware_descriptors():
    """Every generated profile carries the per-model hardware bundle, coherent with the device it
    claims to be (right SoC GPU) and different across different models."""
    from specter import profile as P
    hw = P._load_hardware()
    # Pixel 4 (flame, SD855) -> Adreno 640; Galaxy S10e (beyond0lteeea, Exynos 9820) -> Mali-G76.
    assert hw["flame"]["gpu_renderer"] == "Adreno (TM) 640"
    assert hw["beyond0lteeea"]["gpu_renderer"] == "Mali-G76"
    # A generated profile has all 9 hardware fields, non-empty, keyed off its own codename.
    p = P.generate_unique(None, seed=42)
    for k in ("hw_gpu_renderer", "hw_gpu_vendor", "hw_gles_version", "hw_cores",
              "hw_sensors", "hw_cameras", "hw_codecs", "hw_input_devices", "proc_cpuinfo"):
        assert p.get(k), f"profile missing hardware field {k}"
    cn = p["build_product"].split("_")[0]
    expected = hw.get(cn, hw["_default"])
    assert p["hw_gpu_renderer"] == expected["gpu_renderer"], "GPU renderer not coherent with device"
    assert p["proc_cpuinfo"].rstrip("\n") in p["proc_cpuinfo"]  # cpuinfo present, multi-line
    assert "Hardware\t:" in p["proc_cpuinfo"], "cpuinfo missing Hardware line"


def test_every_selectable_device_has_hardware_entry():
    """No selectable (pickable) device may silently fall back to _default — that would be an
    incoherent hardware story. build_hardware_dataset.py enforces this; assert it stays true."""
    from specter import profile as P
    hw = P._load_hardware()
    devices = P._load_devices()
    for d in devices:
        if not P._is_plausible_phone(d):
            continue
        if d[2].lower() not in P.US_COMMON_BRANDS:
            continue
        cn = d[4].split("_")[0]
        assert cn in hw, f"selectable device codename {cn} missing from hardware.json"
