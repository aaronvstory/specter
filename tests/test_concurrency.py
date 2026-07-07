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
