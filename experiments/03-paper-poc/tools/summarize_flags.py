"""把 .work/paper-poc/flags-*.jsonl 整理成 Markdown 表格（給 REPORT 用）。python3 summarize_flags.py [file ...]
欄位：原始 unsaved 旗標（ChunkAccess.unsaved）/ 有效旗標（LevelChunk.isUnsaved()，Paper 才有）/ save 後是否清掉 / region ts 是否更新 / 封包。"""
import sys, json, os, glob
W = os.path.join(os.path.dirname(__file__), '../../../.work/paper-poc')
files = sys.argv[1:] or sorted(glob.glob(os.path.join(W, 'flags-*.jsonl')))
data = {}
for f in files:
    tag = os.path.basename(f)[6:-6]
    rows = [json.loads(l) for l in open(f)]
    data[tag] = {r['name']: r for r in rows if r['kind'] == 'src'}
names = []
for d in data.values():
    for n in d:
        if n not in names: names.append(n)
def yn(b): return 'Y' if b else '-'
def yn3(b): return '(n/a)' if b is None else yn(b)
def pk(r):
    p = r.get('pkt') or {}
    return ', '.join(f"{k}:{v['packets']}p/{v['entries']}e/{len(v['players'])}pl" for k, v in p.items()) or '-'
EFF = 'flagEffectivePost(isUnsaved incl. tick dirty)'
for tag, d in data.items():
    print(f'\n### {tag}\n')
    print('| 來源 | 原始旗標 | 有效旗標 isUnsaved() | save 後原始旗標清掉 | region ts 更新 | 封包（type:封包數/格數/玩家數） |')
    print('|---|---|---|---|---|---|')
    for n in names:
        r = d.get(n)
        if not r: continue
        print(f"| {n} | {yn(r['flagPost'])} | {yn3(r.get(EFF)) if EFF in r else '(舊版資料)'} | {yn(not r['flagAfterSaveAll'])} | {yn(r['regionTsAfter'] != r['regionTsBefore'])} | {pk(r)} |")
