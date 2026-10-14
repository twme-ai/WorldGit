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

def run(older,newer,output_root,tokens=False,interactions=False):
    OUT=output_root/f'{older}-to-{newer}'
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
        mark=len(bot.lines)
        if text.startswith('wg confirm '):
            clicked=bot.ask('click_chat '+text.split()[-1],'chat_clicked')
            check('GUI click sends received confirmation command',clicked['command']=='/'+text,click=clicked)
        else:bot.ask('chat /'+text,'chat_sent')
        deadline=time.monotonic()+300
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
        s=harness.Server('paper',version,baseline=str(baseline),run_label=label,view=2,extra_props={'gamemode':'survival'},
             config={'language':'en_us','auto-commit':{'enabled':False,'on-shutdown':False}})
        held=OUT/('filefixer-repo-'+version)
        migrate=version==newer and newer.startswith('26.')
        if migrate:
            shutil.rmtree(held,ignore_errors=True);shutil.move(str(repository(s.world)),held)
        try:
            if tokens and version==older:
                from phase5 import fixture
                fixture(Path(s.dir),version=version)
            if interactions and version==newer:
                from components263 import install
                install(s)
            s.start()
            if migrate:
                shutil.rmtree(repository(s.world),ignore_errors=True);shutil.move(str(held),repository(s.world))
                check('native FileFixer migrated test copy without scanning Git objects',
                      (Path(s.world)/'dimensions/minecraft/overworld/region').is_dir()
                      and repository(s.world).is_dir())
            mode=s.cmd('defaultgamemode survival',r'(?i)game ?mode')
            check('fixture explicitly uses compatible survival default game mode','survival' in mode.lower(),console=mode)
            evidence['console_logs'].append(str(Path(s.evidence_log).relative_to(ROOT)))
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
            fixture=ROOT/'.work/paper-delivery/fixtures'/('multiversion-flat-'+older)
            if not fixture.exists():
                native=OUT/'native-source';shutil.rmtree(native,ignore_errors=True);native.mkdir()
                shutil.copytree(ROOT/'.work/worlds'/older/'multiversion/world',native/'world')
                subprocess.run([harness.JAVA[older],'-Xmx512m','-cp',str(ROOT/'cli/build/libs/wgit.jar'),
                    str(ROOT/'paper/tools/ScaleFixture.java'),older,str(native),str(fixture),'16'],check=True)
                shutil.rmtree(native)
            shutil.copytree(fixture,base)
            subprocess.run([harness.JAVA[older],'-cp',str(ROOT/'cli/build/libs/wgit.jar'),str(ROOT/'paper/tools/MultiversionFixture.java'),str(base/'world/level.dat')]+(['--empty-legacy-indices'] if (older,newer)==('1.20.4','1.21.11') else []),check=True)
            server=start(older,base,'mv2-online-source-'+older)
            # The newer server saves an empty scoreboard at startup. Create the same
            # native file before the source commit; preserve the metadata preflight.
            server.cmd('scoreboard objectives add wg_fixture dummy',r'(?i)created')
            server.cmd('scoreboard objectives remove wg_fixture',r'(?i)removed')
            empty=server.cmd('scoreboard objectives list',r'(?i)no objectives')
            check('source has an explicitly empty native scoreboard','no objectives' in empty.lower(),console=empty)
            server.cmd('setblock 4 64 4 gold_block',r'(?i)changed')
            server.cmd('setblock 5 64 4 chest',r'(?i)changed')
            def sword_stack(version):
                if version in ('1.20.1','1.20.4'):
                    return '{Slot:0b,id:"minecraft:diamond_sword",Count:1b,tag:{display:{Name:\'{"text":"WorldGit sword"}\',Lore:[\'{"text":"WorldGit lore"}\']},Enchantments:[{id:"minecraft:sharpness",lvl:3s}],opaque:{Count:7,components:{untouched:1b}}}}'
                if version in ('1.20.6','1.21.1','1.21.4'):
                    name='\'{"text":"WorldGit sword"}\'';lore='\'{"text":"WorldGit lore"}\''
                    enchantments='{levels:{"minecraft:sharpness":3}}'
                else:
                    name='{text:"WorldGit sword"}';lore='{text:"WorldGit lore"}'
                    enchantments='{"minecraft:sharpness":3}'
                extra=''
                if version in ('1.21.1','1.21.4','1.21.8','1.21.11','26.1.2','26.2','26.3'):
                    model='42' if version=='1.21.1' else '{floats:[42.0f]}'
                    attribute='minecraft:generic.attack_damage' if version=='1.21.1' else 'minecraft:attack_damage'
                    modifier='{type:"'+attribute+'",id:"worldgit:fixture",amount:3.0d,operation:"add_value",slot:"mainhand"}'
                    modifiers='{modifiers:['+modifier+']}' if version in ('1.21.1','1.21.4') else '['+modifier+']'
                    extra=',"minecraft:custom_model_data":'+model+',"minecraft:attribute_modifiers":'+modifiers
                return '{Slot:0b,id:"minecraft:diamond_sword",count:1,components:{"minecraft:custom_name":'+name+',"minecraft:lore":['+lore+'],"minecraft:enchantments":'+enchantments+extra+',"minecraft:custom_data":{opaque:{Count:7,components:{untouched:1b}}}}}'
            def boundary_setup(s):
                old_text=s.version in ('1.20.1','1.20.4','1.20.6','1.21.1','1.21.4')
                legacy_items=s.version in ('1.20.1','1.20.4')
                old_boat=s.version in ('1.20.1','1.20.4','1.20.6','1.21.1')
                text='\'{"text":"WorldGit entity","color":"gold"}\'' if old_text else '{text:"WorldGit entity",color:"gold"}'
                count='Count:1b' if legacy_items else 'count:1'
                equipment=('{HandItems:[{id:"minecraft:diamond_sword",'+count+'},{}],ArmorItems:[{},{},{},{id:"minecraft:diamond_helmet",'+count+'}]}'
                           if old_text else '{equipment:{mainhand:{id:"minecraft:diamond_sword",count:1},head:{id:"minecraft:diamond_helmet",count:1}}}')
                s.cmd('data merge entity @e[type=armor_stand,tag=mv2,limit=1] '+equipment,r'(?i)modified')
                s.cmd('data merge entity @e[type=armor_stand,tag=mv2,limit=1] {CustomName:'+text+'}',r'(?i)modified')
                attr='minecraft:generic.max_health' if old_boat else 'minecraft:max_health'
                s.cmd('attribute @e[type=armor_stand,tag=mv2,limit=1] '+attr+' base set 37.5',r'(?i)base value')
                boat='boat' if old_boat else 'oak_boat'
                s.cmd('summon '+boat+' 12 70 12 {Tags:["mv2boat"],NoGravity:1b,Invulnerable:1b'+(',Type:"oak"' if old_boat else '')+'}',r'(?i)summoned')
                saddle='Saddle:1b' if old_text else 'equipment:{saddle:{id:"minecraft:saddle",count:1}}'
                s.cmd('summon pig 4 70 10 {Tags:["mv2pig"],NoAI:1b,NoGravity:1b,PersistenceRequired:1b,'+saddle+'}',r'(?i)summoned')
                s.cmd('setblock 7 64 4 oak_sign',r'(?i)changed')
                line='\'{"text":"WorldGit sign","color":"blue"}\'' if old_text else '{text:"WorldGit sign",color:"blue"}'
                empty='\'""\'' if old_text else '""'
                s.cmd('data merge block 7 64 4 {front_text:{messages:['+line+','+empty+','+empty+','+empty+']}'+(',allow_op_features:1b' if s.version=='26.3' else '')+'}',r'(?i)modified')
                if newer.startswith('26.'):
                    s.cmd('setblock 8 64 4 furnace',r'(?i)changed')
                    duration='237' if s.version=='26.3' else '237s'
                    field='CookTimeTotal' if s.version in ('1.20.1','1.20.4','1.20.6') else 'cooking_total_time'
                    s.cmd('data merge block 8 64 4 {'+field+':'+duration+'}',r'(?i)modified')
                    s.cmd('setblock 9 64 4 decorated_pot',r'(?i)changed')
                    sherds=('{back:{id:"minecraft:brick"},left:{id:"minecraft:archer_pottery_sherd"},right:{id:"minecraft:arms_up_pottery_sherd"},front:{id:"minecraft:brick"}}'
                            if s.version=='26.3' else '["minecraft:brick","minecraft:archer_pottery_sherd","minecraft:arms_up_pottery_sherd","minecraft:brick"]')
                    s.cmd('data merge block 9 64 4 {sherds:'+sherds+'}',r'(?i)modified')
                    s.cmd('setblock 10 64 4 brewing_stand',r'(?i)changed')
                    s.cmd('setblock 11 64 4 oak_log[axis=x]',r'(?i)changed')
                    s.cmd('setblock 12 64 4 oak_log',r'(?i)changed')
            def boundary_check(s,label):
                old_text=s.version in ('1.20.1','1.20.4','1.20.6','1.21.1','1.21.4')
                old_boat=s.version in ('1.20.1','1.20.4','1.20.6','1.21.1')
                stand=re.sub(r'\x1b\[[0-9;]*m','',s.cmd('data get entity @e[type=armor_stand,tag=mv2,limit=1]',r'(?i)entity data'))
                check(label+' equipment text and attributes',all(x in stand for x in ['minecraft:diamond_sword','minecraft:diamond_helmet','WorldGit entity','gold','37.5d'])
                      and ('HandItems' in stand and 'ArmorItems' in stand if old_text else 'equipment' in stand),console=stand)
                pig=re.sub(r'\x1b\[[0-9;]*m','',s.cmd('data get entity @e[type=pig,tag=mv2pig,limit=1]',r'(?i)entity data'))
                check(label+' saddle',('Saddle: 1b' in pig if old_text else 'minecraft:saddle' in pig and 'equipment' in pig),console=pig)
                boat=re.sub(r'\x1b\[[0-9;]*m','',s.cmd('data get entity @e[tag=mv2boat,limit=1]',r'(?i)entity data'))
                check(label+' boat split',('Type: "oak"' in boat if old_boat else 'Type:' not in boat),console=boat)
                sign=re.sub(r'\x1b\[[0-9;]*m','',s.cmd('data get block 7 64 4 front_text',r'(?i)block data'))
                check(label+' sign text','WorldGit sign' in sign and 'blue' in sign,console=sign)
                if newer.startswith('26.'):
                    states=s.cmd('execute if block 11 64 4 oak_log[axis=x] if block 12 64 4 oak_log[axis=y] run say MV2_STATES_OK','MV2_STATES_OK')
                    check(label+' explicit and default block states', 'MV2_STATES_OK' in states, console=states)
                    furnace=re.sub(r'\x1b\[[0-9;]*m','',s.cmd('data get block 8 64 4',r'(?i)block data'))
                    expected='CookTimeTotal: 237s' if s.version in ('1.20.1','1.20.4','1.20.6') else 'cooking_total_time: 237s' if s.version!='26.3' else 'cooking_total_time: 237,'
                    check(label+' furnace native numeric width',expected in furnace,console=furnace)
                    pot=re.sub(r'\x1b\[[0-9;]*m','',s.cmd('data get block 9 64 4 sherds',r'(?i)block data'))
                    check(label+' pot decorations preserve both sherds',all(x in pot for x in ['minecraft:archer_pottery_sherd','minecraft:arms_up_pottery_sherd']) and ('left:' in pot if s.version=='26.3' else '[' in pot),console=pot)
                    brew=re.sub(r'\x1b\[[0-9;]*m','',s.cmd('data get block 10 64 4',r'(?i)block data'))
                    check(label+' brewing native numeric width',('Fuel: 0b' in brew and 'BrewTime: 0s' in brew if s.version!='26.3' else 'Fuel: 0,' in brew and 'BrewTime: 0,' in brew),console=brew)
            sword=sword_stack(older)
            server.cmd('data merge block 5 64 4 {Items:['+sword+']}',r'(?i)modified')
            server.cmd('summon armor_stand 8 70 8 {Tags:["mv2"],NoGravity:1b,Invisible:1b}',r'(?i)summoned')
            boundary_setup(server)
            boundary_check(server,'source native')
            command(server,'wg init '+TARGET)
            legacy=git(server,'rev-parse','HEAD');evidence['legacy_commit']=legacy
            git(server,'cat-file','-e',legacy+':world-meta/scoreboard.dat.nbt')
            check('source commit contains native scoreboard metadata',True)
            command(server,'wg branch legacy '+TARGET)
            server.stop();oldbase=OUT/'old-base';clone_worlds(server,oldbase);shutil.rmtree(server.dir);server=None
            server=start(newer,oldbase,'mv2-online-source-'+newer)
            if older in ('1.20.1','1.20.4') and newer not in ('1.20.1','1.20.4') and not newer.startswith('26.'):
                # Paper 把舊 keep-spawn-loaded=false 遷移為新 gamerule=0；這與原版缺省 2 不同。
                # 先驗證設定差異仍被擋下，再明確建立設定相容的 components 驗收世界。
                modern_rules=newer=='1.21.11'
                if modern_rules:
                    # 1.21.11 已移除 spawnChunkRadius；以存在的原生規則明確製造差異。
                    original=server.cmd('gamerule minecraft:keep_inventory',r'(?i)game ?rule')
                    check('modern jump fixture begins with native keep_inventory false','false' in original.lower(),console=original)
                    server.cmd('gamerule minecraft:keep_inventory true',r'(?i)game ?rule')
                    server.cmd('save-all flush',r'(?i)saved the game')
                rows=command(server,'wg switch legacy '+TARGET,False)
                check('configured new gamerule still requires offline metadata restore',
                      'finished: FAILED' in rows and 'Online restore of world-meta is not supported' in rows
                      and git(server,'rev-parse','HEAD')==legacy,console=rows)
                if modern_rules:
                    server.cmd('gamerule minecraft:keep_inventory false',r'(?i)game ?rule')
                    restored=server.cmd('gamerule minecraft:keep_inventory',r'(?i)game ?rule')
                    check('compatible modern jump fixture restores native keep_inventory','false' in restored.lower(),console=restored)
                else:
                    server.cmd('gamerule spawnChunkRadius 2',r'(?i)game ?rule')
                    radius=server.cmd('gamerule spawnChunkRadius',r'(?i)game ?rule')
                    check('compatible fixture explicitly uses native spawnChunkRadius default','2' in radius,console=radius)
            server.cmd('setblock 4 64 4 diamond_block',r'(?i)changed')
            if newer=='1.20.4':
                attr=server.cmd('attribute @e[type=armor_stand,tag=mv2,limit=1] minecraft:generic.max_absorption base set 8',r'(?i)base value')
                check('1.20.4 real vanilla max_absorption attribute','8' in attr,console=attr)
                loss_source='minecraft:generic.max_absorption'
            else:
                component_sword=sword_stack(newer)
                special_items={'1.21.1':'minecraft:mace','1.21.4':'minecraft:pale_oak_log',
                               '1.21.8':'minecraft:happy_ghast_spawn_egg','1.21.11':'minecraft:nautilus_spawn_egg','26.1.2':'minecraft:golden_dandelion',
                               '26.2':'minecraft:sulfur','26.3':'minecraft:poplar_log'}
                if newer=='1.20.6':
                    special='{Slot:1b,id:"minecraft:diamond",count:3,components:{"minecraft:food":{nutrition:3,saturation:0.3f,can_always_eat:1b,eat_seconds:1.6f}}}'
                    loss_source='minecraft:food'
                else:
                    loss_source=special_items[newer]
                    special='{Slot:1b,id:"'+loss_source+'",count:1}'
                server.cmd('data merge block 5 64 4 {Items:['+component_sword+','+special+']}',r'(?i)modified')
                actual=server.cmd('data get block 5 64 4 Items',r'(?i)block data')
                check('real source contains version-specific item data',loss_source in actual,console=actual)
            def check_sword(s,label):
                actual=s.cmd('data get block 5 64 4 Items',r'(?i)block data')
                plain=re.sub(r'\x1b\[[0-9;]*m','',actual)
                check(label,all(x in plain for x in ['minecraft:diamond_sword','WorldGit sword','WorldGit lore','minecraft:sharpness','opaque','Count: 7','untouched: 1b'])
                      and ('components:' in plain if s.version not in ('1.20.1','1.20.4') else 'Count: 1b' in plain and 'tag:' in plain),console=actual)
                if s.version in ('1.21.1','1.21.4','1.21.8','1.21.11','26.1.2','26.2','26.3') and (s.version==older or older not in ('1.20.1','1.20.4','1.20.6') or label.startswith('confirmed')):
                    attribute='minecraft:generic.attack_damage' if s.version=='1.21.1' else 'minecraft:attack_damage'
                    check(label+' custom model and item attribute modifier',all(x in plain for x in ['minecraft:custom_model_data','42','worldgit:fixture',attribute]),console=actual)
            if interactions:
                from components263 import interact
                interact(server,OUT,check)
            command(server,'wg commit '+TARGET+' -m source-'+newer)
            modern=git(server,'rev-parse','HEAD');evidence['modern_commit']=modern
            command(server,'wg branch modern '+TARGET)
            rows=command(server,'wg switch legacy '+TARGET)
            check('server DFU automatically upgrades '+older+' commit on '+newer,git(server,'rev-parse','HEAD')==legacy and 'confirm' not in rows.lower(),console=rows)
            rows=command(server,'wg verify '+TARGET)
            check('upgraded commit verifies with zero changes',bool(re.search(r'Stats\[chunks=0, sections=0, biomeSections=0, entityPuts=0, entityRemoves=0, chunkDeletes=0, untrackedKept=0, metaFiles=0\]', rows)),console=rows)
            check_sword(server,'automatic upgrade preserves physical name lore enchantments and opaque custom data')
            boundary_check(server,'automatic upgrade')
            source=OUT/'source.git';shutil.rmtree(source,ignore_errors=True);shutil.copytree(repository(server.world),source)
            server.stop();shutil.rmtree(server.dir);server=None
            imported=OUT/'target-base';shutil.rmtree(imported,ignore_errors=True);shutil.copytree(oldbase,imported)
            subprocess.run(['git','--git-dir',str(imported/'world/.worldgit'),'fetch',str(source),'refs/heads/modern:refs/heads/modern'],check=True)
            server=start(older,imported,'mv2-online-target-'+older)
            bot=server.bot('WgBot');other=server.bot('WgBot2');time.sleep(3)
            rows,text=player_command(bot,'wg switch modern '+TARGET+' --force',False);first=token(rows)
            check('downgrade lists real loss and leaves HEAD untouched',loss_source in text and 'FAILED' in text and git(server,'rev-parse','HEAD')==legacy,chat=rows)
            if interactions:
                from components263 import COMPONENTS
                check('downgrade loss list covers all 13 unrepresentable components',all('minecraft:'+key in text for key in COMPONENTS),chat=rows)
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
            if tokens:
                def refused(text,gui=True):
                    mark=len(bot.lines)
                    if gui:bot.ask('click_chat '+text.split()[-1],'chat_clicked')
                    else:bot.ask('chat /'+text,'chat_sent')
                    time.sleep(2)
                    rows=[e for e in bot.lines[mark:] if e.get('ev')=='chat'];return rows,'\n'.join(e['t'] for e in rows)
                def unchanged():
                    return git(server,'rev-parse','HEAD')==legacy and bot.ask('block 4 64 4','block')['name']=='gold_block'
                rows,_=player_command(bot,'wg switch modern '+TARGET,False);revoked=token(rows)
                server.cmd('wgfixture WgBot revoke-switch','SWITCH_ALLOWED=false')
                rows,text=refused('wg confirm '+revoked)
                check('revoked permission refuses token without writing','permission' in text.lower() and unchanged(),chat=rows)
                server.cmd('wgfixture WgBot restore-switch','SWITCH_ALLOWED=true')
                rows,text=refused('wg confirm '+revoked)
                check('restored permission cannot revive revoked token','invalid' in text.lower() and unchanged(),chat=rows)
                for path in [repository(server.world)/'.wgignore',repository(server.world)/'worldgit.yml',repository(server.world)/'worldgit-repo.yml',Path(server.dir)/'plugins/WorldGit/config.yml']:
                    rows,_=player_command(bot,'wg switch modern '+TARGET,False);stale=token(rows)
                    prior=path.read_bytes() if path.exists() else None
                    initial=prior if prior is not None else b'{}\n' if path.suffix=='.yml' else b''
                    path.write_bytes(initial+b'\n# token invalidation fixture\n')
                    rows,text=player_command(bot,'wg confirm '+stale,False)
                    if path.name=='.wgignore':
                        check('changed .wgignore invalidates token before writing','FAILED' in text and '目標與工作區的 .wgignore 不同' in text and unchanged(),chat=rows)
                    else:
                        check('changed '+path.name+' invalidates token before writing','FAILED' in text and token(rows)!=stale and unchanged(),chat=rows)
                    if prior is None:path.unlink()
                    else:path.write_bytes(prior)
                    rows,text=refused('wg confirm '+stale)
                    check('restored '+path.name+' cannot revive consumed token','invalid' in text.lower() and unchanged(),chat=rows)
                rows,_=player_command(bot,'wg switch modern '+TARGET,False);restarted=token(rows)
                server.stop();server.start();evidence['console_logs'].append(str(Path(server.evidence_log).relative_to(ROOT)))
                bot=server.bot('WgBot');time.sleep(3)
                rows,text=refused('wg confirm '+restarted,gui=False)
                check('server restart invalidates old token without writing','invalid' in text.lower() and unchanged(),chat=rows)
            rows,text=player_command(bot,'wg switch modern '+TARGET,False);current=token(rows)
            rows,text=player_command(bot,'wg confirm '+current)
            check('confirmed downgrade publishes original source HEAD',git(server,'rev-parse','HEAD')==modern,chat=rows)
            check('confirmed downgrade applies physical source block',bot.ask('block 4 64 4','block')['name']=='diamond_block')
            rows=command(server,'wg verify '+TARGET)
            check('downgraded commit verifies with zero changes',bool(re.search(r'Stats\[chunks=0, sections=0, biomeSections=0, entityPuts=0, entityRemoves=0, chunkDeletes=0, untrackedKept=0, metaFiles=0\]', rows)),console=rows)
            check_sword(server,'confirmed downgrade preserves physical name lore enchantments and opaque custom data')
            boundary_check(server,'confirmed downgrade')
            if interactions:
                saved=server.cmd('data get block 14 64 7 Items',r'(?i)block data')
                check('confirmed downgrade drops exactly unsupported carrier components',
                      'minecraft:diamond' in saved and all('minecraft:'+key not in saved for key in COMPONENTS),console=saved)
                sign=server.cmd('data get block 13 64 5',r'(?i)block data')
                check('confirmed downgrade preserves placed sign texts and wax',all(x in re.sub(r'\x1b\[[0-9;]*m','',sign) for x in ['WG 26.3','WG back','filtered','is_waxed: 1b']),console=sign)
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
        if server:
            caches=Path(server.dir)/'.work/versions/conversions'
            if caches.is_dir():shutil.copytree(caches,OUT/'failure-conversions',dirs_exist_ok=True)
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
                prefix='dimensions/minecraft/overworld/' if server.version.startswith('26.') else ''
                for name in ('level.dat',prefix+'region/r.0.0.mca',prefix+'entities/r.0.0.mca'):
                    source=Path(server.world)/name
                    if source.is_file():
                        target=physical/name;target.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(source,target)
            shutil.rmtree(server.dir,ignore_errors=True)
        for name in ('prepared-base','old-base','target-base','source.git','native-source'):shutil.rmtree(OUT/name,ignore_errors=True)
        save()
    return evidence['success']

if __name__=='__main__':
    parser=argparse.ArgumentParser()
    pairs=['1.20.1:1.20.4','1.20.4:1.20.6','1.20.6:1.21.1',
           '1.21.1:1.21.4','1.21.4:1.21.8','1.21.8:1.21.11',
           '1.21.11:26.1.2','26.1.2:26.2','26.2:26.3']
    parser.add_argument('--pair',action='append',choices=pairs+['1.20.1:26.3','1.20.4:1.21.11'])
    parser.add_argument('--tokens',action='store_true',help='補權限撤銷／規則與設定／重啟 token 對抗')
    parser.add_argument('--interactions',action='store_true',help='26.3 新元件原生 wire 與真放置／使用')
    parser.add_argument('--output-root',type=Path,default=ROOT/'.work/paper-multiversion')
    args=parser.parse_args()
    for pair in args.pair or pairs:
        if args.interactions and pair.split(':')[1]!='26.3':parser.error('--interactions 需要26.3目標')
        if not run(*pair.split(':'),args.output_root,args.tokens,args.interactions):raise SystemExit(1)
