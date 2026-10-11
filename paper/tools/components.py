#!/usr/bin/env python3
"""真 Paper／Folia components NMS pending ticks 回歸；自持 bench.lock。"""
import argparse,hashlib,json,os,shutil,signal,subprocess,time,traceback,zipfile
from pathlib import Path
import harness
ROOT=Path(harness.ROOT)
def terminate(signum,frame):raise KeyboardInterrupt('components runner terminated')
signal.signal(signal.SIGTERM,terminate)
def run(platform,version):
 out=ROOT/'.work/mv2-2/pending'/f'{platform}-{version}';out.mkdir(parents=True,exist_ok=True)
 result={'platform':platform,'version':version,'success':False};server=None
 with harness.BenchLock():
  try:
   baseline=out/'baseline';shutil.rmtree(baseline,ignore_errors=True)
   shutil.copytree(ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+version),baseline)
   subprocess.run([harness.JAVA[version],'-cp',str(ROOT/'cli/build/libs/wgit.jar'),str(ROOT/'paper/tools/components-fixture/PendingFixture.java'),str(baseline)],check=True)
   server=harness.Server(platform,version,baseline=str(baseline),run_label='mv2-pending-'+platform+'-'+version,view=2,config={'auto-commit':{'enabled':False,'on-shutdown':False}})
   jars=[ROOT/('paper/v'+version.replace('.','_')+'/.gradle/caches/paperweight/taskCache/mappedServerJar.jar'),Path(harness.plugin_jar())]
   base=ROOT/'.work/gradle-home/caches/modules-2/files-2.1'
   jars.append(next((base/'io.papermc.paper/paper-api'/(version+'-R0.1-SNAPSHOT')).rglob('*.jar')))
   for group in ['com.mojang/brigadier','com.mojang/datafixerupper','com.mojang/authlib','com.mojang/logging','it.unimi.dsi/fastutil','org.jetbrains/annotations','org.joml/joml','net.kyori/adventure-api','net.kyori/adventure-key','net.kyori/examination-api','net.kyori/examination-string','net.md-5/bungeecord-chat']:
    jars+=list((base/group).rglob('*.jar'))
   classes=out/'classes';classes.mkdir(exist_ok=True)
   subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/javac','--release','21','-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),str(ROOT/'paper/tools/components-fixture/ComponentsFixture.java')],check=True)
   with zipfile.ZipFile(Path(server.dir)/'plugins/components-fixture.jar','w') as jar:
    for p in classes.rglob('*.class'):jar.write(p,str(p.relative_to(classes)))
    jar.writestr('META-INF/MANIFEST.MF','Manifest-Version: 1.0\npaperweight-mappings-namespace: mojang\n\n')
    jar.writestr('plugin.yml','name: ComponentsFixture\nversion: 1\nmain: org.worldgit.fixture.ComponentsFixture\napi-version: "1.20"\nfolia-supported: true\ndepend: [WorldGit]\ncommands:\n  wgcomponents:\n    description: Pending ticks acceptance\n')
   result['plugin_sha256']=hashlib.sha256(Path(harness.plugin_jar()).read_bytes()).hexdigest()
   server.start();result['console']=str(Path(server.evidence_log).relative_to(ROOT))
   result['output']=server.cmd('wgcomponents',r'WGCOMPONENTS (success|failed)',180)
   assert 'WGCOMPONENTS success pending-replace=true unpack=true delay=600 priorities=-1,1' in result['output'],result['output']
   assert not any('org.worldgit' in line and 'Exception' in line for line in server.lines_since()),server.problems()
   result['success']=True
  except BaseException:result['error']=traceback.format_exc();print(result['error'],flush=True)
  finally:
   if server:server.stop();shutil.rmtree(server.dir,ignore_errors=True)
   shutil.rmtree(out/'baseline',ignore_errors=True);shutil.rmtree(out/'classes',ignore_errors=True)
   (out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(platform,version,result['success'],flush=True)
 return result['success']
if __name__=='__main__':
 parser=argparse.ArgumentParser();parser.add_argument('platform',choices=['paper','folia']);parser.add_argument('version',choices=['1.20.6','1.21.1']);args=parser.parse_args()
 if (args.platform,args.version)==('folia','1.21.1'):parser.error('Folia 1.21.1 官方沒有 build，不可驗收')
 raise SystemExit(0 if run(args.platform,args.version) else 1)
