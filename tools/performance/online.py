"""Paper／Folia／Fabric dedicated 端到端量測及多世界隔離驗收；自帶 bench.lock。

量測只計算送出到 finished 訊息；不包含 harness 的 settle sleep。預設無玩家、既有安全預算。
python3 tools/performance/online.py paper 1.21.11 --label after
"""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import shutil
import statistics
import subprocess
import sys
import time
import zipfile

ROOT=Path(__file__).resolve().parents[2]
sys.path[:0]=[str(ROOT/'paper/tools'),str(ROOT/'fabric/tools')]
import harness
import dedicated_harness


def paper_probe(server):
    cache=ROOT/'.work/gradle-home/caches/modules-2/files-2.1'
    api=next((cache/'io.papermc.paper/paper-api/1.21.11-R0.1-SNAPSHOT').rglob('*.jar'))
    jars=[api]
    for group in ['com.mojang/brigadier','net.kyori/adventure-api','net.kyori/adventure-key','org.jetbrains/annotations','net.md-5/bungeecord-chat','net.kyori/examination-api','net.kyori/examination-string']:
        jars+=list((cache/group).rglob('*.jar'))
    jars.append(Path(server.dir)/'plugins/worldgit-paper.jar')
    classes=Path(server.dir)/'probe-classes';classes.mkdir()
    subprocess.run([str(Path(harness.JAVA['1.21.11']).with_name('javac')),'-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),str(ROOT/'tools/performance/PaperProbe.java')],check=True)
    with zipfile.ZipFile(Path(server.dir)/'plugins/performance-probe.jar','w') as jar:
        for path in classes.rglob('*.class'):jar.write(path,str(path.relative_to(classes)))
        jar.writestr('plugin.yml','name: WorldGitPerformanceProbe\nversion: 1\nmain: org.worldgit.perf.PaperProbe\napi-version: "1.21"\nfolia-supported: true\ndepend: [WorldGit]\ncommands:\n  wgperf:\n    description: Internal performance fixture\n')
    shutil.rmtree(classes)


def main(argv=None):
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('platform',choices=['paper','folia','fabric'])
    parser.add_argument('version',choices=['1.21.11','26.2'])
    parser.add_argument('--label',required=True)
    parser.add_argument('--artifact',type=Path,help='指定 before 插件／模組 jar')
    parser.add_argument('--rounds',type=int,default=3)
    parser.add_argument('--counts',type=int,nargs='+',default=[1,10,100,1000])
    parser.add_argument('--require-isolation',action='store_true')
    parser.add_argument('--legacy-baseline','--latency-only',dest='latency_only',action='store_true',help='舊 jar 基準：量端到端與 TPS；使用相容 probe，略過新版 chunk 隔離驗收')
    args=parser.parse_args(argv)
    if args.latency_only and args.require_isolation:parser.error('--latency-only 與 --require-isolation 不可並用')
    if args.rounds<1:parser.error('rounds >= 1')
    if any(n<1 or n>1024 for n in args.counts):parser.error('counts 必須介於 1–1024')
    work=ROOT/'.work/perf'/f'{args.label}-{args.platform}-{args.version}'
    work.mkdir(parents=True,exist_ok=False)
    result={'platform':args.platform,'version':args.version,'label':args.label,'rounds':args.rounds,'success':False,'rows':{},'isolation':[],'fixture':'online-flat-2704-target-32x32','players':0,'latency_only':args.latency_only,'legacy_baseline':args.latency_only}
    def save(): (work/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    server=None
    with (ROOT/'.work/bench.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        try:
            if not args.artifact and args.platform!='fabric':
                harness.ensure_shared_artifacts(work/'artifact-build.log')
            baseline=ROOT/'.work/perf/fixtures'/('online-flat-'+args.version)
            if not baseline.exists():
                baseline.parent.mkdir(parents=True,exist_ok=True)
                subprocess.run([harness.JAVA[args.version],'-Xmx512m','-cp',str(ROOT/'cli/build/libs/wgit.jar'),str(ROOT/'paper/tools/ScaleFixture.java'),args.version,str(ROOT/'.work/worlds'/args.version/'baseline'),str(baseline),'32'],check=True,timeout=180)
            if args.platform=='fabric':
                project='mc1_21_11' if args.version=='1.21.11' else 'mc26_2'
                libs=ROOT/f'fabric/{project}/build/libs'
                mod=args.artifact or libs/f'worldgit-fabric-{args.version}-0.1.0-SNAPSHOT.jar'
                fixture=next(libs.glob('*dedicated-fixture-remapped.jar' if args.version=='1.21.11' else '*dedicated-fixture.jar'))
                server=dedicated_harness.Server(args.version,work,baseline,mod,fixture,config='locale: en_us\nauto-commit:\n  on-logout: false\n  on-stop: false\n  interval-minutes: 0\n')
                if args.version=='1.21.11':
                    for source,name in [(baseline/'world_nether/DIM-1','DIM-1'),(baseline/'world_the_end/DIM1','DIM1')]:shutil.copytree(source,Path(server.world)/name)
                props=Path(server.dir)/'server.properties'
                with props.open('a') as f:f.write('pause-when-empty-seconds=-1\n')
                artifact=Path(server.dir)/'mods/worldgit.jar'
            else:
                server=harness.Server(args.platform,args.version,baseline=str(baseline),run_label=work.name,view=2,xmx='1500M',extra_props={'pause-when-empty-seconds':'-1'},
                    config={'language':'en_us','auto-commit':{'enabled':False,'on-shutdown':False},'commit':{'chunks-per-tick':8,'snapshot-window':256}})
                artifact=Path(server.dir)/'plugins/worldgit-paper.jar'
                if args.artifact:shutil.copy2(args.artifact,artifact)
                paper_probe(server)
            result['artifact_sha256']=hashlib.sha256(artifact.read_bytes()).hexdigest()
            server.start()
            def command(text,pattern=r'finished: ',timeout=900):
                if args.platform != 'fabric' and text.startswith('wg branch create '):
                    text = text.replace('wg branch create ', 'wg branch ', 1)
                if text.startswith('wg ') and not text.startswith(('wg test ','wg debug ')):
                    words=text.split(' ',2)
                    scope='--dimension minecraft:overworld' if args.platform=='fabric' else '--world world --dimension minecraft:overworld'
                    text=' '.join([words[0],words[1],scope,words[2] if len(words)>2 else '']).strip()
                mark=server.mark();start=time.perf_counter();server.send(text);rx=re.compile(pattern);deadline=start+timeout
                while time.perf_counter()<deadline:
                    lines=server.lines_since(mark)
                    match=next((l for l in lines if rx.search(l)),None)
                    rejected = next((l for l in lines if 'Incorrect argument for command' in l or 'Unknown or incomplete command' in l), None)
                    if rejected: raise AssertionError(text+' '+rejected)
                    probe_error=next((l for l in lines if 'A probe failed' in l or 'B probe failed' in l
                        or 'Far probe failed' in l or 'WGTESTFAIL' in l or re.search(r'WGPERF .*failed',l)
                        or 'WorldGitPerformanceProbe' in l and 'generated an exception' in l),None)
                    if probe_error:raise AssertionError(probe_error)
                    if match:
                        elapsed=time.perf_counter()-start
                        (work/'commands.jsonl').open('a').write(json.dumps({'command':text,'seconds':elapsed,'lines':lines},ensure_ascii=False)+'\n')
                        if 'finished: FAILED' in match or 'finished: PARTIAL' in match or 'failed' in match.lower():raise AssertionError(match)
                        return elapsed,lines
                    if server.proc.poll() is not None:raise RuntimeError('server exited')
                    time.sleep(.01)
                raise TimeoutError(text+' '+str(lines[-8:]))
            prefix='wg test probe-' if args.platform=='fabric' else 'wgperf '
            command(prefix+'start','WGPROBE started' if args.platform=='fabric' else 'WGPERF started')
            def probe(action):
                _,lines=command(prefix+action,'WGPROBE|WGPERF')
                if action=='stats':return json.loads(next(re.search(r'(?:WGPROBE|WGPERF) (\{.*\})',l)[1] for l in lines if re.search(r'(?:WGPROBE|WGPERF) (\{.*\})',l)))
            def run(name,text):
                probe('reset');elapsed,lines=command(text);measurement=probe('stats')
                row=result['rows'].setdefault(name,{'samples':[],'probes':[]})
                row['samples'].append(elapsed);row['probes'].append(measurement)
                row['p50']=statistics.median(row['samples']);row['max']=max(row['samples'])
                if name=='switch-1000':result['isolation'].append(measurement)
                save();print(work.name,name,round(elapsed,3),measurement,flush=True)
            def fill(count,material):
                if args.platform=='fabric':command(f'wg test fill 32 {material} {count} 8','WGFILL done')
                else:command(f'wg debug fill 32 {material} {count} 8','debug fill 完成')
            ignore=Path(server.world)/'.worldgit/.wgignore'
            ignore.parent.mkdir(parents=True,exist_ok=True)
            ignore.write_text(ignore.read_text() if ignore.exists() else '')
            with ignore.open('a') as stream:stream.write('\n# Untracked same-world performance probe\narea 960 -64 -64 1103 319 79\n')
            run('init','wg init')
            run('branch','wg branch create same')
            for _ in range(args.rounds):
                run('status-noop','wg status');run('commit-noop','wg commit -m noop')
                run('switch-noop','wg switch same');run('switch-noop','wg switch main')
            for count in args.counts:
                run('setup-branch',f'wg branch create c{count}')
                run('setup-switch',f'wg switch c{count}')
                for round_ in range(args.rounds):
                    fill(count,'gold_block' if round_%2==0 else 'diamond_block')
                    run(f'commit-{count}',f'wg commit -m change-{count}-{round_}')
                for _ in range(args.rounds):
                    run(f'switch-{count}','wg switch main');run(f'switch-{count}',f'wg switch c{count}')
                run('setup-switch','wg switch main')
                if count<=100:
                    for round_ in range(args.rounds):
                        run('setup-branch',f'wg branch create merge{count}-{round_}')
                        run('setup-switch',f'wg switch merge{count}-{round_}')
                        run(f'merge-{count}',f'wg merge c{count}')
                        run('setup-switch','wg switch main')
            run('verify','wg verify HEAD')
            if not args.latency_only:
                if args.platform!='fabric':command(prefix+'entity-scope',r'WGPERF entity scope passed',60)
                command(prefix+'adversary',r'(?:WGPERF|WGPROBE) adversary passed',60)
                if args.platform=='fabric':command(prefix+'storage',r'WGPROBE storage passed',120)
            result['checks']={'full_verify':True,'adversary':not args.latency_only,
                              'storage_last_entity_removal':args.platform=='fabric' and not args.latency_only}
            if args.require_isolation:
                large=result['isolation']
                assert large and all(p['locked_samples']>0 and p['a_game_time_advances']>0 and p['b_advances_while_locked']>0 and p['far_ticks']>0 for p in large),large
                assert all(p['b_flow_while_locked']>0 for p in large),large
                assert all(p['b_redstone_while_locked']>0 for p in large),large
                assert all(p['far_flow']>0 and p['far_redstone']>0 for p in large),large
                assert all(p['b_tps']>=18 for p in large),large
                result['checks']['isolation']=True
            result['success']=True
        except BaseException as error:
            result['error']=repr(error)
            if server:
                try:command('wg status --full');command('wg diff HEAD')
                except BaseException as diagnostic:result['diagnostic_error']=repr(diagnostic)
            raise
        finally:
            if server:
                server.stop()
                if 'error' in result:
                    shutil.copytree(server.world,work/'failed-world',dirs_exist_ok=True)
                    result['failed_world']=str((work/'failed-world').relative_to(ROOT))
                if hasattr(server, 'evidence_log'):
                    result['server_log']=str(Path(server.evidence_log).relative_to(ROOT))
                shutil.rmtree(server.dir,ignore_errors=True)
            save()
    return 0


if __name__=='__main__':raise SystemExit(main())
