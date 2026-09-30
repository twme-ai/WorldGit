"""真玩家（bot）作為改動來源：放置方塊、挖掘方塊、往箱子放物品。 python3 run_bot_sources.py <paper|folia> <ver>"""
import sys, json, time
from harness import *
plat, ver = sys.argv[1], sys.argv[2]
s = Server(plat, ver, default_plugins(ver), view=8)
out = os.path.join(WORK, 'paper-poc', f'bot-sources-{plat}-{ver}.jsonl')
res = []
def window(name, cx, cz, action, wait=3):
    s.cmd(f'sec clearflag {cx} {cz}', 'sec_clearflag'); time.sleep(0.5)
    s.cmd(f't begin {name} {cx} {cz}', 't_begin')
    action(); time.sleep(wait)
    r = s.cmd(f't end {name} {cx} {cz}', 't_end'); res.append(r)
    print(f"== {name} chunk({cx},{cz}) flag={r['flag']} pkt={ {k:(v['packets'],v['entries']) for k,v in (r['pkt'] or {}).items()} }", flush=True)
try:
    s.start()
    b = connect_bot(s)
    s.send('gamemode creative WgBot'); s.send('tp WgBot 40 190 24'); time.sleep(3)
    s.cmd('watch off -3 12 -3 5', 'watch')
    s.cmd('prep 12', 'prep_done', 120)
    s.cmd('saveall', 'saveall'); time.sleep(4)
    s.send('tp WgBot 40 190.1 24'); time.sleep(2)
    b.ask('give stone', 'give')
    window('bot_place_block', 2, 1, lambda: b.ask('place 40 189 26 0 1 0', 'placed'))
    window('bot_break_block', 2, 1, lambda: b.ask('dig 40 190 26', 'dug'))
    s.send('setblock 42 190 26 minecraft:chest'); time.sleep(1)
    window('bot_chest_deposit', 2, 1, lambda: b.ask('chestput 42 190 26 stone 5', 'chestput'))
    print('LOGERR', [l for l in s.errors_in_log(0) if not any(k in l for k in ('ROOT USER', '****', 'OFFLINE', 'no attempt', 'hackers', 'online-mode', 'latest build', 'behind', 'recommended', 'papermc.io', 'RISKS', 'MORE INFORMATION', 'madelinemiller'))][:20])
    with open(out, 'w') as f:
        for r in res: f.write(json.dumps(r) + '\n')
finally:
    s.stop()
