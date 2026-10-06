#!/usr/bin/env python3
"""Phase5 原版客戶端截圖；自持 bench.lock，沿用 Phase4 同一 Xvfb／GameTest runner。"""
import argparse,importlib.util,json,os,re,shutil,time,traceback,signal
from pathlib import Path
import harness
ROOT=Path(harness.ROOT)
def terminate(signum,frame):raise KeyboardInterrupt('Phase5 screenshots terminated')
signal.signal(signal.SIGTERM,terminate)

def capture(server,work,version):
    spec=importlib.util.spec_from_file_location('client_process',ROOT/'fabric/tools/accept-paper.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
    project='mc1_21_11' if version=='1.21.11' else 'mc26_2';ready=work/'screenshot-ready.json';init=work/'screenshot.init.gradle'
    init.write_text('''allprojects { p -> p.afterEvaluate {
      if (p.path == ':fabric:PROJECT') {
        p.sourceSets.gametest.java.srcDir(new File(rootProject.projectDir, 'paper/tools/phase5-client'))
        p.tasks.named('processGametestResources') {
          inputs.property('paperPhase5ScreenshotFixture', true)
          doLast { new File(destinationDir, 'fabric.mod.json').text = '{"schemaVersion":1,"id":"worldgit-gametest","version":"1.0.0","name":"Paper Phase5 evidence","environment":"client","entrypoints":{"fabric-client-gametest":["org.worldgit.fabric.gametest.Phase5GameTest"]},"depends":{"worldgit":"*","fabric-api":"*"}}' }
        }
      }
    }}'''.replace('PROJECT',project))
    tmp=ROOT/'.work/fabric-tmp';tmp.mkdir(exist_ok=True)
    env={**os.environ,'JAVA_HOME':'/usr/lib/jvm/java-25-openjdk-amd64','GRADLE_USER_HOME':str(ROOT/'.work/gradle-home'),'npm_config_cache':str(ROOT/'.work/npm-cache'),'PLAYWRIGHT_BROWSERS_PATH':str(ROOT/'.work/ms-playwright'),'ALSOFT_DRIVERS':'null','LIBGL_ALWAYS_SOFTWARE':'1','GALLIUM_DRIVER':'llvmpipe','LP_NUM_THREADS':'3','XDG_CACHE_HOME':str(tmp/'cache'),'XDG_CONFIG_HOME':str(tmp/'config'),'TMPDIR':str(tmp)}
    start=time.time();client=None
    try:
      client=m.Process(['xvfb-run','-a','-s','-screen 0 1280x720x24 -ac','./gradlew','--no-daemon','--configure-on-demand','--max-workers=1','-I',str(init),'-PwgtestPaperPort='+str(server.port),'-PwgtestPaperReady='+str(ready),':fabric:'+project+':runClientGameTest'],ROOT,work/'screenshot-client.log',env)
      line=client.wait('PAPER5 connected=',600);player=re.search(r'PAPER5 connected=(\S+)',line)[1]
      server.cmd('op '+player);time.sleep(1);ready.write_text('{}')
      client.wait('PAPER5 DONE',900);client.proc.wait(90)
      if client.proc.returncode:raise RuntimeError('screenshot client failed')
      dest=ROOT/'paper/docs/screenshots/phase5';dest.mkdir(parents=True,exist_ok=True);files=[]
      shots=ROOT/'.work/worlds/fabric-gametest'/f'{version}-paper/screenshots'
      for label in ['init-buttons','graph','bossbar-progress','bossbar-terminal','completion','error-copy-hover','ignore-gui','ignore-preview']:
        found=sorted((p for p in shots.glob('*phase5-'+label+'*.png') if p.stat().st_mtime>=start),key=lambda p:p.stat().st_mtime_ns)
        if not found:raise RuntimeError('missing screenshot '+label)
        target=dest/(server.platform+'-'+version+'-'+label+'.png');shutil.copy2(found[-1],target);files.append(str(target.relative_to(ROOT)))
      return {'files':files,'player':player}
    finally:
      if client:client.stop()
      init.unlink(missing_ok=True);ready.unlink(missing_ok=True)

def run(platform,version):
    out=ROOT/'.work/paper-phase5'/f'screens-{platform}-{version}-{int(time.time())}';out.mkdir(parents=True);server=None;result={'success':False}
    try:
      with harness.BenchLock():
        try:
          harness.RUN=str(ROOT/'.work/paper-phase5/run')
          server=harness.Server(platform,version,baseline=str(ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+version)),run_label=out.name,view=2,xmx='1500M',all_entities=False,config={'language':'en_us','auto-commit':{'enabled':False,'on-shutdown':False},'commit':{'chunks-per-tick':1}})
          server.start();server.cmd('wg debug freeze on','WGFREEZE frozen');result.update(capture(server,out,version));result['success']=True
        finally:
          if server:server.stop();shutil.rmtree(server.dir,ignore_errors=True)
    except BaseException:result['error']=traceback.format_exc();print(result['error'],flush=True)
    (out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n');print(out/'results.json',flush=True);return result['success']
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('platform',choices=['paper','folia']);parser.add_argument('version',choices=['1.21.11','26.2']);args=parser.parse_args();raise SystemExit(0 if run(args.platform,args.version) else 1)
