#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export GRADLE_USER_HOME="$PWD/.work/gradle-home"
export npm_config_cache="$PWD/.work/npm-cache"
export PLAYWRIGHT_BROWSERS_PATH="$PWD/.work/ms-playwright"
mkdir -p .work/folia-switch/tmp
export JAVA_TOOL_OPTIONS="${JAVA_TOOL_OPTIONS:-} -Djava.io.tmpdir=$PWD/.work/folia-switch/tmp -XX:-UsePerfData"
gradle --max-workers=1 --no-daemon -p experiments/08-folia-switch jar
python3 -u experiments/08-folia-switch/tools/run.py "$@"
