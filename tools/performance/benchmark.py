"""CLI 端到端 benchmark。自帶 bench.lock；請勿外包 flock。合成每 chunk 一個 section。

python3 tools/performance/benchmark.py --label before --jar .work/perf/before-wgit.jar
世界、命令輸出及原始時間在 .work/perf；JSON 可另複製進 docs 作為證據。
"""
import argparse
import fcntl
import hashlib
import json
from pathlib import Path
import shutil
import statistics
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]
JAVA = '/usr/lib/jvm/java-21-openjdk-amd64/bin/java'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--jar', type=Path, default=ROOT/'cli/build/libs/wgit.jar')
    parser.add_argument('--label', required=True)
    parser.add_argument('--sizes', type=int, nargs='+', default=[1296, 20000])
    parser.add_argument('--rounds', type=int, default=3)
    args = parser.parse_args()
    if args.rounds < 1 or any(n < 1000 for n in args.sizes): parser.error('rounds >= 1；世界至少 1000 chunk')
    jar = args.jar.resolve()
    work = ROOT/'.work/perf'/args.label
    if work.exists(): raise SystemExit(f'證據目錄已存在：{work}')
    work.mkdir(parents=True)
    result = {'label': args.label, 'rounds': args.rounds, 'fixture': 'synthetic-one-solid-section-per-chunk', 'sizes': {}, 'java': JAVA, 'artifact_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'success': False}
    def save(): (work/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    with (ROOT/'.work/bench.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        for size in args.sizes:
            world = work/str(size)/'world'
            world.parent.mkdir()
            def fixture(action, count, argument):
                subprocess.run([JAVA,'-Xmx768m','-cp',str(jar),str(ROOT/'tools/performance/PerformanceFixture.java'),action,str(world),str(count),str(argument)], check=True, capture_output=True, timeout=900)
            fixture('create',size,ROOT/'.work/worlds/1.21.11/baseline/world')
            rows = result['sizes'][str(size)] = {}
            def run(name, *cmd):
                start = time.perf_counter()
                p = subprocess.run([JAVA,'-Xmx1G','-jar',str(jar),'--world',str(world),*cmd],text=True,capture_output=True,timeout=1800)
                elapsed = time.perf_counter()-start
                entry = rows.setdefault(name, {'samples': []})
                entry['samples'].append(elapsed)
                entry.update(p50=statistics.median(entry['samples']), max=max(entry['samples']))
                with (world.parent/'commands.jsonl').open('a') as log:
                    log.write(json.dumps({'command':cmd,'seconds':elapsed,'exit':p.returncode,'stdout':p.stdout,'stderr':p.stderr},ensure_ascii=False)+'\n')
                save(); print(args.label,size,name,round(elapsed,3),flush=True)
                if p.returncode: raise RuntimeError(f'{cmd}: {p.stderr} {p.stdout}')
            run('init','init','--only')
            run('branch','branch','same')
            for _ in range(args.rounds):
                run('status-noop','status');run('commit-noop','commit','-m','noop')
                run('switch-noop','switch','same');run('switch-noop','switch','main')
            for count in (1,10,100,1000):
                run('setup-switch','switch','main')
                run('setup-branch','branch',f'c{count}')
                run('setup-switch','switch',f'c{count}')
                for round_ in range(args.rounds):
                    material = 'minecraft:gold_block' if round_%2==0 else 'minecraft:diamond_block'
                    fixture('mutate',count,material)
                    run(f'commit-{count}','commit','-m',f'change-{count}-{round_}')
                for _ in range(args.rounds):
                    run(f'switch-{count}','switch','main');run(f'switch-{count}','switch',f'c{count}')
                run('setup-switch','switch','main')
                if count <= 100:
                    for round_ in range(args.rounds):
                        run('setup-branch','branch',f'merge{count}-{round_}')
                        run('setup-switch','switch',f'merge{count}-{round_}')
                        run(f'merge-{count}','merge',f'c{count}')
                        run('setup-switch','switch','main')
            run('verify-full','verify','HEAD')
            shutil.rmtree(world)  # 證據保留；世界不累積，限制中間產物大小。
    result['success'] = True
    save()


if __name__ == '__main__': main()
