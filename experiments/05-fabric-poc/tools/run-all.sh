#!/usr/bin/env bash
set -euo pipefail
POC_ROOT=$(cd "$(dirname "$0")/../../.." && pwd)
bash "$POC_ROOT/experiments/05-fabric-poc/tools/build.sh"
exec python3 "$POC_ROOT/experiments/05-fabric-poc/tools/run.py" "$@"
