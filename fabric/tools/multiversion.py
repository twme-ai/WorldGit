#!/usr/bin/env python3
"""Fabric legacy 相鄰雙向線上轉換；dedicated／未開作弊的 integrated client 依序執行。"""
import argparse,hashlib,json,os,re,shutil,signal,subprocess,time,traceback
from pathlib import Path
from accept_phase5_loader import phase5
import phase5_support as support
from phase5_support import Client,events,texts
import harness,dedicated_harness
from cli_compat import repository
ROOT=support.ROOT
CONFIG='locale: en_us\npermission-level: 2\nread-permission-level: 0\nauto-commit:\n  on-logout: false\n  on-stop: false\n  interval-minutes: 0\n'
TARGET=' --dimension minecraft:overworld'

class Environment:
    def __init__(self,mode,version,directory,artifact,source=None):
        self.mode=mode;self.version=version;self.directory=directory;directory.mkdir()
        self.client=None;self.server=None;self.bot=None
        try:
            if mode=='single':
                self.client=Client(directory,version,True,world_source=source,server_config=CONFIG)
                self.world=Path(self.client.ready['world']);self.config=self.client.run/'config/worldgit-server.yml'
            else:
                baseline=ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+version)
                self.server=dedicated_harness.Server(version,directory,baseline,artifact/'worldgit.jar',artifact/'fixture.jar',config=CONFIG,ops=('WgBot','WgBot2'))
                self.world=Path(self.server.world);self.config=Path(self.server.dir)/'config/worldgit-server.yml'
                if source:
                    shutil.rmtree(self.world);shutil.copytree(source,self.world)
                else:
                    for name,dim in [('world_nether','DIM-1'),('world_the_end','DIM1')]:
                        if (baseline/name/dim).is_dir() and not (self.world/dim).exists():shutil.copytree(baseline/name/dim,self.world/dim)
                    subprocess.run([harness.JAVA[version],'-cp',str(ROOT/'cli/build/libs/wgit.jar'),str(ROOT/'fabric/tools/LegacySingleWorld.java'),str(self.world)],check=True)
                self.server.start();self.bot=self.server.bot('WgBot');time.sleep(2)
            self.native('forceload add 0 0');self.native('wg test fixture-stable')
        except BaseException:self.close();raise
    def native(self,command):
        if self.client:self.client.action('server-command',command=command);return ''
        mark=self.server.mark();self.server.send(command);time.sleep(.7);return '\n'.join(self.server.lines_since(mark))
    def command(self,command,terminal=True):
        if self.client:
            mark=self.client.chat_mark();self.client.command(command,nowait=True)
            if terminal:rows=self.client.wait_chat(mark,r'finished: ',300)
            else:time.sleep(1);rows=self.client.chat(mark)
            time.sleep(.2);return rows,'\n'.join(texts(rows))
        mark=len(self.bot.lines);self.bot.ask('chat /'+command,'chat_sent')
        deadline=time.monotonic()+300
        while terminal and time.monotonic()<deadline:
            rows=[e for e in self.bot.lines[mark:] if e.get('ev')=='chat']
            if any('finished: ' in row['t'] for row in rows):break
            time.sleep(.1)
        else:
            if terminal:raise TimeoutError(command+' '+str(self.bot.lines[mark:])[-1600:])
        time.sleep(.5);rows=[e for e in self.bot.lines[mark:] if e.get('ev')=='chat']
        return rows,'\n'.join(row['t'] for row in rows)
    def block(self,x=4,y=64,z=4):
        if self.bot:return self.bot.ask(f'block {x} {y} {z}','block')['name']
        mark=self.client.chat_mark()
        self.client.action('server-command',command=f'execute if block {x} {y} {z} gold_block run tellraw @a "MV_GOLD"')
        self.client.action('server-command',command=f'execute if block {x} {y} {z} diamond_block run tellraw @a "MV_DIAMOND"')
        self.client.action('server-command',command=f'execute if block {x} {y} {z} emerald_block run tellraw @a "MV_EMERALD"')
        rows='\n'.join(texts(self.client.chat(mark)))
        return next((name for name in ('gold','diamond','emerald') if 'MV_'+name.upper() in rows),'air')+'_block'
    def head(self):return subprocess.check_output(['git','--git-dir',str(repository(self.world)),'rev-parse','HEAD'],text=True).strip()
    def save_world(self,path):
        self.close();shutil.copytree(self.world,path)
    def close(self):
        if self.client:
            try:
                if self.client.process.proc.poll() is None:self.client.finish()
            finally:self.client.stop()
        if self.server:self.server.stop()
    def cleanup(self):
        self.close()
        if self.server:shutil.rmtree(self.server.dir,ignore_errors=True)
        if self.client:shutil.rmtree(self.world,ignore_errors=True)

def token(rows):
    candidates=[re.fullmatch(r'/wg confirm ([a-f0-9]{32})',value) for value in events(rows,'run_command')]
    found=[m[1] for m in candidates if m]
    if not found:raise AssertionError('Missing clickable conversion token: '+json.dumps(rows,ensure_ascii=False)[-1800:])
    return found[-1]

def run(mode,output):
    output.mkdir(parents=True);result={'mode':mode,'success':False,'checks':[],'artifacts':{}}
    active=None
    def check(name,ok,**data):
        result['checks'].append({'name':name,'ok':bool(ok),**data});save();print(('PASS ' if ok else 'FAIL ')+name,flush=True)
        if not ok:raise AssertionError(name+' '+str(data)[-1800:])
    def save():(output/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    def start(version,name,source=None):
        nonlocal active
        active=Environment(mode,version,output/name,output/version,source);return active
    def command(text,success=True):
        rows,plain=active.command(text+TARGET)
        print(text+'\n'+plain[-2000:],flush=True)
        if success:check(text,any('finished: '+state in plain for state in ('SUCCESS','NO_OP')),chat=rows)
        return rows,plain
    def verify(label):
        rows,plain=command('wg verify');check(label,'Verified HEAD: the world matches.' in plain,chat=rows)
    try:
        with harness.BenchLock():
            for version in ('1.20.1','1.20.4'):
                artifact=output/version;artifact.mkdir();phase5.build(version,artifact)
                result['artifacts'][version]={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in artifact.glob('*.jar')}
            start('1.20.1','source-old')
            active.native('setblock 4 64 4 gold_block')
            active.native('summon armor_stand 8 70 8 {Tags:["mv3"],NoGravity:1b,Invulnerable:1b}')
            command('wg init --template survival');legacy=active.head();command('wg branch create legacy')
            old_world=output/'old-world';active.save_world(old_world);active.cleanup();active=None
            start('1.20.4','source-new',old_world)
            active.native('setblock 4 64 4 diamond_block')
            active.native('attribute @e[type=armor_stand,tag=mv3,limit=1] minecraft:generic.max_absorption base set 8')
            command('wg commit -m modern-loss');modern=active.head();command('wg branch create modern')
            rows,plain=command('wg switch legacy')
            check('automatic server DFU upgrade changes HEAD and physical block',active.head()==legacy and active.block()=='gold_block' and not events(rows,'run_command'),chat=rows)
            verify('automatic upgrade verify zero differences')
            source_repo=output/'source.git';shutil.copytree(repository(active.world),source_repo)
            active.cleanup();active=None
            imported=output/'imported-world';shutil.copytree(old_world,imported)
            subprocess.run(['git','--git-dir',str(repository(imported)),'fetch',str(source_repo),'refs/heads/modern:refs/heads/modern'],check=True)
            start('1.20.1','target-old',imported)
            def unchanged():return active.head()==legacy and active.block()=='gold_block'
            rows,plain=command('wg switch modern --force',False);first=token(rows)
            check('downgrade previews real loss without HEAD or physical writes','minecraft:generic.max_absorption' in plain and 'FAILED' in plain and unchanged(),chat=rows)
            if mode=='dedicated':
                other=active.server.bot('WgBot2');mark=len(other.lines);other.ask('chat /wg confirm '+first,'chat_sent');time.sleep(1)
                check('token binds the original player',unchanged() and any('invalid' in row.get('t','').lower() for row in other.lines[mark:]),chat=other.lines[mark:])
            active.native('setblock 6 64 4 emerald_block')
            rows,plain=active.command('wg confirm '+first)
            check('world edits invalidate the approval before writing','FAILED' in plain and token(rows)!=first and unchanged() and active.block(6)=='emerald_block',chat=rows)
            active.native('setblock 6 64 4 air')
            if mode=='dedicated':
                rows,_=command('wg switch modern',False);revoked=token(rows)
                active.native('deop WgBot');rows,plain=active.command('wg confirm '+revoked,False)
                check('revoked permissions reject loss token','do not have permission' in plain
                      and 'invalid' in plain.lower() and 'finished: FAILED' in plain and unchanged(),chat=rows)
                active.native('op WgBot');rows,plain=active.command('wg confirm '+revoked,False)
                check('restoring permissions cannot revive revoked token','invalid' in plain.lower() and unchanged(),chat=rows)
            for path in [repository(active.world)/'.wgignore',repository(active.world)/'worldgit.yml',repository(active.world)/'worldgit-repo.yml',active.config]:
                rows,_=command('wg switch modern',False);stale=token(rows)
                previous=path.read_bytes() if path.exists() else None
                path.write_bytes((previous or b'')+b'\n# loss-token invalidation fixture\n')
                rows,plain=active.command('wg confirm '+stale)
                check('changed '+path.name+' invalidates token without writing','FAILED' in plain and unchanged(),chat=rows)
                if previous is None:path.unlink()
                else:path.write_bytes(previous)
                rows,plain=active.command('wg confirm '+stale,False)
                check('restored '+path.name+' cannot revive consumed token','invalid' in plain.lower() and unchanged(),chat=rows)
            rows,_=command('wg switch modern',False);restarted=token(rows)
            restart=output/'restart-world';active.save_world(restart);active.cleanup();active=None
            start('1.20.1','target-restart',restart)
            rows,plain=active.command('wg confirm '+restarted,False)
            check('runtime restart invalidates old token','invalid' in plain.lower() and unchanged(),chat=rows)
            rows,_=command('wg switch modern',False);confirmed=token(rows)
            rows,plain=active.command('wg confirm '+confirmed)
            check('confirmed downgrade publishes original HEAD and physical content','finished: SUCCESS' in plain and active.head()==modern and active.block()=='diamond_block',chat=rows)
            verify('confirmed downgrade verify zero differences')
            rows,plain=active.command('wg confirm '+confirmed,False);check('token is single use','invalid' in plain.lower(),chat=rows)
            active.native('setblock 4 64 4 emerald_block')
            rows,plain=command('wg reset --hard',False);reset=token(rows)
            check('reset waits for loss confirmation',active.head()==modern and active.block()=='emerald_block',chat=rows)
            rows,plain=active.command('wg confirm '+reset);check('reset confirmation succeeds','finished: SUCCESS' in plain,chat=rows);verify('reset verify zero differences')
            rows,plain=command('wg switch legacy')
            check('original legacy snapshot restores automatically without conversion loss',
                  'finished: SUCCESS' in plain and unchanged() and not events(rows,'run_command'),chat=rows)
            command('wg branch create pull-target');command('wg switch pull-target')
            command('wg remote add origin '+source_repo.as_uri())
            rows,plain=command('wg pull origin modern')
            offered=[re.fullmatch(r'/wg pull --dimension minecraft:overworld confirm ([a-f0-9]{8})',value)
                     for value in events(rows,'suggest_command')]
            offered=[match[1] for match in offered if match]
            check('remote pull previews fixed tips without writing',bool(offered) and unchanged(),chat=rows)
            rows,plain=command('wg pull confirm '+offered[-1],False);pull_loss=token(rows)
            check('remote pull confirmation still waits for the loss token',unchanged() and 'FAILED' in plain,chat=rows)
            rows,plain=active.command('wg confirm '+pull_loss)
            check('loss confirmation resumes the same remote pull preview','finished: SUCCESS' in plain and active.head()==modern and active.block()=='diamond_block',chat=rows)
            verify('remote pull downgrade verify zero differences')
            rows,plain=command('wg switch legacy')
            check('remote pull preserves the original legacy branch','finished: SUCCESS' in plain
                  and unchanged() and not events(rows,'run_command'),chat=rows)
            rows,plain=command('wg merge modern',False);merge=token(rows);check('merge preview makes no writes',unchanged(),chat=rows)
            rows,plain=active.command('wg confirm '+merge);check('merge confirmation succeeds','finished: SUCCESS' in plain,chat=rows);verify('merge verify zero differences')
            result['success']=True
    except BaseException:
        result['error']=traceback.format_exc();print(result['error'],flush=True)
        if active and active.world.exists():
            active.close();shutil.copytree(active.world,output/'failure-world')
    finally:
        if active:active.cleanup()
        for name in ('old-world','imported-world','restart-world','source.git'):shutil.rmtree(output/name,ignore_errors=True)
        for version in ('1.20.1','1.20.4'):
            for p in (output/version).glob('*.jar'):p.unlink()
        save()
    return result['success']

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--mode',choices=['dedicated','single','all'],default='all');parser.add_argument('--output-root',type=Path,default=ROOT/'.work/fabric-multiversion');args=parser.parse_args()
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt('terminated')))
    for mode in (['dedicated','single'] if args.mode=='all' else [args.mode]):
        if not run(mode,args.output_root/(mode+'-'+str(time.time_ns()))):raise SystemExit(1)
