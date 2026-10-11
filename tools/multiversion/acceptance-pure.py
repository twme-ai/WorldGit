#!/usr/bin/env python3
"""All source versions round-trip without letting a server repair serialization. Owns bench.lock."""
import fcntl, importlib.util, json, shutil
from fixtures import ROOT, VERSIONS
from acceptance import WORK

spec = importlib.util.spec_from_file_location('extra', ROOT/'tools/multiversion/acceptance-extra.py')
extra = importlib.util.module_from_spec(spec)
spec.loader.exec_module(extra)

def main():
    result = {'success': False, 'checks': []}
    def check(name, condition, **details):
        result['checks'].append(dict(name=name, passed=bool(condition), **details))
        (WORK/'pure-results.json').write_text(json.dumps(result, indent=2))
        if not condition:
            raise RuntimeError(name)
    with (ROOT/'.work/bench.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        for source in VERSIONS[:-1]:
            base, world = extra.copy(source, 'preflight-'+source)
            try:
                extra.pure_roundtrip(source, world, extra.hashes(world), check)
            finally:
                shutil.rmtree(base, ignore_errors=True)
        result['success'] = True
        (WORK/'pure-results.json').write_text(json.dumps(result, indent=2))

if __name__ == '__main__':
    main()
