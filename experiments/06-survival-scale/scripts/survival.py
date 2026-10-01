#!/usr/bin/env python3
from common import *
import argparse
PROFILES={
 'tol0':['--tol','0'], 'tol2':['--tol','2'], 'tol4':['--tol','4'],
 'items':['--tol','2','--ignore',EXP/'ignore-items.wgignore'],
 'orbs':['--tol','2','--ignore',EXP/'ignore-orbs.wgignore'],
 'wild':['--tol','2','--ignore',EXP/'ignore-wild.wgignore'],
 'extra':['--tol','2','--extra-noise'],
 'combo':['--tol','2','--extra-noise','--ignore',EXP/'ignore-transient.wgignore']}
def setup(s):
    s.cmd('forceload add 0 0 64 32');time.sleep(2)
    for c in ['fill 0 189 0 64 189 32 stone','fill 0 190 0 64 194 32 air','fill 28 189 15 35 189 17 farmland[moisture=7]','fill 28 189 18 35 189 18 water','fill 27 190 19 35 190 22 oak_fence outline','fill 28 190 20 34 190 21 air','setblock 49 190 16 red_bed[part=foot,facing=south]','setblock 49 190 17 red_bed[part=head,facing=south]',*[f'setblock {8+i*13} 190 26 red_bed[part=foot,facing=south]' for i in range(4)],*[f'setblock {8+i*13} 190 27 red_bed[part=head,facing=south]' for i in range(4)],'summon cow 30 190 20 {PersistenceRequired:1b,CustomName:\'"ScaleCowA"\'}','summon cow 32 190 20 {PersistenceRequired:1b,CustomName:\'"ScaleCowB"\'}']:s.cmd(c)
def replenish(s):
    # 已知負載輸入：礦脈/樹幹補充與夜間敵人。不是玩家自然採集速率。
    for c in ['fill 9 190 10 12 190 12 stone','fill 15 190 10 15 192 10 oak_log','summon husk 48 190 23'] :s.cmd(c)
def kit(s,i):
    name='Scale'+str(i)
    s.cmd(f'gamemode survival {name}');s.cmd(f'spawnpoint {name} {8+i*13} 190 6');s.cmd(f'tp {name} {8+i*13} 190 6')
    for item,n in [('iron_pickaxe',1),('iron_axe',1),('iron_sword',1),('oak_planks',256),('wheat_seeds',64),('wheat',64),('chest',4),('cooked_beef',64)]:s.cmd(f'give {name} {item} {n}')
def main():
    p=argparse.ArgumentParser();p.add_argument('--version',default='26.2');p.add_argument('--minutes',type=float,default=30);p.add_argument('--control-minutes',type=float,default=5);p.add_argument('--interval',type=float,default=5);p.add_argument('--skip-no-player',action='store_true');a=p.parse_args()
    if a.minutes <= 0 or a.interval <= 0 or abs(a.minutes/a.interval-round(a.minutes/a.interval)) > 1e-8:p.error('minutes must be a positive multiple of interval')
    dest=WORK/('survival-'+a.version);dest.mkdir(parents=True,exist_ok=True)
    records=[];bots=[];botlogs=[]
    with bench_lock():
      run=prepare(a.version,'survival-'+a.version)
      try:
       with Server(a.version,run,dest/'server.log') as s:
        s.cmd('gamerule random_tick_speed');s.cmd('gamerule spawn_mobs');s.cmd('gamerule players_sleeping_percentage')
        def sample(tag):
            snap=dest/'snapshots'/tag;extra=snapshot_copy(s,run,snap)
            row={'tag':tag,'version':a.version,'locked':True,**extra,'profiles':{}}
            # 在全域鎖內逐一處理同一份唯讀 snapshot；--full 避免秒解析度漏報。
            for name,args in PROFILES.items():row['profiles'][name]=capture(dest/(name+'.git'),snap,a.version+'-'+tag+'-'+name,[*args,'--stream','--full'])
            row['disk_bytes']=guard();records.append(row);write_json(dest/'results.json',records);emit(event='sample',tag=tag,main=row['profiles']['tol2'])
        sample('00-baseline')
        if not a.skip_no_player:
            sleep_progress(a.control_minutes*60,'no-player');sample('01-no-player')
        setup(s)
        for i,role in enumerate(['explorer','miner','builder','keeper']):
            log=open(dest/(role+'.jsonl'),'w');botlogs.append(log)
            bot=subprocess.Popen(['node',str(EXP/'scripts/bots.js'),str(PORT[a.version]),a.version,'Scale'+str(i),role],stdin=subprocess.PIPE,stdout=log,stderr=subprocess.STDOUT,text=True);bots.append(bot)
            if not s.wait('Scale'+str(i)+' joined',60):raise RuntimeError('bot did not join '+role)
            kit(s,i)
        s.cmd('fill 9 190 10 12 190 12 stone');s.cmd('fill 15 190 10 15 192 10 oak_log');s.cmd('forceload remove all');sample('02-setup');sleep_progress(a.control_minutes*60,'standing');sample('03-standing')
        # explorer 在天然地形步行；其他 bot 在可重現的生存操作場景。
        for i in range(4):kit(s,i)
        replenish(s)
        s.cmd('execute as Scale0 at @s positioned 300 0 300 positioned over motion_blocking_no_leaves run tp @s ~ ~1 ~')
        for b in bots:b.stdin.write('active\n');b.stdin.flush()
        start=time.monotonic();steps=int(round(a.minutes/a.interval))
        for step in range(1,steps+1):
            target=start+step*a.interval*60
            while time.monotonic()<target:
                time.sleep(min(30,target-time.monotonic()))
                if (int(time.monotonic()-start)//60)%2==0:replenish(s)
                emit(event='progress',label='active',elapsed_s=round(time.monotonic()-start),step=step)
            # 給一次受控夜晚以驗證 bot 睡覺（時間不在此原型的 world-meta 追蹤中）。
            if step==2:
                s.cmd('time set night')
                for i in range(4):s.cmd(f'tp Scale{i} {8+i*13} 190 24')
            sample('active-'+str(step).zfill(2))
        for b in bots:b.stdin.write('idle\n');b.stdin.flush()
      finally:
        for b in bots:
            if b.poll() is None:
                try:b.stdin.write('quit\n');b.stdin.flush();b.wait(timeout=10)
                except Exception:b.kill();b.wait()
        for f in botlogs:f.close()
      gc=[]
      for name in PROFILES:
        repo=dest/(name+'.git');before=disk_bytes(repo)
        out,t=tool(['gc',repo],a.version+'-'+name+'-gc')
        gc.append({'profile':name,'before_disk':before,'after_disk':disk_bytes(repo),'size':parse_summary(out,'SIZE '),**t})
      write_json(dest/'gc.json',gc);emit(event='complete',version=a.version,disk=guard())
if __name__=='__main__':main()
