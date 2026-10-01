#!/usr/bin/env python3
"""Archive a completed client gametest and verify its commits through the offline CLI."""
import argparse
import collections
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def run(args):
    log = args.log.read_text()
    if "WGTEST DONE" not in log or "BUILD SUCCESSFUL" not in log:
        raise RuntimeError("Gametest did not finish successfully")
    marker = re.search(r"WGTEST world=(.+)", log)
    if not marker:
        raise RuntimeError("Gametest did not report its saved world")
    source = Path(marker.group(1).strip())
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    evidence = ROOT / ".work/fabric-acceptance" / f"singleplayer-{args.version}-{stamp}"
    evidence.mkdir(parents=True)
    worlddir = ROOT / ".work/worlds" / f"fabric-singleplayer-{args.version}" / stamp
    world = worlddir / source.name
    project = "mc1_21_11" if args.version == "1.21.11" else "mc26_2"
    result = {"version": args.version, "success": False, "world": str(world.relative_to(ROOT)),
              "evidence": str(evidence.relative_to(ROOT)), "game_evidence": [line for line in log.splitlines() if "WGTEST " in line]}
    with (ROOT / ".work/bench.lock").open("a") as lock:
        if os.environ.get("WG_LOCK_HELD") != "1":
            fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            shutil.copytree(source, world)
            shutil.copytree(source.parent / ".worldgit" / source.name, worlddir / ".worldgit" / source.name)
            jar = evidence / "wgit.jar"
            shutil.copy2(args.cli_jar, jar)
            shutil.copy2(args.log, evidence / "client.log")
            def cli(name, *command):
                p = subprocess.run(["/usr/lib/jvm/java-21-openjdk-amd64/bin/java", "-jar", str(jar),
                                    "-w", str(world), "--format=json", *command], text=True, capture_output=True, check=True)
                (evidence / f"cli-{name}.json").write_text(p.stdout)
                return json.loads(p.stdout)
            history = cli("log", "log")
            manual = next(row for row in history if "client game test" in row["message"])
            initial = next(row for row in history if row["message"] == "Initialize world")
            dimension = "minecraft:overworld"
            before, after = initial["dimensions"][dimension], manual["dimensions"][dimension]
            diff = cli("diff", "diff", before, after, "--blocks")[dimension]
            blocks = [block for section in diff["sections"] for block in section["blocks"]]
            counts = dict(collections.Counter(block["kind"] for block in blocks))
            if len(diff["sections"]) != 1 or counts != {"added": 1, "removed": 1, "modified": 1}:
                raise AssertionError(f"CLI mismatch: sections={len(diff['sections'])}, blocks={counts}")
            result.update(before=before, after=after, sections=1, counts=counts, author=manual["author"])
            shots = ROOT / ".work/worlds/fabric-gametest" / args.version / "screenshots"
            shutil.copytree(shots, evidence / "screenshots")
            result["success"] = True
        except Exception as error:
            result["error"] = repr(error)
        finally:
            (evidence / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return result["success"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", choices=("1.21.11", "26.2"))
    parser.add_argument("log", type=Path)
    parser.add_argument("--cli-jar", type=Path, default=Path(os.environ.get("WGIT_JAR", ROOT / "cli/build/libs/wgit.jar")))
    raise SystemExit(0 if run(parser.parse_args()) else 1)
