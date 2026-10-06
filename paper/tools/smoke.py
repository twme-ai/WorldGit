"""冒煙：啟動 → 載入插件 → init → status → commit（沒變動）。用法：python3 smoke.py paper 1.21.11"""
import sys, time
sys.path.insert(0, __import__('os').path.dirname(__file__))
from harness import *

platform, version = sys.argv[1], sys.argv[2]
with BenchLock():
    s = Server(platform, version, config={'auto-commit': {'enabled': False}})
    try:
        s.start()
        print('\n'.join(l for l in s.lines_since(0) if 'WorldGit' in l or 'ERROR' in l)[:3000])
        print(s.cmd('wg init --world world --all', r'init 完成|失敗|尚未', 600))
        print(s.cmd('wg status', r'沒有變動|section', 120))
        print(s.cmd('wg commit -m smoke', r'沒有變動|失敗|[0-9a-f]{8} ', 120))
        print(s.cmd('wg log', r'初始化世界|還沒有', 60))
        print('PROBLEMS', s.problems())
    finally:
        s.stop()
