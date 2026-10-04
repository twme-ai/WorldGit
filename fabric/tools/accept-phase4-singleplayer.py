#!/usr/bin/env python3
"""Xvfb 真單人 client／Hub REST merge／CLI clone 直接放 saves 開世界。自取 bench.lock。"""
import argparse,hashlib,json,os,re,shutil,signal,socket,subprocess,sys,time,traceback
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'paper/tools'))
import harness
import phase4 as hub_fixture
from phase4_harness import Client,BenchLock,retain_difference
hub_fixture.PORT=8095
TOKEN=hub_fixture.TOKEN;JAVA=harness.JAVA

def run(args):
    work=ROOT/'.work/fabric-phase4'/f'single-{args.version}-{int(time.time())}';work.mkdir(parents=True)
    result={'version':args.version,'mode':'singleplayer','success':False,'steps':[],'evidence':str(work.relative_to(ROOT))};client=hub=None;world=None;clone_world=None
    def save():(work/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    def check(label,ok,**data):
        result['steps'].append({'name':label,'ok':bool(ok),**data});save();print(('PASS ' if ok else 'FAIL ')+label,flush=True)
        if not ok:raise AssertionError(label+' '+str(data))
    def cli(at,*words):
        p=subprocess.run([JAVA['1.21.11'],'-Xmx900m','-jar',str(work/'wgit.jar'),'--world',str(at),'--format=json',*words],capture_output=True,text=True,env={**os.environ,'WGIT_TOKEN':TOKEN,'WGIT_AUTH':'bearer'},timeout=900)
        (work/'cli.log').open('a').write(str(words)+'\n'+p.stdout+p.stderr)
        if p.returncode:raise RuntimeError(p.stdout+p.stderr)
        return json.loads(p.stdout)
    def command(text):print('owner '+text,flush=True);return client.action('command',command=text)
    def heads(group):return {d.name.replace('.',':',1):subprocess.check_output(['git','--git-dir',str(d),'rev-parse','HEAD'],text=True).strip() for d in Path(group).glob('minecraft.*') if d.is_dir()}
    with BenchLock():
      try:
        for source,name in [('hub/build/libs/worldgit-hub.jar','hub.jar'),('cli/build/libs/wgit.jar','wgit.jar'),('cli/build/libs/acceptance-tools.jar','tools.jar')]:shutil.copy2(ROOT/source,work/name)
        result['artifacts']={name:hashlib.sha256((work/name).read_bytes()).hexdigest() for name in ['hub.jar','wgit.jar','tools.jar']}
        hub=hub_fixture.Hub(work);slug='single-'+args.version.replace('.','-');base='/worlds/admin/'+slug;hub.api('POST','/worlds',{'name':slug,'isPublic':False});hub.api('PUT',base+'/permissions/users/writer',{'role':'write'});url=hub.base+'/admin/'+slug
        run=ROOT/'.work/worlds/fabric-gametest'/(args.version+'-phase4');config=run/'config';config.mkdir(parents=True,exist_ok=True)
        (config/'worldgit-server.yml').write_text('locale: en_us\npermission-level: 4\nread-permission-level: 0\nauto-commit:\n  on-logout: false\n  on-stop: false\n  interval-minutes: 0\nremote:\n  timeout-seconds: 3\n  fetch-interval-seconds: 60\n  webhook:\n    enabled: true\n    port: 25763\n')
        cred=config/'credentials.yml';cred.write_text('credentials:\n  '+hub.base+':\n    mode: bearer\n    token: '+TOKEN+'\n');cred.chmod(0o600)
        client=Client(work,args.version,single=True);world=Path(client.ready['world']);group=Path(client.ready['repository'])
        result['client_artifact']=hashlib.sha256(Path(client.ready['productionJar']).read_bytes()).hexdigest()
        check('new singleplayer remote repository inside save',group==world/'.worldgit');check('singleplayer never opens webhook even when enabled',port_closed(25763))
        for dimension in (['DIM-1','DIM1'] if args.version=='1.21.11' else ['dimensions/minecraft/the_nether','dimensions/minecraft/the_end']):
            (world/dimension/'region').mkdir(parents=True,exist_ok=True)
        command('wg init');command('wg remote add origin '+url);command('wg push');check('owner remote push all dimensions',len(heads(group))==3)
        b=work/'b';cli('.', 'clone',url,str(b));cli(b,'branch','topic');cli(b,'switch','topic')
        subprocess.run([JAVA['1.21.11'],'-Xmx512m','-cp',str(work/'tools.jar'),'org.worldgit.core.Phase4AcceptanceTool','edit',str(b),'8','diamond_block'],check=True,timeout=90);cli(b,'commit','-m','remote branch');cli(b,'push','origin','topic')
        command('setblock 0 224 0 gold_block');command('wg commit -m local singleplayer');command('wg push');command('wg pr create singleplayer building --source topic --target main')
        pr=hub.api('GET',base+'/pulls')['items'][0];detail=hub.api('GET',base+'/pulls/'+pr['id']);hub.api('PUT',base+'/protected-branches',{'branch':'main','prOnly':True,'reviews':1});hub.api('POST',base+'/pulls/'+pr['id']+'/reviews',{'fingerprint':detail['pr']['fingerprint'],'decision':'approve'},hub.reviewer)
        mark=len(client.process.lines);hub.api('POST',base+'/pulls/'+pr['id']+'/merge',{'fingerprint':detail['pr']['fingerprint']});merged=hub.api('GET',base+'/pulls/'+pr['id'])
        client.process.wait('has a new version',120,mark);check('optional polling notification never auto applies',client.action('state')['block8']=='minecraft:air' and heads(group)!=merged['pr']['commits'])
        code=command('wg pull')['code'];check('preview code without world apply',bool(code) and client.action('state')['block8']=='minecraft:air');command('wg pull confirm '+code)
        state=client.action('state');current=heads(group)
        check('singleplayer confirmed pull equals web merge',state['block8']=='minecraft:diamond_block' and current==merged['pr']['commits'],client=state,heads=current,expected=merged['pr']['commits'])
        pin={'dimension':'minecraft:overworld','x':8,'y':224,'z':0,'maxX':10,'maxY':226,'maxZ':2};hub.api('POST',base+'/pulls/'+pr['id']+'/comments',{'body':'<red><click:run_command:/op bad><script>alert(1)</script> literal §c color','pin':pin})
        client.action('view');rows=client.action('comments');check('singleplayer literal HUD/range no entities',rows['comments'][0]['x']==8 and client.action('state')['displays']==0);client.action('hide');check('singleplayer hide',client.action('state')['comments']==0)
        command('wg comment 1 single owner --here');check('owner coordinate comment stored',any(c['body']=='single owner' and c['pin']['dimension']=='minecraft:overworld' for c in hub.api('GET',base+'/comments?pinned=true')['items']))
        client.action('disconnect');check('singleplayer offline verify zero differences',cli(world,'verify')['state']=='COMPLETE')
        clone_world=run/'saves'/'WorldGit-Phase4-Clone';shutil.rmtree(clone_world,ignore_errors=True);cli('.', 'clone',url,str(clone_world));check('clone offline content equals Hub',cli(clone_world,'verify')['state']=='COMPLETE')
        opened=client.action('open-clone',world=clone_world.name);check('CLI clone directly opened from saves',Path(opened['repository'])==clone_world/'.worldgit')
        # clone 不帶玩家資料；由 gametest 定位相機並等待 Screen／chunk／合併方塊，2400 ticks 逾時。
        screenshot=client.action('clone-screenshot',timeout=300)
        state=client.action('state');check('clone gameplay blocks match web merge',state['block8']=='minecraft:diamond_block' and state['block0']=='minecraft:gold_block')
        check('clone screenshot shows rendered world without loading screen',screenshot['inWorld'] and screenshot['renderReady'] and screenshot['surroundingChunksLoaded'] and screenshot['screen']=='none' and screenshot['overlay']=='none' and screenshot['guiHidden'],client=screenshot)
        client.action('disconnect');check('clone after real client offline verify zero differences',cli(clone_world,'verify')['state']=='COMPLETE')
        client.finish();result['screenshots']=client.screenshots();client=None;result['success']=True
      except BaseException as e:
        result['error']=repr(e).replace(TOKEN,'[REDACTED]');result['trace']=traceback.format_exc().replace(TOKEN,'[REDACTED]');print(result['trace'],flush=True)
      finally:
        if client:client.stop()
        if not result['success'] and world and world.exists():
            try:result['final_difference']=retain_difference(world,work,cli)
            except BaseException:result['diagnostic_error']=traceback.format_exc().replace(TOKEN,'[REDACTED]')
        if hub:hub.stop()
        run=ROOT/'.work/worlds/fabric-gametest'/(args.version+'-phase4');(run/'config/credentials.yml').unlink(missing_ok=True);(run/'config/worldgit-server.yml').unlink(missing_ok=True)
        for p in [world,clone_world,work/'b',work/'hub-data']:
            if p:shutil.rmtree(p,ignore_errors=True)
        for n in ['hub.jar','wgit.jar','tools.jar']:(work/n).unlink(missing_ok=True)
        result['ports_closed']={str(p):port_closed(p) for p in [8095,25763,25764]};result['success']=result['success'] and all(result['ports_closed'].values());save()
    print(work/'results.json',flush=True);return result['success']
def port_closed(p):
    with socket.socket() as s:return s.connect_ex(('127.0.0.1',p))!=0
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('version',choices=['1.21.11','26.2']);signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(RuntimeError('terminated')));sys.exit(0 if run(parser.parse_args()) else 1)
