#!/usr/bin/env python3
"""Phase 4 Hub：雙使用者／真 CLI／網頁 PR／release ZIP／Paper 兩版，自取 bench.lock。"""
import argparse
import fcntl
import importlib.util
import hashlib
import json
import sys
import os
from pathlib import Path
import secrets
import shutil
import time
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'paper/tools'))
from cli_compat import cli_data, repository, DIMENSIONS, verify_all, prepare_all_entities
spec=importlib.util.spec_from_file_location('p4',ROOT/'scripts/verify-phase4.py');p4=importlib.util.module_from_spec(spec);spec.loader.exec_module(p4)
WORK=None
BOOT=secrets.token_urlsafe(32)
USERS={k:{'username':'builder-'+k,'password':secrets.token_urlsafe(24)} for k in ['a','b']}
TOKENS={}

def api(method,path,body=None,actor=None):
    headers={'Authorization':'Bearer '+(BOOT if actor is None else TOKENS[actor])}
    if body is not None:headers['Content-Type']='application/json'
    request=urllib.request.Request('http://127.0.0.1:8097/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
    with urllib.request.urlopen(request,timeout=90) as response:return json.load(response)

def cli(world,*args,actor='a',anonymous=False,auth='basic'):
    if args and args[0]=='init':prepare_all_entities(world)
    env=os.environ.copy();env.pop('WGIT_TOKEN',None);env.pop('WGIT_CREDENTIALS_FILE',None)
    if not anonymous:env.update(WGIT_TOKEN=TOKENS[actor],WGIT_AUTH=auth)
    start=time.monotonic()
    output=p4.run([p4.JAVA['1.21.11'],'-Xmx1500m','-jar',str(WORK/'wgit.jar'),'--world',str(world),'--format=json',*map(str,args)],env=env,allow_merging=True)
    value=cli_data(output)
    with (WORK/'cli.log').open('a') as log:log.write(' '.join(map(str,args))+'\n'+output+'\n')
    return value,time.monotonic()-start

def web(operation,world,label,**fields):
    out=WORK/'screenshots';out.mkdir(exist_ok=True);result=WORK/(label+'-browser.json');config=WORK/(label+'-browser-config.json')
    value={'operation':operation,'hub':'http://127.0.0.1:8097','owner':USERS['a']['username'],'world':world,'out':str(out),'result':str(result),'label':label,**fields}
    config.write_text(json.dumps(value));config.chmod(0o600)
    try:
        browser_env={**os.environ,'PLAYWRIGHT_BROWSERS_PATH':str(ROOT/'.work/ms-playwright')}
        font_config=ROOT/'.work/fonts/fonts.conf'
        if font_config.exists():browser_env['FONTCONFIG_FILE']=str(font_config)
        p4.run(['node',str(ROOT/'hub/web/scripts/phase4-acceptance.mjs'),str(config)],env=browser_env,timeout=300)
        return json.loads(result.read_text())
    finally:config.unlink(missing_ok=True)

class Hub:
    def __init__(self):
        env={**os.environ,'WORLDGIT_HUB_DATA_DIR':str(WORK/'hub-data'),'WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN':BOOT,'WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD':secrets.token_urlsafe(32)}
        self.process=p4.Process([p4.JAVA['26.2'],'-Xmx1500m','-XX:ActiveProcessorCount=3','-jar',str(WORK/'hub.jar'),'--server.address=127.0.0.1','--server.port=8097','--worldgit.hub.assets.source-dir='+str(ROOT/'.work/assets')],WORK,WORK/'hub.log',env)
        try:
            self.process.wait('Started HubApplication');deadline=time.monotonic()+30
            while True:
                try:
                    if api('GET','/me')['user'].get('username')=='admin':break
                except urllib.error.HTTPError as ex:
                    if ex.code!=401:raise
                if time.monotonic()>deadline:raise RuntimeError('bootstrap timeout')
                time.sleep(.25)
            for key,user in USERS.items():
                api('POST','/users',user)
                # 以本機密碼取得一次 compatibility session token，只供建立 PAT；瀏覽器另走 cookie。
                request=urllib.request.Request('http://127.0.0.1:8097/api/v1/auth/login',data=json.dumps(user).encode(),headers={'Content-Type':'application/json'})
                with urllib.request.urlopen(request) as r:session=json.load(r)['token']
                request=urllib.request.Request('http://127.0.0.1:8097/api/v1/tokens',data=json.dumps({'name':'acceptance','scope':'admin' if key=='a' else 'write'}).encode(),headers={'Content-Type':'application/json','Authorization':'Bearer '+session})
                with urllib.request.urlopen(request) as r:TOKENS[key]=json.load(r)['token']
        except BaseException:self.process.stop();raise
    def stop(self):self.process.stop()

def version_case(version):
    before=p4.p2.manifest(ROOT/'.work/worlds'/version/'baseline');source=None;result={};slug='hub-'+version.replace('.','-');a=WORK/('a-'+version);b=WORK/('b-'+version);exported=WORK/('release-'+version);zip_path=WORK/('release-'+version+'.zip')
    owner=USERS['a']['username'];base=f'/worlds/{owner}/{slug}';url=f'http://127.0.0.1:8097/{owner}/{slug}'
    try:
        source=p4.paper_source(version);cli(source,'init','--with-dimensions','all');api('POST','/worlds',{'name':slug,'isPublic':False},'a');api('PUT',base+'/permissions/users/'+USERS['b']['username'],{'role':'write'},'a')
        cli(source,'remote','add','origin',url);result['push']=cli(source,'push','--all',actor='a')[0]
        result['clone_a']=cli('.','clone',url,a,actor='a')[0];result['clone_b']=cli('.','clone',url,b,actor='b')[0]
        cli(a,'branch','topic-a','--all');cli(a,'switch','topic-a','--all');p4.tool('edit',a,0,'gold_block');cli(a,'commit','-m','A disjoint edit');cli(a,'push','origin','topic-a','--all',actor='a')
        cli(b,'branch','topic-b','--all');cli(b,'switch','topic-b','--all');p4.tool('edit',b,8,'diamond_block');cli(b,'commit','-m','B disjoint edit');cli(b,'push','origin','topic-b','--all',actor='b')
        api('PUT',base+'/protected-branches',{'branch':'main','prOnly':True,'reviews':1},'a')
        result['first_pr']=web('pr',slug,version+'-first',author=USERS['a'],reviewer=USERS['b'],source='topic-a',title='A initial building',conflict=False)
        result['clean_pr']=web('pr',slug,version+'-clean',author=USERS['b'],reviewer=USERS['a'],source='topic-b',title='B disjoint building',conflict=False)
        cli(a,'switch','main','--all');result['clean_pull']=cli(a,'pull',actor='a')[0];assert result['clean_pull']['fastForward']
        for dimension in DIMENSIONS[1:]:assert cli(a,'--dimension',dimension,'pull',actor='a')[0]['fastForward']
        result['clean_verify']=verify_all(lambda world,*words:cli(world,*words)[0],a)
        result['clean_paper']=p4.load_world(version,a,'clean-paper-'+version,'paper')
        cli(b,'switch','main','--all');cli(b,'pull','--all',actor='b')
        cli(a,'branch','conflict-a','--all');cli(a,'switch','conflict-a','--all');p4.tool('edit',a,3,'gold_block');cli(a,'commit','-m','A conflicting edit');cli(a,'push','origin','conflict-a','--all',actor='a')
        cli(b,'branch','conflict-b','--all');cli(b,'switch','conflict-b','--all');p4.tool('edit',b,3,'diamond_block');cli(b,'commit','-m','B conflicting edit');cli(b,'push','origin','conflict-b','--all',actor='b')
        result['conflict_first']=web('pr',slug,version+'-conflict-first',author=USERS['a'],reviewer=USERS['b'],source='conflict-a',title='A roof proposal',conflict=False)
        result['conflict_pr']=web('pr',slug,version+'-conflict',author=USERS['b'],reviewer=USERS['a'],source='conflict-b',title='B roof conflict resolved',conflict=True)
        cli(a,'switch','main','--all');result['conflict_pull']=cli(a,'pull',actor='a')[0];assert result['conflict_pull']['fastForward']
        for dimension in DIMENSIONS[1:]:assert cli(a,'--dimension',dimension,'pull',actor='a')[0]['fastForward']
        result['conflict_verify']=verify_all(lambda world,*words:cli(world,*words)[0],a)
        # CLI 拉下的全維度 HEAD／tree 與 PR 合併結果一致。
        detail=result['conflict_pr']['detail'];heads={}
        for dim in DIMENSIONS:
            path=repository(a,dim,version);heads[dim]=p4.run(['git','--git-dir='+str(path),'rev-parse','refs/heads/main']).strip()
        assert set(detail['pr']['commits'])=={'minecraft:overworld'}
        assert heads['minecraft:overworld']==detail['pr']['commits']['minecraft:overworld']
        remote=api('GET',base+'/branches',actor='a')
        remote_heads=next(row['heads'] for row in remote['branches'] if row['name']=='main')
        assert heads=={dim:row['id'] for dim,row in remote_heads.items()},(heads,remote_heads)
        result['heads_match']=True
        result['conflict_paper']=p4.load_world(version,a,'conflict-paper-'+version,'paper')
        cli(a,'tag','v-final','-m','Full merged release','--all');cli(a,'push','--tags','--all',actor='a')
        result['release']=web('release',slug,version+'-release',author=USERS['a'],tag='v-final',title='Merged world '+version,zip=str(zip_path))
        with zipfile.ZipFile(zip_path) as archive:
            assert all('.worldgit' not in Path(name).parts and 'playerdata' not in name and name!='session.lock' for name in archive.namelist());assert 'level.dat' in archive.namelist();archive.extractall(exported)
        # ZIP 本身不帶歷史，為開服前後的逐格驗證另建本機 baseline；解壓世界不經還原或修改。
        initialized=cli(exported,'init','--with-dimensions','all')[0]
        assert set(initialized['dimensions'])==set(DIMENSIONS),initialized
        assert initialized.get('snapshot') and all(d.get('error') is None and d.get('value',{}).get('commit') for d in initialized['dimensions'].values()),initialized
        graphs=cli(exported,'log')[0];assert set(graphs)==set(DIMENSIONS) and len({graph['nodes'][0]['snapshot'] for graph in graphs.values()})==len(graphs)
        result['release_verify']=verify_all(lambda world,*words:cli(world,*words)[0],exported);result['release_paper']=p4.load_world(version,exported,'release-paper-'+version,'paper')
        result['baseline_unchanged']=p4.p2.manifest(ROOT/'.work/worlds'/version/'baseline')==before;assert result['baseline_unchanged']
        print(version+' Hub PR / CLI pull / Paper / release PASS',flush=True);return result
    finally:
        (WORK/(version+'-partial.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2))
        for path in [source,a,b,exported]:
            if path:shutil.rmtree(path,ignore_errors=True)
        zip_path.unlink(missing_ok=True)
        assert p4.p2.manifest(ROOT/'.work/worlds'/version/'baseline')==before

def main():
    global WORK
    parser=argparse.ArgumentParser();parser.add_argument('--results-dir',type=Path,required=True);parser.add_argument('--version',choices=['1.21.11','26.2']);args=parser.parse_args();WORK=args.results_dir.resolve();WORK.mkdir(parents=True,exist_ok=False);p4.WORK=WORK;p4.p2.WORK=WORK;p4.TOKEN=BOOT;p4.cli=cli
    results={};hub=None
    try:
        with (ROOT/'.work/bench.lock').open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX)
            hashes={}
            for src,dest in [('cli/build/libs/wgit.jar','wgit.jar'),('cli/build/libs/acceptance-tools.jar','acceptance-tools.jar'),('hub/build/libs/worldgit-hub.jar','hub.jar')]:
                shutil.copy2(ROOT/src,WORK/dest);hashes[dest]=hashlib.sha256((WORK/dest).read_bytes()).hexdigest()
            (WORK/'artifact-hashes.json').write_text(json.dumps(hashes,indent=2))
            hub=Hub()
            for version in ([args.version] if args.version else ['1.21.11','26.2']):results[version]=version_case(version);(WORK/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
    finally:
        if hub:hub.stop()
        (WORK/'results.json').write_text(json.dumps(results,ensure_ascii=False,indent=2))
        for name in ['wgit.jar','acceptance-tools.jar','hub.jar','freeze-fixture.jar']:(WORK/name).unlink(missing_ok=True)
        shutil.rmtree(WORK/'hub-data',ignore_errors=True)
if __name__=='__main__':main()
