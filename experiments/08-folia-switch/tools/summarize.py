#!/usr/bin/env python3
"""從實測 JSON 匯出表格（Markdown）。Folia rolling window 的平均不當逐 tick 分位數：只取視窗平均的範圍與視窗內最差 tick。"""
import json, pathlib
ROOT=pathlib.Path(__file__).resolve().parents[3]
work=ROOT/'.work/folia-switch'
def diff(j):
    o=j.get('offline',{}).get('result',{})
    return sum(o.get(k,0) for k in ('sectionMismatches','missingChunks','entityMissing','entityMismatches','duplicateUUIDs','unexpectedEntities','poiMismatches'))
def tick(j):
    p=j.get('paperTicks')
    if p: return f"全服 tick mean {p['mean']:.2f} / p95 {p['p95']:.2f} / p99 {p['p99']:.2f} / max {p['max']:.1f}; TPS {j['paperTPS']:.2f}"
    rs=[x for recs in j.get('regionReports',{}).values() for x in recs if x.get('ticks',0)>=20]
    if not rs: return '-'
    mm=[x['timePerTickData']['mean']/1e6 for x in rs]; tp=[x['tpsData']['mean'] for x in rs]
    return f"region 窗平均 MSPT {min(mm):.2f}..{max(mm):.2f}; 窗平均 TPS 最低 {min(tp):.2f}; 最差 tick {max(x['timePerTickData']['max'] for x in rs)/1e6:.1f}"
print('| 平台 | 方向 | 預算 ms / 限額 | 秒 | section/s | 載入 / 已載入 | ticket 峰值 | load p95 ms | 單 section p50/p95/max ms | 錯誤 | RSS 峰值 MiB | 離線差異 | bot 抽樣 |')
print('|---|---|---|---:|---:|---|---:|---:|---|---:|---:|---:|---|')
for f in sorted(work.glob('results-*.json')):
    if f.name.endswith('.keep.json'): continue
    r=json.loads(f.read_text())
    for j in r.get('jobs',[]):
        b=j.get('bots',[]);s=j['sectionMs']
        print(f'| {r["name"]} | →{j["target"]} | {j["budgetMs"]:g} / {j["sectionLimit"]} | {j["seconds"]:.1f} | {j["sectionsPerSecond"]:.0f} | {j["ticketLoads"]} / {j["loadedAtDispatch"]} | {j["ticketPeak"]} | {j["loadMs"]["p95"]:.0f} | {s["p50"]:.2f} / {s["p95"]:.2f} / {s["max"]:.1f} | {j["errors"]} | {j.get("rssPeakKiB",0)/1024:.0f} | {diff(j)} | {sum(x["match"] for x in b)}/{len(b)} |')
print('\n### tick 負載\n')
for f in sorted(work.glob('results-*.json')):
    if f.name.endswith('.keep.json'): continue
    r=json.loads(f.read_text())
    print(f'**{r["name"]}**（進程 RSS 全程峰值 {r.get("processRSSPeakKiB",0)/1024:.0f} MiB）\n')
    for j in r.get('jobs',[]): print(f'- →{j["target"]} {j["budgetMs"]:g}ms/{j["sectionLimit"]}：{tick(j)}；regions={j.get("regions")}；heap 峰值 {j["heapPeakBytes"]/2**20:.0f} MiB；spawnNotValid={j.get("spawnNotValid")}')
    print()
print('### 取消 / 恢復 / 關服後\n')
for f in sorted(work.glob('results-*.json')):
    if f.name.endswith('.keep.json'): continue
    r=json.loads(f.read_text());c=r.get('cancel',{});rc=r.get('recovery',{})
    if c: print(f'- {r["name"]}: 取消 head={c["head"]} chunks={c["chunks"]} sections={c["sections"]} 耗時 {c["seconds"]:.2f}s ticketRemaining={c["ticketRemaining"]}；重新套用 A head={rc.get("head")} errors={rc.get("errors")} {rc.get("seconds",0):.1f}s；關服後離線驗 A: {r.get("afterStopA",{}).get("result")}')
    print(f'  protection: {[(p["cause"],p["cancelled"]) for p in r.get("protectionTests",[])]}')
    if r.get('failure'): print('  FAILURE',r['failure'][:200])
