#!/bin/bash
# Build the Specter LSPosed module APK. Requires: a full JDK 17, Android SDK.
# Auto-detects local .jdk / .gradle-dist if present (set up by the overnight build).
set -e
cd "$(dirname "$0")"

JDK="${JAVA_HOME:-$(ls -d .jdk/jdk-* 2>/dev/null | head -1)}"
GRADLE="${GRADLE_BIN:-$(ls .gradle-dist/gradle-*/bin/gradle 2>/dev/null | head -1)}"
: "${ANDROID_HOME:=$HOME/AppData/Local/Android/Sdk}"

[ -x "$JDK/bin/javac" ] || { echo "No JDK 17 (set JAVA_HOME or run bootstrap)"; exit 1; }
[ -x "$GRADLE" ] || { echo "No gradle (set GRADLE_BIN)"; exit 1; }

export JAVA_HOME="$JDK"
export ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

# Single source of truth for the version (../VERSION), so the staged APK name always matches.
VERSION="$(cat ../VERSION 2>/dev/null || echo 0.0.0)"

echo "[build] $(date '+%Y-%m-%d %H:%M:%S')  JDK=$JDK  version=$VERSION"
# Force a fresh Java compile so a NEW compile error can't be masked by stale incremental .class files
# (that once shipped a broken APK — a full 'BUILD SUCCESSFUL' on code that didn't actually compile).
"$GRADLE" :app:clean --no-daemon
"$GRADLE" :app:assembleDebug --no-daemon "$@"
APK="app/build/outputs/apk/debug/app-debug.apk"
echo "[build] APK: $APK"
mkdir -p ../dist
cp "$APK" "../dist/specter-module-v${VERSION}.apk"
echo "[build] staged -> dist/specter-module-v${VERSION}.apk"
