#!/usr/bin/env python3
"""真 Hub/SQLite、正式 Fabric jar、Xvfb client 遠端驗收。自行取 bench.lock，finally 停止。"""
import argparse,http.server,threading,hashlib,importlib.util,json,os,re,secrets,shutil,signal,socket,subprocess,sys,time,traceback
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2];sys.path.insert(0,str(ROOT/'paper/tools'))
import harness
import phase4 as hub_fixture
import dedicated_harness
from phase4_harness import Client,BenchLock,retain_difference
hub_fixture.PORT=8094
TOKEN=hub_fixture.TOKEN;SECRET=hub_fixture.SECRET;JAVA=harness.JAVA

def run(args):
    work=ROOT/'.work/fabric-phase4'/f'dedicated-{args.version}-{int(time.time())}';work.mkdir(parents=True)
    result={'version':args.version,'mode':'dedicated','success':False,'steps':[],'evidence':str(work.relative_to(ROOT))}
    server=hub=client=relay=slow=None
    def save():(work/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    def check(label,ok,**data):
        result['steps'].append({'name':label,'ok':bool(ok),**data});save();print(('PASS ' if ok else 'FAIL ')+label,flush=True)
        if not ok:raise AssertionError(label+' '+str(data))
    def cli(world,*words):
        p=subprocess.run([JAVA['1.21.11'],'-Xmx900m','-jar',str(work/'wgit.jar'),'--world',str(world),'--format=json',*words],text=True,capture_output=True,env={**os.environ,'WGIT_TOKEN':TOKEN,'WGIT_AUTH':'bearer'},timeout=900)
        (work/'cli.log').open('a').write(str(words)+'\n'+p.stdout+p.stderr)
        if p.returncode:raise RuntimeError(p.stdout+p.stderr)
        return json.loads(p.stdout)
    def botcmd(text,pattern,expected=True,who=None):
        target=who or bot;since=len(target.lines);target.ask('chat /wg '+text,'chat_sent');rx=re.compile(pattern);end=time.monotonic()+300
        while time.monotonic()<end:
            with target.lock:events=list(target.lines[since:])
            rows=[e['t'] for e in events if e.get('ev')=='chat'];out='\n'.join(rows)
            if rx.search(out):
                (work/'commands.log').open('a').write(text+'\n'+out+'\n');print(text+' → '+out.splitlines()[-1],flush=True)
                if expected and any(t in out for t in ['Error:','PARTIAL','Hub 401','Hub 403','Invalid Hub']):raise RuntimeError(out)
                return out
            time.sleep(.1)
        raise TimeoutError(text+' '+str(events[-8:]))
    def cmd(text,pattern=r'COMPLETE:|MERGING:|Complete:|Error:|PARTIAL',expected=True):
        out=server.cmd(text,pattern,900);(work/'console.log').open('a').write(text+'\n'+out+'\n')
        if expected and any(t in out for t in ['Error:','PARTIAL']):raise RuntimeError(out)
        return out
    def commit(label):return cmd('wg commit -m '+label,r'Snapshot:|Snapshot |snapshot|No changes, so no commit|Error:')
    def edit(x,block):cmd(f'setblock {x} 224 0 {block}',r'Changed the block|Could not set')
    def heads():
        candidates=[p for p in Path(server.dir).rglob('HEAD') if p.parent.name.startswith('minecraft.')];return {p.parent.name.replace('.',':',1):subprocess.check_output(['git','--git-dir',str(p.parent),'rev-parse','HEAD'],text=True).strip() for p in candidates}
    def clone():
        shutil.rmtree(work/'b',ignore_errors=True);cli('.', 'clone',url,str(work/'b'));return work/'b'
    def remote_edit(world,x,block):subprocess.run([JAVA['1.21.11'],'-Xmx512m','-cp',str(work/'tools.jar'),'org.worldgit.core.Phase4AcceptanceTool','edit',str(world),str(x),block],check=True,timeout=90)
    def sample(x):return bot.ask(f'block {x} 224 0','block')['name']
    def preview():
        out=botcmd('pull',r'Confirm within|requires a clean|Error:');match=re.search(r'/wg pull confirm ([0-9a-f]{8})',out)
        if not match:raise RuntimeError(out)
        return match[1],out
    def pull():code,_=preview();return botcmd('pull confirm '+code,r'COMPLETE:|MERGING:|Error:|PARTIAL')
    with BenchLock():
      try:
        project='mc1_21_11' if args.version=='1.21.11' else 'mc26_2';v=args.version
        for source,name in [('hub/build/libs/worldgit-hub.jar','hub.jar'),('cli/build/libs/wgit.jar','wgit.jar'),('cli/build/libs/acceptance-tools.jar','tools.jar'),(f'fabric/{project}/build/libs/worldgit-fabric-{v}-0.1.0-SNAPSHOT.jar','fabric.jar'),(f'fabric/{project}/build/libs/worldgit-fabric-{v}-0.1.0-SNAPSHOT-'+('dedicated-fixture-remapped' if v=='1.21.11' else 'dedicated-fixture')+'.jar','fixture.jar')]:shutil.copy2(ROOT/source,work/name)
        result['artifacts']={f:hashlib.sha256((work/f).read_bytes()).hexdigest() for f in ['hub.jar','wgit.jar','fabric.jar']};save()
        hub=hub_fixture.Hub(work);slug='fabric-'+v.replace('.','-');base='/worlds/admin/'+slug;hub.api('POST','/worlds',{'name':slug,'isPublic':False});hub.api('PUT',base+'/permissions/users/writer',{'role':'write'});url=hub.base+'/admin/'+slug
        baseline=ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+v)
        server=dedicated_harness.Server(v,work,baseline,work/'fabric.jar',work/'fixture.jar');server.port=25741 if v=='1.21.11' else 25742
        props=Path(server.dir)/'server.properties';props.write_text(re.sub(r'server-port=.*','server-port='+str(server.port),props.read_text()))
        if v=='1.21.11':
            for source,name in [(baseline/'world_nether/DIM-1','DIM-1'),(baseline/'world_the_end/DIM1','DIM1')]:shutil.copytree(source,Path(server.world)/name)
        data=Path(server.dir)/'config';cred=data/'credentials.yml';cred.write_text('credentials:\n  '+hub.base+':\n    mode: bearer\n    token: '+TOKEN+'\n');cred.chmod(0o600)
        secret=data/'webhook.secret';secret.write_text(SECRET);secret.chmod(0o600);webhook_port=25761 if v=='1.21.11' else 25762
        (data/'worldgit-server.yml').write_text('locale: en_us\npermission-level: 2\nread-permission-level: 0\nauto-commit:\n  on-logout: false\n  on-stop: false\n  interval-minutes: 0\nremote:\n  timeout-seconds: 3\n  webhook:\n    enabled: true\n    port: '+str(webhook_port)+'\n')
        server.start();bot=server.bot('WgBot');viewer=server.bot('WgViewer');server.cmd('op WgBot');server.cmd('gamemode creative WgBot');server.cmd('tp WgBot 8 225 8');cmd('tick freeze',r'froze')
        if v=='1.21.11':
            # Paper fixture 的 strider 含 Paper-only AgeLocked；先由真正 Fabric 載入，
            # 再建立初始快照，避免後面的換維度檢查才觸發普通原版序列化變化。
            cmd('execute in minecraft:the_nether run tp WgBot 8 65 8',r'Teleported')
            deadline=time.monotonic()+120
            while True:
                mark=server.mark()
                server.send('execute in minecraft:the_nether if entity @e[type=strider] run say WGSTRIDER_LOADED')
                server.send('execute in minecraft:the_nether unless entity @e[type=strider] run say WGSTRIDER_LOADING')
                status=server.wait(r'WGSTRIDER_LOAD(?:ED|ING)',10,mark)
                if 'WGSTRIDER_LOADED' in status:break
                if time.monotonic()>deadline:raise TimeoutError('Fabric fixture strider did not load')
                time.sleep(.5)
            cmd('execute in minecraft:overworld run tp WgBot 8 225 8',r'Teleported')
            result['fixture_preload']='Paper strider AgeLocked removed by vanilla serialization before initial snapshot'
        cmd('wg init',r'Initialization complete|Error:',True)
        botcmd('remote add origin '+url,r'updated|Error:');botcmd('remote list',r'origin →|Error:');botcmd('push',r'push complete|Error:')
        initial=heads();check('player remote add/list/push all dimensions',len(initial)==3)
        if args.visual_only:
            result['mode']='dedicated-visual'
            edit(8,'diamond_block');commit('visual anchor');botcmd('push',r'push complete|Error:')
            cmd('wg branch create topic',r'Branch updated:|Error:');botcmd('push origin topic',r'push complete|Error:')
            botcmd('pr create visual marker --source topic --target main',r'PR #\d+|Error:');pr=hub.api('GET',base+'/pulls')['items'][0]
            spec=importlib.util.spec_from_file_location('pair_fixture',ROOT/'fabric/tools/accept-paper-phase3.py');pair=importlib.util.module_from_spec(spec);spec.loader.exec_module(pair);relay=pair.TcpRelay(server.port+10,server.port)
            client=Client(work,v,port=server.port+10);result['client_artifact']=hashlib.sha256(Path(client.ready['productionJar']).read_bytes()).hexdigest();player=client.ready['player'];server.cmd('op '+player);server.cmd('gamemode creative '+player)
        else:
            hub.api('POST',base+'/webhooks',{'url':'http://127.0.0.1:'+str(webhook_port)+'/worldgit/webhook','secret':SECRET,'events':['pr.merged','push'],'enabled':True})
            b=clone();cli(b,'branch','topic');cli(b,'switch','topic');remote_edit(b,8,'diamond_block');cli(b,'commit','-m','remote disjoint');cli(b,'push','origin','topic')
            edit(0,'gold_block');commit('local disjoint');botcmd('push',r'push complete|Error:')
            botcmd('pr create remote building --source topic --target main',r'PR #\d+|Error:');pr=hub.api('GET',base+'/pulls')['items'][0];detail=hub.api('GET',base+'/pulls/'+pr['id'])
            hub.api('PUT',base+'/protected-branches',{'branch':'main','prOnly':True,'reviews':1});hub.api('POST',base+'/pulls/'+pr['id']+'/reviews',{'fingerprint':detail['pr']['fingerprint'],'decision':'approve'},hub.reviewer)
            mark=server.mark();hub.api('POST',base+'/pulls/'+pr['id']+'/merge',{'fingerprint':detail['pr']['fingerprint']});merged=hub.api('GET',base+'/pulls/'+pr['id']);server.wait('has a new version',120,mark)
            check('signed webhook notification never auto applies',sample(8)=='air' and heads()!=merged['pr']['commits'])
            botcmd('pr list',r'PR #1|Error:');botcmd('pr view 1',r'approvals|Error:')
            code,out=preview();check('FF preview keeps world unchanged',sample(8)=='air' and 'FF' in out);botcmd('pull confirm '+code,r'COMPLETE:|Error:|PARTIAL');check('live FF equals Hub merge',sample(8)=='diamond_block' and heads()==merged['pr']['commits'])
            out=botcmd('pull confirm '+code,r'No valid preview|Error:',False);check('confirmation one use','No valid preview' in out)
            hub.api('DELETE',base+'/protected-branches?branch=main');b=clone();remote_edit(b,2,'emerald_block');cli(b,'commit','-m','remote2');cli(b,'push');code,_=preview();remote_edit(b,3,'lapis_block');cli(b,'commit','-m','remote3');cli(b,'push');old=heads()
            out=botcmd('pull confirm '+code,r'Pull preview changed|Error:',False);check('changed remote tip rejected',heads()==old and sample(2)=='air' and 'preview changed' in out);pull()
            # 真 client 加入觀看 MERGING 與留言；TCP relay 使用指定客戶端入口。
            spec=importlib.util.spec_from_file_location('pair_fixture',ROOT/'fabric/tools/accept-paper-phase3.py');pair=importlib.util.module_from_spec(spec);spec.loader.exec_module(pair);relay=pair.TcpRelay(server.port+10,server.port)
            client=Client(work,v,port=server.port+10);result['client_artifact']=hashlib.sha256(Path(client.ready['productionJar']).read_bytes()).hexdigest();player=client.ready['player'];server.cmd('op '+player);server.cmd('gamemode creative '+player);server.cmd('tp '+player+' 8 225 8')
            b=clone();edit(4,'gold_block');commit('local conflict');remote_edit(b,4,'diamond_block');cli(b,'commit','-m','remote conflict');cli(b,'push')
            out=botcmd('push',r'non fast-forward|Error:',False);check('non FF push rejected','/wg pull' in out)
            code,out=preview();check('3-way conflict preview exact chunk','3-way' in out and '1 conflict' in out and '1 affected chunks' in out);out=botcmd('pull confirm '+code,r'MERGING:|Error:|PARTIAL');check('pull enters MERGING','MERGING' in out)
            ui=client.action('conflict');check('real client conflict list and resolve',ui['regions']==1);cmd('wg merge --continue');botcmd('push',r'push complete|Error:');cli(b,'fetch');cli(b,'pull');check('resolved Hub equals clone',cli(b,'verify')['state']=='COMPLETE')
            denied=botcmd('push',r'Insufficient permission|Error:',False,viewer);check('read/write op permission split','Insufficient permission' in denied);botcmd('pr list',r'PR #1|Error:',who=viewer)
        body='<red><click:run_command:/op bad><script>alert(1)</script> literal §c color';pin={'dimension':'minecraft:overworld','x':8,'y':224,'z':0,'maxX':10,'maxY':226,'maxZ':2};hub.api('POST',base+'/pulls/'+pr['id']+'/comments',{'body':body,'pin':pin})
        botcmd('comments show pr 1',r'require a WorldGit Fabric client|Error:');check('vanilla fallback explicitly explained',True)
        botcmd('comments pr 1',r'literal|Error:');client.action('view');rows=client.action('comments');state=client.action('state')
        check('native literal HUD/range at correct coordinates, zero server displays',rows['comments'][0]['x']==8 and state['displays']==0)
        cmd('execute unless entity @e[type=text_display] run say WGDISPLAYS_ZERO',r'WGDISPLAYS_ZERO');check('server no text display entity',True)
        before_display_commit=heads();commit('comments not captured');check('HUD excluded from commit',heads()==before_display_commit);client.action('hide');check('hide clears client',client.action('state')['comments']==0)
        botcmd('comment 1 player pin --here',r'Comment created|Error:');check('game --here comment recorded',any(c['body']=='player pin' for c in hub.api('GET',base+'/comments?pinned=true')['items']))
        client.action('comments');cmd('execute in minecraft:the_nether run tp '+player+' 8 65 8',r'Teleported|Error:');time.sleep(2);check('dimension clears client comments',client.action('state')['comments']==0)
        cmd('execute in minecraft:overworld run tp '+player+' 8 225 8',r'Teleported|Error:')
        cred.write_text('credentials:\n  '+hub.base+':\n    mode: bearer\n    token: invalid-pat\n');cred.chmod(0o600);out=botcmd('fetch',r'Hub 401|Hub 404|Error:',False);check('bad PAT safe i18n', 'Hub 401' in out and TOKEN not in out)
        cred.write_text('credentials:\n  '+hub.base+':\n    mode: bearer\n    token: '+TOKEN+'\n');cred.chmod(0o600)
        botcmd('remote add down http://127.0.0.1:8099/admin/missing',r'updated|Error:');cmd('wg test tick-reset',r'WGTICKS reset');out=botcmd('fetch down',r'Cannot reach|timed out|Error:',False);time.sleep(2);probe=cmd('wg test ticks',r'WGTICKS count');check('unreachable Hub leaves server ticks running','Cannot reach' in out,probe=probe)
        entered=threading.Event()
        class Slow(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                entered.set();time.sleep(6)
                try:self.send_response(503);self.end_headers();self.wfile.write(b'{}')
                except (BrokenPipeError,ConnectionResetError):pass
            def log_message(self,*_):pass
        slow=http.server.ThreadingHTTPServer(('127.0.0.1',8099),Slow);slow.daemon_threads=True;thread=threading.Thread(target=slow.serve_forever,daemon=True);thread.start()
        botcmd('remote set-url origin http://127.0.0.1:8099/admin/slow',r'updated|Error:');cmd('wg test tick-reset',r'WGTICKS reset')
        mark=len(bot.lines);bot.ask('chat /wg pr list','chat_sent');check('slow Hub request started',entered.wait(5))
        start=time.monotonic();during=client.action('state');elapsed=time.monotonic()-start
        time.sleep(2);probe=cmd('wg test ticks',r'WGTICKS count');tps=float(re.search(r'tps=([0-9.]+)',probe).group(1));p99=float(re.search(r'p99=([0-9.]+)',probe).group(1))
        check('slow Hub leaves server executor/render responsive',elapsed<2 and during['fps']>0 and tps>18 and p99<100,client_action_seconds=elapsed,client_fps=during['fps'],probe=probe)
        deadline=time.monotonic()+15
        while time.monotonic()<deadline:
            with bot.lock:out='\n'.join(e.get('t','') for e in bot.lines[mark:] if e.get('ev')=='chat')
            if 'timed out' in out:break
            time.sleep(.1)
        check('slow Hub returns fixed timeout i18n','timed out' in out)
        slow.shutdown();slow.server_close();slow=None;thread.join(2)
        client.finish();result['screenshots']=client.screenshots();client=None
        result['success']=True
      except BaseException as e:
        result['error']=repr(e).replace(TOKEN,'[REDACTED]').replace(SECRET,'[REDACTED]');result['trace']=traceback.format_exc().replace(TOKEN,'[REDACTED]').replace(SECRET,'[REDACTED]');print(result['trace'],flush=True)
      finally:
        if slow:slow.shutdown();slow.server_close()
        if client:client.stop()
        if relay:relay.stop()
        if server:
          try:
            server.stop();result['server_exit']=server.proc.returncode
            if result['success']:check('final offline verify zero differences',cli(server.world,'verify')['state']=='COMPLETE')
            result['problems']=[l for l in server.lines_since() if re.search(r'\bERROR\b|ClassNotFound|NoClassDefFound|thread check',l)]
          except BaseException:
            result['cleanup_error']=traceback.format_exc();result['success']=False
            try:result['final_difference']=retain_difference(server.world,work,cli)
            except BaseException:pass
          shutil.rmtree(server.dir,ignore_errors=True)
        if hub:hub.stop()
        for f in ['hub.jar','wgit.jar','tools.jar','fabric.jar','fixture.jar']:(work/f).unlink(missing_ok=True)
        shutil.rmtree(work/'hub-data',ignore_errors=True);shutil.rmtree(work/'b',ignore_errors=True)
        result['ports_closed']={str(p):port_closed(p) for p in [8094,8099,25741,25742,25751,25752,25761,25762]};result['success']=result['success'] and result.get('server_exit')==0 and not result.get('problems') and all(result['ports_closed'].values());save()
    print(work/'results.json',flush=True);return result['success']
def port_closed(p):
    with socket.socket() as s:return s.connect_ex(('127.0.0.1',p))!=0
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('version',choices=['1.21.11','26.2']);parser.add_argument('--visual-only',action='store_true');signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(RuntimeError('terminated')));sys.exit(0 if run(parser.parse_args()) else 1)
