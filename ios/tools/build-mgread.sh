#!/usr/bin/env bash
# Build the mgread verification CLI with the theos toolchain (WSL, non-sudo).
# Run: wsl -d Ubuntu -- bash /mnt/f/claude/specter/ios/tools/build-mgread.sh
set -e
THEOS="${THEOS:-$HOME/theos}"
TOOLS_DIR=/mnt/f/claude/specter/ios/tools
OUT_DIR=/mnt/f/claude/specter/ios/dist
mkdir -p "$OUT_DIR"

CLANG="$(ls "$THEOS"/toolchain/*/iphone/bin/clang 2>/dev/null | head -1)"
[ -z "$CLANG" ] && CLANG="$(ls "$THEOS"/toolchain/*/*/bin/clang 2>/dev/null | head -1)"
[ -z "$CLANG" ] && CLANG="$(command -v clang)"
SDK="$(ls -d "$THEOS"/sdks/iPhoneOS*.sdk 2>/dev/null | sort -V | tail -1)"
LDID="$(command -v ldid || true)"
[ -z "$LDID" ] && LDID="$(ls "$THEOS"/bin/ldid "$HOME"/local/bin/ldid 2>/dev/null | head -1)"

echo "clang: $CLANG"
echo "sdk:   $SDK"
echo "ldid:  $LDID"

for SRC in "$TOOLS_DIR"/*.m; do
  OUT="$OUT_DIR/$(basename "$SRC" .m)"
  "$CLANG" -target arm64-apple-ios13.0 -isysroot "$SDK" \
    -Wno-unused-command-line-argument \
    -framework Foundation -framework CoreFoundation -framework IOKit \
    -o "$OUT" "$SRC"
  if [ -n "$LDID" ]; then "$LDID" -S "$OUT"; fi
  echo "== built $(basename "$OUT") =="; file "$OUT"
done
