#!/usr/bin/env python3
"""Verify completed Phase 2 client checkpoints with the offline CLI and archive evidence."""
import argparse
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time

ROOT = Path(__file__).resolve().parents[2]


def record(args):
    log = args.log.read_text()
    if "WGTEST2 DONE" not in log or "BUILD SUCCESSFUL" not in log:
        raise RuntimeError("Phase 2 gametest did not finish successfully")
    artifacts = Path(re.search(r"WGTEST2 artifacts=(.+)", log)[1].strip())
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    evidence = ROOT / ".work/fabric-acceptance" / f"phase2-{args.version}-{stamp}"
    evidence.mkdir(parents=True, exist_ok=False)
    result = {"version": args.version, "success": False, "artifacts": str(artifacts.relative_to(ROOT)),
              "evidence": str(evidence.relative_to(ROOT)),
              "game_evidence": [line for line in log.splitlines() if "WGTEST2 " in line]}
    with (ROOT / ".work/bench.lock").open("a") as lock:
        if os.environ.get("WG_LOCK_HELD") != "1":
            fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            jar = evidence / "wgit.jar"
            shutil.copy2(args.cli_jar, jar)
            shutil.copy2(args.log, evidence / "client.log")

            def cli(label, world, *command):
                p = subprocess.run(["/usr/lib/jvm/java-21-openjdk-amd64/bin/java", "-XX:-UsePerfData", "-jar", str(jar),
                                    "-w", str(world), "--format=json", *command], text=True, capture_output=True)
                (evidence / f"cli-{label}.json").write_text(p.stdout)
                (evidence / f"cli-{label}.stderr").write_text(p.stderr)
                if p.returncode:
                    raise AssertionError(f"wgit {label} failed ({p.returncode}): {p.stdout} {p.stderr}")
                return json.loads(p.stdout)

            verifies = {}
            for label, revision in (("B-before-preview", "B"), ("B-after-preview", "B"),
                                    ("A-switched", "A"), ("A-recovered", "A")):
                value = cli(f"verify-{label}", artifacts / label / "world", "verify", revision)
                assert value["state"] == "COMPLETE", value
                for dimension, stats in value["dimensions"].items():
                    assert not any(stats[k] for k in ("chunks", "sections", "biomeSections", "entityPuts",
                                                     "entityRemoves", "chunkDeletes", "metaFiles")), stats
                verifies[label] = value
            result["verify"] = verifies
            diff = cli("diff-B-A", artifacts / "B-before-preview/world", "diff", "B", "A", "--blocks")
            # The request window was chunk (0,-1), radius 1; CLI covers the whole commit.
            blocks = [block for section in diff["minecraft:overworld"]["sections"] for block in section["blocks"]
                      if -1 <= block["pos"]["x"] // 16 <= 1 and -2 <= block["pos"]["z"] // 16 <= 0]
            def state(value):
                props = value.get("properties", {})
                return value["name"] + ("[" + ",".join(f"{k}={v}" for k, v in sorted(props.items())) + "]" if props else "")
            expected = {(b["pos"]["x"], b["pos"]["y"], b["pos"]["z"], b["kind"],
                         state(b["before"]), state(b["after"])) for b in blocks}
            preview = json.loads((artifacts / "preview-cells.json").read_text())
            actual = {(c["x"], c["y"], c["z"], c["kind"], c["before"], c["after"]) for c in preview}
            assert actual and actual == expected, {"missing": list(expected - actual), "extra": list(actual - expected)}
            result["preview_cells"] = len(actual)
            result["preview_matches_cli"] = True
            shutil.copy2(artifacts / "preview-cells.json", evidence / "preview-cells.json")
            shots = ROOT / ".work/worlds/fabric-gametest" / f"{args.version}-phase2" / "screenshots"
            shutil.copytree(shots, evidence / "screenshots")
            shutil.copytree(artifacts, evidence / "checkpoints")
            result["checkpoints"] = str((evidence / "checkpoints").relative_to(ROOT))
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
    raise SystemExit(0 if record(parser.parse_args()) else 1)
