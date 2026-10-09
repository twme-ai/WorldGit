"""Fabric 單人正式 jar 端到端 benchmark；自帶 bench.lock。

耗時取同一個客戶端 JVM 的送出／接收 nanoTime，排除 Python 輪詢與等待。
python3 tools/performance/single.py 1.21.11 --label after --artifact fabric/mc1_21_11/build/libs/worldgit-fabric-1.21.11-0.1.0-SNAPSHOT.jar
"""
import argparse
import fcntl
import hashlib
import json
from pathlib import Path
import re
import shutil
import statistics
import sys
import time

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'fabric/tools'),str(ROOT/'paper/tools')]
from phase5_support import Client


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version',choices=['1.21.11','26.2'])
    parser.add_argument('--label',required=True)
    parser.add_argument('--artifact',required=True,type=Path)
    parser.add_argument('--rounds',type=int,default=3)
    parser.add_argument('--counts',nargs='+',type=int,default=[1,10,100,1000])
    parser.add_argument('--require-isolation',action='store_true')
    parser.add_argument('--legacy-baseline','--latency-only',dest='latency_only',action='store_true',help='舊 jar 基準：量端到端與 TPS；使用相容 probe，略過新版 chunk 隔離驗收')
    args=parser.parse_args()
    if args.latency_only and args.require_isolation:parser.error('--latency-only 與 --require-isolation 不可並用')
    if args.rounds<1:parser.error('rounds >= 1')
    if any(n<1 or n>1024 for n in args.counts):parser.error('counts 必須介於 1–1024')
    work=ROOT/'.work/perf'/f'{args.label}-fabric-single-{args.version}'
    work.mkdir(parents=True,exist_ok=False)
    result={'platform':'fabric-single','version':args.version,'label':args.label,'success':False,'rows':{},'isolation':[],
            'artifact_sha256':hashlib.sha256(args.artifact.read_bytes()).hexdigest(),'fixture':'gametest-flat-1296-loaded','players':1,
            'client_renderer':'llvmpipe','client_fps_cap':20,'client_afk_throttle':False,
            'latency_only':args.latency_only,'legacy_baseline':args.latency_only}
    result['auto_commit']='disabled'
    def save():(work/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    client=None;world=None
    with (ROOT/'.work/bench.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        try:
            client=Client(work,args.version,True,artifact=args.artifact)
            world=Path(client.ready['world'])
            client.action('prepare-dimension',dimension='minecraft:the_nether')
            client.action('prepare-dimension',dimension='minecraft:overworld')
            def console(command,pattern=None):
                mark=len(client.process.lines)
                client.action('server-command',command=command)
                if pattern:
                    deadline=time.monotonic()+900
                    while time.monotonic()<deadline:
                        lines=client.process.lines[mark:]
                        if any('WGTESTFAIL' in line for line in lines):raise AssertionError(lines[-8:])
                        if any(re.search(pattern,line) for line in lines):return
                        time.sleep(.05)
                    raise TimeoutError(command+' '+str(lines[-8:]))
            console('wg test fill 36 stone 1296 8','WGFILL done')
            console('tick rate 20')
            console('wg test probe-start','WGPROBE started')
            def probe(action):
                mark=len(client.process.lines);console('wg test probe-'+action,'WGPROBE reset' if action=='reset' else None)
                deadline=time.monotonic()+30
                while time.monotonic()<deadline:
                    lines=client.process.lines[mark:]
                    matched=next((re.search(r'WGPROBE (\{.*\})',line) for line in lines if re.search(r'WGPROBE (\{.*\})',line)),None)
                    if action!='stats' or matched:return json.loads(matched[1]) if matched else None
                    time.sleep(.05)
                raise TimeoutError('probe '+action)
            def run(name,command):
                probe('reset');mark=client.chat_mark();sent=client.command(command,nowait=True)['sentNanos']
                operation=command.split()[1]
                terminal=r'\b'+re.escape(operation)+r' finished: '
                rows=client.wait_chat(mark,terminal,900)
                completed=next(row for row in rows if re.search(terminal,row['text']))
                assert not re.search(r'finished: (FAILED|PARTIAL)',completed['text']),completed
                elapsed=(completed['receivedNanos']-sent)/1e9;measurement=probe('stats')
                with (work/'commands.jsonl').open('a') as log:log.write(json.dumps({'command':command,'seconds':elapsed,'finished':completed['text']},ensure_ascii=False)+'\n')
                row=result['rows'].setdefault(name,{'samples':[],'probes':[]});row['samples'].append(elapsed);row['probes'].append(measurement)
                row.update(p50=statistics.median(row['samples']),max=max(row['samples']))
                if name=='switch-1000':result['isolation'].append(measurement)
                save();print(work.name,name,round(elapsed,3),measurement,flush=True)
            ignore=world/'.worldgit/.wgignore';ignore.parent.mkdir(parents=True,exist_ok=True)
            with ignore.open('a') as stream:stream.write('\narea 960 -64 -64 1103 319 79\n')
            run('init','wg init');run('branch','wg branch create same')
            for _ in range(args.rounds):
                run('status-noop','wg status');run('commit-noop','wg commit -m noop')
                run('switch-noop','wg switch same');run('switch-noop','wg switch main')
            for count in args.counts:
                run('setup-branch',f'wg branch create c{count}');run('setup-switch',f'wg switch c{count}')
                for round_ in range(args.rounds):
                    console(f'wg test fill 32 {"gold_block" if round_%2==0 else "diamond_block"} {count} 8','WGFILL done')
                    run(f'commit-{count}',f'wg commit -m change-{count}-{round_}')
                for _ in range(args.rounds):
                    run(f'switch-{count}','wg switch main');run(f'switch-{count}',f'wg switch c{count}')
                run('setup-switch','wg switch main')
                if count<=100:
                    for round_ in range(args.rounds):
                        run('setup-branch',f'wg branch create merge{count}-{round_}')
                        run('setup-switch',f'wg switch merge{count}-{round_}')
                        run(f'merge-{count}',f'wg merge c{count}');run('setup-switch','wg switch main')
            run('verify','wg verify HEAD')
            if not args.latency_only:console('wg test probe-adversary','WGPROBE adversary passed')
            if not args.latency_only:console('wg test probe-storage','WGPROBE storage passed')
            result['checks']={'full_verify':True,'adversary':not args.latency_only,'storage_last_entity_removal':not args.latency_only}
            if args.require_isolation:
                probes=result['isolation']
                assert probes and all(p['locked_samples']>0 and p['a_game_time_advances']>0 and p['b_advances_while_locked']>0 and p['far_ticks']>0 for p in probes),probes
                assert all(p['b_flow_while_locked']>0 for p in probes),probes
                assert all(p['b_redstone_while_locked']>0 for p in probes),probes
                assert all(p['far_flow']>0 and p['far_redstone']>0 for p in probes),probes
                assert all(p['b_tps']>=18 for p in probes),probes
                result['checks']['isolation']=True
            client.finish();result['success']=True
        except BaseException as error:result['error']=repr(error);raise
        finally:
            if client:client.stop()
            if world and world.is_relative_to(ROOT/'.work/worlds/fabric-gametest'):shutil.rmtree(world,ignore_errors=True)
            save()


if __name__=='__main__':main()
