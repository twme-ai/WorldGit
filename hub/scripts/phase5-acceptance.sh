#!/usr/bin/env bash
# Phase 5 Hub 真 jar／SQLite／CLI／瀏覽器；Python 內持 bench.lock。
set -euo pipefail
TASK_ROOT="$(cd -- "$(dirname -- "$0")/../.." && pwd)"
cd "$TASK_ROOT"
export GRADLE_USER_HOME="$TASK_ROOT/.work/gradle-home"
export npm_config_cache="$TASK_ROOT/.work/npm-cache"
export PLAYWRIGHT_BROWSERS_PATH="$TASK_ROOT/.work/ms-playwright"
if [[ ${SKIP_BUILD:-0} != 1 ]]; then
  flock .work/bench.lock npm --prefix hub/web run build
  flock .work/bench.lock ./gradlew --no-daemon --configure-on-demand --max-workers=1 :hub:bootJar :cli:acceptanceToolsJar
fi
exec python3 hub/scripts/phase5-acceptance.py --results-dir "${RESULTS_DIR:-.work/phase5-hub-$(date +%s)}" "$@"
