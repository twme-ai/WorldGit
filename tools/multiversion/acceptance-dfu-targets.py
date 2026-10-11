#!/usr/bin/env python3
"""驗證每個可升級目標的原版 DFU 反射入口與目標 chunk codec；自行持鎖。"""
import fcntl, subprocess
from acceptance import ROOT, WORK, JAR

def main():
    WORK.mkdir(parents=True, exist_ok=True)
    with (ROOT / '.work/bench.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/java', '-Xmx512m', '-cp', str(JAR),
            str(ROOT / 'tools/multiversion/DfuTargets.java'),
            str(ROOT / '.work/worlds/1.20.1/multiversion/world'), str(WORK / 'dfu-targets.json')],
            cwd=ROOT, check=True, timeout=600)

if __name__ == '__main__':
    main()
