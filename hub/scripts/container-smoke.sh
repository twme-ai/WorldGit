#!/bin/sh
# 容器冒煙測試：建置映像 → 啟動容器 → 以 git push（token）推送測試世界 → 以 HTTP 讀回 API／靜態頁。
# 用法：hub/scripts/container-smoke.sh            （自動偵測 podman 或 docker）
# 環境變數：
#   ENGINE=podman|docker   容器引擎（預設自動偵測）
#   REPOS_DIR              含 <維度>/ 目錄（已 wgit init／commit 的維度 repo）的目錄；
#                          預設 .work/hub/e2e/server/.worldgit/world。沒有的話可設 WORLD_DIR（Minecraft 世界資料夾），
#                          腳本會複製到暫存目錄後以 wgit init 建立（需先 ./gradlew :cli:fatJar）。
#   PORT                   主機埠（預設 8094，綁 127.0.0.1）
#   SKIP_BUILD=1           使用既有映像 localhost/worldgit-hub:smoke
#   LOCK                   重負載鎖檔（預設 .work/bench.lock；有 flock 時建置用它包住）
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ENGINE=${ENGINE:-$(command -v podman >/dev/null 2>&1 && echo podman || echo docker)}
PORT=${PORT:-8094}
IMAGE=localhost/worldgit-hub:smoke
NAME=worldgit-hub-smoke
VOL=worldgit-hub-smoke-data
TOKEN=smoke-token-$$
LOCK=${LOCK:-$ROOT/.work/bench.lock}
TMP=$(mktemp -d)
step() { printf '\n== %s\n' "$*"; }
cleanup() {
  $ENGINE rm -f "$NAME" >/dev/null 2>&1 || true
  $ENGINE volume rm -f "$VOL" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [ -z "${REPOS_DIR:-}" ]; then
  if [ -n "${WORLD_DIR:-}" ]; then
    step "以 wgit 建立測試 repo（$WORLD_DIR）"
    cp -r "$WORLD_DIR" "$TMP/world"
    "$ROOT/wgit" -w "$TMP/world" init
    REPOS_DIR=$(dirname "$(find "$TMP" -type d -path '*/.worldgit/*' -name 'minecraft.overworld' | head -1)")
  else
    REPOS_DIR=$ROOT/.work/hub/e2e/server/.worldgit/world
  fi
fi
[ -d "$REPOS_DIR/minecraft.overworld" ] || { echo "找不到維度 repo：$REPOS_DIR/minecraft.overworld（設定 REPOS_DIR 或 WORLD_DIR）" >&2; exit 2; }

if [ -z "${SKIP_BUILD:-}" ]; then
  step "建置映像（$ENGINE build）"
  if command -v flock >/dev/null 2>&1 && [ -e "$LOCK" ]; then
    timeout 900 flock "$LOCK" $ENGINE build -f "$ROOT/hub/Containerfile" -t "$IMAGE" "$ROOT"
  else
    timeout 900 $ENGINE build -f "$ROOT/hub/Containerfile" -t "$IMAGE" "$ROOT"
  fi
fi

step "啟動容器（127.0.0.1:$PORT → 8080）"
# 用檔案掛載 secrets，inspect 只會顯示路徑；映像內 uid 10001 需要可讀。
printf '%s' "$TOKEN" > "$TMP/admin-token"
printf '%s' smoke-password-1 > "$TMP/admin-password"
chmod 644 "$TMP/admin-token" "$TMP/admin-password"
$ENGINE rm -f "$NAME" >/dev/null 2>&1 || true
$ENGINE run -d --name "$NAME" -p "127.0.0.1:$PORT:8080" -v "$VOL:/data" \
  -v "$TMP/admin-token:/run/secrets/admin-token:ro,Z" -v "$TMP/admin-password:/run/secrets/admin-password:ro,Z" \
  -e WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN_FILE=/run/secrets/admin-token \
  -e WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD_FILE=/run/secrets/admin-password "$IMAGE" >/dev/null
URL=http://127.0.0.1:$PORT
i=0
until curl -fsS "$URL/actuator/health" >/dev/null 2>&1; do
  i=$((i+1)); [ $i -gt 120 ] && { echo "Hub 未在 120 秒內就緒" >&2; $ENGINE logs "$NAME" | tail -40; exit 1; }
  sleep 1
done
echo "就緒（${i}s）；uid=$($ENGINE exec "$NAME" id -u)"
[ "$($ENGINE exec "$NAME" id -u)" != 0 ] || { echo "容器以 root 執行" >&2; exit 1; }
$ENGINE inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$NAME" > "$TMP/env"
if grep -Eq '^WORLDGIT_HUB_BOOTSTRAP_ADMIN_(PASSWORD|TOKEN)=' "$TMP/env"; then
  echo "管理員憑證不應以明文環境變數傳入" >&2; exit 1
fi
grep -q '^WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD_FILE=' "$TMP/env"
grep -q '^WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN_FILE=' "$TMP/env"
echo "bootstrap secrets 使用檔案；inspect 未含明文密碼／token"

step "git push（smart HTTP + token）"
for d in "$REPOS_DIR"/*/; do
  dim=$(basename "$d")
  if ! git -C "$d" push -q "http://admin:$TOKEN@127.0.0.1:$PORT/git/admin/smoke/$dim.git" HEAD:refs/heads/main > "$TMP/push.log" 2>&1; then
    cat "$TMP/push.log" >&2; exit 1
  fi
  tail -2 "$TMP/push.log"
done

step "讀回"
API="$URL/api/v1/worlds/admin/smoke"
curl -fsS -H "Authorization: Bearer $TOKEN" "$API" | head -c 300; echo
N=$(curl -fsS -H "Authorization: Bearer $TOKEN" "$API/snapshots" | grep -o '"snapshot"' | wc -l)
echo "snapshots=$N"
[ "$N" -ge 1 ] || { echo "沒有看到 snapshot" >&2; exit 1; }
curl -fsS "$URL/" | grep -q '<div id="app"' && echo "前端 index.html OK"
step "clone 回來驗證"
git clone -q "http://admin:$TOKEN@127.0.0.1:$PORT/git/admin/smoke/minecraft.overworld.git" "$TMP/clone"
git -C "$TMP/clone" log --oneline -3
step "資料集中在 /data"
$ENGINE exec "$NAME" sh -c 'ls /data; du -sh /data'
echo; echo "冒煙測試通過"
