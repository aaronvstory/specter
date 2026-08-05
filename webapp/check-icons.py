"""Render every inline icon at the size it is ACTUALLY drawn and measure it.

    python webapp/check-icons.py            # report
    python webapp/check-icons.py --strict   # exit 1 on any failure (what the test uses)

Why this exists: three of the six line icons shipped DEAD for weeks. `rx=1.2/>` unquoted parses as the
value `1.2/` with no self-close, so the element swallowed its siblings and `ban` drew literally nothing.
A missing icon is invisible as a bug — it looks exactly like a value that has no icon — and eyeballing a
screenshot missed it twice. So the icons are measured, not judged:

  INK      what fraction of the 13x13 cell is drawn. Near zero = the icon is not there.
  SPREAD   how much of the cell's width and height the drawing occupies. A shape crammed into one
           corner, or a single dash, fails here even when its ink fraction looks fine.
  INSIDE   ink strictly INSIDE the bounding-box border, as a fraction of that interior. This is the one
           that catches "it renders a rectangle at a perfectly reasonable size": a hollow box scores ~0,
           because every pixel it has is on its own outline. Size is not evidence of meaning.
  DISTINCT every pair must differ. `build` and `bot` both rendered as a plain square — each had
           perfectly healthy ink AND spread, and they were still both useless.

The report also prints each icon as ASCII at its real 13x13, so what the browser drew is on the screen
rather than described. Read those, not just the numbers.

No Pillow: PNG decoding is zlib plus the five filter types, which is shorter than a dependency.
"""
import argparse
import shutil
import struct
import subprocess
import sys
import tempfile
import zlib
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parent))

CELL = 13          # the size the icons are drawn at in the page (svg.ico{width:13px;height:13px})
PAD = 5            # gutter around each cell, so a bleeding icon can't be blamed on its neighbour
MIN_INK = 0.045    # below this the icon is effectively absent at 13px
# A 1.3-unit stroke at 13px covers ~1.06px, so a LINE drawing lands around 0.15-0.30 of the cell. Anything
# far above that is not a glyph with detail, it is a shape whose own strokes have closed over the gaps
# between them. This threshold is what catches the "renders a rectangle at a perfectly plausible size"
# failure that ink-and-spread alone happily approves.
MAX_INK = 0.38
MIN_SPREAD = 0.55  # it must use at least this much of the cell in BOTH axes
MIN_INSIDE = 0.05  # interior detail — below this it is an empty box, whatever its size
MIN_DIFF = 0.10    # two icons must differ on at least this fraction of the cell


def find_chrome() -> str:
    for c in (r"C:\Program Files\Google\Chrome\Application\chrome.exe",
              r"C:\Program Files\Google\Chrome Beta\Application\chrome.exe",
              r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
              "/usr/bin/google-chrome", "/usr/bin/chromium"):
        if Path(c).exists():
            return c
    for n in ("chrome", "chromium", "google-chrome", "msedge"):
        f = shutil.which(n)
        if f:
            return f
    return ""


def read_png_gray(data: bytes) -> tuple[int, int, list[list[int]]]:
    """(width, height, rows of 0-255 luma). Handles the 8-bit RGB/RGBA truecolour PNGs Chrome writes."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    pos, idat, w = 8, b"", 0
    h = bit = ctype = 0
    while pos < len(data):
        ln = struct.unpack(">I", data[pos:pos + 4])[0]
        typ = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + ln]
        if typ == b"IHDR":
            w, h, bit, ctype = (*struct.unpack(">IIBB", body[:10])[:2],
                                body[8], body[9])
        elif typ == b"IDAT":
            idat += body
        elif typ == b"IEND":
            break
        pos += 12 + ln
    assert bit == 8, f"expected 8-bit samples, got {bit}"
    nch = {2: 3, 6: 4}.get(ctype)
    assert nch, f"expected truecolour PNG, got colour type {ctype}"
    raw = zlib.decompress(idat)
    stride = w * nch
    out, prev = [], bytearray(stride)
    p = 0
    for _ in range(h):
        ft = raw[p]
        line = bytearray(raw[p + 1:p + 1 + stride])
        p += 1 + stride
        for i in range(stride):
            a = line[i - nch] if i >= nch else 0
            b = prev[i]
            c = prev[i - nch] if i >= nch else 0
            if ft == 1:
                line[i] = (line[i] + a) & 0xFF
            elif ft == 2:
                line[i] = (line[i] + b) & 0xFF
            elif ft == 3:
                line[i] = (line[i] + (a + b) // 2) & 0xFF
            elif ft == 4:
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 0xFF
        # Luma is enough: the harness draws pure white on pure black.
        out.append([(line[i * nch] * 299 + line[i * nch + 1] * 587 + line[i * nch + 2] * 114) // 1000
                    for i in range(w)])
        prev = line
    return w, h, out


def render_strip(chrome: str, icons: dict[str, str]) -> tuple[int, int, list[list[int]]]:
    step = CELL + PAD * 2
    cells = "".join(
        f'<div style="width:{step}px;height:{step}px;display:flex;align-items:center;'
        f'justify-content:center">{svg}</div>' for svg in icons.values())
    # White on black at exactly 13px, no antialiasing tricks, no transforms — the harness must not make an
    # icon look better than the page does.
    html = (f'<style>html,body{{margin:0;background:#000;color:#fff}}'
            f'.s{{display:flex;width:{step * len(icons)}px;height:{step}px}}'
            f'svg.ico{{width:{CELL}px;height:{CELL}px;margin:0}}</style>'
            f'<div class=s>{cells}</div>')
    with tempfile.TemporaryDirectory() as d:
        page = Path(d) / "strip.html"
        page.write_text(html, "utf-8")
        shot = Path(d) / "strip.png"
        subprocess.run([chrome, "--headless", "--disable-gpu", "--hide-scrollbars",
                        f"--screenshot={shot}", f"--window-size={step * len(icons)},{step}",
                        page.as_uri()], check=True, capture_output=True, timeout=120)
        assert shot.exists(), "chrome rendered nothing"
        return read_png_gray(shot.read_bytes())


def cell_bits(rows: list[list[int]], index: int) -> list[list[int]]:
    """The CELL x CELL interior of cell `index`, as 1 = drawn."""
    step = CELL + PAD * 2
    x0, y0 = index * step + PAD, PAD
    return [[1 if rows[y0 + y][x0 + x] > 96 else 0 for x in range(CELL)] for y in range(CELL)]


def measure(bits: list[list[int]]) -> tuple[float, float, float, float]:
    """(ink, width-spread, height-spread, interior-density)."""
    on = [(x, y) for y in range(CELL) for x in range(CELL) if bits[y][x]]
    if not on:
        return 0.0, 0.0, 0.0, 0.0
    xs, ys = [p[0] for p in on], [p[1] for p in on]
    x0, x1, y0, y1 = min(xs), max(xs), min(ys), max(ys)
    # Interior = the bounding box minus a 2px ring, so an outline's own antialiased edge is not counted as
    # detail. A hollow rectangle has ALL of its ink on that ring and scores 0 here no matter how big it is.
    ix0, ix1, iy0, iy1 = x0 + 2, x1 - 2, y0 + 2, y1 - 2
    area = max(0, ix1 - ix0 + 1) * max(0, iy1 - iy0 + 1)
    inside = sum(1 for x, y in on if ix0 <= x <= ix1 and iy0 <= y <= iy1)
    return (len(on) / (CELL * CELL),
            (x1 - x0 + 1) / CELL,
            (y1 - y0 + 1) / CELL,
            inside / area if area else 0.0)


def ascii_art(bits: list[list[int]]) -> list[str]:
    return ["".join("#" if bits[y][x] else "." for x in range(CELL)) for y in range(CELL)]


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--strict", action="store_true")
    args = ap.parse_args()

    chrome = find_chrome()
    if not chrome:
        print("no Chrome found — skipping icon measurement")
        return 0

    from tools.page_assets import inline_icons          # noqa: E402
    icons = inline_icons()
    assert icons, "no icons parsed out of PAGE"
    _, _, rows = render_strip(chrome, icons)
    names = list(icons)
    bits = {n: cell_bits(rows, i) for i, n in enumerate(names)}

    bad = []
    print(f"{'icon':<10} {'ink':>7} {'wide':>7} {'tall':>7} {'inside':>7}   verdict")
    for n in names:
        ink, sw, sh, ins = measure(bits[n])
        why = []
        if ink < MIN_INK:
            why.append(f"NOT DRAWN (ink {ink:.3f} < {MIN_INK})")
        elif ink > MAX_INK:
            why.append(f"solid blob (ink {ink:.3f})")
        if min(sw, sh) < MIN_SPREAD:
            why.append(f"too small/flat ({sw:.2f}x{sh:.2f} of the cell)")
        if ink >= MIN_INK and ins < MIN_INSIDE:
            why.append(f"reads as an empty box — no interior detail ({ins:.3f})")
        print(f"{n:<10} {ink:>7.3f} {sw:>7.2f} {sh:>7.2f} {ins:>7.3f}   {'; '.join(why) if why else 'ok'}")
        bad += [f"{n}: {x}" for x in why]

    # What the browser actually drew, side by side at real size. Numbers say "a shape is present";
    # only this says WHICH shape.
    print()
    art = {n: ascii_art(bits[n]) for n in names}
    print("  " + "  ".join(f"{n:<{CELL}}" for n in names))
    for r in range(CELL):
        print("  " + "  ".join(art[n][r] for n in names))
    print()
    for i, a in enumerate(names):
        for b in names[i + 1:]:
            diff = sum(bits[a][y][x] != bits[b][y][x]
                       for y in range(CELL) for x in range(CELL)) / (CELL * CELL)
            if diff < MIN_DIFF:
                msg = f"{a} and {b} are indistinguishable at {CELL}px (differ on {diff:.1%} of the cell)"
                print("  " + msg)
                bad.append(msg)

    if bad:
        print(f"\n{len(bad)} problem(s).")
        return 1 if args.strict else 0
    print(f"all {len(names)} icons render, fill the cell, and are distinct at {CELL}px")
    return 0


if __name__ == "__main__":
    sys.exit(main())
