#!/bin/bash
# Build the Specter-iOS tweak + probe with theos (run inside WSL Ubuntu).
#   wsl -d Ubuntu -- bash /mnt/f/claude/specter/ios/build.sh [tweak|probe|all]
# Builds in the WSL home (fast native fs, avoids /mnt 9p quirks) and copies the .deb back to ios/dist/.
set -e
: "${HOME:=/home/$(whoami)}"
export THEOS="$HOME/theos"
[ -f "$THEOS/makefiles/common.mk" ] || { echo "theos not found at $THEOS"; exit 1; }

SRC=/mnt/f/claude/specter/ios
BUILD="$HOME/specter-ios-build"
DIST="$SRC/dist"
WHAT="${1:-all}"
mkdir -p "$DIST" "$BUILD"

projects=""
[ "$WHAT" = "all" ] && projects="tweak probe" || projects="$WHAT"

for proj in $projects; do
  echo "===================== building $proj ====================="
  rsync -a --delete --exclude '.theos' --exclude packages --exclude obj "$SRC/$proj/" "$BUILD/$proj/"
  cd "$BUILD/$proj"
  make clean >/dev/null 2>&1 || true
  if make package FINALPACKAGE=1 THEOS="$THEOS"; then
    cp packages/*.deb "$DIST/" && echo ">> $proj OK -> $(ls -1 packages/*.deb | xargs -n1 basename)"
  else
    echo ">> $proj BUILD FAILED"; exit 1
  fi
done
echo "===================== dist ====================="
ls -la "$DIST"/*.deb 2>/dev/null || echo "(no debs)"
