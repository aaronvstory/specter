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
    app/src/main/java/com/specter/module/SpoofLogic.java \
    app/src/main/java/com/specter/module/gen/Generators.java \
    app/src/main/java/com/specter/module/gen/Country.java \
    app/src/main/java/com/specter/module/gen/Profile.java \
    app/src/main/java/com/specter/module/gen/UsedStore.java \
    app/src/main/java/com/specter/module/gen/RootWriter.java \
    app/src/main/java/com/specter/module/gen/SessionMigrator.java \
    app/src/main/java/com/specter/module/gen/AppDataVault.java \
    app/src/main/java/com/specter/module/gen/WidevineL3.java \
    app/src/main/java/com/specter/module/gen/OtaBlock.java \
    app/src/main/java/com/specter/module/gen/GsfReset.java \
    app/src/main/java/com/specter/module/gen/ActivationVerifier.java \
    app/src/main/java/com/specter/module/ui/DiagnosticsCmd.java \
    app/src/main/java/com/specter/module/ui/TraceParser.java \
    app/src/main/java/com/specter/module/ui/VaultChecksum.java \
    app/src/main/java/com/specter/module/ui/Coverage.java \
    app/src/main/java/com/specter/module/ui/Dnsbl.java \
    app/src/main/java/com/specter/module/ui/DiagReport.java \
    app/src/main/java/com/specter/module/ui/RestoreTargets.java \
    app/src/test/java/com/specter/module/SpoofLogicTest.java \
    app/src/test/java/com/specter/module/gen/GeneratorsTest.java \
    app/src/test/java/com/specter/module/gen/ProfileTest.java \
    app/src/test/java/com/specter/module/gen/RootWriterTest.java \
    app/src/test/java/com/specter/module/gen/SessionMigratorTest.java \
    app/src/test/java/com/specter/module/gen/AppDataVaultTest.java \
    app/src/test/java/com/specter/module/gen/WidevineL3Test.java \
    app/src/test/java/com/specter/module/gen/OtaBlockTest.java \
    app/src/test/java/com/specter/module/gen/GsfResetTest.java \
    app/src/test/java/com/specter/module/gen/ActivationVerifierTest.java \
    app/src/test/java/com/specter/module/ui/DiagnosticsCmdTest.java \
    app/src/test/java/com/specter/module/ui/TraceParserTest.java \
    app/src/test/java/com/specter/module/ui/VaultPortableTest.java \
    app/src/test/java/com/specter/module/ui/CoverageTest.java \
    app/src/test/java/com/specter/module/ui/DnsblTest.java \
    app/src/test/java/com/specter/module/ui/DiagReportTest.java \
    app/src/test/java/com/specter/module/ui/RestoreTargetsTest.java
"$JAVA" -cp "$OUT" com.specter.module.SpoofLogicTest
"$JAVA" -cp "$OUT" com.specter.module.gen.GeneratorsTest
"$JAVA" -cp "$OUT" com.specter.module.gen.ProfileTest
"$JAVA" -cp "$OUT" com.specter.module.gen.RootWriterTest
"$JAVA" -cp "$OUT" com.specter.module.gen.SessionMigratorTest
"$JAVA" -cp "$OUT" com.specter.module.gen.AppDataVaultTest
"$JAVA" -cp "$OUT" com.specter.module.gen.WidevineL3Test
"$JAVA" -cp "$OUT" com.specter.module.gen.OtaBlockTest
"$JAVA" -cp "$OUT" com.specter.module.gen.GsfResetTest
"$JAVA" -cp "$OUT" com.specter.module.gen.ActivationVerifierTest
"$JAVA" -cp "$OUT" com.specter.module.ui.DiagnosticsCmdTest
"$JAVA" -cp "$OUT" com.specter.module.ui.TraceParserTest
"$JAVA" -cp "$OUT" com.specter.module.ui.VaultPortableTest
"$JAVA" -cp "$OUT" com.specter.module.ui.CoverageTest
"$JAVA" -cp "$OUT" com.specter.module.ui.DnsblTest
"$JAVA" -cp "$OUT" com.specter.module.ui.DiagReportTest
"$JAVA" -cp "$OUT" com.specter.module.ui.RestoreTargetsTest
python3 check_copy_guard.py
