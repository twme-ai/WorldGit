#!/usr/bin/env python3
"""Legacy Paper and WorldGit CLI on Java 21, including the Java 25 DFU worker."""
import fcntl,hashlib,json,os,pathlib,shutil,subprocess,urllib.request
from fixtures import ROOT,Server
from acceptance import JAR

def java21_plugin(work):
    output=work/'java21-plugin';output.mkdir(exist_ok=True)
    cache=ROOT/'.work/gradle-home/caches/modules-2/files-2.1'
    api=next(p for p in (cache/'io.papermc.paper/paper-api/1.21.11-R0.1-SNAPSHOT').rglob('*.jar')
        if not p.name.endswith(('-sources.jar','-javadoc.jar')))
    jars=[api]+list((cache/'net.kyori').rglob('*.jar'))
    subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/javac','--release','21',
        '-classpath',os.pathsep.join(map(str,jars)),'-d',str(output),
        str(ROOT/'tools/multiversion/Java21PluginProbe.java')],check=True)
    (output/'plugin.yml').write_text('name: WorldGitJava21Probe\nversion: 1\nmain: org.worldgit.validation.Java21PluginProbe\napi-version: \'1.20\'\n')
    jar=work/'java21-plugin.jar'
    subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/jar','--create','--file',str(jar),'-C',str(output),'.'],check=True)
    return jar

def java21_cli(version, work):
    base=work/('java21-cli-'+version);world=base/'world'
    shutil.rmtree(base,ignore_errors=True)
    shutil.copytree(ROOT/'.work/worlds'/version/'multiversion/world',world)
    try:
        with (work/('cli-java21-'+version+'.log')).open('w') as log:
            def run(*args):
                command=['/usr/lib/jvm/java-21-openjdk-amd64/bin/java','-jar',str(JAR),
                    '-w',str(world),'--format=json',*args]
                result=subprocess.run(command,cwd=ROOT,text=True,capture_output=True,timeout=600)
                log.write(json.dumps(command)+'\n'+result.stdout+result.stderr+'\n');log.flush()
                if result.returncode:raise RuntimeError('Java 21 CLI failed: '+result.stdout+result.stderr)
                return json.loads(result.stdout)
            run('init','--with-dimensions=all','--template=survival')
            for operation,target in [('upgrade','26.3'),('downgrade',version)]:
                run(operation,'--to',target,'--accept-loss')
                status=run('status')
                for row in status['data']['dimensions'].values():
                    if any(row['value']['diff'][key] for key in ['sections','entities','biomes','metadata']):
                        raise RuntimeError('Java 21 CLI migration did not produce a clean world')
    finally:shutil.rmtree(base,ignore_errors=True)
def main():
    work=pathlib.Path(os.environ.get('WORLDGIT_MV_RESULTS', ROOT/'.work/multiversion')).resolve();result=[]
    work.mkdir(parents=True,exist_ok=True)
    with (ROOT/'.work/bench.lock').open('w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        plugin=java21_plugin(work)
        for v in ['1.20.1','1.20.4']:
            java21_cli(v,work)
            path=ROOT/'.work/versions'/('paper-'+v+'.json')
            if not path.exists():
                request=urllib.request.Request('https://fill.papermc.io/v3/projects/paper/versions/'+v+'/builds/latest',headers={'User-Agent':'WorldGit local version verification'})
                path.write_bytes(urllib.request.urlopen(request,timeout=30).read())
            meta=json.loads(path.read_text());file=meta['downloads']['server:default'];jar=ROOT/'.work/versions'/('paper-'+v+'.jar');base=work/('java21-paper-'+v)
            try:
                if not jar.exists():subprocess.run(['curl','-fsSL','--max-time','120','--retry','2',file['url'],'-o',str(jar)],check=True)
                assert hashlib.sha256(jar.read_bytes()).hexdigest()==file['checksums']['sha256']
                (base/'plugins').mkdir(parents=True,exist_ok=True)
                shutil.copy2(plugin,base/'plugins/java21-probe.jar')
                with Server(v,base,work/('paper-java21-'+v+'.log'),jar=jar) as server:server.send('version')
                result.append(dict(minecraft=v,paperBuild=meta['id'],java=21,cliJava21=True,
                    dfuTarget='26.3',dfuJava=25,passed=any('Done (' in x for x in server.lines),
                    pluginBytecodeJava21=any('WG_JAVA21_PLUGIN_READY' in x for x in server.lines),
                    artifactSHA256=file['checksums']['sha256']))
                (work/'platform-java21.json').write_text(json.dumps(result,indent=2))
                if not result[-1]['passed'] or not result[-1]['pluginBytecodeJava21']:
                    raise RuntimeError('Paper or its Java 21 plugin did not start')
            finally:shutil.rmtree(base,ignore_errors=True);jar.unlink(missing_ok=True)
if __name__=='__main__':main()
