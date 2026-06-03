#!/usr/bin/env bash
set -euo pipefail
REPO_ZIP_URL="${1:-https://github.com/EasyDarwin/EasyPlayer-RTSP-Android/archive/refs/heads/dev.zip}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TMP="$ROOT/.tmp_easyplayer_import"
ZIP="$TMP/easyplayer-dev.zip"
EXTRACT="$TMP/extract"
TARGET="$ROOT/easyplayer-android/src/main"

rm -rf "$TMP"
mkdir -p "$EXTRACT"

echo "Download: $REPO_ZIP_URL"
if command -v curl >/dev/null 2>&1; then
  curl -L "$REPO_ZIP_URL" -o "$ZIP"
elif command -v wget >/dev/null 2>&1; then
  wget "$REPO_ZIP_URL" -O "$ZIP"
else
  echo "需要 curl 或 wget" >&2
  exit 1
fi

unzip -q "$ZIP" -d "$EXTRACT"
LIBRARY_MAIN="$(find "$EXTRACT" -type d -path '*/EasyPlayer-RTSP-Android-*/library/src/main' | head -n 1)"
if [[ -z "$LIBRARY_MAIN" ]]; then
  echo "未找到官方 library/src/main，请检查下载包结构。" >&2
  exit 1
fi

rm -rf "$TARGET"
mkdir -p "$TARGET"
cp -R "$LIBRARY_MAIN"/* "$TARGET"/

# 官方老代码使用 android.support.annotation，现代工程统一替换为 androidx.annotation
find "$TARGET" -name '*.java' -type f -print0 | xargs -0 sed -i 's/android\.support\.annotation/androidx.annotation/g'

python3 "$ROOT/tools/verify_easyplayer_official.py"
echo "EasyPlayer 官方源码与 so 已导入完成。"
