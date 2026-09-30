"""誤報觀察（fp）+ chunk 載入旗標（load）+ 自動存檔清旗標（autosave）+ 時間戳同秒碰撞（tsdouble）。
python3 run_fp.py <paper|folia> <ver> <bot|nobot>"""
import sys, json, time
from harness import *
plat, ver, mode = sys.argv[1], sys.argv[2], sys.argv[3]
out = os.path.join(WORK, 'paper-poc', f'fp-{plat}-{ver}-{mode}.jsonl')
# autosave-interval 200 tick：只為 autosave 子測試；fp 子測試期間為避免自動存檔清掉旗標，fp 另外在 6000 的設定跑（見下 two-phase）
phase = sys.argv[4] if len(sys.argv) > 4 else 'fp'
s = Server(plat, ver, default_plugins(ver), view=8, autosave_ticks=(200 if phase == 'autosave' else None))
try:
    s.start()
    if mode == 'bot':
        b = connect_bot(s)
        s.send('gamemode creative WgBot'); s.send('tp WgBot 56 191 24')
        time.sleep(3)
    s.cmd('watch off -3 12 -3 5', 'watch')
    s.cmd('prep 8', 'prep_done', 120)
    time.sleep(3)
    m = s.mark()
    if phase == 'fp':
        s.cmd('fp 60', 'fp_result', 200)
        s.cmd('load', 'load_test', 120)
        time.sleep(40)
        s.cmd('tsdouble', 'ts_double', 30)
    else:
        s.cmd('autosave', 'autosave_result', 300)
    res = s.results(m)
    with open(out, 'w') as f:
        for r in res: f.write(json.dumps(r) + '\n')
    for r in res:
        if r['kind'] in ('fp_begin', 'fp_result', 'load_test', 'ts_double', 'autosave_begin', 'autosave_result', 'error'):
            print(json.dumps(r, ensure_ascii=False))
finally:
    s.stop()
