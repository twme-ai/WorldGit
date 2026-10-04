#!/usr/bin/env python3
"""序列執行 Phase 4 後的既有回歸，保留 JSON/log，移除本輪大型測試副本。"""
import argparse,json,os,re,shutil,signal,subprocess,time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

def main():
    parser=argparse.ArgumentParser();parser.add_argument('--only',nargs='*');args=parser.parse_args()
    out=ROOT/'.work/fabric-phase4'/('regressions-'+str(int(time.time())));out.mkdir(parents=True);summary=[]
    env={**os.environ,'JAVA_HOME':'/usr/lib/jvm/java-25-openjdk-amd64','GRADLE_USER_HOME':str(ROOT/'.work/gradle-home'),'ALSOFT_DRIVERS':'null','WG_COMPACT_EVIDENCE':'1','npm_config_cache':str(ROOT/'.work/npm-cache'),'PLAYWRIGHT_BROWSERS_PATH':str(ROOT/'.work/ms-playwright')}
    jobs=[]
    for phase in [2,3]:
      for v in ['1.21.11','26.2']:jobs.append((f'single-{v}-phase{phase}',['fabric/tools/run-gametest.sh',v,'--record'],{f'WG_PHASE{phase}':'1'}))
    for v in ['1.21.11','26.2']:jobs.append((f'dedicated-{v}',['python3','fabric/tools/accept-dedicated.py',v],{}))
    for v in ['1.21.11','26.2']:jobs.append((f'interop-paper-{v}',['python3','fabric/tools/accept-paper-phase3.py','paper',v],{}))
    jobs.append(('paper-phase4-1.21.11',['python3','paper/tools/phase4.py','paper','1.21.11','--screenshots'],{}))
    for name,cmd,extras in jobs:
      if args.only and name not in args.only:continue
      start=time.time();before=set((ROOT/'.work/fabric-acceptance').glob('*'));log=out/(name+'.log');print('RUN '+name,flush=True)
      with log.open('w') as f:p=subprocess.run(cmd,cwd=ROOT,env={**env,**extras},stdout=f,stderr=subprocess.STDOUT)
      text=log.read_text();row={'name':name,'exit':p.returncode,'seconds':time.time()-start,'log':str(log.relative_to(ROOT))}
      for line in text.splitlines():
        if line.startswith(str(ROOT/'.work/')) and line.endswith(('/result.json','/results.json')):
          result=Path(line)
          if result.is_file():
            row['result']=str(result.relative_to(ROOT));row['success']=json.loads(result.read_text())['success']
      if name.startswith('single-'):
        for evidence in set((ROOT/'.work/fabric-acceptance').glob('*'))-before:
          if not evidence.is_dir():continue
          result=evidence/'result.json'
          if result.exists():
            data=json.loads(result.read_text());row['result']=str(result.relative_to(ROOT));row['success']=data['success']
            shutil.rmtree(evidence/'checkpoints',ignore_errors=True);(evidence/'wgit.jar').unlink(missing_ok=True)
            # fresh temporary paths are reported by this run; no baseline is touched.
            if data.get('artifacts'):shutil.rmtree(ROOT/data['artifacts'],ignore_errors=True)
        game_logs=re.findall(r'WGTEST[23] artifacts=(.+)|WGTEST[23] world=(.+)',text)
        for pair in game_logs:
          path=Path(next(x for x in pair if x).strip()).resolve()
          if path.is_relative_to(ROOT/'.work/worlds/fabric-gametest'):shutil.rmtree(path,ignore_errors=True)
      summary.append(row);(out/'results.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n');print(('PASS ' if p.returncode==0 else 'FAIL ')+name,flush=True)
    print(out/'results.json',flush=True);return all(r['exit']==0 for r in summary)
if __name__=='__main__':raise SystemExit(0 if main() else 1)
