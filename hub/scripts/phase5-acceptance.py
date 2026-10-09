#!/usr/bin/env python3
"""每維度獨立 Hub 實機驗收，自持鎖，finally 停止所有 subprocess 並清理世界複本。"""
import argparse, base64, fcntl, importlib.util, json, os, secrets, shutil, sqlite3, subprocess, sys, time, urllib.request, urllib.error, zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'paper/tools'))
import harness
from cli_compat import cli_data, prepare_all_entities
spec=importlib.util.spec_from_file_location('p4',ROOT/'scripts/verify-phase4.py');p4=importlib.util.module_from_spec(spec);spec.loader.exec_module(p4)
MAIN='minecraft:overworld';NETHER='minecraft:the_nether'
TOKEN=secrets.token_urlsafe(32);PASSWORD=secrets.token_urlsafe(24)
WORK=None;RESULT={};PORT=8098

def api(method,path,body=None,expected=200,token=TOKEN):
    headers={'Content-Type':'application/json'}
    if token:headers['Authorization']='Bearer '+token
    req=urllib.request.Request(f'http://127.0.0.1:{PORT}/api/v1'+path,data=None if body is None else json.dumps(body).encode(),headers=headers,method=method)
    try:r=urllib.request.urlopen(req,timeout=60)
    except urllib.error.HTTPError as e:r=e
    with r:
        raw=r.read();assert r.status==expected,(method,path,r.status,raw)
        if r.headers.get('Content-Type','').startswith('application/zip'):return raw
        value=json.loads(raw)
        if method!='GET' and expected<300:
            result=json.loads(base64.b64decode(r.headers['X-WorldGit-Result']));assert result['status'] in ['SUCCESS','NO_OP'];assert result['operationId']
        if expected>=400:assert value['errorReport']['text'] and value['result']['status']=='FAILED'
        return value

def cli(world,*args):
    if args and args[0]=='init':prepare_all_entities(world)
    r=subprocess.run([harness.JAVA['1.21.11'],'-Xmx768m','-jar',str(WORK/'wgit.jar'),'--world',str(world),'--format=json',*map(str,args)],capture_output=True,text=True,timeout=180,env={**os.environ,'WGIT_TOKEN':TOKEN,'WGIT_USERNAME':'admin'})
    with (WORK/'cli.log').open('a') as log:log.write(' '.join(args)+'\n'+(r.stdout+r.stderr).replace(TOKEN,'[REDACTED]')+'\n')
    assert r.returncode==0,(args,r.stdout,r.stderr);return cli_data(r.stdout)

def start():
    env={**os.environ,'WORLDGIT_HUB_DATA_DIR':str(WORK/'hub-data'),'WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN':TOKEN,'WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD':PASSWORD}
    proc=p4.Process([harness.JAVA['26.2'],'-Xmx1g','-XX:ActiveProcessorCount=3','-jar',str(ROOT/'hub/build/libs/worldgit-hub.jar'),'--server.address=127.0.0.1',f'--server.port={PORT}','--worldgit.hub.assets.source-dir='+str(ROOT/'.work/assets')],WORK,WORK/'hub.log',env)
    try:
        proc.wait('Started HubApplication');deadline=time.monotonic()+30
        while True:
            try:api('GET','/me');break
            except (AssertionError,urllib.error.URLError):
                if time.monotonic()>deadline:raise
                time.sleep(.25)
        return proc
    except BaseException:proc.stop();raise

def wait(base,id):
    deadline=time.monotonic()+90
    while True:
        value=api('GET',base+'/operations/'+id)
        if value['result']:
            assert value['result']['status']=='SUCCESS',value
            assert value['events'] and all(e['operationId']==id for e in value['events']);return value
        assert time.monotonic()<deadline,'operation timeout';time.sleep(.1)

def heads(base):
    return {r['name']:r['heads'] for r in api('GET',base+'/branches')['branches']}

def downgrade_to_phase4(base,pr,release):
    # 實際 Phase 4 schema（固定在 Phase 4 commit；HEAD 已含 Phase 5 schema）副本；保留所有 ACL、PR、release 和固定 commits。
    old=subprocess.check_output(['git','show','10dd24e:hub/src/main/resources/schema.sql'],cwd=ROOT,text=True)
    source=sqlite3.connect(WORK/'hub-data/hub.db');target=sqlite3.connect(WORK/'phase4.db');target.executescript(old)
    source.execute("INSERT OR REPLACE INTO branch_rules(world_id,branch,pr_only,reviews) VALUES (?, 'main',1,1)",(source.execute("SELECT world_id FROM pull_requests WHERE id=?",(pr['id'],)).fetchone()[0],))
    for (name,) in target.execute("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'").fetchall():
        cols=[row[1] for row in target.execute('PRAGMA table_info('+name+')')]
        query='SELECT '+','.join(cols)+' FROM '+name
        try:rows=source.execute(query).fetchall()
        except sqlite3.OperationalError:continue
        if rows:target.executemany('INSERT INTO '+name+'('+','.join(cols)+') VALUES ('+','.join('?' for _ in cols)+')',rows)
    source.commit();target.commit();target.close();source.close()
    (WORK/'hub-data/hub.db').unlink()
    for ext in ['-wal','-shm']:(WORK/('hub-data/hub.db'+ext)).unlink(missing_ok=True)
    shutil.move(WORK/'phase4.db',WORK/'hub-data/hub.db')

def main():
    global WORK
    parser=argparse.ArgumentParser();parser.add_argument('--results-dir',required=True,type=Path);args=parser.parse_args();WORK=args.results_dir.resolve();WORK.mkdir(parents=True,exist_ok=False)
    proc=None
    try:
      with (ROOT/'.work/bench.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        shutil.copy2(ROOT/'cli/build/libs/wgit.jar',WORK/'wgit.jar')
        world=WORK/'world';shutil.copytree(ROOT/'core/src/test/resources/fixtures/26.2',world)
        proc=start();base='/worlds/admin/phase5';url=f'http://127.0.0.1:{PORT}/admin/phase5'
        api('POST','/worlds',{'name':'phase5','isPublic':False});cli(world,'init','--with-dimensions','all');cli(world,'remote','add','origin',url);cli(world,'push','--all')
        cli(world,'--dimension',MAIN,'branch','surface');cli(world,'--dimension',MAIN,'push','origin','surface')
        cli(world,'--dimension',NETHER,'branch','cavern');cli(world,'--dimension',NETHER,'switch','cavern')
        subprocess.run([harness.JAVA['1.21.11'],'-Xmx512m','-cp',str(ROOT/'cli/build/libs/acceptance-tools.jar'),str(ROOT/'cli/tools/Phase5Edit.java'),str(world),NETHER],check=True,timeout=120)
        cli(world,'--dimension',NETHER,'commit','-m','地獄獨立變更');cli(world,'--dimension',NETHER,'push','origin','cavern');cli(world,'--dimension',NETHER,'tag','nether-tag','-m','地獄獨立 tag');cli(world,'--dimension',NETHER,'push','--tags')
        before=heads(base)
        pr=api('POST',base+'/pulls',{'dimension':NETHER,'source':'cavern','target':'main','title':'地獄單維度 PR'})
        detail=api('GET',base+'/pulls/'+pr['id']);assert detail['preview']['dimensions'][0]['dimension']==NETHER
        job=api('POST',base+'/operations',{'operation':'merge','pr':pr['id'],'fingerprint':detail['pr']['fingerprint']},202)
        merge=wait(base,job['id']);after=heads(base)
        assert all(after[b].get(MAIN)==before[b].get(MAIN) for b in before)
        assert set(merge['data']['commits'])=={NETHER};RESULT['independentMerge']=True;RESULT['mergeProgress']=merge
        graph=api('GET',base+'/dims/minecraft.the_nether/graph?limit=10000&all=true');assert graph['merges'][0]['id']==pr['id'];assert any(label['kind']=='tag' for node in graph['graph']['nodes'] for label in node['labels']);RESULT['graph']=graph
        api('GET',base+'/dims/minecraft.the_nether/graph?limit=10001',expected=400);api('GET',base+'/dims/minecraft.the_nether/graph',expected=404,token=None)
        release=api('POST',base+'/releases',{'tag':'phase5-release','title':'每維度 revision','revisions':{MAIN:'surface',NETHER:'main'}})
        zipjob=api('POST',base+'/operations',{'operation':'release-zip','release':release['id']},202);RESULT['zipProgress']=wait(base,zipjob['id'])
        data=api('GET',base+'/operations/'+zipjob['id']+'/download');(WORK/'world.zip').write_bytes(data)
        with zipfile.ZipFile(WORK/'world.zip') as z:assert 'level.dat' in z.namelist() and any(n.startswith('dimensions/minecraft/the_nether/') for n in z.namelist())
        secret='phase5-known-secret-for-report-masking';bad=api('POST',base+'/pulls',{'dimension':NETHER,'source':secret,'target':'main','title':'failure','secret':secret},expected=404)
        assert secret not in json.dumps(bad);assert '[REDACTED]' in bad['errorReport']['text'];RESULT['maskedError']=bad
        # push 後處理的同一 operation id 必須有索引／webhook event。
        jobs=api('GET',base+'/operations?limit=100')
        ids=[job['id'] for job in jobs if job['operation']=='push-processing']
        assert ids,'缺少 push operation';RESULT['pushOperations']=[wait(base,id) for id in ids]
        config={'hub':f'http://127.0.0.1:{PORT}','owner':'admin','world':'phase5','username':'admin','password':PASSWORD,'release':release['id'],'out':str(ROOT/'hub/docs/screenshots/phase5'),'result':str(WORK/'browser.json')}
        configfile=WORK/'browser-config.json';configfile.write_text(json.dumps(config));configfile.chmod(0o600)
        try:subprocess.run(['node',str(ROOT/'hub/web/scripts/phase5-acceptance.mjs'),str(configfile)],check=True,timeout=180,env={**os.environ,'PLAYWRIGHT_BROWSERS_PATH':str(ROOT/'.work/ms-playwright')})
        finally:configfile.unlink(missing_ok=True)
        RESULT['browser']=json.loads((WORK/'browser.json').read_text())
        legacy=api('POST',base+'/pulls',{'source':'surface','target':'main','title':'Phase 4 open PR'})
        proc.stop();proc=None;downgrade_to_phase4(base,legacy,release);proc=start()
        migrated=api('GET',base+'/pulls/'+legacy['id']);assert migrated['pr']['legacy'] and migrated['pr']['selectionsInvalidated']
        api('GET',base+'/pulls/'+legacy['id'],expected=404,token=None)
        assert api('GET',base+'/releases/'+release['id'])['commits']==release['commits']
        assert api('GET',base+'/protected-branches')[0]['dimension']=='*'
        with sqlite3.connect(WORK/'hub-data/hub.db') as db:assert db.execute('SELECT COUNT(*) FROM hub_schema_migrations WHERE version=5').fetchone()[0]==1
        RESULT['phase4Migration']=True;print('Phase 5 Hub：獨立 PR／graph／進度／遮罩／舊 DB／瀏覽器 PASS',flush=True)
    finally:
      if proc:proc.stop()
      (WORK/'results.json').write_text(json.dumps(RESULT,ensure_ascii=False,indent=2))
      for name in ['world','hub-data']:shutil.rmtree(WORK/name,ignore_errors=True)
      for name in ['wgit.jar','world.zip']:(WORK/name).unlink(missing_ok=True)
if __name__=='__main__':main()
