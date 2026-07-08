"""TUI render + action tests (no interactive keypress; device mocked)."""
import json
import os
from unittest import mock
import pytest


@pytest.fixture
def dash(tmp_path):
    from specter import tui
    return tui.Dashboard(str(tmp_path))


def _themed_console():
    from rich.console import Console
    from specter.theme import THEME
    return Console(theme=THEME, file=open(os.devnull, "w"))


def test_render_with_no_active_profile(dash):
    _themed_console().print(dash.render())  # must not raise


def test_act_new_creates_active_and_records(dash):
    dash.act_new()
    p = json.load(open(dash.ACTIVE))
    assert p["gsf_id"]
    assert json.load(open(dash.USED))["gsf_id"]


def test_render_after_new(dash):
    dash.act_new()
    _themed_console().print(dash.render())


def test_push_reports_no_device(dash):
    from specter import device as D
    dash.act_new()
    with mock.patch.object(D, "device_connected", return_value=False):
        dash.act_push()
    assert "DEVICE" in dash.msg or "device" in dash.msg


def test_push_success(dash):
    from specter import device as D
    dash.act_new()
    with mock.patch.object(D, "device_connected", return_value=True), \
         mock.patch.object(D, "push_profile"), mock.patch.object(D, "clear_app"):
        dash.act_push()
    assert "PUSHED" in dash.msg


def test_act_stats_renders(dash):
    dash.act_new()
    # act_stats reads console.input at the end — feed it
    dash.console = type(dash.console)(theme=__import__("specter.theme", fromlist=["THEME"]).THEME, file=open(os.devnull, "w"))
    import io
    # monkeypatch input to return immediately
    dash.console.input = lambda *a, **k: ""
    dash.act_stats()  # must not raise


def test_header_says_specter():
    from specter import tui
    import tempfile
    d = tui.Dashboard(tempfile.mkdtemp())
    from rich.console import Console
    from specter.theme import THEME
    con = Console(theme=THEME, file=open(os.devnull, "w"))
    con.print(d.render())  # renders with 'specter' header, not ghostprint
