"""CLI tests — end to end with a temp home, adb mocked."""
import json
import os
import importlib
from unittest import mock
import pytest


@pytest.fixture
def cli(tmp_path, monkeypatch):
    from specter import cli as C
    monkeypatch.setattr(C, "USED", str(tmp_path / "used.json"))
    monkeypatch.setattr(C, "VAULT", str(tmp_path / "vault.json"))
    monkeypatch.setattr(C, "ACTIVE", str(tmp_path / "active.json"))
    return C


def test_new_writes_active_and_records_used(cli):
    assert cli.main(["new"]) == 0
    p = json.load(open(cli.ACTIVE))
    assert p["gsf_id"]
    assert json.load(open(cli.USED))["gsf_id"]


def test_new_with_name_saves_to_vault(cli):
    cli.main(["new", "--name", "alice"])
    vault = json.load(open(cli.VAULT))
    assert "alice" in vault


def test_repeated_new_never_reuses_gsf(cli):
    gsfs = set()
    for _ in range(50):
        cli.main(["new"])
        g = json.load(open(cli.ACTIVE))["gsf_id"]
        assert g not in gsfs
        gsfs.add(g)


def test_stats_counts_issued(cli, capsys):
    for _ in range(5):
        cli.main(["new"])
    cli.main(["stats"])
    assert "5" in capsys.readouterr().out


def test_push_without_device_errors(cli):
    from specter import device as D
    with mock.patch.object(D, "device_connected", return_value=False):
        cli.main(["new"])
        assert cli.main(["push"]) == 2


def test_rotate_generates_and_pushes(cli):
    from specter import device as D
    with mock.patch.object(D, "device_connected", return_value=True), \
         mock.patch.object(D, "push_profile") as pp, \
         mock.patch.object(D, "clear_app") as ca:
        assert cli.main(["rotate", "--pkg", "com.doordash.driverapp"]) == 0
        pp.assert_called_once()
        ca.assert_called_once()
