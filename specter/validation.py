"""
validation.py — input validation at trust boundaries.

Package names get interpolated into `su -c "..."` shell strings on the device, so they must
be validated against Android's package-name grammar before ever reaching a shell. A stray
`;`, backtick, or $() in a pasted package name would otherwise run as root on the phone.
"""
import re

# Android package name: dot-separated segments, each starting with a letter,
# then letters/digits/underscore. At least two segments.
_PKG_RE = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z][a-zA-Z0-9_]*)+$")


class InvalidPackageName(ValueError):
    pass


def validate_pkg(pkg: str) -> str:
    """Return pkg if it is a valid Android package name, else raise InvalidPackageName."""
    if not isinstance(pkg, str) or not _PKG_RE.match(pkg):
        raise InvalidPackageName(
            f"invalid Android package name: {pkg!r} "
            "(expected e.g. com.doordash.driverapp)"
        )
    return pkg
