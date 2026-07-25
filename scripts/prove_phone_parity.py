#!/usr/bin/env python
"""Prove Java<->Python byte-parity for phone_us after the real-area-code change.

Compiles a tiny Java dumper that prints phoneUs(seeded(s)) for N seeds using the SAME seeded RNG as
ProfileTest, and compares line-for-line against the Python phone_us with the identical seeded RNG.
A single differing line means the two generators diverged (byte-parity broken).

Run: .venv/Scripts/python.exe scripts/prove_phone_parity.py
"""
import hashlib
import os
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
N = 500


def py_seeded(seed):
    state = {"h": hashlib.sha256(str(seed).encode()).digest(), "i": 0}
    def r(n):
        state["i"] += 1
        d = hashlib.sha256(state["h"] + state["i"].to_bytes(8, "big")).digest()
        return int.from_bytes(d[:8], "big") % n
    return r


def python_lines():
    sys.path.insert(0, ROOT)
    from specter import generators as G
    out = []
    for s in range(N):
        out.append("%d %s" % (s, G.phone_us(py_seeded(s))))
    return out


JAVA_DUMPER = r'''
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import com.specter.module.gen.Generators;

public class PhoneParityDump {
    static Generators.Rng seeded(long seed) {
        try {
            final byte[] h = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(seed).getBytes(StandardCharsets.UTF_8));
            final long[] i = {0};
            return new Generators.Rng() {
                long draw8() { try {
                    i[0]++; MessageDigest md = MessageDigest.getInstance("SHA-256"); md.update(h);
                    byte[] cnt = new byte[8]; long v = i[0];
                    for (int k = 7; k >= 0; k--) { cnt[k] = (byte)(v & 0xFF); v >>= 8; }
                    md.update(cnt); byte[] d = md.digest();
                    long acc = 0; for (int k = 0; k < 8; k++) acc = (acc << 8) | (d[k] & 0xFF); return acc;
                } catch (Exception e) { throw new RuntimeException(e); } }
                public int next(int n) { return (int) Long.remainderUnsigned(draw8(), n); }
                public long nextLong(long n) { return Long.remainderUnsigned(draw8(), n); }
            };
        } catch (Exception e) { throw new RuntimeException(e); }
    }
    public static void main(String[] args) {
        int N = Integer.parseInt(args[0]);
        StringBuilder sb = new StringBuilder();
        for (int s = 0; s < N; s++) sb.append(s).append(' ').append(Generators.phoneUs(seeded(s))).append('\n');
        System.out.print(sb);
    }
}
'''


def java_lines():
    jdk = os.environ.get("JAVA_HOME")
    if not jdk:
        # try the vendored scoop JDK
        cand = os.path.expanduser("~/scoop/apps/temurin17-jdk/current")
        if os.path.isdir(cand):
            jdk = cand
    javac = os.path.join(jdk, "bin", "javac.exe") if jdk else "javac"
    java = os.path.join(jdk, "bin", "java.exe") if jdk else "java"
    tmp = tempfile.mkdtemp()
    with open(os.path.join(tmp, "PhoneParityDump.java"), "w") as f:
        f.write(JAVA_DUMPER)
    gen = os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/gen/Generators.java")
    country = os.path.join(ROOT, "xposed-module/app/src/main/java/com/specter/module/gen/Country.java")
    subprocess.run([javac, "-d", tmp, gen, country, os.path.join(tmp, "PhoneParityDump.java")], check=True)
    out = subprocess.run([java, "-cp", tmp, "PhoneParityDump", str(N)],
                         check=True, capture_output=True, text=True).stdout
    return out.strip("\n").split("\n")


def main():
    py = python_lines()
    jv = java_lines()
    if py == jv:
        print("PARITY OK: %d seeds, Java == Python for phone_us" % N)
        return 0
    # find first divergence
    for i, (a, b) in enumerate(zip(py, jv)):
        if a != b:
            print("DIVERGE at line %d:\n  py:   %s\n  java: %s" % (i, a, b))
            return 1
    print("length mismatch: py=%d java=%d" % (len(py), len(jv)))
    return 1


if __name__ == "__main__":
    sys.exit(main())
