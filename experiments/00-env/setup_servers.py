#!/usr/bin/env python3
"""下載 Paper/Folia server jar 並寫好 server.properties / eula.txt（冪等）。

產物：.work/servers/{paper,folia}-{1.21.11,26.2}/
用法：python3 setup_servers.py [--force]
"""
import hashlib, json, sys, urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
SERVERS = ROOT / ".work" / "servers"
API = "https://fill.papermc.io/v3/projects/{p}/versions/{v}/builds"
VERSIONS = ["1.21.11", "26.2"]
PROJECTS = ["paper", "folia"]
UA = {"User-Agent": "WorldGit-phase0/0.1 (local test)"}

PROPS = """# WorldGit Phase 0 測試伺服器（僅本機）
online-mode=false
server-ip=127.0.0.1
server-port={port}
level-seed=worldgit
level-name=world
view-distance=4
simulation-distance=4
spawn-monsters=false
spawn-protection=0
max-players=2
motd=WorldGit test {name}
difficulty=easy
gamemode=creative
enable-rcon=false
enable-query=false
sync-chunk-writes=true
"""


def get(url):
    return urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=60).read()


def pick_build(project, ver):
    data = json.loads(get(API.format(p=project, v=ver)))
    builds = data if isinstance(data, list) else data.get("builds", [])
    builds.sort(key=lambda b: b["id"], reverse=True)
    stable = [b for b in builds if b.get("channel") == "STABLE"]
    return (stable or builds)[0]  # 沒有 stable 時退而求其次用最新 build（例如 Folia 26.2 只有 BETA）


def main():
    force = "--force" in sys.argv
    port = 25601
    info = {}
    for proj in PROJECTS:
        for ver in VERSIONS:
            name = f"{proj}-{ver}"
            d = SERVERS / name
            d.mkdir(parents=True, exist_ok=True)
            b = pick_build(proj, ver)
            dl = b["downloads"]["server:default"]
            jar = d / "server.jar"
            want = dl["checksums"]["sha256"] if "checksums" in dl else None
            ok = jar.exists() and want and hashlib.sha256(jar.read_bytes()).hexdigest() == want
            if force or not ok:
                print(f"下載 {name} build {b['id']} ({b['channel']}) ...")
                jar.write_bytes(get(dl["url"]))
            else:
                print(f"{name} build {b['id']} 已存在")
            (d / "server.properties").write_text(PROPS.format(port=port, name=name))
            (d / "eula.txt").write_text("# 本機測試用途，eula=true 由測試腳本寫入\neula=true\n")
            info[name] = {"build": b["id"], "channel": b["channel"], "port": port, "url": dl["url"],
                          "sha256": want}
            port += 1
    (SERVERS / "builds.json").write_text(json.dumps(info, indent=2))
    print(json.dumps(info, indent=2))


if __name__ == "__main__":
    main()
