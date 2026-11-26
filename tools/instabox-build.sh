#!/usr/bin/env bash
# 在 instabox 盒子（默认 pipilot）上跑单测 + release 构建，然后把 APK 拉回本地。
# 用法: tools/instabox-build.sh [额外的 gradle 参数，例如 --offline]
# 详见 INSTABOX.md。
set -euo pipefail

BOX=${INSTABOX_BOX:-pipilot}
DEST=/data/workspace/pipilot
SDK=/data/android-sdk
APK_REL=app/build/outputs/apk/release/app-release.apk

ROOT=$(cd "$(dirname "$0")/.." && pwd)
cd "$ROOT"

BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [ "$BRANCH" = "HEAD" ]; then
  echo "当前是 detached HEAD，先切到分支再同步" >&2
  exit 1
fi

TMPD=$(mktemp -d)
trap 'rm -rf "$TMPD"' EXIT

echo "== 同步 git 历史（含删除/改名）"
git bundle create "$TMPD/sync.bundle" "refs/heads/$BRANCH" >/dev/null
instabox cp "$TMPD/sync.bundle" "$BOX:/tmp/pipilot-sync.bundle" >/dev/null
instabox ssh "$BOX" -- "cd $DEST && git fetch -q -f /tmp/pipilot-sync.bundle 'refs/heads/$BRANCH:refs/instabox/sync' && git reset -q --hard refs/instabox/sync"

echo "== 同步未提交改动"
instabox push "$BOX" . /data/workspace --all

echo "== 编译 + 单测"
instabox ssh "$BOX" -- "cd $DEST && ANDROID_HOME=$SDK /usr/local/bin/gradle $* :app:testDebugUnitTest assembleRelease"

echo "== 取回 APK"
mkdir -p "$(dirname "$APK_REL")"
instabox cp "$BOX:$DEST/$APK_REL" "$APK_REL" >/dev/null
ls -l "$APK_REL"
