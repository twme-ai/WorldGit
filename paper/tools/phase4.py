#!/usr/bin/env python3
"""真 Hub/SQLite + Paper/Folia 遊戲遠端驗收。自持 bench.lock，所有服務 finally 關閉。"""
import argparse, hashlib, importlib.util, json, os, re, secrets, shutil, signal, socket, subprocess, sys, time, traceback, urllib.request
from pathlib import Path
import harness
from cli_compat import DIMENSIONS, cli_data, heads as world_heads, hub_heads, repository, verify_all
ROOT=Path(harness.ROOT)
JAVA=harness.JAVA
TOKEN=secrets.token_urlsafe(32)
SECRET=secrets.token_urlsafe(32)
PORT=8096

def strip(text):return re.sub(r'\x1b\[[0-9;]*m','',text)

class Hub:
    def __init__(self,work):
        spec=importlib.util.spec_from_file_location('remote_harness',ROOT/'scripts/verify-phase4.py');self.p4=importlib.util.module_from_spec(spec);spec.loader.exec_module(self.p4);self.p4.TOKEN=TOKEN
        self.base='http://127.0.0.1:'+str(PORT)
        env={**os.environ,'WORLDGIT_HUB_DATA_DIR':str(work/'hub-data'),'WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN':TOKEN,'WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD':secrets.token_urlsafe(32)}
        self.p=self.p4.Process([JAVA['26.2'],'-Xmx1200m','-XX:ActiveProcessorCount=3','-jar',str(work/'hub.jar'),'--server.address=127.0.0.1','--server.port='+str(PORT),'--worldgit.hub.collaboration.webhooks.allowed-hosts[0]=127.0.0.1'],work,work/'hub.log',env)
        try:
            self.p.wait('Started HubApplication');end=time.monotonic()+30
            while True:
                try:
                    if self.api('GET','/me')['user']['username']=='admin':break
                except urllib.error.HTTPError as e:
                    if e.code!=401:raise
                if time.monotonic()>end:raise TimeoutError('bootstrap')
                time.sleep(.25)
            self.api('POST','/users',{'username':'reviewer','password':secrets.token_urlsafe(24),'email':'reviewer@example.invalid'})
            # 登入相容 token 僅用建立 reviewer PAT，不寫證據。
            password=secrets.token_urlsafe(24)
            user=self.api('POST','/users',{'username':'writer','password':password,'email':'writer@example.invalid'})
            req=urllib.request.Request(self.base+'/api/v1/auth/login',json.dumps({'username':'writer','password':password}).encode(),{'Content-Type':'application/json'})
            with urllib.request.urlopen(req,timeout=15) as r:session=json.load(r)['token']
            self.reviewer=self.api('POST','/tokens',{'name':'paper-acceptance','scope':'write'},session)['token']
        except BaseException:self.stop();raise
    def api(self,method,path,data=None,token=None):
        req=urllib.request.Request(self.base+'/api/v1'+path,None if data is None else json.dumps(data).encode(),{'Content-Type':'application/json','Authorization':'Bearer '+(token or TOKEN)},method=method)
        with urllib.request.urlopen(req,timeout=90) as r:return json.load(r) if r.status!=204 else None
    def stop(self):self.p.stop()

def run(args):
    work=ROOT/'.work/paper-phase4'/f'{args.platform}-{args.version}-{int(time.time())}';work.mkdir(parents=True)
    result={'platform':args.platform,'version':args.version,'success':False,'steps':[],'evidence':str(work.relative_to(ROOT))}
    server=hub=None
    def save():(work/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    def check(name,ok,**fields):
        result['steps'].append({'name':name,'ok':bool(ok),**fields});save();print(('PASS ' if ok else 'FAIL ')+name,flush=True)
        if not ok:raise AssertionError(name+' '+str(fields))
    def cli(world,*words,expected=True):
        env={**os.environ,'WGIT_TOKEN':TOKEN,'WGIT_AUTH':'bearer'};env.pop('WGIT_CREDENTIALS_FILE',None)
        p=subprocess.run([JAVA['1.21.11'],'-Xmx900m','-jar',str(work/'wgit.jar'),'--world',str(world),'--format=json',*words],capture_output=True,text=True,env=env,timeout=900)
        (work/'cli.log').open('a').write(str(words)+'\n'+p.stdout+p.stderr)
        if expected and p.returncode:raise RuntimeError(p.stdout+p.stderr)
        return cli_data(p.stdout)
    def cmd(command,pattern=r'complete|Complete|Error:|Preview|preview|no entries|No entries|MERGING',expected=True):
        pattern+='|No merge is in progress|Hub (?:401|403|404|409)|Hub request timed out'
        # 新指令由真正玩家送出，連回覆與 confirm 的 sender/owner thread 一起驗。
        if command.startswith('wg ') and command.split()[1] in {'remote','fetch','push','pull','pr','comments','comment'}:
            out=botcmd(remote_bot,command[3:],pattern)
        else:out=strip(server.cmd(command,pattern,180))
        (work/'commands.log').open('a').write(command+'\n'+out+'\n');print(command+' → '+out.splitlines()[-1],flush=True)
        if expected and any(t in out for t in ('Error:','PARTIAL','Hub 401','Hub 403','Hub 404','Hub 409','Hub request timed out','No merge is in progress')):raise RuntimeError(command+'\n'+out)
        return out
    def commit(label):return cmd('wg commit -m '+label,r'Snapshot:|has no changes|overworld [0-9a-f]{8}|Error:')
    def edit(x,block):return cmd(f'setblock {x} 224 0 {block}',r'Changed the block|Could not set')
    def pull(confirm=True):
        out=cmd('wg pull',r'Confirm within|Error:');match=re.search(r'/wg pull confirm ([0-9a-f]{8})',out)
        if not match:raise RuntimeError(out)
        if confirm:return cmd('wg pull confirm '+match[1],r'Merge operation complete|Error:|PARTIAL')
        return match[1],out
    def heads():return world_heads(server.world,args.version,paper=True)
    def move(dimension):
        nonlocal remote_bot
        remote_bot=remote_bots[dimension]

    def clone():
        shutil.rmtree(work/'b',ignore_errors=True);cli('.', 'clone',url,str(work/'b'));return work/'b'
    def remote_edit(world,x,block):
        subprocess.run([JAVA['1.21.11'],'-Xmx512m','-cp',str(work/'tools.jar'),'org.worldgit.core.Phase4AcceptanceTool','edit',str(world),str(x),block],check=True,timeout=60)
    def sample(bot,x):return bot.ask(f'block {x} 224 0','block')['name']
    def botcmd(bot,text,pattern):
        since=len(bot.lines);bot.ask('chat /wg '+text,'chat_sent');deadline=time.monotonic()+120;rx=re.compile(pattern)
        while time.monotonic()<deadline:
            rows=bot.events('chat')[0:] # index below tracks all events
            with bot.lock:events=list(bot.lines[since:])
            for row in events:
                if row.get('ev')=='chat' and rx.search(row['t']):return '\n'.join(e['t'] for e in events if e.get('ev')=='chat')
            time.sleep(.1)
        raise TimeoutError(text+' '+str(events[-8:]))
    with harness.BenchLock():
      try:
        for src,target in [('hub/build/libs/worldgit-hub.jar','hub.jar'),('cli/build/libs/wgit.jar','wgit.jar'),('cli/build/libs/acceptance-tools.jar','tools.jar'),('paper/plugin/build/libs/worldgit-paper-0.1.0-SNAPSHOT.jar','paper.jar')]:shutil.copy2(ROOT/src,work/target)
        result['artifacts']={f:hashlib.sha256((work/f).read_bytes()).hexdigest() for f in ['hub.jar','wgit.jar','tools.jar','paper.jar']};save()
        hub=Hub(work);slug='paper-'+args.platform+'-'+args.version.replace('.','-');base='/worlds/admin/'+slug
        hub.api('POST','/worlds',{'name':slug,'isPublic':False});hub.api('PUT',base+'/permissions/users/writer',{'role':'write'})
        url=hub.base+'/admin/'+slug
        harness.RUN=str(ROOT/'.work/servers/phase4-runs');harness.PORTS.update({'paper-1.21.11':25721,'paper-26.2':25722,'folia-1.21.11':25723,'folia-26.2':25724})
        webhook_port=25731+(server_index:={'paper-1.21.11':0,'paper-26.2':1,'folia-1.21.11':2,'folia-26.2':3})[args.platform+'-'+args.version]
        baseline=ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+args.version)
        if not baseline.is_dir():subprocess.run([JAVA[args.version],'-Xmx512m','-cp',str(work/'wgit.jar'),str(ROOT/'paper/tools/ScaleFixture.java'),args.version,str(ROOT/'.work/worlds'/args.version/'baseline'),str(baseline),'16'],check=True)
        server=harness.Server(args.platform,args.version,baseline=str(baseline),run_label=work.name,view=2,xmx='1500M',config={'language':'en_us','auto-commit':{'enabled':False,'on-quit':False,'on-shutdown':False},'remote':{'timeout-seconds':3,'fetch-interval-seconds':60 if args.polling else 0,'webhook':{'enabled':not args.polling,'port':webhook_port}}})
        shutil.copy2(work/'paper.jar',Path(server.dir)/'plugins/worldgit-paper.jar')
        data=Path(server.dir)/'plugins/WorldGit';data.mkdir(exist_ok=True)
        cred=data/'credentials.yml';cred.write_text('credentials:\n  '+hub.base+':\n    mode: bearer\n    token: '+TOKEN+'\n');cred.chmod(0o600)
        secret=data/'webhook.secret';secret.write_text(SECRET);secret.chmod(0o600)
        server.start();result['server_log']=str(Path(server.evidence_log).relative_to(ROOT));save()
        bots=[server.bot('WgBot'),server.bot('WgBot2')]
        remote_bots=dict(zip(DIMENSIONS,[bots[0],server.bot('WgBot3'),server.bot('WgBot4')]))
        remote_bot=bots[0]
        for p in list(dict.fromkeys([*bots,*remote_bots.values()])):server.cmd('tp '+p.name+' 8 225 8');server.cmd('gamemode creative '+p.name)
        server.cmd('wg debug freeze on',r'WGFREEZE frozen');time.sleep(3)
        for dimension,player in remote_bots.items():
            server.cmd('wg debug comment-teleport '+player.name+' '+dimension,r'WGCOMMENTTP success=true')
        move('minecraft:overworld');time.sleep(10)
        # init 前固定首次載入的 DragonFight／saved-data 與 forceload 設定；仍完整追蹤 metadata。
        if args.platform=='paper':server.cmd('save-all flush',r'Saved the game',180)
        cmd('wg init',r'Initialization|Initialized|init 完成|Error:',True)
        for dimension in DIMENSIONS:
            move(dimension)
            cmd('wg remote add origin '+url,r'updated|Error:');cmd('wg remote list',r'origin →|Error:')
            out=cmd('wg push',r'push complete|Error:')
            check('player push only '+dimension,'push complete (1 dimensions)' in out)
        move('minecraft:overworld')
        initial=heads();check('game remote add/list/push all dimensions',len(initial)==3 and initial==hub_heads(work,'admin',slug),local=initial,remote=hub_heads(work,'admin',slug))
        hook=None if args.polling else hub.api('POST',base+'/webhooks',{'url':'http://127.0.0.1:'+str(webhook_port)+'/worldgit/webhook','secret':SECRET,'events':['pr.merged','push'],'enabled':True})
        b=clone();cli(b,'branch','topic','--all');cli(b,'switch','topic','--all');remote_edit(b,8,'diamond_block');cli(b,'commit','-m','B disjoint');cli(b,'push','origin','topic','--all')
        edit(0,'gold_block');commit('A disjoint')
        for dimension in DIMENSIONS:move(dimension);cmd('wg push',r'push complete|Error:')
        move('minecraft:overworld')
        out=cmd('wg pr create B building --source topic --target main',r'PR #\d+|Error:');pr=hub.api('GET',base+'/pulls')['items'][0]
        detail=hub.api('GET',base+'/pulls/'+pr['id']);hub.api('PUT',base+'/protected-branches',{'branch':'main','prOnly':True,'reviews':1})
        hub.api('POST',base+'/pulls/'+pr['id']+'/reviews',{'fingerprint':detail['pr']['fingerprint'],'decision':'approve'},hub.reviewer)
        mark=server.mark();hub.api('POST',base+'/pulls/'+pr['id']+'/merge',{'fingerprint':detail['pr']['fingerprint']});merged=hub.api('GET',base+'/pulls/'+pr['id'])
        server.wait('has a new version',120,mark);time.sleep(2)
        check(('scheduled fetch' if args.polling else 'signed webhook')+' notice without auto apply',sample(bots[0],8)=='air' and heads()['minecraft:overworld']!=merged['pr']['commits']['minecraft:overworld'],notification=True)
        cmd('wg pr list',r'PR #1|Error:');cmd('wg pr view 1',r'Approvals|approvals|Error:')
        code,preview=pull(False);check('preview unchanged',sample(bots[0],8)=='air' and 'FF' in preview)
        cmd('wg pull confirm '+code,r'Merge operation complete|Error:|PARTIAL');time.sleep(2)
        for dimension in DIMENSIONS[1:]:move(dimension);pull()
        move('minecraft:overworld')
        check('confirmed live FF equals Hub group',sample(bots[0],8)=='diamond_block' and heads()==merged['pr']['commits'],local=heads(),remote=merged['pr']['commits'])
        # 舊預覽的遠端 lease 拒絕；先暫時移除 branch policy，以便真 CLI 推送競爭變動。
        hub.api('DELETE',base+'/protected-branches?branch=main')
        b=clone();remote_edit(b,2,'emerald_block');cli(b,'commit','-m','remote v2');cli(b,'push')
        code,_=pull(False);remote_edit(b,3,'lapis_block');cli(b,'commit','-m','remote v3');cli(b,'push')
        old=heads();out=cmd('wg pull confirm '+code,r'Pull preview changed|Error:',False)
        check('stale remote preview rejected before apply',heads()==old and 'preview' in out.lower() and sample(bots[0],2)=='air')
        pull();time.sleep(1)
        # 三方衝突，沿用 Phase 3 select/resolve/continue，推送後全组 heads 相同。
        b=clone();edit(4,'gold_block');commit('A conflict');remote_edit(b,4,'diamond_block');cli(b,'commit','-m','B conflict');cli(b,'push')
        out=cmd('wg push',r'non fast-forward|Error:',False);check('non FF push rejects and suggests pull','/wg pull' in out)
        code,out=pull(False);check('three way conflict preview','3-way' in out and '1 conflict' in out and '1 affected chunks' in out)
        out=cmd('wg pull confirm '+code,r'Merge operation complete|Error:|PARTIAL');check('pull enters MERGING','MERGING' in out)
        cmd('wg conflict-select all theirs',r'Merge operation complete|Error:');time.sleep(.5);check('conflict-select changes exact atom',sample(bots[0],4)=='diamond_block')
        cmd('wg resolve all theirs',r'Merge operation complete|Error:');cmd('wg merge --continue',r'Merge operation complete|Error:')
        for dimension in DIMENSIONS:move(dimension);cmd('wg push',r'push complete|Error:')
        move('minecraft:overworld')
        cli(b,'fetch','--all')
        cli(b,'pull','--all');check('resolved push equals clone',heads()==world_heads(b,args.version) and bool(verify_all(cli,b)))
        # HTML/MiniMessage 注入純文字留言 + 範圍釘選；viewer 不能看見任何 display。
        server.cmd('deop WgBot2',r'no longer a server operator');denied=botcmd(bots[1],'comments show 1',r'permission');check('non op comment permission denied','permission' in denied)
        body='<red><click:run_command:/op bad><script>alert(1)</script> literal §c color'
        pin={'dimension':'minecraft:overworld','x':8,'y':224,'z':0,'maxX':10,'maxY':226,'maxZ':2}
        hub.api('POST',base+'/pulls/'+pr['id']+'/comments',{'body':body,'pin':pin})
        cmd('wg comments 1 --dimension minecraft:overworld',r'literal|Error:')
        botcmd(bots[0],'comments show 1',r'Requested|Error:');time.sleep(3)
        visible=bots[0].ask('entities','entities');hidden=bots[1].ask('entities','entities')
        native=server.cmd('wg debug comment-displays 0 0',r'WGCOMMENTS');display_ids=[int(i.strip()) for i in re.search(r'ids=\[([^\]]*)\]',native)[1].split(',') if i.strip()]
        # 26.2 的驗收 bot 沿用舊 entity 名稱表；用伺服器確認為 TextDisplay 的 wire entity id 比對可見性。
        check('TextDisplay requester only',bool(display_ids) and any(i in visible['ids'] for i in display_ids) and not any(i in hidden['ids'] for i in display_ids),requester=visible,viewer=hidden,display_ids=display_ids)
        if args.screenshots:
            from phase4_screenshots import capture
            result['screenshots']=capture(server,work,args.version);check('real client TextDisplay literal/screenshot',True,**result['screenshots'])
        # capture + verify 必須不包含非持久 display；會在 final 停服後再次全量 verify。
        commit('display exclusion');botcmd(bots[0],'comments hide',r'cleared');time.sleep(2);check('hide removes entities',bots[0].ask('entities','entities')['displays']==0 and 'entities=0' in server.cmd('wg debug comment-displays 0 0',r'WGCOMMENTS'))
        position=bots[0].ask('pos','pos')['pos']
        botcmd(bots[0],'comment 1 player pin --here',r'Comment created');comments=hub.api('GET',base+'/comments?pinned=true')['items'];check('player comment records dimension and coordinates',any(c['body']=='player pin' and c['pin']['dimension']=='minecraft:overworld' and all(c['pin'][axis]==int(position[axis]//1) for axis in ('x','y','z')) for c in comments))
        botcmd(bots[0],'comments show 1',r'Requested');time.sleep(1)
        server.cmd('wg debug comment-teleport WgBot minecraft:the_nether',r'WGCOMMENTTP success=true');time.sleep(2)
        check('dimension change removes original world displays','entities=0' in server.cmd('wg debug comment-displays 0 0',r'WGCOMMENTS'))
        server.cmd('wg debug comment-teleport WgBot minecraft:overworld',r'WGCOMMENTTP success=true');time.sleep(2)
        botcmd(bots[0],'comments show 1',r'Requested');time.sleep(1);bots[0].stop();time.sleep(2)
        # 再次登入觀察 global nonpersistent entity count（viewer 仍看不到，後續 verify 過濾）。
        check('logout removes entities and preserves viewer privacy',bots[1].ask('entities','entities')['displays']==0 and 'entities=0' in server.cmd('wg debug comment-displays 0 0',r'WGCOMMENTS'))
        # 錯誤 PAT／不可達仍由背景 queue 處理；probe 測 tick。
        # 前一步真的登出了原玩家；重新登入後，繼續以真玩家驗網路錯誤。
        bots[0]=server.bot('WgBot');remote_bots['minecraft:overworld']=bots[0];move('minecraft:overworld');server.cmd('gamemode creative WgBot')
        server.cmd('wg debug probe start',r'probe 開始')
        cred.write_text('credentials:\n  '+hub.base+':\n    mode: bearer\n    token: invalid-pat\n');cred.chmod(0o600)
        out=cmd('wg fetch',r'Hub 401|Hub 404|Error:',False);check('bad PAT safe clear failure','401' in out and TOKEN not in out)
        cred.write_text('credentials:\n  '+hub.base+':\n    mode: bearer\n    token: '+TOKEN+'\n');cred.chmod(0o600)
        cmd('wg remote add down http://127.0.0.1:8099/admin/missing',r'updated|Error:')
        out=cmd('wg fetch down',r'Cannot reach|Error:',False);time.sleep(2)
        probe=strip(server.cmd('wg debug probe stop',r'tpsEstimate='));check('unreachable Hub does not block ticks','tpsEstimate=' in probe,probe=probe.strip())
        result['success']=True
      except BaseException as e:
        result['error']=repr(e).replace(TOKEN,'[REDACTED]').replace(SECRET,'[REDACTED]');result['trace']=traceback.format_exc().replace(TOKEN,'[REDACTED]').replace(SECRET,'[REDACTED]');print(result['trace'],flush=True)
      finally:
        if server:
          try:
            server.stop();result['server_exit']=server.proc.returncode if server.proc else None
            if result['success']:
              try:
                verify=verify_all(cli,server.world);check('final offline verify zero differences',len(verify)==3,verify=verify)
              except BaseException:
                result['verification_error']=traceback.format_exc();result['success']=False
            if not result['success']:
              difference=cli(server.world,'diff','--blocks')
              (work/'final-difference.json').write_text(json.dumps(difference,ensure_ascii=False,indent=2)+'\n')
              result['final_difference_path']=str((work/'final-difference.json').relative_to(ROOT))
              for dimension,changes in difference.items():
                for change in changes.get('metadata',[]):
                  for side in ['beforeId','afterId']:
                    oid=change.get(side)
                    if oid:
                      blob=subprocess.check_output(['git','--git-dir',str(repository(server.world,dimension,args.version,paper=True)),'cat-file','blob',oid])
                      (work/(oid+'.blob')).write_bytes(blob)
            result['problems']=[l for l in server.lines_since() if re.search(r'\bERROR\b|Exception|thread check',l)]
          except BaseException:result['cleanup_error']=traceback.format_exc();result['success']=False
          shutil.rmtree(server.dir,ignore_errors=True)
        if hub:hub.stop()
        for file in ['hub.jar','wgit.jar','tools.jar','paper.jar']:(work/file).unlink(missing_ok=True)
        shutil.rmtree(work/'hub-data',ignore_errors=True);shutil.rmtree(work/'b',ignore_errors=True)
        result['ports_closed']={str(p):socket.socket().connect_ex(('127.0.0.1',p))!=0 for p in [PORT,25721,25722,25723,25724,25731,25732,25733,25734]}
        result['success']=result['success'] and result.get('server_exit')==0 and not result.get('problems') and all(result['ports_closed'].values());save()
    print(work/'results.json',flush=True);return result['success']

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('platform',choices=['paper','folia']);parser.add_argument('version',choices=['1.21.11','26.2']);parser.add_argument('--screenshots',action='store_true');parser.add_argument('--polling',action='store_true')
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(RuntimeError('terminated')))
    sys.exit(0 if run(parser.parse_args()) else 1)
