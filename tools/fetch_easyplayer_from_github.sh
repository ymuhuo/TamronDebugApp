#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")/.." && pwd)"
THIRD_PARTY_DIR="$ROOT_DIR/third_party"
REPO_DIR="$THIRD_PARTY_DIR/EasyPlayer-RTSP-Android"

mkdir -p "$THIRD_PARTY_DIR"

if [ -d "$REPO_DIR/.git" ]; then
  git -C "$REPO_DIR" pull --ff-only
else
  git clone --depth 1 --branch dev https://github.com/EasyDarwin/EasyPlayer-RTSP-Android.git "$REPO_DIR"
fi

cat <<MSG

官方 EasyPlayer 已获取到：
$REPO_DIR

官方工程的 library 模块位于：
$REPO_DIR/library

注意：官方模块较旧，可能包含 Android support 依赖、旧 compileSdk 和 native so。
建议先保留当前 :easyplayer-android 兼容模块，确认官方 library 能独立编译后，再按需替换。
MSG
