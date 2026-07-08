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


def test_no_collisions_in_2000_generations():
    seen = {k: set() for k in UNIQUE_KEYS}
    for _ in range(2000):
        p = P.generate_unique(None)
        for k in UNIQUE_KEYS:
            assert p[k] not in seen[k], f"REUSE on {k}: {p[k]}"
            seen[k].add(p[k])


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
