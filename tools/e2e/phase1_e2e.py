"""Phase 1 四端端到端驗收：Paper/Folia 插件 commit → CLI log/diff → Hub（git push + API + 3D 頁面）→ 模組端封包（bot 模擬客戶端）。

用法：python3 tools/e2e/phase1_e2e.py [--platform paper|folia] [--versions 1.21.11,26.2] [--skip-hub]
每個版本：
  1. 複製伺服器與合成平坦世界，裝插件，/wg init，bot 放／挖方塊，`/wg diff --show`（模組 bot 收封包），/wg commit。
  2. 伺服器停止後，CLI（wgit.jar）對同一個 .worldgit repo 跑 log／diff，與插件 commit id、封包格子比對。
  3. 本機 Hub（127.0.0.1、暫存資料目錄）：一般 git push 每個維度 repo；API 讀回 snapshot／commit；
     Playwright 開 commit 3D 頁面截圖，頁內擷取 diff 格子與 CLI 比對。
結果與證據寫入 .work/phase1-e2e/<平台>-<版本>/ ；總表 .work/phase1-e2e/result-<平台>.json。
整支腳本持有 bench.lock（harness.BenchLock）；伺服器／Hub／瀏覽器／bot 一律在 finally 關閉。
"""
import argparse, glob, json, os, re, shutil, signal, subprocess, sys, time, traceback, urllib.request, urllib.error
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
sys.path.insert(0, os.path.join(ROOT, 'paper/tools'))
from harness import *  # noqa

OUT = os.path.join(WORK, 'phase1-e2e')
ANSI = re.compile(r'\x1b\[[0-9;]*m')
JAVA25 = '/usr/lib/jvm/java-25-openjdk-amd64/bin/java'
HUB_PORT = int(os.environ.get('E2E_HUB_PORT', '8197'))
TOKEN = 'e2e-token-phase1'
DIMS = ['minecraft.overworld', 'minecraft.the_nether', 'minecraft.the_end']
results = {'started': time.strftime('%F %T'), 'targets': {}}


def strip(s):
    return ANSI.sub('', s)


RESULT_NAME = 'result.json'


def save():
    json.dump(results, open(os.path.join(OUT, RESULT_NAME), 'w'), ensure_ascii=False, indent=1)


class Target:
    def __init__(self, platform, version):
        self.platform, self.version = platform, version
        self.name = f'{platform}-{version}'
        self.dir = os.path.join(OUT, self.name)
        shutil.rmtree(self.dir, ignore_errors=True)
        os.makedirs(self.dir)
        self.r = {'steps': []}
        results['targets'][self.name] = self.r
        self.world_slug = 'e2e-' + self.name.replace('.', '-')

    def step(self, name, ok, **ev):
        self.r['steps'].append({'step': name, 'ok': bool(ok), **ev})
        print(('PASS ' if ok else 'FAIL ') + f'[{self.name}] ' + name, {k: (v if len(str(v)) < 240 else str(v)[:240] + '…') for k, v in ev.items()}, flush=True)
        save()
        return ok


def git_dir(t, dim):
    return os.path.join(t.server_dir, '.worldgit', 'world', dim)


def git(t, dim, *args):
    r = subprocess.run(['git', '--git-dir', git_dir(t, dim)] + list(args), capture_output=True, text=True)
    return r.stdout.strip()


def parse_cli_cells(text):
    cells = {}
    for m in re.finditer(r'\((-?\d+),(-?\d+),(-?\d+)\)\s+(\S+)\s+→\s+(\S+)', strip(text)):
        cells[tuple(int(m.group(i)) for i in (1, 2, 3))] = (m.group(4), m.group(5))
    return cells


def ensure_fixture(version):
    baseline = os.path.join(WORK, 'paper-delivery', 'fixtures', 'acceptance-flat-' + version)
    if not os.path.isdir(baseline):
        subprocess.run([JAVA[version], '-Xmx512m', '-cp', os.path.join(ROOT, 'cli/build/libs/wgit.jar'),
                        os.path.join(ROOT, 'paper/tools/ScaleFixture.java'), version,
                        os.path.join(WORK, 'worlds', version, 'baseline'), baseline, '16'], check=True, timeout=180)
    return baseline


def decode_dump(path):
    if not os.path.isfile(path):
        return []
    r = subprocess.run([JAVA['1.21.11'], '-cp', os.path.join(OUT, 'wgit.jar'), os.path.join(ROOT, 'tools/e2e/DecodeDump.java'), path],
                       capture_output=True, text=True, timeout=120)
    if r.returncode != 0:
        raise RuntimeError('DecodeDump 失敗: ' + r.stderr[-800:])
    return json.loads(r.stdout)


def stage_server(t):
    """伺服器階段：commit 並收集模組端封包。成功回傳 True。"""
    baseline = ensure_fixture(t.version)
    dump = os.path.join(t.dir, 'mod-packets.txt')
    os.environ['WG_BOT_DUMP'] = dump
    s = Server(t.platform, t.version, baseline=baseline, config={'auto-commit': {'enabled': False, 'on-shutdown': False}}, run_label='e2e-' + t.name)
    t.server_dir = s.dir
    ok = True
    try:
        s.start()
        t.r['console_log'] = os.path.relpath(s.evidence_log, ROOT)
        bot = s.bot('WgBot3', mod=True)   # 模擬裝了模組的客戶端（WgBot3 是 op）
        for g in ('random_tick_speed 0', 'spawn_mobs false', 'advance_weather false'):
            s.cmd('gamerule ' + g, r'(?i)game ?rule|incorrect|unknown', 20)
        s.cmd('gamemode creative WgBot3')
        time.sleep(8)
        s.cmd('kill @e[type=!minecraft:player]'); time.sleep(2)
        s.cmd('wg init', r'init 完成|Initialization complete|失敗|尚未', 900)
        t.step('init 建立三個維度 repo', all(len(git(t, d, 'log', '--format=%H').split()) == 1 for d in DIMS))
        time.sleep(5)
        s.cmd('wg commit -m fixture-settle', r'overworld [0-9a-f]{8}|沒有變動|失敗', 120)
        base_commits = git(t, DIMS[0], 'log', '--format=%H').split()
        # bot 放兩格、挖一格
        placed = [place_block(bot, 2, 'stone'), place_block(bot, -2, 'gold_block')]
        dug = None
        try:
            tx, ty, tz = placed[0]
            dx, dz = tx + 1, tz
            b = bot.ask(f'block {dx} {ty - 1} {dz}', 'block', 10)
            if b.get('name') and 'air' not in b['name']:
                bot.ask(f'dig {dx} {ty - 1} {dz}', 'dug', 20); dug = (dx, ty - 1, dz, b['name'])
        except Exception as e:
            t.r['dig_error'] = str(e)
        time.sleep(3)
        t.r['edits'] = {'placed': placed, 'dug': dug}
        t.step('bot 完成放置／挖掘', True, placed=placed, dug=dug)
        # 模組端：status 描邊 + diff 鬼影
        bot.ask('chat /wg status --show', 'chat_sent', 10); time.sleep(8)
        bot.ask('chat /wg diff --show', 'chat_sent', 10); time.sleep(10)
        t.r['mod_stats'] = bot.ask('stats', 'stats', 10)['received']
        out = strip(s.cmd('wg commit -m e2e-edit', r'overworld [0-9a-f]{8}|沒有變動|失敗', 180))
        t.r['plugin_commit_output'] = out[-600:]
        c1 = git(t, DIMS[0], 'log', '--format=%H').split()
        ok = t.step('commit 後 overworld 多一個 commit', len(c1) == len(base_commits) + 1, commits=len(c1))
        t.head = {d: git(t, d, 'rev-parse', 'HEAD') for d in DIMS}
        t.base_overworld = base_commits[0] if base_commits else None
        t.r['head'] = t.head
        t.r['plugin_problems'] = [strip(l) for l in s.lines_since(0) if re.search(r'\[WorldGit\].*(WARN|ERROR)|org\.worldgit.*Exception', l)][:10]
        t.step('插件沒有 WARN/ERROR', not t.r['plugin_problems'], problems=t.r['plugin_problems'][:3])
        bot.stop()
    except Exception:
        t.step('伺服器階段例外', False, trace=traceback.format_exc())
        ok = False
    finally:
        s.stop()
    # 解碼模組端封包
    try:
        batches = decode_dump(dump)
        t.r['mod_batches'] = [{'channel': b['channel'], 'preview': b['preview'], 'cells': len(b['cells']), 'outlines': len(b['outlines'])} for b in batches]
        t.mod_diff = next((b for b in batches if b['channel'] == 'worldgit:diff' and b['cells']), None)
        t.mod_status = next((b for b in batches if b['channel'] == 'worldgit:status' and b['outlines']), None)
        json.dump(batches, open(os.path.join(t.dir, 'mod-batches.json'), 'w'), indent=1)
    except Exception:
        t.step('封包解碼例外', False, trace=traceback.format_exc())
        t.mod_diff = t.mod_status = None
    return ok


def stage_cli(t):
    log = wgit(['--world', os.path.join(t.server_dir, 'world'), 'log', '-n', '5'], check=False)
    diff = wgit(['--world', os.path.join(t.server_dir, 'world'), 'diff', 'HEAD~1', 'HEAD', '--blocks', '--dimension', 'minecraft:overworld'], check=False)
    open(os.path.join(t.dir, 'cli-log.txt'), 'w').write(strip(log))
    open(os.path.join(t.dir, 'cli-diff.txt'), 'w').write(strip(diff))
    head = t.head[DIMS[0]]
    t.step('CLI log 含插件的 commit id 與訊息', head[:8] in strip(log) and 'e2e-edit' in strip(log), head=head[:8])
    t.cli_cells = parse_cli_cells(diff)
    t.step('CLI diff 有方塊格子', len(t.cli_cells) >= 2, cells=len(t.cli_cells))
    exp = {tuple(p): None for p in t.r['edits']['placed']}
    t.step('CLI diff 的格子涵蓋 bot 放的方塊', all(k in t.cli_cells for k in exp), cli=list(t.cli_cells.items())[:6], placed=list(exp))
    t.r['cli_cells'] = [[*k, *v] for k, v in sorted(t.cli_cells.items())]
    # 模組端封包 vs CLI
    if t.mod_diff:
        mod = {(c[0], c[1], c[2]): (c[4], c[5]) for c in t.mod_diff['cells']}
        norm = lambda v: tuple(x if ':' in x else 'minecraft:' + x for x in v)
        same = set(mod) == set(t.cli_cells)
        t.step('模組端 diff 封包的格子 == CLI diff 的格子（座標集合）', same, mod=len(mod), cli=len(t.cli_cells), only_mod=sorted(set(mod) - set(t.cli_cells))[:5], only_cli=sorted(set(t.cli_cells) - set(mod))[:5])
        stripped = lambda s_: re.sub(r'\[.*\]$', '', s_)
        t.step('模組端 diff 封包的前後狀態方塊名稱與 CLI 一致', same and all(stripped(norm(mod[k])[0]) == stripped(t.cli_cells[k][0]) and stripped(norm(mod[k])[1]) == stripped(t.cli_cells[k][1]) for k in mod), sample=[(k, mod[k], t.cli_cells[k]) for k in list(mod)[:3] if k in t.cli_cells])
    else:
        t.step('模組端收到 diff 封包', False, batches=t.r.get('mod_batches'))
    if t.mod_status:
        inside = all(any(o[0] <= k[0] <= o[3] and o[1] <= k[1] <= o[4] and o[2] <= k[2] <= o[5] for o in t.mod_status['outlines']) for k in t.cli_cells)
        t.step('模組端 status 描邊包含所有 CLI diff 格子', inside, outlines=t.mod_status['outlines'][:4])
    else:
        t.step('模組端收到 status 封包', False, batches=t.r.get('mod_batches'))


# ---------------- Hub ----------------

def http(path, token=None, accept_json=True, raw=False):
    req = urllib.request.Request(f'http://127.0.0.1:{HUB_PORT}{path}', headers={'Authorization': f'Bearer {token or TOKEN}'})
    with urllib.request.urlopen(req, timeout=120) as r:
        b = r.read()
        return b if raw else json.loads(b)


class Hub:
    def __init__(self):
        self.dir = os.path.join(OUT, 'hub')
        shutil.rmtree(self.dir, ignore_errors=True)
        os.makedirs(self.dir)
        self.jar = os.path.join(OUT, 'worldgit-hub.jar')
        self.proc = None

    def start(self):
        env = dict(os.environ, WORLDGIT_HUB_DATA_DIR=os.path.join(self.dir, 'data'), WORLDGIT_HUB_BOOTSTRAP_ADMIN_TOKEN=TOKEN,
                   WORLDGIT_HUB_BOOTSTRAP_ADMIN_PASSWORD='e2e-admin-pass', WORLDGIT_HUB_ASSETS_SOURCE_DIR=os.path.join(WORK, 'assets'))
        self.log = open(os.path.join(self.dir, 'hub.log'), 'w')
        self.proc = subprocess.Popen([JAVA25, '-Xmx768m', '-jar', self.jar, '--server.address=127.0.0.1', f'--server.port={HUB_PORT}'],
                                     env=env, stdout=self.log, stderr=subprocess.STDOUT, preexec_fn=os.setsid)
        end = time.time() + 180
        while time.time() < end:
            try:
                urllib.request.urlopen(f'http://127.0.0.1:{HUB_PORT}/actuator/health', timeout=3).read()
                return
            except Exception:
                if self.proc.poll() is not None:
                    raise RuntimeError('Hub 提前結束，見 ' + self.log.name)
                time.sleep(1)
        raise TimeoutError('Hub 未就緒')

    def stop(self):
        if self.proc and self.proc.poll() is None:
            try:
                os.killpg(os.getpgid(self.proc.pid), signal.SIGTERM)
                self.proc.wait(30)
            except Exception:
                os.killpg(os.getpgid(self.proc.pid), signal.SIGKILL)
        if getattr(self, 'log', None):
            self.log.close()


def stage_hub(targets):
    hub = Hub()
    shutil.copy(os.path.join(ROOT, 'hub/build/libs/worldgit-hub.jar'), hub.jar)
    try:
        hub.start()
        jobs_by_target = {}
        for t in targets:
            if not hasattr(t, 'head'):
                continue
            slug = t.world_slug
            pushed = True
            for d in DIMS:
                r = subprocess.run(['git', '--git-dir', git_dir(t, d), 'push', '-q', f'http://admin:{TOKEN}@127.0.0.1:{HUB_PORT}/git/admin/{slug}/{d}.git', 'HEAD:refs/heads/main'], capture_output=True, text=True, timeout=300)
                if r.returncode != 0:
                    pushed = False
                    t.r.setdefault('push_errors', []).append(d + ': ' + r.stderr[-300:])
            t.step('git push 三個維度 repo 到 Hub', pushed, errors=t.r.get('push_errors'))
            if not pushed:
                continue
            try:
                snaps = http(f'/api/v1/worlds/admin/{slug}/snapshots')
                json.dump(snaps, open(os.path.join(t.dir, 'hub-snapshots.json'), 'w'), indent=1)
                text = json.dumps(snaps)
                t.step('Hub snapshots API 含插件 HEAD commit id（三個維度）', all(t.head[d] in text or t.head[d][:8] in text for d in DIMS), head=t.head[DIMS[0]][:8])
                detail = http(f'/api/v1/worlds/admin/{slug}/dims/{DIMS[0]}/commits/{t.head[DIMS[0]]}')
                json.dump(detail, open(os.path.join(t.dir, 'hub-commit.json'), 'w'), indent=1)
                dtext = json.dumps(detail)
                t.step('Hub commit 詳情 API 回傳同一個 commit id', t.head[DIMS[0]] in dtext, keys=list(detail)[:10])
            except Exception:
                t.step('Hub API 例外', False, trace=traceback.format_exc())
                continue
            # 以 API 的 WGDF 解出 diff 格子
            try:
                chunks = sorted({(k[0] >> 4, k[2] >> 4) for k in t.cli_cells})
                x0, x1 = min(c[0] for c in chunks), max(c[0] for c in chunks)
                z0, z1 = min(c[1] for c in chunks), max(c[1] for c in chunks)
                buf = http(f'/api/v1/worlds/admin/{slug}/dims/{DIMS[0]}/commits/{t.head[DIMS[0]]}/diff?base={t.base_overworld}&x0={x0}&z0={z0}&x1={x1}&z1={z1}', raw=True)
                t.hub_api_cells = decode_wgdf(buf)
                t.step('Hub diff API（WGDF）的格子 == CLI diff 的格子', set(t.hub_api_cells) == set(t.cli_cells), hub=len(t.hub_api_cells), cli=len(t.cli_cells), only_hub=sorted(set(t.hub_api_cells) - set(t.cli_cells))[:5], only_cli=sorted(set(t.cli_cells) - set(t.hub_api_cells))[:5])
            except Exception:
                t.step('Hub diff API 例外', False, trace=traceback.format_exc())
            first = t.r['edits']['placed'][0]
            jobs_by_target[t.name] = [
                {'name': f'{t.name}-hub-commit-3d', 'path': f'/admin/{slug}/commit/{DIMS[0]}/{t.head[DIMS[0]]}', 'extract': True},
                {'name': f'{t.name}-hub-commit-closeup', 'path': f'/admin/{slug}/commit/{DIMS[0]}/{t.head[DIMS[0]]}',
                 'cam': {'x': first[0] + .5, 'y': first[1] + .5, 'z': first[2] + .5, 'yaw': 0.6, 'pitch': 0.6, 'dist': 9, 'mode': 'orbit'}},
            ]
        for t in targets:
            jobs = jobs_by_target.get(t.name)
            if not jobs:
                continue
            jf = os.path.join(t.dir, 'shots-jobs.json'); json.dump(jobs, open(jf, 'w'))
            shots = os.path.join(t.dir, 'shots')
            r = subprocess.run(['node', os.path.join(ROOT, 'tools/e2e/hub_shots.mjs'), f'http://127.0.0.1:{HUB_PORT}', TOKEN, shots, jf], capture_output=True, text=True, timeout=900)
            open(os.path.join(t.dir, 'shots.log'), 'w').write(r.stdout + r.stderr)
            if r.returncode != 0:
                t.step('Playwright 3D 頁面截圖', False, err=(r.stderr or r.stdout)[-600:])
                continue
            info = json.load(open(os.path.join(shots, 'shots.json')))
            main = info[0]
            t.step('Playwright：commit 3D 頁面載入並截圖', all(x['bytes'] > 20000 for x in info), shots=[(x['name'], x['bytes'], round(x['readyMs'])) for x in info], console_errors=[x['logs'] for x in info if x['logs']])
            vc = {(c[0], c[1], c[2]) for c in main.get('cells', [])}
            t.step('3D 頁面內 viewer 的 diff 格子 == CLI diff 的格子（上色位置正確）', vc == set(t.cli_cells), viewer=len(vc), cli=len(t.cli_cells), only_viewer=sorted(vc - set(t.cli_cells))[:5], only_cli=sorted(set(t.cli_cells) - vc)[:5])
    except Exception:
        for t in targets:
            t.step('Hub 階段例外', False, trace=traceback.format_exc())
    finally:
        hub.stop()


def decode_wgdf(buf):
    import struct, io
    b = io.BytesIO(buf)
    rd = lambda fmt: struct.unpack('>' + fmt, b.read(struct.calcsize('>' + fmt)))
    assert b.read(4) == b'WGDF'
    rd('B')
    n_states, = rd('H')
    for _ in range(n_states):
        ln, = rd('H'); b.read(ln)
    n, = rd('I')
    cells = {}
    for _ in range(n):
        cx, cz, sy, count = rd('iibH')
        for _ in range(count):
            idx, kind, before = rd('HBH')
            cells[(cx * 16 + (idx & 15), sy * 16 + (idx >> 8), cz * 16 + ((idx >> 4) & 15))] = kind
    return cells


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--platform', default='paper')
    ap.add_argument('--versions', default='1.21.11,26.2')
    ap.add_argument('--skip-hub', action='store_true')
    a = ap.parse_args()
    global RESULT_NAME
    RESULT_NAME = f'result-{a.platform}.json'
    results['platform'] = a.platform
    os.makedirs(OUT, exist_ok=True)
    signal.signal(signal.SIGTERM, lambda *_: (_ for _ in ()).throw(KeyboardInterrupt('terminated')))
    shutil.copy(os.path.join(ROOT, 'cli/build/libs/wgit.jar'), os.path.join(OUT, 'wgit.jar'))
    targets = []
    with BenchLock():
        try:
            for v in a.versions.split(','):
                t = Target(a.platform, v)
                targets.append(t)
                if stage_server(t):
                    try:
                        stage_cli(t)
                    except Exception:
                        t.step('CLI 階段例外', False, trace=traceback.format_exc())
            if not a.skip_hub:
                stage_hub(targets)
        finally:
            results['finished'] = time.strftime('%F %T')
            save()
    failed = [(n, s['step']) for n, tr in results['targets'].items() for s in tr['steps'] if not s['ok']]
    print('FAILED:', failed if failed else 'none')
    return 1 if failed else 0


if __name__ == '__main__':
    sys.exit(main())
