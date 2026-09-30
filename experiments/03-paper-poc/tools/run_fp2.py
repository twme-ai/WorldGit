"""誤報觀察 2：重複樣本。 python3 run_fp2.py <paper|folia> <ver> <bot|nobot> [seconds]"""
import sys, json, time
from harness import *
plat, ver, mode = sys.argv[1], sys.argv[2], sys.argv[3]
secs = int(sys.argv[4]) if len(sys.argv) > 4 else 120
s = Server(plat, ver, default_plugins(ver), view=8)
try:
    s.start()
    if mode == 'bot':
        b = connect_bot(s); s.send('gamemode creative WgBot'); s.send('tp WgBot 88 191 40'); time.sleep(3)
    s.cmd('watch off -3 12 -3 5', 'watch')
    s.cmd('prep 10', 'prep_done', 120)
    m = s.mark()
    s.cmd(f'fp2 {secs}', 'fp2_result', secs + 120)
    for r in s.results(m):
        if r['kind'] in ('fp2_begin', 'fp2_result'): print(json.dumps(r))
finally:
    s.stop()
