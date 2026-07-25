"""
Run the pure-JVM Java logic tests as part of the Python suite when a JDK is available.

Invokes javac/java directly (no bash) so it's portable across shells, and runs the SAME four test
mains as run-jvm-tests.sh. Skips cleanly when no JDK is present.

This file was dead for a while: it pointed at the pre-rename package (com/fleet/idrotate) and only
looked for a JDK under xposed-module/.jdk/, so it silently skipped on every run instead of failing.
A skipped test guards nothing — hence the JAVA_HOME/PATH lookup and the path assertions below.
"""
import glob
import os
import shutil
import subprocess

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD = os.path.join(ROOT, "xposed-module")
MAIN = os.path.join(MOD, "app", "src", "main", "java", "com", "specter", "module")
TEST = os.path.join(MOD, "app", "src", "test", "java", "com", "specter", "module")

SOURCES = [
    os.path.join(MAIN, "SpoofLogic.java"),
    os.path.join(MAIN, "gen", "Generators.java"),
    os.path.join(MAIN, "gen", "Country.java"),
    os.path.join(MAIN, "gen", "Profile.java"),
    os.path.join(MAIN, "gen", "UsedStore.java"),
    os.path.join(MAIN, "gen", "RootWriter.java"),
    os.path.join(TEST, "SpoofLogicTest.java"),
    os.path.join(TEST, "gen", "GeneratorsTest.java"),
    os.path.join(TEST, "gen", "ProfileTest.java"),
    os.path.join(TEST, "gen", "RootWriterTest.java"),
]
MAINS = [
    "com.specter.module.SpoofLogicTest",
    "com.specter.module.gen.GeneratorsTest",
    "com.specter.module.gen.ProfileTest",
    "com.specter.module.gen.RootWriterTest",
]


def _jdk_bin(name):
    """javac/java from JAVA_HOME, then a vendored .jdk, then PATH."""
    home = os.environ.get("JAVA_HOME")
    bases = ([home] if home else []) + sorted(glob.glob(os.path.join(MOD, ".jdk", "jdk-*")))
    for base in bases:
        for cand in (os.path.join(base, "bin", name), os.path.join(base, "bin", name + ".exe")):
            if os.path.exists(cand):
                return cand
    return shutil.which(name)


@pytest.mark.skipif(_jdk_bin("javac") is None, reason="no JDK (set JAVA_HOME)")
def test_jvm_logic_suite_passes(tmp_path):
    javac, java = _jdk_bin("javac"), _jdk_bin("java")
    missing = [s for s in SOURCES if not os.path.exists(s)]
    assert not missing, f"Java sources moved or renamed: {missing}"

    out = str(tmp_path / "jvmout")
    os.makedirs(out, exist_ok=True)
    c = subprocess.run([javac, "-d", out, *SOURCES], capture_output=True, text=True)
    assert c.returncode == 0, f"javac failed:\n{c.stdout}\n{c.stderr}"

    for cls in MAINS:
        r = subprocess.run([java, "-cp", out, cls], capture_output=True, text=True)
        assert r.returncode == 0 and "0 failed" in r.stdout, \
            f"{cls} failed:\n{r.stdout}\n{r.stderr}"
