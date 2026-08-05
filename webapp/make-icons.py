"""Rasterise webapp/icon.svg + icon-maskable.svg into the PNG sizes the page and the manifest ask for.

Run after editing either SVG:  python webapp/make-icons.py

Chrome headless does the rendering — no new dependency, and it is the same engine that will show the
result. The PNGs are COMMITTED, because the Vercel deploy is a static upload with no build step.

Every write is verified (PNG magic + the IHDR dimensions), because a silently-empty or wrong-sized icon
looks exactly like a working one in the file listing and only shows up as a blank tab weeks later.
"""
import os
import shutil
import struct
import subprocess
import sys
import tempfile
from pathlib import Path

HERE = Path(__file__).resolve().parent

# name -> (source svg, pixel size)
TARGETS = {
    "favicon-16.png": ("icon.svg", 16),
    "favicon-32.png": ("icon.svg", 32),
    "apple-touch-icon.png": ("icon.svg", 180),      # iOS home screen; must be PNG, no SVG support
    "icon-192.png": ("icon.svg", 192),
    "icon-512.png": ("icon.svg", 512),
    "icon-maskable-512.png": ("icon-maskable.svg", 512),
}

CHROME_CANDIDATES = [
    r"C:\Program Files\Google\Chrome\Application\chrome.exe",
    r"C:\Program Files\Google\Chrome Beta\Application\chrome.exe",
    r"C:\Program Files (x86)\Google\Chrome\Application\chrome.exe",
    "/usr/bin/google-chrome",
    "/usr/bin/chromium",
    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
]


def find_chrome() -> str:
    for c in CHROME_CANDIDATES:
        if Path(c).exists():
            return c
    for name in ("chrome", "chromium", "google-chrome", "msedge"):
        found = shutil.which(name)
        if found:
            return found
    raise SystemExit("no Chrome/Chromium found — install one, or add its path to CHROME_CANDIDATES")


def png_size(data: bytes) -> tuple[int, int]:
    """(width, height) from a PNG's IHDR, or a hard failure. This is the check: Chrome exits 0 whether or
    not it wrote anything useful, so the only proof is reading the bytes back."""
    assert data[:8] == b"\x89PNG\r\n\x1a\n", "not a PNG"
    assert data[12:16] == b"IHDR", "no IHDR where one must be"
    return struct.unpack(">II", data[16:24])


def render(chrome: str, svg: Path, size: int, out: Path) -> None:
    # An HTML shell rather than the .svg directly: --window-size sets the VIEWPORT, and a bare SVG is
    # letterboxed inside it (Chrome scales it to fit and pads), which produced transparent edges. A page
    # whose body IS the image at exactly size x size, with margin 0, screenshots pixel-for-pixel.
    shell = (f'<style>html,body{{margin:0;padding:0;width:{size}px;height:{size}px;overflow:hidden}}'
             f'img{{display:block;width:{size}px;height:{size}px}}</style>'
             f'<img src="{svg.name}">')
    with tempfile.TemporaryDirectory() as d:
        page = Path(d) / "shell.html"
        shutil.copyfile(svg, Path(d) / svg.name)      # same dir, so the relative <img> resolves
        page.write_text(shell, "utf-8")
        shot = Path(d) / "out.png"
        subprocess.run([chrome, "--headless", "--disable-gpu", "--hide-scrollbars",
                        "--default-background-color=00000000",
                        f"--screenshot={shot}", f"--window-size={size},{size}",
                        page.as_uri()],
                       check=True, capture_output=True, timeout=120)
        assert shot.exists(), f"chrome wrote nothing for {out.name}"
        data = shot.read_bytes()
    w, h = png_size(data)
    assert (w, h) == (size, size), f"{out.name}: chrome rendered {w}x{h}, expected {size}x{size}"
    out.write_bytes(data)
    print(f"  {out.name}  {size}x{size}  {len(data):,} bytes")


def main() -> int:
    chrome = find_chrome()
    print(f"rendering with {chrome}")
    for name, (src, size) in TARGETS.items():
        svg = HERE / src
        assert svg.exists(), f"missing source {svg}"
        render(chrome, svg, size, HERE / name)
    return 0


if __name__ == "__main__":
    sys.exit(main())
