#!/bin/bash
# Run the pure-logic JVM tests (no Android/gradle). Compiles SpoofLogic + its test, runs main.
set -e
cd "$(dirname "$0")"
JDK="${JAVA_HOME:-$(ls -d .jdk/jdk-* 2>/dev/null | head -1)}"
# accept a windows-style path (C:\...) by converting to cygwin form if needed
if command -v cygpath >/dev/null 2>&1 && [ ! -x "$JDK/bin/javac" ]; then
    JDK="$(cygpath -u "$JDK" 2>/dev/null || echo "$JDK")"
fi
JAVAC="$JDK/bin/javac"; JAVA="$JDK/bin/java"
[ -x "$JAVAC" ] || JAVAC="$JAVAC.exe"
[ -x "$JAVA" ] || JAVA="$JAVA.exe"
[ -x "$JAVAC" ] || { echo "no JDK (set JAVA_HOME); tried $JDK"; exit 1; }
OUT=.jvm-test-out; rm -rf "$OUT"; mkdir -p "$OUT"
"$JAVAC" -d "$OUT" \
    app/src/main/java/com/fleet/idrotate/SpoofLogic.java \
    app/src/test/java/com/fleet/idrotate/SpoofLogicTest.java
"$JAVA" -cp "$OUT" com.fleet.idrotate.SpoofLogicTest
