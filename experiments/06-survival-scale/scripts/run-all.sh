#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/../../.."
export JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
export GRADLE_USER_HOME="$PWD/.work/gradle-home"
export npm_config_cache="$PWD/.work/npm-cache"
export PLAYWRIGHT_BROWSERS_PATH="$PWD/.work/ms-playwright"
mkdir -p .work/survival-scale/logs
gradle -p experiments/06-survival-scale --max-workers=1 --no-daemon jar
/usr/lib/jvm/java-25-openjdk-amd64/bin/java -jar experiments/06-survival-scale/build/libs/survival-scale.jar check
python3 experiments/06-survival-scale/scripts/download.py
python3 -u experiments/06-survival-scale/scripts/survival.py --version 26.2
python3 -u experiments/06-survival-scale/scripts/survival.py --version 1.21.11 --minutes 5 --control-minutes 1 --interval 5
python3 -u experiments/06-survival-scale/scripts/large.py
python3 -u experiments/06-survival-scale/scripts/transport.py
python3 experiments/06-survival-scale/scripts/summarize.py
