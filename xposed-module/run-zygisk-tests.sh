#!/bin/bash
# Unit-test the Zygisk native layer's pure logic (spoof_logic.h) by cross-compiling the self-test for
# arm64 with the NDK clang and running it on the connected device (the only aarch64 target on this box).
# No device write beyond /data/local/tmp. Exit 0 = all asserts passed.
set -e
cd "$(dirname "$0")"

: "${ANDROID_HOME:=$HOME/AppData/Local/Android/Sdk}"
NDK="$(ls -d "$ANDROID_HOME"/ndk/27.* 2>/dev/null | head -1)"
[ -d "$NDK" ] || { echo "NDK 27 not found under $ANDROID_HOME/ndk"; exit 1; }

# The NDK ships a Windows clang; use it to target aarch64-linux-android.
CLANG="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/clang++.exe 2>/dev/null | head -1)"
[ -x "$CLANG" ] || CLANG="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/clang++ 2>/dev/null | head -1)"
[ -x "$CLANG" ] || { echo "clang++ not found in NDK"; exit 1; }

SERIAL="${1:-}"
ADB="adb"; [ -n "$SERIAL" ] && ADB="adb -s $SERIAL"

OUT=".zygisk-test-out"
mkdir -p "$OUT"
BIN="$OUT/test_spoof_logic"
echo "[zygisk-test] compiling with $CLANG"
"$CLANG" --target=aarch64-linux-android24 -static-libstdc++ -std=c++17 -O0 \
    -I zygisk/src/main/cpp \
    zygisk/src/main/cpp/test_spoof_logic.cpp -o "$BIN"

echo "[zygisk-test] pushing + running on device"
$ADB push "$BIN" /data/local/tmp/test_spoof_logic >/dev/null
$ADB shell chmod 755 /data/local/tmp/test_spoof_logic
set +e
RESULT="$($ADB shell /data/local/tmp/test_spoof_logic)"
CODE=$?
set -e
echo "$RESULT"
$ADB shell rm -f /data/local/tmp/test_spoof_logic >/dev/null 2>&1 || true
[ "$CODE" = "0" ] || { echo "[zygisk-test] FAILED (exit $CODE)"; exit 1; }
echo "[zygisk-test] PASS"
