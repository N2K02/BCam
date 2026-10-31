#!/usr/bin/env bash
# Pure shell regression test: no internet or GitHub credentials required.
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
resolver="$script_dir/latest-public-version.sh"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

cat > "$tmp/gh" <<'FAKE_GH'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == 'api --paginate repos/N2K02/n2k02.github.io/git/matching-refs/tags/bcam-v --jq .[].ref' ]] || exit 4
[[ "${SIMULATE_API_FAILURE:-0}" != "1" ]] || exit 5
cat "$PUBLIC_REFS_FIXTURE"
FAKE_GH
chmod +x "$tmp/gh"

# The real public website contains other apps' releases, so the old first-100
# search silently missed VRec v4.5. Check all matching refs, not the first page.
for major in $(seq 1 14); do
  for minor in $(seq 0 9); do
    printf 'refs/tags/bcam-v%s.%s\n' "$major" "$minor"
  done
done > "$tmp/many-refs"
printf '%s\n' \
  'refs/tags/bcam-v15.0-build-240' \
  'refs/tags/bcam-v15.0' \
  'refs/tags/bcam-v999.99' \
  'refs/tags/pcl-v99.9' \
  'refs/tags/bcam-experimental-pr-41-run-1000' >> "$tmp/many-refs"

result=$(PATH="$tmp:$PATH" PUBLIC_REFS_FIXTURE="$tmp/many-refs" bash "$resolver" bcam)
[[ "$result" == "v15.0" ]] || { echo "Wrong highest published version: $result"; exit 1; }

printf '%s\n' 'refs/tags/bcam-v4.5' 'refs/tags/bcam-v4.2' > "$tmp/current-refs"
result=$(PATH="$tmp:$PATH" PUBLIC_REFS_FIXTURE="$tmp/current-refs" bash "$resolver" bcam)
[[ "$result" == "v4.5" ]] || { echo "Expected v4.5, got $result"; exit 1; }

: > "$tmp/empty-refs"
result=$(PATH="$tmp:$PATH" PUBLIC_REFS_FIXTURE="$tmp/empty-refs" bash "$resolver" bcam)
[[ -z "$result" ]] || { echo "Expected empty for a new app"; exit 1; }

if PATH="$tmp:$PATH" PUBLIC_REFS_FIXTURE="$tmp/current-refs" SIMULATE_API_FAILURE=1 \
    bash "$resolver" bcam >/dev/null 2>&1; then
  echo "API failures must fail closed instead of guessing a release number"
  exit 1
fi
if PATH="$tmp:$PATH" PUBLIC_REFS_FIXTURE="$tmp/current-refs" \
    bash "$resolver" '../wrong' >/dev/null 2>&1; then
  echo "Invalid slugs must be rejected"
  exit 1
fi
echo 'Public release version resolver regression tests passed'
