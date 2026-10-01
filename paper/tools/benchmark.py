"""真實 Paper/Folia 的 1000/10000 個變動 chunk commit 量測。

用法：timeout 3600 python3 paper/tools/benchmark.py paper 1.21.11 1000
本程式取得 bench.lock，不可再套外層 flock。負載為合成平坦 chunk，不代表生存世界。
先載入並暖機、init；比較 idle 與 commit 期間 spawn 所屬 region 的 tick 間隔。
"""
import json
import math
import os
import re
import signal
import subprocess
import sys
import time
from harness import BenchLock, Server, JAVA, ROOT, WORK


def number_fields(text):
    return {key: float(value) for key, value in re.findall(r'(\w+)=([0-9.]+)', text)}


def terminate(signum, frame):
    raise KeyboardInterrupt('benchmark terminated')


def main():
    platform, version, count = sys.argv[1], sys.argv[2], int(sys.argv[3])
    if count not in (1000, 10000):
        raise SystemExit('count must be 1000 or 10000')
    signal.signal(signal.SIGTERM, terminate)
    side = math.ceil(math.sqrt(count))
    name = f'{platform}-{version}-{count}'
    output = os.path.join(WORK, 'paper-delivery', 'results', 'benchmark-' + name + '.json')
    fixture = os.path.join(WORK, 'paper-delivery', 'fixtures', f'{version}-{count}')
    with BenchLock():
        if not os.path.isdir(fixture):
            subprocess.run([JAVA[version], '-Xmx768m', '-cp', os.path.join(ROOT, 'cli/build/libs/wgit.jar'),
                os.path.join(ROOT, 'paper/tools/ScaleFixture.java'), version,
                os.path.join(WORK, 'worlds', version, 'baseline'), fixture, str(side)], check=True, timeout=300)
        result = {'platform': platform, 'version': version, 'target_changed_chunks': count, 'fixture': 'synthetic-stone-flat-with-10-chunk-halo',
            'probe': 'spawn region owner scheduler tick gaps; Folia does not report every region', 'rounds': [], 'started': time.strftime('%F %T')}
        s = Server(platform, version, baseline=fixture, run_label='benchmark-' + name, view=2, xmx='4G',
            config={'auto-commit': {'enabled': False, 'on-shutdown': False}, 'commit': {'chunks-per-tick': 8, 'snapshot-window': 256, 'timeout-seconds': 1200}})
        try:
            s.start()
            result['console_log'] = os.path.relpath(s.evidence_log, ROOT)
            print(name, 'loading', flush=True)
            result['load'] = s.cmd(f'wg debug fill {side} stone {count}', r'debug fill 完成|失敗', 900)
            result['init'] = s.cmd('wg init', r'init 完成|失敗', 900)
            time.sleep(10)
            s.cmd('wg debug probe start', r'probe 開始', 30)
            time.sleep(10)
            result['idle_probe'] = s.cmd('wg debug probe stop', r'probe ticks=', 30)
            for material in ('gold_block', 'diamond_block'):
                s.cmd(f'wg debug fill {side} {material} {count}', r'debug fill 完成|失敗', 900)
                s.cmd('wg debug probe start', r'probe 開始', 30)
                t0 = time.monotonic()
                output_text = s.cmd('wg commit -m benchmark-' + material, r'overworld [0-9a-f]{8}|失敗', 1200)
                wall = time.monotonic() - t0
                probe = s.cmd('wg debug probe stop', r'probe ticks=', 30)
                measurements = [line for line in output_text.splitlines() if 'copy{' in line and 'minecraft:overworld' in line]
                copy = re.search(r'copy\{([^}]+)\}', '\n'.join(measurements))
                section_counts = re.findall(r'section (\d+)', output_text)
                ok = bool(copy and section_counts and int(section_counts[0]) == count and 'failures=0' in copy.group(1))
                result['rounds'].append({'material': material, 'ok': ok, 'wall_seconds': round(wall, 3), 'copy': number_fields(copy.group(1)) if copy else {},
                    'probe': number_fields(probe), 'measurement_lines': measurements, 'commit': output_text, 'probe_raw': probe})
                json.dump(result, open(output, 'w'), ensure_ascii=False, indent=2)
                print(name, material, 'PASS' if ok else 'FAIL', 'wall', round(wall, 2), measurements, probe[-220:], flush=True)
            s.cmd('wg debug release', r'已釋放', 30)
        finally:
            s.stop()
            result['finished'] = time.strftime('%F %T')
            json.dump(result, open(output, 'w'), ensure_ascii=False, indent=2)
    return 0 if len(result['rounds']) == 2 and all(r['ok'] for r in result['rounds']) else 1


if __name__ == '__main__':
    sys.exit(main())
