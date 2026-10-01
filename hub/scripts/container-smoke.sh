#!/bin/sh
# 容器冒煙測試：建置映像 → 啟動容器 → healthcheck → 密碼登入 → git push（token）→ 讀回 API／clone → 重啟後資料仍在。
# 用法：hub/scripts/container-smoke.sh            （自動偵測 podman 或 docker）
# 環境變數：
#   ENGINE=podman|docker   容器引擎（預設自動偵測）
#   REPOS_DIR              含 <維度>/ 目錄（已 wgit init／commit 的維度 repo）的目錄；
#                          沒設就用 WORLD_DIR（Minecraft 世界資料夾，預設 core 的 26.2 測試 fixture），
#                          複製到暫存目錄後以 wgit init 建立（需先 ./gradlew :cli:fatJar）。
#   PORT                   主機埠（預設 8094，綁 127.0.0.1）
#   DB=sqlite|postgres     資料庫後端（預設 sqlite；postgres 會另起 postgres:16-alpine 容器並用使用者自訂 network 連線）
#   SKIP_BUILD=1           使用既有映像 localhost/worldgit-hub:smoke
#   PG_IMAGE               PostgreSQL 映像（預設 docker.io/library/postgres:16-alpine）
#   LOCK                   重負載鎖檔（預設 .work/bench.lock；有 flock 時建置用它包住）
set -eu
ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)
ENGINE=${ENGINE:-$(command -v podman >/dev/null 2>&1 && echo podman || echo docker)}
PORT=${PORT:-8094}
IMAGE=localhost/worldgit-hub:smoke
NAME=worldgit-hub-smoke
VOL=worldgit-hub-smoke-data
DB=${DB:-sqlite}
PG_IMAGE=${PG_IMAGE:-docker.io/library/postgres:16-alpine}
PGNAME=worldgit-hub-smoke-pg
NET=worldgit-hub-smoke-net
TOKEN=smoke-token-$$
LOCK=${LOCK:-$ROOT/.work/bench.lock}
TMP=$(mktemp -d)
step() { printf '\n== %s\n' "$*"; }
cleanup() {
  $ENGINE rm -f "$NAME" "$PGNAME" >/dev/null 2>&1 || true
  $ENGINE volume rm -f "$VOL" >/dev/null 2>&1 || true
  $ENGINE network rm "$NET" >/dev/null 2>&1 || true
  rm -rf "$TMP"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

if [ -z "${REPOS_DIR:-}" ]; then
  WORLD_DIR=${WORLD_DIR:-$ROOT/core/src/test/resources/fixtures/26.2/world}
  step "以 wgit 建立測試 repo（$WORLD_DIR）"
  cp -r "$WORLD_DIR" "$TMP/world"
  "$ROOT/wgit" -w "$TMP/world" init
  REPOS_DIR=$(dirname "$(find "$TMP" -type d -path '*/.worldgit/*' -name 'minecraft.overworld' | head -1)")
fi
[ -d "$REPOS_DIR/minecraft.overworld" ] || { echo "找不到維度 repo：$REPOS_DIR/minecraft.overworld（設定 REPOS_DIR 或 WORLD_DIR）" >&2; exit 2; }

if [ -z "${SKIP_BUILD:-}" ]; then
  step "建置映像（$ENGINE build）"
# podman 預設 OCI 格式會丟掉 Containerfile 的 HEALTHCHECK；需要 --format docker 才會保留。
FMT=
[ "$ENGINE" != podman ] || FMT="--format docker"
  if command -v flock >/dev/null 2>&1 && [ -e "$LOCK" ]; then
    timeout 900 flock "$LOCK" $ENGINE build $FMT -f "$ROOT/hub/Containerfile" -t "$IMAGE" "$ROOT"
  else
    timeout 900 $ENGINE build $FMT -f "$ROOT/hub/Containerfile" -t "$IMAGE" "$ROOT"
  fi
fi

step "啟動容器（127.0.0.1:$PORT → 8080）"
# 用檔案掛載 secrets，inspect 只會顯示路徑；映像內 uid 10001 需要可讀。
printf '%s' "$TOKEN" > "$TMP/admin-token"
printf '%s' smoke-password-1 > "$TMP/admin-password"
chmod 644 "$TMP/admin-token" "$TMP/admin-password"
$ENGINE rm -f "$NAME" >/dev/null 2>&1 || true
DBENV=
if [ "$DB" = postgres ]; then
  step "啟動 PostgreSQL（$PG_IMAGE，network $NET）"
  $ENGINE network create "$NET" >/dev/null
  $ENGINE run -d --name "$PGNAME" --network "$NET" -e POSTGRES_PASSWORD=smoke-pg-pass -e POSTGRES_DB=hub "$PG_IMAGE" >/dev/null
  i=0
  until $ENGINE exec "$PGNAME" pg_isready -U postgres -d hub >/dev/null 2>&1; do
    i=$((i+1)); [ $i -gt 60 ] && { echo "PostgreSQL 未就緒" >&2; exit 1; }
    sleep 1
  done
  sleep 2
  # 直接用容器 IP 連線：部分環境（例如本機 netavark 的 aardvark-dns）的自訂 network 名稱解析不可用
  PGIP=$($ENGINE inspect --format "{{(index .NetworkSettings.Networks \"$NET\").IPAddress}}" "$PGNAME")
  [ -n "$PGIP" ] || { echo "取不到 PostgreSQL 容器 IP" >&2; exit 1; }
  DBENV="--network $NET -e SPRING_DATASOURCE_URL=jdbc:postgresql://$PGIP:5432/hub -e SPRING_DATASOURCE_USERNAME=postgres -e SPRING_DATASOURCE_PASSWORD=smoke-pg-pass -e SPRING_DATASOURCE_DRIVER_CLASS_NAME=org.postgresql.Driver"
fi
# shellcheck disable=SC2086
$ENGINE run -d --name "$NAME" $DBENV -p "127.0.0.1:$PORT:8080" -v "$VOL:/data" \
  -v "$TMP/admin-token:/run/secrets/admin-token:ro,Z" -v "$TMP/admin-password:/run/secrets/admin-password:ro,Z" \
  -e WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN_FILE=/run/secrets/admin-token \
  -e WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD_FILE=/run/secrets/admin-password "$IMAGE" >/dev/null
URL=http://127.0.0.1:$PORT
wait_ready() {
  i=0
  until curl -fsS "$URL/actuator/health" >/dev/null 2>&1; do
    i=$((i+1)); [ $i -gt 120 ] && { echo "Hub 未在 120 秒內就緒" >&2; $ENGINE logs "$NAME" | tail -40; exit 1; }
    sleep 1
  done
}
wait_healthy() {
  i=0
  until [ "$($ENGINE inspect --format '{{.State.Health.Status}}' "$NAME" 2>/dev/null)" = healthy ]; do
    i=$((i+1)); [ $i -gt 120 ] && { echo "容器 healthcheck 未在 120 秒內轉 healthy" >&2; $ENGINE inspect --format '{{json .State.Health}}' "$NAME" >&2; exit 1; }
    sleep 1
  done
}
wait_ready
echo "就緒（${i}s）；uid=$($ENGINE exec "$NAME" id -u)"
[ "$($ENGINE exec "$NAME" id -u)" != 0 ] || { echo "容器以 root 執行" >&2; exit 1; }
$ENGINE inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$NAME" > "$TMP/env"
if grep -Eq '^WORLDGIT_HUB_BOOTSTRAP_ADMIN_(PASSWORD|TOKEN)=' "$TMP/env"; then
  echo "管理員憑證不應以明文環境變數傳入" >&2; exit 1
fi
grep -q '^WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD_FILE=' "$TMP/env"
grep -q '^WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN_FILE=' "$TMP/env"
echo "bootstrap secrets 使用檔案；inspect 未含明文密碼／token"

step "容器 healthcheck（HEALTHCHECK 指令實際回報 healthy）"
wait_healthy
echo "healthy（${i}s）"

step "密碼登入（證明 *_FILE 的 bootstrap 密碼確實被讀到）"
login() {
  curl -sS -o "$TMP/login.json" -w '%{http_code}' -H 'Content-Type: application/json' \
    -d "{\"username\":\"admin\",\"password\":\"$1\"}" "$URL/api/v1/auth/login"
}
[ "$(login smoke-password-1)" = 200 ] || { echo "以檔案密碼登入失敗" >&2; cat "$TMP/login.json" >&2; exit 1; }
SESSION=$(sed -n 's/.*"token":"\([^"]*\)".*/\1/p' "$TMP/login.json")
[ -n "$SESSION" ] || { echo "登入回應沒有 token" >&2; exit 1; }
curl -fsS -H "Authorization: Bearer $SESSION" "$URL/api/v1/me" | grep -q '"admin"' || { echo "/me 沒有回 admin" >&2; exit 1; }
[ "$(login wrong-password)" = 401 ] || { echo "錯誤密碼沒有被拒絕" >&2; exit 1; }
echo "登入成功、錯誤密碼 401"

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
step "重啟容器後資料仍在（$DB）"
$ENGINE restart "$NAME" >/dev/null
sleep 2
wait_ready
N2=$(curl -fsS -H "Authorization: Bearer $TOKEN" "$API/snapshots" | grep -o '"snapshot"' | wc -l)
[ "$N2" = "$N" ] || { echo "重啟後 snapshots $N2 != $N" >&2; exit 1; }
[ "$(login smoke-password-1)" = 200 ] || { echo "重啟後登入失敗" >&2; exit 1; }
rm -rf "$TMP/clone2"
git clone -q "http://admin:$TOKEN@127.0.0.1:$PORT/git/admin/smoke/minecraft.overworld.git" "$TMP/clone2"
echo "重啟後 snapshots=$N2、登入與 clone 正常"
step "資料集中在 /data"
$ENGINE exec "$NAME" sh -c 'ls /data; du -sh /data'
echo; echo "冒煙測試通過"
