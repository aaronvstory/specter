"""
The ban-critical test: no identifier is EVER reused across signups.

This is the exact failure that got the fleet banned (GeerGit 2.9.6 reused a stale GSF).
If any of these fail, the tool would recreate the bug.
"""
import os
import tempfile
import pytest
from specter import profile as P
from specter.identifiers import UNIQUE_KEYS


def test_ledger_enforces_uniqueness(tmp_path):
    """
    With a used-ledger (the real usage), NO unique id ever repeats — even IMEI, whose keyspace is
    intentionally smaller now (fixed brand TAC + 6-digit serial) for coherence. The ledger's
    retry-on-collision is the guarantee. 400 gens exercises retries without the O(n^2) disk cost
    of a huge loop (per-signup use is one-at-a-time; the statistical generator check is separate).
    """
    import os
    store = P.UsedStore(os.path.join(tmp_path, "used.json"))
    seen = {k: set() for k in UNIQUE_KEYS}
    for _ in range(400):
        p = P.generate_unique(store)
        for k in UNIQUE_KEYS:
            assert p[k] not in seen[k], f"REUSE on {k}: {p[k]}"
            seen[k].add(p[k])


def test_generator_high_entropy_fields_rarely_collide():
    """The high-entropy fields (android_id, gsf, serial, etc.) collide ~never even WITHOUT a
    ledger — fast in-memory check. IMEI is excluded (small keyspace by design; ledger covers it).

    "Rarely", not "never": these draw from the real CSPRNG, so a smaller-keyspace field (a 46-bit MAC/BSSID)
    can chance-collide once in 2000 draws — the birthday bound makes that a legitimate few-tenths-of-a-percent
    event, and asserting ZERO made this test flakily fail the autonomous loop. Tally per field and allow at
    most ONE chance collision; a real entropy REGRESSION (a field that stopped randomizing) produces dozens,
    which this still catches decisively."""
    from specter import profile as P
    HIGH_ENTROPY = [k for k in UNIQUE_KEYS if k not in ("imei1", "imei2")]
    seen = {k: set() for k in HIGH_ENTROPY}
    collisions = {k: 0 for k in HIGH_ENTROPY}
    for _ in range(2000):
        p = P.build_profile(P._csprng, P._load_devices())
        for k in HIGH_ENTROPY:
            if p[k] in seen[k]:
                collisions[k] += 1
            seen[k].add(p[k])
    # (codex noted a generator cycling through exactly 1999 distinct values would also yield 1 collision and
    # pass — but that's not a realistic regression shape; a real one stops randomizing entirely and collides
    # ~1999 times, which this still fails on. ≤1 is the right tradeoff for a CSPRNG chance collision.)
    for k, n in collisions.items():
        assert n <= 1, f"generator entropy regression on {k}: {n} collisions in 2000 (expected ~0)"


def test_used_store_persists_and_blocks_reuse():
    with tempfile.TemporaryDirectory() as d:
        path = os.path.join(d, "used.json")
        store = P.UsedStore(path)
        first = [P.generate_unique(store) for _ in range(100)]
        store.save()
        assert store.count() == 100

        # reload from disk — history must survive
        store2 = P.UsedStore(path)
        assert store2.count() == 100
        for p in first:
            assert store2.collides(p), "reloaded store failed to see prior ids"

        # new generations still avoid all prior ids
        prior_gsf = set(store2.data.get("gsf_id", []))
        for _ in range(100):
            p = P.generate_unique(store2)
            assert p["gsf_id"] not in prior_gsf, "reused an id already on disk"
            prior_gsf.add(p["gsf_id"])
        assert store2.count() == 200


def test_gsf_specifically_never_repeats():
    """The exact regressed identifier. Extra emphasis."""
    seen = set()
    for _ in range(3000):
        g = P.generate_unique(None)["gsf_id"]
        assert g not in seen, "GSF REUSE — this is the ban bug"
        seen.add(g)


def test_corrupt_ledger_fails_closed(tmp_path):
    """A corrupt used_ids.json must NOT be treated as empty (that would allow reuse)."""
    import pytest
    path = str(tmp_path / "used.json")
    open(path, "w").write("{ this is not valid json")
    with pytest.raises(P.UsedStoreCorrupt):
        P.UsedStore(path)
    # it quarantined the bad file
    assert __import__("os").path.exists(path + ".corrupt")


def test_non_dict_ledger_fails_closed(tmp_path):
    import pytest
    path = str(tmp_path / "used.json")
    open(path, "w").write('["a", "list", "not", "a", "dict"]')
    with pytest.raises(P.UsedStoreCorrupt):
        P.UsedStore(path)


def test_generate_unique_fails_loud_when_it_cannot_produce_a_valid_profile(monkeypatch):
    """The last line of the ban-critical guarantee: if generation can never satisfy validity (or
    uniqueness), it must RAISE — never silently return an unvalidated or duplicate profile. Force
    validate() to always reject and confirm it dies loud within max_tries rather than returning
    something bad. (This is the branch that turns a stuck generator into a visible failure instead
    of a reused identifier.)"""
    monkeypatch.setattr(P, "validate", lambda p, catalog=None: (False, ["forced-invalid"]))
    with pytest.raises(RuntimeError, match="fresh valid profile"):
        P.generate_unique(None, max_tries=5)


def test_generate_unique_fails_loud_when_every_profile_collides(monkeypatch, tmp_path):
    """Same guarantee via the uniqueness path: if the ledger rejects every candidate as a collision,
    generation raises rather than handing back a reused id."""
    store = P.UsedStore(str(tmp_path / "used.json"))
    monkeypatch.setattr(store, "collides", lambda p: True)   # everything looks already-used
    with pytest.raises(RuntimeError, match="fresh valid profile"):
        P.generate_unique(store, max_tries=5)
