#!/usr/bin/env python3
"""從使用者下載的原版 jar 產生 registry 報告；不散布 jar／mappings。自行持 bench.lock。"""
import argparse,fcntl,json,pathlib,subprocess,zipfile,re
from fixtures import download
ROOT=pathlib.Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('versions',nargs='*');a=p.parse_args()
versions=a.versions or ['1.20.1','1.20.4','1.20.6','1.21.1','1.21.4','1.21.8','1.21.11','26.1.2','26.2','26.3']
with (ROOT/'.work/bench.lock').open('w') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 for v in versions:
  download(v)
  d=ROOT/'.work/versions'/v;main='net.minecraft.data.Main'
  info=json.loads((ROOT/'.work/versions'/(v+'.json')).read_text())['downloads'].get('server_mappings')
  if info and not (d/'server.txt').exists():
   import hashlib
   content=subprocess.check_output(['curl','-fsSL','--retry','2',info['url']]);assert hashlib.sha1(content).hexdigest()==info['sha1'];(d/'server.txt').write_bytes(content)
  if (d/'server.txt').exists():
   for l in (d/'server.txt').read_text().splitlines():
    if l.startswith(main+' -> '):main=l.split(' -> ')[1].rstrip(':');break
  java='/usr/lib/jvm/java-'+('25' if v.startswith('26.') else '21')+'-openjdk-amd64/bin/java'
  with (d/'reports.log').open('w') as out:
   r=subprocess.run([java,'-Xmx1G','-XX:ActiveProcessorCount=3','-DbundlerMainClass='+main,'-jar','server.jar','--reports','--output',str(d/'generated')],cwd=d,stdout=out,stderr=subprocess.STDOUT,timeout=240)
  print(v,r.returncode,flush=True)
  if r.returncode:
   print((d/'reports.log').read_text()[-3000:])
   raise SystemExit(r.returncode)
