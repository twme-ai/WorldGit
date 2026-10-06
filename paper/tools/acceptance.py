"""Phase 1 Paper/Folia 插件驗收（單一平台）。用法：python3 acceptance.py <paper|folia> <1.21.11|26.2> [scenarios...]

scenarios：basic（commit 兩次無變動、bot 放一格、status 恰好 1 個 section、CLI 看到同樣歷史）、attribution（兩個 bot 各改一處）、
           mod（握手與 status/diff 傳送）、display（沒裝模組：BlockDisplay fallback，只對請求者可見、/wg clear、逾時、登出、不進實體快照）、worldedit（//set 大範圍）、auto（自動 commit：定時、登出）、shutdown（關閉前 commit 與離線 CLI 一致）
結果寫入 .work/paper-delivery/results/<平台>-<版本>.json；每一步 pass/fail 與原始證據都留下，失敗照實記錄，不中斷後續場景。
"""
import json, os, re, subprocess, sys, time, traceback
sys.path.insert(0, os.path.dirname(__file__))
from harness import *

platform, version = sys.argv[1], sys.argv[2]
wanted = sys.argv[3:] or ['basic', 'attribution', 'mod', 'display', 'worldedit', 'shutdown', 'auto']
NAME = f'{platform}-{version}'
RESULTS = os.path.join(WORK, 'paper-delivery', 'results')
os.makedirs(RESULTS, exist_ok=True)
ANSI = re.compile(r'\x1b\[[0-9;]*m')
results = {'server': NAME, 'steps': [], 'started': time.strftime('%F %T')}
results['scenarios'] = wanted
# 每次執行保留原始證據，局部重跑不會抹掉已驗證場景。
previous_path = os.path.join(RESULTS, NAME + '.json')
previous = json.load(open(previous_path)) if os.path.isfile(previous_path) else None
if previous:
    history = os.path.join(RESULTS, 'history')
    os.makedirs(history, exist_ok=True)
    archive = os.path.join(history, NAME + '-' + time.strftime('%Y%m%d-%H%M%S') + '.json')
    json.dump(previous, open(archive, 'w'), ensure_ascii=False, indent=1)
    results['previous_evidence'] = os.path.relpath(archive, ROOT)


def terminate(signum, frame):
    # timeout 的 SIGTERM 也必須走 finally，停止獨立 process group 中的 server/bot。
    raise KeyboardInterrupt('acceptance terminated')


signal.signal(signal.SIGTERM, terminate)


def strip(s):
    return ANSI.sub('', s)


def step(name, ok, **evidence):
    results['steps'].append({'step': name, 'ok': bool(ok), **evidence})
    print(('PASS ' if ok else 'FAIL ') + name, {k: (v if len(str(v)) < 300 else str(v)[:300] + '…') for k, v in evidence.items()}, flush=True)
    save()


def save():
    json.dump(results, open(os.path.join(RESULTS, NAME + '.json'), 'w'), ensure_ascii=False, indent=1)


def repo_dir(s, dim='minecraft.overworld'):
    return os.path.join(s.world, '.worldgit') if dim == 'minecraft.overworld' else (os.path.join(s.world,'dimensions',*dim.split('.',1),'.worldgit') if version=='26.2' else os.path.join(s.dir,'world_nether' if dim=='minecraft.the_nether' else 'world_the_end','DIM-1' if dim=='minecraft.the_nether' else 'DIM1','.worldgit'))


def git(s, dim, *args):
    r = subprocess.run(['git', '--git-dir', repo_dir(s, dim)] + list(args), capture_output=True, text=True)
    return r.stdout.strip()


def commits(s, dim='minecraft.overworld'):
    out = git(s, dim, 'log', '--format=%H')
    return out.split() if out else []


def sections_of(text, dim='minecraft:overworld'):
    m = re.search(re.escape(dim) + r'\s+\S+\s+section (\d+)', strip(text))
    return int(m.group(1)) if m else None


def shadow_world(s):
    """伺服器運行中 CLI 會因 session.lock 拒絕；建立只含 level.dat 與空 region 目錄的影子世界，.worldgit 以符號連結指向真正的 repo，
    讓 CLI 以「另一端」身分讀同一份歷史（log／commit 對 commit 的 diff 不需要世界檔案）。"""
    import shutil
    sh = os.path.join(WORK, 'paper-delivery', 'shadow', NAME)
    shutil.rmtree(sh, ignore_errors=True)
    os.makedirs(os.path.join(sh, 'world'))
    lvl = os.path.join(s.world, 'level.dat')
    shutil.copy(lvl, os.path.join(sh, 'world', 'level.dat'))
    for d in ('region',):
        os.makedirs(os.path.join(sh, 'world', d))
    if version == '1.21.11':
        for extra in ('world_nether/DIM-1/region', 'world_the_end/DIM1/region'):
            os.makedirs(os.path.join(sh, extra))
    else:
        for dim in ('the_nether', 'the_end', 'overworld'):
            os.makedirs(os.path.join(sh, 'world', 'dimensions', 'minecraft', dim, 'region'), exist_ok=True)
    os.symlink(os.path.join(s.world, '.worldgit'), os.path.join(sh, 'world', '.worldgit'))
    return os.path.join(sh, 'world')


def plugin_problems(s):
    bad = []
    lines = s.lines_since(0)
    for i, l in enumerate(lines):
        if re.search(r'\[WorldGit\]', l) and re.search(r'(WARN|ERROR)\]', l):
            bad.append(strip(l))
        elif 'org.worldgit' in l and l.startswith('\tat '):
            bad.append(strip(l))
        elif re.search(r'\[(ERROR|WARN)\].*org\.worldgit', l):
            bad.append(strip(l))
    return bad


def settle(s, seconds=6):
    time.sleep(seconds)


def scenario_basic(s):
    # 測試世界（baseline）的底噪是 Phase 0 已記錄的事實：作物/珊瑚生長、流體、活生物。驗收 fixture 關掉隨機刻與生物生成，
    # 並讓 bot 先進場、把周圍 chunk 載入，之後才 init。活世界仍會有真實底噪，不能把「沒有玩家編輯」等同「沒有世界變動」。
    bot = s.bot('WgBot')
    # 1.21.11 起 gamerule 改名為 snake_case（舊名稱會被拒絕）；實際回應記進結果以便確認有生效
    rules = [s.cmd('gamerule ' + g, r'(?i)game ?rule|incorrect|unknown', 20) for g in ('random_tick_speed 0', 'spawn_mobs false', 'advance_weather false')]
    results['fixture_gamerules'] = [strip(r)[-160:] for r in rules]
    s.cmd('gamemode creative WgBot')
    # baseline 含燃燒中的熔爐及排程流體 tick；先等它們穩定，再建立受測基準。
    time.sleep(8 if results.get('fixture_kind') == 'synthetic-flat' else 45)
    s.cmd('kill @e[type=!minecraft:player]')  # baseline 海洋的活生物會一直移動，實體 diff 會不斷產生變動（見 docs/11 底噪發現）
    time.sleep(2)
    s.cmd('kill @e[type=!minecraft:player]')  # 前一次 kill 產生的掉落物也要清除。
    time.sleep(2)
    s.cmd('wg init --world world --all', r'init 完成|失敗|尚未', 900)
    n0 = len(commits(s))
    step('init 建立三個維度 repo 與第一個 commit', n0 == 1 and all(len(commits(s, d)) == 1 for d in ['minecraft.the_nether', 'minecraft.the_end']), commits_overworld=n0)
    settle(s, 5)
    results['fixture_settle_commit'] = strip(s.cmd('wg commit -m fixture-settle', r'overworld [0-9a-f]{8}|沒有變動|失敗', 120))
    if len(commits(s)) != n0:
        results['fixture_settle_diff'] = strip(wgit(['--world', shadow_world(s), 'diff', 'HEAD~1', 'HEAD', '--blocks', '--dimension', 'minecraft:overworld'], check=False))[:5000]
    n0 = len(commits(s))
    out1 = strip(s.cmd('wg commit -m first-noop', r'沒有變動|失敗', 120))
    out2 = strip(s.cmd('wg commit -m second-noop', r'沒有變動|失敗', 120))
    step('沒人動時連續 commit 兩次：沒有新 commit', len(commits(s)) == n0 and '沒有變動' in out1 and '沒有變動' in out2, noop1=out1.count('沒有變動'), noop2=out2.count('沒有變動'), commits=len(commits(s)))
    if len(commits(s)) != n0:  # 沒人動卻產生了 commit：把那個 commit 的內容留作證據（找底噪來源）
        results['noop_commit_diff'] = strip(wgit(['--world', shadow_world(s), 'diff', 'HEAD~1', 'HEAD', '--blocks', '--dimension', 'minecraft:overworld'], check=False))[:2500]
        n0 = len(commits(s))  # 以下步驟以此為基準，避免連鎖失敗
    time.sleep(1)
    st0 = strip(s.cmd('wg status', r'沒有變動|section', 120))
    block = place_block(bot)
    step('bot 放置一格', True, block=block)
    time.sleep(2)
    st1 = s.cmd('wg status', r'section \d+', 120)
    sec = sections_of(st1)
    step('status 恰好 1 個 section', sec == 1, status_before=strip(st0), status_after=strip(st1))
    out = strip(s.cmd('wg commit -m bot-block', r'overworld [0-9a-f]{8}|沒有變動|失敗', 120))
    c1 = commits(s)
    step('commit 後 overworld 多一個 commit', len(c1) == n0 + 1, commit_output=out[:400])
    # CLI（另一端）看到同一份歷史：log 與 commit 對 commit 的 diff
    shadow = shadow_world(s)
    cli_log = wgit(['--world', shadow, 'log', '-n', '5'], check=False)
    cli_diff = wgit(['--world', shadow, 'diff', 'HEAD~1', 'HEAD', '--blocks', '--dimension', 'minecraft:overworld'], check=False)
    step('CLI wgit log 看到插件的 commit', 'bot-block' in cli_log, log=strip(cli_log)[:500])
    x, y, z = block
    step('CLI wgit diff HEAD~1 HEAD 看到同一格', f'({x},{y},{z}) minecraft:air → minecraft:stone' in cli_diff and '1 sections，+1 -0 ~0' in cli_diff, diff=strip(cli_diff)[:600])
    step('插件沒有 WARN/ERROR 輸出', not plugin_problems(s), problems=plugin_problems(s)[-5:])
    return bot


def scenario_attribution(s, bot):
    bot2 = s.bot('WgBot2')
    time.sleep(6)
    s.cmd('gamemode creative WgBot2')
    # 把 bot2 傳到離 bot1 很遠的地方（>= 6 chunk），確保是不同 chunk
    p1 = bot.ask('pos', 'pos', 10)['pos']
    fx, fy, fz = int(p1['x'] + 96), int(p1['y']), int(p1['z'] + 96)
    s.cmd(f'tp WgBot2 {fx} {fy + 2} {fz}')
    time.sleep(8)
    # 遠處可能是海或空中：先用主控台鋪一塊 7x7 石頭平台當放置參考面，再 commit 一次當新基準（平台不算在受測的歸屬內）
    s.cmd(f'fill {fx - 3} {fy - 1} {fz - 3} {fx + 3} {fy - 1} {fz + 3} minecraft:stone')
    time.sleep(2)
    s.cmd(f'tp WgBot2 {fx} {fy} {fz}')
    time.sleep(2)
    s.cmd('wg commit -m platform', r'沒有變動|overworld [0-9a-f]{8}|失敗', 120)
    b1 = place_block(bot, -1, 'dirt')
    b2 = place_block(bot2, 1, 'gold_block')
    time.sleep(2)
    out = strip(s.cmd('wg commit -m two-authors', r'overworld [0-9a-f]{8}|沒有變動|失敗', 120))
    head = git(s, 'minecraft.overworld', 'log', '-1', '--format=%an|%ae|%cn|%B')
    step('commit 帶兩位作者（primary + co-author trailer）', 'WgBot' in head and 'WgBot2' in head, head=head[:900], blocks=[b1, b2])
    contrib = [l for l in head.splitlines() if 'Contribution' in l]
    chunks1 = (b1[0] >> 4, b1[2] >> 4)
    chunks2 = (b2[0] >> 4, b2[2] >> 4)
    step('兩個 bot 的 chunk 不同且各自歸屬', chunks1 != chunks2 and len(contrib) >= 2, chunks=[chunks1, chunks2], contribution_trailers=contrib)
    bot2.stop()
    return bot2


def scenario_mod(s, platform_name):
    modbot = s.bot('WgBot3', mod=True)
    time.sleep(8)
    st = modbot.ask('stats', 'stats', 10)
    step('Fabric 模組握手（bot 模擬）：收到 hello 並回覆', st['received']['hello'] >= 1 and st['ready'], stats=st['received'])
    s.cmd('gamemode creative WgBot3')
    pos = modbot.ask('pos', 'pos', 10)['pos']
    place_block(modbot, 2, 'stone')
    time.sleep(2)
    modbot.ask('chat /wg status --show', 'chat_sent', 10)
    time.sleep(8)
    st = modbot.ask('stats', 'stats', 10)['received']
    step('/wg status --show：模組端收到 status 封包', st['status'] >= 1 and any(p['kind'] == 3 and p['seen'] == p['parts'] for p in st['previews'].values()), received=st)
    modbot.ask('chat /wg diff --show', 'chat_sent', 10)
    time.sleep(10)
    st = modbot.ask('stats', 'stats', 10)['received']
    step('/wg diff --show：模組端收到 diff（鬼影）封包且分包收齊', st['diff'] >= 1 and any(p['kind'] == 2 and p['seen'] == p['parts'] for p in st['previews'].values()), received=st)
    modbot.ask('chat /wg clear', 'chat_sent', 10)
    time.sleep(2)
    st = modbot.ask('stats', 'stats', 10)['received']
    step('/wg clear：模組端收到 clear', st['clear'] >= 1, received=st)
    # 沒有模組的玩家：聊天提示
    plain = s.bot('WgBot4')
    time.sleep(8)
    plain.ask('chat /wg status --show', 'chat_sent', 10)
    time.sleep(6)
    chats = [e['t'] for e in plain.events('chat')]
    step('沒有模組的玩家：聊天中有提示與清單', any('沒有 WorldGit 模組' in c or 'does not have the WorldGit mod' in c for c in chats), chats=chats[-6:])
    plain.stop()
    modbot.stop()
    s.cmd('wg commit -m after-mod', r'overworld [0-9a-f]{8}|沒有變動|失敗', 120)


def scenario_display(s, bot):
    """display entity fallback：A 請求 --show，B 在旁邊但沒請求；B 必須看不到。"""
    obs = s.bot('WgBot4')
    time.sleep(6)
    count = lambda b: b.ask('entities', 'entities', 10)['displays']
    tagged = lambda: s.cmd('execute if entity @e[tag=worldgit_preview]', r'Test (passed|failed)', 20)
    tagged_n = lambda: int(m.group(1)) if (m := re.search(r'(?i)count: (\d+)', strip(tagged()))) else 0
    def wait_shown():
        for _ in range(15):  # diff／status 要重新掃描，慢的時候超過數秒；輪詢到伺服器端出現標記實體，再多等 2 秒讓客戶端收齊
            time.sleep(1)
            if tagged_n():
                break
        time.sleep(2)
    s.cmd('wg commit -m before-display', r'overworld [0-9a-f]{8}|沒有變動|失敗', 120)
    b1 = place_block(bot, 2, 'sand')
    b2 = place_block(bot, -2, 'sand')
    time.sleep(3)
    step('display：前置放置兩格', True, blocks=[b1, b2])
    bot.ask('chat /wg diff --show', 'chat_sent', 10)
    wait_shown()
    a, o, n = count(bot), count(obs), tagged_n()
    step('display：沒模組的請求者看到 2 個 BlockDisplay、旁邊沒請求的玩家看到 0、伺服器端有 2 個標記實體', a == 2 and o == 0 and n == 2, requester=a, observer=o, server_tagged=n)
    chats = [e['t'] for e in bot.events('chat')]
    step('display：聊天仍有沒有模組的提示與 display 說明', any('沒有 WorldGit 模組' in c or 'does not have the WorldGit mod' in c for c in chats) and any('display entit' in c for c in chats), chats=chats[-4:])
    st = strip(s.cmd('wg status', r'section \d+|沒有變動', 120))
    ent = re.findall(r'(?:實體|entities|entity) (\d+)', st)
    step('display：預覽實體不進入 WorldGit 的實體快照（status 沒有實體變動、section 數等於兩格所在的 chunk 數）', sections_of(st) == len({(b[0] >> 4, b[2] >> 4) for b in (b1, b2)}) and all(int(x) == 0 for x in ent), status=st[-900:], entity_counts=ent)
    bot.ask('chat /wg clear', 'chat_sent', 10)
    time.sleep(3)
    step('display：/wg clear 清掉請求者的實體（客戶端與伺服器端）', count(bot) == 0 and tagged_n() == 0, requester=count(bot), server_tagged=tagged_n())
    bot.ask('chat /wg status --show', 'chat_sent', 10)
    wait_shown()
    a, o, n = count(bot), count(obs), tagged_n()
    step('display：status --show 畫區域包圍盒（每個 section 12 條邊；上限 20 → 只畫 1 個 section、其餘省略），只對請求者可見', a == 12 and o == 0 and n == 12, requester=a, observer=o, server_tagged=n)
    time.sleep(12)
    step('display：display-seconds 到期後自動移除', count(bot) == 0 and tagged_n() == 0, requester=count(bot), server_tagged=tagged_n())
    bot.ask('chat /wg diff --show', 'chat_sent', 10)
    shown = 0
    for _ in range(15):  # diff 要重新掃描，慢的時候超過 4 秒；輪詢到實體出現
        time.sleep(1)
        shown = tagged_n()
        if shown:
            break
    obs.stop()
    bot.stop()
    time.sleep(4)
    step('display：玩家登出後實體被移除', shown == 2 and tagged_n() == 0, before_quit=shown, after_quit=tagged_n())
    nb = s.bot('WgBot')
    time.sleep(6)
    s.cmd('gamemode creative WgBot')
    out = strip(s.cmd('wg commit -m after-display', r'overworld [0-9a-f]{8}|沒有變動|失敗', 120))
    step('display：commit 成功且沒有因預覽實體產生額外 commit 錯誤', '失敗' not in out, out=out[:300])
    return nb


def scenario_worldedit(s, bot, fawe):
    pos = bot.ask('pos', 'pos', 10)['pos']
    x, y, z = int(pos['x']), int(pos['y']), int(pos['z'])
    before = len(commits(s))
    # 3x3 chunk 範圍的 //set，在地面上方 20 格以免撞到未載入的 chunk
    bot.ask(f'chat //pos1 {x - 24},{y + 20},{z - 24}', 'chat_sent', 10)
    bot.ask(f'chat //pos2 {x + 24},{y + 24},{z + 24}', 'chat_sent', 10)
    time.sleep(1)
    bot.ask('chat //set stone', 'chat_sent', 10)
    time.sleep(10)
    out = s.cmd('wg status', r'section \d+', 120)
    sec = sections_of(out)
    step(('FAWE' if fawe else 'WorldEdit') + ' //set 大範圍編輯被偵測到', sec is not None and sec >= 9, sections=sec, status=strip(out)[:500])
    out = strip(s.cmd('wg commit -m worldedit-set', r'overworld [0-9a-f]{8}|沒有變動|失敗', 180))
    head = git(s, 'minecraft.overworld', 'log', '-1', '--format=%an|%B')
    step('WorldEdit 作者歸屬：commit 的作者含 WgBot 與 worldedit 原因', 'WgBot' in head and 'worldedit' in head, head=head[:600])
    log = [l for l in s.lines_since(0) if 'FAWE' in l or 'WorldEdit' in l and 'WorldGit' in l or 'allowed-plugins' in l][:6]
    step('插件掛接 WorldEdit/FAWE 的 log', any('已掛接' in l for l in log), log=log)


def scenario_auto(platform, version, baseline):
    s = Server(platform, version, baseline=baseline, config={'auto-commit': {'enabled': True, 'interval-minutes': 1, 'max-wait-minutes': 1, 'min-changed-sections': 1, 'on-quit': True, 'quit-delay-seconds': 5, 'on-shutdown': False}})
    try:
        s.start()
        s.cmd('wg init --world world --all', r'init 完成|失敗', 900)
        n0 = len(commits(s))
        bot = s.bot('WgBot')
        time.sleep(8)
        s.cmd('gamemode creative WgBot')
        place_block(bot)
        t0 = time.time()
        # 定時：最多等 3 分鐘
        deadline = t0 + 200
        while time.time() < deadline and len(commits(s)) == n0:
            time.sleep(3)
        waited = time.time() - t0
        head = git(s, 'minecraft.overworld', 'log', '-1', '--format=%an|%cn|%B')
        step('自動 commit（定時）觸發並標記 auto、作者為放方塊的玩家', len(commits(s)) > n0 and 'WorldGit-Auto: true' in head and 'WgBot' in head, waited_s=round(waited), head=head[:500])
        n1 = len(commits(s))
        time.sleep(80)
        step('沒有變動時自動 commit 不產生新 commit', len(commits(s)) == n1, commits=len(commits(s)))
        # 登出觸發獨立測試：延長定時間隔，避免下一個定時工作先提交同一格而造成競速誤判。
        s.stop()
        s = Server(platform, version, baseline=baseline, fresh=False, config={'auto-commit': {'enabled': True, 'interval-minutes': 10, 'max-wait-minutes': 10, 'on-quit': True, 'quit-delay-seconds': 3, 'on-shutdown': False}})
        s.start()
        bot = s.bot('WgBot')
        time.sleep(5)
        s.cmd('gamemode creative WgBot')
        n1 = len(commits(s))
        place_block(bot, -1, 'dirt')
        bot.stop()
        deadline = time.time() + 40
        while time.time() < deadline and len(commits(s)) == n1:
            time.sleep(2)
        head = git(s, 'minecraft.overworld', 'log', '-1', '--format=%s|%B')
        step('玩家登出觸發自動 commit', len(commits(s)) > n1 and '登出' in head, head=head[:400])
    finally:
        s.stop()


def scenario_sigterm(platform, version, baseline):
    """SIGTERM（kill、容器停止）而不是 /stop：Folia 的 JVM 關閉鉤子必須等到世界存檔完成才離線 commit。"""
    s = Server(platform, version, baseline=baseline, run_label=f'sigterm-{platform}-{version}', config={'auto-commit': {'enabled': False, 'on-shutdown': True}})
    try:
        s.start()
        s.cmd('wg init --world world --all', r'init 完成|失敗', 900)
        bot = s.bot('WgBot')
        time.sleep(6)
        s.cmd('gamemode creative WgBot')
        n = len(commits(s))
        block = place_block(bot, 2, 'glass')
        time.sleep(2)
        s.proc.send_signal(signal.SIGTERM)
        try:
            s.proc.wait(150)
        except subprocess.TimeoutExpired:
            pass
        exited = s.proc.poll() is not None
    finally:
        s.stop()
    logf = os.path.join(s.dir, 'plugins', 'WorldGit', 'shutdown-commit.log')
    hook_log = open(logf).read() if os.path.isfile(logf) else ''
    head = git(s, 'minecraft.overworld', 'log', '-1', '--format=%s')
    out = wgit(['--world', s.world, 'status'], check=False)
    summaries = re.findall(r'(\d+) chunks，(\d+) sections，\+(\d+) -(\d+) ~(\d+) !(\d+)', strip(out))
    step('SIGTERM 關閉：JVM 自行結束、關閉前 commit 建立、離線 CLI status 沒有變動', exited and len(commits(s)) == n + 1 and '關閉前' in head and all(all(int(x) == 0 for x in row) for row in summaries),
         exited=exited, before=n, after=len(commits(s)), head=head, block=block, hook_log=hook_log[-500:], status=strip(out)[:300])


def main():
    if 'phase3' in wanted:
        from phase3 import run
        return run(platform, version)
    if 'phase2-cancel' in wanted:
        from phase2_shutdown import run
        return run(platform, version, shutdown=False)
    if 'phase2-entities' in wanted:
        from phase2_entities import run
        return run(platform, version)
    if 'phase2-shutdown' in wanted:
        from phase2_shutdown import run
        return run(platform, version)
    if 'phase2' in wanted:
        from phase2 import run
        return run(platform,version)
    with BenchLock():
        baseline = os.path.join(WORK, 'paper-delivery', 'fixtures', 'acceptance-flat-' + version)
        if not os.path.isdir(baseline):
            subprocess.run([JAVA[version], '-Xmx512m', '-cp', os.path.join(ROOT, 'cli/build/libs/wgit.jar'),
                os.path.join(ROOT, 'paper/tools/ScaleFixture.java'), version,
                os.path.join(WORK, 'worlds', version, 'baseline'), baseline, '16'], check=True, timeout=180)
        results['fixture_kind'] = 'synthetic-flat'
        s = Server(platform, version, baseline=baseline, plugins=[f'fawe-{version}'] if platform == 'paper' else ([f'we-{"7.4.2" if version == "1.21.11" else "7.4.5"}']), config={'auto-commit': {'enabled': False, 'on-shutdown': True}, 'show': {'display-max-entities': 20, 'display-seconds': 10}})
        try:
            s.start()
            results['console_log'] = os.path.relpath(s.evidence_log, ROOT)
            results['plugin_enable_log'] = [strip(l) for l in s.lines_since(0) if 'WorldGit' in l][:8]
            bot = bot2 = None
            try:
                if 'basic' in wanted:
                    bot = scenario_basic(s)
                if bot is None:
                    s.cmd('wg init --world world --all', r'init 完成|失敗', 900)
                    bot = s.bot('WgBot'); time.sleep(8); s.cmd('gamemode creative WgBot')
                if 'attribution' in wanted:
                    scenario_attribution(s, bot)
                if 'mod' in wanted:
                    scenario_mod(s, platform)
                if 'display' in wanted:
                    bot = scenario_display(s, bot)
                if 'worldedit' in wanted:
                    scenario_worldedit(s, bot, platform == 'paper')
            except Exception:
                step('場景例外', False, trace=traceback.format_exc())
            results['plugin_problem_lines'] = plugin_problems(s)[-20:]
            if 'shutdown' in wanted:
                n = len(commits(s))
                # 製造一個未 commit 的變動，讓關閉前 commit 有東西可寫
                try:
                    place_block(bot, 2, 'glass')
                except Exception:
                    step('shutdown 前置放置失敗', False, trace=traceback.format_exc())
                time.sleep(2)
                results['_shutdown_n'] = n
        finally:
            s.stop()
        if 'shutdown' in wanted and '_shutdown_n' in results:
            n = results.pop('_shutdown_n')
            after = len(commits(s))
            head = git(s, 'minecraft.overworld', 'log', '-1', '--format=%s|%B')
            out = wgit(['--world', s.world, 'status'], check=False)
            summaries = re.findall(r'(\d+) chunks，(\d+) sections，\+(\d+) -(\d+) ~(\d+) !(\d+)；實體 (\d+)，biome (\d+)，metadata (\d+)', strip(out))
            if platform == 'folia':
                # Folia：onDisable 沒有單一擁有執行緒，改由 JVM 關閉鉤子在世界存檔完成（session.lock 釋放）後以離線路徑提交。
                logf = os.path.join(s.dir, 'plugins', 'WorldGit', 'shutdown-commit.log')
                hook_log = open(logf).read() if os.path.isfile(logf) else ''
                results['folia_shutdown_log'] = hook_log[-1500:]
                step('Folia：關閉後離線 commit 建立（鉤子 log、commit 數 +1、標記為關閉前自動存檔點）',
                     after == n + 1 and '關閉前' in head and 'WorldGit-Auto: true' in git(s, 'minecraft.overworld', 'log', '-1', '--format=%B'),
                     before=n, after=after, head=head[:300], hook_log=hook_log[-600:])
            else:
                step('伺服器關閉前自動 commit', after == n + 1 and '關閉' in head, before=n, after=after, head=head[:300])
            if summaries is not None: step('伺服器停止後離線 CLI status 沒有變動（線上快照與磁碟內容一致）', len(summaries) == 3 and all(all(int(n) == 0 for n in row) for row in summaries), status=strip(out)[:1500])
    if 'sigterm' in wanted:
        with BenchLock():
            scenario_sigterm(platform, version, baseline)
    if 'auto' in wanted:
        with BenchLock():
            scenario_auto(platform, version, baseline)
    results['finished'] = time.strftime('%F %T')
    save()
    failed = [x['step'] for x in results['steps'] if not x['ok']]
    print('FAILED:', failed if failed else 'none')
    if os.environ.get('WG_COMPACT_EVIDENCE') == '1':
        shutil.rmtree(s.dir, ignore_errors=True)
    return bool(failed)


def sections_of_cli(out):
    return 0 if re.search(r'(沒有變動|nothing|0 section)', strip(out)) else -1


if __name__ == '__main__':
    sys.exit(main())
