"""Pull the drawable assets out of PAGE, so a checker and a test can inspect what actually ships.

ONE parser, used by webapp/check-icons.py and by tests/test_ipcheck.py. Two copies of "find the icons"
would drift, and the drift would land on the side that is supposed to catch drift.
"""
import re

from specter.ipcheck import PAGE


def inline_icons() -> dict[str, str]:
    """{name: <svg…>} for every entry in PAGE's ICON table, with SVG()'s wrapper already applied.

    Evaluated the way the browser will see it rather than by eye: the icons are defined as
    ``name: SVG('<rect .../>...')``, and it is the WRAPPED markup that renders, so that is what this
    returns."""
    wrap = re.search(r"const SVG=p=>`(.*?)`\+\s*\n?\s*`(.*?)`;", PAGE, re.S)
    assert wrap, "SVG() helper not found in PAGE"
    head = (wrap.group(1) + wrap.group(2)).replace("${p}", "\x00")
    assert "\x00" in head, "SVG() no longer interpolates its argument"

    block = re.search(r"const ICON=\{(.*?)\n\};", PAGE, re.S)
    assert block, "ICON table not found in PAGE"
    out: dict[str, str] = {}
    # Each entry starts at `name: SVG(` and runs to the next one (or the end of the block). Cutting on
    # entry STARTS rather than on a trailing `),\n` is deliberate: an entry's body spans several lines, and
    # the terminator-based version silently dropped the last icon — which happened to be `ban`, the very
    # one that rendered nothing. The parsed count is checked against the declared count so a parser that
    # quietly skips an icon fails here instead of reporting "all icons fine" about a subset.
    body = block.group(1)
    starts = [m for m in re.finditer(r"(\w+):\s*SVG\(", body)]
    for i, m in enumerate(starts):
        end = starts[i + 1].start() if i + 1 < len(starts) else len(body)
        parts = re.findall(r"'([^']*)'", body[m.end():end])
        assert parts, f"ICON.{m.group(1)} has no string literal"
        out[m.group(1)] = head.replace("\x00", "".join(parts))
    assert len(out) == len(starts), "duplicate icon name in the ICON table"
    return out


def svg_attributes_are_quoted() -> list[str]:
    """Every ``name=value`` in PAGE's inline SVG that is NOT quoted, as ``tag name=value`` strings.

    Unquoted is not a style question here. ``rx=1.2/>`` parses as the value ``1.2/`` with NO self-close,
    so the element swallows the siblings that follow it — which is how three of six icons shipped blank.
    """
    bad = []
    for tag in re.findall(r"<(?:rect|circle|path|line|ellipse|polygon|polyline|svg)\b[^>]*>", PAGE):
        for attr in re.findall(r"([\w:-]+)=([^\s\"'>]+)", tag):
            bad.append(f"{tag.split()[0].lstrip('<')} {attr[0]}={attr[1]}")
    return bad
