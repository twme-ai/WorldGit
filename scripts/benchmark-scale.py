#!/usr/bin/env python3
"""正式 CLI 的 init/status/commit/repack 量測；持有 bench.lock，不修改 Phase 0 原檔。"""
import fcntl
import json
import os
from pathlib import Path
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
WORK = Path(os.environ.get('WGIT_BENCH_DIR',str(ROOT/'.work/phase1/scale')))
JAVA = '/usr/lib/jvm/java-21-openjdk-amd64/bin/java'

def measure(name, command):
    target = WORK/(name+'.time.json')
    with open(WORK/(name+'.stdout'),'w') as out, open(WORK/(name+'.stderr'),'w') as err:
        result = subprocess.run(['/usr/bin/time','-f','{"wall_seconds":%e,"max_rss_kib":%M}','-o',str(target),*map(str,command)],stdout=out,stderr=err)
    if result.returncode: raise RuntimeError(name+' failed; see '+str(WORK/(name+'.stderr')))
    stats=json.loads(target.read_text())
    print(name,stats,flush=True)
    return stats

def main():
    WORK.mkdir(parents=True,exist_ok=True)
    report={}
    for name in ['wgit.jar','acceptance-tools.jar']:
        shutil.copy2(ROOT/'cli/build/libs'/name, WORK/name)
    with open(ROOT/'.work/bench.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        print('取得 bench.lock',flush=True)
        directory=WORK/'run'
        if directory.exists(): raise RuntimeError('量測目錄已存在：'+str(directory))
        original=Path(os.environ.get('WGIT_BENCH_SOURCE',str(ROOT/'.work/survival-scale/large/run')))
        if (original/'world/level.dat').is_file():
            shutil.copytree(original,directory,ignore=shutil.ignore_patterns('.worldgit','*.jar','libraries','cache','versions','logs','plugins'))
            report['provenance']='指定來源 '+str(original)+' 的複本' if os.environ.get('WGIT_BENCH_SOURCE') else 'Phase 0 原始 large/run 的複本'
        else:
            repo=ROOT/'.work/survival-scale/large/original.git'
            prototype=ROOT/'experiments/06-survival-scale/build/libs/survival-scale.jar'
            if not (repo/'HEAD').is_file() or not prototype.is_file():
                (WORK/'results.json').write_text(json.dumps({'skipped':'Phase 0 的大型世界及可重建 repo 不存在'},ensure_ascii=False,indent=2))
                return
            shutil.copytree(ROOT/'.work/worlds/26.2/baseline',directory)
            # 原始 raw 世界已清理；只讀 Phase 0 init repo，用原型 restore 重建供正式 CLI 量測。
            head=subprocess.check_output(['git','--git-dir='+str(repo),'rev-parse','main'],text=True).strip()
            report['provenance']='Phase 0 原始 raw 世界已清理；由 original.git 的 init commit 重建（非原始 raw 存檔）'
            report['phase0_commit']=head
            report['restore']=measure('restore',[JAVA,'-Xmx1g','-jar',prototype,'restore',directory,repo,head,'minecraft/overworld',-80,-80,80,80,'--poi','delete'])
        cp=str(WORK/'acceptance-tools.jar')
        tool=[JAVA,'-Xmx1g','-cp',cp,'org.worldgit.core.AcceptanceTool']
        chunks=subprocess.check_output([*tool,'chunks',str(directory)],text=True).splitlines()
        report['full_chunks']=len(chunks)
        cli=[JAVA,'-Xmx1g','-jar',str(WORK/'wgit.jar'),'--world',str(directory),'--color=never']
        report['init']=measure('init',[*cli,'init'])
        report['status']=measure('status',[*cli,'status'])
        report['no_change_commit']=measure('no-change-commit',[*cli,'commit','-m','unchanged'])
        subprocess.run([*tool,'one-block',str(directory)],check=True)
        report['one_block_commit']=measure('one-block-commit',[*cli,'commit','-m','one block'])
        report['repack']=measure('repack',[*tool,'repack',str(directory)])
        # 正式 repo 在世界資料夾外：run/.worldgit/world/（directory 是 server root）。
        packs=list((directory/'.worldgit').rglob('*.pack'))
        report['packs']={str(p.relative_to(directory)):p.stat().st_size for p in packs}
        report['all_packs_under_100MB']=bool(packs) and all(p.stat().st_size<100_000_000 for p in packs)
        for repo in (directory/'.worldgit/world').iterdir():
            if repo.is_dir() and (repo/'HEAD').is_file():
                subprocess.run(['git','--git-dir='+str(repo),'fsck','--no-dangling'],check=True,stdout=subprocess.DEVNULL)
        report['git_fsck']='passed'
        (WORK/'results.json').write_text(json.dumps(report,indent=2,ensure_ascii=False))
        print(json.dumps(report,indent=2,ensure_ascii=False),flush=True)
if __name__=='__main__':main()
