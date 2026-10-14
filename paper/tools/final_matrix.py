#!/usr/bin/env python3
"""任務2最終序列矩陣；固定jar、逐格留證、成功格可續跑。子runner自持bench.lock。"""
import argparse
import fcntl
import hashlib
import json
import os
from pathlib import Path
import shutil
import signal
import socket
import subprocess
import time

ROOT=Path(__file__).resolve().parents[2]
VERSIONS=('1.20.1','1.20.4','1.20.6','1.21.1','1.21.4','1.21.8','1.21.11','26.1.2','26.2','26.3')
FOLIA=tuple(v for v in VERSIONS if v not in ('1.21.1','26.3'))
PHASE5=('1.20.1','1.20.6','1.21.4','26.1.2','26.3')
OWNER=('1.20.1','26.1.2')

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()
def write(path,value):
    tmp=path.with_suffix(path.suffix+'.tmp');tmp.write_text(json.dumps(value,ensure_ascii=False,indent=2)+'\n');tmp.replace(path)

def cases(out):
    result=[]
    def add(name,command,pattern):result.append((name,['python3',*command],pattern))
    for old,new in list(zip(VERSIONS,VERSIONS[1:]))+[('1.20.1','26.3'),('1.20.4','1.21.11')]:
        name=f'online-{old}-to-{new}'
        add(name,['paper/tools/multiversion.py','--pair',old+':'+new,'--output-root',str(out/'online')]+(['--tokens'] if (old,new) in [('1.20.1','1.20.4'),('26.2','26.3')] else [])+(['--interactions'] if (old,new)==('26.2','26.3') else []),f'{out.relative_to(ROOT)}/online/{old}-to-{new}/results.json')
    for version in VERSIONS:
        for platform in ('paper','folia'):
            if platform=='folia' and version not in FOLIA:continue
            name=platform+'-'+version
            add('acceptance-'+name,['paper/tools/acceptance.py',platform,version],'.work/paper-delivery/results/'+name+'.json')
            if platform=='paper' and version in PHASE5:
                add('phase5-'+name,['paper/tools/phase5.py',platform,version],'.work/paper-phase5/'+name+'-*/results.json')
            if platform=='folia' and version in OWNER:
                add('pending-'+name,['paper/tools/components.py',platform,version,'--output-root',str(out/'pending'),'--shutdown-held'],f'{out.relative_to(ROOT)}/pending/{name}/results.json')
                for suffix,prefix in [('phase2-entities','entities'),('phase2-cancel','cancel'),('phase2-shutdown','shutdown')]:
                    add(prefix+'-'+name,['paper/tools/acceptance.py',platform,version,suffix],'.work/paper-phase2/'+prefix+'-'+name+'-*/results.json')
                add('sigterm-'+name,['paper/tools/acceptance.py',platform,version,'sigterm'],'.work/paper-delivery/results/'+name+'.json')
    add('phase2-regressions',['paper/tools/phase4_regressions.py','--case','paper-1.21.11-phase2','--case','folia-1.21.11-phase2'],'.work/paper-phase4/regressions-*/results.json')
    for label,artifact in [('before',out/'artifacts/before-667feff.jar'),('after',out/'artifacts/final.jar')]:
        add('perf-'+label,['tools/performance/online.py','paper','26.2','--label','mv2-5-'+label,'--artifact',str(artifact),'--rounds','3','--counts','1','100','--latency-only'],'.work/perf/mv2-5-'+label+'-paper-26.2/result.json')
        add('large-'+label,['paper/tools/large_content.py','--output',str(out/('large-'+label)),'--artifact',str(artifact)],f'{out.relative_to(ROOT)}/large-{label}/results.json')
    add('large-projection',['paper/tools/large_content.py','--output',str(out/'large-projection'),'--projection'],f'{out.relative_to(ROOT)}/large-projection/results.json')
    return result

def ports_closed():
    opened=[]
    for port in range(25671,25691):
        with socket.socket() as sock:
            sock.settimeout(.1)
            if sock.connect_ex(('127.0.0.1',port))==0:opened.append(port)
    if opened:raise RuntimeError('上一格的測試伺服器尚未關閉：'+str(opened))

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output-root',type=Path,default=ROOT/'.work/mv2-5')
    parser.add_argument('--stage',action='append');parser.add_argument('--list',action='store_true')
    parser.add_argument('--freeze',type=Path,help='首次複製最終jar；已有manifest時必須同SHA')
    args=parser.parse_args();out=args.output_root.resolve();out.mkdir(parents=True,exist_ok=True)
    if not out.is_relative_to(ROOT/'.work'):parser.error('output-root必須在本工作樹.work')
    commands=cases(out)
    if args.list:
        for name,cmd,_ in commands:print(name+' '+' '.join(cmd))
        return 0
    jar=out/'artifacts/final.jar';manifest=out/'artifact-manifest.json'
    if args.freeze:
        sha=digest(args.freeze)
        if manifest.exists() and json.loads(manifest.read_text())['sha256']!=sha:
            parser.error('最終jar已改變；請用新的output-root完整重驗，不能混用舊格')
        jar.parent.mkdir(exist_ok=True);shutil.copy2(args.freeze,jar)
        shared={p:digest(ROOT/p) for p in ['hub/build/libs/worldgit-hub.jar','cli/build/libs/wgit.jar','cli/build/libs/acceptance-tools.jar']}
        write(manifest,{'sha256':sha,'jar':str(jar.relative_to(ROOT)),'source':str(args.freeze),'shared_artifacts':shared,'frozen_at':time.strftime('%FT%TZ',time.gmtime())})
    metadata=json.loads(manifest.read_text());sha=metadata['sha256']
    if digest(jar)!=sha:raise RuntimeError('固定jar與manifest不符')
    statefile=out/'checkpoint.json';state=json.loads(statefile.read_text()) if statefile.exists() else {'sha256':sha,'cells':{}}
    if state['sha256']!=sha:raise RuntimeError('checkpoint屬於不同jar')
    if args.stage:
        unknown=set(args.stage)-{n for n,_,_ in commands}
        if unknown:parser.error('未知stage：'+str(unknown))
        commands=[c for c in commands if c[0] in args.stage]
    runner_lock=(out/'runner.lock').open('a')
    try:fcntl.flock(runner_lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
    except BlockingIOError:raise RuntimeError('此矩陣已有 runner；請查看 checkpoint／log，不能重複啟動')
    child=None
    def terminate(signum,frame):
        if child and child.poll() is None:child.send_signal(signal.SIGTERM)
        raise KeyboardInterrupt('matrix terminated')
    signal.signal(signal.SIGTERM,terminate)
    env={**os.environ,'WG_PLUGIN_JAR':str(jar),'WG_PLUGIN_SHA256':sha,'WG_COMPACT_EVIDENCE':'1','GRADLE_USER_HOME':str(ROOT/'.work/gradle-home'),'npm_config_cache':str(ROOT/'.work/npm-cache'),'JAVA_HOME':'/usr/lib/jvm/java-25-openjdk-amd64','PYTHONUNBUFFERED':'1'}
    try:
        for name,command,pattern in commands:
            prior=state['cells'].get(name)
            if prior and prior['exit']==0 and prior.get('validated') and prior['sha256']==sha and all((ROOT/f['path']).is_file() and digest(ROOT/f['path'])==f['sha256'] for f in prior['evidence']):
                print('SKIP '+name,flush=True);continue
            if digest(jar)!=sha:raise RuntimeError('測試途中固定jar被修改')
            if any(digest(ROOT/path)!=expected for path,expected in metadata.get('shared_artifacts',{}).items()):raise RuntimeError('測試途中共用產物被修改')
            ports_closed()
            log=out/(name+'.log');receiptfile=out/(name+'-receipt.json')
            if any(file.exists() for file in (log,receiptfile,out/(name+'-results.json'))):
                history=out/'failures'/name/str(time.time_ns());history.mkdir(parents=True)
                for file in (log,receiptfile,out/(name+'-results.json')):
                    if file.exists():shutil.copy2(file,history/file.name)
            started=time.time();state['active']={'name':name,'started':started,'command':command};write(statefile,state)
            print('START '+name,flush=True)
            with log.open('w') as stream:
                child=subprocess.Popen(command,cwd=ROOT,env=env,stdout=stream,stderr=subprocess.STDOUT)
                code=child.wait()
            receipt={'name':name,'command':command,'exit':code,'seconds':time.time()-started,'sha256':sha,'evidence':[],'validated':False}
            sources=sorted(ROOT.glob(pattern),key=lambda p:p.stat().st_mtime_ns)
            if sources and sources[-1].stat().st_mtime>=started:
                source=sources[-1];data=json.loads(source.read_text())
                checks=data.get('steps',data.get('checks',data.get('cases',[])))
                # Performance receipts have a checks object, while acceptance
                # receipts have a list. Do not eagerly evaluate the fallback
                # when a runner explicitly records its overall success.
                good=data['success'] if 'success' in data else bool(checks) and all(c.get('ok',c.get('exit')==0) for c in checks)
                recorded=data.get('plugin_sha256',data.get('artifact_sha256'))
                expected=digest(out/'artifacts/before-667feff.jar') if name in ('perf-before','large-before') else sha
                # The regression wrapper records child receipts; its subprocesses
                # inherit the frozen jar and validate it before starting a server.
                receipt['validated']=code==0 and good and (recorded==expected or name=='phase2-regressions')
                copy=out/(name+'-results.json');shutil.copy2(source,copy)
                receipt['result_source']=str(source.relative_to(ROOT))
                receipt['evidence']=[{'path':str(p.relative_to(ROOT)),'sha256':digest(p)} for p in (copy,log)]
            ports_closed();write(receiptfile,receipt);state['cells'][name]=receipt;state.pop('active',None);write(statefile,state)
            print(('PASS ' if receipt['validated'] else 'FAIL ')+name+' '+str(round(receipt['seconds'],1))+'s',flush=True)
            if not receipt['validated']:return 1
        return 0
    finally:
        if child and child.poll() is None:
            child.send_signal(signal.SIGTERM)
            try:child.wait(120)
            except subprocess.TimeoutExpired:child.kill();child.wait()
        runner_lock.close()

if __name__=='__main__':raise SystemExit(main())
