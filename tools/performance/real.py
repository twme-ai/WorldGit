"""既有 1.21.11 baseline 複本的 CLI 端到端量測；自帶 bench.lock。"""
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
    parser.add_argument('--label', required=True)
    parser.add_argument('--jar', type=Path, default=ROOT/'cli/build/libs/wgit.jar')
    parser.add_argument('--baseline', type=Path, default=ROOT/'.work/worlds/1.21.11/baseline/world')
    parser.add_argument('--rounds', type=int, default=3)
    args = parser.parse_args()
    if args.rounds < 1: parser.error('rounds >= 1')
    jar = args.jar.resolve()
    work = ROOT/'.work/perf'/args.label
    work.mkdir(parents=True, exist_ok=False)
    world = work/'world'
    result = {'label': args.label, 'fixture': str(args.baseline.resolve()),
              'artifact_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
              'rounds': args.rounds, 'rows': {}, 'success': False}
    def save(): (work/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
    def run(name, *command):
        started = time.perf_counter()
        p = subprocess.run([JAVA, '-Xmx1G', '-jar', str(jar), '-w', str(world), *command],
                           capture_output=True, text=True, timeout=900)
        elapsed = time.perf_counter()-started
        row = result['rows'].setdefault(name, {'samples': []})
        row['samples'].append(elapsed)
        row.update(p50=statistics.median(row['samples']), max=max(row['samples']))
        with (work/'commands.jsonl').open('a') as log:
            log.write(json.dumps({'command': command, 'seconds': elapsed, 'exit': p.returncode,
                                  'stdout': p.stdout, 'stderr': p.stderr}, ensure_ascii=False)+'\n')
        save(); print(args.label, name, round(elapsed, 3), flush=True)
        if p.returncode: raise AssertionError(p.stderr+' '+p.stdout)
    with (ROOT/'.work/bench.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            shutil.copytree(args.baseline, world, ignore=shutil.ignore_patterns('.worldgit', 'session.lock'))
            run('init', 'init', '--only'); run('branch', 'branch', 'same')
            for _ in range(args.rounds):
                run('status-noop', 'status'); run('commit-noop', 'commit', '-m', 'noop')
                run('switch-noop', 'switch', 'same'); run('switch-noop', 'switch', 'main')
            run('verify-full', 'verify', 'HEAD'); result['success'] = True
        except BaseException as error:
            result['error'] = repr(error); raise
        finally:
            shutil.rmtree(world, ignore_errors=True); save()


if __name__ == '__main__': main()
