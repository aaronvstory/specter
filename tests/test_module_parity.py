"""Ensure the Xposed module's profile dir matches device.py's PROFILE_DIR (can't drift)."""
import os, re
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

def test_module_profile_dir_matches_device():
    from specter import device as D
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/fleet/idrotate/HookEntry.java")).read()
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
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/fleet/idrotate/HookEntry.java")).read()
    assert "com.google.android.gsf.Gservices" in java, "must hook the Gservices helper"
    assert 'hookAllMethods(cr, "query"' in java or "\"query\"" in java, "must hook ContentResolver.query"
    assert "com.google.android.gsf.gservices" in java, "must target the gservices provider authority"
    assert "GsfCursorWrapper" in java, "must wrap the cursor to rewrite the android_id row"


def test_module_spoofs_build_version():
    """Build.VERSION.* must be spoofed to match the fingerprint's Android version."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/fleet/idrotate/HookEntry.java")).read()
    assert "Build.VERSION.class" in java, "must spoof Build.VERSION.* static fields"
    for f in ("RELEASE", "INCREMENTAL", "SECURITY_PATCH"):
        assert f in java, f"Build.VERSION.{f} not spoofed"


def test_module_spoofs_bootloader():
    """Build.BOOTLOADER must be spoofed (GeerGit 2.7.0 parity: deviceBootloader) and generated."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/fleet/idrotate/HookEntry.java")).read()
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
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/fleet/idrotate/HookEntry.java")).read()
    assert '"bluetooth_address"' in java, "module must spoof Settings.Secure bluetooth_address"
    # it must use the profile's bluetooth_mac value (coherent with BluetoothAdapter.getAddress)
    assert "bluetooth_mac" in java, "bluetooth_address spoof must reuse the generated bluetooth_mac"


def test_module_hides_dev_mode_tells():
    """adb_enabled + development_settings_enabled read 1 on this rooted fleet phone — a strong
    'not a normal user' fingerprint. The module must spoof them to 0 via Settings.Global."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/fleet/idrotate/HookEntry.java")).read()
    assert '"adb_enabled"' in java, "must hide adb_enabled"
    assert '"development_settings_enabled"' in java, "must hide development_settings_enabled"
    assert "Settings.Global.class" in java, "must hook Settings.Global (not just Secure/System)"


def test_module_hooks_gservices_getlong():
    """GSF read via Gservices.getLong must be covered, not just getString."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/fleet/idrotate/HookEntry.java")).read()
    assert 'hookAllMethods(gs, "getLong"' in java, "must hook Gservices.getLong for GSF"


def test_module_hooks_contentproviderclient():
    """GSF via ContentProviderClient.query must be covered (some clients bypass ContentResolver)."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/fleet/idrotate/HookEntry.java")).read()
    assert "ContentProviderClient" in java, "must hook ContentProviderClient.query"


def test_cursor_wrapper_covers_blob_and_buffer():
    """The GSF cursor must not leak the real value via getBlob or copyStringToBuffer."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/fleet/idrotate/HookEntry.java")).read()
    assert "getBlob" in java, "cursor must override getBlob"
    assert "copyStringToBuffer" in java, "cursor must override copyStringToBuffer"
