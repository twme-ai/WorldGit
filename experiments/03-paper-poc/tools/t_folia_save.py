import sys, time, json
from harness import *
plat, ver = sys.argv[1], sys.argv[2]
s = Server(plat, ver, ['pe'], view=6)
try:
    s.start()
    b = connect_bot(s); s.send('gamemode creative WgBot'); s.send('tp WgBot 24 191 24'); time.sleep(3)
    s.cmd('prep 5', 'prep_done', 60)
    time.sleep(5)
    m = s.mark()
    s.send('wgpoc savetest'); s.wait(r'"suite":"savetest"', 120, m)
    for r in s.results(m):
        if r['kind'].startswith('savetest'): print(json.dumps(r)[:400])
    print('ERR', [l[:200] for l in s.errors_in_log(0) if 'Exception' in l][:5])
finally:
    s.stop()
