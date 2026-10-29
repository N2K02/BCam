#!/usr/bin/env bash
# Resolve the latest published stable version from the complete public tag namespace.
# The shared website has more than 100 releases across apps: a one-page releases
# query can silently return an older version for the selected app.
set -euo pipefail

slug="${1:?Usage: latest-public-version.sh app-slug}"
if [[ ! "$slug" =~ ^[a-z0-9]+$ ]]; then
  echo "::error::Invalid app slug: $slug" >&2
  exit 1
fi

repo="N2K02/n2k02.github.io"
refs=$(gh api --paginate "repos/$repo/git/matching-refs/tags/$slug-v" --jq '.[].ref') || {
  echo "::error::Unable to verify public $slug release tags; refusing to guess a version." >&2
  exit 1
}

# Both version-only tags and historical -build-N tags identify the same stable
# version. Restrict matches to one-decimal release numbers (e.g. v4.9, v5.0).
printf '%s\n' "$refs" |
  sed -nE "s|^refs/tags/$slug-v([0-9]+[.][0-9])(-build-[0-9]+)?$|v\\1|p" |
  sort -V | tail -1
