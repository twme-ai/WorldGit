"""診斷 2：ping-pong chunk 每次存檔之間磁碟 NBT 哪個欄位在變。"""
import sys, json, time
from harness import *
plat, ver = sys.argv[1], sys.argv[2]
cx, cz = int(sys.argv[3]), int(sys.argv[4])
s = Server(plat, ver, default_plugins(ver), view=6)
try:
    s.start()
    s.cmd('watch on -3 30 -12 30', 'watch')
    time.sleep(2)
    s.send(f'wgpoc sec clearflag {cx} {cz}')
    m = s.mark()
    # 載入：用 load suite 的第一個 chunk 太雜；直接用 flagdump 不會載入。用 tp 不行（沒玩家）。用 prep 的 ticket 機制：這裡用 console forceload
    s.send(f'forceload add {cx*16} {cz*16}')
    time.sleep(3)
    prev = None
    for i in range(5):
        s.cmd('saveall', 'saveall'); time.sleep(2)
        r = s.cmd(f'disksig {cx} {cz}', 'disksig')
        f = r['fields']
        if prev:
            diff = {k: (prev.get(k), v) for k, v in f.items() if prev.get(k) != v}
            print(f'save#{i}: changed fields vs previous save:', json.dumps(diff)[:600])
        else:
            print('first:', json.dumps(f)[:900])
        prev = f
    for r in s.results(m):
        if r['kind'] == 'flag_transition' and (r['cx'], r['cz']) == (cx, cz): print('  transition', r['t'] % 100000, r['unsaved'])
finally:
    s.stop()
