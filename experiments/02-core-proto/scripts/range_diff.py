#!/usr/bin/env python3
"""T4 補充：只統計「restore 範圍內」的 section 差異。用法：range_diff.py <ver> keep|delete"""
import re, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
from mcserver import *
ver, mode = sys.argv[1], sys.argv[2]
rp = WORK / ver / f"repo-t4-{mode}.git"
init = java_tool("log", WORK / ver / "repoB.git").strip().splitlines()[-1].split()[0]
out = java_tool("diff", rp, init, "main", "-v")
n = b = 0; other = 0
for l in out.splitlines():
    m = re.match(r"   section minecraft/overworld/r\.\S+/c\.(-?\d+)\.(-?\d+)/s\.\S+ blocks=(\d+)", l)
    if m:
        cx, cz = int(m[1]), int(m[2])
        if -2 <= cx <= 4 and -2 <= cz <= 3:
            n += 1; b += int(m[3])
        else:
            other += 1
print(f"{ver} {mode}: sections changed inside restore range = {n} (blocks {b}); outside range (physics from T1 etc.) = {other}")
