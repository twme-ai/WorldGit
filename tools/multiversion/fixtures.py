#!/usr/bin/env python3
"""以真正原版伺服器產生小世界與 registry 代表內容。世界／jar 只存在 .work。自行持鎖。"""
import argparse,fcntl,json,pathlib,subprocess,socket,time,threading,shutil,hashlib
ROOT=pathlib.Path(__file__).resolve().parents[2]
VERSIONS=['1.20.1','1.20.4','1.20.6','1.21.1','1.21.4','1.21.8','1.21.11','26.1.2','26.2','26.3']
def download(v):
 d=ROOT/'.work/versions'/v;d.mkdir(parents=True,exist_ok=True)
 def get(u):return subprocess.check_output(['curl','-fsSL','--retry','2','--max-time','120',u])
 meta=ROOT/'.work/versions'/(v+'.json')
 if not meta.exists():
  m=json.loads(get('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json'));url=next(x['url'] for x in m['versions'] if x['id']==v);meta.write_bytes(get(url))
 info=json.loads(meta.read_text())['downloads']['server'];jar=d/'server.jar'
 if not jar.exists() or hashlib.sha1(jar.read_bytes()).hexdigest()!=info['sha1']:jar.write_bytes(get(info['url']))
 assert hashlib.sha1(jar.read_bytes()).hexdigest()==info['sha1'];return jar
class Server:
 def __init__(self,v,base,log,extra=None,jar=None,freeze=False):
  self.freeze=freeze
  self.base=base;self.base.mkdir(parents=True,exist_ok=True);self.log=log;self.log.parent.mkdir(parents=True,exist_ok=True);self.lines=[]
  with socket.socket() as s:s.bind(('127.0.0.1',0));port=s.getsockname()[1]
  self.port=port
  (base/'eula.txt').write_text('eula=true\n');(base/'server.properties').write_text('online-mode=false\nserver-ip=127.0.0.1\nserver-port='+str(port)+'\nview-distance=2\nsimulation-distance=2\nspawn-chunks=0\nspawn-chunk-radius=0\nlevel-type=minecraft:flat\nlevel-seed=123\ngenerator-settings={"biome":"minecraft:plains","layers":[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},{"block":"minecraft:grass_block","height":1}]}\nwhite-list=false\nmax-tick-time=120000\n')
  java='/usr/lib/jvm/java-'+('25' if v.startswith('26.') else '21')+'-openjdk-amd64/bin/java'
  self.proc=subprocess.Popen([java,'-Xmx1200M','-XX:ActiveProcessorCount=3','-jar',str(jar or download(v)),'nogui']+(extra or []),cwd=base,stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
  def reader():
   with self.log.open('w') as out:
    for line in self.proc.stdout:self.lines.append(line);out.write(line);out.flush()
  self.thread=threading.Thread(target=reader);self.thread.start()
  start=time.monotonic()
  while time.monotonic()-start<180:
   if self.proc.poll() is not None:raise RuntimeError('server exited: '+str(log))
   if any('Done (' in x for x in self.lines):break
   time.sleep(.5)
  else:self.stop();raise RuntimeError('server timeout: '+str(log))
  if freeze:self.send('tick freeze')
  self.send('say WG_READY')
  while time.monotonic()-start<180:
   if any('WG_READY' in x for x in self.lines):break
   if self.proc.poll() is not None:raise RuntimeError('server exited: '+str(log))
   time.sleep(.1)
  else:self.stop();raise RuntimeError('server command timeout: '+str(log))
  if freeze and not any('The game is frozen' in x for x in self.lines):
   self.stop();raise RuntimeError('server did not acknowledge tick freeze: '+str(log))
 def send(self,s):self.proc.stdin.write(s+'\n');self.proc.stdin.flush()
 def stop(self):
  if self.proc.poll() is None:
   if self.freeze:
    self.send('forceload remove all')
    self.send('save-all flush')
   self.send('stop')
   try:self.proc.wait(timeout=90)
   except subprocess.TimeoutExpired:self.proc.kill();self.proc.wait()
  self.thread.join();self.proc.stdin.close();self.proc.stdout.close()
 def __enter__(self):return self
 def __exit__(self,*args):self.stop()
def commands(v):
 legacy=v in ['1.20.1','1.20.4'];newtext=v not in ['1.20.1','1.20.4','1.20.6','1.21.1','1.21.4'];newrules=v.startswith('26.') or v=='1.21.11'
 cmds=['gamerule '+('minecraft:spawn_mobs' if newrules else 'doMobSpawning')+' false','gamerule '+('minecraft:advance_time' if newrules else 'doDaylightCycle')+' false','gamerule '+('minecraft:random_tick_speed' if newrules else 'randomTickSpeed')+' 0','forceload add 0 0 31 31','fill 0 63 0 15 63 15 minecraft:stone','setblock 1 64 1 minecraft:oak_sign','setblock 2 64 1 minecraft:furnace','fillbiome 0 64 0 15 79 15 minecraft:forest']
 if legacy:item='{Slot:0b,id:"minecraft:diamond_sword",Count:1b,tag:{Enchantments:[{id:"minecraft:sharpness",lvl:3s}],display:{Name:\'{"text":"WorldGit sword"}\'},WorldGitFixture:{number:42}}}'
 else:
  name='{text:"WorldGit sword"}' if newtext else '\'{"text":"WorldGit sword"}\'';ench='{"minecraft:sharpness":3}' if newtext else '{levels:{"minecraft:sharpness":3}}'
  item='{Slot:0b,id:"minecraft:diamond_sword",count:1,components:{"minecraft:enchantments":'+ench+',"minecraft:custom_name":'+name+',"minecraft:custom_data":{WorldGitFixture:{number:42}}}}'
 cmds+=['setblock 0 64 0 minecraft:chest{Items:['+item+']}','summon minecraft:armor_stand 4 64 4 {UUID:[I;1,2,3,4],NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b,CustomName:'+('{text:"WorldGit stand"}' if newtext else '\'{"text":"WorldGit stand"}\'')+'}']
 if v not in ['1.20.1','1.20.4','1.20.6']:cmds+=['setblock 5 64 5 minecraft:crafter','setblock 6 64 5 minecraft:copper_bulb']
 if v not in ['1.20.1','1.20.4','1.20.6','1.21.1']:cmds+=['setblock 7 64 5 minecraft:pale_oak_log','setblock 8 64 5 minecraft:creaking_heart']
 if v in ['1.21.11','26.1.2','26.2','26.3']:cmds+=['setblock 9 64 5 minecraft:copper_chest','setblock 10 64 5 minecraft:oak_shelf']
 if v in ['26.2','26.3']:cmds+=['setblock 11 64 5 minecraft:sulfur','summon minecraft:sulfur_cube 12 65 5 {PersistenceRequired:1b,NoAI:1b,NoGravity:1b}']
 # 26.3 的 registry 差集加入一種實際存在的新方塊／物品，避免把 release 名稱當內容假設。
 if v=='26.3':
  try:
   import yaml
   a=yaml.safe_load((ROOT/'core/src/main/resources/org/worldgit/versions/5023.yml').read_text());b=yaml.safe_load((ROOT/'core/src/main/resources/org/worldgit/versions/4903.yml').read_text());new=sorted(set(a['blocks'])-set(b['blocks']));cmds+=['setblock 13 64 5 '+new[0], 'fillbiome 16 64 0 31 79 15 minecraft:dappled_forest', 'summon minecraft:cushion 14 64 5 {Pos:[14.5d,64.0d,5.5d],block_pos:[I;14,64,5],CustomName:{text:"New cushion"},PersistenceRequired:1b}', 'item replace block 0 64 0 container.1 with minecraft:black_cushion', 'setblock 3 64 1 minecraft:decorated_pot{sherds:{back:{id:"minecraft:brick"},left:{id:"minecraft:archer_pottery_sherd"},right:{id:"minecraft:brick"},front:{id:"minecraft:brick"}}}']
  except FileNotFoundError:pass
 return cmds+['save-all flush']
def main():
 p=argparse.ArgumentParser();p.add_argument('versions',nargs='*');p.add_argument('--replace',action='store_true');a=p.parse_args()
 with (ROOT/'.work/bench.lock').open('w') as lock:
  fcntl.flock(lock,fcntl.LOCK_EX)
  for v in a.versions or VERSIONS:
   base=ROOT/'.work/worlds'/v/'multiversion';
   if (base/'world/level.dat').exists():
    if not a.replace:print(v,'existing',flush=True);continue
    shutil.rmtree(base)
   try:
    with Server(v,base,ROOT/'.work/multiversion'/('fixture-'+v+'.log')) as server:
     for cmd in commands(v):server.send(cmd)
     time.sleep(3)
    failures=[x for x in server.lines if any(s in x for s in ['Unknown or incomplete command','Unknown block type','Invalid item','Failed to parse','/ERROR]' ])]
    if failures:raise RuntimeError('fixture command errors: '+''.join(failures))
    print(v,'created',flush=True)
    for name in ['libraries','versions']:shutil.rmtree(base/name,ignore_errors=True)
   except Exception as e:print(v,'FAILED',str(e),flush=True);raise
if __name__=='__main__':main()
