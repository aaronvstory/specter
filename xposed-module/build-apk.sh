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

echo "[build] $(date '+%Y-%m-%d %H:%M:%S')  JDK=$JDK"
"$GRADLE" :app:assembleDebug --no-daemon "$@"
APK="app/build/outputs/apk/debug/app-debug.apk"
echo "[build] APK: $APK"
mkdir -p ../dist
cp "$APK" ../dist/specter-module-v0.1.0.apk
echo "[build] staged -> dist/specter-module-v0.1.0.apk"
