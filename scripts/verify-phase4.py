#!/usr/bin/env python3
"""Phase 4 真 Hub/CLI/Paper/Fabric/native git HTTP 驗收；自取 bench.lock，只複製 baseline。"""
import argparse
import fcntl
import hashlib
import http.server
import importlib.util
import json
import sys
import os
from pathlib import Path
import re
import secrets
import shutil
import subprocess
import threading
import time
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'paper/tools'))
from cli_compat import cli_data, repository, DIMENSIONS, verify_all, prepare_all_entities
_spec=importlib.util.spec_from_file_location('phase2',ROOT/'scripts/verify-phase2.py')
p2=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(p2)
JAVA=p2.JAVA
WORK=None
TOKEN=secrets.token_urlsafe(32)

def run(command,**kwargs):
    allow_merging=kwargs.pop('allow_merging',False)
    p=subprocess.run(command,text=True,capture_output=True,timeout=kwargs.pop('timeout',900),**kwargs)
    if p.returncode and allow_merging:
        try:
            value=json.loads(p.stdout)
            if value['data'].get('state')=='MERGING' or value['data'].get('result',{}).get('state')=='MERGING':return p.stdout
        except (ValueError,AttributeError):pass
    if p.returncode: raise RuntimeError((p.stderr+p.stdout).replace(TOKEN,'[REDACTED]'))
    return p.stdout

def cli(world,*args,anonymous=False,auth='basic'):
    if args and args[0]=='init':prepare_all_entities(world)
    env=os.environ.copy();env.pop('WGIT_TOKEN',None);env.pop('WGIT_CREDENTIALS_FILE',None)
    if not anonymous:env.update(WGIT_TOKEN=TOKEN,WGIT_AUTH=auth)
    began=time.monotonic()
    output=run([JAVA['1.21.11'],'-Xmx1500m','-jar',str(WORK/'wgit.jar'),'--world',str(world),'--format=json',*map(str,args)],env=env,allow_merging=True)
    value=cli_data(output)
    with (WORK/'cli.log').open('a') as log:
        log.write(' '.join(map(str,args))+'\n')
        if len(output)>8*1024*1024:
            log.write(json.dumps({'largeOutputOmitted':True,'bytes':len(output),'snapshot':value.get('snapshot'),'dimensions':list(value.get('dimensions',{}))},ensure_ascii=False)+'\n')
        else:log.write(output+'\n')
    return value,time.monotonic()-began

def tool(command,world,*args,phase2=False):
    return run([JAVA['1.21.11'],'-Xmx1g','-cp',str(WORK/'acceptance-tools.jar'),'org.worldgit.core.Phase2AcceptanceTool' if phase2 else 'org.worldgit.core.Phase4AcceptanceTool',command,str(world),*map(str,args)])

class Process:
    def __init__(self,command,directory,log,env=None):
        self.lines=[];self.condition=threading.Condition();self.log=open(log,'w')
        self.proc=subprocess.Popen(command,cwd=directory,env=env,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
        self.thread=threading.Thread(target=self.pump,daemon=True);self.thread.start()
    def pump(self):
        for line in self.proc.stdout:
            line=line.replace(TOKEN,'[REDACTED]');self.log.write(line);self.log.flush()
            with self.condition:self.lines.append(line);self.condition.notify_all()
    def wait(self,text,start=0,timeout=300):
        deadline=time.monotonic()+timeout
        with self.condition:
            while time.monotonic()<deadline:
                if any(text in l for l in self.lines[start:]):return
                if self.proc.poll() is not None:raise RuntimeError('程序提前結束：'+''.join(self.lines[-20:]))
                self.condition.wait(min(1,deadline-time.monotonic()))
        raise RuntimeError('等待逾時：'+text)
    def command(self,command,response=None):
        start=len(self.lines);self.proc.stdin.write(command+'\n');self.proc.stdin.flush()
        if response:self.wait(response,start)
    def stop(self,minecraft=False):
        if self.proc.poll() is None:
            try:
                if minecraft:self.command('stop')
                else:self.proc.terminate()
            except (BrokenPipeError,ProcessLookupError):pass
            try:self.proc.wait(timeout=90)
            except subprocess.TimeoutExpired:self.proc.kill();self.proc.wait()
        self.thread.join(5);self.log.close()

class Hub:
    def __init__(self):
        env=os.environ.copy();env.update(WORLDGIT_HUB_DATA_DIR=str(WORK/'hub-data'),WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN=TOKEN,WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD=secrets.token_urlsafe(24))
        self.process=Process([JAVA['26.2'],'-Xmx1500m','-XX:ActiveProcessorCount=3','-jar',str(WORK/'hub.jar'),'--server.address=127.0.0.1','--server.port=8097','--worldgit.hub.auth.attempts=10000'],WORK,WORK/'hub.log',env)
        try:
            self.process.wait('Started HubApplication')
            # Spring 的 Started log 早於 bootstrap ApplicationRunner，等 PAT 可用再開始。
            deadline=time.monotonic()+30
            while True:
                req=urllib.request.Request('http://127.0.0.1:8097/api/v1/me',headers={'Authorization':'Bearer '+TOKEN})
                try:
                    with urllib.request.urlopen(req,timeout=5) as r:
                        if r.status==200 and json.load(r).get('user',{}).get('username')=='admin':break
                        if time.monotonic()>=deadline:raise RuntimeError('Hub bootstrap PAT 等待逾時')
                        time.sleep(.25)
                except urllib.error.HTTPError as e:
                    if e.code!=401 or time.monotonic()>=deadline:raise
                    time.sleep(.25)
        except BaseException:self.process.stop();raise
    def world(self,name):
        data=json.dumps({'owner':'admin','name':name,'isPublic':True}).encode()
        req=urllib.request.Request('http://127.0.0.1:8097/api/v1/worlds',data=data,headers={'Content-Type':'application/json','Authorization':'Bearer '+TOKEN})
        with urllib.request.urlopen(req,timeout=30) as r:assert r.status==200
        return 'http://127.0.0.1:8097/admin/'+name
    def stop(self):self.process.stop()

def freeze_paper(directory):
    """沿用 Phase 5 fixture：第一個 tick 前凍結，不忽略自然流體／ticks 的差異。"""
    fixture=WORK/'freeze-fixture.jar'
    if not fixture.exists():
        source=ROOT/'.work/servers/paper-1.21.11';classes=WORK/'freeze-classes';classes.mkdir()
        try:
            jars=sorted(source.glob('libraries/**/*.jar'))+sorted(source.glob('versions/**/*.jar'))
            run([str(Path(JAVA['1.21.11']).with_name('javac')),'--release','21','-proc:none','-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),str(ROOT/'cli/tools/Phase5Freeze.java')],timeout=120)
            with zipfile.ZipFile(fixture,'w') as archive:
                archive.write(classes/'Phase5Freeze.class','Phase5Freeze.class')
                archive.writestr('plugin.yml','name: Phase5Freeze\nversion: 1.0\nmain: Phase5Freeze\napi-version: "1.21"\nload: POSTWORLD\n')
        finally:shutil.rmtree(classes,ignore_errors=True)
    plugins=directory/'plugins';plugins.mkdir(exist_ok=True)
    shutil.copy2(fixture,plugins/'phase5-freeze.jar')

def stage_server(version,clone,label,platform):
    dest=WORK/label;dest.mkdir();shutil.copytree(clone,dest/'world')
    if platform=='paper':
        source=ROOT/'.work/servers'/('paper-'+version)
        for name in ['server.jar','cache','libraries','versions','eula.txt','config','bukkit.yml','spigot.yml','commands.yml']:
            p=source/name
            if p.is_dir():shutil.copytree(p,dest/name)
            elif p.exists():shutil.copy2(p,dest/name)
        jar='server.jar'
        freeze_paper(dest)
    else:
        source=ROOT/'.work/fabric-srv'/version
        for name in ['libraries','versions']:
            if (source/name).exists():shutil.copytree(source/name,dest/name)
        shutil.copy2(source/'fabric-server-launch.jar',dest/'fabric-server-launch.jar')
        (dest/'eula.txt').write_text('eula=true\n');jar='fabric-server-launch.jar'
        (dest/'mods').mkdir();shutil.copy2(source/'fabric-api.jar',dest/'mods/fabric-api.jar')
    (dest/'server.properties').write_text('\n'.join(['server-ip=127.0.0.1','server-port='+('25691' if version=='1.21.11' else '25692'),'online-mode=false','view-distance=2','simulation-distance=2','level-name=world','gamemode=creative','max-tick-time=-1','pause-when-empty-seconds=-1','management-server-enabled=false','enable-rcon=false','enforce-secure-profile=false'])+'\n')
    return dest,jar

def load_world(version,clone,label,platform):
    directory,jar=stage_server(version,clone,label,platform);log=WORK/(label+'.log');process=None
    try:
        try:
            process=Process([JAVA[version],'-Xms256m','-Xmx1500m','-XX:ActiveProcessorCount=3','-jar',jar,'--nogui'],directory,log)
            process.wait('Done (');process.command('tick freeze');process.command('forceload add 0 0 31 15');time.sleep(6);process.command('save-all flush','Saved the game')
        finally:
            if process:process.stop(True)
        text=log.read_text();errors=[l for l in text.splitlines() if re.search(r'\bERROR\b|Exception|Watchdog',l)]
        assert not errors,errors[:8]
        verify=verify_all(lambda world,*words:cli(world,*words)[0],directory)
        inspect=tool('inspect',directory,phase2=True)
        assert int(re.search(r'blockLightSections=(\d+)',inspect)[1])>0,inspect
        assert int(re.search(r'skyLightSections=(\d+)',inspect)[1])>0,inspect
        assert 'duplicateUUIDs=0' in inspect,inspect
        assert 'minecraft:librarian' in inspect,inspect
        seed=tool('seed',directory)
        return {'errors':0,'verify':verify,'light_poi_entities':inspect,'seed':seed}
    finally:shutil.rmtree(directory)

def paper_source(version):
    p2.WORK=WORK;directory=p2.stage(version,'source-'+version)
    tool('synthetic',directory,441,phase2=True);tool('mutate',directory,4,phase2=True)
    freeze_paper(directory)
    p=p2.Server(version,directory,WORK/('source-'+version+'.log'))
    try:
        p.wait('Done (');p.command('tick freeze');p.command('forceload add 0 0 31 15');time.sleep(5);p.command('save-all flush','Saved the game')
    finally:p.stop()
    return directory

def version_case(hub,version):
    baseline=ROOT/'.work/worlds'/version/'baseline';paper=ROOT/'.work/servers'/('paper-'+version)
    if not baseline.exists() or not (paper/'server.jar').exists():return {'skipped':'baseline/Paper 不存在'}
    before=p2.manifest(baseline);source=paper_source(version);url=hub.world('phase4-'+version.replace('.','-'));a=WORK/('a-'+version);b=WORK/('b-'+version)
    result={}
    try:
        cli(source,'init','--with-dimensions','all');cli(source,'remote','add','origin',url);cli(source,'tag','v1','-m','release','--all');push,seconds=cli(source,'push','--tags','--all',auth='bearer');result['push']={'seconds':seconds,'transfer':push}
        result['clone'],seconds=cli('.', 'clone',url,a,anonymous=True);result['clone_seconds']=seconds
        result['paper']=load_world(version,a,'paper-'+version,'paper')
        if (ROOT/'.work/fabric-srv'/version/'fabric-server-launch.jar').exists():result['fabric']=load_world(version,a,'fabric-'+version,'fabric')
        else:result['fabric']={'skipped':'Fabric dedicated 不存在'}
        cli('.', 'clone',url,b,anonymous=True)
        tool('edit',a,0,'gold_block');cli(a,'commit','-m','A');tool('edit',b,8,'diamond_block');cli(b,'commit','-m','B');cli(a,'push')
        merge,_=cli(b,'pull');assert merge['result']['state']=='COMPLETE' and not merge['fastForward'],merge;cli(b,'push');ff,_=cli(a,'pull','--ff-only');assert ff['fastForward'];result['pull_merge']=merge;result['pull_ff']=ff
        for world in [a,b]:assert cli(world,'verify')[0]['state']=='COMPLETE'
        # 各維度完整 commit graph 必須相同（snapshot UUID 不做跨維度配對）。
        la=cli(a,'log')[0];lb=cli(b,'log')[0];assert la==lb
        tool('edit',a,2,'stone');cli(a,'commit','-m','conflict A');tool('edit',b,2,'dirt');cli(b,'commit','-m','conflict B');cli(a,'push');conflict,_=cli(b,'pull');assert conflict['result']['state']=='MERGING';cli(b,'resolve','all','--theirs');cli(b,'merge','--continue');cli(b,'push');cli(a,'pull');result['conflict']=conflict
        # 在本地與 Hub 裸 repo 上用相同 tips 合併，clone 結果與本地 merge 的世界逐格比較。
        cli(a,'branch','topic','--all');tool('edit',a,10,'emerald_block');cli(a,'commit','-m','main');cli(a,'switch','topic','--all');tool('edit',a,12,'lapis_block');cli(a,'commit','-m','topic');cli(a,'push','origin','topic','--all');cli(a,'push','origin','main','--all');cli(a,'switch','main','--all');cli(a,'merge','topic')
        bare=WORK/'hub-data/repos/admin'/('phase4-'+version.replace('.','-'));result['bare_clean']=tool('bare-merge',bare,'main','topic')
        merged=WORK/('merged-'+version);cli('.', 'clone',url,merged,anonymous=True)
        # CLI 合併與 Hub 合併的 commit identity 可不同；中性 tree ids 必須完全一致。
        localtrees=json.loads(tool('trees',a))
        remotetrees=json.loads(tool('trees',merged))
        assert localtrees==remotetrees,(localtrees,remotetrees)
        result['bare_loaded']=load_world(version,merged,'bare-paper-'+version,'paper');shutil.rmtree(merged)
        cli(b,'pull');cli(b,'branch','topic-conflict');tool('edit',b,3,'gold_block');cli(b,'commit','-m','main conflict');cli(b,'switch','topic-conflict','--all');tool('edit',b,3,'diamond_block');cli(b,'commit','-m','topic conflict');cli(b,'push','origin','topic-conflict','--all');cli(b,'push','origin','main','--all');cli(b,'switch','main','--all');cli(b,'merge','topic-conflict');cli(b,'resolve','all','--base');cli(b,'merge','--continue')
        result['bare_conflict']=tool('bare-merge',bare,'main','topic-conflict','base');cli('.', 'clone',url,merged,anonymous=True)
        localtrees=json.loads(tool('trees',b));remotetrees=json.loads(tool('trees',merged));assert localtrees==remotetrees
        result['bare_conflict_loaded']=load_world(version,merged,'bare-conflict-paper-'+version,'paper');shutil.rmtree(merged)
        cli(a,'export','v1',WORK/('release-'+version+'.zip'));result['baseline_unchanged']=p2.manifest(baseline)==before
        print(version,'Hub/Paper/Fabric/pull/bare merge PASS',flush=True);return result
    finally:
        (WORK/(version+'-partial.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2))
        for path in [source,a,b]:shutil.rmtree(path,ignore_errors=True)
        assert p2.manifest(baseline)==before

class GitHttp(http.server.BaseHTTPRequestHandler):
    project=None
    def log_message(self,*args):pass
    def do_GET(self):self.backend()
    def do_POST(self):self.backend()
    def backend(self):
        body=b''
        if self.headers.get('Transfer-Encoding')=='chunked':
            pieces=[]
            while True:
                n=int(self.rfile.readline().split(b';')[0],16)
                if not n:self.rfile.readline();break
                pieces.append(self.rfile.read(n));self.rfile.read(2)
            body=b''.join(pieces)
        elif self.headers.get('Content-Length'):body=self.rfile.read(int(self.headers['Content-Length']))
        path,_,query=self.path.partition('?')
        env=os.environ.copy();env.update(GIT_PROJECT_ROOT=str(self.project),GIT_HTTP_EXPORT_ALL='1',PATH_INFO=path,QUERY_STRING=query,REQUEST_METHOD=self.command,CONTENT_TYPE=self.headers.get('Content-Type',''),CONTENT_LENGTH=str(len(body)),REMOTE_USER='tester',REMOTE_ADDR='127.0.0.1')
        if self.headers.get('Content-Encoding'):env['HTTP_CONTENT_ENCODING']=self.headers['Content-Encoding']
        p=subprocess.run(['git','http-backend'],input=body,capture_output=True,env=env)
        headers,_,data=p.stdout.partition(b'\r\n\r\n');lines=headers.decode().splitlines();code=200
        for line in lines:
            if line.lower().startswith('status:'):code=int(line.split()[1])
        self.send_response(code)
        for line in lines:
            key,sep,value=line.partition(':')
            if sep and key.lower()!='status':self.send_header(key,value.strip())
        self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data)

def generic_http():
    path=WORK/'generic';path.mkdir();GitHttp.project=path
    for d in ['overworld','the_nether','the_end']:
        repo=path/('minecraft.'+d+'.git');run(['git','init','--bare','--initial-branch=main',str(repo)]);run(['git','--git-dir='+str(repo),'config','http.receivepack','true'])
    server=http.server.ThreadingHTTPServer(('127.0.0.1',8098),GitHttp);thread=threading.Thread(target=server.serve_forever,daemon=True);thread.start()
    source=WORK/'generic-source';clone=WORK/'generic-clone'
    try:
        shutil.copytree(ROOT/'core/src/test/resources/fixtures/1.21.11',source);cli(source,'init','--with-dimensions','all');url='http://127.0.0.1:8098/{dimension}.git';cli(source,'remote','add','origin',url);result,_=cli(source,'push','--all');cli('.', 'clone',url,clone,anonymous=True);assert cli(clone,'verify')[0]['state']=='COMPLETE';return {'transfer':result,'template':url,'verify':'COMPLETE'}
    finally:server.shutdown();server.server_close();thread.join();shutil.rmtree(source,ignore_errors=True);shutil.rmtree(clone,ignore_errors=True)

def scale(hub):
    baseline=ROOT/'.work/worlds/26.2/baseline'
    if not baseline.exists():return {'skipped':'baseline 不存在'}
    classes=WORK/'scale-classes';classes.mkdir()
    source=WORK/'scale-source';clone=WORK/'scale-clone';url=hub.world('scale')
    try:
        prepared=ROOT/'.work/phase4-scale-prepared'
        large=ROOT/'.work/phase1/scale/run'
        if prepared.exists() and not (prepared/'metadata.json').exists():
            shutil.rmtree(prepared)
        if (prepared/'source').exists():
            shutil.copytree(prepared/'source',source)
            cached=json.loads((prepared/'metadata.json').read_text());fixture=cached['fixture'];init_seconds=cached['init_seconds']
        elif (large/'world/level.dat').exists():
            shutil.copytree(large,source,ignore=shutil.ignore_patterns('.worldgit','session.lock'))
            fixture='20,521 full chunk：Phase 1 從 Phase 0 初始物件重建的世界複本；忽略舊 repo'
        else:
            run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/javac','-cp',str(WORK/'wgit.jar'),'-d',str(classes),str(ROOT/'paper/tools/ScaleFixture.java')])
            fixture=run([JAVA['1.21.11'],'-Xmx1500m','-cp',str(classes)+':'+str(WORK/'wgit.jar'),'ScaleFixture','26.2',str(baseline),str(source),'122'],timeout=900)
        if not (prepared/'source').exists():
            init,init_seconds=cli(source,'init','--with-dimensions','all')
            prepared.mkdir();shutil.copytree(source,prepared/'source');(prepared/'metadata.json').write_text(json.dumps({'fixture':fixture,'init_seconds':init_seconds}))
        cli(source,'remote','add','origin',url);first,seconds=cli(source,'push','--all');cloned,clone_seconds=cli('.', 'clone',url,clone,anonymous=True)
        tool('edit',source,5,'gold_block');cli(source,'commit','-m','incremental');incremental,incremental_seconds=cli(source,'push','--all');cli(clone,'pull');assert cli(clone,'verify')[0]['state']=='COMPLETE'
        for transfer in [first,cloned,incremental]:
            entries=transfer.values() if 'packs' not in transfer else [{'data':transfer}]
            assert all(p['preparedBytes']<=95000000 and (p['wireBytes'] is None or p['wireBytes']<=95000000) for entry in entries for ps in entry['data']['packs'].values() for p in ps)
        # 失敗保留已初始化複本以便重跑；成功後清除本次中間產物。
        shutil.rmtree(prepared)
        return {'fixture':fixture,'init_seconds':init_seconds,'first_push_seconds':seconds,'first_push':first,'clone_seconds':clone_seconds,'clone':cloned,'incremental_seconds':incremental_seconds,'incremental':incremental}
    finally:shutil.rmtree(source,ignore_errors=True);shutil.rmtree(clone,ignore_errors=True);shutil.rmtree(WORK/'hub-data/repos/admin/scale',ignore_errors=True)

def main():
    global WORK
    parser=argparse.ArgumentParser();parser.add_argument('--results-dir',type=Path,default=ROOT/'.work/phase4-core');parser.add_argument('--skip-scale',action='store_true');parser.add_argument('--only-scale',action='store_true');args=parser.parse_args();WORK=args.results_dir.resolve();WORK.mkdir(parents=True,exist_ok=False)
    results={};hub=None
    for source,target in [(ROOT/'cli/build/libs/wgit.jar',WORK/'wgit.jar'),(ROOT/'cli/build/libs/acceptance-tools.jar',WORK/'acceptance-tools.jar'),(ROOT/'hub/build/libs/worldgit-hub.jar',WORK/'hub.jar')]:shutil.copy2(source,target)
    try:
        with (ROOT/'.work/bench.lock').open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX)
            hub=Hub()
            for version in ([] if args.only_scale else ['1.21.11','26.2']):
                results[version]=version_case(hub,version);(WORK/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
            if not args.only_scale:results['generic_http']=generic_http()
            if not args.skip_scale:results['scale']=scale(hub)
    finally:
        if hub:hub.stop()
        (WORK/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
        for name in ['wgit.jar','acceptance-tools.jar','hub.jar','freeze-fixture.jar']: (WORK/name).unlink(missing_ok=True)
if __name__=='__main__':main()
