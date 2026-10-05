#!/usr/bin/env python3
"""Paper Brigadier 真客戶端指令樹／補全／tooltip／紅字／權限證據；自持 bench.lock，finally 清理。"""
import argparse, hashlib, importlib.util, json, os, re, shutil, subprocess, time, traceback
from pathlib import Path
import harness
from cli_compat import player_command
from phase4 import Hub, TOKEN
ROOT=Path(harness.ROOT)
LABELS=['root','switch-tooltip','resolve-tooltip','restore-relative','restore-complete','pr-tooltip','integer-error','coordinate-error','no-permission-root']

def client_module():
    spec=importlib.util.spec_from_file_location('client_process',ROOT/'fabric/tools/accept-paper.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module);return module

def init_script(path,project):
    path.write_text('''allprojects { p ->
      p.afterEvaluate {
        if (p.path == ':fabric:PROJECT') {
          p.sourceSets.gametest.java.srcDir(new File(rootProject.projectDir, 'paper/tools/brigadier-fixture'))
          p.tasks.named('processGametestResources') {
            inputs.property('paperBrigadierFixture', true)
            doLast {
              new File(destinationDir, 'fabric.mod.json').text = '{"schemaVersion":1,"id":"worldgit-gametest","version":"1.0.0","name":"Paper Brigadier evidence","environment":"client","entrypoints":{"fabric-client-gametest":["org.worldgit.fabric.gametest.BrigadierGameTest"]},"depends":{"worldgit":"*","fabric-api":"*"}}'
            }
          }
        }
      }
    }'''.replace('PROJECT',project))

def run(args):
    work=ROOT/'.work/brigadier'/f'paper-{args.version}-{int(time.time())}';work.mkdir(parents=True)
    result={'version':args.version,'success':False,'files':[]};server=hub=client=None
    project='mc1_21_11' if args.version=='1.21.11' else 'mc26_2';ready=work/'ready';init=work/'client.init.gradle';init_script(init,project)
    with harness.BenchLock():
      try:
        for src,target in [('hub/build/libs/worldgit-hub.jar','hub.jar'),('paper/plugin/build/libs/worldgit-paper-0.1.0-SNAPSHOT.jar','paper.jar')]:shutil.copy2(ROOT/src,work/target)
        result['plugin_sha256']=hashlib.sha256((work/'paper.jar').read_bytes()).hexdigest()
        hub=Hub(work);slug='brigadier-'+args.version.replace('.','-');hub.api('POST','/worlds',{'name':slug,'isPublic':False})
        url=hub.base+'/admin/'+slug
        baseline=ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+args.version)
        if not baseline.is_dir():raise RuntimeError('缺少既有 Phase4 flat baseline：'+str(baseline))
        harness.PORTS['paper-'+args.version]=25742 if args.version=='26.2' else 25741
        harness.RUN=str(ROOT/'.work/servers/brigadier-runs')
        server=harness.Server('paper',args.version,baseline=str(baseline),run_label=work.name,view=2,xmx='1500M',
          config={'language':'en_us','auto-commit':{'enabled':False,'on-quit':False,'on-shutdown':False},'remote':{'timeout-seconds':3}})
        shutil.copy2(work/'paper.jar',Path(server.dir)/'plugins/worldgit-paper.jar')
        cred=Path(server.dir)/'plugins/WorldGit/credentials.yml';cred.write_text('credentials:\n  '+hub.base+':\n    mode: bearer\n    token: '+TOKEN+'\n');cred.chmod(0o600)
        server.start()
        def command(text,pattern,timeout=300):
            output=server.cmd(text,pattern+'|Error:|Hub (?:400|401|403|404|409)|Unknown or incomplete command|That position is not loaded',timeout)
            if re.search(r'Hub (?:400|401|403|404|409)',output) or any(error in output for error in ['Error:', 'Unknown or incomplete command', 'That position is not loaded']):raise AssertionError(text+'\n'+output)
            print(text+' → '+output.splitlines()[-1],flush=True);return output
        dimension_bots={dimension:server.bot(name) for dimension,name in [('minecraft:the_nether','WgBot3'),('minecraft:the_end','WgBot4')]}
        for dimension,bot in dimension_bots.items():
            server.cmd('gamemode creative '+bot.name)
            command('wg debug comment-teleport '+bot.name+' '+dimension,'WGCOMMENTTP success=true')
        time.sleep(10)
        command('forceload add 0 0','Marked chunk|already marked')
        time.sleep(2)
        command('wg debug freeze on','WGFREEZE frozen')
        command('wg init','Initialized|Initialization complete')
        command('wg branch topic','Branches')
        for bot in dimension_bots.values():player_command(bot,'wg branch topic','Branches')
        command('setblock 0 224 0 gold_block','Changed the block')
        command('wg commit -m Main tooltip message','Snapshot:|overworld [a-f0-9]{8}')
        command('wg switch topic','Switched')
        command('setblock 0 224 0 diamond_block','Changed the block')
        command('wg commit -m Feature tooltip <red> literal','Snapshot:|overworld [a-f0-9]{8}')
        command('wg switch main','Switched')
        command('wg remote add origin '+url,'updated')
        command('wg push','push complete')
        command('wg push origin topic','push complete')
        for bot in dimension_bots.values():
            player_command(bot,'wg remote add origin '+url,'updated')
            player_command(bot,'wg push','push complete')
            player_command(bot,'wg push origin topic','push complete')
        command('wg pr create --source topic --target main Brigadier tooltip PR','PR #1')
        command('wg merge topic','MERGING')
        # 以來源原點取得 ~ 座標，只做 dry-run，不改 MERGING 中的世界。
        command('wg reload','reloaded')
        tmp=ROOT/'.work/fabric-tmp';tmp.mkdir(exist_ok=True)
        env={**os.environ,'JAVA_HOME':'/usr/lib/jvm/java-25-openjdk-amd64','GRADLE_USER_HOME':str(ROOT/'.work/gradle-home'),
          'npm_config_cache':str(ROOT/'.work/npm-cache'),'PLAYWRIGHT_BROWSERS_PATH':str(ROOT/'.work/ms-playwright'),
          'ALSOFT_DRIVERS':'null','LIBGL_ALWAYS_SOFTWARE':'1','GALLIUM_DRIVER':'llvmpipe','LP_NUM_THREADS':'3',
          'XDG_CACHE_HOME':str(tmp/'cache'),'XDG_CONFIG_HOME':str(tmp/'config'),'TMPDIR':str(tmp)}
        start=time.time();client=client_module().Process(['xvfb-run','-a','-s','-screen 0 1280x720x24 -ac','./gradlew','--no-daemon','--configure-on-demand','--max-workers=1','-I',str(init),
          '-PwgtestPaperPort='+str(server.port),'-PwgtestPaperReady='+str(ready),':fabric:'+project+':runClientGameTest'],ROOT,work/'client.log',env)
        line=client.wait('BRIGADIER connected=',600);player=re.search(r'connected=(\S+)',line)[1];server.cmd('op '+player)
        command('wg debug comment-camera '+player,'WGCOMMENTCAM success=true');command('wg debug freeze off','WGFREEZE restored')
        ready.write_text('{}');client.wait('BRIGADIER deop-ready',600);server.cmd('deop '+player);Path(str(ready)+'.deop').write_text('{}')
        client.wait('BRIGADIER DONE',300);client.proc.wait(90)
        if client.proc.returncode:raise RuntimeError('真客戶端 fixture 失敗')
        dest=ROOT/'paper/docs/screenshots/brigadier';dest.mkdir(parents=True,exist_ok=True)
        shots=ROOT/'.work/worlds/fabric-gametest'/f'{args.version}-paper/screenshots'
        for label in LABELS:
          matches=sorted((p for p in shots.glob('*brigadier-'+label+'*.png') if p.stat().st_mtime>=start),key=lambda p:p.stat().st_mtime_ns)
          if not matches:raise RuntimeError('缺少截圖 '+label)
          target=dest/('paper-'+args.version+'-'+label+'.png');shutil.copy2(matches[-1],target);result['files'].append(str(target.relative_to(ROOT)))
        result['evidence']=[line for line in client.lines if 'BRIGADIER ' in line];result['success']=True
      except BaseException:
        result['error']=traceback.format_exc().replace(TOKEN,'[REDACTED]');print(result['error'],flush=True)
      finally:
        if client:client.stop()
        if server:
          server.stop();result['server_problems']=server.problems();shutil.rmtree(server.dir,ignore_errors=True)
        if hub:hub.stop()
        for name in ['hub.jar','paper.jar']:(work/name).unlink(missing_ok=True)
        shutil.rmtree(work/'hub-data',ignore_errors=True)
        init.unlink(missing_ok=True);ready.unlink(missing_ok=True);Path(str(ready)+'.deop').unlink(missing_ok=True)
        (work/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        print(work/'results.json',flush=True)
    return result['success']
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('version',choices=['1.21.11','26.2'])
    raise SystemExit(0 if run(parser.parse_args()) else 1)
