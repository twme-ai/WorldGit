"""由 Phase4 driver 持 bench.lock 呼叫。真客戶端 fixture 存 paper/tools，Fabric 原始碼不變。"""
import importlib.util, json, os, re, shutil, subprocess, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]

def capture(server,work,version):
    spec=importlib.util.spec_from_file_location('client_process',ROOT/'fabric/tools/accept-paper.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m)
    project='mc1_21_11' if version=='1.21.11' else 'mc26_2';ready=work/'screenshot-ready.json';view_ready=Path(str(ready)+'.view');init=work/'screenshot.init.gradle'
    init.write_text('''allprojects { p ->
      p.afterEvaluate {
        if (p.path == ':fabric:PROJECT') {
          p.sourceSets.gametest.java.srcDir(new File(rootProject.projectDir, 'paper/tools/fixtures'))
          p.tasks.named('processGametestResources') {
            inputs.property('paperPhase4ScreenshotFixture', true)
            doLast {
              new File(destinationDir, 'fabric.mod.json').text = '{"schemaVersion":1,"id":"worldgit-gametest","version":"1.0.0","name":"Paper Phase4 evidence","environment":"client","entrypoints":{"fabric-client-gametest":["org.worldgit.fabric.gametest.Phase4DisplayGameTest"]},"depends":{"worldgit":"*","fabric-api":"*"}}'
            }
          }
        }
      }
    }'''.replace('PROJECT',project))
    tmp=ROOT/'.work/fabric-tmp';tmp.mkdir(exist_ok=True)
    env={**os.environ,'JAVA_HOME':'/usr/lib/jvm/java-25-openjdk-amd64','GRADLE_USER_HOME':str(ROOT/'.work/gradle-home'),'ALSOFT_DRIVERS':'null','LIBGL_ALWAYS_SOFTWARE':'1','GALLIUM_DRIVER':'llvmpipe','LP_NUM_THREADS':'3','XDG_CACHE_HOME':str(tmp/'cache'),'XDG_CONFIG_HOME':str(tmp/'config'),'TMPDIR':str(tmp)}
    start=time.time();client=None;thawed=False
    try:
      client=m.Process(['xvfb-run','-a','-s','-screen 0 1280x720x24 -ac','./gradlew','--no-daemon','--configure-on-demand','--max-workers=1','-I',str(init),'-PwgtestPaperPort='+str(server.port),'-PwgtestPaperReady='+str(ready),':fabric:'+project+':runClientGameTest'],ROOT,work/'screenshot-client.log',env)
      line=client.wait('PAPER4 connected=',600);player=re.search(r'PAPER4 connected=(\S+)',line)[1]
      server.cmd('op '+player)
      # 由玩家 owner 設定真正 server flight/no-gravity 並 teleportAsync，避免 ability packet 與凍結狀態競爭。
      server.cmd('wg debug comment-camera '+player,r'WGCOMMENTCAM success=true');time.sleep(.5)
      ready.write_text('{}');client.wait('PAPER4 flying=true',120)
      # vanilla tick freeze 也停止客戶端 Display.tick 的 render state 初始化。
      # 截圖時暫時恢復真伺服器 tick，讓原版 renderer 畫出收到的文字；之後還原 fixture。
      server.cmd('wg debug freeze off',r'WGFREEZE restored');thawed=True;view_ready.write_text('{}')
      client.wait('PAPER4 DONE',600);client.proc.wait(90)
      if client.proc.returncode:raise RuntimeError('screenshot client failed')
      dest=ROOT/'paper/docs/screenshots/phase4';dest.mkdir(parents=True,exist_ok=True);files=[]
      shots=ROOT/'.work/worlds/fabric-gametest'/f'{version}-paper/screenshots'
      for label in ['phase4-comments-visible','phase4-comments-hidden']:
        found=sorted((p for p in shots.glob('*'+label+'*.png') if p.stat().st_mtime>=start),key=lambda p:p.stat().st_mtime_ns)
        if not found:raise RuntimeError('missing screenshot '+label)
        target=dest/(server.platform+'-'+version+'-'+label+'.png');shutil.copy2(found[-1],target);files.append(str(target.relative_to(ROOT)))
      return {'files':files,'literal':next(l for l in client.lines if 'PAPER4 literal=' in l),'player':player}
    finally:
      if client:client.stop()
      if thawed:server.cmd('wg debug freeze on',r'WGFREEZE frozen')
      init.unlink(missing_ok=True);ready.unlink(missing_ok=True);view_ready.unlink(missing_ok=True)
