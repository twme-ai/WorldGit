#!/usr/bin/env bash
set -euo pipefail
EXPERIMENT_DIR=$(cd "$(dirname "$0")/.." && pwd)
REPO_DIR=$(cd "$EXPERIMENT_DIR/../.." && pwd)
WORK_DIR="$REPO_DIR/.work/viewer-scale"
mkdir -p "$WORK_DIR"
export TMPDIR="$WORK_DIR"
export npm_config_cache="$REPO_DIR/.work/npm-cache"
export PLAYWRIGHT_BROWSERS_PATH="$REPO_DIR/.work/ms-playwright"
cd "$EXPERIMENT_DIR"
# 本次使用 04 已安裝依賴的複本；全新 checkout 才需要 npm ci。
if [ ! -x node_modules/.bin/vite ]; then
  if [ -d "$REPO_DIR/experiments/04-deepslate/node_modules" ]; then
    cp -a "$REPO_DIR/experiments/04-deepslate/node_modules" "$WORK_DIR/node_modules"
    ln -s ../../.work/viewer-scale/node_modules node_modules
  else
    mkdir -p "$WORK_DIR/node_modules"
    ln -s ../../.work/viewer-scale/node_modules node_modules
    npm ci
  fi
fi
if [ ! -f "$WORK_DIR/pack/atlas.png" ]; then
  if [ -f "$REPO_DIR/.work/assets/26.2/pack/atlas.png" ]; then
    cp -a "$REPO_DIR/.work/assets/26.2/pack" "$WORK_DIR/pack"
  else
    node scripts/prep-assets.mjs 26.2
  fi
fi
if [ ! -f "$WORK_DIR/fonts/NotoSansCJKtc-Regular.otf" ]; then
  mkdir -p "$WORK_DIR/fonts"
  curl -fL --retry 2 https://raw.githubusercontent.com/notofonts/noto-cjk/main/Sans/OTF/TraditionalChinese/NotoSansCJKtc-Regular.otf -o "$WORK_DIR/fonts/NotoSansCJKtc-Regular.otf"
fi
node scripts/texture-metadata.mjs
node_modules/.bin/tsc --noEmit
flock "$REPO_DIR/.work/bench.lock" node_modules/.bin/tsx scripts/prepare.ts > "$WORK_DIR/prepare.log" 2>&1
node_modules/.bin/tsx scripts/check.ts > "$WORK_DIR/check.json"
node_modules/.bin/vite build > "$WORK_DIR/build.log" 2>&1
# 包含 Chrome 啟動、網格、飛越、截圖與 GL 後端嘗試；finally 關閉瀏覽器與 Vite。
flock "$REPO_DIR/.work/bench.lock" env BENCH_LOCK_HELD=1 node scripts/bench-browser.mjs | tee "$WORK_DIR/bench.log"
du -sb "$WORK_DIR" "$EXPERIMENT_DIR" > "$WORK_DIR/disk-usage.txt"
