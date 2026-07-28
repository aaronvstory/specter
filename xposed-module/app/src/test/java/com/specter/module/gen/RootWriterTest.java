package com.specter.module.gen;

/** Plain-JVM tests for RootWriter command-building + fail-loudly behaviour. Run via run-jvm-tests.sh. */
public class RootWriterTest {
    static int passed = 0, failed = 0;
    static void check(boolean cond, String name) {
        if (cond) passed++; else { failed++; System.out.println("FAIL: " + name); }
    }

    public static void main(String[] args) {
        // valid package names accepted
        check(RootWriter.validPkg("com.liuzh.deviceinfo"), "valid pkg");
        check(RootWriter.validPkg("com.a.b.c"), "valid multi-segment");
        // shell-injection attempts rejected (the pkg is the only thing on the su command line)
        check(!RootWriter.validPkg("com.x; rm -rf /"), "reject semicolon");
        check(!RootWriter.validPkg("com.x`whoami`"), "reject backtick");
        check(!RootWriter.validPkg("com.x$(id)"), "reject subshell");
        check(!RootWriter.validPkg("nodots"), "reject no-dot");
        check(!RootWriter.validPkg(""), "reject empty");
        check(!RootWriter.validPkg(null), "reject null");

        // command targets the exact hook-read path, feeds JSON via stdin, chmods 644, writes atomically
        String cmd = RootWriter.buildShellCommand("com.liuzh.deviceinfo");
        String finalPath = "/data/local/tmp/specter/com.liuzh.deviceinfo.json";
        check(cmd.contains(finalPath), "command targets hook path");
        check(cmd.contains("mkdir -p /data/local/tmp/specter"), "command mkdirs the dir");
        check(cmd.contains("chmod 644"), "command chmods world-readable");
        check(cmd.startsWith("mkdir"), "no pkg interpolation before validated path");
        // ATOMIC: writes to a .tmp, and mv's it over the final path (never truncates the live file).
        check(cmd.contains(finalPath + ".tmp"), "writes to a .tmp first");
        check(cmd.contains("cat > " + finalPath + ".tmp"), "stdin lands in the .tmp, not the live file");
        check(cmd.contains("mv -f " + finalPath + ".tmp " + finalPath), "atomically mv .tmp over final");
        check(cmd.contains("[ -s " + finalPath + ".tmp ]"), "verifies .tmp non-empty before mv");
        check(cmd.contains("rm -f " + finalPath + ".tmp"), "cleans the .tmp on failure");
        // the live file is only ever named on the mv target / non-empty check — never `cat >`-truncated
        check(!cmd.contains("cat > " + finalPath + " "), "never truncates the live file with cat");

        // invalid pkg throws at command-build (never reaches a shell)
        boolean threw = false;
        try { RootWriter.buildShellCommand("bad;rm"); } catch (RootWriter.WriteException e) { threw = true; }
        check(threw, "invalid pkg -> WriteException at build");

        // write() fails LOUDLY (throws) on a non-zero exit — never a silent no-op
        RootWriter.Shell denied = (command, stdin) -> 1; // simulate su denied
        boolean loud = false;
        try { RootWriter.write(denied, "com.liuzh.deviceinfo", "{}"); }
        catch (RootWriter.WriteException e) { loud = true; }
        check(loud, "non-zero su exit -> WriteException (loud)");

        // write() passes the JSON to stdin and the built command
        final String[] captured = new String[2];
        RootWriter.Shell ok = (command, stdin) -> { captured[0] = command; captured[1] = stdin; return 0; };
        RootWriter.write(ok, "com.liuzh.deviceinfo", "{\"android_id\":\"abc\"}");
        check(captured[1].equals("{\"android_id\":\"abc\"}"), "json fed via stdin");
        check(captured[0].contains("com.liuzh.deviceinfo.json"), "correct target in command");

        // exec exception surfaces as WriteException (e.g. su binary absent)
        RootWriter.Shell boom = (command, stdin) -> { throw new RuntimeException("su: not found"); };
        boolean surfaced = false;
        try { RootWriter.write(boom, "com.liuzh.deviceinfo", "{}"); }
        catch (RootWriter.WriteException e) { surfaced = e.getMessage().contains("root"); }
        check(surfaced, "su-absent surfaces as WriteException mentioning root");

        System.out.println("RootWriter: " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
