#!/usr/bin/env python3
"""Phase 2 本機驗收：兩版 Paper 複本、bench.lock、finally 關服與清理。

先建置 :cli:acceptanceToolsJar。完整 baseline 的往返／裁切另由 :core:integrationTest 驗證。
"""
import argparse
import fcntl
import hashlib
import importlib.util
import json
import sys
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

_spec=importlib.util.spec_from_file_location('phase1_verify',Path(__file__).with_name('verify-paper.py'))
_module=importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(_module)
Server, JAVA = _module.Server, _module.JAVA

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'paper/tools'))
from cli_compat import cli_data, repository, DIMENSIONS, verify_all, prepare_all_entities
WORK = ROOT / '.work/phase2-core'


def manifest(path):
    return {str(p.relative_to(path)): hashlib.sha256(p.read_bytes()).hexdigest()
            for p in path.rglob('*') if p.is_file()}


def cli(directory, *args, reject=False):
    if args and args[0]=='init':prepare_all_entities(directory)
    result = subprocess.run([JAVA['1.21.11'], '-Xmx1g', '-jar', str(WORK/'wgit.jar'),
                             '--world', str(directory), '--format=json', *args],
                            text=True, capture_output=True, timeout=240)
    with (WORK/'cli.log').open('a') as out:
        out.write(' '.join(args)+'\n'+result.stdout+result.stderr+'\n')
    if reject:
        assert result.returncode != 0 and 'session.lock' in result.stderr+result.stdout, result.stderr+result.stdout
        return
    if result.returncode:
        raise RuntimeError(result.stderr+result.stdout)
    return cli_data(result.stdout)


def tool(directory, command, *args):
    result = subprocess.run([JAVA['1.21.11'], '-Xmx1g', '-cp', str(WORK/'acceptance-tools.jar'),
                             'org.worldgit.core.Phase2AcceptanceTool', command, str(directory), *map(str,args)],
                            text=True, capture_output=True, timeout=600)
    if result.returncode:
        raise RuntimeError(result.stderr+result.stdout)
    return result.stdout


def stage(version, label):
    source = ROOT/'.work/servers'/('paper-'+version)
    baseline = ROOT/'.work/worlds'/version/'baseline'
    directory = WORK/label
    shutil.copytree(baseline, directory)
    # 複製可寫的 Paper 工作檔，不讓 launcher 修改原始伺服器目錄。
    for name in ['server.jar','cache','libraries','versions','eula.txt','config','bukkit.yml','spigot.yml','commands.yml']:
        path = source/name
        if path.is_dir(): shutil.copytree(path,directory/name)
        elif path.exists(): shutil.copy2(path,directory/name)
    props = {}
    for line in (source/'server.properties').read_text().splitlines():
        if '=' in line and not line.startswith('#'):
            key,value=line.split('=',1);props[key]=value
    props.update({'server-ip':'127.0.0.1','server-port':'25661' if version=='1.21.11' else '25662',
                  'online-mode':'false','view-distance':'2','simulation-distance':'2','enable-rcon':'false',
                  'pause-when-empty-seconds':'-1','management-server-enabled':'false','management-server-secret':''})
    (directory/'server.properties').write_text('\n'.join(k+'='+v for k,v in props.items())+'\n')
    return directory


def restart(version, directory, name, reject=False):
    server = Server(version,directory,WORK/(version+'-'+name+'.log'))
    try:
        server.wait('Done (',timeout=300)
        server.command('tick freeze')
        for rule in ['random_tick_speed 0','spawn_mobs false','advance_time false','advance_weather false']:
            server.command('gamerule minecraft:'+rule)
        server.command('forceload add 0 0 31 15')
        # 非同步載入與光照處理需要跑完；遊戲 tick 保持凍結。
        time.sleep(6)
        if reject:
            for command in [('switch','A','--force'),('restore','A'),('branch','--format=json'),('stash','list'),('reset','--hard'),('verify',)]:
                cli(directory,*command,reject=True)
        server.command('save-all flush','Saved the game')
    finally:
        server.stop()
    text = (WORK/(version+'-'+name+'.log')).read_text()
    problems = [line for line in text.splitlines() if re.search(r'\bERROR\b|Watchdog|Exception',line)]
    assert not problems, problems[:6]


def paper(version):
    baseline = ROOT/'.work/worlds'/version/'baseline'
    if not baseline.exists() or not (ROOT/'.work/servers'/('paper-'+version)/'server.jar').exists():
        return {'skipped':'本機 baseline 或伺服器不存在'}
    before = manifest(baseline)
    directory = stage(version,'paper-'+version)
    try:
        tool(directory,'synthetic',441)
        restart(version,directory,'warmup')
        cli(directory,'init','--with-dimensions','all')
        cli(directory,'branch','A')
        tool(directory,'mutate',4)
        # 先讓新 BE 補上原版預設欄位，B 才是能在真伺服器上穩定往返的快照。
        restart(version,directory,'B-settle')
        cli(directory,'commit','-m','B block BE entity across chunks')
        cli(directory,'branch','B')
        evidence = {}
        for branch in ['A','B']:
            applied = cli(directory,'switch',branch)
            assert applied['state']=='COMPLETE', applied
            assert cli(directory,'verify',branch)['state']=='COMPLETE'
            pre = tool(directory,'inspect')
            assert 'blockLightSections=0' in pre and 'skyLightSections=0' in pre and 'poi=[]' in pre, pre
            restart(version,directory,'restore-'+branch,reject=branch=='A')
            post = tool(directory,'inspect')
            assert int(re.search(r'blockLightSections=(\d+)',post)[1])>0, post
            assert int(re.search(r'skyLightSections=(\d+)',post)[1])>0, post
            assert 'duplicateUUIDs=0' in post and 'controlledEntityCount=1' in post, post
            expected = '[3, 65, 3]' if branch=='A' else '[4, 65, 4]'
            unexpected = '[4, 65, 4]' if branch=='A' else '[3, 65, 3]'
            assert 'minecraft:librarian'+expected in post and 'minecraft:librarian'+unexpected not in post, post
            verified = cli(directory,'verify',branch)
            assert verified['state']=='COMPLETE', verified
            evidence[branch]={'apply':applied,'before_restart':pre,'after_restart':post,'verify':verified}
            print(version,branch,'restart/light/POI/entity/verify PASS',flush=True)
        return {'passes':evidence,'active_session_rejected':True,'baseline_unchanged':manifest(baseline)==before}
    finally:
        shutil.rmtree(directory)
        assert manifest(baseline)==before, 'baseline 被修改'


def benchmark():
    baseline=ROOT/'.work/worlds/26.2/baseline'
    if not baseline.exists(): return {'skipped':'本機 baseline 不存在'}
    directory=WORK/'benchmark'
    shutil.copytree(baseline,directory)
    try:
        tool(directory,'synthetic',1000)
        tool(directory,'benchmark-prepare')
        timed=subprocess.run(['/usr/bin/time','-f','wall_seconds=%e\nmax_rss_kib=%M','-o',str(WORK/'benchmark.time'),
                              JAVA['1.21.11'],'-Xmx1g','-cp',str(WORK/'acceptance-tools.jar'),
                              'org.worldgit.core.Phase2AcceptanceTool','benchmark-switch',str(directory)],
                             capture_output=True,text=True,timeout=600)
        (WORK/'benchmark.log').write_text(timed.stdout+timed.stderr)
        assert timed.returncode==0, timed.stderr+timed.stdout
        verified=cli(directory,'verify','A')
        result={'measurement':(WORK/'benchmark.time').read_text(),'switch':timed.stdout,'verify':verified}
        print('benchmark',result,flush=True)
        return result
    finally: shutil.rmtree(directory)


def main():
    global WORK
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--results-dir',default='.work/phase2-core',help='新的驗收證據目錄，相對專案根目錄')
    WORK=(ROOT/parser.parse_args().results_dir).resolve()
    WORK.mkdir(parents=True,exist_ok=True)
    if (WORK/'results.json').exists(): raise RuntimeError('驗收結果目錄已存在；請以 --results-dir 指定新目錄')
    for name in ['wgit.jar','acceptance-tools.jar']: shutil.copy2(ROOT/'cli/build/libs'/name,WORK/name)
    results={}
    with (ROOT/'.work/bench.lock').open('a') as lock:
        print('waiting for bench.lock',flush=True);fcntl.flock(lock,fcntl.LOCK_EX)
        for version in JAVA:
            try: results[version]=paper(version)
            except Exception as error: results[version]={'error':str(error)};print(version,'FAILED',error,flush=True)
            (WORK/'results.json').write_text(json.dumps(results,indent=2,ensure_ascii=False))
        try: results['benchmark']=benchmark()
        except Exception as error: results['benchmark']={'error':str(error)};print('benchmark FAILED',error,flush=True)
        (WORK/'results.json').write_text(json.dumps(results,indent=2,ensure_ascii=False))
    if any('error' in value for value in results.values()): raise SystemExit(1)


if __name__=='__main__': main()
