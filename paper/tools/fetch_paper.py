#!/usr/bin/env python3
"""下載指定版本的最新 Paper 伺服器 jar（PaperMC Fill v3 API）。用法：fetch_paper.py <mc-version> <輸出檔>

GET https://fill.papermc.io/v3/projects/paper/versions/<v>/builds/latest 回傳 JSON，
下載網址在 downloads["server:default"].url。失敗（網路、API 變動）時以明確訊息結束，不留下半個檔案。
"""
import json
import sys
import time
import urllib.request

if len(sys.argv) != 3:
    raise SystemExit(__doc__.strip())
version, out = sys.argv[1:]
UA = {"User-Agent": "worldgit-ci (https://github.com/twme-ai/worldgit)"}


def get(url, tries=4):
    last = None
    for i in range(tries):
        try:
            with urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60) as r:
                return r.read()
        except Exception as e:  # noqa: BLE001
            last = e
            time.sleep(5 * (i + 1))
    raise SystemExit(f"fetch_paper: cannot GET {url}: {last}")


meta = json.loads(get(f"https://fill.papermc.io/v3/projects/paper/versions/{version}/builds/latest"))
try:
    url = meta["downloads"]["server:default"]["url"]
except KeyError:
    raise SystemExit(f"fetch_paper: unexpected API response for {version}: {str(meta)[:300]}")
data = get(url)
with open(out, "wb") as f:
    f.write(data)
print(f"paper {version} build {meta.get('id')} -> {out} ({len(data)} bytes)")
