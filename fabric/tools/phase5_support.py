"""Phase 5 Fabric 驗收共用：真客戶端（Xvfb）控制、聊天 JSON 解析、證據記錄。呼叫端已持 bench.lock。"""
import json,os,re,shutil,sys,time,subprocess
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'paper/tools'))
import importlib.util
spec=importlib.util.spec_from_file_location('phase1_process',ROOT/'fabric/tools/accept-paper.py');_module=importlib.util.module_from_spec(spec);spec.loader.exec_module(_module)
Process=_module.Process
SECRET_ENV='phase5-arbitrary-secret'
SECRET_YAML='yaml-arbitrary-secret'
SCREENSHOTS=ROOT/'fabric/docs/screenshots/phase5'

from versions import project

class Recorder:
    """每個檢查寫入 result.json；失敗立即丟出 AssertionError。"""
    def __init__(self,path,result):self.path=path;self.result=result
    def save(self):self.path.write_text(json.dumps(self.result,ensure_ascii=False,indent=2)+'\n')
    def check(self,label,ok,**data):
        self.result['steps'].append({'name':label,'ok':bool(ok),**data});self.save();print(('PASS ' if ok else 'FAIL ')+label,flush=True)
        if not ok:raise AssertionError(label+' '+json.dumps(data,ensure_ascii=False)[:1500])

class Client:
    """Gradle runClientGameTest（Phase5ClientGameTest）；control 目錄以 request/response JSON 溝通。"""
    def __init__(self,work,version,single,port=0,language='en_us',artifact=None,world_source=None,server_config=None):
        self.sequence=0;self.work=work;self.version=version;self.single=single
        self.control=work/'client-control';self.control.mkdir(parents=True,exist_ok=True)
        self.run=ROOT/'.work/worlds/fabric-gametest'/(version+'-phase5')
        shutil.rmtree(self.run/'screenshots',ignore_errors=True)
        if artifact:
            config=self.run/'config';config.mkdir(parents=True,exist_ok=True)
            (config/'worldgit-server.yml').write_text('locale: en_us\nauto-commit:\n  on-logout: false\n  on-stop: false\n  interval-minutes: 0\n')
        legacy=version in ('1.20.1','1.20.4')
        if legacy:
            if not single: raise ValueError('Legacy client validation must run as one integrated Minecraft instance')
            world=self.run/'saves/WorldGitPhase5'
            shutil.rmtree(world,ignore_errors=True);world.parent.mkdir(parents=True,exist_ok=True)
            baseline=ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+version)
            shutil.copytree(world_source or baseline/'world',world)
            if world_source is None:
                for source,name in [(baseline/'world_nether/DIM-1','DIM-1'),(baseline/'world_the_end/DIM1','DIM1')]:
                    if source.is_dir() and not (world/name).exists():shutil.copytree(source,world/name)
            with (work/'fixture-prepare.log').open('w') as log:
                subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/java','-cp',str(ROOT/'cli/build/libs/wgit.jar'),str(ROOT/'fabric/tools/LegacySingleWorld.java'),str(world)],check=True,stdout=log,stderr=subprocess.STDOUT)
        if server_config is not None:
            config=self.run/'config';config.mkdir(parents=True,exist_ok=True)
            (config/'worldgit-server.yml').write_text(server_config)
        tmp=ROOT/'.work/fabric-tmp';tmp.mkdir(exist_ok=True)
        env={**os.environ,'JAVA_HOME':'/usr/lib/jvm/java-25-openjdk-amd64','GRADLE_USER_HOME':str(ROOT/'.work/gradle-home'),'ALSOFT_DRIVERS':'null','LIBGL_ALWAYS_SOFTWARE':'1','GALLIUM_DRIVER':'llvmpipe','LP_NUM_THREADS':'3','XDG_CACHE_HOME':str(tmp/'cache'),'XDG_CONFIG_HOME':str(tmp/'config'),'TMPDIR':str(tmp)}
        cmd=['xvfb-run','-a','-s','-screen 0 1280x720x24 -ac','./gradlew','--no-daemon','--configure-on-demand','--max-workers=1','-PwgtestPhase5=true','-PwgtestPhase5Dir='+str(self.control),'-PwgtestPhase5Single='+str(single).lower(),'-PwgtestPhase5Language='+language]
        if port:cmd+=['-PwgtestPaperPort='+str(port),'-PwgtestPaperReady='+str(self.control)]
        if artifact:cmd+=['-PwgtestPerformanceArtifact='+str(Path(artifact).resolve())]
        self.started=time.time()
        task='runLegacyTestClient' if legacy else 'runPerformanceClient' if artifact else 'runClientGameTest'
        self.process=Process(cmd+[f':fabric:{project(version)}:{task}'],ROOT,work/'client.log',env)
        try:self.ready=self.waitfile('ready.json',900)
        except BaseException:self.stop();raise
    def waitfile(self,name,timeout=300):
        deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            file=self.control/name
            if file.exists():return json.loads(file.read_text())
            if self.process.proc.poll() is not None:raise RuntimeError('client exited: '+str(self.process.lines[-15:]))
            time.sleep(.1)
        raise TimeoutError('client response '+name+' '+str(self.process.lines[-12:]))
    def action(self,action,timeout=900,**data):
        self.sequence+=1;name=f'request-{self.sequence}.json';tmp=self.control/(name+'.tmp');tmp.write_text(json.dumps({'action':action,**data}));tmp.replace(self.control/name)
        return self.waitfile(f'response-{self.sequence}.json',timeout)
    def command(self,text,**data):return self.action('command',command=text,**data)
    def chat_mark(self):return self.action('chat',**{'from':10**9})['size']
    def chat(self,mark=0):return self.action('chat',**{'from':mark})['messages']
    def wait_chat(self,mark,pattern,timeout=240):
        rx=re.compile(pattern);deadline=time.monotonic()+timeout
        while time.monotonic()<deadline:
            rows=self.chat(mark)
            if any(rx.search(r['text']) for r in rows):return rows
            time.sleep(.3)
        raise TimeoutError(pattern+' '+str([r['text'] for r in self.chat(mark)][-8:]))
    def shot(self,name,destination=None):
        """真正 framebuffer 截圖；預設保存在本次 evidence，發布時可明確指定目錄。"""
        result=self.action('screenshot',name=name);source=next((self.run/'screenshots').glob('*'+result['file'].rsplit('.',1)[0]+'*.png'),None) or (self.run/'screenshots'/result['file'])
        folder=Path(destination) if destination is not None else self.work/'screenshots'
        folder.mkdir(parents=True,exist_ok=True)
        target=folder/(('single' if self.single else 'dedicated')+'-'+self.version+'-'+name+'.png');shutil.copy2(source,target);return target
    def finish(self):
        self.action('stop');self.process.wait('FABRIC5 DONE',180);self.process.proc.wait(180)
        if self.process.proc.returncode:raise RuntimeError('client test failed')
    def stop(self):
        if self.process:self.process.stop()

# ---- 聊天 JSON ------------------------------------------------------------------

def walk(value):
    if isinstance(value,dict):
        yield value
        for child in value.values():yield from walk(child)
    elif isinstance(value,list):
        for child in value:yield from walk(child)

def events(rows,action):
    """所有 click_event／clickEvent 中 action 相符者：(value, 所在 JSON)。"""
    found=[]
    for row in rows:
        for node in walk(row.get('json')):
            for key in ('click_event','clickEvent'):
                click=node.get(key)
                if isinstance(click,dict) and click.get('action')==action:
                    found.append(click.get('command') or click.get('value') or click.get('url') or '')
    return found

def hover_text(rows):
    out=[]
    for row in rows:
        for node in walk(row.get('json')):
            for key in ('hover_event','hoverEvent'):
                hover=node.get(key)
                if isinstance(hover,dict):out.append(json.dumps(hover,ensure_ascii=False))
    return out

def texts(rows):return [r.get('t') or r.get('text') or '' for r in rows]

HUMAN_BAD=re.compile(r'Counts\[|CommitResult\[|Batch\[|Result\[|\{head=|added=\d+, removed=|dimensions=\{|=\{minecraft:')

def humanly(lines):
    """終止訊息不可含 Java record／Map toString。"""
    return not any(HUMAN_BAD.search(line) for line in lines)
