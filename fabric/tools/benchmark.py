"""Fabric dedicated 的 commit／switch／TPS 量測；單人量測另見 Phase 5 客戶端驗收。

python3 fabric/tools/benchmark.py 1.21.11 --label after --require-isolation
自帶 bench.lock，請勿外包 flock。
"""
import sys
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[2]/'tools/performance'))
from online import main
if __name__=='__main__':raise SystemExit(main(['fabric',*sys.argv[1:]]))
