#!/usr/bin/env python3
"""Report the app's largest methods in an APK, and fail if any is too big for ART to JIT.

ART refuses to JIT-compile a method over 10 000 code units. It logs "Method exceeds compiler
instruction limit" and runs that method in the interpreter for the life of the process, and nothing
at build time says so. A composable's body compiles into one method, and `Box`, `Row`, `Column` and
`remember` are all inline, so a screen grows past the limit one reasonable-looking change at a time.
v0.1.79 shipped `EmbedWebView` at 14 803 units: every television playing an embed server ran the
player's whole body interpreted, on the main thread, twice a second.

Usage:
    python scripts/method_sizes.py app/build/outputs/apk/tv/release/Anilili_tv.apk \
        --mapping app/build/outputs/mapping/tvRelease/mapping.txt

Release numbers are the ones that matter to viewers; debug builds carry Compose's source
information and no R8, and measure roughly half again as large. Without --mapping a release build's
names are obfuscated, but the sizes are still right.

Exits 1 when an app method is at or over --limit (default 10 000). The fix is almost never to
delete code: see com.anilili.ui.components.CompiledSeparately for how the three that were over
came back under without changing what they do.
"""
import argparse
import glob
import os
import re
import subprocess
import sys
import tempfile
import zipfile

ART_JIT_LIMIT = 10_000
APP_PREFIX = "com.anilili."


def find_dexdump():
    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT") or os.path.expanduser(
        os.path.join("~", "AppData", "Local", "Android", "Sdk"))
    tools = sorted(glob.glob(os.path.join(sdk, "build-tools", "*")))
    for folder in reversed(tools):
        for name in ("dexdump.exe", "dexdump"):
            path = os.path.join(folder, name)
            if os.path.exists(path):
                return path
    sys.exit("dexdump not found under %s/build-tools; set ANDROID_HOME" % sdk)


def read_mapping(path):
    """Obfuscated (class, method) -> original "class.method"."""
    classes, methods, current = {}, {}, None
    with open(path, encoding="utf-8") as handle:
        for line in handle:
            line = line.rstrip("\n")
            if line and not line.startswith(" ") and line.endswith(":") and " -> " in line:
                original, obfuscated = line[:-1].split(" -> ")
                current = obfuscated.strip()
                classes[current] = original.strip()
            elif current and " -> " in line and "(" in line:
                left, obfuscated = line.strip().rsplit(" -> ", 1)
                name = re.search(r"([\w$<>]+)\(", left)
                if name:
                    methods.setdefault((current, obfuscated.strip()), name.group(1))
    return classes, methods


def method_sizes(apk, dexdump):
    with tempfile.TemporaryDirectory() as tmp:
        with zipfile.ZipFile(apk) as archive:
            dexes = [name for name in archive.namelist() if re.fullmatch(r"classes\d*\.dex", name)]
            for dex in dexes:
                archive.extract(dex, tmp)
        for dex in dexes:
            out = subprocess.run([dexdump, "-d", os.path.join(tmp, dex)], capture_output=True,
                                 text=True, errors="ignore").stdout
            cls = name = None
            for line in out.split("\n"):
                match = re.match(r"\s*Class descriptor\s*:\s*'L([^;]+);", line)
                if match:
                    cls = match.group(1).replace("/", ".")
                    continue
                match = re.match(r"\s*name\s*:\s*'([^']+)'", line)
                if match:
                    name = match.group(1)
                    continue
                match = re.match(r"\s*insns size\s*:\s*(\d+)", line)
                if match and cls:
                    yield int(match.group(1)), cls, name


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    parser.add_argument("apk")
    parser.add_argument("--mapping", help="R8 mapping.txt for the same build")
    parser.add_argument("--limit", type=int, default=ART_JIT_LIMIT)
    parser.add_argument("--top", type=int, default=8)
    args = parser.parse_args()

    classes, methods = read_mapping(args.mapping) if args.mapping else ({}, {})
    rows = []
    for size, cls, name in method_sizes(args.apk, find_dexdump()):
        original = classes.get(cls, cls)
        label = original + "." + methods.get((cls, name), name)
        if original.startswith(APP_PREFIX) or not args.mapping:
            rows.append((size, label))
    rows.sort(reverse=True)

    print("largest %s methods in %s:" % ("app" if args.mapping else "(unmapped)", os.path.basename(args.apk)))
    for size, label in rows[:args.top]:
        flag = "  OVER" if size >= args.limit else ""
        print("  %6d  %s%s" % (size, label, flag))
    over = [row for row in rows if row[0] >= args.limit and (args.mapping or APP_PREFIX in row[1])]
    if over:
        print("\n%d method(s) at or over %d code units: ART will interpret them." % (len(over), args.limit))
        return 1
    headroom = args.limit - rows[0][0] if rows else args.limit
    print("\nall under %d; the largest has %d units of headroom." % (args.limit, headroom))
    return 0


if __name__ == "__main__":
    sys.exit(main())
