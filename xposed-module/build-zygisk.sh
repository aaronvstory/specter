#!/bin/bash
# Build the Specter Zygisk native module .so and assemble the flashable module zip.
# Requires: a full JDK 17, Android SDK + NDK 27 + CMake 3.22 (same toolchain the probe uses).
# Env: JAVA_HOME, GRADLE_BIN, ANDROID_HOME (see CLAUDE.md "Build (Windows)").
set -e
cd "$(dirname "$0")"

JDK="${JAVA_HOME:-$(ls -d .jdk/jdk-* 2>/dev/null | head -1)}"
GRADLE="${GRADLE_BIN:-$(ls .gradle-dist/gradle-*/bin/gradle 2>/dev/null | head -1)}"
: "${ANDROID_HOME:=$HOME/AppData/Local/Android/Sdk}"

[ -x "$JDK/bin/javac" ] || { echo "No JDK 17 (set JAVA_HOME)"; exit 1; }
[ -x "$GRADLE" ] || { echo "No gradle (set GRADLE_BIN)"; exit 1; }

export JAVA_HOME="$JDK"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

VERSION="$(cat ../VERSION 2>/dev/null || echo 0.0.0)"
echo "[zygisk] $(date '+%Y-%m-%d %H:%M:%S')  JDK=$JDK  version=$VERSION"

# Clean-build the native lib so a NEW compile error can't hide behind stale objects (CLAUDE.md rule).
"$GRADLE" :zygisk:clean --no-daemon
"$GRADLE" :zygisk:assembleRelease --no-daemon "$@"

# Extract the built .so from the AAR (jni/arm64-v8a/libspecter_zygisk.so).
AAR="$(ls zygisk/build/outputs/aar/zygisk-release.aar 2>/dev/null | head -1)"
[ -f "$AAR" ] || { echo "[zygisk] AAR not found"; exit 1; }
SO="$(find zygisk/build/intermediates -name 'libspecter_zygisk.so' -path '*arm64-v8a*' 2>/dev/null | head -1)"
[ -f "$SO" ] || { echo "[zygisk] libspecter_zygisk.so not found in build intermediates"; exit 1; }
echo "[zygisk] .so: $SO ($(stat -c%s "$SO" 2>/dev/null || wc -c < "$SO") bytes)"

# Assemble the flashable module tree.
STAGE="zygisk/build/module"
rm -rf "$STAGE"
mkdir -p "$STAGE/zygisk"
cp zygisk/module/module.prop  "$STAGE/module.prop"
cp zygisk/module/sepolicy.rule "$STAGE/sepolicy.rule"
# Keep module.prop's version in lockstep with ../VERSION.
sed -i "s/^version=.*/version=v${VERSION}/" "$STAGE/module.prop"
cp "$SO" "$STAGE/zygisk/arm64-v8a.so"

mkdir -p ../dist
OUT="../dist/specter-zygisk-v${VERSION}.zip"
rm -f "$OUT"
if command -v zip >/dev/null 2>&1; then
    ( cd "$STAGE" && zip -r -q "$OLDPWD/$OUT" . )
else
    # No `zip` on this box — use PowerShell's Compress-Archive (writes a flat zip Magisk accepts).
    WIN_STAGE="$(cygpath -w "$STAGE" 2>/dev/null || echo "$STAGE")"
    WIN_OUT="$(cygpath -w "$OUT" 2>/dev/null || echo "$OUT")"
    powershell.exe -NoProfile -Command "Compress-Archive -Path '$WIN_STAGE\\*' -DestinationPath '$WIN_OUT' -Force"
fi
echo "[zygisk] staged -> dist/specter-zygisk-v${VERSION}.zip"
