#!/usr/bin/env python3
"""用 Paper 產生測試世界並搭建測試場景，最後複製為 baseline。（冪等：每次都從乾淨伺服器目錄重建）

用法：python3 build_world.py 1.21.11 [26.2 ...]
產物：.work/worlds/<ver>/baseline/{world,world_nether,world_the_end 或新版佈局}
      .work/worlds/<ver>/build-report.json（耗時、失敗指令、大小）
      .work/worlds/<ver>/server-log.txt、command-log.txt
"""
import json, shutil, subprocess, sys, threading, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]
SERVERS = ROOT / ".work" / "servers"
WORLDS = ROOT / ".work" / "worlds"
JAVA = {"1.21.11": "/usr/lib/jvm/java-21-openjdk-amd64/bin/java",
        "26.2": "/usr/lib/jvm/java-25-openjdk-amd64/bin/java"}
ERR_MARKERS = ("Unknown or incomplete", "Expected", "Unknown ", "Incorrect argument", "Invalid ", "Could not",
               "That position", "Cannot ", "No entity", "Found no", "Unable", "Nothing changed",
               "Failed", "Unhandled exception", "Test failed", "Encountered an unexpected exception")
# 預先生成半徑 10 chunk（-10..10）：分四塊 forceload，每塊 <256 chunks
FORCELOAD = ["forceload add -160 -160 15 15", "forceload add 16 -160 175 15",
             "forceload add -160 16 15 175", "forceload add 16 16 175 175"]


class Server:
    def __init__(self, ver, logpath):
        self.dir = SERVERS / f"paper-{ver}"
        self.log = open(logpath, "wb")
        self.p = subprocess.Popen([JAVA[ver], "-Xms1G", "-Xmx3G", "-jar", "server.jar", "--nogui"],
                                  cwd=self.dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT)
        self.lines, self.lock = [], threading.Lock()
        self.last = time.time()
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        for raw in self.p.stdout:
            self.log.write(raw); self.log.flush()
            with self.lock:
                self.lines.append(raw.decode("utf8", "replace").rstrip())
                self.last = time.time()

    def wait_for(self, text, timeout):
        t0 = time.time()
        while time.time() - t0 < timeout:
            with self.lock:
                if any(text in l for l in self.lines):
                    return True
            if self.p.poll() is not None:
                return False
            time.sleep(0.2)
        return False

    def cmd(self, c, quiet=0.25, maxwait=60):
        with self.lock:
            n = len(self.lines)
        self.p.stdin.write((c + "\n").encode()); self.p.stdin.flush()
        t0 = time.time(); time.sleep(0.1)
        while time.time() - t0 < maxwait:
            with self.lock:
                idle = time.time() - self.last
            if idle >= quiet:
                break
            time.sleep(0.05)
        with self.lock:
            return self.lines[n:]

    def stop(self):
        try:
            self.p.stdin.write(b"stop\n"); self.p.stdin.flush()
            self.p.wait(timeout=120)
        except Exception:
            self.p.kill(); self.p.wait()


def build(ver):
    sdir = SERVERS / f"paper-{ver}"
    out = WORLDS / ver
    if not (sdir / "server.jar").exists():
        sys.exit("先執行 setup_servers.py")
    # 冪等：清掉舊世界與快取以外的狀態
    for item in sdir.iterdir():
        if item.name in ("server.jar", "server.properties", "eula.txt", "cache", "libraries", "versions"):
            continue
        shutil.rmtree(item) if item.is_dir() else item.unlink()
    shutil.rmtree(out, ignore_errors=True)
    out.mkdir(parents=True)
    rep = {"version": ver, "java": JAVA[ver], "failed_commands": [], "timings": {}}
    t_start = time.time()
    srv = Server(ver, out / "server-log.txt")
    try:
        if not srv.wait_for('Done (', 900):
            raise RuntimeError("伺服器未啟動成功，見 server-log.txt")
        rep["timings"]["startup_s"] = round(time.time() - t_start, 1)
        cmdlog = open(out / "command-log.txt", "w")

        def run(c, **kw):
            res = srv.cmd(c, **kw)
            cmdlog.write(f"> {c}\n" + "\n".join("  " + r for r in res) + "\n")
            return res

        # 場景（固定座標，先載入出生區）
        run("setworldspawn 0 150 0")
        run("forceload add -16 -16 63 63")
        for _ in range(60):
            if "Test passed" in "\n".join(run("execute if loaded 56 150 40")) and \
               "Test passed" in "\n".join(run("execute if loaded -8 150 -8")):
                break
            time.sleep(1)
        time.sleep(3)
        for line in (HERE / "scene.txt").read_text().splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            res = run(line)
            text = "\n".join(res)
            if any(m in text for m in ERR_MARKERS):
                rep["failed_commands"].append({"cmd": line, "output": text[:400]})
        # 預生成
        t_gen = time.time()
        for c in FORCELOAD:
            run(c, maxwait=120)
        run("execute in minecraft:the_nether run forceload add 0 0 31 31")
        run("execute in minecraft:the_end run forceload add 0 0 31 31")
        # 等待最遠角落載入完成
        for _ in range(600):
            r = "\n".join(run("execute if loaded 175 150 175", quiet=0.3))
            if "Test passed" in r:
                break
            time.sleep(2)
        time.sleep(15)
        # 讓自由走動的牛和實體 tick 一會兒
        time.sleep(10)
        rep["timings"]["pregen_s"] = round(time.time() - t_gen, 1)
        run("forceload remove all")
        run("execute in minecraft:the_nether run forceload remove all")
        run("execute in minecraft:the_end run forceload remove all")
        time.sleep(3)
        run("save-all flush", quiet=2, maxwait=300)
        time.sleep(3)
        cmdlog.close()
    finally:
        srv.stop()
    rep["timings"]["total_s"] = round(time.time() - t_start, 1)
    # 複製世界資料夾為 baseline（含 level.dat 的資料夾，以及新版佈局可能出現的其他目錄）
    base = out / "baseline"
    base.mkdir()
    for item in sorted(sdir.iterdir()):
        if item.is_dir() and item.name not in ("cache", "libraries", "versions", "logs", "plugins", "config",
                                               "crash-reports"):
            shutil.copytree(item, base / item.name)
    for f in ("server.properties", "bukkit.yml", "spigot.yml"):
        if (sdir / f).exists():
            shutil.copy(sdir / f, out / f"{f}.used")
    rep["baseline_size_bytes"] = subprocess.check_output(["du", "-sb", str(base)]).split()[0].decode()
    rep["baseline_dirs"] = sorted(p.name for p in base.iterdir())
    (out / "build-report.json").write_text(json.dumps(rep, indent=2, ensure_ascii=False))
    print(json.dumps(rep, indent=2, ensure_ascii=False))


if __name__ == "__main__":
    for v in sys.argv[1:] or ["1.21.11", "26.2"]:
        build(v)
