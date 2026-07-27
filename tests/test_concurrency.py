"""
Concurrency regression tests for UsedStore — the exact race the code-reviewer found.

Before the fix, two overlapping processes' save() calls clobbered each other, erasing
issued ids from the ban-critical history so a future run could legally reuse them.
"""
import json
import os
import tempfile
import threading
from specter import profile as P


def test_interleaved_stores_do_not_erase_each_others_ids(tmp_path):
    """The reviewer's scenario: B loads before A records; B must not erase A's id."""
    path = str(tmp_path / "used.json")

    storeA = P.UsedStore(path)
    storeB = P.UsedStore(path)  # both start empty

    pA = P.generate_unique(storeA)   # A records + persists atomically
    pB = P.generate_unique(storeB)   # B refreshes-from-disk, so it SEES pA and persists both

    final = P.UsedStore(path)
    # both ids must survive on disk
    assert pA["gsf_id"] in set(final.data["gsf_id"]), "A's id was erased (the race bug)"
    assert pB["gsf_id"] in set(final.data["gsf_id"]), "B's id missing"
    assert final.count() == 2


def test_threaded_generation_never_reuses(tmp_path):
    """Many threads generating concurrently must never produce a duplicate unique id."""
    path = str(tmp_path / "used.json")
    results = []
    lock = threading.Lock()

    def worker():
        store = P.UsedStore(path)
        p = P.generate_unique(store)
        with lock:
            results.append(p["gsf_id"])

    threads = [threading.Thread(target=worker) for _ in range(20)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()

    assert len(results) == 20
    assert len(set(results)) == 20, "duplicate gsf_id generated under concurrency"
    # and disk agrees
    final = P.UsedStore(path)
    assert final.count() == 20


def test_transient_read_error_does_not_quarantine_the_ledger(tmp_path, monkeypatch):
    """
    Regression: on Windows a reader's open() can hit a TRANSIENT share violation while a concurrent
    record() does os.replace(tmp, path). The old _read_disk treated ANY open/read error as CORRUPTION
    and quarantined the ledger (moving used.json -> used.json.corrupt), destroying the ban-critical
    no-reuse history. A transient PermissionError must retry, NOT quarantine. Only real JSON corruption
    quarantines (covered by the fail-closed behavior).
    """
    path = str(tmp_path / "used.json")
    store = P.UsedStore(path)
    p0 = P.generate_unique(store)          # ledger now has one real id on disk
    assert P.UsedStore(path).count() == 1

    real_open = open
    calls = {"n": 0}

    def flaky_open(*a, **k):
        # Fail the FIRST open of the ledger with a transient PermissionError, then succeed.
        if a and str(a[0]) == path and calls["n"] == 0:
            calls["n"] += 1
            raise PermissionError("simulated Windows share violation during os.replace")
        return real_open(*a, **k)

    monkeypatch.setattr("builtins.open", flaky_open)
    # This construction's _read_disk hits the transient error first, retries, then reads the real ledger.
    reloaded = P.UsedStore(path)
    assert reloaded.count() == 1, "transient read error lost the ledger content"
    assert p0["gsf_id"] in set(reloaded.data["gsf_id"]), "the issued id must survive a transient read"
    monkeypatch.undo()
    # The ledger was NOT quarantined by the transient error.
    assert not os.path.exists(path + ".corrupt"), "a transient read error must not quarantine the ledger"
    assert os.path.exists(path), "the ledger file must still be in place"


def test_atomic_write_leaves_no_partial_on_disk(tmp_path):
    path = str(tmp_path / "used.json")
    store = P.UsedStore(path)
    for _ in range(50):
        P.generate_unique(store)
    # file must be valid json (never a truncated/partial write)
    data = json.load(open(path))
    assert len(data["gsf_id"]) == 50
    # no leftover temp files
    leftovers = [f for f in os.listdir(tmp_path) if f.endswith(".tmp")]
    assert not leftovers, f"temp files left behind: {leftovers}"


def test_tiny_keyspace_never_hands_out_duplicate(tmp_path, monkeypatch):
    """
    Reviewer's reproduction: force the GSF keyspace tiny so concurrent threads WILL draw the
    same value, and assert generate_unique() never RETURNS a duplicate (retries on concurrent
    claim). Before the record()->bool fix, this returned duplicates while the disk stayed clean.
    """
    import threading
    from specter import generators as G

    # shrink gsf to 8 possible values so collisions are frequent
    monkeypatch.setattr(G, "gsf", lambda r: str(1_000_000_000_000_000_000 + r(8)))

    path = str(tmp_path / "used.json")
    results = []
    lock = threading.Lock()
    stop = {"full": False}

    def worker():
        # each thread grabs a few until keyspace-ish exhausted or it can't (max_tries guards)
        for _ in range(2):
            try:
                p = P.generate_unique(P.UsedStore(path), max_tries=2000)
            except Exception:
                return  # keyspace exhausted — fine, means no reuse was forced
            with lock:
                results.append(p["gsf_id"])

    threads = [threading.Thread(target=worker) for _ in range(8)]
    for t in threads: t.start()
    for t in threads: t.join()

    # whatever got HANDED OUT must be unique — no two callers received the same gsf
    assert len(results) == len(set(results)), f"duplicate handed out: {results}"
