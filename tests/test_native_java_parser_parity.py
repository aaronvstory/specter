r"""Native (C++) <-> Java flat-JSON parser escape-handling parity.

The applied profile at /data/local/tmp/specter/<pkg>.json is read by TWO parsers that MUST decode it
identically, or a native prop read disagrees with the Java hook on the same profile:
  * Java  SpoofLogic.readJsonString  (the Xposed layer)
  * C++   parse_flat_json in spoof_logic.h (the Zygisk native layer)
The v0.22.10 bug was exactly this class — the native parser didn't unescape `\/` / `\uXXXX` that the
Java one did, so native reads served backslash-mangled values. The native test runs on-device only (no
host C++ toolchain here), so we can't diff the two at runtime; instead we pin, at the source, that both
decode the same escape set — the same way test_ipcheck pins the Dnsbl.java <-> ipcheck.py tables.
"""
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
NATIVE = (ROOT / "xposed-module" / "zygisk" / "src" / "main" / "cpp" / "spoof_logic.h").read_text("utf-8")
JAVA = (ROOT / "xposed-module" / "app" / "src" / "main" / "java" / "com" / "specter" / "module"
        / "SpoofLogic.java").read_text("utf-8")

# The single-char escapes each parser gives a dedicated `case 'X':` to. (Both ALSO fall through to a
# default that emits the literal char, which is how \/ \\ \" are handled — covered separately below.)
_CASE = re.compile(r"case '(\\?.)':")


def _native_parse_body():
    m = re.search(r"parse_flat_json\(const std::string[^{]*\{(.*?)\n\}", NATIVE, re.S)
    assert m, "parse_flat_json not found in spoof_logic.h"
    return m.group(1)


def _java_readstring_body():
    m = re.search(r"static int readJsonString\([^{]*\{(.*?)\n    \}", JAVA, re.S)
    assert m, "readJsonString not found in SpoofLogic.java"
    return m.group(1)


def _escape_letters(body):
    # The letters that appear as `case 'x':` inside the escape switch — normalise the Java `'\\'` to '\'.
    out = set()
    for c in _CASE.findall(body):
        out.add("\\" if c == "\\\\" else c)
    return out


def test_both_parsers_decode_the_same_core_escape_set():
    n = _escape_letters(_native_parse_body())
    j = _escape_letters(_java_readstring_body())
    core = {"n", "r", "t", "b", "f", "u"}
    # The C-style escapes + \u must be handled by BOTH; a parser missing any of these is the v0.22.10
    # divergence (a native read serving a value the Java layer decoded differently).
    assert core <= n, f"native parser missing escape cases: {core - n}"
    assert core <= j, f"java parser missing escape cases: {core - j}"


def test_both_parsers_decode_unicode_escapes():
    # \uXXXX must be decoded on both sides (org.json emits it; a profile with a non-ASCII value would
    # otherwise read differently native vs Java). Java calls Integer.parseInt(...,16); native has a
    # dedicated UTF-8 decoder.
    assert "append_utf8_escape" in NATIVE and "case 'u'" in NATIVE
    assert 'Integer.parseInt(s.substring(i, i + 4), 16)' in JAVA


def test_both_parsers_emit_the_literal_for_an_unknown_escape():
    # \/ \\ \" are handled by the DEFAULT arm on both sides (emit the escaped char verbatim) — this is
    # what unescapes org.json's `\/`. Both must have that fallback, or one keeps the backslash.
    assert re.search(r"default:\s*val \+= e", _native_parse_body())
    assert re.search(r"default:\s*sb\.append\(e\)", _java_readstring_body())


def test_both_parsers_drop_a_truncated_value_rather_than_store_a_partial():
    # A value with no closing quote must be dropped by BOTH (not stored half-parsed), or an imported /
    # hand-edited profile makes the two disagree. Native: `if (!closed) break;`; Java: returns -1.
    assert "if (!closed) break;" in NATIVE
    assert "return -1;" in _java_readstring_body()
