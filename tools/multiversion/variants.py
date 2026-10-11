#!/usr/bin/env python3
"""Derive variant registries from genuine server reports and vanilla datapack assets."""
import fcntl,io,json,zipfile,yaml
from fixtures import ROOT,VERSIONS,download
result={}
with (ROOT/'.work/bench.lock').open('w') as lock:
 fcntl.flock(lock,fcntl.LOCK_EX)
 for v in VERSIONS:
  path=ROOT/'.work/versions'/v/'server.jar';existed=path.exists()
  try:
   jar=download(v);reports=json.loads((jar.parent/'generated/reports/registries.json').read_text())
   data={k.removeprefix('minecraft:'):sorted(value.get('entries',{})) for k,value in reports.items() if k.endswith(('variant','sound_variant'))}
   with zipfile.ZipFile(jar) as bundle:
    inner=next(n for n in bundle.namelist() if n.startswith('META-INF/versions/') and n.endswith('.jar'));content=bundle.read(inner)
   with zipfile.ZipFile(io.BytesIO(content)) as z:
    for name in z.namelist():
     p=name.split('/')
     if len(p)==4 and p[:2]==['data','minecraft'] and p[2].endswith(('variant','sound_variant')) and p[3].endswith('.json'):data.setdefault(p[2],[]).append('minecraft:'+p[3][:-5])
   data={k:sorted(set(values)) for k,values in data.items()}
   if v in ['1.20.1','1.20.4']:data['wolf_variant']=['minecraft:pale']
   result[str(yaml.safe_load((ROOT/'core/src/main/resources/org/worldgit/versions/catalog.yml').read_text())['versions'][VERSIONS.index(v)]['data-version'])]=data
   print(v,{k:len(x) for k,x in data.items()},flush=True)
  finally:
   if not existed:path.unlink(missing_ok=True)
(ROOT/'core/src/main/resources/org/worldgit/versions/variants.yml').write_text(yaml.safe_dump(result,sort_keys=True))
