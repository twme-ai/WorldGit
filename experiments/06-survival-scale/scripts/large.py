#!/usr/bin/env python3
from common import *
import argparse

def pregenerate(s,x1,z1,x2,z2,label):
    start=time.monotonic();n=len(s.lines)
    for c in ['chunky world world','chunky shape square',f'chunky corners {x1} {z1} {x2} {z2}','chunky start']:s.cmd(c)
    while True:
        if s.wait('Task finished',20,n):break
        if s.p.poll() is not None:raise RuntimeError('pregeneration server stopped')
        emit(event='pregeneration',label=label,elapsed_s=round(time.monotonic()-start),disk=guard())
        if time.monotonic()-start>7200:raise RuntimeError('pregeneration timeout')
    s.cmd('save-all flush','Saved the game',180)
    return {'wall_s':time.monotonic()-start,'corners':[x1,z1,x2,z2],'locked':True}
def sizes(repo):
    files=object_sizes(repo)
    packs=list((repo/'objects/pack').glob('*.pack'))
    count_text=subprocess.check_output(['git','--git-dir='+str(repo),'count-objects','-v'],text=True)
    counts={k:int(v.strip()) for k,v in (l.split(':',1) for l in count_text.splitlines()) if v.strip().isdigit()}
    return {'reachable_objects':len(subprocess.check_output(['git','--git-dir='+str(repo),'rev-list','--objects','--all'],text=True).splitlines()),'git_counts':counts,'loose_bytes':sum(files.values()),'loose_objects':len(files),'pack_bytes':sum(p.stat().st_size for p in packs),'pack_files':len(packs),'disk_bytes':disk_bytes(repo)}
def main():
    p=argparse.ArgumentParser();p.add_argument('--radius',type=int,default=1136);a=p.parse_args()
    dest=WORK/'large';dest.mkdir(exist_ok=True);result={'locked':True,'radius':a.radius}
    with bench_lock():
      run=prepare('26.2','large');plugins=run/'plugins';plugins.mkdir(exist_ok=True)
      shutil.copy2(WORK/'downloads/Chunky-Bukkit-1.5.3.jar',plugins/'Chunky.jar')
      with Server('26.2',run,dest/'pregen-server.log','3G') as s:
        result['pregen']=pregenerate(s,-a.radius,-a.radius,a.radius-1,a.radius-1,'initial')
      result['world_disk_bytes']=disk_bytes(run/'world');write_json(dest/'results.json',result)
      for name,args in [('original',[]),('stream',['--stream'])]:
        repo=dest/(name+'.git')
        out,timing=tool(['init',run,repo,*args],f'large-init-{name}',xmx='1G')
        root=subprocess.check_output(['git','--git-dir='+str(repo),'rev-parse','main^{tree}'],text=True).strip()
        result[name]={'init':{**timing,**parse_summary(out)},'loose':sizes(repo),'root':root}
        # 傳輸使用 gc 後資料；兩個 init 的 loose 空間不必同時保留。
        out,t=tool(['gc',repo],f'large-gc-{name}',xmx='1G');result[name]['gc']={**t,**sizes(repo)}
        write_json(dest/'results.json',result);emit(event='init',variant=name,result=result[name]);guard()
      if result['original']['root']!=result['stream']['root']:raise RuntimeError('init trees differ')
      repo=dest/'stream.git'
      result['init_head']=subprocess.check_output(['git','--git-dir='+str(repo),'rev-parse','main'],text=True).strip()
      with Server('26.2',run,dest/'changes-server.log','3G') as s:
        s.cmd('tick freeze');s.cmd('forceload add 480 480');time.sleep(2);s.cmd('setblock 480 250 480 gold_block','Changed the block');s.cmd('save-all flush','Saved the game');s.cmd('save-off')
        result['small']=capture(repo,run,'large-small',['--stream']);write_json(dest/'results.json',result)
        s.cmd('save-on');s.cmd('tick unfreeze');result['exploration_generation']=pregenerate(s,2048,2048,2303,2303,'exploration')
        s.cmd('tick freeze');s.cmd('save-all flush','Saved the game');s.cmd('save-off')
        result['exploration']=capture(repo,run,'large-exploration',['--stream']);write_json(dest/'results.json',result)
      out,t=tool(['gc',repo],'large-final-gc');result['final_gc']={**t,**sizes(repo)}
      out,t=tool(['stats',repo],'large-stats');result['stats_log']='logs/large-stats.out'
      r=subprocess.run(['git','--git-dir='+str(repo),'fsck','--full'],text=True,capture_output=True);result['fsck']={'returncode':r.returncode,'out':r.stdout+r.stderr}
      if r.returncode:raise RuntimeError('fsck failed')
      result['disk_bytes']=guard();write_json(dest/'results.json',result);emit(event='large_complete',result=result)
if __name__=='__main__':main()
