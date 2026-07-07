"""
Run the pure-JVM Java logic tests as part of the suite when a JDK is available.

Invokes javac/java directly (no bash) so it's portable across shells. Compiles SpoofLogic +
its test and runs the tiny assert-based main. Skips cleanly when no JDK is present.
"""
import glob
import os
import subprocess

import pytest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MOD = os.path.join(ROOT, "xposed-module")
SRC = os.path.join(MOD, "app", "src", "main", "java", "com", "fleet", "idrotate", "SpoofLogic.java")
TST = os.path.join(MOD, "app", "src", "test", "java", "com", "fleet", "idrotate", "SpoofLogicTest.java")


def _jdk_bin(name):
    for base in glob.glob(os.path.join(MOD, ".jdk", "jdk-*")):
        for cand in (os.path.join(base, "bin", name), os.path.join(base, "bin", name + ".exe")):
            if os.path.exists(cand):
                return cand
    return None


@pytest.mark.skipif(_jdk_bin("javac") is None or not os.path.exists(SRC),
                    reason="JDK or Java sources not present")
def test_spooflogic_jvm_passes(tmp_path):
    javac, java = _jdk_bin("javac"), _jdk_bin("java")
    out = str(tmp_path / "jvmout")
    os.makedirs(out, exist_ok=True)
    c = subprocess.run([javac, "-d", out, SRC, TST], capture_output=True, text=True)
    assert c.returncode == 0, f"javac failed:\n{c.stdout}\n{c.stderr}"
    r = subprocess.run([java, "-cp", out, "com.fleet.idrotate.SpoofLogicTest"],
                       capture_output=True, text=True)
    assert "0 failed" in r.stdout, f"JVM tests failed:\n{r.stdout}\n{r.stderr}"
