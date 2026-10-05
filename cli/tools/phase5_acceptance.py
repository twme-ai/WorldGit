#!/usr/bin/env python3
"""Phase 5：真 Hub＋SQLite、兩版 Paper、世界 ZIP 攜帶、獨立分支與遷移。自持 bench.lock。"""
import argparse, hashlib, importlib.util, json, os, re, secrets, shutil, subprocess, sys, time, urllib.request, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'paper/tools'))
import harness
spec=importlib.util.spec_from_file_location('p4',ROOT/'scripts/verify-phase4.py');p4=importlib.util.module_from_spec(spec);spec.loader.exec_module(p4)
MAIN='minecraft:overworld';NETHER='minecraft:the_nether'
WORK=None;TOKEN=secrets.token_urlsafe(32);RESULT={}
def freeze_fixture():
    source=ROOT/'.work/servers/paper-1.21.11';classes=WORK/'freeze-classes';classes.mkdir()
    # 以兩版共用的公開 Paper API 編譯，fixture 不放入任何正式 WorldGit 產物。
    jars=sorted(source.glob('libraries/**/*.jar'))+sorted(source.glob('versions/**/*.jar'))
    subprocess.run([str(Path(harness.JAVA['1.21.11']).with_name('javac')),'--release','21','-proc:none','-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),str(ROOT/'cli/tools/Phase5Freeze.java')],check=True,timeout=120)
    with zipfile.ZipFile(WORK/'freeze-fixture.jar','w') as jar:
        jar.write(classes/'Phase5Freeze.class','Phase5Freeze.class')
        jar.writestr('plugin.yml','name: Phase5Freeze\nversion: 1.0\nmain: Phase5Freeze\napi-version: "1.21"\nload: POSTWORLD\n')
    shutil.rmtree(classes)
def freeze_server(directory):
    plugins=directory/'plugins';plugins.mkdir(exist_ok=True)
    shutil.copy2(WORK/'freeze-fixture.jar',plugins/'phase5-freeze.jar')
def cli(world,*args,expected=(0,)):
    proc=subprocess.run([harness.JAVA['1.21.11'],'-Xmx1g','-jar',str(WORK/'wgit.jar'),'--world',str(world),'--format=json',*map(str,args)],text=True,capture_output=True,timeout=900,env={**os.environ,'WGIT_TOKEN':TOKEN,'WGIT_USERNAME':'admin'})
    with (WORK/'cli.log').open('a') as log:log.write(' '.join(map(str,args))+'\n'+proc.stdout.replace(TOKEN,'[REDACTED]')+'\n'+proc.stderr.replace(TOKEN,'[REDACTED]')+'\n')
    if proc.returncode not in expected:raise AssertionError(proc.stdout+proc.stderr)
    value=json.loads(proc.stdout);assert value['result']['status'] in ['SUCCESS','NO_OP'] if proc.returncode==0 else True
    return value['data']
def api(method,path,body=None):
    req=urllib.request.Request('http://127.0.0.1:8091/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers={'Authorization':'Bearer '+TOKEN,'Content-Type':'application/json'},method=method)
    with urllib.request.urlopen(req,timeout=30) as response:return json.load(response)
def repo(world,dimension,version):
    return world/'.worldgit' if dimension==MAIN else world/('DIM-1' if version=='1.21.11' else 'dimensions/minecraft/the_nether')/'.worldgit'
def edit(world,dimension):
    subprocess.run([harness.JAVA['1.21.11'],'-Xmx512m','-cp',str(WORK/'acceptance-tools.jar'),str(ROOT/'cli/tools/Phase5Edit.java'),str(world),dimension],check=True,timeout=120)
def head(path):return subprocess.check_output(['git','--git-dir='+str(path),'rev-parse','HEAD'],text=True).strip()
def migrate_case(world,version,url,mode):
    target=WORK/('migration-'+mode);shutil.copytree(world,target/'world');w=target/'world'
    ids=[MAIN,NETHER];before={d:head(repo(w,d,version)) for d in ids}
    if mode=='external':
        for d in ids:
            old=target/'.worldgit/world'/d.replace(':','.');old.parent.mkdir(parents=True,exist_ok=True);shutil.move(repo(w,d,version),old)
    else:
        staging=w/'.old-root';staging.mkdir()
        for d in ids:shutil.move(repo(w,d,version),staging/d.replace(':','.'))
        staging.rename(w/'.worldgit')
    result=cli(w,'migrate');assert len(result)==2,result
    assert {d:head(repo(w,d,version)) for d in ids}==before
    edit(w,MAIN);cli(w,'--dimension',MAIN,'commit','-m','after migration '+mode);cli(w,'--dimension',MAIN,'push')
    assert cli(w,'status')['dimensions'].keys()=={MAIN,NETHER}
    shutil.rmtree(target);return {'dimensions':2,'historyPreserved':True,'commitPush':True}
def case(version):
    baseline=ROOT/'.work/worlds'/version/'baseline';before=p4.p2.manifest(baseline);result={};source=None;server=None
    world=WORK/('world-'+version);moved=WORK/('moved-'+version);clone=WORK/('clone-'+version);archive=WORK/('world-'+version+'.zip')
    try:
        source=p4.paper_source(version)
        freeze_server(source)
        generated=p4.Process([harness.JAVA[version],'-Xmx1500m','-XX:ActiveProcessorCount=3','-jar','server.jar','nogui'],source,WORK/('nether-source-'+version+'.log'))
        try:
            generated.wait('Done (');generated.wait('PHASE5 frozen before first tick');generated.command('execute in minecraft:the_nether run forceload add 0 0 31 15');time.sleep(12);generated.command('save-all flush','Saved the game')
        finally:generated.stop(True)
        shutil.copytree(source/'world',world)
        if version=='1.21.11':
            for wrapper,dimension in [('world_nether','DIM-1'),('world_the_end','DIM1')]:
                src=source/wrapper/dimension
                if src.exists():shutil.copytree(src,world/dimension,dirs_exist_ok=True)
        shutil.rmtree(source);source=None
        initialized=cli(world,'init','--with-dimensions','nether');assert set(initialized['dimensions'])=={MAIN,NETHER}
        cli(world,'--dimension',MAIN,'branch','surface');cli(world,'--dimension',MAIN,'switch','surface');edit(world,MAIN);cli(world,'--dimension',MAIN,'commit','-m','surface independent')
        mainhead=head(repo(world,MAIN,version));cli(world,'--dimension',NETHER,'branch','cavern');cli(world,'--dimension',NETHER,'switch','cavern');edit(world,NETHER);cli(world,'--dimension',NETHER,'commit','-m','cavern independent')
        assert head(repo(world,MAIN,version))==mainhead
        heads={d:head(repo(world,d,version)) for d in [MAIN,NETHER]};result['independentBranches']=True
        with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as zip:
            for file in world.rglob('*'):
                if file.is_file():zip.write(file,file.relative_to(world))
        with zipfile.ZipFile(archive) as zip:
            assert '.worldgit/HEAD' in zip.namelist();assert any(n.endswith('/.worldgit/HEAD') for n in zip.namelist());zip.extractall(moved)
        assert set(cli(moved,'status')['dimensions'])=={MAIN,NETHER};assert all(g['nodes'] for g in cli(moved,'log','--graph','--all').values());result['portableZip']=True
        url='http://127.0.0.1:8091/admin/phase5-'+version.replace('.','-');api('POST','/worlds',{'name':'phase5-'+version.replace('.','-'),'isPublic':True})
        cli(moved,'remote','add','origin',url);cli(moved,'--dimension',MAIN,'push');cli(moved,'--dimension',NETHER,'push');result['independentPush']=True
        # 預設分支也是 main；選取主世界 surface 與地獄 cavern，兩維度不要求同名。
        cloned=cli('.', 'clone',url,clone,'--branch',MAIN+'=surface','--branch',NETHER+'=cavern');assert set(cloned['dimensions'])=={MAIN,NETHER},cloned
        assert {d:head(repo(clone,d,version)) for d in [MAIN,NETHER]}==heads;result['cloneBranches']=True
        server,jar=p4.stage_server(version,clone,'paper-'+version,'paper');process=None
        freeze_server(server)
        try:
            process=p4.Process([harness.JAVA[version],'-Xmx1500m','-XX:ActiveProcessorCount=3','-jar',jar,'nogui'],server,WORK/('paper-'+version+'.log'))
            process.wait('Done (');process.wait('PHASE5 frozen before first tick');process.command('save-all flush','Saved the game')
        finally:
            if process:process.stop(True)
        errors=[line for line in (WORK/('paper-'+version+'.log')).read_text().splitlines() if re.search(r'\bERROR\b|Exception|Watchdog',line)]
        assert not errors,errors[:8]
        for dimension in [MAIN,NETHER]:
            checked=cli(server/'world','--dimension',dimension,'verify',heads[dimension],expected=(0,1))
            if checked['state']!='COMPLETE':
                diagnostic=subprocess.run([harness.JAVA['1.21.11'],'-cp',str(WORK/'wgit.jar'),str(ROOT/'cli/tools/Phase5Metadata.java'),str(server/'world'),dimension,heads[dimension]],text=True,capture_output=True,timeout=120)
                (WORK/(version+'-metadata-difference.log')).write_text(diagnostic.stdout+diagnostic.stderr)
            assert checked['state']=='COMPLETE',checked
        result['paper']={'errors':0,'verifyDimensions':2,'differences':0}
        if version=='1.21.11':result['externalMigration']=migrate_case(moved,version,url,'external')
        else:result['singleplayerMigration']=migrate_case(moved,version,url,'singleplayer')
        result['baselineUnchanged']=p4.p2.manifest(baseline)==before;assert result['baselineUnchanged']
        print('PASS '+version+' ZIP / branches / push / clone / Paper verify / migration',flush=True);return result
    finally:
        (WORK/(version+'-result.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2))
        for path in [source,server,world,moved,clone]:
            if path:shutil.rmtree(path,ignore_errors=True)
        archive.unlink(missing_ok=True);assert p4.p2.manifest(baseline)==before
def main():
    global WORK
    parser=argparse.ArgumentParser();parser.add_argument('--results-dir',type=Path,default=ROOT/'.work/phase5-acceptance');parser.add_argument('--version',choices=['1.21.11','26.2']);args=parser.parse_args()
    WORK=args.results_dir.resolve();WORK.mkdir(parents=True,exist_ok=False);p4.WORK=WORK;p4.p2.WORK=WORK;p4.TOKEN=TOKEN
    hub=None
    with harness.BenchLock():
        try:
            freeze_fixture()
            for src,name in [('cli/build/libs/wgit.jar','wgit.jar'),('cli/build/libs/acceptance-tools.jar','acceptance-tools.jar'),('hub/build/libs/worldgit-hub.jar','hub.jar')]:shutil.copy2(ROOT/src,WORK/name)
            (WORK/'hashes.json').write_text(json.dumps({p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in WORK.glob('*.jar')},indent=2))
            env={**os.environ,'WORLDGIT_HUB_DATA_DIR':str(WORK/'hub-data'),'WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN':TOKEN,'WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD':secrets.token_urlsafe(32)}
            hub=p4.Process([harness.JAVA['26.2'],'-Xmx1g','-XX:ActiveProcessorCount=3','-jar',str(WORK/'hub.jar'),'--server.address=127.0.0.1','--server.port=8091'],WORK,WORK/'hub.log',env);hub.wait('Started HubApplication')
            for attempt in range(120):
                try:
                    if api('GET','/me')['user']['username']=='admin':break
                except urllib.error.HTTPError as error:
                    if error.code!=401:raise
                time.sleep(.25)
            for version in ([args.version] if args.version else ['1.21.11','26.2']):RESULT[version]=case(version)
        finally:
            if hub:hub.stop()
            (WORK/'results.json').write_text(json.dumps(RESULT,ensure_ascii=False,indent=2))
            for jar in WORK.glob('*.jar'):jar.unlink()
            shutil.rmtree(WORK/'hub-data',ignore_errors=True)
if __name__=='__main__':main()
