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

# Bundle the Zygisk native layer INTO the app's assets so the app can self-install it (no manual flash).
# Copies the freshly-built .so + a version-stamped module.prop + sepolicy.rule. If the .so isn't built yet,
# warn but don't fail — the app just won't be able to auto-install until build-zygisk.sh has run.
ZYGISK_ASSET="app/src/main/assets/zygisk"
ZYGISK_SO="$(ls zygisk/build/intermediates/cxx/*/*/obj/arm64-v8a/libspecter_zygisk.so 2>/dev/null | head -1)"
if [ -n "$ZYGISK_SO" ] && [ -f "$ZYGISK_SO" ]; then
    mkdir -p "$ZYGISK_ASSET"
    cp "$ZYGISK_SO" "$ZYGISK_ASSET/arm64-v8a.so"
    cp zygisk/module/module.prop "$ZYGISK_ASSET/module.prop"
    sed -i "s/^version=.*/version=v${VERSION}/" "$ZYGISK_ASSET/module.prop"
    sed -i "s/^versionCode=.*/versionCode=$(echo "$VERSION" | tr -d '.')/" "$ZYGISK_ASSET/module.prop"
    cp zygisk/module/sepolicy.rule "$ZYGISK_ASSET/sepolicy.rule" 2>/dev/null || true
    echo "[build] bundled zygisk asset ($(stat -c%s "$ZYGISK_ASSET/arm64-v8a.so" 2>/dev/null || echo '?') bytes) v${VERSION}"
else
    echo "[build] WARN: no built zygisk .so found — run build-zygisk.sh first so the app can self-install it"
fi

# Force a fresh Java compile so a NEW compile error can't be masked by stale incremental .class files
# (that once shipped a broken APK — a full 'BUILD SUCCESSFUL' on code that didn't actually compile).
"$GRADLE" :app:clean --no-daemon
"$GRADLE" :app:assembleDebug --no-daemon "$@"
APK="app/build/outputs/apk/debug/app-debug.apk"
echo "[build] APK: $APK"
mkdir -p ../dist
cp "$APK" "../dist/specter-module-v${VERSION}.apk"
echo "[build] staged -> dist/specter-module-v${VERSION}.apk"

# Specter Lite — the standalone no-root harvester. Versioned independently (lite/build.gradle), so a
# friend can install it on a NON-rooted phone, harvest a profile, and hand it back to import into Specter.
# Debug-signed like the module (this project's accepted install path). Best-effort — a Lite build failure
# must not fail the main module build.
LITE_VER="$(grep -oE 'versionName[[:space:]]+"[^"]+"' lite/build.gradle 2>/dev/null | grep -oE '"[^"]+"' | tr -d '"')"
: "${LITE_VER:=1.0}"
if "$GRADLE" :lite:assembleDebug --no-daemon "$@"; then
    LITE_APK="lite/build/outputs/apk/debug/lite-debug.apk"
    if [ -f "$LITE_APK" ]; then
        cp "$LITE_APK" "../dist/specter-lite-v${LITE_VER}.apk"
        echo "[build] staged -> dist/specter-lite-v${LITE_VER}.apk"
    fi
else
    echo "[build] WARN: Specter Lite build failed — module APK is fine, lite not staged"
fi
