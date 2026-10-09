#!/usr/bin/env python3
"""Axiom 互通性真伺服器驗收；自取 bench.lock，依序執行並清除伺服器副本。"""
import argparse,base64,hashlib,json,os,re,shutil,signal,socket,subprocess,sys,time,traceback,uuid,zipfile
from pathlib import Path
import harness
from cli_compat import repository,cli_data
ROOT=Path(harness.ROOT)
sys.path.insert(0,str(ROOT/'fabric/tools'))
import dedicated_harness
from phase5 import fixture

def terminate(signum,frame):raise KeyboardInterrupt('Axiom runner terminated')
signal.signal(signal.SIGTERM,terminate)

def axiom_fixture(destination):
 api=next((ROOT/'.work/gradle-home/caches/modules-2/files-2.1/io.papermc.paper/paper-api/1.21.11-R0.1-SNAPSHOT').rglob('*.jar'))
 jars=[api]
 for group in ['net.kyori/adventure-api','net.kyori/adventure-key','org.jetbrains/annotations','net.md-5/bungeecord-chat','net.kyori/examination-api','net.kyori/examination-string']:
  jars+=list((ROOT/'.work/gradle-home/caches/modules-2/files-2.1'/group).rglob('*.jar'))
 classes=destination/'axiom-fixture-classes';classes.mkdir()
 subprocess.run([str(Path(harness.JAVA['1.21.11']).with_name('javac')),'-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),str(ROOT/'paper/tools/axiom-fixture/AxiomFixture.java')],check=True)
 with zipfile.ZipFile(destination/'plugins/axiom-fixture.jar','w') as jar:
  for path in classes.rglob('*.class'):jar.write(path,str(path.relative_to(classes)))
  jar.writestr('plugin.yml','name: AxiomFixture\nversion: 1\nmain: org.worldgit.fixture.AxiomFixture\napi-version: "1.21"\nsoftdepend: [AxiomPaper, WorldGit]\ncommands:\n  axiomfixture:\n    description: Axiom barrier fixture\n')
 shutil.rmtree(classes)

def run(platform,version,screenshots=False):
 work=ROOT/'.work/axiom'/f'{platform}-{version}-{int(time.time())}';work.mkdir()
 result={'platform':platform,'version':version,'success':False,'steps':[]};server=None
 def save(): (work/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 def check(name,ok,**evidence):
  result['steps'].append({'name':name,'ok':bool(ok),**evidence});save();print(('PASS ' if ok else 'FAIL ')+name,flush=True)
  if not ok:raise AssertionError(name+' '+str(evidence)[:1000])
 try:
  with harness.BenchLock():
   try:
    harness.BOT_JS=str(ROOT/'paper/tools/axiom-bot.js')
    baseline=ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+version)
    if platform=='paper':
     harness.RUN=str(work/'run');server=harness.Server('paper',version,baseline=str(baseline),view=3,all_entities=False,config={'language':'en_us','auto-commit':{'enabled':False,'on-shutdown':False},'commit':{'chunks-per-tick':1}})
     fixture(Path(server.dir));axiom_fixture(Path(server.dir));shutil.copy2(ROOT/f'.work/axiom/AxiomPaperPlugin-6.0.1-for-MC{version}.jar',Path(server.dir)/'plugins/axiom.jar')
    else:
     project='mc1_21_11' if version=='1.21.11' else 'mc26_2'
     fixturetask='remapDedicatedFixtureJar' if version=='1.21.11' else 'dedicatedFixtureJar'
     with (work/'build.log').open('w') as output:
      subprocess.run(['./gradlew','--no-daemon','--configure-on-demand','--max-workers=1',f':fabric:{project}:assemble',f':fabric:{project}:{fixturetask}'],cwd=ROOT,env=dict(os.environ,GRADLE_USER_HOME=str(ROOT/'.work/gradle-home')),stdout=output,stderr=subprocess.STDOUT,check=True)
     lib=ROOT/f'fabric/{project}/build/libs'
     server=dedicated_harness.Server(version,work,baseline,lib/f'worldgit-fabric-{version}-0.1.0-SNAPSHOT.jar',lib/f'worldgit-fabric-{version}-0.1.0-SNAPSHOT-dedicated-fixture{"-remapped" if version=="1.21.11" else ""}.jar',config='locale: en_us\npermission-level: 2\nauto-commit:\n  on-stop: false\n  on-logout: false\n  interval-minutes: 0\n',ops=['WgBot','WgBot2'])
     shutil.copy2(ROOT/f'.work/axiom/Axiom-6.1.3-for-MC{version}.jar',Path(server.dir)/'mods/axiom.jar')
    server.start();check('server with Axiom starts',True,log=server.evidence_log)
    result['server_pid']=server.proc.pid
    if platform=='paper':check('optional hook installed',any('已掛接 AxiomPaper' in l for l in server.lines_since(0)))
    bot=server.bot('WgBot');enabled=bot.wait_ev('axiom_enable',60);check('Axiom API 10 handshake',enabled['enabled'],packet=enabled)
    second=server.bot('WgBot2');check('second player handshake',second.wait_ev('axiom_enable',60)['enabled'])
    def rows(mark):
     with bot.lock:return [e for e in bot.lines[mark:] if e.get('ev')=='chat']
    def finish(mark,timeout=900):
     until=time.monotonic()+timeout
     while time.monotonic()<until:
      events=rows(mark)
      (work/'pending-chat.json').write_text(json.dumps(events,ensure_ascii=False,indent=2))
      if any('Unknown or incomplete command' in e['t'] or 'Incorrect argument' in e['t'] or 'Unknown flag' in e['t'] for e in events):raise AssertionError('Invalid fixture command: '+str(events))
      if any('finished:' in e['t'] for e in events):
       check('operation succeeds',not any('FAILED' in e['t'] or 'PARTIAL' in e['t'] for e in events),chat=events)
       return events
      time.sleep(.1)
     raise TimeoutError(str(events))
    def command(text):
     mark=len(bot.lines);bot.ask('chat /wg '+text,'chat_sent');return finish(mark)
    def git(*args):return subprocess.check_output(['git','--git-dir',str(repository(server.world,version=version,paper=platform=='paper')),*args],text=True).strip()
    def entities():return subprocess.check_output([harness.JAVA[version],'-cp',str(ROOT/'cli/build/libs/wgit.jar'),str(ROOT/'paper/tools/phase5-fixture/Phase5RepoProbe.java'),str(repository(server.world,version=version,paper=platform=='paper'))],text=True).splitlines()
    def axiom(bot,text):return bot.ask('axiom '+text,'axiom_sent')
    def contribution():
     msg=git('log','-1','--format=%B');data=[]
     for token in re.findall('WorldGit-Contribution: (\\S+)',msg):data.append(base64.urlsafe_b64decode(token+'='*((-len(token))%4)).decode('latin1'))
     return msg,data
    server.cmd('wg debug guard on','WGCHUNKGUARD locked')
    if platform=='paper':server.cmd('wgfixture WgBot pin','PINNED=true')
    else:server.cmd('tp WgBot 8.5 65 8.5','Teleported')
    time.sleep(2);command('init');check('creative uses player-touched','player-touched' in (repository(server.world)/'worldgit-repo.yml').read_text())
    axiom(bot,'block 2 80 2 diamond_block');time.sleep(2)
    check('direct section set_block reaches world',bot.ask('block 2 80 2','block')['name']=='diamond_block')
    status=command('status' if platform=='paper' else 'status --blocks');check('Axiom block in status',(any(re.search(r'\bsections 1\b',e['t']) for e in status) and any('WgBot(1)' in e['t'] for e in status)) if platform=='paper' else (any(re.search(r'\b1 chunks, 1 sections\b',e['t']) for e in status) and any('c.0.0 s.5' in e['t'] for e in status)),chat=status)
    axiom(second,'block 18 80 2 emerald_block');time.sleep(2);command('commit -m axiom-two-players')
    msg,data=contribution();check('two players have distinct axiom contributions',len([s for s in data if 'axiom' in s])==2 and any('WgBot2' in s and '1,0' in s for s in data) and any('WgBot' in s and '0,0' in s for s in data),commit=msg,contributions=data)
    axiom(bot,'buffer 0 6 0 gold_block');time.sleep(4)
    check('set_buffer palette section applied',bot.ask('block 3 98 3','block')['name']=='gold_block')
    command('commit -m axiom-buffer');msg,data=contribution();check('buffer has axiom attribution',any('axiom' in s and 'WgBot' in s for s in data),commit=msg)
    uid=str(uuid.uuid4());axiom(bot,f'spawn 4 65 4 {uid}');time.sleep(2);command('commit -m axiom-entity')
    check('Axiom spawned UUID stored in entity blob',uid in entities(),ids=entities())
    axiom(bot,f'manipulate {uid} 6 65 6');time.sleep(2);command('commit -m axiom-manipulated')
    check('manipulated entity remains tracked',uid in entities())
    # Manipulating a previously untracked natural entity must grant qualification.
    if platform=='paper':
     observed=server.cmd('wgfixture WgBot',r'NATURAL_UUID=');natural=re.search('NATURAL_UUID=([a-f0-9-]+)',observed)[1]
    else:
     observed=server.cmd('wg test natural-cow 10 65 10','WGCOW');natural=re.search('uuid=([a-f0-9-]+)',observed)[1]
    command('commit -m natural-baseline');check('natural entity excluded',natural not in entities())
    axiom(second,f'read {natural}');second.wait_ev('axiom_entity_data',60)
    axiom(bot,'block 34 80 2 stone');time.sleep(1);command('commit -m readonly-is-not-author')
    msg,data=contribution();check('read-only Axiom request creates no attribution',any('WgBot' in s and '2,0' in s for s in data) and all('WgBot2' not in s for s in data),commit=msg)
    check('read-only request does not grant entity qualification',natural not in entities())
    axiom(bot,f'manipulate {natural} 11 65 11');time.sleep(2);command('commit -m axiom-natural-touch');check('Axiom manipulation grants player-touched',natural in entities())
    if platform=='fabric':
     axiom(bot,'block 5 80 5 chest');time.sleep(.5)
     check('Axiom creates block entity','WGBE present=true' in server.cmd('wg test be-check 5 80 5','WGBE'))
     server.cmd('wg test be-remove 5 80 5','WGBE present=false')
     axiom(bot,'fix 5 80 5');time.sleep(.5)
     check('fix_area payload positive control','WGBE present=true' in server.cmd('wg test be-check 5 80 5','WGBE'))
     server.cmd('wg test be-remove 5 80 5','WGBE present=false')
     command('commit -m fix-area-fixture')
    axiom(bot,'block 2 80 3 oak_fence');time.sleep(1);command('commit -m stale-fence')
    fence=bot.ask('block 2 80 3','block');check('force-tick fixture starts disconnected',fence['properties']['north'] is False,block=fence)
    # 26.2 queries the default clock's day timeline instead of the removed daytime literal.
    time_command='time query minecraft:day' if version=='26.2' else 'time query daytime'
    time_pattern=r'Timeline minecraft:day is at (\d+) tick\(s\)' if version=='26.2' else r'The time is (\d+)'
    before_time=server.cmd(time_command,time_pattern);before_weather=server.cmd('gamerule advance_weather','advance_weather')
    weather_value=re.search(r'advance_weather.*?(true|false)',before_weather)[1]
    clock_value=int(re.search(time_pattern,before_time)[1])
    requested_time=12000 if clock_value!=12000 else 6000
    axiom(bot,f'time {requested_time}');time.sleep(.5)
    clock=server.cmd(time_command,time_pattern)
    check('time payload positive control',int(re.search(time_pattern,clock)[1])==requested_time,console=clock)
    axiom(bot,f'time {clock_value}');time.sleep(.5)
    axiom(bot,f'property {1 if weather_value=="true" else 0}');time.sleep(.5)
    weather=server.cmd('gamerule advance_weather','advance_weather')
    check('world property payload positive control',re.search(r'advance_weather.*?(true|false)',weather)[1]!=weather_value,console=weather)
    axiom(bot,f'property {0 if weather_value=="true" else 1}');time.sleep(.5)
    axiom(bot,'tick 2 80 3');time.sleep(.5)
    check('dangerous tick payload positive control',bot.ask('block 2 80 3','block')['properties']['north'] is True)
    axiom(bot,'block 2 80 3 oak_fence');time.sleep(.5)
    check('force-tick fixture restored',bot.ask('block 2 80 3','block')['stateId']==fence['stateId'])
    # Bukkit setTime can move the absolute 26.2 clock into the next day when restoring
    # its daytime. Capture the positive controls before branching: online world-meta
    # restore is intentionally refused, and this test must reach an actual block apply.
    if version=='26.2':command('commit -m axiom-positive-controls')
    command('branch axiom-base');base=git('rev-parse','HEAD')
    if platform=='paper':server.cmd('wg debug fill 12 lapis_block 144',r'debug fill 完成',900)
    else:
     fill=server.cmd('wg test fill 12 lapis_block 144 8','WGFILL|WGTESTFAIL',900)
     check('apply target fixture fills 144 chunks','WGFILL done count=144' in fill and 'WGTESTFAIL' not in fill,console=fill)
    command('commit -m apply-target');command('branch axiom-large')
    if platform=='paper':
     server.cmd('axiomfixture bypass true','AXIOM_BYPASS=true')
     queued=server.cmd('axiomfixture seed','AXIOM_QUEUED=1|AXIOM_FIXTURE_ERROR')
     check('seed actual pending buffer','AXIOM_QUEUED=1' in queued and 'AXIOM_FIXTURE_ERROR' not in queued,console=queued)
     time.sleep(.5);check('pending buffer waits across ticks', 'AXIOM_QUEUED=1' in server.cmd('axiomfixture inspect','AXIOM_QUEUED='))
    axiom(second,'locale zh_tw');time.sleep(.2)
    mark=len(bot.lines);bot.ask('chat /wg switch axiom-base --force','chat_sent')
    journal=repository(server.world)/'apply-state.yml';until=time.monotonic()+180
    while time.monotonic()<until:
     if journal.exists() and re.search('state: (LOCKING|APPLYING)',journal.read_text()):break
     if any('switch finished: FAILED' in e['t'] for e in rows(mark)):raise AssertionError('switch failed before edit lock: '+str(rows(mark)[-4:]))
     time.sleep(.01)
    else:raise TimeoutError('actual apply lock not observed')
    # Multiple mutations while the real switch holds its edit lock.
    denied_uid=str(uuid.uuid4())
    denied_block=axiom(bot,'block 2 80 2 redstone_block')
    for edit in ['buffer 0 6 0 redstone_block',f'spawn 5 65 5 {denied_uid}',f'manipulate {uid} 12 65 12',f'delete {uid}',f'time {requested_time}','tick 2 80 3']:axiom(bot,edit)
    denied_property=axiom(bot,f'property {1 if weather_value=="true" else 0}')
    if platform=='fabric':axiom(bot,'fix 5 80 5')
    axiom(second,'block 18 80 2 redstone_block')
    finish(mark);time.sleep(2)
    if platform=='paper':
     check('apply cancels old buffer despite region bypass','AXIOM_QUEUED=0' in server.cmd('axiomfixture inspect','AXIOM_QUEUED='))
     server.cmd('axiomfixture bypass false','AXIOM_BYPASS=false')
    rejected=[e for e in rows(mark) if 'Axiom edit rejected' in e['t']]
    check('real apply rejects Axiom with clear message',bool(rejected),chat=rows(mark))
    check('zh_tw rejection follows player locale',any('Axiom 編輯已拒絕' in e['t'] for e in second.events('chat')),chat=second.events('chat'))
    check('rejected world property acknowledged',any(e['hex']==f'{denied_property["updateId"]:02x}' for e in bot.events('axiom_property_ack')),acks=bot.events('axiom_property_ack'))
    check('rejected block prediction acknowledged',any(e['packet']['sequenceId']==denied_block['sequenceId'] for e in bot.events('axiom_block_ack')),sequence=denied_block['sequenceId'],acks=bot.events('axiom_block_ack'))
    check('switch reaches target HEAD',git('rev-parse','HEAD')==base)
    after_time=server.cmd(time_command,time_pattern);after_weather=server.cmd('gamerule advance_weather','advance_weather')
    check('time request rejected',re.findall(time_pattern,before_time)==re.findall(time_pattern,after_time) and bool(re.findall(time_pattern,before_time)),before=before_time,after=after_time)
    check('world property request rejected',re.findall('advance_weather.*(?:true|false)',before_weather)==re.findall('advance_weather.*(?:true|false)',after_weather) and bool(re.findall('advance_weather.*(?:true|false)',before_weather)),before=before_weather,after=after_weather)
    check('dangerous tick rejected',bot.ask('block 2 80 3','block')['stateId']==fence['stateId'],expected=fence)
    if platform=='fabric':check('fix_area block entity mutation rejected','WGBE present=false' in server.cmd('wg test be-check 5 80 5','WGBE'))
    check('rejected set_block and buffer leave expected world',bot.ask('block 2 80 2','block')['name']=='diamond_block' and bot.ask('block 3 98 3','block')['name']=='gold_block')
    if platform=='paper':live=server.cmd('wgfixture WgBot inspect','ENTITIES=')
    else:live=server.cmd('wg test entities','WGENTITIES')
    check('spawn rejected and tracked entity not deleted',denied_uid not in live and uid in live,entities=live)
    verify=command('verify');check('post-apply verify COMPLETE and zero changes',any('world matches' in e['t'] or 'chunks=0' in e['t'] or 'chunks 0' in e['t'] for e in verify) and not any('FAILED' in e['t'] for e in verify),chat=verify)
    if platform=='paper':server.cmd('wg debug release','已釋放')
    else:server.cmd('wg test release','WGRELEASE')
    if screenshots:
     from phase5_support import Client
     variables=('WG_TEST_AXIOM_JAR','WG_TEST_AXIOM_DEPS');previous={key:os.environ.get(key) for key in variables}
     artifact=ROOT/f'.work/axiom/Axiom-6.1.3-for-MC{version}.jar'
     dependencies=ROOT/f'.work/axiom/client-deps/{version}';dependencies.mkdir(parents=True,exist_ok=True)
     # Loom local file dependencies do not put nested jars on the dev classpath.
     with zipfile.ZipFile(artifact) as archive:
      for entry in json.loads(archive.read('fabric.mod.json')).get('jars',[]):
       name=entry['file'];(dependencies/Path(name).name).write_bytes(archive.read(name))
     os.environ['WG_TEST_AXIOM_JAR']=str(artifact);os.environ['WG_TEST_AXIOM_DEPS']=str(dependencies)
     client=None
     try:
      client=Client(work,version,False,server.port)
      server.cmd('op '+client.ready['player'],'operator|Operator|already')
      preview_mark=client.chat_mark();client.command('wg status --show')
      preview=client.wait_chat(preview_mark,'status finished:',180)
      check('real client WorldGit preview handshake',client.ready['uiCapable'] and any('status finished: SUCCESS' in row['text'] for row in preview) and not any('FAILED' in row['text'] for row in preview),chat=preview)
      server.cmd('tp '+client.ready['player']+' 24 104 24 135 25','Teleported')
      client.action('close');result['axiom_editor']=client.action('axiom-editor');time.sleep(2)
      target=client.shot('axiom-interop');destination=ROOT/f'fabric/docs/screenshots/axiom/{platform}-{version}.png';destination.parent.mkdir(parents=True,exist_ok=True);shutil.move(target,destination)
      check('real Axiom 6.1.3 client framebuffer',destination.is_file(),screenshot=str(destination.relative_to(ROOT)),sha256=hashlib.sha256(destination.read_bytes()).hexdigest())
      client.finish()
     finally:
      if client:client.stop()
      for key,value in previous.items():
       if value is None:os.environ.pop(key,None)
       else:os.environ[key]=value
    result['bot_events']=bot.lines;result['second_events']=second.lines
    server.stop();check('server exits cleanly',server.proc.poll()==0)
    mod_args=['--mod-pack',f'axiom={ROOT}/.work/axiom/Axiom-6.1.3-for-MC{version}.jar'] if platform=='fabric' else []
    output=harness.wgit(['--world',server.world,'--format=json',*mod_args,'verify','HEAD','--dimension','minecraft:overworld']);data=cli_data(output)
    counts=('chunks','sections','biomeSections','entityPuts','entityRemoves','chunkDeletes','metaFiles','untrackedKept')
    check('offline verify COMPLETE zero differences',data['state']=='COMPLETE' and set(data['dimensions'])=={'minecraft:overworld'} and all(data['dimensions']['minecraft:overworld'].get(k)==0 for k in counts),verify=json.loads(output))
    bad=[l for l in server.lines_since(0) if ('ERROR' in l or 'Exception' in l or 'acknowledgement failed' in l) and ('worldgit' in l.lower() or 'mixin' in l.lower() or 'axiom' in l.lower())];check('zero WorldGit/Axiom/mixin errors',not bad,problems=bad)
    result['success']=True
   finally:
    if server:
     result['bot_events']={b.name:b.lines for b in server.bots}
     server.stop()
     if hasattr(server,'evidence_log'):shutil.copy2(server.evidence_log,work/'console.log')
     shutil.rmtree(server.dir,ignore_errors=True)
     with socket.socket() as probe:result['port_closed']=probe.connect_ex(('127.0.0.1',server.port))!=0
     result['process_exit_codes']={'server':server.proc.poll() if server.proc else None,**{b.name:b.p.wait(10) for b in server.bots}}
     result['success']=result['success'] and result['port_closed'] and all(code==0 for code in result['process_exit_codes'].values())
 except BaseException as error:result['error']=repr(error);result['trace']=traceback.format_exc();print(result['trace'],flush=True)
 finally:save()
 print(work/'result.json',flush=True);return result['success']
if __name__=='__main__':
 p=argparse.ArgumentParser();p.add_argument('platform',choices=['paper','fabric']);p.add_argument('version',choices=['1.21.11','26.2']);p.add_argument('--screenshots',action='store_true');a=p.parse_args();sys.exit(0 if run(a.platform,a.version,a.screenshots) else 1)
