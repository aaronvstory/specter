"""Tests for the operator-side activation generator (scripts/make_activation.py).

The signed-message FORMAT is pinned to the same literal the JVM test (ActivationVerifierTest) pins, so the
Python generator and the Java verifier stay byte-parallel. Crypto round-trip is gated on `cryptography`
being installed, exactly like the JVM test is gated on a JDK — CI has neither and skips cleanly."""

import base64
import importlib.util
import time
from pathlib import Path

import pytest

_SPEC = importlib.util.spec_from_file_location(
    "make_activation", Path(__file__).resolve().parents[1] / "scripts" / "make_activation.py")
assert _SPEC and _SPEC.loader
mk = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(mk)

_HAVE_CRYPTO = importlib.util.find_spec("cryptography") is not None
_needs_crypto = pytest.mark.skipif(not _HAVE_CRYPTO, reason="cryptography not installed (CI)")
# crypto imported LOCALLY inside each gated test so this module imports fine on CI (no cryptography there).


def test_canonical_format_is_pinned():
    # The exact string that gets signed — the JVM test pins the identical literal. Changing either side
    # without the other silently breaks every issued code.
    assert (mk.canonical("00112233aabbccdd", 1893456000, "1w", "deadbeef")
            == "SPECTER-ACT-1|00112233aabbccdd|1893456000|1w|deadbeef")


def test_durations_are_1d_1w_1m():
    assert set(mk.DURATIONS) == {"1d", "1w", "1m"}
    assert mk.DURATIONS["1d"] == 86400
    assert mk.DURATIONS["1w"] == 7 * 86400
    assert mk.DURATIONS["1m"] == 30 * 86400


def test_bad_device_hash_rejected(monkeypatch):
    monkeypatch.setattr(mk, "_need_crypto", lambda: None)
    monkeypatch.setattr(mk, "_load_private", lambda: (_ for _ in ()).throw(AssertionError("should not sign")))
    with pytest.raises(SystemExit):
        mk.cmd_make("NOTHEX", "1w")          # not 16 hex chars
    with pytest.raises(SystemExit):
        mk.cmd_make("00112233aabbccdd", "1y")  # unknown duration


def _b64url_decode(s: str) -> bytes:
    return base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))


@_needs_crypto
def test_sign_then_verify_round_trip(tmp_path, monkeypatch):
    # A code the generator produces must verify against the operator PUBLIC key with the SHA256withECDSA
    # scheme the app uses — this is the interop contract with ActivationVerifier.verify.
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    key_path = tmp_path / "key.pem"
    ledger = tmp_path / "ledger.jsonl"
    monkeypatch.setattr(mk, "KEY_PATH", key_path)
    monkeypatch.setattr(mk, "LEDGER_PATH", ledger)

    priv = ec.generate_private_key(ec.SECP256R1())
    key_path.write_bytes(priv.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))

    printed = {}
    monkeypatch.setattr("builtins.print", lambda *a, **k: printed.setdefault("lines", []).append(" ".join(map(str, a))))
    mk.cmd_make("00112233aabbccdd", "1w")
    code = printed["lines"][-1].strip()

    payload_b64, sig_b64 = code.split(".")
    payload = _b64url_decode(payload_b64)
    sig = _b64url_decode(sig_b64)

    # Verifies against the public key (the app's exact check).
    priv.public_key().verify(sig, payload, ec.ECDSA(hashes.SHA256()))

    fields = payload.decode().split("|")
    assert fields[0] == "SPECTER-ACT-1"
    assert fields[1] == "00112233aabbccdd"
    assert fields[3] == "1w"
    assert int(fields[2]) > time.time()          # expiry in the future
    assert int(fields[2]) <= time.time() + mk.DURATIONS["1w"] + 5

    # A tampered payload must NOT verify with the original signature.
    with pytest.raises(Exception):
        priv.public_key().verify(sig, payload + b"x", ec.ECDSA(hashes.SHA256()))

    # The issue was logged to the ledger.
    assert ledger.exists() and "00112233aabbccdd" in ledger.read_text()


@_needs_crypto
def test_public_key_b64_is_x509_der(tmp_path, monkeypatch):
    # setup prints an X.509 SubjectPublicKeyInfo base64 — the exact form ActivationVerifier decodes.
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    key_path = tmp_path / "key.pem"
    monkeypatch.setattr(mk, "KEY_PATH", key_path)
    priv = ec.generate_private_key(ec.SECP256R1())
    key_path.write_bytes(priv.private_bytes(
        serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
    b64 = mk._public_key_b64(priv)
    der = base64.b64decode(b64)
    # round-trips back to a public key object
    serialization.load_der_public_key(der)
