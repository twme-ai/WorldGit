#!/usr/bin/env python3
"""Phase 5 任務 4（Fabric）：真 dedicated 伺服器＋協定 bot＋Xvfb 真客戶端。自取 bench.lock。

Session A（bot）：別名、地獄單獨 init＋追加按鈕、graph、BossBar、每個動作的完成訊息、錯誤複製與遮罩、ignore 指令、
  creative player-touched 實體（自然牛不入庫、命名後入庫、盔甲座還原、跨維度 UUID 預檢）、auto／logout／shutdown commit。
Session B（真客戶端）：init 追加按鈕、聊天 graph、分支圖畫面、BossBar／HUD 進度與終態、完成訊息、錯誤［複製］、ignore 編輯畫面。
Session C／D：別名停用開關；已有第三方 /git 時跳過。
"""
import argparse,hashlib,json,os,re,shutil,signal,socket,subprocess,sys,time,traceback
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'paper/tools'));sys.path.insert(0,str(ROOT/'fabric/tools'))
import harness,dedicated_harness
from cli_compat import repository,complete_verification_batch
import phase5_support as support
from phase5_support import Recorder,Client,events,hover_text,texts,humanly,SECRET_ENV,SECRET_YAML

JAVA=harness.JAVA
BASE_CONFIG='locale: en_us\npermission-level: 2\nread-permission-level: 0\nauto-commit:\n  on-logout: true\n  on-stop: true\n  interval-minutes: 0\nfeedback:\n  terminal-seconds: 4\n'

def build(version,work):
    project=support.project(version);fixture_task='remapDedicatedFixtureJar' if version=='1.21.11' else 'dedicatedFixtureJar'
    with (work/'build.log').open('w') as out:
        subprocess.run(['./gradlew','--no-daemon','--configure-on-demand','--max-workers=1',f':fabric:{project}:assemble',f':fabric:{project}:{fixture_task}',':cli:fatJar'],cwd=ROOT,env=dict(os.environ,JAVA_HOME='/usr/lib/jvm/java-25-openjdk-amd64',GRADLE_USER_HOME=str(ROOT/'.work/gradle-home')),stdout=out,stderr=subprocess.STDOUT,check=True)
    suffix='-0.1.0-SNAPSHOT'
    mod=ROOT/f'fabric/{project}/build/libs/worldgit-fabric-{version}{suffix}.jar'
    fixture=ROOT/f'fabric/{project}/build/libs/worldgit-fabric-{version}{suffix}-dedicated-fixture{"-remapped" if version=="1.21.11" else ""}.jar'
    shutil.copy2(mod,work/'worldgit.jar');shutil.copy2(fixture,work/'fixture.jar');shutil.copy2(ROOT/'cli/build/libs/wgit.jar',work/'wgit.jar')

class Session:
    """一次 dedicated 伺服器執行（world 副本在 work/<name>/server）。"""
    def __init__(self,version,work,name,config=BASE_CONFIG,jvm=(),env=None,bots=('WgBot',)):
        self.version=version;self.work=work/name;self.work.mkdir(parents=True)
        baseline=ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+version)
        self.server=dedicated_harness.Server(version,self.work,baseline,work/'worldgit.jar',work/'fixture.jar',config=config,jvm=jvm,env=env,ops=bots)
        if version=='1.21.11':
            for source,n in [(baseline/'world_nether/DIM-1','DIM-1'),(baseline/'world_the_end/DIM1','DIM1')]:shutil.copytree(source,Path(self.server.world)/n)
        self.bots={}
    @property
    def world(self):return self.server.world
    def start(self):self.server.start();return self
    def bot(self,name,mod=False):
        b=self.server.bot(name,mod);self.bots[name]=b;return b
    def repo(self,dimension='minecraft:overworld'):return repository(self.world,dimension,self.version)
    def initialized(self,dimension):return (self.repo(dimension)/'HEAD').exists()
    def stop(self):
        self.server.stop()

def run(args):
    version=args.version
    work=ROOT/'.work/fabric-phase5'/f'{args.mode}-{version}-{int(time.time())}';work.mkdir(parents=True)
    result={'platform':'fabric','mode':args.mode,'version':version,'success':False,'steps':[],'evidence':str(work.relative_to(ROOT))}
    rec=Recorder(work/'result.json',result);check=rec.check
    sessions=[];client=None
    old_token=os.environ.get('WGIT_TOKEN')
    lock=harness.BenchLock();lock.__enter__()
    try:
        build(version,work)
        result['artifacts']={name:hashlib.sha256((work/name).read_bytes()).hexdigest() for name in ('worldgit.jar','fixture.jar','wgit.jar')}
        if args.mode in ('dedicated','all'):
            if 'a' in args.stages:session_a(args,work,result,check,sessions)
            if 'b' in args.stages:session_b(args,work,result,check,sessions)
            if 'c' in args.stages:session_cd(args,work,result,check,sessions)
        if args.mode in ('single','all'):session_single(args,work,result,check)
        result['success']=True
    except BaseException as error:
        result['error']=repr(error);result['trace']=traceback.format_exc();print(result['trace'],flush=True)
    finally:
        for session in sessions:
            try:session.stop()
            except BaseException as error:result.setdefault('cleanup_errors',[]).append(repr(error))
        for session in sessions:shutil.rmtree(Path(session.server.dir),ignore_errors=True)
        for name in ('worldgit.jar','fixture.jar','wgit.jar'):(work/name).unlink(missing_ok=True)
        if old_token is None:os.environ.pop('WGIT_TOKEN',None)
        else:os.environ['WGIT_TOKEN']=old_token
        result['ports_closed']={str(p):port_closed(p) for p in (25701,25702)}
        result['success']=result['success'] and all(result['ports_closed'].values()) and not result.get('cleanup_errors');rec.save()
        lock.__exit__(None,None,None)
    print(work/'result.json',flush=True);return result['success']

def port_closed(port):
    with socket.socket() as probe:return probe.connect_ex(('127.0.0.1',port))!=0

# ---------------------------------------------------------------------------------------------

class Chat:
    """bot 的聊天觀察：每個動作等到終止行（'<op> finished:'）。"""
    def __init__(self,bot):self.bot=bot
    def rows(self,mark):
        with self.bot.lock:return [e for e in self.bot.lines[mark:] if e.get('ev')=='chat']
    def bars(self,mark):
        with self.bot.lock:return [e for e in self.bot.lines[mark:] if e.get('ev')=='bossbar']
    def say(self,command,expect=r'\bfinished: ',timeout=300,settle=.7,all_dims=False):
        bot=self.bot;mark=len(bot.lines);bot.ask('chat /'+command,'chat_sent');rx=re.compile(expect) if expect else None;deadline=time.monotonic()+timeout
        while rx and time.monotonic()<deadline:
            rows=self.rows(mark)
            if any(rx.search(r['t']) and (not all_dims or ' · all' in r['t']) for r in rows):break
            time.sleep(.1)
        else:
            if rx:raise TimeoutError(command+' '+str([r['t'] for r in self.rows(mark)][-6:]))
        time.sleep(settle if rx else 2.0)
        return self.rows(mark),mark

def session_a(args,work,result,check,sessions):
    version=args.version
    os.environ['WGIT_TOKEN']=SECRET_ENV
    s=Session(version,work,'session-a',env={'WGIT_TOKEN':SECRET_ENV});sessions.append(s)
    cred=Path(s.server.dir)/'config/credentials.yml';cred.write_text('credentials:\n  https://fixture.invalid:\n    mode: bearer\n    token: '+SECRET_YAML+'\n');cred.chmod(0o600)
    s.start()
    srv=s.server
    bot=s.bot('WgBot');plain=s.bot('WgPlain');chat=Chat(bot);time.sleep(3)
    srv.cmd('gamemode creative WgBot');srv.cmd('gamemode creative WgPlain')
    srv.cmd('gamerule doMobSpawning false',None) if False else None
    cli=work/'wgit.jar'
    def probe(dimension='minecraft:overworld'):
        out=subprocess.check_output([JAVA['1.21.11'],'-cp',str(cli),str(ROOT/'paper/tools/phase5-fixture/Phase5RepoProbe.java'),str(s.repo(dimension))],text=True,timeout=300)
        return set(out.split())
    def tp(dimension,x,y,z,who='WgBot'):
        srv.cmd(f'execute in {dimension} run tp {who} {x} {y} {z}',r'Teleported');time.sleep(3)
    # ---- 別名 ----
    for alias in ('wg','wgit','git','worldgit'):
        rows,_=chat.say(alias+' help');check(f'/{alias} help works',any('version control for Minecraft worlds' in r['t'] for r in rows) and any('help finished: SUCCESS' in r['t'] for r in rows),chat=texts(rows)[-4:])
    # ---- 地獄單獨 init ----
    tp('minecraft:the_nether',8,65,8)
    rows,_=chat.say('wg init');check('init in the nether only initializes the nether',s.initialized('minecraft:the_nether') and not s.initialized('minecraft:overworld') and not s.initialized('minecraft:the_end'),chat=texts(rows)[-6:])
    check('nether init has no append buttons',not events(rows,'run_command'))
    # 觀察者（控制台）也有人類可讀的完成行
    check('nether init completion is human readable',any(re.search(r'init finished: SUCCESS · minecraft:the_nether · minecraft:the_nether [0-9a-f]{8}: added [\d,]+ blocks, [\d,]+ sections',r['t']) for r in rows) and humanly(texts(rows)),chat=texts(rows)[-4:])
    tp('minecraft:overworld',8,65,8)
    # ---- 主世界 init＋追加按鈕 ----
    time.sleep(2)
    rows,_=chat.say('wg init')
    runs=events(rows,'run_command')
    check('overworld init offers the missing End only (nether already initialized)',any('--dimension minecraft:the_end' in c for c in runs) and not any('--dimension minecraft:the_nether' in c for c in runs),clicks=runs)
    check('init is hover-described',any('--dimension minecraft:the_end' in h for h in hover_text(rows)))
    check('creative init defaults to entities: player-touched','entities: player-touched' in (s.repo()/'worldgit-repo.yml').read_text())
    check('overworld init did not touch the End',not s.initialized('minecraft:the_end'))
    button=next(c for c in runs if '--dimension minecraft:the_end' in c)
    rows,_=chat.say(button.removeprefix('/'))
    check('append button command initializes the End',s.initialized('minecraft:the_end'),chat=texts(rows)[-4:])
    # ---- graph ----
    rows,_=chat.say('wg log --graph')
    check('chat graph has lanes, colored labels, hover and suggest',any('[main]' in r['t'] and '* ' in r['t'] for r in rows) and events(rows,'suggest_command') and any('/wg diff ' in c and '--dimension minecraft:overworld' in c for c in events(rows,'suggest_command')),chat=texts(rows)[-4:])
    check('graph hover carries the full commit id',any(re.search(r'[0-9a-f]{40}',h) for h in hover_text(rows)))
    # ---- 每個動作的完成訊息 ----
    srv.cmd('wg test fixture-stable',r'WGSTABLE')
    matrix=[]
    def act(command,expect_status=None,summary=None,absent=None,timeout=300,all_dims=False,**kw):
        rows,_=chat.say(command,timeout=timeout,all_dims=all_dims,**kw);lines=texts(rows)
        op=command.split()[1] if command.startswith('wg ') else command.split()[0]
        done=[l for l in lines if re.search(r'\bfinished: ',l)]
        ok=bool(done) and humanly(lines)
        if expect_status:ok=ok and any(f'finished: {expect_status}' in l for l in done)
        if summary:ok=ok and any(re.search(summary,l) for l in lines)
        matrix.append({'command':command,'finished':done[-1] if done else None})
        check(f'completion message: {command}',ok,chat=lines[-5:])
        return rows
    act('wg remote','FAILED');act('wg status','SUCCESS',r'clean');act('wg status --full','SUCCESS');act('wg commit -m nothing','NO_OP',r'no changes')
    srv.cmd('setblock 2 64 2 stone',r'Changed the block')
    act('wg commit -m first-change','SUCCESS',r'added 1 blocks, 1 sections')
    act('wg log');act('wg log --graph');act('wg graph','SUCCESS');act('wg diff','SUCCESS');act('wg diff --blocks','SUCCESS')
    act('wg branch list','SUCCESS');act('wg branch create m1','SUCCESS');act('wg tag create t1','SUCCESS');act('wg tag list','SUCCESS');act('wg tag delete t1','SUCCESS')
    act('wg verify','SUCCESS');act('wg info','SUCCESS');act('wg reload','SUCCESS');act('wg clear','SUCCESS');act('wg cancel','SUCCESS');act('wg conflicts','SUCCESS')
    srv.cmd('setblock 3 64 3 gold_block',r'Changed the block')
    act('wg stash push saved','SUCCESS',r'sections');act('wg stash list','SUCCESS');act('wg stash pop','SUCCESS');act('wg commit -m stash-result','SUCCESS')
    act('wg switch m1','SUCCESS',r'main|m1|@');act('wg switch main','SUCCESS',r'main @ [0-9a-f]{8}')
    act('wg restore HEAD --dry-run','SUCCESS',r'dry run');act('wg reset --hard --dry-run','SUCCESS',r'dry run');act('wg branch delete m1','SUCCESS')
    # 錯誤路徑：失敗終態＋複製按鈕＋遮罩
    rows=act('wg preview HEAD','FAILED');check('error message has a separate copy button',events(rows,'copy_to_clipboard'),clicks=events(rows,'copy_to_clipboard'))
    for command in ('wg fetch','wg push','wg pull','wg pr list','wg comments','wg remote list'):
        rows=act(command)
    rows=act('wg switch phase5-arbitrary-secret','FAILED');copies=events(rows,'copy_to_clipboard')
    check('copy report masks the environment token and keeps fields',copies and all(SECRET_ENV not in c for c in copies) and any('UTC=' in c and 'Minecraft=' in c and 'WorldGit=' in c for c in copies),copies=copies)
    rows=act('wg switch yaml-arbitrary-secret','FAILED');copies=events(rows,'copy_to_clipboard')
    check('copy report masks the credentials file token',copies and all(SECRET_YAML not in c for c in copies),copies=copies)
    check('copy hover text comes from i18n',any('copy the error report' in h for h in hover_text(rows)))
    for command in ('wg remote add origin https://hub.invalid/alice/world','wg remote list','wg remote set-url origin https://hub.invalid/bob/world','wg remote remove origin'):act(command,'SUCCESS')
    act('wg init --dimension minecraft:the_nether','NO_OP')   # 已 init：略過
    # --all：逐維度各自回報
    rows=act('wg status --all',all_dims=True);finished=[l for l in texts(rows) if re.search(r'\bfinished: ',l)]
    check('--all reports each dimension separately and then the batch',sum('status finished:' in l for l in finished)==4 and any(' all ' in l or '· all' in l for l in finished),finished=finished)
    rows=act('wg log --graph --all',all_dims=True)
    check('--dimension targets one dimension',bool(events(rows,'suggest_command')) and all('--dimension minecraft:' in c for c in events(rows,'suggest_command')),clicks=events(rows,'suggest_command'))
    nether_head=subprocess.check_output(['git','--git-dir',str(s.repo('minecraft:the_nether')),'rev-parse','HEAD'],text=True).strip()
    act('wg branch create only-nether --dimension minecraft:the_nether','SUCCESS')
    refs=lambda dim:subprocess.check_output(['git','--git-dir',str(s.repo(dim)),'for-each-ref','--format=%(refname)'],text=True)
    check('--dimension isolates the branch',('refs/heads/only-nether' in refs('minecraft:the_nether')) and 'refs/heads/only-nether' not in refs('minecraft:overworld'))
    # ---- creative player-touched 實體 ----
    srv.cmd('kill @e[type=!player]',r'Killed|No entity');
    pos=bot.ask('pos','pos')['pos'];px,py,pz=pos['x'],pos['y'],pos['z']
    cow_out=srv.cmd(f'wg test natural-cow {px+1.5} {py} {pz}',r'WGCOW uuid=');cow=re.search(r'uuid=([0-9a-f-]{36})',cow_out)[1]
    act('wg commit -m natural-cow');check('natural cow is not committed',cow not in probe(),entities=sorted(probe()))
    srv.cmd(f'give WgBot name_tag[custom_name=\'"Named"\']',r'Gave');time.sleep(1)
    interact=bot.ask('interact_uuid '+cow,'entity_interacted',30);time.sleep(1.5)
    act('wg commit -m named-cow','SUCCESS');check('named cow becomes player-touched and is committed',cow in probe(),interact=interact.get('held'),entities=sorted(probe()))
    # ---- ignore 指令（無模組客戶端可用） ----
    rows=act('wg ignore list','SUCCESS');act('wg ignore check','SUCCESS')
    rows=act('wg ignore add entity minecraft:cow','SUCCESS')
    raw=json.dumps(rows,ensure_ascii=False)
    check('ignore add previews before any change and offers a confirm click','Preview:' in raw and any('ignore' in c and 'confirm' in c for c in events(rows,'run_command')),clicks=events(rows,'run_command'))
    check('ignore file unchanged before confirmation','entity minecraft:cow' not in ((s.repo()/'.wgignore').read_text() if (s.repo()/'.wgignore').exists() else ''))
    confirm=next(c for c in events(rows,'run_command') if 'confirm' in c)
    rows=act(confirm.removeprefix('/'),'SUCCESS')
    check('ignore confirmed writes the rule','entity minecraft:cow' in (s.repo()/'.wgignore').read_text())
    act('wg ignore test entity minecraft:cow 0,64,0','SUCCESS');act('wg ignore move 1 1','SUCCESS');act('wg ignore cancel','SUCCESS')
    rows=act('wg ignore disable 1','SUCCESS');confirm=next(c for c in events(rows,'run_command') if 'confirm' in c);act(confirm.removeprefix('/'),'SUCCESS')
    check('disabled rule is kept as a restorable line','worldgit-disabled' in (s.repo()/'.wgignore').read_text())
    rows=act('wg ignore enable 1','SUCCESS');confirm=next(c for c in events(rows,'run_command') if 'confirm' in c);act(confirm.removeprefix('/'),'SUCCESS')
    rows,_=Chat(plain).say('wg ignore list',expect=None)
    check('non-privileged player cannot use ignore (permission)',not any('finished:' in r['t'] for r in rows))
    act('wg commit -m ignored-cow','SUCCESS');check('committed ignore rule excludes the cow from the next snapshot',cow not in probe())
    srv.cmd('clear WgBot',r'Removed|No items');srv.cmd('give WgBot armor_stand',r'Gave');time.sleep(1.5)
    spot=(int(px)-2,int(py)-1,int(pz))
    placed=bot.ask(f'place_entity {spot[0]} {spot[1]} {spot[2]}','entity_place_sent' if version=='26.2' else 'entity_placed',30);time.sleep(2)
    entities=srv.cmd('wg test entities',r'WGENTITIES minecraft:overworld');armor=re.search(r'armor_stand:([0-9a-f-]{36})',entities)
    check('placed armor stand exists',armor is not None,entities=entities);armor=armor[1]
    act('wg commit -m armor-stand','SUCCESS');check('placed armor stand is player-touched and committed',armor in probe())
    srv.cmd(f'kill {armor}',r'Killed')
    cow2=re.search(r'uuid=([0-9a-f-]{36})',srv.cmd(f'wg test natural-cow {px+1.5} {py} {pz+1}',r'WGCOW uuid='))[1]
    act('wg restore HEAD','SUCCESS')
    entities=srv.cmd('wg test entities',r'WGENTITIES minecraft:overworld')
    check('armor stand restored; natural cow created after the commit is preserved',armor in entities and cow2 in entities,entities=entities)
    srv.cmd(f'execute in minecraft:the_nether run tp {armor} 8 70 8',r'Teleported');time.sleep(2)
    # 目的地 entity chunk 可能未載入（不凍結後 chunk 會正常卸載）：實體在 entity manager 內但不在可見清單，故以 knownUuids 判斷。
    transferred=srv.cmd(f'execute in minecraft:the_nether run wg test known {armor}',r'WGKNOWN minecraft:the_nether')
    check('transferred armor stand is present in the destination before restore','true' in transferred,entities=transferred)
    rows=act('wg restore HEAD','FAILED');raw=json.dumps(rows,ensure_ascii=False)
    check('cross-dimension duplicate UUID is rejected before writing and names the dimension','minecraft:the_nether' in raw and 'duplicate UUID' in raw,chat=texts(rows)[-4:])
    act('wg commit --dimension minecraft:the_nether -m transferred','SUCCESS');check('destination dimension inherits the touched UUID',armor in probe('minecraft:the_nether'))
    act('wg commit -m departed','SUCCESS');check('source dimension drops the departed UUID on the next commit',armor not in probe())
    # ---- MERGING：ignore 禁止修改，merge／resolve／abort 的終止訊息 ----
    act('wg branch create base-p5','SUCCESS');act('wg switch base-p5','SUCCESS')
    srv.cmd('setblock 5 64 5 diamond_block',r'Changed the block');act('wg commit -m theirs-side','SUCCESS');act('wg branch create theirs-p5','SUCCESS');act('wg switch main','SUCCESS')
    srv.cmd('setblock 5 64 5 emerald_block',r'Changed the block');act('wg commit -m ours-side','SUCCESS')
    act('wg branch create ours-p5','SUCCESS')
    rows=act('wg merge theirs-p5','SUCCESS',r'unresolved conflict region');
    act('wg conflicts','SUCCESS',r'#1')
    rows=act('wg ignore add entity minecraft:pig','FAILED');check('ignore edits are refused while MERGING',any('MERGING' in t for t in texts(rows)),chat=texts(rows)[-3:])
    act('wg commit -m while-merging');act('wg conflict-select 1 theirs','SUCCESS');act('wg resolve 1 ours','SUCCESS');act('wg merge --continue','SUCCESS')
    # 先回到保存的單 parent ours，重新造成真衝突再 abort；已合併祖先的 NO_OP 不能當 abort fixture。
    act('wg switch ours-p5','SUCCESS');act('wg merge theirs-p5','SUCCESS',r'unresolved conflict region');act('wg merge --abort','SUCCESS')
    act('wg revert HEAD','SUCCESS');act('wg cherry-pick theirs-p5','SUCCESS')
    # ---- BossBar：長操作期間出現、終態、之後移除 ----
    mark=len(bot.lines);rows,_=chat.say('wg status --full',settle=6)
    bars=chat.bars(mark)
    # 前一操作的終態可能還在 TTL 內；不能把它的 REMOVE 和本次 status 的 UPDATE 混在一起比較。
    added={b['packet']['entityUUID'] for b in bars if b['packet']['action']==0}
    check('status creates exactly one BossBar for the executor',len(added)==1,ids=sorted(added))
    bar_id=next(iter(added));bars=[b for b in bars if b['packet']['entityUUID']==bar_id]
    actions=[b['packet']['action'] for b in bars]
    titles=json.dumps([b['packet'].get('title') for b in bars],ensure_ascii=False)
    check('bossbar added, updated with progress, shows the terminal state and is removed',0 in actions and 3 in actions and 1 in actions and 'status finished: SUCCESS' in titles and actions.index(1)>max(i for i,t in enumerate(actions) if t in (0,3,2,4)),id=bar_id,actions=actions,packets=bars)
    # ---- auto commit／logout commit／shutdown commit ----
    srv.cmd('setblock 9 64 9 lapis_block',r'Changed the block')
    out=srv.cmd('wg test auto',r'auto\.commit finished:',60)
    check('auto commit has a terminal message (log + authorized player)',re.search(r'auto\.commit finished: SUCCESS',out) is not None and any('auto.commit finished:' in r['t'] for r in chat.rows(0)),log=out[-400:])
    srv.cmd('give WgPlain stone',r'Gave');
    from harness import place_block
    place_block(plain,dx=2);mark=srv.mark();plain.stop();logout=srv.wait(r'logout\.commit finished:',90,mark)
    check('logout commit has a terminal message',bool(re.search(r'logout\.commit finished: (SUCCESS|NO_OP)',logout)),log=logout[-400:])
    result['matrix']=matrix
    # ---- 離線 verify（停服後） ----
    mark=srv.mark();srv.send('stop');shutdown=srv.wait(r'shutdown\.commit finished:',120,mark)
    check('shutdown commit has a terminal message',bool(re.search(r'shutdown\.commit finished: (SUCCESS|NO_OP)',shutdown)),log=shutdown[-400:])
    s.stop();problems=[l for l in srv.problems(0) if 'jgit' not in l and 'OFFLINE' not in l and 'offline' not in l.lower() and 'authenticate' not in l and 'online-mode' not in l and 'Missing data pack' not in l and 'hackers' not in l]
    result['server_problems_a']=problems
    check('server log has no unexpected ERROR',not [l for l in problems if 'ERROR' in l or 'Exception' in l],problems=problems[:8])
    verify=subprocess.run([JAVA['1.21.11'],'-jar',str(cli),'-w',srv.world,'--format=json','verify','HEAD','--all'],text=True,capture_output=True,timeout=900)
    check('offline verify has zero differences',verify.returncode==0 and complete_verification_batch(verify.stdout),stdout=verify.stdout[-400:])
    os.environ.pop('WGIT_TOKEN',None)

def client_command(client,command,timeout=300):
    mark=client.chat_mark();client.command(command,nowait=True)
    rows=client.wait_chat(mark,r'\bfinished: ',timeout)
    if any('CLI' in text or 'UUID 集合' in text for text in texts(rows)):
        raise AssertionError('遊戲內不應顯示離線 CLI 實體提示：'+str(texts(rows)))
    return rows

def client_ui(client,check,result):
    """以真 Screen 元件點擊，驗證 editor 的 responder、preview／確認及 graph 指令填入。"""
    def shot(name):
        target=client.shot(name);result.setdefault('screenshots',[]).append(str(target.relative_to(ROOT)))
    rows=client_command(client,'wg log --graph')
    check('client chat graph has hover and suggest',events(rows,'suggest_command') and any(re.search(r'[0-9a-f]{40}',h) for h in hover_text(rows)))
    client.action('chat-open');shot('chat-graph');client.action('close')
    client_command(client,'wg graph');check('graph Screen opens',client.action('wait-screen',name='GraphScreen')['ok'])
    state=client.action('screen');check('graph Screen displays server nodes',state['nodes']>0,nodes=state['nodes'])
    check('graph refresh publishes a new server generation',client.action('refresh-graph')['ok'])
    client.action('select',index=0);shot('graph-screen')
    check('copy commit button works',client.action('click',label='Copy commit id')['ok'])
    check('graph copies the full commit id',bool(re.fullmatch(r'[0-9a-f]{40}',client.action('clipboard')['text'])))
    check('diff button fills chat',client.action('click',label='Fill /wg diff')['ok'])
    # 真 ChatScreen 輸入由版本 adapter 讀取，不送出指令。
    check('suggested diff is bound to the graph dimension',bool(re.match(r'/wg diff [0-9a-f]{40} --dimension minecraft:overworld',client.action('screen')['input'])))
    client.action('close')
    rows=client_command(client,'wg switch missing-phase5-branch')
    check('client business error has copy and i18n hover',events(rows,'copy_to_clipboard') and any('copy the error report' in h for h in hover_text(rows)))
    client.action('chat-open');check('copy hover is hit by the real mouse',client.action('hover-chat')['found']);shot('error-copy-hover');client.action('close')
    client_command(client,'wg ignore gui');check('ignore Screen opens',client.action('wait-screen',name='IgnoreScreen')['ok'])
    state=client.action('screen');comments=[row.strip() for row in state['ignoreRows'] if row.strip().startswith('#')]
    check('ignore Screen preserves comments without a duplicated marker',bool(comments) and not any(re.match(r'^#\s+#',row) for row in comments),rows=state['ignoreRows'])
    shot('ignore-editor')
    client.action('type',text='not a valid rule')
    state=client.action('screen');check('live syntax errors disable Add',any(b=='Add rule|false' for b in state['buttons']),buttons=state['buttons'])
    shot('ignore-syntax-error')
    client.action('type',text='entity minecraft:armor_stand')
    state=client.action('screen');check('valid typing enables Add without rebuilding the Screen',any(b=='Add rule|true' for b in state['buttons']),buttons=state['buttons'])
    mark=client.chat_mark();check('Add sends a server preview request',client.action('click',label='Add')['ok'])
    rows=client.wait_chat(mark,r'ignore\.add finished: SUCCESS')
    state=client.action('screen');check('preview requires explicit confirmation',any(b=='Confirm change|true' for b in state['buttons']),buttons=state['buttons']);shot('ignore-preview')
    mark=client.chat_mark();check('Confirm sends the preview code',client.action('click',label='Confirm')['ok']);client.wait_chat(mark,r'ignore\.confirm finished: SUCCESS')
    client.action('close');client_command(client,'wg commit -m ignore-from-screen')
    rows=client_command(client,'wg ignore test entity minecraft:armor_stand 0,64,0')
    check('confirmed and committed GUI rule takes effect',any('Excluded: true' in t for t in texts(rows)),chat=texts(rows))
    # 舊畫面／兩步確認也檢查 HEAD lease；preview 之後 commit，舊 confirm 必須拒絕。
    rows=client_command(client,'wg ignore add entity minecraft:pig')
    confirm=next(c for c in events(rows,'run_command') if 'confirm' in c)
    client_command(client,'wg commit -m noop-before-confirm')
    # NO_OP 不會移動 HEAD；實際規則修改讓舊 preview 過期。
    client_command(client,'wg ignore add entity minecraft:sheep')
    rows=client_command(client,confirm.removeprefix('/'))
    check('superseded preview cannot be confirmed',any('finished: FAILED' in t for t in texts(rows)),chat=texts(rows))
    client_command(client,'wg ignore cancel')
    client.action('chat-open');shot('completion-message');client.action('close')

def client_progress(client,check,result):
    # init/status full 使用真 capture，觀察 RUNNING、終態及 TTL；不合成進度封包。
    client.action('wait-hud-gone')
    mark=client.chat_mark();client.command('wg status --full',nowait=True)
    running=client.action('wait-hud',prefix='RUNNING|status|')
    check('long scan shows running HUD',running['ok'],hud=running)
    state=client.action('hud');check('vanilla BossBar appears with HUD',bool(state['bars']),state=state)
    target=client.shot('progress-running');result.setdefault('screenshots',[]).append(str(target.relative_to(ROOT)))
    rows=client.wait_chat(mark,r'status finished: SUCCESS',600)
    terminal=client.action('wait-hud',prefix='SUCCESS|status|')
    check('HUD displays the terminal state',terminal['ok'],hud=terminal)
    state=client.action('hud');check('BossBar displays the same terminal state',any('status finished: SUCCESS' in b for b in state['bars']),state=state)
    target=client.shot('progress-terminal');result['screenshots'].append(str(target.relative_to(ROOT)))
    check('completion is human readable',humanly(texts(rows)),chat=texts(rows))
    check('terminal HUD disappears after TTL',client.action('wait-hud-gone')['ok'])
    check('terminal BossBar disappears after TTL',not client.action('hud')['bars'])

def session_b(args,work,result,check,sessions):
    session=Session(args.version,work,'session-b');sessions.append(session);session.start()
    client=None
    try:
        client=Client(session.work,args.version,False,session.server.port)
        player=client.ready['player'];session.server.cmd('op '+player);session.server.cmd('gamemode creative '+player)
        session.server.cmd('wg test fixture-stable',r'WGSTABLE');session.server.cmd(f'tp {player} 8 65 8 0 25',r'Teleported');time.sleep(3)
        rows=client_command(client,'wg init',900)
        check('true client receives nether, End and All append buttons',len(events(rows,'run_command'))==3,clicks=events(rows,'run_command'))
        client.action('chat-open');target=client.shot('init-append-buttons');result.setdefault('screenshots',[]).append(str(target.relative_to(ROOT)));client.action('close')
        for dim in ('minecraft:the_nether','minecraft:the_end'):
            button=next(c for c in events(rows,'run_command') if '--dimension '+dim in c)
            client_command(client,button.removeprefix('/'),900);check('client append command initializes '+dim,session.initialized(dim))
        check('UI capability negotiated',client.ready['uiCapable'])
        client_progress(client,check,result)
        spawned=session.server.cmd('summon armor_stand 6 65 8 {NoGravity:1b}',r'Summoned')
        armor=re.search(r'armor_stand:([0-9a-f-]{36})',session.server.cmd('wg test entities',r'WGENTITIES minecraft:overworld'))[1]
        client_command(client,'wg commit -m armor-before-editor')
        before=subprocess.check_output([JAVA['1.21.11'],'-cp',str(work/'wgit.jar'),str(ROOT/'paper/tools/phase5-fixture/Phase5RepoProbe.java'),str(session.repo())],text=True,timeout=300).split()
        check('armor stand is tracked before the GUI edit',armor in before)
        client_ui(client,check,result)
        stored=subprocess.check_output([JAVA['1.21.11'],'-cp',str(work/'wgit.jar'),str(ROOT/'paper/tools/phase5-fixture/Phase5RepoProbe.java'),str(session.repo())],text=True,timeout=300).split()
        check('GUI preview-confirm-commit excludes an actual tracked armor stand',armor not in stored)
        # 真畫面 preview 之後撤權，再按確認；舊 Screen 不能繞過 server 權限，也必須有失敗終態。
        client_command(client,'wg ignore gui');client.action('wait-screen',name='IgnoreScreen')
        client.action('type',text='entity minecraft:pig');mark=client.chat_mark();client.action('click',label='Add')
        client.wait_chat(mark,r'ignore\.add finished: SUCCESS')
        rules=(session.repo()/'.wgignore').read_text()
        session.server.cmd('deop '+player,r'operator')
        try:
            mark=client.chat_mark();check('stale authorized Screen still sends the confirm request',client.action('click',label='Confirm')['ok'])
            denied=client.wait_chat(mark,r'ignore\.confirm finished: FAILED')
            check('server refuses GUI confirmation after permission is revoked and sends copy error',bool(events(denied,'copy_to_clipboard')) and any('op level 2' in t for t in texts(denied)),chat=texts(denied))
            check('revoked GUI confirmation leaves the rules unchanged',(session.repo()/'.wgignore').read_text()==rules)
        finally:
            session.server.cmd('op '+player,r'operator')
        client.action('close');client_command(client,'wg ignore cancel')
        client.finish()
    finally:
        if client:client.stop()
        session.stop()

def session_cd(args,work,result,check,sessions):
    for name,config,jvm in [('disabled',BASE_CONFIG+'aliases:\n  wgit: false\n  git: false\n',()),('conflict',BASE_CONFIG,('-Dworldgit.fixture.git=true',))]:
        session=Session(args.version,work,'session-'+name,config=config,jvm=jvm);sessions.append(session);session.start()
        try:
            chat=Chat(session.bot('WgBot'))
            if name=='disabled':
                for alias in ('wgit','git'):
                    rows,_=chat.say(alias+' help',expect=None)
                    check('/'+alias+' disabled by YAML',not any('finished:' in t for t in texts(rows)) and any('Unknown' in t for t in texts(rows)),chat=texts(rows))
            else:
                rows,_=chat.say('git',expect=None)
                check('existing third-party /git is preserved',any('OTHER_GIT' in t for t in texts(rows)) and not any('finished:' in t for t in texts(rows)),chat=texts(rows))
                check('/git conflict is logged once',sum('WORLDGIT 已有其他模組註冊 /git' in l for l in session.server.lines_since(0))==1)
            for alias in ('wg','worldgit'):
                rows,_=chat.say(alias+' help');check('/'+alias+' still works with '+name,any('help finished: SUCCESS' in t for t in texts(rows)))
            if name=='conflict':
                rows,_=chat.say('wgit help');check('/wgit works when /git conflicts',any('help finished: SUCCESS' in t for t in texts(rows)))
        finally:session.stop()

def session_single(args,work,result,check):
    directory=work/'single';directory.mkdir();client=None;world=None
    try:
        client=Client(directory,args.version,True);world=Path(client.ready['world'])
        check('singleplayer UI capable owner without cheats',client.ready['uiCapable'])
        # 追加按鈕只列實際存在的維度；先進入並儲存 End，保持其 repo 未初始化。
        client.action('prepare-dimension',dimension='minecraft:the_end')
        client.action('prepare-dimension',dimension='minecraft:the_nether')
        rows=client_command(client,'wg init',900)
        check('singleplayer nether init only initializes the nether',(repository(world,'minecraft:the_nether',args.version)/'HEAD').exists() and not (repository(world)/'HEAD').exists())
        client.action('prepare-dimension',dimension='minecraft:overworld')
        rows=client_command(client,'wg init',900)
        check('singleplayer main init offers remaining End',any('--dimension minecraft:the_end' in c for c in events(rows,'run_command')),chat=texts(rows))
        check('singleplayer creative defaults to player-touched','entities: player-touched' in (repository(world)/'worldgit-repo.yml').read_text())
        client.action('chat-open');target=client.shot('init-append-buttons');result.setdefault('screenshots',[]).append(str(target.relative_to(ROOT)));client.action('close')
        button=next(c for c in events(rows,'run_command') if '--dimension minecraft:the_end' in c);client_command(client,button.removeprefix('/'),900)
        check('singleplayer append command executes',(repository(world,'minecraft:the_end',args.version)/'HEAD').exists())
        for alias in ('wgit','git'):
            rows=client_command(client,alias+' help');check('singleplayer /'+alias+' is usable without cheats',any('help finished: SUCCESS' in t for t in texts(rows)))
        def probe():
            return set(subprocess.check_output([JAVA['1.21.11'],'-cp',str(work/'wgit.jar'),str(ROOT/'paper/tools/phase5-fixture/Phase5RepoProbe.java'),str(repository(world))],text=True,timeout=300).split())
        cow=client.action('natural-cow')['uuid'];client_command(client,'wg commit -m natural-cow')
        check('singleplayer natural cow is excluded',cow not in probe())
        client.action('server-command',command='give @a name_tag[custom_name=\'"Named"\']');client.action('wait-ticks',ticks=20)
        check('singleplayer real name-tag interaction succeeds',client.action('interact',uuid=cow)['ok']);client_command(client,'wg commit -m named-cow')
        check('singleplayer named cow is tracked',cow in probe())
        client.action('server-command',command='clear @a');client.action('server-command',command='give @a armor_stand');client.action('wait-ticks',ticks=20)
        check('singleplayer places armor stand using real item interaction',client.action('place-armor')['ok'])
        armor=client.action('entities',type='minecraft:armor_stand')['uuids'];check('singleplayer armor stand spawned',len(armor)==1);armor=armor[0]
        client_command(client,'wg commit -m armor-stand');check('singleplayer placed armor stand is tracked',armor in probe())
        client.action('server-command',command='kill '+armor);cow2=client.action('natural-cow')['uuid']
        client_command(client,'wg restore HEAD');current=client.action('entities',type='minecraft:armor_stand')['uuids']
        check('singleplayer armor stand restored and natural cow preserved',armor in current and cow2 in client.action('entities',type='minecraft:cow')['uuids'])
        client.action('server-command',command='execute in minecraft:the_nether run tp '+armor+' 8 70 8');rows=client_command(client,'wg restore HEAD')
        check('singleplayer cross-dimension UUID preflight rejects',any('duplicate UUID' in t and 'minecraft:the_nether' in t for t in texts(rows)))
        client_command(client,'wg commit --dimension minecraft:the_nether -m transferred');client_command(client,'wg commit -m departed')
        client_progress(client,check,result);client_ui(client,check,result)
        client.finish();check('singleplayer save-and-quit commit has terminal result',any('shutdown.commit finished:' in l for l in client.process.lines))
        verification=subprocess.run([JAVA['1.21.11'],'-jar',str(work/'wgit.jar'),'-w',str(world),'--format=json','verify','HEAD','--all'],capture_output=True,text=True,timeout=900)
        (directory/'verify.json').write_text(verification.stdout)
        check('singleplayer final offline verify is complete and zero differences',verification.returncode==0 and complete_verification_batch(verification.stdout),output=verification.stdout[-600:])
    finally:
        if client:client.stop()
        if world and world.is_relative_to(ROOT/'.work/worlds/fabric-gametest'):shutil.rmtree(world,ignore_errors=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('version',choices=['1.21.11','26.2']);parser.add_argument('--stages',default='abc',choices=['a','b','c','ab','ac','bc','abc']);parser.add_argument('--mode',choices=['dedicated','single','all'],default='all')
    def terminate(signum,frame):raise RuntimeError(f'terminated by signal {signum}')
    signal.signal(signal.SIGTERM,terminate)
    raise SystemExit(0 if run(parser.parse_args()) else 1)
