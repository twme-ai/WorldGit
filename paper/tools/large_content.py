#!/usr/bin/env python3
"""大型 entity／BE 線上抽樣與首次跨版 tree 投影；單 JVM、自持 bench.lock。"""
import argparse,hashlib,json,os,re,shutil,signal,statistics,subprocess,time,traceback,zipfile
from pathlib import Path
import harness
from cli_compat import repository
ROOT=Path(harness.ROOT)
TARGET='--world world --dimension minecraft:overworld'

def install(server):
    base=ROOT/'.work/gradle-home/caches/modules-2/files-2.1'
    jars=[]
    for group in ['io.papermc.paper/paper-api/1.21.11-R0.1-SNAPSHOT','com.google.guava/guava','net.kyori/adventure-api','net.kyori/adventure-key','org.jetbrains/annotations','net.md-5/bungeecord-chat','net.kyori/examination-api','net.kyori/examination-string']:
        jars+=list((base/group).rglob('*.jar'))
    classes=Path(server.dir)/'large-classes';classes.mkdir()
    subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/javac','--release','21','-cp',os.pathsep.join(map(str,jars)),'-d',str(classes),str(ROOT/'paper/tools/LargeContentPlugin.java')],check=True)
    with zipfile.ZipFile(Path(server.dir)/'plugins/large-fixture.jar','w') as jar:
        for path in classes.rglob('*.class'):jar.write(path,str(path.relative_to(classes)))
        jar.writestr('plugin.yml','name: LargeContentFixture\nversion: 1\nmain: org.worldgit.fixture.LargeContentPlugin\napi-version: "1.21"\ncommands:\n  wglarge:\n    description: Large entity and BE acceptance\n')
    shutil.rmtree(classes)

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--artifact',type=Path)
    parser.add_argument('--projection',action='store_true')
    args=parser.parse_args();out=args.output.resolve()
    if not out.is_relative_to(ROOT/'.work'):parser.error('output must be in .work')
    out.mkdir(parents=True,exist_ok=True)
    result={'success':False,'checks':[],'rows':{},'fixture':{'chunks':128,'entities':2048,'block_entities':2048,'item_stacks':55296},'console_logs':[]}
    server=None
    def save():(out/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    def check(name,ok,**data):
        result['checks'].append({'name':name,'ok':bool(ok),**data});save()
        if not ok:raise AssertionError(name+' '+str(data)[-2000:])
    def command(name,text,pattern=r'finished: (SUCCESS|NO_OP|FAILED|PARTIAL)',timeout=900):
        mark=server.mark();start=time.perf_counter();server.send(text)
        server.wait(pattern,timeout,mark);elapsed=time.perf_counter()-start;lines='\n'.join(server.lines_since(mark))
        with (out/'commands.jsonl').open('a') as f:f.write(json.dumps({'name':name,'command':text,'seconds':elapsed,'console':lines},ensure_ascii=False)+'\n')
        check(name+' success',not re.search(r'finished: (FAILED|PARTIAL)|WGLARGE failed',lines),console=lines)
        row=result['rows'].setdefault(name,{'samples':[]});row['samples'].append(elapsed);row['p50']=statistics.median(row['samples']);save()
        print(name,round(elapsed,3),flush=True);return lines
    def make(version,baseline,label):
        s=harness.Server('paper',version,baseline=str(baseline),run_label=label,view=2,xmx='2500M',extra_props={'gamemode':'survival','pause-when-empty-seconds':'-1'},config={'language':'en_us','auto-commit':{'enabled':False,'on-shutdown':False},'commit':{'chunks-per-tick':8,'snapshot-window':256,'timeout-seconds':900}})
        if args.artifact:shutil.copy2(args.artifact,Path(s.dir)/'plugins/worldgit-paper.jar')
        install(s);return s
    signal.signal(signal.SIGTERM,lambda *_:(_ for _ in ()).throw(KeyboardInterrupt()))
    try:
      with harness.BenchLock():
        try:
          version='1.21.11' if args.projection else '26.2'
          baseline=ROOT/'.work/paper-delivery/fixtures'/('multiversion-flat-'+version if args.projection else 'acceptance-flat-'+version)
          server=make(version,baseline,'large-content-'+out.name)
          result['plugin_sha256']=hashlib.sha256(Path(server.dir,'plugins/worldgit-paper.jar').read_bytes()).hexdigest()
          server.start();result['console_logs'].append(str(Path(server.evidence_log).relative_to(ROOT)))
          seed=command('seed-A','wglarge A',r'WGLARGE (complete|failed)')
          check('native fixture exact content counts','chunks=128 entities=2048 blockEntities=2048' in seed,console=seed)
          command('init','wg init '+TARGET)
          command('branch-A','wg branch A '+TARGET)
          if args.projection:
            server.stop();base=out/'projection-base';shutil.rmtree(base,ignore_errors=True);base.mkdir()
            for name in ('world','world_nether','world_the_end'):
              p=Path(server.dir)/name
              if p.is_dir():shutil.copytree(p,base/name)
            shutil.rmtree(server.dir);server=make('26.2',base,'large-projection-'+out.name)
            held=out/'filefixer-repo';shutil.move(str(repository(server.world)),held)
            server.start();shutil.move(str(held),repository(server.world));result['console_logs'].append(str(Path(server.evidence_log).relative_to(ROOT)))
            cache=Path(server.dir)/'.work/versions/conversions'
            check('first cross-version tree projection begins without cache',not cache.exists() or not any(cache.glob('*.nbt')))
            result['projection']={'from':'1.21.11','to':'26.2'}
            cold=command('status-cold-projection','wg status '+TARGET+' --full')
            warm=command('status-warm-projection','wg status '+TARGET+' --full')
            check('cold and warm projection report a clean world',all('minecraft:overworld: clean' in text for text in (cold,warm)),cold=cold,warm=warm)
            check('first projection produces reusable tree cache',cache.exists() and any(cache.glob('*.nbt')))
          else:
            command('seed-B','wglarge B',r'WGLARGE (complete|failed)')
            command('commit-B','wg commit '+TARGET+' -m large-B')
            command('branch-B','wg branch B '+TARGET)
            for _ in range(3):
              command('status-noop','wg status '+TARGET)
              command('switch-A','wg switch A '+TARGET)
              command('switch-B','wg switch B '+TARGET)
              verify=command('verify','wg verify '+TARGET)
              check('large content verifies with zero changes','Stats[chunks=0, sections=0, biomeSections=0, entityPuts=0, entityRemoves=0, chunkDeletes=0, untrackedKept=0, metaFiles=0]' in verify,console=verify)
          result['success']=True
        finally:
          if server:server.stop();shutil.rmtree(server.dir,ignore_errors=True)
          shutil.rmtree(out/'projection-base',ignore_errors=True)
    except BaseException:result['error']=traceback.format_exc();print(result['error'],flush=True)
    finally:save()
    return 0 if result['success'] else 1

if __name__=='__main__':raise SystemExit(main())
