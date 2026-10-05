#!/usr/bin/env python3
"""真 PTY 的 init 詢問、進度、NO_COLOR／非 TTY 與 Ctrl+C；只寫 fixture 副本。"""
import errno, json, os, pty, select, shutil, signal, subprocess, sys, tempfile, time
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'paper/tools'))
import harness
JAVA=harness.JAVA['1.21.11'];JAR=ROOT/'cli/build/libs/wgit.jar'

def terminal(world,args,answer=None,environment=None,cancel=False):
    master,slave=pty.openpty();chunks=[];cancelled=False;answered=False
    process=subprocess.Popen([JAVA,'-Xmx1g','-jar',str(JAR),'--world',str(world),*args],stdin=slave,stdout=slave,stderr=slave,env=environment)
    os.close(slave);deadline=time.monotonic()+120
    try:
        while time.monotonic()<deadline:
            ready,_,_=select.select([master],[],[],.1)
            if ready:
                try:value=os.read(master,65536)
                except OSError as error:
                    if error.errno==errno.EIO:break
                    raise
                if not value:break
                chunks.append(value);text=b''.join(chunks)
                if answer and not answered and b'[y/N]' in text:os.write(master,answer);answered=True
                if cancel and not cancelled and b'ETA=' in text:process.send_signal(signal.SIGINT);cancelled=True
            if process.poll() is not None and not ready:break
        process.wait(timeout=15)
        return process.returncode,b''.join(chunks).decode('utf-8',errors='replace'),cancelled
    finally:
        if process.poll() is None:process.kill();process.wait()
        os.close(master)

def main():
    results={};evidence=ROOT/'.work/phase5-terminal-results.json'
    with harness.BenchLock(),tempfile.TemporaryDirectory(prefix='phase5-terminal-',dir=ROOT/'.work') as directory:
        work=Path(directory);fixture=ROOT/'core/src/test/resources/fixtures/26.2'
        plain=work/'plain';shutil.copytree(fixture,plain)
        run=subprocess.run([JAVA,'-jar',str(JAR),'--world',str(plain),'init','--format=json'],text=True,capture_output=True,timeout=60)
        assert run.returncode==0 and set(json.loads(run.stdout)['data']['dimensions'])=={'minecraft:overworld'}
        assert '\x1b' not in run.stdout+run.stderr and '[y/N]' not in run.stdout+run.stderr
        results['nonTtyJsonScope']=True
        interactive=work/'interactive';shutil.copytree(fixture,interactive)
        env=dict(os.environ);env.pop('CI',None);env.pop('NO_COLOR',None);env.pop('WGIT_LOCALE',None)
        code,text,_=terminal(interactive,['init'],b'y\n',env)
        assert code==0 and '[y/N]' in text and '成功完成' in text and 'ETA=' in text,text
        for path in ['.worldgit/HEAD','dimensions/minecraft/the_nether/.worldgit/HEAD','dimensions/minecraft/the_end/.worldgit/HEAD']:
            assert (interactive/'world'/path).is_file(),path
        results['ttyPromptAndProgress']=True
        for flags,environment,name in [(['--color=never'],env,'colorNever'),([],dict(env,NO_COLOR='1'),'noColor')]:
            code,text,_=terminal(interactive,['status','--full',*flags],environment=environment)
            assert code==0 and '\x1b' not in text and '成功完成' in text,text
            results[name]=True
        large=work/'large'
        subprocess.run([JAVA,'-Xmx512m','-cp',str(JAR),str(ROOT/'paper/tools/ScaleFixture.java'),'26.2',str(ROOT/'.work/worlds/26.2/baseline'),str(large),'32'],check=True,timeout=120)
        code,text,sent=terminal(large,['init','--only'],environment=env,cancel=True)
        assert sent and code==130 and '已取消' in text,(code,text)
        results['ctrlCSafeCancellation']=True
        evidence.write_text(json.dumps(results,ensure_ascii=False,indent=2)+'\n')
    print('PASS PTY scope / progress / NO_COLOR / non-TTY / Ctrl+C',flush=True)
if __name__=='__main__':main()
