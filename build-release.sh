#!/bin/bash
# Build a distributable Specter release: the module APK + the Python tool + docs, zipped.
set -e
cd "$(dirname "$0")"
VERSION=$(cat VERSION 2>/dev/null || echo "0.1.0")
OUT="dist/specter-v${VERSION}"
echo "[release] building Specter v${VERSION}"

# 1. build the module APK (if toolchain present)
if [ -x xposed-module/build-apk.sh ]; then
    echo "[release] building module APK (R8-obfuscated, no seeded keys)..."
    # SPECTER_RELEASE=1 => the distributable is obfuscated + carries no dev keys (§2e). A dev build is the
    # default (readable + seeded); the thing we hand out must not be.
    ( cd xposed-module && SPECTER_RELEASE=1 ./build-apk.sh ) || echo "[release] APK build skipped (no toolchain)"
fi

# 2. stage the distributable tree
rm -rf "$OUT"; mkdir -p "$OUT"
cp -r specter data docs "$OUT/"
cp README.md CHANGELOG.md VERSION pyproject.toml requirements.txt launch.bat launch.command "$OUT/" 2>/dev/null || true
mkdir -p "$OUT/module"
[ -f dist/specter-module-v${VERSION}.apk ] && cp dist/specter-module-v${VERSION}.apk "$OUT/module/"
# strip caches / secrets
find "$OUT" -name "__pycache__" -type d -exec rm -rf {} + 2>/dev/null || true
find "$OUT" -name "used_ids.json" -o -name "profiles.json" -o -name "profile.json" | xargs rm -f 2>/dev/null || true

# 3. zip it
( cd dist && python -c "import shutil; shutil.make_archive('specter-v${VERSION}', 'zip', 'specter-v${VERSION}')" )
echo "[release] -> dist/specter-v${VERSION}.zip"
ls -la "dist/specter-v${VERSION}.zip"
