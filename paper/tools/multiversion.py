#!/usr/bin/env python3
"""真 Paper 相鄰版本升降級、item components 與 loss-token 驗收；自持 bench.lock。"""
import argparse,hashlib,json,re,shutil,subprocess,time,traceback,signal
from pathlib import Path
import harness
from cli_compat import repository
ROOT=Path(harness.ROOT)
TARGET='--world world --dimension minecraft:overworld'
def terminate(signum,frame):raise KeyboardInterrupt('multiversion runner terminated')
signal.signal(signal.SIGTERM,terminate)

def run(older,newer):
    OUT=ROOT/'.work/mv2-2/online'/f'{older}-to-{newer}'
    OUT.mkdir(parents=True,exist_ok=True)
    evidence={'older':older,'newer':newer,'success':False,'checks':[],'console_logs':[]}
    server=None
    def save(): (OUT/'results.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2)+'\n')
    def check(name,ok,**data):
        evidence['checks'].append({'name':name,'ok':bool(ok),**data});save()
        print(('PASS ' if ok else 'FAIL ')+name,flush=True)
        if not ok:raise AssertionError(name+' '+str(data)[-1800:])
    def git(s,*args):return subprocess.check_output(['git','--git-dir',str(repository(s.world)),*args],text=True).strip()
    def command(s,text,success=True):
        mark=s.mark();s.send(text);s.wait(r'finished: (SUCCESS|NO_OP|FAILED|PARTIAL|CANCELLED)',300,mark)
        time.sleep(.4);rows='\n'.join(s.lines_since(mark));print(text+'\n'+rows[-2200:],flush=True)
        if success:check(text,'finished: SUCCESS' in rows or 'finished: NO_OP' in rows,console=rows)
        return rows
    def player_command(bot,text,success=True):
        mark=len(bot.lines);bot.ask('chat /'+text,'chat_sent');deadline=time.monotonic()+300
        while time.monotonic()<deadline:
            rows=[e for e in bot.lines[mark:] if e.get('ev')=='chat']
            if any(re.search(r'(?:finished:|完成：)\s*(?:SUCCESS|NO_OP|FAILED|PARTIAL|CANCELLED)',e['t']) for e in rows):break
            time.sleep(.1)
        else:raise TimeoutError(text+' '+str(rows)[-2000:])
        time.sleep(.4);rows=[e for e in bot.lines[mark:] if e.get('ev')=='chat'];textual='\n'.join(e['t'] for e in rows)
        if success:check(text,any('finished: SUCCESS' in e['t'] or 'finished: NO_OP' in e['t'] for e in rows),chat=rows)
        return rows,textual
    def clone_worlds(s,destination):
        shutil.rmtree(destination,ignore_errors=True);destination.mkdir()
        for name in ('world','world_nether','world_the_end'):
            source=Path(s.dir)/name
            if source.exists():shutil.copytree(source,destination/name)
    def start(version,baseline,label):
        s=harness.Server('paper',version,baseline=str(baseline),run_label=label,view=2,
             config={'language':'en_us','auto-commit':{'enabled':False,'on-shutdown':False}})
        try:
            s.start();evidence['console_logs'].append(str(Path(s.evidence_log).relative_to(ROOT)))
            evidence['plugin_sha256']=hashlib.sha256((Path(s.dir)/'plugins/worldgit-paper.jar').read_bytes()).hexdigest()
            s.cmd('forceload add 0 0',r'(?i)(forced|force loading)');
            loaded=s.cmd('forceload query 0 0',r'(?i)marked for force loading')
            check('representative chunk is force loaded','not marked' not in loaded.lower(),console=loaded);return s
        except BaseException:
            s.stop();raise
    def token(rows):
        def clicks(value):
            if isinstance(value,dict):
                if value.get('action')=='run_command':yield value.get('value',value.get('command',''))
                for child in value.values():yield from clicks(child)
            elif isinstance(value,list):
                for child in value:yield from clicks(child)
        matches=[re.fullmatch(r'/wg confirm ([a-f0-9]{32})',value) for value in clicks(rows)]
        found=[match[1] for match in matches if match]
        if not found:raise AssertionError('No clickable loss token: '+json.dumps(rows,ensure_ascii=False)[-2000:])
        return found[0]
    try:
        with harness.BenchLock():
            base=OUT/'prepared-base';shutil.rmtree(base,ignore_errors=True)
            shutil.copytree(ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+older),base)
            subprocess.run([harness.JAVA[older],'-cp',str(ROOT/'cli/build/libs/wgit.jar'),str(ROOT/'paper/tools/MultiversionFixture.java'),str(base/'world/level.dat')],check=True)
            server=start(older,base,'mv2-online-source-'+older)
            server.cmd('setblock 4 64 4 gold_block',r'(?i)changed')
            server.cmd('setblock 5 64 4 chest',r'(?i)changed')
            sword=('{Slot:0b,id:"minecraft:diamond_sword",Count:1b,tag:{display:{Name:\'{"text":"WorldGit sword"}\',Lore:[\'{"text":"WorldGit lore"}\']},Enchantments:[{id:"minecraft:sharpness",lvl:3s}],opaque:{Count:7,components:{untouched:1b}}}}'
                   if older in ('1.20.1','1.20.4') else
                   '{Slot:0b,id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_name":\'{"text":"WorldGit sword"}\',"minecraft:lore":[\'{"text":"WorldGit lore"}\'],"minecraft:enchantments":{levels:{"minecraft:sharpness":3}},"minecraft:custom_data":{opaque:{Count:7,components:{untouched:1b}}}}}')
            server.cmd('data merge block 5 64 4 {Items:['+sword+']}',r'(?i)modified')
            server.cmd('summon armor_stand 8 70 8 {Tags:["mv2"],NoGravity:1b,Invisible:1b}',r'(?i)summoned')
            command(server,'wg init '+TARGET)
            legacy=git(server,'rev-parse','HEAD');evidence['legacy_commit']=legacy
            command(server,'wg branch legacy '+TARGET)
            server.stop();oldbase=OUT/'old-base';clone_worlds(server,oldbase);shutil.rmtree(server.dir);server=None
            server=start(newer,oldbase,'mv2-online-source-'+newer)
            if older=='1.20.4' and newer=='1.20.6':
                # Paper 把舊 keep-spawn-loaded=false 遷移為新 gamerule=0；這與原版缺省 2 不同。
                # 先驗證設定差異仍被擋下，再明確建立設定相容的 components 驗收世界。
                rows=command(server,'wg switch legacy '+TARGET,False)
                check('configured new gamerule still requires offline metadata restore',
                      'finished: FAILED' in rows and 'Online restore of world-meta is not supported' in rows
                      and git(server,'rev-parse','HEAD')==legacy,console=rows)
                server.cmd('gamerule spawnChunkRadius 2',r'(?i)game ?rule')
                radius=server.cmd('gamerule spawnChunkRadius',r'(?i)game ?rule')
                check('compatible fixture explicitly uses native spawnChunkRadius default','2' in radius,console=radius)
            server.cmd('setblock 4 64 4 diamond_block',r'(?i)changed')
            if newer=='1.20.4':
                attr=server.cmd('attribute @e[type=armor_stand,tag=mv2,limit=1] minecraft:generic.max_absorption base set 8',r'(?i)base value')
                check('1.20.4 real vanilla max_absorption attribute','8' in attr,console=attr)
                loss_source='minecraft:generic.max_absorption'
            else:
                component_sword='{Slot:0b,id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_name":\'{"text":"WorldGit sword"}\',"minecraft:lore":[\'{"text":"WorldGit lore"}\'],"minecraft:enchantments":{levels:{"minecraft:sharpness":3}},"minecraft:custom_data":{opaque:{Count:7,components:{untouched:1b}}}}}'
                special=('{Slot:1b,id:"minecraft:diamond",count:3,components:{"minecraft:food":{nutrition:3,saturation:0.3f,can_always_eat:1b,eat_seconds:1.6f}}}'
                         if newer=='1.20.6' else '{Slot:1b,id:"minecraft:mace",count:1}')
                loss_source='minecraft:food' if newer=='1.20.6' else 'minecraft:mace'
                server.cmd('data merge block 5 64 4 {Items:['+component_sword+','+special+']}',r'(?i)modified')
                actual=server.cmd('data get block 5 64 4 Items',r'(?i)block data')
                check('real source contains version-specific item data',loss_source in actual,console=actual)
            def check_sword(s,label):
                actual=s.cmd('data get block 5 64 4 Items',r'(?i)block data')
                plain=re.sub(r'\x1b\[[0-9;]*m','',actual)
                check(label,all(x in plain for x in ['minecraft:diamond_sword','WorldGit sword','WorldGit lore','minecraft:sharpness','opaque','Count: 7','untouched: 1b'])
                      and ('components:' in plain if s.version not in ('1.20.1','1.20.4') else 'Count: 1b' in plain and 'tag:' in plain),console=actual)
            command(server,'wg commit '+TARGET+' -m source-'+newer)
            modern=git(server,'rev-parse','HEAD');evidence['modern_commit']=modern
            command(server,'wg branch modern '+TARGET)
            rows=command(server,'wg switch legacy '+TARGET)
            check('server DFU automatically upgrades '+older+' commit on '+newer,git(server,'rev-parse','HEAD')==legacy and 'confirm' not in rows.lower(),console=rows)
            rows=command(server,'wg verify '+TARGET)
            check('upgraded commit verifies with zero changes',bool(re.search(r'Stats\[chunks=0, sections=0, biomeSections=0, entityPuts=0, entityRemoves=0, chunkDeletes=0, untrackedKept=0, metaFiles=0\]', rows)),console=rows)
            check_sword(server,'automatic upgrade preserves physical name lore enchantments and opaque custom data')
            source=OUT/'source.git';shutil.rmtree(source,ignore_errors=True);shutil.copytree(repository(server.world),source)
            server.stop();shutil.rmtree(server.dir);server=None
            imported=OUT/'target-base';shutil.rmtree(imported,ignore_errors=True);shutil.copytree(oldbase,imported)
            subprocess.run(['git','--git-dir',str(imported/'world/.worldgit'),'fetch',str(source),'refs/heads/modern:refs/heads/modern'],check=True)
            server=start(older,imported,'mv2-online-target-'+older)
            bot=server.bot('WgBot');other=server.bot('WgBot2');time.sleep(3)
            rows,text=player_command(bot,'wg switch modern '+TARGET+' --force',False);first=token(rows)
            check('downgrade lists real loss and leaves HEAD untouched',loss_source in text and 'FAILED' in text and git(server,'rev-parse','HEAD')==legacy,chat=rows)
            check('unconfirmed downgrade leaves physical block intact',bot.ask('block 4 64 4','block')['name']=='gold_block')
            bot.ask('locale zh_tw','locale');time.sleep(1)
            help_rows,help_text=player_command(bot,'wg help confirm',False)
            check('Traditional Chinese confirm help is localized','SUCCESS' in help_text and '確認有損轉換預覽' in help_text and '/wg confirm <token>' in help_text,chat=help_rows)
            zh_rows,zh_text=player_command(bot,'wg switch modern '+TARGET,False)
            check('Traditional Chinese complete loss preview and click token','將損失以下內容' in zh_text and loss_source in zh_text and bool(token(zh_rows)) and git(server,'rev-parse','HEAD')==legacy,chat=zh_rows)
            bot.ask('locale en_us','locale');time.sleep(1)
            mark=len(other.lines);other.ask('chat /wg confirm '+first,'chat_sent');time.sleep(2)
            check('another actor cannot consume the token',git(server,'rev-parse','HEAD')==legacy and any('invalid' in e.get('t','').lower() for e in other.lines[mark:]),chat=other.lines[mark:])
            server.cmd('setblock 6 64 4 emerald_block',r'(?i)changed')
            rows,text=player_command(bot,'wg confirm '+first,False)
            check('changed world invalidates prior approval','FAILED' in text and token(rows)!=first and git(server,'rev-parse','HEAD')==legacy
                  and bot.ask('block 4 64 4','block')['name']=='gold_block'
                  and bot.ask('block 6 64 4','block')['name']=='emerald_block',chat=rows)
            server.cmd('setblock 6 64 4 air',r'(?i)changed')
            rows,text=player_command(bot,'wg switch modern '+TARGET,False);current=token(rows)
            rows,text=player_command(bot,'wg confirm '+current)
            check('confirmed downgrade publishes original source HEAD',git(server,'rev-parse','HEAD')==modern,chat=rows)
            check('confirmed downgrade applies physical source block',bot.ask('block 4 64 4','block')['name']=='diamond_block')
            rows=command(server,'wg verify '+TARGET)
            check('downgraded commit verifies with zero changes',bool(re.search(r'Stats\[chunks=0, sections=0, biomeSections=0, entityPuts=0, entityRemoves=0, chunkDeletes=0, untrackedKept=0, metaFiles=0\]', rows)),console=rows)
            check_sword(server,'confirmed downgrade preserves physical name lore enchantments and opaque custom data')
            mark=len(bot.lines);bot.ask('chat /wg confirm '+current,'chat_sent');time.sleep(2)
            check('token is single use',any('invalid' in e.get('t','').lower() for e in bot.lines[mark:]),chat=bot.lines[mark:])
            # Reset and merge use the same pre-write gate; their previews cannot mutate the world.
            server.cmd('setblock 4 64 4 emerald_block',r'(?i)changed')
            rows,text=player_command(bot,'wg reset --hard '+TARGET,False);reset=token(rows)
            check('cross-version reset waits for confirmation',bot.ask('block 4 64 4','block')['name']=='emerald_block' and git(server,'rev-parse','HEAD')==modern,chat=rows)
            player_command(bot,'wg confirm '+reset)
            rows=command(server,'wg verify '+TARGET)
            check('confirmed reset verifies with zero changes',bool(re.search(r'Stats\[chunks=0, sections=0, biomeSections=0, entityPuts=0, entityRemoves=0, chunkDeletes=0, untrackedKept=0, metaFiles=0\]', rows)),console=rows)
            rows,text=player_command(bot,'wg switch legacy '+TARGET,False);back=token(rows)
            check('switching away from a newer HEAD also waits for loss confirmation',
                  git(server,'rev-parse','HEAD')==modern and bot.ask('block 4 64 4','block')['name']=='diamond_block',chat=rows)
            player_command(bot,'wg confirm '+back)
            check('confirmed return to legacy publishes HEAD and physical block',
                  git(server,'rev-parse','HEAD')==legacy and bot.ask('block 4 64 4','block')['name']=='gold_block')
            rows,text=player_command(bot,'wg merge modern '+TARGET,False);merge=token(rows)
            check('cross-version merge waits for confirmation',git(server,'rev-parse','HEAD')==legacy and bot.ask('block 4 64 4','block')['name']=='gold_block',chat=rows)
            player_command(bot,'wg confirm '+merge)
            rows=command(server,'wg verify '+TARGET)
            check('confirmed merge verifies with zero changes',bool(re.search(r'Stats\[chunks=0, sections=0, biomeSections=0, entityPuts=0, entityRemoves=0, chunkDeletes=0, untrackedKept=0, metaFiles=0\]', rows)),console=rows)
            evidence['success']=True
    except BaseException:
        evidence['error']=traceback.format_exc();print(evidence['error'],flush=True)
        if server and repository(server.world).is_dir():
            diagnostic=OUT/'failure-repo'
            shutil.rmtree(diagnostic,ignore_errors=True)
            shutil.copytree(repository(server.world),diagnostic)
            shutil.copy2(Path(server.world)/'level.dat',OUT/'failure-level.dat')
            evidence['failure_repository']=str(diagnostic.relative_to(ROOT))
    finally:
        if server:
            server.stop()
            if not evidence['success']:
                physical=OUT/'failure-world'
                shutil.rmtree(physical,ignore_errors=True);physical.mkdir()
                for name in ('level.dat','region/r.0.0.mca','entities/r.0.0.mca'):
                    source=Path(server.world)/name
                    if source.is_file():
                        target=physical/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
            shutil.rmtree(server.dir,ignore_errors=True)
        for name in ('prepared-base','old-base','target-base','source.git'):shutil.rmtree(OUT/name,ignore_errors=True)
        save()
    return evidence['success']

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    pairs=['1.20.1:1.20.4','1.20.4:1.20.6','1.20.6:1.21.1']
    parser.add_argument('--pair',action='append',choices=pairs)
    args=parser.parse_args()
    for pair in args.pair or pairs:
        if not run(*pair.split(':')):raise SystemExit(1)
