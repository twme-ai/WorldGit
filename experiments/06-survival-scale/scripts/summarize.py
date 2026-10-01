#!/usr/bin/env python3
from common import *
import collections

def main():
    summary={}
    for ver in ('26.2','1.21.11'):
        dest=WORK/('survival-'+ver);p=dest/'results.json'
        if not p.exists():continue
        records=json.loads(p.read_text());active=[r for r in records if r['tag'].startswith('active-')];profiles={}
        for profile in ('tol0','tol2','tol4','items','orbs','wild','extra','combo'):
            totals=collections.Counter();cats={}
            for row in active:
                v=row['profiles'][profile]
                for k in ('new_objects','new_loose_bytes'):totals[k]+=v[k]
                for k,n in v.get('diff',{}).items():
                    if isinstance(n,int):totals[k]+=n
                for k,counts in v.get('categories',{}).items():
                    cats.setdefault(k,collections.Counter()).update(counts)
            profiles[profile]={'totals':dict(totals),'categories':{k:dict(v) for k,v in cats.items()}}
        bots={}
        for p in dest.glob('*.jsonl'):
            rows=[]
            for l in p.read_text().splitlines():
                try:rows.append(json.loads(l))
                except ValueError:pass
            valid=[r for r in rows if r.get('event')=='heartbeat']
            bots[p.stem]={'successes':dict(collections.Counter(r['action'] for r in rows if r.get('event')=='success')),'failures':dict(collections.Counter(r['action'] for r in rows if r.get('event')=='failure')),'deaths':sum(r.get('event')=='death' for r in rows),'last_heartbeat':valid[-1] if valid else None}
        summary[ver]={'records':len(records),'profiles':profiles,'bots':bots,'game_seconds':(active[-1]['game_time']-next(r['game_time'] for r in records if r['tag']=='03-standing'))/20 if active else 0}
    write_json(EXP/'data/summary.json',summary)
    for name in ('survival-26.2','survival-1.21.11','large','transport'):
        for filename in ('results.json','gc.json'):
            p=WORK/name/filename
            if p.exists():target=EXP/'data'/(name+'-'+filename);target.parent.mkdir(exist_ok=True);shutil.copy2(p,target)
    print(json.dumps(summary,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
