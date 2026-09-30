"""suite flags：各種改動來源 × unsaved 旗標 / region 時間戳 / 封包（PacketEvents）。 python3 run_flags.py <paper|folia> <ver> [nobot]"""
import sys, json, time
from harness import *
plat, ver = sys.argv[1], sys.argv[2]
usebot = 'nobot' not in sys.argv
extra = int([a for a in sys.argv if a.startswith('extra=')][0][6:]) if any(a.startswith('extra=') for a in sys.argv) else 0
outf = os.path.join(WORK, 'paper-poc', f'flags-{plat}-{ver}' + (f'-extra{extra}' if extra else '') + ('' if usebot else '-nobot') + '.jsonl')
s = Server(plat, ver, default_plugins(ver), view=8)
res = []
try:
    s.start()
    if usebot:
        b = connect_bot(s)
        s.send('gamemode creative WgBot')
        s.send('tp WgBot 88 191 40')
        time.sleep(3)
        if extra:
            connect_extra_bots(s, extra)
            for i in range(2, extra + 2): s.send(f'tp WgBot{i} 88 191 40')
            time.sleep(3)
    s.cmd('watch off -3 12 -3 5', 'watch')
    r = s.cmd('prep 22', 'prep_done', 120)
    time.sleep(2)
    m = s.mark()
    s.send('wgpoc flags')
    s.wait(r'"suite":"flags"', 600, m)
    res = [r for r in s.results(m)]
    with open(outf, 'w') as f:
        for r in res:
            f.write(json.dumps(r) + '\n')
    for r in res:
        if r['kind'] == 'src':
            print(json.dumps(r, ensure_ascii=False))
        elif r['kind'] in ('error',):
            print('ERROR', json.dumps(r))
    print('LOGERR', [l for l in s.errors_in_log(0) if 'ROOT USER' not in l and '****' not in l and 'OFFLINE' not in l and 'no attempt' not in l][:20])
finally:
    s.stop()
