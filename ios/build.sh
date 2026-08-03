#!/bin/bash
# Build Specter-iOS (tweak + probe) with theos — no sudo required. Run inside WSL Ubuntu:
#   wsl -d Ubuntu -- bash /mnt/f/claude/specter/ios/build.sh [all|tweak|probe]
# Bootstraps build tools user-space, builds in the WSL home (native fs), repacks the deb arch to
# iphoneos-arm64e (RootHide), and copies the .deb to ios/dist/. One-time toolchain setup is separate.
set -e
: "${HOME:=/home/$(whoami)}"
export THEOS="$HOME/theos"
LOCAL="$HOME/local"
export PATH="$LOCAL/usr/bin:$LOCAL/bin:/usr/bin:/bin:/usr/sbin:/sbin:$THEOS/bin:$PATH"
export LD_LIBRARY_PATH="$LOCAL/usr/lib/x86_64-linux-gnu:$LOCAL/usr/lib:$LD_LIBRARY_PATH"
[ -f "$THEOS/makefiles/common.mk" ] || { echo "theos not found at $THEOS — run scratchpad/setup_theos.sh first"; exit 1; }

# --- build tools without sudo: make + fakeroot via apt-get download|dpkg-deb -x; static ldid ---
mkdir -p "$LOCAL/usr/bin"
if ! command -v make >/dev/null 2>&1; then
  d=$(mktemp -d); ( cd "$d" && apt-get download make fakeroot libfakeroot >/dev/null 2>&1 || true
                    for p in ./*.deb; do [ -f "$p" ] && dpkg-deb -x "$p" "$LOCAL"; done ); rm -rf "$d"
fi
[ -x "$LOCAL/usr/bin/ldid" ] || curl -sL -o "$LOCAL/usr/bin/ldid" \
  https://github.com/ProcursusTeam/ldid/releases/latest/download/ldid_linux_x86_64 && chmod +x "$LOCAL/usr/bin/ldid" 2>/dev/null || true
command -v make >/dev/null || { echo "make unavailable (apt-get download failed — needs network or a one-time 'sudo apt install make fakeroot')"; exit 1; }

SRC="$(cd "$(dirname "$0")" && pwd)"
BUILD="$HOME/specter-ios-build"; DIST="$SRC/dist"; mkdir -p "$BUILD" "$DIST"
WHAT="${1:-all}"; if [ "$WHAT" = all ]; then projs="tweak probe"; else projs="$WHAT"; fi

for p in $projs; do
  echo "===================== $p ====================="
  rsync -a --delete --exclude .theos --exclude packages --exclude obj "$SRC/$p/" "$BUILD/$p/"
  cd "$BUILD/$p"
  make clean >/dev/null 2>&1 || true
  make package FINALPACKAGE=1 THEOS="$THEOS"
  # theos rootless scheme tags the deb iphoneos-arm64; RootHide wants iphoneos-arm64e. The binaries are
  # already fat (arm64 + arm64e), so repack with the correct arch tag → installs with plain `dpkg -i`.
  for deb in packages/*.deb; do
    [ -f "$deb" ] || continue
    t=$(mktemp -d); dpkg-deb -R "$deb" "$t"
    sed -i 's/^Architecture: iphoneos-arm64$/Architecture: iphoneos-arm64e/' "$t/DEBIAN/control"
    out="$DIST/$(basename "${deb%_iphoneos-arm64.deb}")_iphoneos-arm64e.deb"
    dpkg-deb -b "$t" "$out" >/dev/null && echo ">> $p -> $(basename "$out")"
    rm -rf "$t"
  done
done
echo "===================== dist ====================="
ls -la "$DIST"/*.deb 2>/dev/null || echo "(no debs)"
