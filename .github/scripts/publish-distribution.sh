#!/usr/bin/env bash
set -euo pipefail
: "${GH_TOKEN:?MRADIO_DISTRIBUTION_TOKEN secret must be configured}"
: "${APP:?APP required}"
: "${VERSION:?VERSION required}"
: "${VERSION_CODE:?VERSION_CODE required}"
[[ "$VERSION_CODE" =~ ^[1-9][0-9]*$ ]] || exit 1
TARGET="N2K02/n2k02.github.io"
SLUG=$(echo "$APP" | tr '[:upper:]' '[:lower:]')
APK="$APP-$VERSION.apk"
test -s "$APK"
[[ "$VERSION" =~ ^[0-9]+\.[0-9]$ ]] || exit 1
CHANNEL=stable
TAG="$SLUG-v$VERSION"
MANIFEST="$SLUG/updates/stable.json"
if [ "$GITHUB_EVENT_NAME" = "pull_request" ]; then
  : "${PR_NUMBER:?PR_NUMBER required}"
  CHANNEL=experimental
  TAG="$SLUG-experimental-pr-$PR_NUMBER-run-$GITHUB_RUN_NUMBER"
  MANIFEST="$SLUG/updates/experimental.json"
fi
SHA=$(sha256sum "$APK" | cut -d' ' -f1)
SIZE=$(stat -c %s "$APK")
if gh release view "$TAG" --repo "$TARGET" >/dev/null 2>&1; then
  echo "::error::Release tag already exists; refusing to overwrite a signed binary"
  exit 1
fi
gh release create "$TAG" "$APK" --repo "$TARGET" --target main --title "$APP $VERSION ($CHANNEL)" --notes-file release-notes.md --latest=false
URL="https://github.com/$TARGET/releases/download/$TAG/$APK"
NOTES=$( { grep '^- ' release-notes.md || true; } | head -10 | sed 's/^- //' | jq -R -s 'split("\n") | map(select(length > 0))')
jq -n --arg channel "$CHANNEL" --arg tag "$TAG" --arg version "$VERSION" --arg apkName "$APK" --arg apkUrl "$URL" --arg pageUrl "https://github.com/$TARGET/releases/tag/$TAG" --arg sha256 "$SHA" --argjson versionCode "$VERSION_CODE" --argjson apkSize "$SIZE" --argjson notes "$NOTES" '{channel:$channel,tag:$tag,versionName:$version,versionCode:$versionCode,apkName:$apkName,apkUrl:$apkUrl,apkSize:$apkSize,sha256:$sha256,pageUrl:$pageUrl,notes:$notes}' > manifest.json
CURRENT_JSON=$(gh api "repos/$TARGET/contents/$MANIFEST" --jq .content 2>/dev/null | tr -d '\n' | base64 -d 2>/dev/null || true)
if [ -n "$CURRENT_JSON" ]; then
  OLD=$(jq -r '.versionCode // 0' <<<"$CURRENT_JSON")
  if [[ "$OLD" =~ ^[0-9]+$ ]] && (( OLD >= VERSION_CODE )); then
    echo "Skipping manifest update: equal or newer build exists"
    exit 0
  fi
fi
ENC=$(base64 -w0 manifest.json)
FEED_REPO="${PUBLIC_REPO:-${TARGET:-}}"
FEED_PATH="${PATH_IN_REPO:-${MANIFEST:-}}"
FEED_SLUG="${FEED_PATH%%/*}"
FEED_BRANCH="publish-feed-${FEED_SLUG}-${CHANNEL}"
APP_LABEL="${APP:-${APK_NAME:-$FEED_SLUG}}"
[[ -n "$FEED_REPO" && -n "$FEED_PATH" ]] || { echo "::error::Missing public feed target" >&2; exit 1; }

# Keep one reviewable feed PR per app and channel. Never write the protected main branch.
if ! gh api "repos/$FEED_REPO/git/ref/heads/$FEED_BRANCH" >/dev/null 2>&1; then
  MAIN_SHA=$(gh api "repos/$FEED_REPO/git/ref/heads/main" --jq '.object.sha')
  gh api --method POST "repos/$FEED_REPO/git/refs" \
    -f "ref=refs/heads/$FEED_BRANCH" -f "sha=$MAIN_SHA" >/dev/null || {
      gh api "repos/$FEED_REPO/git/ref/heads/$FEED_BRANCH" >/dev/null
    }
fi

SKIP_MANIFEST_UPDATE=false
CURRENT_JSON=$(gh api "repos/$FEED_REPO/contents/$FEED_PATH?ref=$FEED_BRANCH" --jq '.content' 2>/dev/null | tr -d '\n' | base64 -d 2>/dev/null || true)
if [[ -n "$CURRENT_JSON" ]]; then
  BRANCH_CODE=$(jq -r '.versionCode // 0' <<<"$CURRENT_JSON")
  if [[ "$BRANCH_CODE" =~ ^[0-9]+$ ]] && (( BRANCH_CODE >= VERSION_CODE )); then
    echo "Equal or newer feed manifest is already on $FEED_BRANCH; keeping it."
    SKIP_MANIFEST_UPDATE=true
  fi
fi

if [[ "$SKIP_MANIFEST_UPDATE" != true ]]; then
  for attempt in 1 2 3 4 5; do
    CURRENT_JSON=$(gh api "repos/$FEED_REPO/contents/$FEED_PATH?ref=$FEED_BRANCH" --jq '.content' 2>/dev/null | tr -d '\n' | base64 -d 2>/dev/null || true)
    if [[ -n "$CURRENT_JSON" ]]; then
      BRANCH_CODE=$(jq -r '.versionCode // 0' <<<"$CURRENT_JSON")
      if [[ "$BRANCH_CODE" =~ ^[0-9]+$ ]] && (( BRANCH_CODE >= VERSION_CODE )); then
        echo "A newer feed manifest won the publication race; keeping it."
        SKIP_MANIFEST_UPDATE=true
        break
      fi
    fi
    CURRENT_SHA=$(gh api "repos/$FEED_REPO/contents/$FEED_PATH?ref=$FEED_BRANCH" --jq '.sha' 2>/dev/null || true)
    ARGS=(--method PUT "repos/$FEED_REPO/contents/$FEED_PATH" \
      -f "message=Prepare $APP_LABEL $VERSION ($CHANNEL) update feed" \
      -f "content=$(base64 -w0 manifest.json)" -f "branch=$FEED_BRANCH")
    [[ -z "$CURRENT_SHA" ]] || ARGS+=(-f "sha=$CURRENT_SHA")
    if gh api "${ARGS[@]}" >/dev/null; then
      break
    fi
    [[ "$attempt" -lt 5 ]] || { echo "::error::Unable to update feed branch after retries" >&2; exit 1; }
    sleep "$((attempt * 2))"
  done
fi

EXISTING_PR=$(gh pr list --repo "$FEED_REPO" --state open --head "N2K02:$FEED_BRANCH" --json number --jq '.[0].number // empty')
if [[ -z "$EXISTING_PR" ]]; then
  if gh pr create --repo "$FEED_REPO" --base main --head "$FEED_BRANCH" \
    --title "Publish $APP_LABEL $VERSION ($CHANNEL) update feed" \
    --body "Updates $FEED_PATH to reference the signed APK already published for $APP_LABEL $VERSION ($CHANNEL). The app will discover this release after the feed change is merged."; then
    echo "Created feed pull request for $APP_LABEL $CHANNEL."
  else
    echo "::error::Could not open the feed PR. The distribution token needs Contents: write and Pull requests: write access to $FEED_REPO." >&2
    exit 1
  fi
else
  echo "Updated feed pull request #$EXISTING_PR for $APP_LABEL $CHANNEL."
fi
