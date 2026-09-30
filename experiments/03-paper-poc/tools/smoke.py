import sys, json, time
from harness import *
plat, ver = sys.argv[1], sys.argv[2]
plugins = ['pe']
s = Server(plat, ver, plugins)
try:
    s.start()
    time.sleep(3)
    m = s.mark()
    s.cmd('info', 'info')
    for r in s.results(0):
        if r['kind'] in ('boot', 'hook', 'info'):
            print(json.dumps(r))
    print('ERR', s.errors_in_log(0)[:10])
finally:
    s.stop()
