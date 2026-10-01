#!/usr/bin/env bash
# 重跑 Phase 2 Hub 分支／compare；只在 127.0.0.1:18xxx 的暫存 Hub 推送測試 repo。
set -euo pipefail
TASK_ROOT="$(cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$TASK_ROOT"
mkdir -p .work/hub-phase2-h
if [[ ${1:-} != --locked ]]; then
  exec flock .work/bench.lock "$0" --locked
fi
export GRADLE_USER_HOME="$TASK_ROOT/.work/gradle-home"
export npm_config_cache="$TASK_ROOT/.work/npm-cache"
export PLAYWRIGHT_BROWSERS_PATH="$TASK_ROOT/.work/ms-playwright"
TASK_RUN="$TASK_ROOT/.work/hub-phase2-h/run-$(date +%s)"
TASK_PORT=18097
TASK_TOKEN=hub-phase2-acceptance-token
TASK_URL="http://127.0.0.1:$TASK_PORT"
mkdir -p "$TASK_RUN"
printf '%s\n' "$TASK_RUN" > .work/hub-phase2-h/latest-run.txt
npm --prefix hub/web run build > "$TASK_RUN/web-build.log" 2>&1
./gradlew --configure-on-demand --max-workers=1 :hub:bootJar :hub:branchFixture "-Ptarget=$TASK_RUN/fixture" > "$TASK_RUN/build.log" 2>&1
# 可選：重用已有的 Mojang 衍生資源快取，避免重複下載（不放入 git）。
if [[ -d "$TASK_ROOT/.work/hub/run2/data/cache/assets/26.2" ]]; then
  mkdir -p "$TASK_RUN/data/cache/assets"
  cp -a "$TASK_ROOT/.work/hub/run2/data/cache/assets/26.2" "$TASK_RUN/data/cache/assets/"
fi
WORLDGIT_HUB_DATA_DIR="$TASK_RUN/data" WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN="$TASK_TOKEN" \
WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD=acceptance-password-2026 \
  /usr/lib/jvm/java-25-openjdk-amd64/bin/java -Xmx512m -jar hub/build/libs/worldgit-hub.jar \
    --server.address=127.0.0.1 "--server.port=$TASK_PORT" > "$TASK_RUN/hub.log" 2>&1 &
TASK_HUB_PID=$!
cleanup() { kill "$TASK_HUB_PID" 2>/dev/null || true; wait "$TASK_HUB_PID" 2>/dev/null || true; }
trap cleanup EXIT INT TERM
for ((i=0; i<60; i++)); do
  if curl -fsS "$TASK_URL/actuator/health" > /dev/null 2>&1; then break; fi
  if ! kill -0 "$TASK_HUB_PID" 2>/dev/null; then cat "$TASK_RUN/hub.log"; exit 1; fi
  sleep 1
done
curl -fsS "$TASK_URL/actuator/health" > "$TASK_RUN/health.json"
for TASK_DIM in minecraft.overworld minecraft.the_nether minecraft.the_end; do
  git -C "$TASK_RUN/fixture/$TASK_DIM.git" -c "http.extraHeader=Authorization: Bearer $TASK_TOKEN" \
    push "$TASK_URL/git/admin/branches/$TASK_DIM.git" refs/heads/main refs/heads/feature >> "$TASK_RUN/push.log" 2>&1
done
git -C "$TASK_RUN/fixture/minecraft.overworld.git" -c "http.extraHeader=Authorization: Bearer $TASK_TOKEN" \
  push "$TASK_URL/git/admin/branches/minecraft.overworld.git" refs/heads/side refs/heads/clear refs/heads/feature:refs/heads/build/castle >> "$TASK_RUN/push.log" 2>&1
node hub/web/scripts/phase2-acceptance.mjs "$TASK_URL" "$TASK_TOKEN" "$TASK_RUN"
du -sh "$TASK_RUN"
