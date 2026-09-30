"""FAWE/WorldEdit：EditSessionEvent 是否看得到逐格變動與 actor。 python3 run_we.py <paper|folia> <ver> <fawe|we>"""
import sys, json, time
from harness import *
plat, ver, kind = sys.argv[1], sys.argv[2], sys.argv[3]
plug = {'fawe': 'fawe-' + ver, 'we': 'we-7.4.2' if ver == '1.21.11' else 'we-7.4.5'}[kind]
out = os.path.join(WORK, 'paper-poc', f'we-{plat}-{ver}-{kind}.jsonl')
s = Server(plat, ver, default_plugins(ver, plug), view=8)
res = []
def P(*a):
    print(' '.join(str(x) for x in a)); sys.stdout.flush()
def window(name, cx, cz, action, wait=4):
    """清旗標 → begin → action() → 等 → end，回傳 t_end 結果"""
    s.cmd(f'sec clearflag {cx} {cz}', 'sec_clearflag')
    time.sleep(0.5)
    s.cmd('we drain', 'we_drain')  # 清掉舊統計
    s.cmd(f't begin {name} {cx} {cz}', 't_begin')
    action()
    time.sleep(wait)
    r = s.cmd(f't end {name} {cx} {cz}', 't_end')
    res.append(r)
    sess = r.get('we') or []
    P(f'== {name} chunk({cx},{cz}) flag={r["flag"]} pkt={ {k:(v["packets"],v["entries"]) for k,v in (r["pkt"] or {}).items()} }')
    for x in sess:
        P('   session', json.dumps(x, ensure_ascii=False))
    return r
try:
    s.start()
    b = connect_bot(s)
    s.send('gamemode creative WgBot'); s.send('tp WgBot 40 191 40')
    time.sleep(3)
    s.cmd('watch off -3 12 -3 5', 'watch')
    s.cmd('prep 14', 'prep_done', 120)
    time.sleep(3)
    s.cmd('saveall', 'saveall'); time.sleep(3)
    m0 = s.mark()
    r = s.cmd('info', 'info'); P('info', json.dumps(r))
    for r in s.results(0):
        if r['kind'] in ('boot', 'hook'): P(json.dumps(r))
    P('--- hook status: ', [l for l in s.log_lines if 'FastAsyncWorldEdit' in l or 'WorldEdit' in l][:6])
    # 1-4：以 API 建立 EditSession
    window('api_set_noactor', 0, 1, lambda: s.send('wgpoc we api set 0'))
    window('api_set_actor_WgBot', 1, 1, lambda: s.send('wgpoc we api set 1 WgBot'))
    window('api_fast_actor_WgBot', 2, 1, lambda: s.send('wgpoc we api fast 2 WgBot'))
    window('api_pattern_actor_WgBot', 3, 1, lambda: s.send('wgpoc we api pattern 3 WgBot'))
    window('api_perblock_actor_WgBot', 4, 1, lambda: s.send('wgpoc we api blocks 4 WgBot'))
    # 5：bot 下 WE 指令（//pos1 //pos2 //set …）
    def chat(*lines):
        for l in lines:
            b.ask('chat ' + l, 'chat'); time.sleep(0.6)
    # chunk (5,1) = x 80..95, z 16..31
    window('cmd_set_WgBot', 5, 1, lambda: chat('//pos1 81,190,17', '//pos2 86,193,22', '//set gold_block'))
    window('cmd_replace_WgBot', 5, 1, lambda: chat('//replace gold_block iron_block'))
    window('cmd_undo_WgBot', 5, 1, lambda: chat('//undo'))
    window('cmd_fast_set_WgBot', 6, 1, lambda: chat('//fast', '//pos1 97,190,17', '//pos2 102,193,22', '//set emerald_block', '//fast'))
    window('cmd_copy_paste_WgBot', 5, 2, lambda: chat('//pos1 81,190,17', '//pos2 86,193,22', '//copy', '//pos1 113,194,17', '//pos2 113,194,17', '//paste'))
    # 6：多 chunk 大範圍（48x4x48 跨 cx 0..2, cz 1..3）
    def big():
        chat('//pos1 1,194,17', '//pos2 46,197,62', '//set lapis_block')
    window('cmd_big_set_3x3chunks_WgBot', 1, 2, big, wait=8)
    # 另外：整體看一下被影響的 chunk 旗標
    s.cmd('flagdump -1 10 0 4', 'flagdump')
    with open(out, 'w') as f:
        for r in res: f.write(json.dumps(r) + '\n')
    P('LOGERR', [l for l in s.errors_in_log(0) if not any(k in l for k in ('ROOT USER', '****', 'OFFLINE', 'no attempt', 'hackers', 'online-mode', 'EULA', 'latest build', 'behind', 'recommended', 'papermc.io', 'RISKS', 'MORE INFORMATION', 'madelinemiller'))][:30])
finally:
    s.stop()
