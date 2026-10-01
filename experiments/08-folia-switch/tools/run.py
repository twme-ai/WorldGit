#!/usr/bin/env python3
"""08 的重跑入口；所有 fixture、server、bot、離線量測全程持 bench.lock。"""
import argparse, fcntl, hashlib, json, os, re, shutil, subprocess, sys, threading, time
from pathlib import Path
from harness import Server, Bot, JAVA, ROOT, WORK, PLUGIN_JAR
OUT = Path(WORK)/'folia-switch'

def overworld(s):
    return Path(s.dir)/'world'/('dimensions/minecraft/overworld' if s.version=='26.2' else '')

def cmd(s, text, kind, timeout=600):
    r=s.cmd(text,kind,timeout)
    if r.get('kind')=='error':raise RuntimeError(r)
    return r

def offline(*args):
    (OUT/'tmp').mkdir(parents=True,exist_ok=True)
    p=subprocess.run([JAVA['1.21.11'],'-XX:-UsePerfData','-Djava.io.tmpdir='+str(OUT/'tmp'),'-Xmx768M','-cp',PLUGIN_JAR,'wgproto.Offline',*map(str,args)],capture_output=True,text=True)
    if p.returncode not in (0,2):raise RuntimeError(p.stdout+p.stderr)
    lines=p.stdout.splitlines()
    return {'returncode':p.returncode,'result':json.loads(lines[-1]) if lines and lines[-1].startswith('{') else p.stdout,'stderr':p.stderr}

def sample(s, bots, tag):
    result=[]
    for i,b in enumerate(bots):
        x=(i%3)*128+16;z=15
        for sy in (8,11):
            r=cmd(s,f'sample {x} {z} {sy} {tag}-{i}-{sy}','sample',60)
            h=b.ask(f'hash {x} {sy} {z} {tag}','hash',60)
            result.append({'bot':i+1,'cx':x,'cz':z,'sy':sy,'server':r['hash'],'client':h['hash'],'nulls':h['nulls'],'match':r['hash']==h['hash'] and h['nulls']==0})
    return result

def diskcopy(s, dest):
    dest.mkdir(parents=True,exist_ok=True)
    w=overworld(s)
    for d in ('region','entities','poi'):
        if (w/d).exists():shutil.copytree(w/d,dest/d,dirs_exist_ok=True)


def run(platform,version,args):
    name=f'{platform}-{version}'
    print(f'BEGIN {name}',flush=True)
    s=Server(platform,version,plugins=(),fresh=True,view=2,extra_props={'difficulty':'peaceful','spawn-monsters':'false'})
    configs=Path(s.dir)/'config'
    globalp=configs/'paper-global.yml';txt=globalp.read_text()
    txt=txt.replace('io-threads: -1','io-threads: 1').replace('worker-threads: -1','worker-threads: 1').replace('  threads: -1','  threads: 2')
    txt=txt.replace('update-checker:\n  enabled: true','update-checker:\n  enabled: false')
    txt=txt.replace('spark:\n  enable-immediately: false\n  enabled: true','spark:\n  enable-immediately: false\n  enabled: false')
    globalp.write_text(txt)
    p=configs/'paper-world-defaults.yml';p.write_text(p.read_text().replace('auto-save-interval: default','auto-save-interval: 6000'))
    w=overworld(s)
    for d in ('region','entities','poi'):
        shutil.rmtree(w/d,ignore_errors=True)
    fixture=offline('fixture',w,4671 if version=='1.21.11' else 4903)
    print(f'FIXTURE {name}: {fixture}',flush=True)
    results={'name':name,'lockHeld':True,'conditions':{'bots':args.bots,'xmx':'3G','view':2,'simulation':2,'regionThreads':2 if platform=='folia' else 1,'chunkWorkers':1,'chunkIO':1,'changedChunks':1008,'sections':4032,'cpuCount':3},'fixture':fixture,'jobs':[]}
    bots=[];rss=[];running=True
    def memory():
        while running:
            try:
                txt=Path(f'/proc/{s.proc.pid}/status').read_text()
                rss.append({'t':time.time(),'rssKiB':int(re.search(r'VmRSS:\s+(\d+)',txt)[1])})
            except Exception:pass
            time.sleep(.5)
    try:
        s.start(xmx='3G')
        threading.Thread(target=memory,daemon=True).start()
        s.send('gamerule random_tick_speed 0');s.send('gamerule spawn_mobs false');s.send('gamerule advance_time false');s.send('gamerule advance_weather false')
        for i in range(args.bots):
            b=Bot(s.port,version,f'WgBot{i+1}');bots.append(b);b.wait_ev('spawn',90)
            cmd(s,f'park WgBot{i+1} {i%3}','parked',90)
            time.sleep(.3)
        print(f'BOTS {name}: {len(bots)}',flush=True)
        init=cmd(s,'init','job_done',1200);results['init']=init
        print(f'INIT {name}: {init["seconds"]:.2f}s errors={init["errors"]}',flush=True)
        time.sleep(8)
        cmd(s,'save','saved',120)
        manifests=Path(s.dir)/'plugins/WorldGitPoc'
        manifestcopy=OUT/'evidence'/name/'manifests';manifestcopy.mkdir(parents=True,exist_ok=True)
        for f in list(manifests.glob('*.tsv'))+list(manifests.glob('entity-*.nbt')):shutil.copy2(f,manifestcopy/f.name)
        tmp=OUT/'verify-current';shutil.rmtree(tmp,ignore_errors=True);diskcopy(s,tmp)
        results['initialA']=offline('verify',tmp,manifestcopy,'A');shutil.rmtree(tmp)
        results['initialBots']=sample(s,bots,'initialA')
        print(f'INITIAL VERIFY {name}: {results["initialA"]}',flush=True)
        if results['initialA']['returncode'] != 0: raise RuntimeError('initial snapshot verification failed')
        for limit in (args.limits or [args.limit]):
            for budget in args.budgets:
                for target in ('B','A'):
                    print(f'SWITCH {name} ->{target} {budget}ms limit={limit}',flush=True)
                    mark=s.mark();start=time.time();job=cmd(s,f'switch {target} {budget} {limit} {args.inflight}','job_done',1200)
                    time.sleep(5);cmd(s,'save','saved',120)
                    tmp=OUT/'verify-current';shutil.rmtree(tmp,ignore_errors=True);diskcopy(s,tmp)
                    verify=offline('verify',tmp,manifestcopy,target);shutil.rmtree(tmp)
                    samples=sample(s,bots,f'{target}-{budget}')
                    job['offline']=verify;job['bots']=samples;job['warnings']=s.errors_in_log(mark)
                    job['rssPeakKiB']=max((m['rssKiB'] for m in rss if m['t']>=start),default=0)
                    results['jobs'].append(job)
                    print(f'DONE {name} {target}/{budget}: {job["seconds"]:.2f}s {job["sectionsPerSecond"]:.1f}sec/s verify={verify["result"]} bots={sum(x["match"] for x in samples)}/{len(samples)}',flush=True)
                    (OUT/f'results-{name}.json').write_text(json.dumps(results,indent=2))
        if not args.skip_cancel:
            print('CANCEL TEST',flush=True);cancel_mark=s.mark();cmd(s,f'switch B 1 1 {args.inflight}','job_start',120);time.sleep(.5);s.send('wgpoc cancel')
            ln=s.wait(r'"kind":"job_done"',120,0 if False else cancel_mark)
            results['cancel']=json.loads(re.search(r'\[WGPOC\] (\{.*\})',ln)[1])
            results['recovery']=cmd(s,f'switch A 5 {args.limit} {args.inflight}','job_done',1200)
            time.sleep(3);cmd(s,'save','saved',120)
        mark=s.mark();s.send('wgpoc protection-test WgBot1');time.sleep(1);results['protectionTests']=[r for r in s.results(mark) if r['kind']=='protection_test']
        results['warnings']=s.errors_in_log()
    except BaseException as e:
        results['failure']=repr(e);print(f'FAIL {name}: {e!r}',flush=True)
        raise
    finally:
        running=False
        try:
            for b in bots:
                try:b.stop()
                except Exception as e:print(f'BOT CLEANUP: {e!r}',flush=True)
        finally:s.stop()
        evidence=OUT/'evidence'/name;evidence.mkdir(parents=True,exist_ok=True)
        for f in ('console.log','server.properties'):
            if (Path(s.dir)/f).exists():shutil.copy2(Path(s.dir)/f,evidence/f)
        p=Path(s.dir)/'plugins/WorldGitPoc/results.jsonl'
        if p.exists():shutil.copy2(p,evidence/'plugin.jsonl')
        results['processRSSPeakKiB']=max((m['rssKiB'] for m in rss),default=0)
        results['memorySamples']=rss
        if 'failure' not in results:
            manifestcopy=OUT/'evidence'/name/'manifests'
            results['afterStopA']=offline('verify',overworld(s),manifestcopy,'A')
        (OUT/f'results-{name}.json').write_text(json.dumps(results,indent=2))
        print(f'END {name} peakRSS={results["processRSSPeakKiB"]}KiB',flush=True)
        if not args.keep_run:shutil.rmtree(s.dir)

if __name__=='__main__':
    ap=argparse.ArgumentParser();ap.add_argument('--server',choices=['folia-1.21.11','folia-26.2','paper-26.2','all'],default='all')
    ap.add_argument('--budgets',nargs='+',type=float,default=[5,10]);ap.add_argument('--bots',type=int,default=3)
    ap.add_argument('--limit',type=int,default=4);ap.add_argument('--limits',nargs='+',type=int);ap.add_argument('--inflight',type=int,default=24)
    ap.add_argument('--skip-cancel',action='store_true');ap.add_argument('--keep-run',action='store_true');args=ap.parse_args()
    if not 3<=args.bots<=10:ap.error('bots must be 3..10')
    OUT.mkdir(parents=True,exist_ok=True)
    print('WAITING bench.lock',flush=True)
    with open(Path(WORK)/'bench.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX);print('ACQUIRED bench.lock',flush=True)
        for name in (['folia-1.21.11','folia-26.2','paper-26.2'] if args.server=='all' else [args.server]):
            platform,version=name.split('-',1)
            try:run(platform,version,args)
            except Exception as e:print(f'PLATFORM FAILED {name}: {e!r}',flush=True)
