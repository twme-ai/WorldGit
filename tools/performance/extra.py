"""CLI restore／reset／stash／revert／cherry-pick／diff 小變動補充 benchmark；自帶 bench.lock。"""
import argparse
import fcntl
import hashlib
import json
from pathlib import Path
import shutil
import statistics
import subprocess
import time

ROOT=Path(__file__).resolve().parents[2]
JAVA='/usr/lib/jvm/java-21-openjdk-amd64/bin/java'


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--label',required=True)
    parser.add_argument('--jar',type=Path,default=ROOT/'cli/build/libs/wgit.jar')
    parser.add_argument('--rounds',type=int,default=3)
    args=parser.parse_args()
    if args.rounds<1:parser.error('rounds >= 1')
    jar=args.jar.resolve();work=ROOT/'.work/perf'/args.label;work.mkdir(parents=True,exist_ok=False)
    world=work/'world';result={'label':args.label,'rows':{},'fixture':'synthetic-1296-one-solid-section-per-chunk','success':False,
        'rounds':args.rounds,'artifact_sha256':hashlib.sha256(jar.read_bytes()).hexdigest(),'java':JAVA}
    def save():(work/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    def fixture(action,count,argument):
        subprocess.run([JAVA,'-Xmx768m','-cp',str(jar),str(ROOT/'tools/performance/PerformanceFixture.java'),action,str(world),str(count),str(argument)],check=True,capture_output=True,timeout=900)
    def run(name,*command):
        start=time.perf_counter();p=subprocess.run([JAVA,'-Xmx1G','-jar',str(jar),'-w',str(world),*command],capture_output=True,text=True,timeout=900)
        elapsed=time.perf_counter()-start
        row=result['rows'].setdefault(name,{'samples':[]});row['samples'].append(elapsed);row.update(p50=statistics.median(row['samples']),max=max(row['samples']))
        with (work/'commands.jsonl').open('a') as log:log.write(json.dumps({'command':command,'seconds':elapsed,'exit':p.returncode,'stdout':p.stdout,'stderr':p.stderr},ensure_ascii=False)+'\n')
        save();print(args.label,name,round(elapsed,3),flush=True)
        if p.returncode:raise AssertionError(p.stderr+' '+p.stdout)
    with (ROOT/'.work/bench.lock').open('a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        try:
            fixture('create',1296,ROOT/'.work/worlds/1.21.11/baseline/world')
            run('setup','init','--only')
            for count in (1,10,100):
                run('setup','branch',f'c{count}');run('setup','switch',f'c{count}')
                fixture('mutate',count,'minecraft:gold_block');run('setup','commit','-m',f'change-{count}')
                for _ in range(args.rounds):
                    run(f'diff-{count}','diff','main','HEAD','--blocks')
                    run(f'restore-{count}','restore','main');run(f'reset-{count}','reset','--hard')
                    fixture('mutate',count,'minecraft:emerald_block')
                    run(f'stash-push-{count}','stash','push','-m','benchmark')
                    run(f'stash-pop-{count}','stash','pop');run('setup','reset','--hard')
                    run(f'revert-{count}','revert',f'c{count}')
                    # 分支在 revert 後指向新的 commit；固定初始變動的 revision 從 HEAD~1 取回。
                    run(f'cherry-pick-{count}','cherry-pick','HEAD~1')
                run('setup','switch','main')
            run('verify','verify','HEAD');result['success']=True
        except BaseException as error:result['error']=repr(error);raise
        finally:shutil.rmtree(world,ignore_errors=True);save()


if __name__=='__main__':main()
