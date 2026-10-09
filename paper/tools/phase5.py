#!/usr/bin/env python3
"""Phase 5 真 Paper／Folia＋bot，持 bench.lock；原始 chat/click/bossbar 封包留證，不放寬回歸。"""
import argparse,json,os,re,shutil,subprocess,time,traceback,zipfile,signal,math,hashlib
from pathlib import Path
import harness
from cli_compat import repository
ROOT=Path(harness.ROOT)
def terminate(signum,frame):raise KeyboardInterrupt('Phase5 runner terminated')
signal.signal(signal.SIGTERM,terminate)

def fixture(destination,conflict=False):
    api=next((ROOT/'.work/gradle-home/caches/modules-2/files-2.1/io.papermc.paper/paper-api/1.21.11-R0.1-SNAPSHOT').rglob('*.jar'))
    jars=[api]
    for group in ['com.mojang/brigadier','net.kyori/adventure-api','net.kyori/adventure-key','org.jetbrains/annotations','net.md-5/bungeecord-chat','net.kyori/examination-api','net.kyori/examination-string']:
        jars+=list((ROOT/'.work/gradle-home/caches/modules-2/files-2.1'/group).rglob('*.jar'))
    classes=destination/'fixture-classes';classes.mkdir()
    subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/javac','-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),str(ROOT/'paper/tools/phase5-fixture/Phase5Fixture.java')],check=True)
    with zipfile.ZipFile(destination/'plugins/phase5-fixture.jar','w') as jar:
        for path in classes.rglob('*.class'):jar.write(path,str(path.relative_to(classes)))
        jar.writestr('plugin.yml','name: Phase5Fixture\nversion: 1\nmain: org.worldgit.fixture.Phase5Fixture\napi-version: "1.21"\nfolia-supported: true\nloadbefore: [WorldGit]\ncommands:\n  wgfixture:\n    description: Phase 5 test fixture\n')
        jar.writestr('config.yml','git: '+str(conflict).lower()+'\n')
    shutil.rmtree(classes)

def run(platform,version,conflict=False,disabled=False):
    out=ROOT/'.work/paper-phase5'/f'{platform}-{version}-{int(time.time())}';out.mkdir(parents=True)
    result={'platform':platform,'version':version,'conflict':conflict,'disabled':disabled,'success':False,'checks':[]}
    server=None
    old_token=os.environ.get('WGIT_TOKEN');os.environ['WGIT_TOKEN']='phase5-arbitrary-secret'
    def check(name,value,**data):
        result['checks'].append({'name':name,'ok':bool(value),**data});print(('PASS ' if value else 'FAIL ')+name,flush=True)
        if not value:raise AssertionError(name+' '+str(data)[:1000])
    try:
      with harness.BenchLock():
        try:
          harness.RUN=str(ROOT/'.work/paper-phase5/run')
          server=harness.Server(platform,version,baseline=str(ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+version)),run_label=out.name,view=2,xmx='1500M',all_entities=False,
              config={'language':'en_us','aliases':{'git':not disabled,'wgit':not disabled},'auto-commit':{'enabled':False,'on-shutdown':False},'commit':{'chunks-per-tick':1}})
          result['plugin_sha256']=hashlib.sha256((Path(server.dir)/'plugins/worldgit-paper.jar').read_bytes()).hexdigest()
          fixture(Path(server.dir),conflict)
          credentials=Path(server.dir)/'plugins/WorldGit/credentials.yml';credentials.write_text('credentials:\n  https://fixture.invalid:\n    mode: bearer\n    token: yaml-arbitrary-secret\n');credentials.chmod(0o600)
          server.start();bot=server.bot('WgBot');time.sleep(3)
          def chats(mark):
            with bot.lock:return [event for event in bot.lines[mark:] if event.get('ev')=='chat']
          def command(text,timeout=180):
            mark=len(bot.lines);bot.ask('chat /'+text,'chat_sent');deadline=time.monotonic()+timeout
            while time.monotonic()<deadline:
              rows=chats(mark)
              if any('finished:' in row['t'] and ('--all' not in text.split() or ' all ' in row['t']) for row in rows):
                if any('CLI' in row['t'] or 'UUID 集合' in row['t'] for row in rows):raise AssertionError('遊戲內不應顯示離線 CLI 實體提示：'+str(rows))
                return rows
              time.sleep(.1)
            raise TimeoutError(text+' '+str(chats(mark))[-1500:])
          def serialized(rows):return json.dumps(rows,ensure_ascii=False)
          def git(dim,*args):return subprocess.check_output(['git','--git-dir',str(repository(server.world,dim,version,True)),*args],text=True).strip()
          def initialized(dim):return (repository(server.world,dim,version,True)/'HEAD').exists()
          def clicks(value):
            if isinstance(value,dict):
              if value.get('action')=='run_command':yield value.get('value',value.get('command',''))
              for child in value.values():yield from clicks(child)
            elif isinstance(value,list):
              for child in value:yield from clicks(child)
          def entities(dim='minecraft:overworld'):
            return subprocess.check_output(['/usr/lib/jvm/java-21-openjdk-amd64/bin/java','-cp',str(ROOT/'cli/build/libs/wgit.jar'),str(ROOT/'paper/tools/phase5-fixture/Phase5RepoProbe.java'),str(repository(server.world,dim,version,True))],text=True).splitlines()
          server.cmd('wg debug guard on','WGCHUNKGUARD locked')
          for alias in ['wgit','git','worldgit:git','worldgit']:
            if alias=='git' and conflict:
              mark=len(bot.lines);bot.ask('chat /git','chat_sent');time.sleep(1);check('existing git preserved',any('OTHER_GIT' in r['t'] for r in chats(mark)));continue
            if disabled and alias in ['wgit','git']:
              mark=len(bot.lines);bot.ask('chat /'+alias+' help','chat_sent');time.sleep(1);check(alias+' disabled',not any('WorldGit -' in r['t'] for r in chats(mark)));continue
            rows=command(alias+' help');check(alias+' alias',any('WorldGit -' in row['t'] for row in rows))
          if not (conflict or disabled):
            server.cmd('wg debug comment-teleport WgBot minecraft:the_nether','WGCOMMENTTP success=true');time.sleep(3)
            rows=command('wg init');check('nether init only',initialized('minecraft:the_nether') and not initialized('minecraft:overworld') and not initialized('minecraft:the_end'),chat=rows)
            server.cmd('wg debug comment-teleport WgBot minecraft:overworld','WGCOMMENTTP success=true');time.sleep(3)
          # baseline 的 level.dat 出生點高於合成平坦地形；固定玩家高度，避免
          # 牛生成後玩家仍在下落、實際距離已超過原版互動範圍。
          server.cmd('wgfixture WgBot pin','PINNED=true');time.sleep(1)
          server.cmd('wgfixture WgBot',r'NATURAL_UUID=');line=next(l for l in server.lines_since(0)[::-1] if 'NATURAL_UUID=' in l);before_init=re.search(r'NATURAL_UUID=([a-f0-9-]+)',line)[1]
          def name_entity(uid,label):
            server.cmd('wgfixture WgBot give-name '+label,'HELD=NAME_TAG');time.sleep(.5)
            # 真實玩家互動期間恢復 ticks；命名仍必須由命名牌封包完成。
            server.cmd('wg debug guard off','WGCHUNKGUARD restored')
            try:
              bot.ask('interact_uuid '+uid,'entity_interacted');time.sleep(1)
            finally:server.cmd('wg debug guard on','WGCHUNKGUARD locked')
            observed=server.cmd('wgfixture WgBot inspect-name '+uid,'NAMED=')
            check('real name tag '+label,'NAME='+label in observed,named=observed)
          if platform=='paper':
            server.cmd('data merge entity '+before_init+' {CustomName:\'{"text":"Before init"}\'}',r'Modified entity|modified');time.sleep(2)
          else:name_entity(before_init,'Before_init')
          rows=command('wg init');raw=serialized(rows);check('overworld init append buttons','run_command' in raw and 'Init End too' in raw and '[All]' in raw,chat=rows)
          check('touch before init inherited',before_init in entities(),ids=entities())
          if conflict or disabled:
            append=next(c for c in clicks(rows) if '--dimension minecraft:the_nether' in c)
            check('main init leaves nether uninitialized',not initialized('minecraft:the_nether'))
            command(append.removeprefix('/'));check('nether append button executes',initialized('minecraft:the_nether') and not initialized('minecraft:the_end'))
          append=next(c for c in clicks(rows) if '--dimension minecraft:the_end' in c)
          rows=command(append.removeprefix('/'));check('append button initializes selected dimension',initialized('minecraft:the_end'),chat=rows)
          # 已有地獄 repo；移除空白 fixture repo 後另次 server run 驗地獄追加按鈕。
          check('creative player-touched default','player-touched' in (repository(server.world)/'worldgit-repo.yml').read_text())
          rows=command('wg log --graph');check('graph has lanes hover suggest','suggest_command' in serialized(rows) and '* ' in serialized(rows),chat=rows)
          overworld_head=git('minecraft:overworld','rev-parse','HEAD')
          rows=command('wg branch dimension-only --dimension minecraft:the_nether')
          check('branch target isolated',git('minecraft:the_nether','rev-parse','refs/heads/dimension-only') and 'refs/heads/dimension-only' not in git('minecraft:overworld','for-each-ref','--format=%(refname)') and git('minecraft:overworld','rev-parse','HEAD')==overworld_head,chat=rows)
          rows=command('wg remote list --all');check('all remote executes every dimension',sum('remote.list finished:' in row['t'] for row in rows)==4 and not any('busy' in row['t'].lower() for row in rows),chat=rows)
          server.cmd('wgfixture WgBot',r'NATURAL_UUID=');line=next(l for l in server.lines_since(0)[::-1] if 'NATURAL_UUID=' in l);uid=re.search(r'NATURAL_UUID=([a-f0-9-]+)',line)[1]
          rows=command('wg commit -m natural');check('natural cow excluded',uid not in entities())
          name_entity(uid,'Named')
          rows=command('wg commit -m named');check('named cow tracked',uid in entities(),ids=entities())
          rows=command('wg ignore add entity minecraft:cow');raw=serialized(rows);check('ignore preview before confirm','Preview:' in raw and 'run_command' in raw and 'Confirm change' in raw,chat=rows)
          code=re.search(r'Confirm change ([a-f0-9]{8})',raw)[1];rows=command('wg ignore confirm '+code);check('ignore confirmed',any('.wgignore updated' in r['t'] for r in rows))
          command('wg commit -m ignored');check('ignore committed excludes cow',uid not in entities())
          server.cmd('wgfixture WgBot',r'NATURAL_UUID=');line=next(l for l in server.lines_since(0)[::-1] if 'NATURAL_UUID=' in l);natural=re.search(r'NATURAL_UUID=([a-f0-9-]+)',line)[1]
          pos=bot.ask('pos','pos')['pos'];server.cmd('wgfixture WgBot give-armor','HELD=ARMOR_STAND');time.sleep(.5)
          bot.ask('place_entity '+str(math.floor(pos['x'])-2)+' 63 '+str(math.floor(pos['z'])),'entity_place_sent' if version=='26.2' else 'entity_placed');time.sleep(2)
          observed=server.cmd('wgfixture WgBot inspect','ENTITIES=');armor=re.search(r'ARMOR_STAND:([a-f0-9-]+)',observed)[1]
          command('wg commit -m armor');check('placed armor stand tracked',armor in entities())
          server.cmd('wgfixture WgBot remove '+armor,'REMOVED=');command('wg restore HEAD')
          observed=server.cmd('wgfixture WgBot inspect','ENTITIES=');check('armor restored natural cow preserved',armor in observed and natural in observed,entities=observed)
          transferred=server.cmd('wgfixture WgBot transfer '+armor+' minecraft:the_nether','TRANSFERRED=',60)
          check('tracked entity transfers to nether','TRANSFERRED=true' in transferred,transfer=transferred);time.sleep(2)
          rows=command('wg restore HEAD');check('duplicate UUID rejected before restore',any('FAILED' in row['t'] for row in rows) and 'minecraft:the_nether' in serialized(rows),chat=rows)
          rows=command('wg commit --dimension minecraft:the_nether -m transferred');check('destination inherits touched UUID',armor in entities('minecraft:the_nether'),chat=rows)
          command('wg commit -m departed');check('source drops departed touched UUID',armor not in entities())
          # 傳送與獨立提交後直接使用新的 HEAD；不偷偷刪另一維度的 UUID。
          rows=command('wg ignore');window=bot.ask('window','window');check('ignore GUI opens',window['opened'],window=window)
          bot.ask('click 49','clicked');time.sleep(.5);mark=len(bot.lines);bot.ask('chat entity minecraft:armor_stand','chat_sent');deadline=time.monotonic()+180
          while time.monotonic()<deadline and not any('finished:' in r['t'] for r in chats(mark)):time.sleep(.1)
          check('GUI add previews',any('Preview:' in r['t'] for r in chats(mark)),chat=chats(mark));command('wg clear')
          rows=command('wg switch missing-branch');raw=serialized(rows);check('error copy report','copy_to_clipboard' in raw and 'UTC=' in raw and 'Minecraft=' in raw and 'FAILED' in raw,chat=rows)
          rows=command('wg switch phase5-arbitrary-secret')
          def copies(value):
            if isinstance(value,dict):
              if value.get('action')=='copy_to_clipboard':yield json.dumps(value,ensure_ascii=False)
              for child in value.values():yield from copies(child)
            elif isinstance(value,list):
              for child in value:yield from copies(child)
          reports=list(copies(rows));check('copy report masks known arbitrary token',bool(reports) and all('phase5-arbitrary-secret' not in report for report in reports) and any('[REDACTED]' in report for report in reports),reports=reports)
          rows=command('wg switch yaml-arbitrary-secret');reports=list(copies(rows));check('copy masks YAML secret without remote request',bool(reports) and all('yaml-arbitrary-secret' not in report for report in reports) and any('[REDACTED]' in report for report in reports),reports=reports)
          mark=len(bot.lines);rows=command('wg status --full');time.sleep(4)
          with bot.lock:bars=[e for e in bot.lines[mark:] if e.get('ev')=='bossbar']
          check('bossbar progress terminal removal',any(e['packet']['action']==0 for e in bars) and any(e['packet']['action']==1 for e in bars) and 'finished:' in serialized(bars),bars=bars)
          for action in ['status','log --graph --all','branch phase5','branch','branch -d phase5','tag phase5','tag','tag -d phase5','stash list','remote list','fetch missing','pr list','comments','cancel','reload','clear','ignore list','ignore check','ignore test entity minecraft:cow 0,64,0']:
            rows=command('wg '+action);check('completion '+action,any('finished:' in row['t'] for row in rows),chat=rows)
          server.cmd('wg debug comment-teleport WgBot minecraft:the_nether','WGCOMMENTTP success=true');time.sleep(3)
          placed=harness.place_block(bot,2,'glass')
          server.cmd('wg debug comment-teleport WgBot minecraft:overworld','WGCOMMENTTP success=true');time.sleep(3)
          command('wg commit -m other-dimension')
          rows=command('wg commit --dimension minecraft:the_nether -m attributed')
          check('single dimension commit preserves other authors',git('minecraft:the_nether','log','-1','--format=%an')=='WgBot',placed=placed,chat=rows)
          result['success']=True
        finally:
          if server:
            server.stop();result['bot_events']=[b.lines for b in server.bots];result['server_problems']=server.problems();shutil.rmtree(server.dir,ignore_errors=True)
            result['plugin_problems']=[line for line in server.lines_since(0) if re.search(r'\[WorldGit\].*(?:ERROR|Exception)|(?:WARN|ERROR)\].*\[WorldGit\]|Thread failed.*check|Cannot tick|Exception in thread|Caused by:',line)]
            if result['plugin_problems']:result['success']=False
    except BaseException:result['error']=traceback.format_exc();print(result['error'],flush=True)
    if old_token is None:os.environ.pop('WGIT_TOKEN',None)
    else:os.environ['WGIT_TOKEN']=old_token
    (out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(out/'results.json',flush=True)
    return result['success']
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('platform',nargs='?',choices=['paper','folia']);parser.add_argument('version',nargs='?',choices=['1.21.11','26.2']);parser.add_argument('--matrix',action='store_true');parser.add_argument('--conflict',action='store_true');parser.add_argument('--disabled',action='store_true');args=parser.parse_args()
    if args.matrix:
      ok=True
      for platform,version,conflict,disabled in [('paper','1.21.11',False,False),('paper','26.2',False,False),('folia','1.21.11',False,False),('folia','26.2',False,False),('paper','1.21.11',True,False),('folia','26.2',False,True)]:
        if not run(platform,version,conflict,disabled):ok=False;break
      raise SystemExit(0 if ok else 1)
    if not args.platform or not args.version:parser.error('platform/version required unless --matrix')
    raise SystemExit(0 if run(args.platform,args.version,args.conflict,args.disabled) else 1)
