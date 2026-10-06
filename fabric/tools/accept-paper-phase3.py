#!/usr/bin/env python3
"""真 Fabric client (Xvfb) ↔ Paper/Folia Phase 3；自行取得 bench.lock，finally 關閉並清除世界。"""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import signal
import select
import threading
import socket
import subprocess
import sys
import time
import traceback

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'paper/tools'))
import harness
from cli_compat import cli_data, complete_verification_batch, repository
spec=importlib.util.spec_from_file_location('phase1',Path(__file__).with_name('accept-paper.py'))
phase1=importlib.util.module_from_spec(spec);spec.loader.exec_module(phase1)


class TcpRelay:
    """客戶端入口 25711–25714 ↔ 真伺服器 25701–25704；只轉送 TCP bytes。"""
    def __init__(self, port, target):
        self.target=target;self.closed=threading.Event();self.sockets=[]
        self.listener=socket.socket();self.listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        self.listener.bind(('127.0.0.1',port));self.listener.listen();self.listener.settimeout(.25)
        self.thread=threading.Thread(target=self.accept,daemon=True);self.thread.start()
    def accept(self):
        while not self.closed.is_set():
            try: incoming,_=self.listener.accept()
            except socket.timeout:continue
            except OSError:break
            self.sockets.append(incoming)
            threading.Thread(target=self.forward,args=(incoming,),daemon=True).start()
    def forward(self,incoming):
        outgoing=None
        try:
            outgoing=socket.create_connection(('127.0.0.1',self.target),timeout=10)
            outgoing.settimeout(None);self.sockets.append(outgoing)
            while not self.closed.is_set():
                readable,_,_=select.select([incoming,outgoing],[],[],.25)
                for source in readable:
                    data=source.recv(65536)
                    if not data:return
                    (outgoing if source is incoming else incoming).sendall(data)
        except OSError:pass
        finally:
            incoming.close()
            if outgoing:outgoing.close()
    def stop(self):
        self.closed.set();self.listener.close()
        for sock in self.sockets:
            try:sock.shutdown(socket.SHUT_RDWR)
            except OSError:pass
            sock.close()
        self.thread.join(timeout=2)


def run(args):
    started=time.time()
    name=f'{args.platform}-{args.version}'
    evidence=ROOT/'.work/fabric-acceptance'/f'pair-{name}-{time.strftime("%Y%m%d-%H%M%S",time.gmtime())}'
    evidence.mkdir(parents=True)
    result={'platform':args.platform,'version':args.version,'success':False,'steps':[],'evidence':str(evidence.relative_to(ROOT))}
    client=server=relay=None
    def save(): (evidence/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    def check(label,ok,**data):
        result['steps'].append({'name':label,'ok':bool(ok),**data});save();print(('PASS ' if ok else 'FAIL ')+label,flush=True)
        if not ok:raise AssertionError(label+' '+str(data))
    def cmd(command,until=r'Merge operation complete|合併操作完成|Error:|錯誤：|PARTIAL|工作區有未提交變動',timeout=900):
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
        out=server.cmd(f'wg debug sample {cx} 0 4',r'WGSAMPLE',120)
        return re.search(r'hash=([0-9a-f]{64})',out)[1]
    def signal_client(name,value=None):
        target=control/name;temp=control/(name+'.tmp');temp.write_text(json.dumps(value or {}));temp.replace(target)
    def commit(label):cmd('wg commit -m '+label,r'World snapshot|世界存檔點|overworld [0-9a-f]{8}|Error:|錯誤：|失敗')
    def branch(label):cmd('wg branch '+label,r'Branches|分支：|Error:|錯誤：')
    def switch(label):cmd('wg switch '+label,r'Switched|已切換到|Error:|錯誤：|PARTIAL|工作區有未提交變動')
    def variant(door,fence,delay,item):
        for xyz in ['15 65 2','15 64 2','16 64 2','17 64 2','18 64 2']:server.cmd('setblock '+xyz+' air',r'Changed the block|Could not set the block',120)
        for command in [f'setblock 15 64 2 {door}_door[facing=east,half=lower,hinge=left,open=false,powered=false]',f'setblock 15 65 2 {door}_door[facing=east,half=upper,hinge=left,open=false,powered=false]',f'setblock 17 64 2 repeater[delay={delay},facing=north,locked=false,powered=false]',f'setblock 16 64 2 {fence}_fence[north=false,east=false,south=false,west=false,waterlogged=false]','setblock 18 64 2 chest']:
            server.cmd(command,r'Changed the block|Could not set the block',120)
        server.cmd(f'wg debug fixture-container 18 64 2 {item}',r'WGCONTAINER done|Error:',120)
        # setblock 拆門會觸發 vanilla 掉落；玩家傳送後可撿走，不能帶進候選快照。
        server.cmd('wg debug fixture-clean-items',r'WGITEMS done',120)
        time.sleep(.5)
    with harness.BenchLock():
        try:
            # fixture helper 與正式 plugin 必須同步；在本腳本持有的鎖內建置，沒有第二層 flock。
            with phase1.zipfile.ZipFile(args.plugin) as jar: fixture_ready=b'WGCONTAINER' in jar.read('org/worldgit/paper/Debug.class')
            plugin_time=args.plugin.stat().st_mtime_ns
            default_plugin=args.plugin.resolve()==(ROOT/'paper/plugin/build/libs/worldgit-paper-0.1.0-SNAPSHOT.jar').resolve()
            plugin_stale=default_plugin and any(path.stat().st_mtime_ns>plugin_time for path in (ROOT/'paper').rglob('*.java') if '/src/main/' in str(path))
            if not fixture_ready or plugin_stale:
                if not default_plugin: raise RuntimeError("指定的 plugin 缺少 fixture helper；請使用新版建置產物")
                with (evidence/'fixture-build.log').open('w') as out:
                    subprocess.run(['./gradlew','--no-daemon','--configure-on-demand','--max-workers=1',':paper:plugin:build'],cwd=ROOT,env=dict(os.environ,JAVA_HOME='/usr/lib/jvm/java-21-openjdk-amd64',GRADLE_USER_HOME=str(ROOT/'.work/gradle-home')),stdout=out,stderr=subprocess.STDOUT,check=True)

            cli=evidence/'wgit.jar';shutil.copy2(ROOT/'cli/build/libs/wgit.jar',cli)
            classes=evidence/'classes';classes.mkdir()
            subprocess.run([harness.JAVA['1.21.11'][:-4]+'javac','-cp',str(cli),'-d',str(classes),str(ROOT/'paper/tools/MergeEvidence.java'),str(ROOT/'fabric/tools/InteropEvidence.java')],check=True)
            baseline=ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+args.version)
            if not baseline.is_dir():subprocess.run([harness.JAVA[args.version],'-Xmx512m','-cp',str(cli),str(ROOT/'paper/tools/ScaleFixture.java'),args.version,str(ROOT/'.work/worlds'/args.version/'baseline'),str(baseline),'16'],check=True)
            harness.RUN=str(ROOT/'.work/fabric-pair/run')
            harness.PORTS.update({'paper-1.21.11':25701,'paper-26.2':25702,'folia-1.21.11':25703,'folia-26.2':25704})
            server=harness.Server(args.platform,args.version,baseline=str(baseline),run_label=name,view=3,xmx='1500M',config={'language':'en_us','auto-commit':{'enabled':False,'on-shutdown':False},'commit':{'timeout-seconds':900}})
            result['plugin']=phase1.snapshot_plugin(args.plugin,evidence/'worldgit-paper.jar')
            shutil.copy2(evidence/'worldgit-paper.jar',Path(server.dir)/'plugins/worldgit-paper.jar')
            control=evidence/'control';control.mkdir()
            server.start();result['console']=str(Path(server.evidence_log).relative_to(ROOT));result['port']=server.port;save()
            client_port=server.port+10;relay=TcpRelay(client_port,server.port);result['client_port']=client_port;save()
            project='mc1_21_11' if args.version=='1.21.11' else 'mc26_2'
            temp=ROOT/'.work/fabric-tmp';temp.mkdir(exist_ok=True)
            env=dict(os.environ,JAVA_HOME='/usr/lib/jvm/java-25-openjdk-amd64',GRADLE_USER_HOME=str(ROOT/'.work/gradle-home'),ALSOFT_DRIVERS='null',LIBGL_ALWAYS_SOFTWARE='1',GALLIUM_DRIVER='llvmpipe',LP_NUM_THREADS='3',XDG_CACHE_HOME=str(temp/'cache'),XDG_CONFIG_HOME=str(temp/'config'),TMPDIR=str(temp))
            client=phase1.Process(['xvfb-run','-a','-s','-screen 0 1280x720x24 -ac','./gradlew','--no-daemon','--configure-on-demand','--max-workers=1',f'-PwgtestPaperPort={client_port}',f'-PwgtestPaperReady={control}','-PwgtestPaperPhase3=true',f':fabric:{project}:runClientGameTest'],ROOT,evidence/'client.log',env)
            client.wait('WGPAIR handshake=true',600)
            joined=re.sub(r'\x1b\[[0-9;]*m','',server.wait(r'(\S+) joined the game',120))
            player=re.search(r'\b([A-Za-z0-9_]{1,16}) joined the game',joined)[1]
            server.cmd('op '+player);server.cmd('gamemode creative '+player)
            server.cmd(f'tp {player} 15.5 66 -4 0 20');server.cmd('forceload add 0 0 31 15')
            server.cmd('wg debug freeze on',r'WGFREEZE frozen');server.cmd('kill @e[type=!player]')
            variant('oak','oak',1,'diamond')
            if args.platform=='paper':server.cmd('save-all flush',r'Saved the game',180)
            cmd('wg init --world world --all',r'init 完成|Initialized|Initialization|失敗',900);branch('pair-base');branch('pair-theirs')
            variant('spruce','spruce',2,'emerald');commit('pair ours');branch('pair-ours');switch('pair-theirs')
            variant('birch','birch',3,'gold_ingot');commit('pair theirs');switch('pair-ours');cmd('wg merge pair-theirs')
            expected=inspect(True)
            expected['hashes']={choice:{str(cx):snapshot_hash(rev,cx) for cx in [0,1]} for choice,rev in [('OURS','pair-ours'),('THEIRS','pair-theirs'),('BASE','pair-base')]}
            check('server conflict bounds/count',len(expected['regions'])==1 and expected['regions'][0]['blockCount']==5,regions=expected['regions'])
            initial={str(cx):live_hash(cx) for cx in [0,1]}
            signal_client('ready.json',expected)
            client.wait('WGPAIR ghosts_done',900)
            check('ghosts world unchanged (server full sections)',initial=={str(cx):live_hash(cx) for cx in [0,1]})
            signal_client('ghosts-checked.json')
            client.wait('WGPAIR set_done',300)
            actual=inspect();check('Set blocks durable + client list, unresolved',actual['regions']==json.loads((control/'set.json').read_text()) and actual['regions'][0]['choice']=='THEIRS' and not actual['regions'][0]['resolved'])
            check('Set blocks full server state matches theirs',expected['hashes']['THEIRS']=={str(cx):live_hash(cx) for cx in [0,1]})
            signal_client('set-checked.json')
            client.wait('WGPAIR resolve_done',300)
            actual=inspect();check('Resolve durable + immediate client list',actual['regions']==json.loads((control/'resolve.json').read_text()) and actual['regions'][0]['resolved'] and actual['regions'][0]['choice']=='OURS')
            signal_client('resolve-checked.json')
            client.wait('WGPAIR small_done',900);check('continue clears durable list',inspect()['regions']==[])
            # 200 個分散於既存乾淨地形的 atoms：不探索自然世界，不引入 vault。
            branch('pair-many-theirs');branch('pair-many-ours')
            # 26.2 保存 chunk tickets；fixture 的暫時 plugin tickets 不屬於受測分支內容。
            switch('pair-many-ours');server.cmd('wg debug fill 16 emerald_block 200 10',r'debug fill 完成',900);server.cmd('wg debug release',r'已釋放');commit('many ours')
            switch('pair-many-theirs');server.cmd('wg debug fill 16 lapis_block 200 10',r'debug fill 完成',900);server.cmd('wg debug release',r'已釋放');commit('many theirs')
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
            if server and Path(server.dir).is_dir(): shutil.copytree(server.dir,evidence/'failed-server',ignore=shutil.ignore_patterns('libraries','versions','cache','server.jar','plugins'))
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
                        if proc.returncode:
                            diff=subprocess.run([harness.JAVA['1.21.11'],'-jar',str(cli),'-w',server.world,'--format=json','diff','--blocks'],text=True,capture_output=True,timeout=900)
                            (evidence/'final-difference.json').write_text(diff.stdout)
                            result['final_difference_path']=str((evidence/'final-difference.json').relative_to(ROOT))
                            for dimension,changes in cli_data(diff.stdout).items():
                                for change in changes.get('metadata',[]):
                                    for side in ['beforeId','afterId']:
                                        oid=change.get(side)
                                        if oid:
                                            blob=subprocess.check_output(['git','--git-dir',str(repository(server.world,dimension,args.version,paper=True)),'cat-file','blob',oid])
                                            (evidence/(oid+'.blob')).write_bytes(blob)
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
    parser.add_argument('platform',choices=['paper','folia']);parser.add_argument('version',choices=['1.21.11','26.2'])
    parser.add_argument('--plugin',type=Path,default=ROOT/'paper/plugin/build/libs/worldgit-paper-0.1.0-SNAPSHOT.jar')
    def terminate(signum,frame):raise RuntimeError(f'terminated by signal {signum}')
    signal.signal(signal.SIGTERM,terminate)
    raise SystemExit(0 if run(parser.parse_args()) else 1)
