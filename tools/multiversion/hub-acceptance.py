#!/usr/bin/env python3
"""真 Hub／原版 fixture 的跨版本摘要、3D wire 與 LOD；自行持 bench.lock。"""
import fcntl, importlib.util, json, os, pathlib, shutil, struct, subprocess, urllib.request
from fixtures import ROOT

spec = importlib.util.spec_from_file_location('hub_fixture', ROOT/'hub/scripts/phase5-acceptance.py')
hub = importlib.util.module_from_spec(spec)
spec.loader.exec_module(hub)
WORK = pathlib.Path(os.environ.get('WORLDGIT_MV_RESULTS', ROOT/'.work/multiversion')).resolve()/'hub'

def main():
    WORK.mkdir(parents=True, exist_ok=True)
    hub.WORK = WORK
    hub.PORT = 8099
    proc = None
    result = {'success': False, 'checks': []}
    def check(name, condition):
        result['checks'].append({'name': name, 'passed': bool(condition)})
        (WORK/'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
        if not condition:
            raise AssertionError(name)
        print('PASS', name, flush=True)
    def binary(path):
        request = urllib.request.Request(f'http://127.0.0.1:{hub.PORT}/api/v1'+path,
            headers={'Authorization': 'Bearer '+hub.TOKEN})
        with urllib.request.urlopen(request, timeout=180) as response:
            return response.read()
    def sections(data):
        assert data[:5] == b'WGDF\x01'
        count = struct.unpack_from('>H', data, 5)[0]
        offset = 7
        for _ in range(count):
            length = struct.unpack_from('>H', data, offset)[0]
            offset += 2+length
        return struct.unpack_from('>I', data, offset)[0]
    with (ROOT/'.work/bench.lock').open('a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        world = WORK/'world'
        old_options = os.environ.get('JAVA_TOOL_OPTIONS')
        os.environ['JAVA_TOOL_OPTIONS'] = (old_options or '')+' -Dworldgit.versions='+str(ROOT/'.work/versions')
        try:
            shutil.copy2(ROOT/'cli/build/libs/wgit.jar', WORK/'wgit.jar')
            shutil.copytree(ROOT/'.work/worlds/1.20.4/multiversion/world', world)
            subprocess.run([hub.harness.JAVA['1.21.11'], '-cp', str(WORK/'wgit.jar'),
                str(ROOT/'tools/multiversion/FixtureWindow.java'), str(world)],
                cwd=ROOT, check=True, timeout=120)
            hub.cli(world, 'init', '--with-dimensions=all', '--template=survival')
            hub.cli(world, 'branch', 'old')
            hub.cli(world, 'upgrade', '--to', '26.3')
            hub.cli(world, 'branch', 'new')
            hub.cli(world, 'downgrade', '--to', '1.20.4', '--accept-loss')
            proc = hub.start()
            hub.api('POST', '/worlds', {'name': 'versions', 'isPublic': False})
            hub.cli(world, 'remote', 'add', 'origin', f'http://127.0.0.1:{hub.PORT}/admin/versions')
            # WorldGit --all selects dimensions; each revision is pushed explicitly.
            for branch in ['old', 'new', 'main']:
                hub.cli(world, 'push', 'origin', branch)
            base = '/worlds/admin/versions'
            published = hub.api('GET', base+'/branches?dimension=minecraft%3Aoverworld')
            check('old, new and main fixture branches are published',
                {'old', 'new', 'main'} <= {branch['name'] for branch in published['branches']})
            comparisons = [hub.api('GET', base+'/compare?a='+a+'&b='+b)
                for a, b in [('old', 'new'), ('new', 'main')]]
            check('upgrade and downgrade summaries have no schema-only block or entity changes',
                all(not row[key] for row in comparisons for key in ['added', 'removed', 'modified'])
                and all(not dim[key] for row in comparisons for dim in row['dimensions']
                    for key in ['entitiesAdded', 'entitiesRemoved', 'entitiesModified']))
            commits = comparisons[1]['dimensions'][0]
            new, old = commits['a']['id'], commits['b']['id']
            prefix = base+'/dims/minecraft.overworld/commits/'
            window = '&x0=0&z0=0&x1=1&z1=1'
            for a, b in [(new, old), (old, new)]:
                check('binary diff has no schema-only sections '+b[:8],
                    sections(binary(prefix+b+'/diff?base='+a+window+'&target=26.3')) == 0)
            check('projected chunk wire equals the native target-version tree',
                binary(prefix+old+'/chunks?target=26.3'+window)
                == binary(prefix+new+'/chunks?target=26.3'+window))
            entities = hub.api('GET', prefix+old+'/entities?base='+new+'&target=26.3'+window)
            check('entity overlay does not report schema-only changes',
                bool(entities) and all('kind' not in entity for entity in entities))
            detail = hub.api('GET', prefix+old)
            check('downgrade commit viewer selects common-version resources',
                detail['commit']['mcVersion'] == '1.20.4' and detail['mcVersion'] == '26.3')
            tiles = [hub.api('GET', prefix+id+'/tiles?target=26.3') for id in [old, new]]
            check('projected tile references are identical', tiles[0] == tiles[1])
            for suffix in ['png', 'height']:
                check('projected LOD '+suffix+' matches the target version',
                    binary(prefix+old+'/tiles/0/0.'+suffix+'?target=26.3')
                    == binary(prefix+new+'/tiles/0/0.'+suffix+'?target=26.3'))
            hub.api('GET', prefix+new+'/chunks?target=1.20.4'+window, expected=400)
            check('readonly rendering rejects hidden downgrade loss', True)
            preview = hub.api('GET', base+'/merge-preview?ours=new&theirs=old&dimension=minecraft%3Aoverworld')
            check('cross-version merge preview has no version problems', not preview.get('problems'))
            config = WORK/'browser-config.json'
            config.write_text(json.dumps({'hub': f'http://127.0.0.1:{hub.PORT}',
                'password': hub.PASSWORD, 'out': str(WORK), 'result': str(WORK/'browser.json')}))
            config.chmod(0o600)
            try:
                subprocess.run(['node', str(ROOT/'tools/multiversion/hub-browser.mjs'), str(config)],
                    cwd=ROOT, check=True, timeout=600)
            finally:
                config.unlink(missing_ok=True)
            check('browser color and before/after views use the correct target resources',
                json.loads((WORK/'browser.json').read_text())['success'])
            result['success'] = True
        finally:
            if proc:
                proc.stop()
            if old_options is None:
                os.environ.pop('JAVA_TOOL_OPTIONS', None)
            else:
                os.environ['JAVA_TOOL_OPTIONS'] = old_options
            (WORK/'results.json').write_text(json.dumps(result, ensure_ascii=False, indent=2))
            for name in ['world', 'hub-data']:
                shutil.rmtree(WORK/name, ignore_errors=True)
            (WORK/'wgit.jar').unlink(missing_ok=True)

if __name__ == '__main__':
    main()
