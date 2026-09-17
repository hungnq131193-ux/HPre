#!/usr/bin/env bash
# check-newpipe-update.sh — auto-release HPre.
# Triggers:
#   A) origin/main has commits on top of the newest v* tag (i.e. the tag is merged into main
#      and main moved on) -> build main, bump patch, release.
#   B) NewPipeExtractor upstream published a newer version than the newest tag pins
#      -> build that tag, bump extractor + patch, release.
# Manual: BASE_REF_OVERRIDE=<git ref> forces a release built from that ref.
# Safety: single-run lock, dirty-tree abort, build failure restores files, no downgrade
# (main is only released when it already contains the newest release tag).
set -euo pipefail
export HOME=/root PATH=/usr/local/bin:/usr/bin:/bin:/usr/local/sbin:/usr/sbin:/sbin

REPO=/opt/HPre
GITHUB_REPO=hungnq131193-ux/HPre
cd "$REPO"

exec 9>/tmp/hpre-newpipe.lock
flock -n 9 || exit 0

# Build daemons hold ~4.7 GB idle on this box; always stop them when done.
trap './gradlew --stop >/dev/null 2>&1 || true; pkill -f "KotlinCompileD[a]emon" || true' EXIT

if [ -n "$(git status --porcelain --untracked-files=no)" ]; then
    echo "dirty tree, skip"
    exit 1
fi

git fetch --tags --quiet origin

TAG_MAX=$(git tag -l 'v*' | sort -V | tail -1)
TAG_COMMIT=$(git rev-list -n1 "$TAG_MAX")
MAIN=$(git rev-parse origin/main)
NP_NEW=$(gh api repos/TeamNewPipe/NewPipeExtractor/releases/latest -q .tag_name | sed 's/^v//')
NP_PINNED=$(git show "$TAG_MAX:gradle/libs.versions.toml" | grep -oP 'newpipeExtractor = "v\K[^"]+')

if [ -n "${BASE_REF_OVERRIDE:-}" ]; then
    BASE_REF=$BASE_REF_OVERRIDE
    REASON="manual release ($BASE_REF_OVERRIDE)"
elif git merge-base --is-ancestor "$TAG_MAX" origin/main && [ "$MAIN" != "$TAG_COMMIT" ] \
    && ! git diff --quiet "$TAG_MAX" origin/main -- app/ gradle/; then
    # Only release when app/build inputs actually changed vs the tag — merge or
    # script-only commits on main must not produce a duplicate release.
    BASE_REF=origin/main
    REASON="new commits on main"
elif [ "$NP_PINNED" != "$NP_NEW" ]; then
    BASE_REF=$TAG_MAX
    REASON="NewPipeExtractor v$NP_NEW"
else
    echo "nothing to release (main at/behind $TAG_MAX, newpipe $NP_PINNED = latest)"
    exit 0
fi

FILE_VN=$(git show "$BASE_REF:app/build.gradle.kts" | grep -oP 'versionName = "\K[^"]+')
FILE_VC=$(git show "$BASE_REF:app/build.gradle.kts" | grep -oP 'versionCode = \K\d+')
TAG_VN="${TAG_MAX#v}"
TAG_VC=$(git show "$TAG_MAX:app/build.gradle.kts" | grep -oP 'versionCode = \K\d+')
MAX_VN=$(printf '%s\n%s\n' "$FILE_VN" "$TAG_VN" | sort -V | tail -1)
MAX_VC=$(( FILE_VC > TAG_VC ? FILE_VC : TAG_VC ))
NEW_VN=$(awk -F. '{printf "%d.%d.%d", $1, $2, $3 + 1}' <<<"$MAX_VN")
NEW_VC=$((MAX_VC + 1))
TAG="v$NEW_VN"
BRANCH="auto/release-$TAG"

if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null || gh release view "$TAG" >/dev/null 2>&1; then
    echo "$TAG already released"
    exit 0
fi

echo "releasing $TAG from $BASE_REF ($REASON, newpipe $NP_NEW)"
git checkout -B "$BRANCH" "$BASE_REF"
TEST=app/src/test/java/com/hpre/app/BuildConfigurationTest.kt
sed -i "s/newpipeExtractor = \"v[^\"]*\"/newpipeExtractor = \"v$NP_NEW\"/" gradle/libs.versions.toml
sed -i "s/versionCode = [0-9]*/versionCode = $NEW_VC/; s/versionName = \"[^\"]*\"/versionName = \"$NEW_VN\"/" app/build.gradle.kts
sed -i \
    -e "s/fun release_version_is_[0-9_]*_code_[0-9]*_and_shrinks_resources/fun release_version_is_${NEW_VN//./_}_code_${NEW_VC}_and_shrinks_resources/" \
    -e "s/assertEquals(\"[^\"]*\", BuildConfig.VERSION_NAME)/assertEquals(\"$NEW_VN\", BuildConfig.VERSION_NAME)/" \
    -e "s/assertEquals([0-9]*, BuildConfig.VERSION_CODE)/assertEquals($NEW_VC, BuildConfig.VERSION_CODE)/" \
    "$TEST"

trap 'git checkout -- gradle/libs.versions.toml app/build.gradle.kts "$TEST" 2>/dev/null || true' ERR
./gradlew testDebugUnitTest assembleRelease --console=plain
trap - ERR

export GIT_AUTHOR_NAME="hpre-bot" GIT_AUTHOR_EMAIL="hpre-bot@localhost"
export GIT_COMMITTER_NAME="hpre-bot" GIT_COMMITTER_EMAIL="hpre-bot@localhost"
git add gradle/libs.versions.toml app/build.gradle.kts "$TEST"
git commit -m "chore(release): $NEW_VN — $REASON"
git tag "$TAG"
git push origin "$BRANCH" "$TAG"

APK="HPre-$TAG-release.apk"
cp app/build/outputs/apk/release/app-release.apk "/tmp/$APK"
( cd /tmp && sha256sum "$APK" | tr 'a-f' 'A-F' > "$APK.sha256" )
gh release create "$TAG" --repo "$GITHUB_REPO" --title "HPre $NEW_VN" \
    --notes "$REASON. NewPipeExtractor v$NP_NEW." \
    "/tmp/$APK" "/tmp/$APK.sha256"
echo "released $TAG"
