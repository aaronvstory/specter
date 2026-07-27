#!/bin/bash
# Cross-compile the GLES extension-spoof invariant test for arm64 (NDK clang) and run it on the connected
# device — mirrors run-zygisk-tests.sh. No root, no device write beyond /data/local/tmp. Exit 0 = pass.
set -e
cd "$(dirname "$0")"

: "${ANDROID_HOME:=$HOME/AppData/Local/Android/Sdk}"
NDK="$(ls -d "$ANDROID_HOME"/ndk/27.* 2>/dev/null | head -1)"
[ -d "$NDK" ] || { echo "NDK 27 not found under $ANDROID_HOME/ndk"; exit 1; }
CLANG="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/clang++.exe 2>/dev/null | head -1)"
[ -x "$CLANG" ] || CLANG="$(ls "$NDK"/toolchains/llvm/prebuilt/*/bin/clang++ 2>/dev/null | head -1)"
[ -x "$CLANG" ] || { echo "clang++ not found in NDK"; exit 1; }
SYSROOT="$(dirname "$(dirname "$CLANG")")/sysroot"

SERIAL="${1:-}"
ADB="adb"; [ -n "$SERIAL" ] && ADB="adb -s $SERIAL"

OUT=".gl-ext-test-out"; mkdir -p "$OUT"; BIN="$OUT/gl_ext_invariants_test"
echo "[gl-ext-test] compiling"
"$CLANG" --target=aarch64-none-linux-android24 --sysroot="$SYSROOT" -static-libstdc++ -std=c++17 -O0 \
    gl_ext_invariants_test.cpp -o "$BIN"

echo "[gl-ext-test] pushing + running on device"
export MSYS2_ARG_CONV_EXCL="*"
$ADB push "$BIN" /data/local/tmp/gl_ext_invariants_test >/dev/null
$ADB shell chmod 755 /data/local/tmp/gl_ext_invariants_test
set +e
RESULT="$($ADB shell /data/local/tmp/gl_ext_invariants_test)"; CODE=$?
set -e
echo "$RESULT"
$ADB shell rm -f /data/local/tmp/gl_ext_invariants_test >/dev/null 2>&1 || true
[ "$CODE" = "0" ] || { echo "[gl-ext-test] FAILED (exit $CODE)"; exit 1; }
echo "[gl-ext-test] PASS"
