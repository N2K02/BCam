#!/usr/bin/env python3
"""Consistent next-stable experimental labels, with counters restarting at 1 per version.

Keep internal experimental-pr-*-run-* GitHub tags for backwards compatibility.
Set VERSION, VERSION_SUFFIX, APK_NAME and EXPERIMENTAL_TITLE via GITHUB_ENV.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

TAG = re.compile(r"^(?:sdcleaner-)?v?(\d+)\.(\d)$", re.I)
EXPERIMENT = re.compile(r"^(.*?) (\d+\.\d)-experimental\.(\d+)$", re.I)
LEGACY = re.compile(r"^experimental-pr-(\d+)-run-(\d+)$", re.I)
MAX_PAGES = 20

def next_base(stable_tag):
    match = TAG.fullmatch(stable_tag)
    if match is None:
        raise ValueError("latest stable tag has unsupported version format: " + stable_tag)
    step = int(match.group(1)) * 10 + int(match.group(2)) + 1
    return f"{step // 10}.{step % 10}"

def latest_sequence(releases, app, base, run, pr):
    current = 0
    existing = None
    for item in releases:
        if not isinstance(item, dict) or item.get("draft") or not item.get("prerelease"):
            continue
        title = (item.get("name") or "").strip()
        match = EXPERIMENT.fullmatch(title)
        if match and match.group(1).lower() == app.lower() and match.group(2) == base:
            number = int(match.group(3))
            current = max(current, number)
            tag_match = LEGACY.fullmatch(item.get("tag_name") or "")
            if tag_match and int(tag_match.group(1)) == pr and int(tag_match.group(2)) == run:
                existing = number
    return existing if existing is not None else current + 1

def api(repo, path):
    req = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/{path}",
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "N2K02-APK-Release-Naming",
            **({"Authorization": "Bearer " + os.environ["GH_TOKEN"]} if os.environ.get("GH_TOKEN") else {}),
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            return json.load(response)
    except urllib.error.HTTPError as error:
        if error.code == 404 and path == "releases/latest":
            return None
        raise

def naming(repo, app, run, pr):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repo):
        raise ValueError("invalid repository")
    if not re.fullmatch(r"[A-Za-z][A-Za-z0-9_.-]{0,60}", app):
        raise ValueError("invalid app name")
    if not 0 < run < 2_000_000_000 or not 0 < pr < 2_000_000_000:
        raise ValueError("invalid GitHub run or PR number")
    latest = api(repo, "releases/latest")
    base = next_base(latest["tag_name"]) if latest else "1.0"
    releases = []
    for page in range(1, MAX_PAGES + 1):
        batch = api(repo, f"releases?per_page=100&page={page}")
        if not isinstance(batch, list):
            raise ValueError("unexpected releases response")
        releases.extend(batch)
        if len(batch) < 100:
            break
    else:
        raise ValueError("too many releases to safely calculate experimental sequence")
    sequence = latest_sequence(releases, app, base, run, pr)
    if sequence > 99999:
        raise ValueError("experimental sequence exhausted")
    return {
        "VERSION": base,
        "VERSION_SUFFIX": f"-experimental.{sequence}",
        "APK_NAME": f"{app}-{base}-experimental.{sequence}",
        "EXPERIMENTAL_TITLE": f"{app} {base}-experimental.{sequence}",
        "EXPERIMENTAL_NUMBER": str(sequence),
    }

def tests():
    assert next_base("v8.3") == "8.4"
    assert next_base("sdcleaner-v2.9") == "3.0"
    assert latest_sequence([], "VREC", "8.4", 5, 1) == 1
    releases = [{"name": "VREC 8.4-experimental.2", "tag_name": "experimental-pr-1-run-5", "prerelease": True},
                {"name": "VREC 8.4-experimental.3", "tag_name": "experimental-pr-1-run-6", "prerelease": True},
                {"name": "VREC 8.3-experimental.9", "tag_name": "experimental-pr-1-run-4", "prerelease": True}]
    assert latest_sequence(releases, "VREC", "8.4", 7, 1) == 4
    assert latest_sequence(releases, "VREC", "8.4", 5, 1) == 2
    assert latest_sequence(releases, "VREC", "8.5", 7, 1) == 1

if __name__ == "__main__":
    try:
        tests()
        if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
            print("release naming policy tests passed")
        elif len(sys.argv) == 5:
            data = naming(sys.argv[1], sys.argv[2], int(sys.argv[3]), int(sys.argv[4]))
            dest = os.environ.get("GITHUB_ENV")
            if not dest:
                raise ValueError("GITHUB_ENV is not set")
            with open(dest, "a", encoding="utf-8") as f:
                for key, value in data.items():
                    f.write(f"{key}={value}\n")
            print(data["EXPERIMENTAL_TITLE"])
        else:
            raise ValueError("usage: experimental_naming.py OWNER/REPO APP RUN PR | --self-test")
    except Exception as exc:
        print("::error title=Experimental release naming::" + str(exc), file=sys.stderr)
        sys.exit(1)
