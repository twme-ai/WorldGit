#!/bin/bash
# 在 Xvfb 下以軟體渲染（llvmpipe）執行 Fabric client game test：真正的 Minecraft 客戶端。
# 用法: fabric/tools/run-gametest.sh <1.21.11|26.2> [--record]（自行取得 bench.lock）
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
VER=${1:?版本 1.21.11 或 26.2}
case $VER in 1.21.11) PROJ=mc1_21_11;; 26.2) PROJ=mc26_2;; *) echo "未知版本"; exit 2;; esac
GRADLE_ROOT=${GRADLE_ROOT:-$ROOT}
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export GRADLE_USER_HOME=${GRADLE_USER_HOME:-$ROOT/.work/gradle-home}
export LIBGL_ALWAYS_SOFTWARE=1 GALLIUM_DRIVER=llvmpipe LP_NUM_THREADS=3
export XDG_CACHE_HOME=$ROOT/.work/fabric-tmp/cache XDG_CONFIG_HOME=$ROOT/.work/fabric-tmp/config TMPDIR=$ROOT/.work/fabric-tmp
mkdir -p "$XDG_CACHE_HOME" "$XDG_CONFIG_HOME" "$TMPDIR"
cd "$GRADLE_ROOT"
exec 9>"$ROOT/.work/bench.lock"
flock 9
mkdir -p "$ROOT/.work/fabric-acceptance/logs"
LOG="$ROOT/.work/fabric-acceptance/logs/singleplayer-$VER-$(date -u +%Y%m%d-%H%M%S).log"
test_pid=
cleanup() {
  if [[ -n $test_pid ]]; then
    kill -TERM -- "-$test_pid" 2>/dev/null || true
    sleep 1
    kill -KILL -- "-$test_pid" 2>/dev/null || true
    wait "$test_pid" 2>/dev/null || true
  fi
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
# 子程序獨立 process group；bench.lock 只由外層腳本持有，避免中斷後 daemon 繼承鎖。
setsid timeout --kill-after=10 "${GAMETEST_TIMEOUT:-1800}" /usr/bin/xvfb-run -a -s "-screen 0 1280x720x24 -ac" \
  ./gradlew --no-daemon --max-workers=1 --configure-on-demand ${WG_PHASE2:+-PwgtestPhase2=true} ":fabric:$PROJ:runClientGameTest" \
  9>&- > >(tee "$LOG" 9>&-) 2>&1 &
test_pid=$!
wait "$test_pid"
if [[ ${2:-} == --record ]]; then
  if [[ -n ${WG_PHASE2:-} ]]; then
    WG_LOCK_HELD=1 python3 "$ROOT/fabric/tools/record-phase2.py" "$VER" "$LOG"
  else
    WG_LOCK_HELD=1 python3 "$ROOT/fabric/tools/record-singleplayer.py" "$VER" "$LOG"
  fi
fi
