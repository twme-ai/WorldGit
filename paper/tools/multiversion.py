#!/usr/bin/env python3
"""True Paper 1.20.1/4 online conversion and loss-token acceptance; owns bench.lock."""
import hashlib,json,re,shutil,subprocess,time,traceback,signal
from pathlib import Path
import harness
from cli_compat import repository
ROOT=Path(harness.ROOT)
OUT=ROOT/'.work/mv2-1/online'
TARGET='--world world --dimension minecraft:overworld'
def terminate(signum,frame):raise KeyboardInterrupt('multiversion runner terminated')
signal.signal(signal.SIGTERM,terminate)

def run():
    OUT.mkdir(parents=True,exist_ok=True)
    evidence={'success':False,'checks':[],'console_logs':[]}
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
            shutil.copytree(ROOT/'.work/paper-delivery/fixtures/acceptance-flat-1.20.1',base)
            subprocess.run([harness.JAVA['1.20.1'],'-cp',str(ROOT/'cli/build/libs/wgit.jar'),str(ROOT/'paper/tools/MultiversionFixture.java'),str(base/'world/level.dat')],check=True)
            server=start('1.20.1',base,'mv2-online-source-1.20.1')
            server.cmd('setblock 4 64 4 gold_block',r'(?i)changed')
            server.cmd('setblock 5 64 4 chest',r'(?i)changed')
            server.cmd('data merge block 5 64 4 {Items:[{Slot:0b,id:"minecraft:diamond",Count:3b}]}',r'(?i)modified')
            server.cmd('summon armor_stand 8 70 8 {Tags:["mv2"],NoGravity:1b,Invisible:1b}',r'(?i)summoned')
            command(server,'wg init '+TARGET)
            legacy=git(server,'rev-parse','HEAD');evidence['legacy_commit']=legacy
            command(server,'wg branch legacy '+TARGET)
            server.stop();oldbase=OUT/'old-base';clone_worlds(server,oldbase);shutil.rmtree(server.dir);server=None
            server=start('1.20.4',oldbase,'mv2-online-source-1.20.4')
            server.cmd('setblock 4 64 4 diamond_block',r'(?i)changed')
            attr=server.cmd('attribute @e[type=armor_stand,tag=mv2,limit=1] minecraft:generic.max_absorption base set 8',r'(?i)base value')
            check('1.20.4 real vanilla max_absorption attribute', '8' in attr,console=attr)
            command(server,'wg commit '+TARGET+' -m source-1.20.4')
            modern=git(server,'rev-parse','HEAD');evidence['modern_commit']=modern
            command(server,'wg branch modern '+TARGET)
            rows=command(server,'wg switch legacy '+TARGET)
            check('server DFU upgrades the 1.20.1 commit automatically',git(server,'rev-parse','HEAD')==legacy and 'confirm' not in rows.lower(),console=rows)
            rows=command(server,'wg verify '+TARGET)
            check('upgraded commit verifies with zero changes',bool(re.search(r'Stats\[chunks=0, sections=0, biomeSections=0, entityPuts=0, entityRemoves=0, chunkDeletes=0, untrackedKept=0, metaFiles=0\]', rows)),console=rows)
            source=OUT/'source.git';shutil.rmtree(source,ignore_errors=True);shutil.copytree(repository(server.world),source)
            server.stop();shutil.rmtree(server.dir);server=None
            imported=OUT/'target-base';shutil.rmtree(imported,ignore_errors=True);shutil.copytree(oldbase,imported)
            subprocess.run(['git','--git-dir',str(imported/'world/.worldgit'),'fetch',str(source),'refs/heads/modern:refs/heads/modern'],check=True)
            server=start('1.20.1',imported,'mv2-online-target-1.20.1')
            bot=server.bot('WgBot');other=server.bot('WgBot2');time.sleep(3)
            rows,text=player_command(bot,'wg switch modern '+TARGET+' --force',False);first=token(rows)
            check('downgrade lists real loss and leaves HEAD untouched','minecraft:generic.max_absorption' in text and 'FAILED' in text and git(server,'rev-parse','HEAD')==legacy,chat=rows)
            check('unconfirmed downgrade leaves physical block intact',bot.ask('block 4 64 4','block')['name']=='gold_block')
            bot.ask('locale zh_tw','locale');time.sleep(1)
            help_rows,help_text=player_command(bot,'wg help confirm',False)
            check('Traditional Chinese confirm help is localized','SUCCESS' in help_text and '確認有損轉換預覽' in help_text and '/wg confirm <token>' in help_text,chat=help_rows)
            zh_rows,zh_text=player_command(bot,'wg switch modern '+TARGET,False)
            check('Traditional Chinese complete loss preview and click token','將損失以下內容' in zh_text and 'minecraft:generic.max_absorption' in zh_text and bool(token(zh_rows)) and git(server,'rev-parse','HEAD')==legacy,chat=zh_rows)
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
    finally:
        if server:server.stop()
        save()
    return evidence['success']

if __name__=='__main__':raise SystemExit(0 if run() else 1)
