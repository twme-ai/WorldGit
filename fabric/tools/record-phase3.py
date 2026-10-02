#!/usr/bin/env python3
"""Verify Phase 3 (merge) client checkpoints with the offline CLI and archive evidence."""
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
JAVA = "/usr/lib/jvm/java-21-openjdk-amd64/bin/java"


def record(args):
    log = args.log.read_text(errors="replace")
    if "WGTEST3 DONE" not in log or "BUILD SUCCESSFUL" not in log:
        raise RuntimeError("Phase 3 gametest did not finish successfully")
    artifacts = Path(re.search(r"WGTEST3 artifacts=(.+)", log)[1].strip())
    branch = re.search(r"WGTEST3 main=(\S+)", log)[1]
    stamp = time.strftime("%Y%m%d-%H%M%S", time.gmtime())
    evidence = ROOT / ".work/fabric-acceptance" / f"phase3-{args.version}-{stamp}"
    evidence.mkdir(parents=True, exist_ok=False)
    result = {"version": args.version, "success": False, "artifacts": str(artifacts.relative_to(ROOT)),
              "evidence": str(evidence.relative_to(ROOT)),
              "game_evidence": [line for line in log.splitlines() if "WGTEST3 " in line]}
    with (ROOT / ".work/bench.lock").open("a") as lock:
        if os.environ.get("WG_LOCK_HELD") != "1":
            fcntl.flock(lock, fcntl.LOCK_EX)
        try:
            jar = evidence / "wgit.jar"
            shutil.copy2(args.cli_jar, jar)
            shutil.copy2(args.log, evidence / "client.log")

            def cli(label, world, *command, check=True):
                p = subprocess.run([JAVA, "-XX:-UsePerfData", "-jar", str(jar), "-w", str(world), "--format=json", *command],
                                   text=True, capture_output=True)
                (evidence / f"cli-{label}.json").write_text(p.stdout)
                (evidence / f"cli-{label}.stderr").write_text(p.stderr)
                if check and p.returncode:
                    raise AssertionError(f"wgit {label} failed ({p.returncode}): {p.stdout} {p.stderr}")
                return json.loads(p.stdout) if p.stdout.strip() else None

            verifies = {}
            for label in ("clean-merge", "after-abort", "final"):
                value = cli(f"verify-{label}", artifacts / label / "world", "verify", branch)
                assert value["state"] == "COMPLETE", value
                for stats in value["dimensions"].values():
                    assert not any(stats[k] for k in ("chunks", "sections", "biomeSections", "entityPuts",
                                                     "entityRemoves", "chunkDeletes", "metaFiles")), stats
                verifies[label] = value
            result["verify"] = verifies

            # MERGING 中途：wgit conflicts 與客戶端／伺服器看到的清單必須一致。
            cli_regions = cli("conflicts-merging", artifacts / "merging-initial/world", "conflicts")
            game = json.loads((artifacts / "regions.json").read_text())
            def norm_cli(r):
                b = r["bounds"]
                return (r["id"], r["blockCount"], f'{b["minX"]},{b["minY"]},{b["minZ"]},{b["maxX"]},{b["maxY"]},{b["maxZ"]}', bool(r["redstone"]))
            expected = sorted((g["id"], g["count"], g["bounds"], g["redstone"]) for g in game)
            actual = sorted(norm_cli(r) for r in cli_regions)
            assert expected == actual, {"game": expected, "cli": actual}
            result["conflicts_match_cli"] = True
            result["regions"] = actual
            after = cli("conflicts-after-abort", artifacts / "after-abort/world", "conflicts")
            assert after == [], after
            final_conflicts = cli("conflicts-final", artifacts / "final/world", "conflicts")
            assert final_conflicts == [], final_conflicts

            # 兩個 parent：直接讀 bare repo 的 HEAD commit。
            repo = next((artifacts / "final/.worldgit/world").glob("minecraft.overworld"))
            head = subprocess.run(["git", "--git-dir", str(repo), "cat-file", "-p", f"refs/heads/{branch}"],
                                  text=True, capture_output=True)
            if head.returncode:
                head = subprocess.run(["git", "--git-dir", str(repo), "cat-file", "-p", "HEAD"], text=True, capture_output=True)
            (evidence / "final-head.txt").write_text(head.stdout + head.stderr)
            parents = [l for l in head.stdout.splitlines() if l.startswith("parent ")]
            assert len(parents) == 2, head.stdout + head.stderr
            result["final_parents"] = len(parents)
            clean_repo = next((artifacts / "clean-merge/.worldgit/world").glob("minecraft.overworld"))
            clean_head = subprocess.run(["git", "--git-dir", str(clean_repo), "cat-file", "-p", f"refs/heads/{branch}"],
                                        text=True, capture_output=True)
            assert len([l for l in clean_head.stdout.splitlines() if l.startswith("parent ")]) == 2, clean_head.stdout
            result["clean_merge_parents"] = 2
            shots = ROOT / ".work/worlds/fabric-gametest" / f"{args.version}-phase3" / "screenshots"
            shutil.copytree(shots, evidence / "screenshots")
            shutil.copytree(artifacts, evidence / "checkpoints")
            result["success"] = True
        except Exception as error:
            result["error"] = repr(error)
        finally:
            (evidence / "result.json").write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({k: v for k, v in result.items() if k != "game_evidence"}, ensure_ascii=False, indent=2))
    return result["success"]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("version", choices=("1.21.11", "26.2"))
    parser.add_argument("log", type=Path)
    parser.add_argument("--cli-jar", type=Path, default=Path(os.environ.get("WGIT_JAR", ROOT / "cli/build/libs/wgit.jar")))
    raise SystemExit(0 if record(parser.parse_args()) else 1)
