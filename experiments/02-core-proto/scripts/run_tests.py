#!/usr/bin/env python3
"""T1–T4 一鍵測試。用法：python3 run_tests.py 1.21.11 [26.2] [--only t1,t2,t3,t4]
結果：.work/core-proto/<ver>/results.json 與 stdout；伺服器 log 在 .work/core-proto/<ver>/logs/。
每個階段都是「從 baseline 複製 → init → 伺服器 → commit」，伺服器一律在 finally 中關閉。
"""
import json, os, re, shutil, sys, time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / "00-env"))
from mcserver import *
import mcnbt

WIDE = ["forceload add -160 -160 15 15", "forceload add 16 -160 175 15", "forceload add -160 16 15 175", "forceload add 16 16 175 175",
        "execute in minecraft:the_nether run forceload add 0 0 31 31", "execute in minecraft:the_end run forceload add 0 0 31 31"]
WIDE_OFF = ["forceload remove all", "execute in minecraft:the_nether run forceload remove all", "execute in minecraft:the_end run forceload remove all"]
INIT_DIM = "minecraft/overworld"


def log(*a):
    print(time.strftime("%H:%M:%S"), *a, flush=True)


def dim_dirs(ver, run):
    if ver == "1.21.11":
        return run / "world"
    return run / "world" / "dimensions" / "minecraft" / "overworld"


def clone_run(ver, src, dstname):
    dst = WORK / ver / dstname
    shutil.rmtree(dst, ignore_errors=True)
    dst.mkdir(parents=True)
    for item in src.iterdir():
        if item.is_symlink():
            (dst / item.name).symlink_to(os.readlink(item))
        elif item.is_dir():
            shutil.copytree(item, dst / item.name, symlinks=True)
        else:
            shutil.copy(item, dst / item.name)
    return dst


def scan_log(path):
    bad = []
    for l in Path(path).read_text(errors="replace").splitlines():
        if re.search(r"\[.*(ERROR|SEVERE)\]|Exception|FATAL", l) and "Rcon" not in l:
            bad.append(l[:300])
    return bad


def wait_loaded(s, x, y, z, timeout=180):
    t0 = time.time()
    while time.time() - t0 < timeout:
        if any("Test passed" in l for l in s.cmd(f"execute if loaded {x} {y} {z}", quiet=0.3)):
            return True
        time.sleep(2)
    return False


def gamerule(s, name_camel, name_snake, value):
    for n in (name_camel, name_snake):
        out = s.cmd(f"gamerule {n} {value}")
        txt = " ".join(out)
        if "Incorrect" not in txt and "Unknown" not in txt:
            return n, txt
    return None, txt


def sizes(repo):
    o = java_tool("size", repo)
    m = re.search(r"looseObjects=(\d+) looseBytes=(\d+) packFiles=(\d+) packBytes=(\d+)", o)
    du = re.search(r"du -sk objects: (\d+)", o)
    return {"looseObjects": int(m[1]), "looseBytes": int(m[2]), "packFiles": int(m[3]), "packBytes": int(m[4]), "duKB": int(du[1]) if du else None}


def commit(run, repo, msg, tol, full=False):
    """回傳 dict：SUMMARY + 大小增量。"""
    b = sizes(repo)
    t0 = time.time()
    args = ["commit", run, repo, "--tol", tol, "-m", msg] + (["--full"] if full else [])
    out = java_tool(*args)
    a = sizes(repo)
    r = summary(out)
    r["wall_s"] = round(time.time() - t0, 2)
    r["addedLooseBytes"] = a["looseBytes"] - b["looseBytes"]
    r["addedLooseObjects"] = a["looseObjects"] - b["looseObjects"]
    r["line"] = [l for l in out.splitlines() if l.startswith("commit:")][0]
    return r


def diff(repo, a="main~1", b="main"):
    out = java_tool("diff", repo, a, b, "-v")
    r = summary(out)
    r["text"] = "\n".join(l for l in out.splitlines() if not l.startswith("SUMMARY"))
    return r


def run_phase(ver, run, tag, seconds=0, wide=False, pre=(), churn=None, post=()):
    """啟動伺服器 → 指令 → 等 seconds → save-all flush → stop。回傳 log 檢查結果。"""
    logs = WORK / ver / "logs"; logs.mkdir(parents=True, exist_ok=True)
    lp = logs / f"{tag}.log"
    info = {"tag": tag}
    t0 = time.time()
    with Session(ver, run, lp) as s:
        info["startup_s"] = round(time.time() - t0, 1)
        for c in pre:
            if c == "RTS0":
                info.setdefault("pre", []).append(("randomTickSpeed 0", gamerule(s, "randomTickSpeed", "random_tick_speed", 0)))
                continue
            info.setdefault("pre", []).append((c, " | ".join(s.cmd(c, maxwait=120))[:200]))
        if wide:
            for c in WIDE:
                s.cmd(c, maxwait=120)
            wait_loaded(s, 175, 150, 175)
        if churn:
            churn(s)
        if seconds:
            time.sleep(seconds)
        for c in post:
            info.setdefault("post", []).append((c, " | ".join(s.cmd(c, maxwait=120))[:200]))
        flush(s)
    info["log_errors"] = scan_log(lp)
    return info


def churn(s):
    """把同一批方塊換成別的再換回來：伺服器會重寫 chunk（調色盤順序可能改變），語意上內容不變。"""
    for lo, hi in ((150, 158), (159, 167), (168, 175)):
        s.cmd(f"fill -8 {lo} -8 56 {hi} 40 minecraft:emerald_block replace minecraft:air", maxwait=60)
    s.cmd("fill -8 149 -8 56 149 40 minecraft:emerald_block replace minecraft:stone_bricks", maxwait=60)
    s.cmd("fill -8 148 -8 56 148 40 minecraft:emerald_block replace minecraft:dirt", maxwait=60)
    time.sleep(5)
    for lo, hi in ((150, 158), (159, 167), (168, 175)):
        s.cmd(f"fill -8 {lo} -8 56 {hi} 40 minecraft:air replace minecraft:emerald_block", maxwait=60)
    s.cmd("fill -8 149 -8 56 149 40 minecraft:stone_bricks replace minecraft:emerald_block", maxwait=60)
    s.cmd("fill -8 148 -8 56 148 40 minecraft:dirt replace minecraft:emerald_block", maxwait=60)
    time.sleep(3)


def short(d):
    return {k: v for k, v in d.items() if k not in ("text",)}


# ---------------------------------------------------------------- T1
def t1(ver, R):
    T = {}
    # ---- T1a：預設 gamerule（含 randomTick），寬範圍載入 60 秒：列出「真實」變動並分類 ----
    w = WORK / ver
    run = prepare_run(ver)
    for r in ("repoA.git", "repoB.git"):
        java_tool("init", run, w / r, "-m", "init")
    log("T1a natural run")
    T["natural_run"] = run_phase(ver, run, "t1a-natural", seconds=60, wide=True)
    ca = commit(run, w / "repoA.git", "t1a tol0", 0); cb = commit(run, w / "repoB.git", "t1a tol2", 2)
    T["natural"] = {"tol0": {"commit": ca, "diff": diff(w / "repoA.git")}, "tol2": {"commit": cb, "diff": diff(w / "repoB.git")}}
    # ---- T1b：穩定性（randomTickSpeed 0）----
    log("T1b stability")
    run = prepare_run(ver)                      # fresh
    for r in ("repoA.git", "repoB.git"):
        java_tool("init", run, w / r, "-m", "init")
    init_id = java_tool("log", w / "repoB.git").split()[0]
    R["init_commit"] = init_id
    steps = []
    plain = run_phase(ver, run, "t1b-plain-restart", seconds=20)      # 無載入：伺服器什麼都不寫
    ca = commit(run, w / "repoA.git", "plain tol0", 0); cb = commit(run, w / "repoB.git", "plain tol2", 2)
    steps.append({"name": "restart0: no chunk loaded, no players (20s)", "run": plain, "tol0": {"commit": ca}, "tol2": {"commit": cb}})
    plan = [("restart1: wide forceload (441 overworld chunks + nether/end), randomTickSpeed=0, 60s", dict(seconds=60, wide=True, pre=("__GAMERULE__",))),
            ("restart2: same + churn (fill emerald_block and back)", dict(seconds=60, wide=True, churn=churn)),
            ("restart3: same as restart1", dict(seconds=60, wide=True))]
    for i, (name, kw) in enumerate(plan, 1):
        if kw.get("pre") == ("__GAMERULE__",):
            kw = dict(kw); kw["pre"] = ()
            run_kw = kw
            info = None
            # gamerule 需在伺服器內設定；包成 pre 指令（依版本命名）
            run_kw["pre"] = ("RTS0",)
            info = run_phase(ver, run, f"t1b-r{i}", **run_kw)
        else:
            info = run_phase(ver, run, f"t1b-r{i}", **kw)
        ca = commit(run, w / "repoA.git", f"r{i} tol0", 0); cb = commit(run, w / "repoB.git", f"r{i} tol2", 2)
        da = diff(w / "repoA.git") if ca.get("committed") == "true" else None
        db = diff(w / "repoB.git") if cb.get("committed") == "true" else None
        steps.append({"name": name, "run": info, "tol0": {"commit": ca, "diff": da}, "tol2": {"commit": cb, "diff": db}})
        log("  step", i, "tol0:", ca["line"][:160], "| tol2 diff:", (db or {}).get("SUMMARY", ""))
    T["stability"] = steps
    R["t1"] = T
    return run, init_id


# ---------------------------------------------------------------- T2
def t2(ver, R, run):
    w = WORK / ver; repo = w / "repoB.git"
    T = []
    def expect(name, cmds, exp, pre_wait=2):
        holder = {}
        def go(s):
            for c in cmds:
                holder.setdefault("out", []).append(" | ".join(s.cmd(c))[:160])
            time.sleep(pre_wait)
        info = run_phase(ver, run, "t2-" + name, pre=(), post=(), churn=go, wide=False)
        c = commit(run, repo, "t2 " + name, 2)
        d = diff(repo) if c.get("committed") == "true" else {"sectionsChanged": "0", "blocksChanged": "0", "beChanged": "0", "text": "(no commit: no change)"}
        ok = all(str(d.get(k)) == str(v) for k, v in exp.items())
        T.append({"name": name, "cmds": cmds, "console": holder.get("out"), "expected": exp, "actual": short(d), "pass": ok, "commit": c, "log_errors": info["log_errors"]})
        log("  T2", name, "PASS" if ok else "FAIL", short(d).get("SUMMARY", ""))
    # 先把世界靜置為乾淨狀態並提交（scene 區域：只載入場景 chunk，無隨機刻）
    prep = run_phase(ver, run, "t2-prep", seconds=5, pre=("RTS0",) + tuple(WIDE_OFF) + ("forceload add -8 -8 56 40",))
    c = commit(run, repo, "t2 prep", 2)
    log("  t2 prep commit", c["line"][:200])
    T.append({"name": "prep (settle scene chunks, no edits)", "commit": c})
    expect("setblock-1", ["setblock 5 160 5 minecraft:gold_block"], {"sectionsChanged": 1, "blocksChanged": 1, "beChanged": 0})
    expect("setblock-same-again", ["setblock 5 160 5 minecraft:gold_block"], {"sectionsChanged": 0, "blocksChanged": 0, "beChanged": 0})
    expect("fill-multi-section", ["fill 0 155 0 20 175 20 minecraft:sea_lantern"], {"sectionsChanged": 8, "blocksChanged": 9261, "beChanged": 0})
    expect("chest-place", ["setblock 3 150 30 minecraft:chest"], {"sectionsChanged": 1, "blocksChanged": 1, "beChanged": 1})
    expect("chest-content", ["item replace block 3 150 30 container.0 with minecraft:diamond 5"], {"sectionsChanged": 1, "blocksChanged": 0, "beChanged": 1})
    R["t2"] = T


# ---------------------------------------------------------------- T3
def t3(ver, R, run):
    w = WORK / ver; repo = w / "repoB.git"
    T = {}
    T["stats_head"] = java_tool("stats", repo)
    first = java_tool("log", repo).strip().splitlines()[-1].split()[0]
    T["stats_init"] = java_tool("stats", repo, first)
    T["size_loose"] = sizes(repo)
    cp = w / "repo-gc.git"
    shutil.rmtree(cp, ignore_errors=True); shutil.copytree(repo, cp)
    t0 = time.time()
    o = java_tool("gc", cp)
    T["gc_s"] = round(time.time() - t0, 1)
    T["size_packed"] = sizes(cp)
    # init 版本單獨 gc（新 repo 只有 init 用 --full commit）
    cp2 = w / "repo-init-only.git"
    shutil.rmtree(cp2, ignore_errors=True)
    base = WORK / ver / "run-baseline-copy"
    shutil.rmtree(base, ignore_errors=True)
    src = ROOT / ".work" / "worlds" / ver / "baseline"
    shutil.copytree(src, base)
    t0 = time.time(); out = java_tool("init", base, cp2, "-m", "init only"); T["init_only"] = {"summary": summary(out), "wall_s": round(time.time() - t0, 2), "size_loose": sizes(cp2)}
    java_tool("gc", cp2)
    T["init_only"]["size_packed"] = sizes(cp2)
    raw = subprocess.check_output(["du", "-sb", str(src)]).split()[0].decode()
    T["baseline_bytes"] = int(raw)
    shutil.rmtree(base, ignore_errors=True)
    R["t3"] = T
    log("  T3", T["init_only"]["summary"], T["init_only"]["size_loose"], T["init_only"]["size_packed"])


# ---------------------------------------------------------------- T4
def lightinfo(region_dir, cx, cz):
    f = region_dir / f"r.{cx // 32}.{cz // 32}.mca"
    if not f.exists():
        return None
    for x, z, ts, c, nb in mcnbt.iter_mca(f, typed=False):
        if (x, z) == (cx % 32, cz % 32):
            secs = nb.get("sections", [])
            n_sky = sum(1 for s in secs if "SkyLight" in s); n_blk = sum(1 for s in secs if "BlockLight" in s)
            sl = {s["Y"]: s for s in secs}
            def nib(arr, i): return (arr[i >> 1] >> (4 * (i & 1))) & 15
            out = {"isLightOn": nb.get("isLightOn"), "status": nb.get("Status"), "sections": len(secs), "with_SkyLight": n_sky, "with_BlockLight": n_blk,
                   "starlight": nb.get("starlight.light_version"), "heightmaps": sorted(nb.get("Heightmaps", {}).keys()), "lastUpdate": nb.get("LastUpdate")}
            if cx == 1 and cz == 1 and 9 in sl and "BlockLight" in sl[9]:
                i = (7 << 8) | (4 << 4) | 9
                out["lava_cell_blocklight"] = nib(bytes(v & 255 for v in sl[9]["BlockLight"]), i)
            if 10 in sl and "SkyLight" in sl[10]:
                out["sky_y160s_5_5_5"] = nib(bytes(v & 255 for v in sl[10]["SkyLight"]), (5 << 8) | (5 << 4) | 5)
            out["_sections"] = {s["Y"]: s for s in secs}
            return out
    return None


def light_equal(a, b):
    """比較兩個 chunk 的光照陣列：回傳 (相同 nibble 數, 總數)，只比兩邊都有光照的 section。"""
    same = tot = 0
    for y, sa in a["_sections"].items():
        sb = b["_sections"].get(y)
        if not sb:
            continue
        for key in ("SkyLight", "BlockLight"):
            if key in sa and key in sb:
                xa, xb = bytes(v & 255 for v in sa[key]), bytes(v & 255 for v in sb[key])
                tot += 4096; same += sum(1 for i in range(2048) if xa[i] == xb[i]) * 2
    return same, tot


def poi_records(poi_dir):
    out = set()
    for f in sorted(poi_dir.glob("r.*.mca")):
        for cx, cz, ts, c, nb in mcnbt.iter_mca(f, typed=False):
            for y, sec in nb["Sections"].items():
                for r in sec["Records"]:
                    out.add((r["type"].replace("minecraft:", ""), tuple(r["pos"])))
    return sorted(out)


def t4(ver, R, run, init_id):
    w = WORK / ver; repo = w / "repoB.git"
    T = {}
    base_run = ROOT / ".work" / "worlds" / ver / "baseline"
    odir = dim_dirs(ver, run)
    # T4 準備：POI 相關編輯（移除圖書館員的講台、新增另一個講台）
    def prep(s):
        s.cmd("setblock 7 150 24 minecraft:air"); s.cmd("setblock 12 150 28 minecraft:lectern[facing=north]"); time.sleep(3)
    run_phase(ver, run, "t4-prep", churn=prep, pre=("forceload add -8 -8 56 40",))
    c = commit(run, repo, "t4 prep (lectern moved)", 2)
    T["prep_commit"] = c["line"]
    T["poi_baseline"] = poi_records(dim_dirs(ver, base_run) / "poi")
    T["poi_before_restore"] = poi_records(odir / "poi")
    T["light_baseline_c11"] = {k: v for k, v in lightinfo(dim_dirs(ver, base_run) / "region", 1, 1).items() if k != "_sections"}
    base_c11 = lightinfo(dim_dirs(ver, base_run) / "region", 1, 1)
    for mode in ("keep", "delete"):
        log("T4 restore, poi =", mode)
        r = clone_run(ver, run, "run-t4-" + mode)
        rp = w / f"repo-t4-{mode}.git"
        shutil.rmtree(rp, ignore_errors=True); shutil.copytree(repo, rp); shutil.copy(str(repo) + ".index", str(rp) + ".index")
        t0 = time.time()
        out = java_tool("restore", r, rp, init_id, INIT_DIM, -2, -2, 4, 3, "--poi", mode)
        res = {"restore_out": out.strip().splitlines()[-1], "restore_s": round(time.time() - t0, 2)}
        res["light_after_restore_before_server_c11"] = {k: v for k, v in (lightinfo(dim_dirs(ver, r) / "region", 1, 1) or {}).items() if k != "_sections"}
        res["poi_after_restore_files"] = poi_records(dim_dirs(ver, r) / "poi")
        checks = {}
        def verify(s):
            for name, cmd in (("lectern back at 7,150,24", "execute if block 7 150 24 minecraft:lectern[has_book=true]"),
                              ("no lectern at 12,150,28", "execute if block 12 150 28 minecraft:air"),
                              ("gold_block reverted (5,160,5 air)", "execute if block 5 160 5 minecraft:air"),
                              ("sea_lantern area reverted (20,175,20 air)", "execute if block 20 175 20 minecraft:air"),
                              ("sea_lantern area reverted (0,155,0 air)", "execute if block 0 155 0 minecraft:air"),
                              ("chest gone (3,150,30 air)", "execute if block 3 150 30 minecraft:air"),
                              ("furnace(11,150,24)", "execute if block 11 150 24 minecraft:furnace"),
                              ("barrel(13,150,24)", "execute if block 13 150 24 minecraft:barrel"),
                              ("lava(25,151,20)", "execute if block 25 151 20 minecraft:lava")):
                out = s.cmd(cmd)
                checks[name] = any("Test passed" in l for l in out)
            time.sleep(5)
        info = run_phase(ver, r, "t4-" + mode, pre=("tick freeze", "forceload add -32 -32 79 63"), churn=lambda s: (wait_loaded(s, 79, 150, 63), time.sleep(8), verify(s)))
        res["console_block_checks"] = checks
        res["log_errors"] = info["log_errors"]
        res["startup_s"] = info["startup_s"]
        c11 = lightinfo(dim_dirs(ver, r) / "region", 1, 1)
        res["light_after_server_c11"] = {k: v for k, v in c11.items() if k != "_sections"}
        res["light_vs_baseline_c11_nibbles_equal/total"] = light_equal(c11, base_c11)
        c88 = lightinfo(dim_dirs(ver, r) / "region", 8, 8)
        res["control_chunk_8_8"] = {k: v for k, v in (c88 or {}).items() if k != "_sections"}
        res["poi_after_server"] = poi_records(dim_dirs(ver, r) / "poi")
        cm = commit(r, rp, "t4 after restore+server " + mode, 2, full=True)
        d = diff(rp, init_id, "main")
        res["diff_vs_init"] = short(d); res["diff_vs_init_text_head"] = d["text"].splitlines()[:40]
        res["commit_after"] = cm["line"]
        T[mode] = res
        log("  T4", mode, res["console_block_checks"], "light:", res["light_after_server_c11"].get("isLightOn"), "diff:", d.get("SUMMARY"))
        shutil.rmtree(r, ignore_errors=True)
    R["t4"] = T


def main():
    args = sys.argv[1:]
    only = None
    if "--only" in args:
        i = args.index("--only"); only = args[i + 1].split(","); del args[i:i + 2]
    vers = args or ["1.21.11", "26.2"]
    for ver in vers:
        R = {"version": ver, "started": time.ctime(), "cores": os.cpu_count()}
        w = WORK / ver
        try:
            log("=== ", ver)
            run, init_id = t1(ver, R)
            t2(ver, R, run)
            t3(ver, R, run)
            t4(ver, R, run, init_id)
        finally:
            R["finished"] = time.ctime()
            (w / "results.json").write_text(json.dumps(R, indent=1, ensure_ascii=False, default=str))
            log("results written", w / "results.json")


if __name__ == "__main__":
    main()
