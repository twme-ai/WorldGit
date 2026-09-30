"""診斷 3：idle 的空 chunk 被設旗標時，磁碟 NBT 是哪個部分變了（方塊/光照/其他）。 python3 run_diag3.py <paper|folia> <ver> [seconds]"""
import sys, json, time
from harness import *
plat, ver = sys.argv[1], sys.argv[2]
secs = int(sys.argv[3]) if len(sys.argv) > 3 else 150
s = Server(plat, ver, default_plugins(ver), view=8)
try:
    s.start()
    b = connect_bot(s); s.send('gamemode creative WgBot'); s.send('tp WgBot 88 191 40'); time.sleep(3)
    s.cmd('watch off -3 12 -3 5', 'watch')
    s.cmd('prep 10', 'prep_done', 120)
    time.sleep(2)
    for i in range(3): s.cmd('saveall', 'saveall'); time.sleep(4)
    before = {}
    for cx in range(0, 10):
        s.send(f'wgpoc sec clearflag {cx} 1'); 
    time.sleep(1)
    for cx in range(0, 10):
        before[cx] = s.cmd(f'disksig {cx} 1', 'disksig')['fields']
    t0 = time.time()
    m = s.mark()
    s.send('wgpoc watch on -3 12 -3 5')
    time.sleep(secs)
    flagged = sorted({r['cx'] for r in s.results(m) if r['kind'] == 'flag_transition' and r['unsaved'] and r['cz'] == 1 and 0 <= r['cx'] <= 9})
    print('flagged test chunks during', secs, 's:', flagged)
    s.cmd('saveall', 'saveall'); time.sleep(4)
    for cx in flagged:
        after = s.cmd(f'disksig {cx} 1', 'disksig')['fields']
        diff = {k: (before[cx].get(k), v) for k, v in after.items() if before[cx].get(k) != v}
        print(f'chunk {cx}: changed fields:', json.dumps(diff))
finally:
    s.stop()
