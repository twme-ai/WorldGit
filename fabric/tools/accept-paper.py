#!/usr/bin/env python3
"""Real Fabric client + copied Paper plugin acceptance. Takes bench.lock internally."""
import argparse
import datetime
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import socket
import subprocess
import threading
import time
import zipfile

ROOT = Path(__file__).resolve().parents[2]


class Process:
    def __init__(self, command, cwd, log, env=None):
        self.lines = []
        self.log = log.open("w")
        self.proc = subprocess.Popen(command, cwd=cwd, env=env, stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                     text=True, start_new_session=True)
        self.reader = threading.Thread(target=self.read, daemon=True)
        self.reader.start()

    def read(self):
        for line in self.proc.stdout:
            self.lines.append(line.rstrip())
            self.log.write(line)
            self.log.flush()

    def wait(self, pattern, timeout=240, start=0):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            for line in self.lines[start:]:
                if re.search(pattern, line):
                    return line
            if self.proc.poll() is not None:
                raise RuntimeError(f"Process exited {self.proc.returncode}: {self.lines[-12:]}")
            time.sleep(.25)
        raise TimeoutError(f"Waiting for {pattern}: {self.lines[-12:]}")

    def send(self, command):
        self.proc.stdin.write(command + "\n")
        self.proc.stdin.flush()

    def stop(self, command=None):
        if self.proc.poll() is None:
            if command:
                self.send(command)
            else:
                os.killpg(self.proc.pid, signal.SIGTERM)
            try:
                self.proc.wait(timeout=60)
            except subprocess.TimeoutExpired:
                os.killpg(self.proc.pid, signal.SIGKILL)
                self.proc.wait(timeout=20)
        # xvfb-run and Gradle may exit before their game process does.
        try:
            os.killpg(self.proc.pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        self.reader.join(timeout=10)
        self.log.close()


def snapshot_plugin(source, target):
    for _ in range(10):
        try:
            with source.open("rb") as src, target.open("wb") as dst:
                before = os.fstat(src.fileno())
                shutil.copyfileobj(src, dst)
                after = os.fstat(src.fileno())
            if (before.st_mtime_ns, before.st_size) != (after.st_mtime_ns, after.st_size):
                raise OSError("Plugin changed while copying")
            with zipfile.ZipFile(target) as archive:
                if archive.testzip() is not None:
                    raise OSError("Plugin snapshot CRC failed")
            return {"source": str(source.relative_to(ROOT)), "source_mtime_ns": before.st_mtime_ns,
                    "source_mtime_utc": datetime.datetime.fromtimestamp(before.st_mtime, datetime.timezone.utc).isoformat(),
                    "bytes": before.st_size, "sha256": hashlib.sha256(target.read_bytes()).hexdigest()}
        except (OSError, zipfile.BadZipFile):
            time.sleep(1)
    raise RuntimeError("Cannot snapshot a stable Paper plugin jar")


def run(args):
    version = args.version
    port = 25663 if version == "1.21.11" else 25664
    project = "mc1_21_11" if version == "1.21.11" else "mc26_2"
    stamp = datetime.datetime.now(datetime.timezone.utc).strftime("%Y%m%d-%H%M%S")
    evidence = ROOT / ".work/fabric-acceptance" / f"paper-{version}-{stamp}"
    evidence.mkdir(parents=True)
    serverdir = ROOT / ".work/servers" / f"fabric-paper-{version}" / stamp
    worlds = ROOT / ".work/worlds" / f"fabric-paper-{version}" / stamp
    baseline = ROOT / ".work/servers" / f"paper-{version}"
    result = {"version": version, "port": port, "success": False, "evidence": str(evidence.relative_to(ROOT))}
    server = client = None
    print(f"Waiting for bench.lock: Paper {version}", flush=True)
    with (ROOT / ".work/bench.lock").open("a") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            with socket.socket() as probe:
                if probe.connect_ex(("127.0.0.1", port)) == 0:
                    raise RuntimeError(f"Port {port} already in use")
            serverdir.mkdir(parents=True)
            worlds.mkdir(parents=True)
            for name in ("world", "world_nether", "world_the_end"):
                (worlds / name).mkdir()
                (serverdir / name).symlink_to(worlds / name, target_is_directory=True)
            shutil.copy2(baseline / "server.jar", serverdir / "server.jar")
            for name in ("libraries", "versions", "cache"):
                if (baseline / name).exists():
                    shutil.copytree(baseline / name, serverdir / name)
            plugin = evidence / "worldgit-paper.jar"
            result["plugin"] = snapshot_plugin(args.plugin, plugin)
            (serverdir / "plugins/WorldGit").mkdir(parents=True)
            with zipfile.ZipFile(plugin) as archive:
                config = archive.read("config.yml").decode().replace("enabled: true", "enabled: false").replace("language: zh_tw", "language: en_us")
            (serverdir / "plugins/WorldGit/config.yml").write_text(config)
            (serverdir / "eula.txt").write_text("eula=true\n")
            generator = json.dumps({"biome": "minecraft:plains", "layers": [
                {"block": "minecraft:bedrock", "height": 1}, {"block": "minecraft:dirt", "height": 2},
                {"block": "minecraft:grass_block", "height": 1}], "lakes": False, "features": False})
            (serverdir / "server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nlevel-type=minecraft\\:flat\ngenerator-settings={generator}\nview-distance=4\nsimulation-distance=4\nspawn-protection=0\nmax-players=2\n")
            java = "/usr/lib/jvm/java-21-openjdk-amd64/bin/java" if version == "1.21.11" else "/usr/lib/jvm/java-25-openjdk-amd64/bin/java"
            command = [java, "-Xmx1500M", "-XX:ActiveProcessorCount=2", f"-Djava.io.tmpdir={ROOT / '.work/fabric-tmp'}", "-jar", "server.jar", "nogui"]
            # Write level.dat before loading the plugin, as for an existing player's world.
            server = Process(command, serverdir, evidence / "warmup.log")
            server.wait(r"Done \(", 300)
            server.send("save-all flush")
            server.wait("Saved the game", 120)
            server.stop("stop")
            result["warmup_exit"] = server.proc.returncode
            shutil.copy2(plugin, serverdir / "plugins/worldgit.jar")
            server = Process(command, serverdir, evidence / "server.log")
            server.wait(r"Done \(", 300)
            for command in ("gamerule random_tick_speed 0", "gamerule spawn_mobs false", "time set noon", "weather clear"):
                server.send(command)
            temp = ROOT / ".work/fabric-tmp"
            temp.mkdir(exist_ok=True)
            env = dict(os.environ, JAVA_HOME="/usr/lib/jvm/java-25-openjdk-amd64", GRADLE_USER_HOME=str(ROOT / ".work/gradle-home"),
                       LIBGL_ALWAYS_SOFTWARE="1", GALLIUM_DRIVER="llvmpipe", LP_NUM_THREADS="3",
                       XDG_CACHE_HOME=str(temp / "cache"), XDG_CONFIG_HOME=str(temp / "config"), TMPDIR=str(temp))
            ready = evidence / "ready"
            client = Process(["xvfb-run", "-a", "-s", "-screen 0 1280x720x24 -ac", "./gradlew", "--configure-on-demand", "--max-workers=1",
                              f"-PwgtestPaperPort={port}", f"-PwgtestPaperReady={ready}", f":fabric:{project}:runClientGameTest"],
                             args.gradle_root, evidence / "client.log", env)
            client.wait("WGPAPER handshake=true", 600)
            joined = server.wait(r"(\S+) joined the game")
            joined = re.sub(r"\x1b\[[0-9;]*m", "", joined)
            name = re.search(r"\b([A-Za-z0-9_]{1,16}) joined the game", joined).group(1)
            for command in (f"op {name}", f"gamemode creative {name}", f"tp {name} 5 203 -4 0 25", "forceload add 0 -16 15 15"):
                server.send(command)
            time.sleep(3)
            for command in ("fill 0 200 -8 15 200 15 stone", "setblock 5 201 5 oak_stairs", "setblock 7 201 7 cobblestone"):
                server.send(command)
            # Stand the camera on the platform after it exists; creative mode alone does not fly.
            server.send(f"tp {name} 5 203 -4 0 25")
            server.send("wg init --world world --all")
            server.wait(r"WorldGit.*(Initialized|Initialization|初始化完成)", 600)
            for command in ("setblock 3 201 3 stone", "setblock 5 201 5 air", "setblock 7 201 7 gold_block"):
                server.send(command)
            time.sleep(3)
            ready.write_text("ready\n")
            client.wait("WGPAPER DONE", 600)
            client.proc.wait(timeout=60)
            result["client_exit"] = client.proc.returncode
            if client.proc.returncode != 0:
                raise RuntimeError("Client gametest failed")
            shots = ROOT / ".work/worlds/fabric-gametest" / f"{version}-paper" / "screenshots"
            for name in ("paper-diff-default", "paper-diff-colorblind", "paper-status-outline"):
                candidates = sorted(shots.glob(f"*{name}*.png"), key=lambda p: p.stat().st_mtime_ns)
                if not candidates:
                    raise RuntimeError(f"Missing screenshot {name}")
                shutil.copy2(candidates[-1], evidence / f"{name}.png")
            result["client_evidence"] = [line for line in client.lines if "WGPAPER " in line or "WORLDGIT PREVIEW" in line]
            result["success"] = True
        except Exception as error:
            result["error"] = repr(error)
        finally:
            if client:
                client.stop()
                result["client_exit"] = client.proc.returncode
            if server:
                server.stop("stop")
                result["server_exit"] = server.proc.returncode
                result["server_evidence"] = [line for line in server.lines if "WorldGit" in line or "HANDSHAKE" in line]
            with socket.socket() as probe:
                result["port_closed"] = probe.connect_ex(("127.0.0.1", port)) != 0
            (evidence / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
            shutil.rmtree(serverdir, ignore_errors=True)
            shutil.rmtree(worlds, ignore_errors=True)
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)
    return result["success"] and result["port_closed"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", choices=("1.21.11", "26.2"))
    parser.add_argument("--gradle-root", type=Path, default=ROOT)
    parser.add_argument("--plugin", type=Path, default=ROOT / "paper/plugin/build/libs/worldgit-paper-0.1.0-SNAPSHOT.jar")
    def terminate(signum, frame):
        raise RuntimeError(f"Acceptance terminated by signal {signum}")
    signal.signal(signal.SIGTERM, terminate)
    raise SystemExit(0 if run(parser.parse_args()) else 1)
