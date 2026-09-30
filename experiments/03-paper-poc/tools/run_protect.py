"""玩家保護：事件取消 vs 藥水效果。 python3 run_protect.py <paper|folia> <ver>"""
import sys, json, time
from harness import *
plat, ver = sys.argv[1], sys.argv[2]
s = Server(plat, ver, default_plugins(ver), view=6)
try:
    s.start()
    b = connect_bot(s)
    s.send('gamemode creative WgBot')
    time.sleep(2)
    s.cmd('prep 1', 'prep_done', 60)
    time.sleep(2)
    for mode in ('none', 'event', 'potion'):
        m = s.mark()
        s.send(f'wgpoc protect {mode} WgBot')
        s.wait(r'"kind":"protect_suffocate"|"kind":"error"', 30, m)
        for r in s.results(m):
            if r['kind'].startswith('protect') or r['kind'] == 'error':
                print(json.dumps(r, ensure_ascii=False))
        time.sleep(3)
    print('LOGERR', [l for l in s.errors_in_log(0) if not any(k in l for k in ('ROOT USER', '****', 'OFFLINE', 'no attempt', 'hackers', 'online-mode', 'EULA', 'latest build', 'behind', 'recommended', 'papermc.io', 'RISKS', 'MORE INFORMATION', 'madelinemiller'))][:20])
finally:
    s.stop()
