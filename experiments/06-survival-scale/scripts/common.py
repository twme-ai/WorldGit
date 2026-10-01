#!/usr/bin/env python3
import contextlib, fcntl, json, os, shutil, subprocess, threading, time, sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[3]
WORK=ROOT/'.work/survival-scale'
EXP=ROOT/'experiments/06-survival-scale'
JAR=EXP/'build/libs/survival-scale.jar'
JAVA={'1.21.11':'/usr/lib/jvm/java-21-openjdk-amd64/bin/java','26.2':'/usr/lib/jvm/java-25-openjdk-amd64/bin/java'}
PORT={'1.21.11':25641,'26.2':25642}
def emit(**x):print(json.dumps({'time':time.time(),**x}),flush=True)
@contextlib.contextmanager
def bench_lock():
    emit(event='lock_wait')
    with open(ROOT/'.work/bench.lock','a') as f:
        fcntl.flock(f,fcntl.LOCK_EX);emit(event='lock_acquired')
        try:yield
        finally:fcntl.flock(f,fcntl.LOCK_UN);emit(event='lock_released')
def write_json(p,x):p=Path(p);p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(x,indent=2,ensure_ascii=False))
def disk_bytes(p):
    return int(subprocess.check_output(['du','-s','-B1',str(p)],text=True).split()[0])
def guard():
    n=disk_bytes(WORK)+disk_bytes(EXP)
    if n>5.8*1024**3:raise RuntimeError(f'disk budget guard: {n}')
    return n
def prepare(ver,name):
    run=WORK/name/'run'
    if run.exists():raise RuntimeError(f'run exists: {run} (delete intentionally before rerun)')
    run.mkdir(parents=True)
    src=ROOT/'.work/servers'/f'paper-{ver}'
    for name in ('server.jar','cache','libraries','versions','eula.txt','bukkit.yml','spigot.yml','commands.yml','config'):
        p=src/name
        if p.exists():(shutil.copytree if p.is_dir() else shutil.copy2)(p,run/name)
    for p in (ROOT/'.work/worlds'/ver/'baseline').iterdir():(shutil.copytree if p.is_dir() else shutil.copy2)(p,run/p.name)
    props={}
    for l in (src/'server.properties').read_text().splitlines():
        if '=' in l and not l.startswith('#'):k,v=l.split('=',1);props[k]=v
    props.update({'server-ip':'127.0.0.1','server-port':str(PORT[ver]),'online-mode':'false','gamemode':'survival','difficulty':'normal','view-distance':'4','simulation-distance':'4','pause-when-empty-seconds':'-1','enable-rcon':'false','enable-query':'false','enable-status':'true','max-players':'10'})
    # 移除來源的 management 憑證，不使用管理 API。
    props.update({'management-server-enabled':'false','management-server-secret':''})
    (run/'server.properties').write_text('\n'.join(f'{k}={v}' for k,v in props.items())+'\n')
    return run
class Server:
    def __init__(self,ver,run,log,xmx='2G'):
        self.log=open(log,'w');self.lines=[];self.cv=threading.Condition()
        self.p=subprocess.Popen([JAVA[ver],'-Xms512M','-Xmx'+xmx,'-XX:ActiveProcessorCount=3','-jar','server.jar','--nogui'],cwd=run,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        threading.Thread(target=self.pump,daemon=True).start()
        self.control_path=WORK/("control-"+ver+".jsonl")
        self.control_seen=len(self.control_path.read_text().splitlines()) if self.control_path.exists() else 0
        threading.Thread(target=self.controls,daemon=True).start()
    def controls(self):
        while self.p.poll() is None:
            try:
                lines=self.control_path.read_text().splitlines() if self.control_path.exists() else []
                for line in lines[self.control_seen:]:
                    command=json.loads(line)["cmd"];self.cmd(command);emit(event="manual_console",cmd=command)
                self.control_seen=len(lines)
            except Exception as e:emit(event="manual_console_error",error=str(e))
            time.sleep(.5)
    def pump(self):
        for s in self.p.stdout:
            self.log.write(s);self.log.flush()
            with self.cv:self.lines.append(s);self.cv.notify_all()
    def wait(self,needle,timeout=180,start=0):
        end=time.monotonic()+timeout
        with self.cv:
            while time.monotonic()<end:
                if any(needle in l for l in self.lines[start:]):return True
                if self.p.poll() is not None:return False
                self.cv.wait(min(1,end-time.monotonic()))
        return False
    def cmd(self,s,needle=None,timeout=120):
        n=len(self.lines);self.p.stdin.write(s+'\n');self.p.stdin.flush()
        if needle and not self.wait(needle,timeout,n):raise RuntimeError('command timeout '+s)
        if not needle:time.sleep(.15)
        return ''.join(self.lines[n:])
    def stop(self):
        if self.p.poll() is None:
            self.cmd('stop')
            try:self.p.wait(timeout=120)
            except subprocess.TimeoutExpired:self.p.kill();self.p.wait()
        self.log.close()
    def __enter__(self):
        if not self.wait('Done (',300):self.stop();raise RuntimeError('server startup failed')
        return self
    def __exit__(self,*a):self.stop()
def sleep_progress(seconds,label):
    end=time.monotonic()+seconds
    while time.monotonic()<end:
        time.sleep(min(20,end-time.monotonic()));emit(event='progress',label=label,remaining_s=round(max(0,end-time.monotonic())))
def snapshot_copy(s,run,target):
    start=time.monotonic();s.cmd('tick freeze');s.cmd('save-all flush','Saved the game',180)
    target.mkdir(parents=True)
    try:
        for p in run.iterdir():
            if p.name.startswith('world') and p.is_dir():shutil.copytree(p,target/p.name)
    finally:s.cmd('tick unfreeze')
    sys.path.insert(0,str(ROOT/'experiments/00-env'))
    import mcnbt
    _,data=mcnbt.read_nbt((target/'world/level.dat').read_bytes(),gz=True)
    return {'snapshot_pause_s':time.monotonic()-start,'game_time':data['Data'].get('Time'),'day_time':data['Data'].get('DayTime')}
def parse_summary(out,prefix='SUMMARY '):
    lines=[s for s in out.splitlines() if s.startswith(prefix)]
    if not lines:return {}
    ans={}
    for kv in lines[-1][len(prefix):].split():
        if '=' in kv:
            k,v=kv.split('=',1)
            try:v=int(v)
            except ValueError:pass
            ans[k]=v
    return ans
def object_sizes(repo):
    files={}
    for d in (repo/'objects').glob('??'):
        if d.is_dir():
            for f in d.iterdir():
                if len(f.name)==38:files[d.name+f.name]=f.stat().st_size
    return files
def tool(args,label,xmx='1G'):
    logs=WORK/'logs';logs.mkdir(exist_ok=True)
    cmd=['/usr/bin/time','-v','-o',str(logs/(label+'.time')),JAVA['26.2'],'-Xmx'+xmx,'--enable-native-access=ALL-UNNAMED','-jar',str(JAR),*map(str,args)]
    t=time.monotonic();r=subprocess.run(cmd,text=True,capture_output=True,cwd=ROOT)
    (logs/(label+'.out')).write_text(r.stdout+r.stderr)
    if r.returncode:raise RuntimeError(f'{label}: {r.stdout}\n{r.stderr}')
    peak=0
    for line in (logs/(label+'.time')).read_text().splitlines():
        if 'Maximum resident set size' in line:peak=int(line.split(':')[-1])*1024
    return r.stdout,{'wall_s':time.monotonic()-t,'rss_bytes':peak,'xmx':xmx}
def capture(repo,world,label,args):
    old=object_sizes(repo) if repo.exists() else {};prev=subprocess.check_output(['git','--git-dir='+str(repo),'rev-parse','main'],text=True).strip() if (repo/'HEAD').exists() else None
    out,timing=tool(['commit' if prev else 'init',world,repo,'-m',label,*args],label)
    new=object_sizes(repo);metrics={**timing,**parse_summary(out),'new_objects':len(new.keys()-old.keys()),'new_loose_bytes':sum(new[x] for x in new.keys()-old.keys()),'loose_objects':len(new),'loose_bytes':sum(new.values())}
    head=subprocess.check_output(['git','--git-dir='+str(repo),'rev-parse','main'],text=True).strip();metrics['head']=head
    if prev:
        diff,_=tool(['diff',repo,prev,head],label+'-diff');metrics['diff']=parse_summary(diff);metrics['categories']={}
        for l in diff.splitlines():
            if l.startswith('CATEGORY '):parts=l.split();metrics['categories'][parts[1]]={k:int(v) for k,v in (f.split('=') for f in parts[2:])}
    return metrics
