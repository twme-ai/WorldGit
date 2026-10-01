#!/usr/bin/env bash
set -euo pipefail
POC_ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
export GRADLE_USER_HOME="$POC_ROOT/.work/gradle-home"
export JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64
export npm_config_cache="$POC_ROOT/.work/npm-cache"
mkdir -p "$POC_ROOT/.work/fabric-poc/project-cache"
mkdir -p "$POC_ROOT/.work/gradle-home/caches" "$POC_ROOT/.work/fabric-poc/tmp"
if [[ ! -L "$POC_ROOT/.work/gradle-home/caches/fabric-loom" ]]; then
    if [[ -d "$POC_ROOT/.work/gradle-home/caches/fabric-loom" ]]; then
        mv "$POC_ROOT/.work/gradle-home/caches/fabric-loom" "$POC_ROOT/.work/fabric-poc/loom-cache"
    else
        mkdir -p "$POC_ROOT/.work/fabric-poc/loom-cache"
    fi
    ln -s "$POC_ROOT/.work/fabric-poc/loom-cache" "$POC_ROOT/.work/gradle-home/caches/fabric-loom"
fi
export TMPDIR="$POC_ROOT/.work/fabric-poc/tmp"
export GRADLE_OPTS="${GRADLE_OPTS:-} -Djava.io.tmpdir=$TMPDIR -XX:-UsePerfData"
exec gradle -p "$POC_ROOT/experiments/05-fabric-poc" --project-cache-dir "$POC_ROOT/.work/fabric-poc/project-cache" --max-workers=1 :protocol:check :paper:jar :client121:build :client262:build "$@"
