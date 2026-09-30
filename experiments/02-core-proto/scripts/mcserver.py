#!/usr/bin/env python3
"""測試用 Paper 伺服器包裝：在 .work/core-proto/<ver>/run/ 啟動，stdin 下 console 指令。"""
import os, shutil, subprocess, threading, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
WORK = ROOT / ".work" / "core-proto"
SERVERS = ROOT / ".work" / "servers"
JAVA = {"1.21.11": "/usr/lib/jvm/java-21-openjdk-amd64/bin/java", "26.2": "/usr/lib/jvm/java-25-openjdk-amd64/bin/java"}
PORT = {"1.21.11": 25611, "26.2": 25612}
JAR = ROOT / "experiments" / "02-core-proto" / "build" / "libs" / "core-proto.jar"


def prepare_run(ver, fresh=True):
    """複製 baseline 到 run/，並補上伺服器檔案（jar/cache 以 symlink）。"""
    w = WORK / ver
    run = w / "run"
    if fresh:
        shutil.rmtree(run, ignore_errors=True)
        for p in w.glob("repo*"):
            shutil.rmtree(p, ignore_errors=True) if p.is_dir() else p.unlink()
    run.mkdir(parents=True, exist_ok=True)
    base = ROOT / ".work" / "worlds" / ver / "baseline"
    if fresh:
        for item in base.iterdir():
            (shutil.copytree if item.is_dir() else shutil.copy)(item, run / item.name)
    src = SERVERS / f"paper-{ver}"
    for n in ("server.jar", "cache", "libraries", "versions"):
        if not (run / n).exists():
            (run / n).symlink_to(src / n)
    for n in ("eula.txt", "bukkit.yml", "spigot.yml", "commands.yml"):
        if (src / n).exists() and not (run / n).exists():
            shutil.copy(src / n, run / n)
    if (src / "config").exists() and not (run / "config").exists():
        shutil.copytree(src / "config", run / "config")
    props = (src / "server.properties").read_text().replace(f"server-port={25601 if ver=='1.21.11' else 25602}", f"server-port={PORT[ver]}")
    (run / "server.properties").write_text(props)
    return run


class Server:
    def __init__(self, ver, run, logname):
        self.log = open(logname, "wb")
        self.p = subprocess.Popen([JAVA[ver], "-Xms512M", "-Xmx2G", "-jar", "server.jar", "--nogui"], cwd=run,
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
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

    def cmd(self, c, quiet=0.4, maxwait=60):
        with self.lock:
            n = len(self.lines)
        self.p.stdin.write((c + "\n").encode()); self.p.stdin.flush()
        t0 = time.time(); time.sleep(0.15)
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


class Session:
    """with Session(ver, run, log) as s: s.cmd(...)；離開時一定 stop。"""
    def __init__(self, ver, run, logname):
        self.ver, self.run, self.logname = ver, run, logname
    def __enter__(self):
        self.t0 = time.time()
        self.s = Server(self.ver, self.run, self.logname)
        if not self.s.wait_for("Done (", 300):
            self.s.stop(); raise RuntimeError("server did not start; see " + str(self.logname))
        self.startup_s = time.time() - self.t0
        return self.s
    def __exit__(self, *a):
        self.s.stop()


def flush(s):
    s.cmd("save-all flush", quiet=2.0, maxwait=120)


def java_tool(*args, capture=True):
    cmd = ["java", "--enable-native-access=ALL-UNNAMED", "-jar", str(JAR)] + [str(a) for a in args]
    r = subprocess.run(cmd, capture_output=True, text=True)
    if r.returncode != 0:
        raise RuntimeError(f"{cmd}\n{r.stdout}\n{r.stderr}")
    return r.stdout


def summary(out):
    for l in out.splitlines()[::-1]:
        if l.startswith("SUMMARY "):
            return dict(kv.split("=", 1) for kv in l[8:].split())
    return {}
