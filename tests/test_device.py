"""device layer tests — adb calls mocked so they run with no phone attached."""
import json
import pytest
from unittest import mock
from specter import device as D


def fake_run(rc=0, out="", err=""):
    return mock.Mock(return_value=(rc, out, err))


def test_device_connected_parses_adb_devices():
    with mock.patch.object(D, "adb", return_value=(0, "List of devices attached\nABC123\tdevice", "")):
        assert D.device_connected()
    with mock.patch.object(D, "adb", return_value=(0, "List of devices attached\n", "")):
        assert not D.device_connected()


def test_has_root_checks_uid0():
    with mock.patch.object(D, "su", return_value=(0, "uid=0(root) gid=0(root)", "")):
        assert D.has_root()
    with mock.patch.object(D, "su", return_value=(0, "uid=2000(shell)", "")):
        assert not D.has_root()


def test_push_profile_calls_adb_push(tmp_path):
    calls = []
    def rec_adb(*a, **k):
        calls.append(a)
        return (0, "", "")
    with mock.patch.object(D, "adb", side_effect=rec_adb), mock.patch.object(D, "su", return_value=(0, "", "")):
        D.push_profile({"android_id": "abc"}, "com.doordash.driverapp")
    assert any(a and a[0] == "push" for a in calls), "adb push not called"


def test_push_profile_raises_on_failure():
    with mock.patch.object(D, "adb", return_value=(1, "", "no space")), mock.patch.object(D, "su", return_value=(0, "", "")):
        with pytest.raises(D.AdbError):
            D.push_profile({"x": "y"}, "com.test.app")


def test_clear_app_requires_success():
    with mock.patch.object(D, "su", return_value=(0, "Success", "")):
        D.clear_app("com.test.app")  # no raise
    with mock.patch.object(D, "su", return_value=(0, "Failed", "")):
        with pytest.raises(D.AdbError):
            D.clear_app("com.test.app")


def test_adb_missing_binary_raises():
    with mock.patch("subprocess.run", side_effect=FileNotFoundError):
        with pytest.raises(D.AdbError):
            D.adb("devices")
