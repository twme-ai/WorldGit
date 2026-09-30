"""未載入 chunk 的 chunk IO 讀出→改→寫回，多種變體。 python3 run_io.py <paper|folia> <ver>"""
import sys, json, time
from harness import *
plat, ver = sys.argv[1], sys.argv[2]
s = Server(plat, ver, default_plugins(ver), view=4)
try:
    s.start()
    time.sleep(2)
    for i, (cx, cz, sy, blk, var) in enumerate([(9, -9, 4, 'glass', 'min'), (8, -8, 4, 'glass', 'full'), (7, -7, 3, 'gold_block', 'full')]):
        m = s.mark()
        s.send(f'wgpoc io {cx} {cz} {sy} {blk} {var}')
        s.wait(r'"kind":"io_loaded"|"kind":"error"|"kind":"io"', 60, m)
        time.sleep(1)
        for r in s.results(m):
            if r['kind'].startswith('io') or r['kind'] == 'error': print(var, json.dumps(r, ensure_ascii=False)[:600])
    print('LOGERR', [l for l in s.errors_in_log(0) if not any(k in l for k in ('ROOT USER', '****', 'OFFLINE', 'no attempt', 'hackers', 'online-mode', 'latest build', 'behind', 'recommended', 'papermc.io', 'RISKS', 'MORE INFORMATION', 'madelinemiller'))][:20])
finally:
    s.stop()
