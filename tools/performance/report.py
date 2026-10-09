"""Render comparable benchmark JSON as Markdown, including raw TPS ranges.

python3 tools/performance/report.py --before docs/performance/before-cli.json \
    --after docs/performance/after-cli.json
"""
import argparse
import json
from pathlib import Path

OPERATIONS = ['init', 'status-noop', 'commit-noop', 'switch-noop',
              'commit-1', 'commit-10', 'commit-100', 'commit-1000',
              'switch-1', 'switch-10', 'switch-100', 'switch-1000',
              'merge-1', 'merge-10', 'merge-100',
              *[f'{name}-{count}' for name in ('diff', 'restore', 'reset',
                 'stash-push', 'stash-pop', 'revert', 'cherry-pick')
                for count in (1, 10, 100)], 'verify', 'verify-full']


def latency(row):
    return '—' if not row else f"{row['p50']:.3f} / {row['max']:.3f}"


def tps(row):
    probes = row.get('probes', []) if row else []
    values = [probe['b_tps'] for probe in probes if probe]
    return '—' if not values else f'{min(values):.3f}–{max(values):.3f}'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', type=Path, required=True)
    parser.add_argument('--after', type=Path, required=True)
    args = parser.parse_args()
    before, after = [json.loads(path.read_text()) for path in (args.before, args.after)]
    if after.get('success') is not True:
        parser.error('after result must have success=true')
    if before.get('success') is False:
        parser.error('before result failed')
    if before.get('fixture') != after.get('fixture'):
        parser.error('fixture differs; historical measurements need separate tables')
    for key in ('players', 'version', 'client_fps_cap'):
        if before.get(key) != after.get(key):
            parser.error(f'{key} differs')
    groups = after.get('sizes', {'': after.get('rows', {})})
    previous = before.get('sizes', {'': before.get('rows', {})})
    for size, rows in groups.items():
        if size not in previous:
            parser.error(f'missing before size {size}')
        print(f'\n{size + " chunk · " if size else ""}{after["fixture"]}\n')
        online = any('probes' in row for row in rows.values())
        print('| 操作 | 優化前 p50 / max（秒） | 優化後 p50 / max（秒） |' +
              (' 前 TPS | 後 TPS |' if online else ''))
        print('|---|---:|---:|' + ('---:|---:|' if online else ''))
        for operation in OPERATIONS:
            if operation not in rows:
                continue
            old = previous[size].get(operation)
            row = rows[operation]
            print(f'| `{operation}` | {latency(old)} | {latency(row)} |' +
                  (f' {tps(old)} | {tps(row)} |' if online else ''))
        if online:
            print('\n短命令的 TPS 有首尾 tick 取樣誤差；隔離與穩態 TPS 以千 chunk 切換長窗口判斷。')


if __name__ == '__main__':
    main()
