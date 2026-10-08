#!/usr/bin/env python3
"""驗證未安裝 AxiomPaper 時，正式 WorldGit jar 的啟動／指令／關閉。自取 bench.lock。"""
import json,shutil,signal,socket,sys,time,zipfile
from pathlib import Path
import harness
ROOT=Path(harness.ROOT)
def terminate(signum,frame):raise KeyboardInterrupt('optional Axiom runner terminated')
signal.signal(signal.SIGTERM,terminate)
def run(version):
 work=ROOT/'.work/axiom'/f'without-axiom-{version}-{int(time.time())}';work.mkdir();server=None
 result={'version':version,'success':False,'checks':[]}
 def check(name,ok,**data):
  result['checks'].append({'name':name,'ok':bool(ok),**data});print(('PASS ' if ok else 'FAIL ')+name,flush=True)
  if not ok:raise AssertionError(name)
 try:
  with harness.BenchLock():
   try:
    harness.RUN=str(work/'run')
    server=harness.Server('paper',version,baseline=str(ROOT/'.work/paper-delivery/fixtures'/('acceptance-flat-'+version)),view=2,all_entities=False,config={'language':'en_us','auto-commit':{'enabled':False,'on-shutdown':False}})
    with zipfile.ZipFile(harness.plugin_jar()) as artifact:check('compileOnly API absent from production jar',not any(n.startswith('com/moulberry/axiom/') for n in artifact.namelist()))
    server.start();check('WorldGit enables without AxiomPaper',any('WorldGit 已啟用' in s for s in server.lines_since(0)) and not any('已掛接 AxiomPaper' in s for s in server.lines_since(0)))
    observed=server.cmd('wg help','help finished: SUCCESS',60);check('WorldGit help works','WorldGit' in observed and 'help finished: SUCCESS' in observed,console=observed)
    server.stop();check('clean shutdown',server.proc.poll()==0)
    problems=[s for s in server.lines_since(0) if ('ClassNotFound' in s or 'NoClassDefFound' in s or ('ERROR' in s and ('WorldGit' in s or 'axiom' in s.lower())))];check('no linkage or WorldGit errors',not problems,problems=problems)
    result['success']=True
   finally:
    if server:
     server.stop()
     if hasattr(server,'evidence_log'):shutil.copy2(server.evidence_log,work/'console.log')
     shutil.rmtree(server.dir,ignore_errors=True)
     with socket.socket() as probe:result['port_closed']=probe.connect_ex(('127.0.0.1',server.port))!=0
     result['success']=result['success'] and result['port_closed']
 except BaseException as e:result['error']=repr(e)
 finally:(work/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
 print(work/'result.json',flush=True);return result['success']
if __name__=='__main__':sys.exit(0 if run(sys.argv[1]) else 1)
