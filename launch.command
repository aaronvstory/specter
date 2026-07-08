#!/bin/bash
# Specter launcher for macOS — double-click (first run: right-click → Open).
cd "$(dirname "$0")"
echo "[specter] $(date '+%Y-%m-%d %H:%M:%S')  dir: $(pwd)"

if command -v uv >/dev/null 2>&1; then
    uv run --with rich --with questionary python -m specter.cli tui
else
    if ! command -v python3 >/dev/null 2>&1; then echo "[!] need python3 or uv"; read -r; exit 1; fi
    python3 -c "import rich, questionary" 2>/dev/null || python3 -m pip install rich questionary
    PYTHONPATH="$(pwd)" python3 -m specter.cli tui
fi
