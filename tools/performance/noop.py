"""以真實 baseline 複本重現 CLI 無變動操作；自帶 bench.lock，保留原始命令結果。"""
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
    if args.rounds < 1:
        parser.error('rounds >= 1')
    jar = args.jar.resolve()
    work = ROOT/'.work/perf'/args.label
    work.mkdir(parents=True, exist_ok=False)
    world = work/'world'
    result = {'fixture': 'original-1.21.11-baseline', 'success': False, 'rounds': args.rounds,
              'artifact_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(), 'rows': {}}

    def save():
        (work/'result.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))

    with (ROOT/'.work/bench.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            shutil.copytree(args.baseline, world, ignore=shutil.ignore_patterns('.worldgit', 'session.lock'))

            def run(name, *command):
                start = time.perf_counter()
                process = subprocess.run([JAVA, '-Xmx1G', '-jar', str(jar), '--world', str(world), *command],
                                         text=True, capture_output=True, timeout=900)
                elapsed = time.perf_counter()-start
                with (work/'commands.jsonl').open('a') as stream:
                    stream.write(json.dumps({'command': command, 'seconds': elapsed, 'exit': process.returncode,
                                             'stdout': process.stdout, 'stderr': process.stderr}, ensure_ascii=False)+'\n')
                if process.returncode:
                    raise RuntimeError(f'{command}: {process.stderr} {process.stdout}')
                row = result['rows'].setdefault(name, {'samples': []})
                row['samples'].append(elapsed)
                row.update(p50=statistics.median(row['samples']), max=max(row['samples']))
                save()
                print(args.label, name, round(elapsed, 3), flush=True)

            run('init', 'init', '--only')
            run('branch', 'branch', 'same')
            for _ in range(args.rounds):
                run('status-noop', 'status')
                run('commit-noop', 'commit', '-m', 'noop')
                run('switch-noop', 'switch', 'same')
                run('switch-noop', 'switch', 'main')
            run('verify-full', 'verify', 'HEAD')
            result['success'] = True
        except BaseException as error:
            result['error'] = repr(error)
            raise
        finally:
            shutil.rmtree(world, ignore_errors=True)
            save()


if __name__ == '__main__':
    main()
