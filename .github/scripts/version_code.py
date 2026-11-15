#!/usr/bin/env python3
"""Allocate Android version codes above every previously published APK build."""
import json
import os
import re
import sys
import urllib.request

MAX_ANDROID_CODE = 2_100_000_000
VERSION_CODE = re.compile(r"(?im)^\s*Version code:\s*(\d+)\s*$")
EXPERIMENTAL_TAG = re.compile(r"^experimental-pr-\d+-run-(\d+)$", re.I)

def published_codes(releases):
    for release in releases:
        if release.get("draft"):
            continue
        body = release.get("body") or ""
        for match in VERSION_CODE.finditer(body):
            yield int(match.group(1))
        tag = release.get("tag_name") or ""
        match = EXPERIMENTAL_TAG.fullmatch(tag)
        if match:
            yield 100_000 + int(match.group(1))

def allocate(releases, run_number):
    if not 0 < run_number < MAX_ANDROID_CODE - 100_000:
        raise ValueError("invalid GitHub run number")
    code = max(100_000 + run_number, max(published_codes(releases), default=0) + 1)
    if code > MAX_ANDROID_CODE:
        raise ValueError("Android versionCode limit reached")
    return code

def get_releases(repository):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("invalid repository")
    headers = {"Accept": "application/vnd.github+json", "User-Agent": "N2K02-Android-CI"}
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if token:
        headers["Authorization"] = "Bearer " + token
    result = []
    for page in range(1, 21):
        url = "https://api.github.com/repos/" + repository + "/releases?per_page=100&page=" + str(page)
        request = urllib.request.Request(url, headers=headers)
        with urllib.request.urlopen(request, timeout=20) as response:
            items = json.load(response)
        if not isinstance(items, list):
            raise ValueError("unexpected GitHub Releases response")
        result.extend(items)
        if len(items) < 100:
            return result
    raise ValueError("more than 2000 releases: refusing incomplete version-code scan")

def self_test():
    assert allocate([], 5) == 100005
    assert allocate([{"tag_name": "experimental-pr-4-run-125", "body": ""}], 90) == 100126
    assert allocate([{"tag_name": "v2.3", "body": "Version code: 500700"}], 130) == 500701
    assert allocate([{"tag_name": "v1.0", "body": "Version code: 2000000", "draft": True}], 9) == 100009
    assert allocate([{"tag_name": "v2.1", "body": "Version code: 100200\nVersion code: 100250"}], 80) == 100251

if __name__ == "__main__":
    try:
        if len(sys.argv) == 2 and sys.argv[1] == "--self-test":
            self_test()
        elif len(sys.argv) == 3:
            self_test()
            print(allocate(get_releases(sys.argv[1]), int(sys.argv[2])))
        else:
            raise ValueError("usage: version_code.py OWNER/REPO RUN_NUMBER | --self-test")
    except Exception as exc:
        print("::error title=Android version code allocation::" + str(exc), file=sys.stderr)
        sys.exit(1)
