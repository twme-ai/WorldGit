#!/usr/bin/env python3
"""序列執行既有 Phase2/3 與真 Fabric↔Paper/Folia 回歸；子腳本各自持 bench.lock。"""
import argparse,json,os,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
PLATFORMS=[('paper','1.21.11'),('paper','26.2'),('folia','1.21.11'),('folia','26.2')]
PHASE4_CASES=[(p,v,'polling' if (p,v)==('folia','26.2') else 'phase4') for p,v in PLATFORMS]
REGRESSION_CASES=[(p,v,phase) for p,v in PLATFORMS for phase in ['phase2','phase3','interop']]
def case_name(case):return '-'.join(case)

def run(args):
    out=ROOT/'.work/paper-phase4'/('regressions-'+str(int(time.time())));out.mkdir(parents=True)
    result={'success':False,'cases':[]}
    cases=[]
    if args.phase4 or args.phase4_only:
      cases=list(PHASE4_CASES)
      if args.folia_first:cases.sort(key=lambda c:c[:2]!=('folia','1.21.11'))
      if args.polling_first:cases.sort(key=lambda c:c[2]!='polling')
    if not args.phase4_only:cases += REGRESSION_CASES
    if args.case:cases=[c for c in PHASE4_CASES+REGRESSION_CASES if case_name(c) in args.case]
    for platform,version,phase in cases:
        name=platform+'-'+version+'-'+phase;start=time.monotonic();log=out/(name+'.log')
        if phase in ('phase4','polling'):
          command=['python3','paper/tools/phase4.py',platform,version,'--screenshots']+(['--polling'] if phase=='polling' else [])
        else:command=['python3','fabric/tools/accept-paper-phase3.py',platform,version] if phase=='interop' else ['python3','paper/tools/acceptance.py',platform,version,phase]
        print('START '+name,flush=True)
        with log.open('w') as f:p=subprocess.run(command,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT,env={**os.environ,'GRADLE_USER_HOME':str(ROOT/'.work/gradle-home'),'npm_config_cache':str(ROOT/'.work/npm-cache'),'PLAYWRIGHT_BROWSERS_PATH':str(ROOT/'.work/ms-playwright')})
        result['cases'].append({'name':name,'exit':p.returncode,'seconds':time.monotonic()-start,'log':str(log.relative_to(ROOT))});(out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
        print(('PASS ' if p.returncode==0 else 'FAIL ')+name,flush=True)
    result['success']=all(c['exit']==0 for c in result['cases']);(out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2));print(out/'results.json',flush=True);return result['success']
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--phase4',action='store_true',help='先驗四種 Phase4／通知組合，再跑 Phase2/3/interop')
    parser.add_argument('--phase4-only',action='store_true',help='只驗四種 Phase4／通知組合')
    parser.add_argument('--polling-first',action='store_true',help='Phase4 先驗 Folia 26.2 定時 fetch')
    parser.add_argument('--folia-first',action='store_true',help='Phase4 先驗 Folia 1.21.11')
    parser.add_argument('--case',action='append',choices=[case_name(c) for c in PHASE4_CASES+REGRESSION_CASES],help='只驗指定項目，可重複；用於補驗失敗或缺少的組合')
    raise SystemExit(0 if run(parser.parse_args()) else 1)
