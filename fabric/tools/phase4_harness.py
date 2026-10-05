"""Phase 4 共用真客戶端控制；呼叫端已持 bench.lock，無秘密 command line。"""
import fcntl,importlib.util,json,os,shutil,subprocess,time,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'paper/tools'))
from cli_compat import repository

def retain_difference(world,work,cli):
    """刪除失敗副本前保留完整 diff 與變動 metadata blob，不放寬 verify。"""
    difference=cli(world,'diff','--blocks')
    (work/'final-difference.json').write_text(json.dumps(difference,ensure_ascii=False,indent=2)+'\n')
    for dimension,data in difference.items():
        for change in data.get('metadata',[]):
            for side in ['beforeId','afterId']:
                oid=change.get(side)
                if not oid:continue
                blob=subprocess.check_output(['git','--git-dir',str(repository(world,dimension,'1.21.11' if (Path(world)/'DIM-1').is_dir() else '26.2')),'cat-file','blob',oid])
                (work/(oid+'.blob')).write_bytes(blob)
    return difference
spec=importlib.util.spec_from_file_location('phase4_process',ROOT/'fabric/tools/accept-paper.py');module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
class BenchLock:
    """使用阻塞 flock 排隊，避免序列回歸反覆搶先非阻塞 poller。"""
    def __enter__(self):
        self.file=(ROOT/'.work/bench.lock').open('a');fcntl.flock(self.file,fcntl.LOCK_EX);return self
    def __exit__(self,*_):
        fcntl.flock(self.file,fcntl.LOCK_UN);self.file.close()
class Client:
    def __init__(self,work,version,single=False,port=0):
        self.sequence=0;self.work=work;self.version=version;self.control=work/'client-control';self.control.mkdir()
        self.run=ROOT/'.work/worlds/fabric-gametest'/(version+'-phase4');self.single=single
        self.project='mc1_21_11' if version=='1.21.11' else 'mc26_2'
        tmp=ROOT/'.work/fabric-tmp';tmp.mkdir(exist_ok=True)
        env={**os.environ,'JAVA_HOME':'/usr/lib/jvm/java-25-openjdk-amd64','GRADLE_USER_HOME':str(ROOT/'.work/gradle-home'),'ALSOFT_DRIVERS':'null','LIBGL_ALWAYS_SOFTWARE':'1','GALLIUM_DRIVER':'llvmpipe','LP_NUM_THREADS':'3','XDG_CACHE_HOME':str(tmp/'cache'),'XDG_CONFIG_HOME':str(tmp/'config'),'TMPDIR':str(tmp)}
        cmd=['xvfb-run','-a','-s','-screen 0 1280x720x24 -ac','./gradlew','--no-daemon','--configure-on-demand','--max-workers=1','-PwgtestPhase4=true','-PwgtestPhase4Dir='+str(self.control),'-PwgtestPhase4Single='+str(single).lower()]
        if port:cmd+=['-PwgtestPaperPort='+str(port),'-PwgtestPaperReady='+str(self.control)]
        self.started=time.time();self.process=module.Process(cmd+[':fabric:'+self.project+':runPhase4ProductionClient'],ROOT,work/'client.log',env)
        try:self.ready=self.waitfile('ready.json',600)
        except BaseException:self.stop();raise
    def waitfile(self,name,timeout=300):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            file=self.control/name
            if file.exists():return json.loads(file.read_text())
            if self.process.proc.poll() is not None:raise RuntimeError('client exited: '+str(self.process.lines[-12:]))
            if any('Incompatible mods found!' in line for line in self.process.lines):raise RuntimeError('production Loader dependency failure: '+str(self.process.lines[-16:]))
            time.sleep(.1)
        raise TimeoutError('client response '+name+' '+str(self.process.lines[-12:]))
    def action(self,action,timeout=900,**data):
        self.sequence+=1;name=f'request-{self.sequence}.json';tmp=self.control/(name+'.tmp');tmp.write_text(json.dumps({'action':action,**data}));tmp.replace(self.control/name)
        return self.waitfile(f'response-{self.sequence}.json',timeout)
    def stop(self):
        if self.process:self.process.stop()
    def finish(self):
        self.action('stop');self.process.wait('FABRIC4 DONE',120);self.process.proc.wait(120)
        if self.process.proc.returncode:raise RuntimeError('client test failed')
        self.screenshots();self.stop()
    def screenshots(self):
        dest=ROOT/'fabric/docs/screenshots/phase4';dest.mkdir(parents=True,exist_ok=True);files=[]
        selected=set()
        for p in sorted((self.run/'screenshots').glob('*phase4*.png')):
            if p.stat().st_mtime>=self.started:
                # 每場景保留第一張：comments-visible 的第一張含注入測試字串。
                scene=p.name[p.name.index('phase4'):]
                if scene in selected:continue
                selected.add(scene)
                target=dest/(('single' if self.single else 'dedicated')+'-'+self.version+'-'+scene)
                shutil.copy2(p,target);files.append(str(target.relative_to(ROOT)))
        (self.work/'screenshots.json').write_text(json.dumps(files,indent=2));return files
