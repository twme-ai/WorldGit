"""診斷：新載入的 chunk 的旗標時間線（逐 tick 轉換）+ 每次 save-all flush。 python3 run_diag.py <paper|folia> <ver> <bot|nobot>"""
import sys, json, time
from harness import *
plat, ver, mode = sys.argv[1], sys.argv[2], sys.argv[3]
s = Server(plat, ver, default_plugins(ver), view=6)
try:
    s.start()
    if mode == 'bot':
        b = connect_bot(s); s.send('gamemode creative WgBot'); s.send('tp WgBot 56 191 24'); time.sleep(3)
    s.cmd('watch on -3 30 -12 30', 'watch')
    time.sleep(3)
    m = s.mark()
    s.send('wgpoc load')       # 載入 (9,-9)（baseline 已生成）與 (25,25)（未生成）
    t0 = time.time()
    for i in range(1, 9):
        time.sleep(4)
        s.cmd('saveall', 'saveall')
        print(f'[t+{time.time()-t0:5.1f}s] save-all flush sent', flush=True)
    time.sleep(3)
    for r in s.results(m):
        if r['kind'] == 'flag_transition' and (r['cx'], r['cz']) in ((9, -9), (25, 25)):
            print(f"  t={(r['t']-int(t0*1000))/1000:6.2f}s chunk({r['cx']},{r['cz']}) unsaved={r['unsaved']}")
        elif r['kind'] in ('load_test',):
            print(json.dumps(r)[:600])
    # 其他 chunk 的轉換數
    cnt = {}
    for r in s.results(m):
        if r['kind'] == 'flag_transition': cnt[(r['cx'], r['cz'])] = cnt.get((r['cx'], r['cz']), 0) + 1
    print('transitions per chunk', {f'{k[0]},{k[1]}': v for k, v in sorted(cnt.items())})
    saved = [l for l in s.log_lines[m:] if 'Saved the game' in l]
    print('saved-the-game log lines', len(saved))
finally:
    s.stop()
