"""光照外溢：改一個方塊後，鄰近沒改方塊的 chunk 是否也被設 unsaved。 python3 run_light.py <paper|folia> <ver> <bot|nobot>"""
import sys, json, time
from harness import *
plat, ver, mode = sys.argv[1], sys.argv[2], sys.argv[3]
s = Server(plat, ver, default_plugins(ver), view=8)
try:
    s.start()
    if mode == 'bot':
        b = connect_bot(s); s.send('gamemode creative WgBot'); s.send('tp WgBot 88 191 40'); time.sleep(3)
    s.cmd('watch off -3 12 -3 5', 'watch')
    s.cmd('prep 10', 'prep_done', 120)
    m = s.mark()
    s.send('wgpoc light'); s.wait(r'"suite":"light"', 120, m)
    for r in s.results(m):
        if r['kind'] == 'light_case': print(json.dumps(r))
finally:
    s.stop()
