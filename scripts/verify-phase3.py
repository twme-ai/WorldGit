#!/usr/bin/env python3
"""Phase 3：自行持有 bench.lock，baseline／Paper 只複製，finally 停服與刪除複本。"""
import argparse
import fcntl
import importlib.util
import json
import sys
from pathlib import Path
import re
import shutil
import subprocess
import time

_spec=importlib.util.spec_from_file_location('phase2',Path(__file__).with_name('verify-phase2.py'))
p2=importlib.util.module_from_spec(_spec);_spec.loader.exec_module(p2)
ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'paper/tools'))
from cli_compat import cli_data, repository, DIMENSIONS, verify_all, prepare_all_entities
WORK=ROOT/'.work/phase3-core'
JAVA=p2.JAVA

def cli(directory,*args):
    if args and args[0]=='init':prepare_all_entities(directory)
    result=subprocess.run([JAVA['1.21.11'],'-Xmx1g','-jar',str(WORK/'wgit.jar'),'--world',str(directory),'--format=json',*args],capture_output=True,text=True,timeout=300)
    with (WORK/'cli.log').open('a') as log:log.write(' '.join(args)+'\n'+result.stdout+result.stderr+'\n')
    value=cli_data(result.stdout)
    if result.returncode and not (result.returncode==2 and value.get('state')=='MERGING'):raise RuntimeError(result.stderr+result.stdout)
    return value

def tool(directory,command,*args,phase2=False):
    result=subprocess.run([JAVA['1.21.11'],'-Xmx1g','-cp',str(WORK/'acceptance-tools.jar'),'org.worldgit.core.Phase2AcceptanceTool' if phase2 else 'org.worldgit.core.Phase3AcceptanceTool',command,str(directory),*map(str,args)],capture_output=True,text=True,timeout=600)
    if result.returncode:raise RuntimeError(result.stderr+result.stdout)
    return result.stdout

def server(version,directory,label,commands=()):
    instance=p2.Server(version,directory,WORK/(version+'-'+label+'.log'))
    try:
        instance.wait('Done (',timeout=300)
        instance.command('tick freeze')
        instance.command('forceload add -48 -48 31 31')
        time.sleep(5)
        for command in commands:instance.command(command)
        instance.command('save-all flush','Saved the game')
    finally:instance.stop()
    text=(WORK/(version+'-'+label+'.log')).read_text()
    errors=[line for line in text.splitlines() if re.search(r'\bERROR\b|Watchdog|Exception',line)]
    assert not errors,errors[:6]
    return {'log':version+'-'+label+'.log','errors':0}

def paper(version):
    baseline=ROOT/'.work/worlds'/version/'baseline'
    source=ROOT/'.work/servers'/('paper-'+version)
    if not baseline.exists() or not (source/'server.jar').exists():return {'skipped':'本機 baseline 或 Paper 不存在'}
    before=p2.manifest(baseline);source_before=p2.manifest(source)
    p2.WORK=WORK
    directory=p2.stage(version,'paper-'+version)
    props=(directory/'server.properties').read_text()
    props=re.sub(r'^server-port=.*$', 'server-port='+('25691' if version=='1.21.11' else '25692'),props,flags=re.M)
    (directory/'server.properties').write_text(props)
    result={}
    try:
        tool(directory,'synthetic',441,phase2=True)
        result['warmup']=server(version,directory,'warmup',['fill 0 223 2 2 223 2 minecraft:stone'])
        cli(directory,'init','--with-dimensions','all');cli(directory,'branch','base');cli(directory,'branch','B')
        a=['setblock 0 224 0 minecraft:gold_block','setblock 1 224 1 minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:4}]}','setblock 16 224 0 minecraft:emerald_block','summon minecraft:armor_stand 16.5 226 0.5 {UUID:[I;0,16384,-2147483648,67],NoGravity:1b,Marker:1b,Invulnerable:1b}','setblock 15 80 0 minecraft:oak_fence','setblock 15 80 2 minecraft:stone']
        server(version,directory,'A-build',a);cli(directory,'commit','-m','A BE/entity/cross chunk');cli(directory,'branch','A');cli(directory,'switch','B')
        b=['setblock -32 224 -32 minecraft:diamond_block','setblock -31 224 -31 minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:9}]}','setblock -16 224 -32 minecraft:lapis_block','summon minecraft:armor_stand -15.5 226 -31.5 {UUID:[I;0,16384,-2147483648,68],NoGravity:1b,Marker:1b,Invulnerable:1b}','setblock 16 80 0 minecraft:stone','setblock 16 80 2 minecraft:oak_fence']
        server(version,directory,'B-build',b);cli(directory,'commit','-m','B BE/entity/cross chunk');cli(directory,'switch','A');cli(directory,'branch','A-original')
        merged=cli(directory,'merge','B');assert merged['state']=='COMPLETE',merged
        assert all(not r['regions'] for r in merged['reports'].values()),merged
        assert cli(directory,'verify')['state']=='COMPLETE'
        history=cli(directory,'log');merge_row=history['minecraft:overworld']['nodes'][0]
        # log 的中性模型每個維度包含完整 parents。
        contents=tool(directory,'inspect');assert 'PASS both branches' in contents,contents
        result['clean_merge']={'merge':merged,'log':merge_row,'verify':cli(directory,'verify'),'contents':contents}
        result['shapes_before']=tool(directory,'shapes')
        result['loaded']=server(version,directory,'merged-load')
        result['shapes_after_load']=tool(directory,'shapes')
        result['verify_after_load']=cli(directory,'verify')
        assert result['verify_after_load']['state']=='COMPLETE',result['verify_after_load']
        result['update_observation']=server(version,directory,'shape-neighbor-update',['setblock 16 80 0 minecraft:air','setblock 16 80 0 minecraft:stone','setblock 15 80 2 minecraft:air','setblock 15 80 2 minecraft:stone'])
        result['shapes_after_neighbor_update']=tool(directory,'shapes')
        cli(directory,'reset','--hard');cli(directory,'switch','base');cli(directory,'branch','D');cli(directory,'branch','C');cli(directory,'switch','C')
        c=['setblock 0 224 2 minecraft:oak_door[half=lower,facing=north]','setblock 0 225 2 minecraft:oak_door[half=upper,facing=north]','setblock 1 224 2 minecraft:oak_fence','setblock 2 224 2 minecraft:redstone_wire']
        server(version,directory,'C-build',c);cli(directory,'commit','-m','C doors/fence/redstone');cli(directory,'switch','D')
        d=['setblock 0 224 2 minecraft:iron_door[half=lower,facing=north]','setblock 0 225 2 minecraft:iron_door[half=upper,facing=north]','setblock 1 224 2 minecraft:birch_fence','setblock 2 224 2 minecraft:stone']
        server(version,directory,'D-build',d);cli(directory,'commit','-m','D same location');cli(directory,'switch','C')
        original=cli(directory,'log')['minecraft:overworld']['nodes'][0];conflict=cli(directory,'merge','D');assert conflict['state']=='MERGING',conflict
        regions=cli(directory,'conflicts','--dimension','minecraft:overworld');assert len(regions)==1,regions
        assert regions[0]['bounds']=={'minX':0,'minY':224,'minZ':2,'maxX':2,'maxY':225,'maxZ':2},regions
        assert regions[0]['blockCount']==4 and regions[0]['redstone'],regions
        assert cli(directory,'status')['repositories']['minecraft:overworld']['remaining']==1
        selections={}
        for choice,revision in [('theirs','D'),('base','base'),('ours','C')]:
            selections[choice]=cli(directory,'resolve','all','--'+choice)
            assert cli(directory,'verify',revision)['state']=='COMPLETE'
        aborted=cli(directory,'merge','--abort');assert aborted['state']=='COMPLETE'
        assert cli(directory,'verify','C')['state']=='COMPLETE';assert cli(directory,'log')['minecraft:overworld']['nodes'][0]==original
        result['conflict']={'merge':conflict,'regions':regions,'selections':selections,'abort':aborted,'verify':cli(directory,'verify','C')}
        cli(directory,'switch','A-original');pick=cli(directory,'cherry-pick','B');assert pick['state']=='COMPLETE',pick
        reverted=cli(directory,'revert','B');assert reverted['state']=='COMPLETE',reverted;assert cli(directory,'verify','A-original')['state']=='COMPLETE'
        cli(directory,'switch','C');pick_conflict=cli(directory,'cherry-pick','D');assert pick_conflict['state']=='MERGING',pick_conflict;cli(directory,'merge','--abort')
        revert_conflict=cli(directory,'revert','D');assert revert_conflict['state']=='MERGING',revert_conflict;cli(directory,'merge','--abort')
        result['patches']={'clean_cherry_pick':pick,'clean_revert':reverted,'conflict_cherry_pick':pick_conflict,'conflict_revert':revert_conflict}
        result['baseline_unchanged']=p2.manifest(baseline)==before
        result['server_source_unchanged']=p2.manifest(source)==source_before
        print(version,'CLI/Paper merge/conflicts/abort/revert/cherry-pick PASS',flush=True)
        return result
    finally:
        (WORK/(version+'-partial.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2))
        shutil.rmtree(directory)
        assert p2.manifest(baseline)==before,'baseline 被修改'
        assert p2.manifest(source)==source_before,'原始 Paper 目錄被修改'

def benchmark(conflicts):
    baseline=ROOT/'.work/worlds/26.2/baseline'
    if not baseline.exists():return {'skipped':'本機 baseline 不存在'}
    directory=WORK/('bench-'+str(conflicts));shutil.copytree(baseline,directory)
    try:
        tool(directory,'synthetic',1000,phase2=True);prep=tool(directory,'benchmark-prepare',conflicts)
        timefile=WORK/('bench-'+str(conflicts)+'.time')
        run=subprocess.run(['/usr/bin/time','-f','wall_seconds=%e\nmax_rss_kib=%M','-o',str(timefile),JAVA['1.21.11'],'-Xmx1g','-cp',str(WORK/'acceptance-tools.jar'),'org.worldgit.core.Phase3AcceptanceTool','benchmark-merge',str(directory)],capture_output=True,text=True,timeout=600)
        (WORK/('bench-'+str(conflicts)+'.log')).write_text(prep+run.stdout+run.stderr)
        assert run.returncode==0,run.stderr+run.stdout
        regions=int(re.search(r'regions=(\d+)',run.stdout)[1]);assert regions==conflicts,run.stdout
        print('benchmark',conflicts,timefile.read_text().strip(),run.stdout.strip(),flush=True)
        return {'preparation':prep,'merge':run.stdout,'time':timefile.read_text(),'regions':regions}
    finally:shutil.rmtree(directory)

def main():
    global WORK
    parser=argparse.ArgumentParser();parser.add_argument('--results-dir',type=Path,default=WORK);args=parser.parse_args();WORK=args.results_dir.resolve()
    if WORK.exists():raise RuntimeError('結果目錄已存在，請指定新目錄')
    WORK.mkdir(parents=True)
    result={}
    try:
        with (ROOT/'.work/bench.lock').open('a') as lock:
            fcntl.flock(lock,fcntl.LOCK_EX)
            shutil.copy2(ROOT/'cli/build/libs/wgit.jar',WORK/'wgit.jar');shutil.copy2(ROOT/'cli/build/libs/acceptance-tools.jar',WORK/'acceptance-tools.jar')
            result['paper']={}
            for version in ['1.21.11','26.2']:
                result['paper'][version]=paper(version)
                (WORK/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
            result['benchmarks']={'1000_chunks_each_branch':benchmark(0),'100_conflict_regions':benchmark(100)}
        result['success']=True
    except Exception as error:
        result['success']=False;result['error']=repr(error);raise
    finally:(WORK/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))

if __name__=='__main__':main()
