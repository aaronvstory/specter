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

def test_module_hooks_gsf():
    """The regressed identifier must be hooked in the module."""
    java = open(os.path.join(ROOT, "xposed-module/app/src/main/java/com/fleet/idrotate/HookEntry.java")).read()
    assert "hookGsf" in java and "gsf_id" in java, "module must hook GSF (the ban surface)"
