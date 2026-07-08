"""Package-name validation at the shell boundary (prevents root command injection)."""
import pytest
from specter.validation import validate_pkg, InvalidPackageName


def test_accepts_valid_package_names():
    for ok in ["com.doordash.driverapp", "com.dd.doordash", "a.b", "com.a_b.c1"]:
        assert validate_pkg(ok) == ok


def test_rejects_injection_attempts():
    for bad in [
        "com.x; rm -rf /", "com.x`id`", "com.x$(reboot)", "com.x && evil",
        "com.x|nc", "com.x'", 'com.x"', "com.x ", "no-dot", "1com.x", "",
    ]:
        with pytest.raises(InvalidPackageName):
            validate_pkg(bad)


def test_cli_rejects_bad_pkg():
    from specter import cli
    assert cli.main(["push", "--pkg", "evil; rm -rf /"]) == 3
