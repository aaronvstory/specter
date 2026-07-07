"""verify harness tests — device mocked, so checks run with no phone."""
import json
import os
from unittest import mock
import pytest
from rich.console import Console


@pytest.fixture
def V(tmp_path, monkeypatch):
    from specter import verify, profile as P
    # point profile storage at tmp
    monkeypatch.setattr(verify, "ROOT", str(tmp_path))
    monkeypatch.setattr(verify, "REPORTS", str(tmp_path / "reports"))
    v = verify.Verifier("com.doordash.driverapp", console=Console(file=open(os.devnull, "w")))
    return v, verify


def test_coverage_records(V):
    v, mod = V
    from specter import device as D
    with mock.patch.object(D, "read_geergit_hook_log", return_value=["GEERGIT Android ID", "GEERGIT serial"]):
        v.check_coverage()
    assert v.results["coverage"]["loglines"] == 2
    assert v.results["coverage"]["covered"]


def test_backup_reload_roundtrip(V, tmp_path):
    v, mod = V
    v.check_backup_reload()
    assert v.results["backup_reload"]["ok"] is True


def test_rotation_detects_fresh(V):
    v, mod = V
    from specter import device as D, profile as P
    # each launch: app "stores" exactly the pushed identity (simulate a working hook)
    pushed = {}

    def fake_push(profile, pkg):
        pushed["p"] = profile

    def fake_reads():
        p = pushed["p"]
        return {"android_ids": [p["android_id"]], "gsf_ids": [p["gsf_id"]]}

    with mock.patch.object(D, "push_profile", side_effect=fake_push), \
         mock.patch.object(D, "clear_app"), \
         mock.patch.object(v, "_launch_target", return_value="1234"), \
         mock.patch.object(v, "_app_stored_identity", side_effect=fake_reads):
        v.check_rotation(launches=3)
    assert v.results["rotation"]["any_repeat"] is False
    assert len(v.results["rotation"]["android_ids"]) == 3


def test_leak_audit_flags_real_id(V):
    v, mod = V
    from specter import device as D
    with mock.patch.object(D, "read_live_identifiers",
                           return_value={"serial": "REALSER", "ssaid_u0": "deadbeefdeadbeef"}), \
         mock.patch.object(v, "_app_stored_identity",
                           return_value={"android_ids": ["deadbeefdeadbeef"], "gsf_ids": []}):
        v.check_leak_audit()
    assert v.results["leak_audit"]["leaks"], "should flag the real android_id leak"


def test_leak_audit_clean(V):
    v, mod = V
    from specter import device as D
    with mock.patch.object(D, "read_live_identifiers",
                           return_value={"serial": "REALSER", "ssaid_u0": "realssaid00000000"}), \
         mock.patch.object(v, "_app_stored_identity",
                           return_value={"android_ids": ["fakefakefakefake"], "gsf_ids": []}):
        v.check_leak_audit()
    assert not v.results["leak_audit"]["leaks"]


def test_summary_renders(V):
    v, mod = V
    from rich.console import Console
    from specter.theme import THEME
    import os
    v.results = {
        "coverage": {"loglines": 5, "covered": ["android_id", "gsf_id"]},
        "rotation": {"launches": 3, "any_repeat": False, "not_found": 0, "android_ids": ["a"], "gsf_ids": ["g"]},
        "backup_reload": {"ok": True},
        "leak_audit": {"leaks": []},
    }
    con = Console(theme=THEME, file=open(os.devnull, "w"))
    mod._summary(con, v.results)  # must not raise


def test_preflight_no_device(V):
    v, mod = V
    from rich.console import Console
    from specter.theme import THEME
    from specter import device as D
    import os
    from unittest import mock
    con = Console(theme=THEME, file=open(os.devnull, "w"))
    with mock.patch.object(D, "device_connected", return_value=False):
        assert mod._preflight(con) is False
