#!/usr/bin/env python3
"""真 Fabric 專用伺服器＋Fabric client (Xvfb)；Phase 2／3、重啟、多人同步與量測。自取 bench.lock。"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import sys
import time
import traceback

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'paper/tools'))
import harness
from cli_compat import complete_verification_batch
import dedicated_harness
import statistics
spec=importlib.util.spec_from_file_location('phase1',Path(__file__).with_name('accept-paper.py'))
phase1=importlib.util.module_from_spec(spec);spec.loader.exec_module(phase1)


pair_spec=importlib.util.spec_from_file_location('pair',Path(__file__).with_name('accept-paper-phase3.py'))
pair=importlib.util.module_from_spec(pair_spec);pair_spec.loader.exec_module(pair)
TcpRelay=pair.TcpRelay


def run(args):
    started=time.time()
    name=f'fabric-{args.version}'
    evidence=ROOT/'.work/fabric-acceptance'/f'dedicated-{name}-{time.strftime("%Y%m%d-%H%M%S",time.gmtime())}'
    evidence.mkdir(parents=True)
    result={'platform':'fabric','version':args.version,'success':False,'steps':[],'evidence':str(evidence.relative_to(ROOT))}
    client=server=relay=None
    def save(): (evidence/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    def check(label,ok,**data):
        result['steps'].append({'name':label,'ok':bool(ok),**data});save();print(('PASS ' if ok else 'FAIL ')+label,flush=True)
        if not ok:raise AssertionError(label+' '+str(data))
    def cmd(command,until=r'COMPLETE:|MERGING:|Error:|錯誤：|PARTIAL|工作區有未提交變動',timeout=900):
        out=server.cmd(command,until,timeout)
        with (evidence/'commands.log').open('a') as f:f.write(command+'\n'+out+'\n')
        if any(e in out for e in ['Error:','錯誤：','PARTIAL','失敗：','工作區有未提交變動']):raise RuntimeError(command+'\n'+out)
        print(command+' → '+out.splitlines()[-1],flush=True)
        return out
    def inspect(previews=False):
        proc=subprocess.run([harness.JAVA['1.21.11'],'-Xmx512m','-cp',str(classes)+':'+str(cli),'InteropEvidence',server.world,*(['previews'] if previews else [])],text=True,capture_output=True,check=True,timeout=120)
        return json.loads(proc.stdout)
    def snapshot_hash(revision,cx):
        return subprocess.check_output([harness.JAVA['1.21.11'],'-cp',str(classes)+':'+str(cli),'MergeEvidence','section',server.world,revision,str(cx),'0','4'],text=True,timeout=60).strip()
    def live_hash(cx):
        out=server.cmd(f'wg test sample {cx} 0 4',r'WGSAMPLE',120)
        return re.search(r'hash=([0-9a-f]{64})',out)[1]
    def signal_client(name,value=None):
        target=control/name;temp=control/(name+'.tmp');temp.write_text(json.dumps(value or {}));temp.replace(target)
    def commit(label):cmd('wg commit -m '+label,r'Snapshot|snapshot|世界存檔點|Error:|錯誤：|失敗')
    def branch(label):cmd('wg branch '+label,r'Branch|分支|Error:|錯誤：')
    def switch(label):cmd('wg switch '+label,r'Complete:|Completed|Error:|錯誤：|PARTIAL|工作區有未提交變動')
    def variant(door,fence,delay,item):
        for xyz in ['15 65 2','15 64 2','16 64 2','17 64 2','18 64 2']:server.cmd('setblock '+xyz+' air',r'Changed the block|Could not set the block',120)
        for command in [f'setblock 15 64 2 {door}_door[facing=east,half=lower,hinge=left,open=false,powered=false]',f'setblock 15 65 2 {door}_door[facing=east,half=upper,hinge=left,open=false,powered=false]',f'setblock 17 64 2 repeater[delay={delay},facing=north,locked=false,powered=false]',f'setblock 16 64 2 {fence}_fence[north=false,east=false,south=false,west=false,waterlogged=false]','setblock 18 64 2 chest']:
            server.cmd(command,r'Changed the block|Could not set the block',120)
        server.cmd(f'data merge block 18 64 2 {{Items:[{{Slot:0b,id:"minecraft:{item}",count:4}}]}}',r'Modified block data|Nothing changed',120)
        # setblock 拆門會觸發 vanilla 掉落；玩家傳送後可撿走，不能帶進候選快照。
        server.cmd('kill @e[type=item]',r'Killed|No entity was found',120)
        time.sleep(.5)
    def head():return inspect()['heads']['minecraft:overworld']
    def block(x,y,z,state):
        server.cmd(f'execute if block {x} {y} {z} {state} run say WGBLOCK match',r'WGBLOCK match',120)
    observer=None
    observer_index=0
    def observe():
        nonlocal observer,observer_index
        observer_index+=1
        os.environ['WG_BOT_DUMP']=str(evidence/f'observer-{observer_index}.wire')
        observer=server.bot('WgViewer',True)
        observer.wait_ev('hello_replied',120)
        os.environ.pop('WG_BOT_DUMP',None)
    def multi_sync(expected):
        wire=evidence/f'observer-{observer_index}.wire'
        deadline=time.monotonic()+30
        while time.monotonic()<deadline:
            proc=subprocess.run([harness.JAVA['1.21.11'],'-cp',str(classes)+':'+str(cli),'MergeEvidence','wire',str(wire)],text=True,capture_output=True,timeout=30)
            if proc.returncode==0:
                batches=json.loads(proc.stdout)
                rows=[b['regions'] for b in batches if b['type']=='REGIONS' and b['dimension']['value']=='minecraft:overworld']
                if rows and rows[-1]==expected:
                    check('second viewer receives synchronized list',True,regions=len(expected));return
            time.sleep(.2)
        check('second viewer receives synchronized list',False,stderr=proc.stderr)
    with harness.BenchLock():
        try:
            project='mc1_21_11' if args.version=='1.21.11' else 'mc26_2'
            fixture_task='remapDedicatedFixtureJar' if args.version=='1.21.11' else 'dedicatedFixtureJar'
            with (evidence/'build.log').open('w') as out:
                subprocess.run(['./gradlew','--no-daemon','--configure-on-demand','--max-workers=1',f':fabric:{project}:build',f':fabric:{project}:{fixture_task}',':cli:fatJar'],cwd=ROOT,env=dict(os.environ,JAVA_HOME='/usr/lib/jvm/java-21-openjdk-amd64',GRADLE_USER_HOME=str(ROOT/'.work/gradle-home')),stdout=out,stderr=subprocess.STDOUT,check=True)
            cli=evidence/'wgit.jar';shutil.copy2(ROOT/'cli/build/libs/wgit.jar',cli)
            classes=evidence/'classes';classes.mkdir()
            subprocess.run([harness.JAVA['1.21.11'][:-4]+'javac','-cp',str(cli),'-d',str(classes),str(ROOT/'paper/tools/MergeEvidence.java'),str(ROOT/'fabric/tools/InteropEvidence.java')],check=True)
            baseline=ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+args.version)
            if not baseline.is_dir():subprocess.run([harness.JAVA[args.version],'-Xmx512m','-cp',str(cli),str(ROOT/'paper/tools/ScaleFixture.java'),args.version,str(ROOT/'.work/worlds'/args.version/'baseline'),str(baseline),'16'],check=True)
            mod=ROOT/f'fabric/{project}/build/libs/worldgit-fabric-{args.version}-0.1.0-SNAPSHOT.jar'
            fixture=ROOT/f'fabric/{project}/build/libs/worldgit-fabric-{args.version}-0.1.0-SNAPSHOT-dedicated-fixture{("-remapped" if args.version=="1.21.11" else "")}.jar'
            result['mod']=phase1.snapshot_plugin(mod,evidence/'worldgit-fabric.jar')
            shutil.copy2(fixture,evidence/'fixture.jar')
            server=dedicated_harness.Server(args.version,evidence,baseline,evidence/'worldgit-fabric.jar',evidence/'fixture.jar')
            if args.version=='1.21.11':
                # baseline 是 Paper 分離維度版面；dedicated vanilla 要把兩個維度一併複製。
                for source,name in [(baseline/'world_nether/DIM-1','DIM-1'),(baseline/'world_the_end/DIM1','DIM1')]:
                    shutil.copytree(source,Path(server.world)/name)
            control=evidence/'control';control.mkdir()
            server.start();result['console']=str(Path(server.evidence_log).relative_to(ROOT));result['port']=server.port;save()
            lock_result=server.cmd('wg test locks',r'WGLOCKS|WGTESTFAIL',120);check('batch edit guards execute before mutation', 'WGLOCKS' in lock_result,output=lock_result)
            client_port=server.port+10;relay=TcpRelay(client_port,server.port);result['client_port']=client_port;save()
            project='mc1_21_11' if args.version=='1.21.11' else 'mc26_2'
            temp=ROOT/'.work/fabric-tmp';temp.mkdir(exist_ok=True)
            env=dict(os.environ,JAVA_HOME='/usr/lib/jvm/java-25-openjdk-amd64',GRADLE_USER_HOME=str(ROOT/'.work/gradle-home'),ALSOFT_DRIVERS='null',LIBGL_ALWAYS_SOFTWARE='1',GALLIUM_DRIVER='llvmpipe',LP_NUM_THREADS='3',XDG_CACHE_HOME=str(temp/'cache'),XDG_CONFIG_HOME=str(temp/'config'),TMPDIR=str(temp))
            client=phase1.Process(['xvfb-run','-a','-s','-screen 0 1280x720x24 -ac','./gradlew','--no-daemon','--configure-on-demand','--max-workers=1',f'-PwgtestPaperPort={client_port}',f'-PwgtestPaperReady={control}','-PwgtestPaperPhase3=true','-PwgtestDedicated=true',f':fabric:{project}:runClientGameTest'],ROOT,evidence/'client.log',env)
            client.wait('WGPAIR handshake=true',600)
            joined=re.sub(r'\x1b\[[0-9;]*m','',server.wait(r'(\S+) joined the game',120))
            player=re.search(r'\b([A-Za-z0-9_]{1,16}) joined the game',joined)[1]
            server.cmd('op '+player);server.cmd('gamemode creative '+player)
            server.cmd(f'tp {player} 15.5 66 -4 0 20');server.cmd('forceload add 0 0 31 15')
            server.cmd('wg test fixture-stable',r'WGSTABLE');server.cmd('kill @e[type=!player]')
            variant('oak','oak',1,'diamond');cmd('wg init --all',r'Initialization complete|失敗',900)
            observe()
            op_rows=json.loads((Path(server.dir)/'ops.json').read_text());check('client operator level 2',any(row['name']==player and row['level']==2 for row in op_rows))
            # console Phase 2 寫入；HEAD 與完整 section 比對。
            branch('phase2-A');hash_a=live_hash(0)
            server.cmd('setblock 2 64 2 diamond_block',r'Changed the block');commit('phase2 B');branch('phase2-B');hash_b=live_hash(0);head_b=head()
            server.cmd('setblock 2 64 2 gold_block',r'Changed the block');cmd('wg reset --hard',r'Complete:|Error:')
            check('reset hard restores world, preserves HEAD',live_hash(0)==hash_b and head()==head_b)
            cmd('wg restore phase2-A --box 2 64 2 2 64 2',r'Complete:|Error:')
            check('console box restore preserves HEAD',live_hash(0)==hash_a and head()==head_b)
            cmd('wg reset --hard',r'Complete:|Error:')
            server.cmd('setblock 3 64 3 gold_block',r'Changed the block');stash_hash=live_hash(0)
            cmd('wg stash push dedicated-test',r'Complete:|Error:');check('stash push resets to HEAD',live_hash(0)==hash_b)
            cmd('wg stash pop',r'Complete:|Error:');check('stash pop restores changes',live_hash(0)==stash_hash)
            cmd('wg reset --hard',r'Complete:|Error:');switch('phase2-A');check('switch A',live_hash(0)==hash_a)
            switch('phase2-B');check('switch B',live_hash(0)==hash_b)
            # 乾淨 merge、單 parent patch；固定分支不被操作推進。
            branch('clean-theirs');branch('clean-ours')
            switch('clean-theirs');server.cmd('setblock 6 64 6 lapis_block',r'Changed the block');commit('clean theirs');branch('patch-source')
            switch('clean-ours');server.cmd('setblock 5 64 5 diamond_block',r'Changed the block');commit('clean ours');cmd('wg merge clean-theirs')
            block(5,64,5,'diamond_block');block(6,64,6,'lapis_block');check('clean merge two parents',len(head()['parents'])==2 and inspect()['regions']==[])
            cmd('wg revert patch-source');block(6,64,6,'air');check('clean revert single parent',len(head()['parents'])==1)
            cmd('wg cherry-pick patch-source');block(6,64,6,'lapis_block');check('clean cherry-pick single parent',len(head()['parents'])==1)
            # 1,000 chunk 真正套用與提交；玩家在線，tick observer 使用 monotonic clock。
            branch('scale-base');branch('scale-theirs');branch('scale-ours')
            switch('scale-theirs');server.cmd('wg test fill 32 lapis_block 1000 9',r'WGFILL done',900);commit('scale theirs');server.cmd('wg test release',r'WGRELEASE done')
            switch('scale-ours');server.cmd('wg test fill 32 emerald_block 1000 10',r'WGFILL done',900);commit('scale ours');server.cmd('wg test release',r'WGRELEASE done')
            server.cmd('wg test tick-reset',r'WGTICKS reset');start=time.monotonic();cmd('wg merge scale-theirs');elapsed=time.monotonic()-start
            tps=server.cmd('wg test ticks',r'WGTICKS count');result['scale']={'seconds':elapsed,'tick_probe':tps}
            check('1000 chunk merge two parents, TPS measured',len(head()['parents'])==2,tps=tps,seconds=elapsed)
            switch('scale-base')
            # 再開始小衝突，避免 scale patch 影響完整 section 候選。
            branch('pair-base');branch('pair-theirs')
            variant('spruce','spruce',2,'emerald');commit('pair ours');branch('pair-ours');switch('pair-theirs')
            variant('birch','birch',3,'gold_ingot');commit('pair theirs');switch('pair-ours');cmd('wg merge pair-theirs')
            expected=inspect(True)
            expected['hashes']={choice:{str(cx):snapshot_hash(rev,cx) for cx in [0,1]} for choice,rev in [('OURS','pair-ours'),('THEIRS','pair-theirs'),('BASE','pair-base')]}
            check('server conflict bounds/count',len(expected['regions'])==1 and expected['regions'][0]['blockCount']==5,regions=expected['regions'])
            initial={str(cx):live_hash(cx) for cx in [0,1]}
            rid=expected['regions'][0]['id']
            # abort 及 MERGING 自動 commit，後續以相同候選重建正式 UI 場景。
            original_head=head()
            cmd(f'wg conflict-select {rid} theirs');cmd('wg merge --abort')
            check('abort restores complete sections and HEAD',initial=={str(cx):live_hash(cx) for cx in [0,1]} and head()==original_head and inspect()['regions']==[])
            cmd('wg merge pair-theirs');server.cmd('wg test auto',r'WGAUTO queued');time.sleep(1)
            check('MERGING logout auto commit preserves HEAD',head()==original_head)
            # 真玩家位於受影響 chunk，以操作的實際保護政策檢查三種傷害。
            server.cmd(f'tp {player} 15.5 66 2.5');server.cmd(f'gamemode survival {player}')
            timings=[]
            for choice in ['theirs','base','ours','theirs','base','ours']:
                start=time.monotonic();cmd(f'wg conflict-select {rid} {choice}');timings.append(time.monotonic()-start)
            result['region_latency']={'samples':timings,'median':statistics.median(timings),'max':max(timings)}
            check('region median <= 2 seconds',statistics.median(timings)<=2,**result['region_latency'])
            server.cmd(f'wg test protection {player}',r'WGPROTECT fall');check('affected player damage protection',True)
            server.cmd(f'gamemode creative {player}');server.cmd(f'tp {player} 15.5 66 -4 0 20')
            time.sleep(10.2);server.cmd(f'wg test protection {player} expired',r'WGPROTECT expired');check('player protection expires after operation grace',True)
            multi_sync(inspect()['regions'])
            signal_client('ready.json',expected)
            client.wait('WGPAIR restart_ready',120)
            client.wait('WGPAIR disconnected state-reset=true',120)
            observer.stop();server.stop();server.bots=[];server.start()
            server.cmd('wg test fixture-stable',r'WGSTABLE')
            check('shutdown and restart preserve MERGING and HEAD',inspect()['regions']==expected['regions'] and head()==original_head)
            signal_client('restarted.json');client.wait('WGPAIR restart_restored',300);observe();multi_sync(expected['regions']);signal_client('restart-checked.json')
            client.wait('WGPAIR ghosts_done',900)
            check('ghosts world unchanged (server full sections)',initial=={str(cx):live_hash(cx) for cx in [0,1]})
            signal_client('ghosts-checked.json')
            client.wait('WGPAIR set_done',300)
            actual=inspect();check('Set blocks durable + client list, unresolved',actual['regions']==json.loads((control/'set.json').read_text()) and actual['regions'][0]['choice']=='THEIRS' and not actual['regions'][0]['resolved'])
            check('Set blocks full server state matches theirs',expected['hashes']['THEIRS']=={str(cx):live_hash(cx) for cx in [0,1]})
            multi_sync(actual['regions']);signal_client('set-checked.json')
            client.wait('WGPAIR resolve_done',300)
            actual=inspect();check('Resolve durable + immediate client list',actual['regions']==json.loads((control/'resolve.json').read_text()) and actual['regions'][0]['resolved'] and actual['regions'][0]['choice']=='OURS')
            multi_sync(actual['regions']);signal_client('resolve-checked.json')
            client.wait('WGPAIR small_done',900);check('continue clears durable list, two parents',inspect()['regions']==[] and len(head()['parents'])==2);multi_sync([])
            server.cmd('setblock 6 64 6 gold_block',r'Changed the block');commit('patch conflict')
            for action in ['revert','cherry-pick']:
                patch_head=head();patch_world={str(cx):live_hash(cx) for cx in [0,1]}
                cmd(f'wg {action} patch-source');check(action+' conflict enters MERGING',bool(inspect()['regions']))
                cmd('wg merge --abort');check(action+' conflict abort restores HEAD/world',head()==patch_head and patch_world=={str(cx):live_hash(cx) for cx in [0,1]})
            # 200 個分散於既存乾淨地形的 atoms：不探索自然世界，不引入 vault。
            branch('pair-many-theirs');branch('pair-many-ours')
            switch('pair-many-ours');server.cmd('wg test fill 16 emerald_block 200 10',r'WGFILL done',900);commit('many ours');server.cmd('wg test release',r'WGRELEASE done')
            switch('pair-many-theirs');server.cmd('wg test fill 16 lapis_block 200 10',r'WGFILL done',900);commit('many theirs');server.cmd('wg test release',r'WGRELEASE done')
            switch('pair-many-ours');cmd('wg merge pair-many-theirs');many=inspect();check('server 200 regions',len(many['regions'])==200)
            signal_client('many.json',many)
            client.wait('WGPAIR reconnected regions=200',900)
            check('reconnect obtains same durable list',inspect()['regions']==many['regions'])
            parts=[int(re.search(r'parts=(\d+)',l)[1]) for l in client.lines if 'MERGE_PART channel=worldgit:conflicts' in l]
            check('200 regions actual multipart transport',max(parts)>1,parts=max(parts))
            signal_client('reconnect-checked.json')
            client.wait('WGPAIR many_resolved',900);actual=inspect();check('200 resolved durable',len(actual['regions'])==200 and all(r['resolved'] and r['choice']=='MANUAL' for r in actual['regions']))
            signal_client('many-resolved-checked.json')
            client.wait('WGPAIR DONE',900);client.proc.wait(timeout=90);check('real Fabric client completed',client.proc.returncode==0)
            check('final list empty',inspect()['regions']==[])
            shots=ROOT/'.work/worlds/fabric-gametest'/f'{args.version}-paper/screenshots'
            for label in ['pair-01-conflict-list','pair-02-ghost-theirs','pair-03-set-blocks','pair-04-200-regions']:
                candidates=sorted((p for p in shots.glob('*'+label+'*.png') if p.stat().st_mtime>=started),key=lambda p:p.stat().st_mtime_ns);check('screenshot '+label,bool(candidates));shutil.copy2(candidates[-1],evidence/(label+'.png'))
            result['success']=True
        except BaseException as error:
            result['error']=repr(error);result['trace']=traceback.format_exc();print(result['trace'],flush=True)
            if server and Path(server.dir).is_dir(): shutil.copytree(server.dir,evidence/'failed-server',ignore=shutil.ignore_patterns('libraries','versions','cache','server.jar','plugins','.fabric','mods'))
        finally:
            if client:client.stop();result['client_exit']=client.proc.returncode
            if relay:relay.stop()
            if server:
                try:
                    server.stop();result['server_exit']=server.proc.returncode if server.proc else None
                    result['server_problems']=[l for l in server.lines_since() if 'ERROR' in l or 'Exception' in l]
                    with socket.socket() as probe: result['port_closed']=probe.connect_ex(('127.0.0.1',server.port))!=0
                    with socket.socket() as probe: result['client_port_closed']=probe.connect_ex(('127.0.0.1',server.port+10))!=0
                    if result['success']:
                        proc=subprocess.run([harness.JAVA['1.21.11'],'-jar',str(cli),'-w',server.world,'--format=json','verify','HEAD','--all'],text=True,capture_output=True,timeout=900)
                        (evidence/'verify.json').write_text(proc.stdout);(evidence/'verify.stderr').write_text(proc.stderr)
                        check('final offline verify',proc.returncode==0 and complete_verification_batch(proc.stdout))
                except BaseException:
                    result['success']=False;result['cleanup_error']=traceback.format_exc()
                finally: shutil.rmtree(server.dir,ignore_errors=True)
            result['client_evidence']=[l for l in client.lines if 'WGPAIR ' in l] if client else []
            result['success']=result['success'] and not result.get('server_problems') and result.get('port_closed',False) and result.get('server_exit')==0 and result.get('client_port_closed',False)
            save()
    print(str(evidence/'result.json'),flush=True)
    return result['success']


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version',choices=['1.21.11','26.2'])
    def terminate(signum,frame):raise RuntimeError(f'terminated by signal {signum}')
    signal.signal(signal.SIGTERM,terminate)
    raise SystemExit(0 if run(parser.parse_args()) else 1)
