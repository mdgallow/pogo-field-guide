#!/usr/bin/env python3
"""One place to change the version.

version.json is the single source of truth: Gradle reads versionCode / versionName from it, the
in-app updater compares against the copy on the site, and this script stamps the two files that
cannot read it at run time (the page's APP_VERSION label and the service-worker cache name).

    python tools/release.py 2.4.0 "What changed, in one sentence"   bump (versionCode + 1) and stamp
    python tools/release.py --check                                 verify everything agrees (CI runs this)
"""
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
VERSION_FILE = ROOT / "version.json"

# (file, pattern with one group around the stamped value, how the value is derived from version.json)
STAMPS = [
    (ROOT / "index.html", r"const APP_VERSION = '([^']*)';", lambda v: "v" + v["versionName"]),
    (ROOT / "sw.js", r"const CACHE_NAME = '([^']*)';", lambda v: "pogo-companion-" + v["versionName"]),
]


def read_version():
    return json.loads(VERSION_FILE.read_text(encoding="utf-8"))


def read_text(path):
    # newline="" keeps the file's own line endings (the repo has CRLF and LF files).
    with open(path, encoding="utf-8", newline="") as f:
        return f.read()


def check():
    v = read_version()
    problems = []
    if not isinstance(v.get("versionCode"), int) or not re.fullmatch(r"\d+\.\d+\.\d+", str(v.get("versionName", ""))):
        problems.append("version.json needs an integer versionCode and an x.y.z versionName")
    for path, pattern, want in STAMPS:
        found = re.findall(pattern, read_text(path))
        if len(found) != 1:
            problems.append(f"{path.name}: expected exactly one match for {pattern}")
        elif found[0] != want(v):
            problems.append(f"{path.name}: has '{found[0]}', version.json says '{want(v)}' (run tools/release.py)")
    gradle = read_text(ROOT / "android" / "app" / "build.gradle.kts")
    if re.search(r"versionCode\s*=\s*\d+|versionName\s*=\s*\"", gradle):
        problems.append("android/app/build.gradle.kts: hard-coded version; it must read version.json")
    return v, problems


def bump(name, notes):
    v = read_version()
    if not re.fullmatch(r"\d+\.\d+\.\d+", name):
        sys.exit("version must look like 2.4.0")
    if tuple(map(int, name.split("."))) <= tuple(map(int, v["versionName"].split("."))):
        sys.exit(f"{name} is not newer than {v['versionName']}")
    v.update(versionCode=v["versionCode"] + 1, versionName=name, notes=notes)
    VERSION_FILE.write_text(json.dumps(v, ensure_ascii=False) + "\n", encoding="utf-8", newline="\n")
    for path, pattern, want in STAMPS:
        text = read_text(path)
        new, n = re.subn(pattern, lambda m: m.group(0).replace(m.group(1), want(v)), text)
        if n != 1:
            sys.exit(f"{path.name}: expected exactly one match for {pattern}")
        with open(path, "w", encoding="utf-8", newline="") as f:
            f.write(new)
    print(f"version {v['versionName']} (code {v['versionCode']})")


if __name__ == "__main__":
    args = sys.argv[1:]
    if args == ["--check"]:
        version, problems = check()
        if problems:
            sys.exit("Version check failed:\n  " + "\n  ".join(problems))
        print(f"version {version['versionName']} (code {version['versionCode']}) is consistent")
    elif len(args) == 2:
        bump(*args)
        _, problems = check()
        if problems:
            sys.exit("Stamped, but the check still fails:\n  " + "\n  ".join(problems))
    else:
        sys.exit(__doc__)
