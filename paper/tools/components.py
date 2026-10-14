#!/usr/bin/env python3
"""真 Paper／Folia components NMS pending ticks 回歸；自持 bench.lock。"""
import argparse,hashlib,json,os,shutil,signal,subprocess,time,traceback,zipfile
from pathlib import Path
import harness
ROOT=Path(harness.ROOT)
def terminate(signum,frame):raise KeyboardInterrupt('components runner terminated')
signal.signal(signal.SIGTERM,terminate)
def run(platform,version,output_root,shutdown_held=False):
 out=output_root/f'{platform}-{version}';out.mkdir(parents=True,exist_ok=True)
 result={'platform':platform,'version':version,'success':False};server=None
 with harness.BenchLock():
  try:
   baseline=out/'baseline';shutil.rmtree(baseline,ignore_errors=True)
   shutil.copytree(ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+version),baseline)
   subprocess.run([harness.JAVA[version],'-cp',str(ROOT/'cli/build/libs/wgit.jar'),str(ROOT/'paper/tools/components-fixture/PendingFixture.java'),str(baseline)],check=True)
   server=harness.Server(platform,version,baseline=str(baseline),run_label='mv2-pending-'+platform+'-'+version,view=2,config={'auto-commit':{'enabled':False,'on-shutdown':False}})
   jars=[ROOT/('paper/v'+version.replace('.','_')+'/.gradle/caches/paperweight/taskCache/mappedServerJar.jar'),Path(harness.plugin_jar())]
   base=ROOT/'.work/gradle-home/caches/modules-2/files-2.1'
   jars.append(next((base/'io.papermc.paper/paper-api'/(version+'-R0.1-SNAPSHOT' if not version.startswith('26.') else {'26.1.2':'26.1.2.build.74-stable','26.2':'26.2.build.129-stable','26.3':'26.3.build.177-stable'}[version])).rglob('*.jar')))
   for group in ['com.mojang/brigadier','com.mojang/datafixerupper','com.mojang/authlib','com.mojang/logging','it.unimi.dsi/fastutil','org.jetbrains/annotations','org.joml/joml','net.kyori/adventure-api','net.kyori/adventure-key','net.kyori/examination-api','net.kyori/examination-string','net.md-5/bungeecord-chat']:
    jars+=list((base/group).rglob('*.jar'))
   classes=out/'classes';classes.mkdir(exist_ok=True)
   source=ROOT/'paper/tools/components-fixture/ComponentsFixture.java'
   legacy=version in ('1.20.1','1.20.4')
   if version not in ('1.20.1','1.20.4','1.20.6','1.21.1'):
    source=out/'ComponentsFixture.java'
    source.write_text((ROOT/'paper/tools/components-fixture/ComponentsFixture.java').read_text().replace('BuiltInRegistries.BLOCK.get(', 'BuiltInRegistries.BLOCK.getValue(').replace('BuiltInRegistries.FLUID.get(', 'BuiltInRegistries.FLUID.getValue('))
   if version.startswith('26.'):
    source.write_text(source.read_text().replace('net.minecraft.resources.ResourceLocation','net.minecraft.resources.Identifier'))
   mapping=jars[0].with_name('reobfMappings.tiny')
   if legacy:
    text=source.read_text().replace('org.bukkit.craftbukkit.CraftWorld','org.bukkit.craftbukkit.'+('v1_20_R1' if version=='1.20.1' else 'v1_20_R3')+'.CraftWorld').replace('values.getFirst()','values.get(0)')
    owner=None;fields={}
    for line in mapping.read_text().splitlines():
     row=line.split('\t')
     if row[0]=='c':owner=row[1]
     elif len(row)>4 and row[1]=='f':fields[(owner,row[3])]=row[4]
    for owner,name in [('net/minecraft/world/ticks/LevelChunkTicks','pendingTicks'),('net/minecraft/world/level/chunk/LevelChunk','blockTicks'),('net/minecraft/world/level/chunk/LevelChunk','fluidTicks')]:
     text=text.replace('"'+name+'"','"'+fields[(owner,name)]+'"')
    source=out/'ComponentsFixture.java';source.write_text(text)
   subprocess.run([str(Path(harness.JAVA[version]).with_name('javac')),'--release','25' if version.startswith('26.') else '17' if legacy else '21','-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),str(source)],check=True)
   fixture=Path(server.dir)/'plugins/components-fixture.jar'
   with zipfile.ZipFile(fixture,'w') as jar:
    for p in classes.rglob('*.class'):jar.write(p,str(p.relative_to(classes)))
    jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n\n')
    jar.writestr('plugin.yml','name: ComponentsFixture\nversion: 1\nmain: org.worldgit.fixture.ComponentsFixture\napi-version: "1.20"\nfolia-supported: true\ndepend: [WorldGit]\ncommands:\n  wgcomponents:\n    description: Pending ticks acceptance\n')
   if legacy:
    remapper=next((base/'net.fabricmc/tiny-remapper/0.12.0').rglob('*-fat.jar'))
    reobf=out/'components-fixture-reobf.jar'
    subprocess.run([harness.JAVA[version],'-Xmx512m','-cp',str(remapper),'net.fabricmc.tinyremapper.Main',str(fixture),str(reobf),str(mapping),'mojang+yarn','spigot',str(jars[0]),'--threads=1'],check=True)
    shutil.move(reobf,fixture)
   result['plugin_sha256']=hashlib.sha256(Path(harness.plugin_jar()).read_bytes()).hexdigest()
   server.start();result['console']=str(Path(server.evidence_log).relative_to(ROOT))
   result['output']=server.cmd('wgcomponents'+(' shutdown' if shutdown_held else ''),r'WGCOMPONENTS (success|failed)',180)
   assert 'WGCOMPONENTS success pending-replace=true unpack=true delay=600 priorities=-1,1' in result['output'],result['output']
   assert not any('org.worldgit' in line and 'Exception' in line for line in server.lines_since()),server.problems()
   if shutdown_held:
    assert 'shutdown-held=true' in result['output']
    server.stop()
    verified=subprocess.run([harness.JAVA[version],'-Xmx512m','-cp',str(ROOT/'cli/build/libs/wgit.jar'),str(ROOT/'paper/tools/components-fixture/VerifyPendingTicks.java'),str(server.world)],check=True,text=True,capture_output=True)
    result['shutdown_disk']=verified.stdout
    assert 'WGPENDINGDISK success delay=600 priorities=-1,1' in verified.stdout,verified.stdout
    assert not any('Exception' in line or 'ERROR' in line for line in server.lines_since()),server.problems()
   result['success']=True
  except BaseException as error:
   result['native_error']=getattr(error,'stderr','');result['native_output']=getattr(error,'stdout','')
   result['error']=traceback.format_exc();print(result['error']+'\n'+str(result['native_output'])+'\n'+str(result['native_error']),flush=True)
  finally:
   if server:server.stop();shutil.rmtree(server.dir,ignore_errors=True)
   shutil.rmtree(out/'baseline',ignore_errors=True);shutil.rmtree(out/'classes',ignore_errors=True)
   (out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(platform,version,result['success'],flush=True)
 return result['success']
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('platform',choices=['paper','folia']);parser.add_argument('version',choices=['1.20.1','1.20.4','1.20.6','1.21.1','1.21.4','1.21.8','1.21.11','26.1.2','26.2','26.3']);parser.add_argument('--output-root',type=Path,default=ROOT/'.work/paper-pending');parser.add_argument('--shutdown-held',action='store_true');args=parser.parse_args()
 if args.platform=='folia' and args.version in ('1.21.1','26.3'):parser.error(f'Folia {args.version} 官方沒有 build，不可驗收')
 raise SystemExit(0 if run(args.platform,args.version,args.output_root,args.shutdown_held) else 1)
