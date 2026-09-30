#!/usr/bin/env bash
# 冒煙測試：啟動 Folia 到 "Done" 後 stop，記錄啟動時間與世界資料夾佈局。用法：./smoke_folia.sh [1.21.11|26.2]
set -u
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
for ver in "${@:-1.21.11 26.2}"; do
  for v in $ver; do
    case $v in 1.21.11) J=/usr/lib/jvm/java-21-openjdk-amd64/bin/java;; *) J=/usr/lib/jvm/java-25-openjdk-amd64/bin/java;; esac
    d="$ROOT/.work/servers/folia-$v"; cd "$d" || exit 1
    rm -rf world world_nether world_the_end logs
    start=$(date +%s)
    ( sleep 1; while ! grep -q 'Done (' "$d/smoke.log" 2>/dev/null; do sleep 1; done; sleep 3; echo stop ) | \
      timeout 300 "$J" -Xmx2G -jar server.jar --nogui > smoke.log 2>&1
    echo "folia-$v: 啟動至 Done 約 $(( $(date +%s) - start )) 秒（含 stop）；$(grep -o 'Done ([^)]*)' smoke.log)"
    ls "$d" | tr '\n' ' '; echo
  done
done
