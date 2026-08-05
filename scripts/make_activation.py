#!/usr/bin/env python3
"""Operator-side activation-code generator for Specter (offline, no server).

The app verifies a code against an EC P-256 PUBLIC key compiled into it; this script holds the matching
PRIVATE key (outside the PUBLIC repo) and signs codes. Only the public key ships, so a decompiled APK
cannot forge codes. See docs/DECISIONS.md.

Usage
-----
  # one-time: make the operator keypair, then paste the printed line into ActivationVerifier
  uv run --with cryptography python scripts/make_activation.py setup

  # issue a code after someone pays: bind it to their device hash for a duration
  uv run --with cryptography python scripts/make_activation.py <device_hash> <1d|1w|1m>

The customer reads their device hash off the app's Activation screen and sends it to you; you run the
second form and send back the code. Nothing is online.

Files (both OUTSIDE this repo, since it is public):
  ~/.specter-activation-key.pem   the EC P-256 private key — NEVER commit, NEVER share
  ~/.specter-activations.jsonl    an append-only ledger of what was issued (device, tier, expiry, key_id)
"""
from __future__ import annotations

import base64
import json
import os
import re
import secrets
import sys
import time
from pathlib import Path

PREFIX = "SPECTER-ACT-1"
KEY_PATH = Path.home() / ".specter-activation-key.pem"
LEDGER_PATH = Path.home() / ".specter-activations.jsonl"
DURATIONS = {"1d": 86400, "1w": 7 * 86400, "1m": 30 * 86400}


def _need_crypto():
    try:
        from cryptography.hazmat.primitives.asymmetric import ec  # noqa: F401
        return None
    except ModuleNotFoundError:
        sys.exit("cryptography not installed — run via:  uv run --with cryptography python "
                 "scripts/make_activation.py ...")


def _b64url(b: bytes) -> str:
    return base64.urlsafe_b64encode(b).decode().rstrip("=")


def _load_private():
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    if not KEY_PATH.exists():
        sys.exit(f"no operator key at {KEY_PATH} — run:  python scripts/make_activation.py setup")
    priv = serialization.load_pem_private_key(KEY_PATH.read_bytes(), password=None)
    if not isinstance(priv, ec.EllipticCurvePrivateKey):
        sys.exit(f"{KEY_PATH} is not an EC private key — delete it and re-run setup")
    return priv


def _public_key_b64(priv) -> str:
    from cryptography.hazmat.primitives import serialization
    der = priv.public_key().public_bytes(
        serialization.Encoding.DER,
        serialization.PublicFormat.SubjectPublicKeyInfo,
    )
    return base64.b64encode(der).decode()


def cmd_setup() -> None:
    _need_crypto()
    from cryptography.hazmat.primitives import serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    if KEY_PATH.exists():
        priv = _load_private()
        print(f"operator key already exists at {KEY_PATH} (reusing it)")
    else:
        priv = ec.generate_private_key(ec.SECP256R1())
        KEY_PATH.write_bytes(priv.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ))
        try:
            os.chmod(KEY_PATH, 0o600)
        except OSError:
            pass
        print(f"wrote new operator private key -> {KEY_PATH}  (chmod 600, keep it secret)")
    pub = _public_key_b64(priv)
    print("\nPaste this into ActivationVerifier.PUBLIC_KEY_B64:\n")
    print(f'    public static final String PUBLIC_KEY_B64 =\n            "{pub}";\n')


def canonical(device_hash: str, expiry: int, tier: str, key_id: str) -> str:
    """The exact ASCII string that gets signed. Java builds the identical string — keep them in lockstep."""
    return f"{PREFIX}|{device_hash}|{expiry}|{tier}|{key_id}"


def cmd_make(device_hash: str, tier: str) -> None:
    # Validate BEFORE importing crypto so a typo fails instantly (and without needing the lib installed).
    device_hash = device_hash.strip().lower()
    if not re.fullmatch(r"[0-9a-f]{16}", device_hash):
        sys.exit("device_hash must be 16 hex chars (read it off the app's Activation screen)")
    if tier not in DURATIONS:
        sys.exit(f"duration must be one of {', '.join(DURATIONS)}")

    _need_crypto()
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec

    priv = _load_private()
    key_id = secrets.token_hex(4)
    expiry = int(time.time()) + DURATIONS[tier]
    payload = canonical(device_hash, expiry, tier, key_id).encode()
    sig = priv.sign(payload, ec.ECDSA(hashes.SHA256()))
    code = f"{_b64url(payload)}.{_b64url(sig)}"

    exp_human = time.strftime("%Y-%m-%d %H:%M", time.localtime(expiry))
    with LEDGER_PATH.open("a", encoding="utf-8") as fh:
        fh.write(json.dumps({"key_id": key_id, "device_hash": device_hash, "tier": tier,
                             "expiry": expiry, "expiry_human": exp_human,
                             "issued": int(time.time())}) + "\n")

    print(f"device : {device_hash}")
    print(f"tier   : {tier}   (expires {exp_human} local)")
    print(f"key_id : {key_id}   (logged to {LEDGER_PATH})")
    print("\nsend the customer this code:\n")
    print(code)


def main(argv: list[str]) -> None:
    if len(argv) == 1 and argv[0] == "setup":
        cmd_setup()
    elif len(argv) == 2:
        cmd_make(argv[0], argv[1])
    else:
        sys.exit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
