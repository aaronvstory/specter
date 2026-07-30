#!/usr/bin/env python3
"""Copy-guard: every Protections.ALL description must be ONE short line — no paragraphs anywhere in the app
(user's hard rule, POLISH-PLAN-v0.19.3). Scans the Java source directly since Protections.java depends on
android.content.SharedPreferences and can't compile in the plain-JVM test harness (run-jvm-tests.sh)."""
import re
import sys

PATH = "app/src/main/java/com/specter/module/ui/Protections.java"
MAX_LEN = 80


def main():
    text = open(PATH, encoding="utf-8").read()
    descs = re.findall(r'new P\(\s*"[^"]*",\s*"[^"]*",\s*"([^"]*)"', text)
    if len(descs) < 5:
        print(f"FAIL: only found {len(descs)} descriptions — parser likely broken vs Protections.ALL")
        return 1

    failed = 0
    for d in descs:
        if "\n" in d:
            print(f"FAIL: description contains a newline: {d!r}")
            failed += 1
        if d.count(".") > 1:
            print(f"FAIL: description has more than one sentence terminator: {d!r}")
            failed += 1
        if len(d) > MAX_LEN:
            print(f"FAIL: description exceeds {MAX_LEN} chars ({len(d)}): {d!r}")
            failed += 1

    if failed:
        print(f"{failed} copy-guard violation(s)")
        return 1
    print(f"OK: {len(descs)} descriptions pass the copy guard")
    return 0


if __name__ == "__main__":
    sys.exit(main())
