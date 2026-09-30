"""四個平台的 flags 結果併成一張表（REPORT 用）。"""
import json, os
W = os.path.join(os.path.dirname(__file__), '../../../.work/paper-poc')
tags = ['paper-1.21.11', 'paper-26.2', 'folia-1.21.11', 'folia-26.2']
D = {}
for t in tags:
    D[t] = {}
    for l in open(os.path.join(W, f'flags-{t}.jsonl')):
        r = json.loads(l)
        if r['kind'] == 'src': D[t][r['name']] = r
names = list(D[tags[0]].keys())
def yn(b): return 'Y' if b else '-'
EFF = 'flagEffectivePost(isUnsaved incl. tick dirty)'
print('| 來源 | 原始旗標 P1.21 / P26.2 / F1.21 / F26.2 | 有效 isUnsaved() P1.21 / P26.2 | region ts 更新 P1.21 / P26.2 / F1.21 / F26.2 | PacketEvents 封包（Paper 1.21.11，1 玩家） |')
print('|---|---|---|---|---|')
for n in names:
    row = [D[t].get(n) for t in tags]
    raw = ' / '.join(yn(r['flagPost']) if r else '?' for r in row)
    eff = ' / '.join(('n/a' if r.get(EFF) is None else yn(r.get(EFF))) if r else '?' for r in row[:2])
    ts = ' / '.join(yn(r['regionTsAfter'] != r['regionTsBefore']) if r else '?' for r in row)
    p = row[0].get('pkt') or {}
    pk = ', '.join(f"{k} ×{v['packets']}" + (f"({v['entries']}格)" if k == 'MULTI_BLOCK_CHANGE' else '') for k, v in p.items()) or '-'
    print(f'| {n} | {raw} | {eff} | {ts} | {pk} |')
