#!/usr/bin/env python3
"""盤點一個世界（server 資料夾內的所有世界目錄）的存檔結構，輸出 JSON。

用法：python3 inspect_world.py <baseline 目錄> [-o out.json]
輸出內容：目錄樹、level.dat 欄位、chunk 頂層/section/palette 欄位、entities 檔欄位、
          block entity 與實體各 id 的欄位型別、調色盤方塊狀態、data/*.dat 欄位。
"""
import argparse, json, os, sys
from collections import Counter, defaultdict
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import mcnbt
from mcnbt import tname


def shape(v, depth=0, maxdepth=3):
    """回傳 {key: type} 的結構（compound 遞迴到 maxdepth）。"""
    if isinstance(v, dict):
        if depth >= maxdepth:
            return "compound"
        return {k: shape(x, depth + 1, maxdepth) for k, x in sorted(v.items())}
    return tname(v)


def flat(v, prefix="", out=None, maxdepth=4, depth=0):
    """壓平成 {path: type}；list<compound> 以 path[] 表示。"""
    out = {} if out is None else out
    if isinstance(v, dict):
        for k, x in v.items():
            p = f"{prefix}.{k}" if prefix else k
            if isinstance(x, dict) and depth < maxdepth:
                out[p] = "compound"; flat(x, p, out, maxdepth, depth + 1)
            elif isinstance(x, list) and x and isinstance(x[0], dict) and depth < maxdepth:
                out[p] = tname(x)
                for e in x: flat(e, p + "[]", out, maxdepth, depth + 1)
            else:
                out[p] = tname(x)
    return out


def tree(root, depth=0, maxdepth=3):
    res = {}
    for p in sorted(root.iterdir()):
        if p.is_dir():
            files = [f for f in p.iterdir() if f.is_file()]
            sub = tree(p, depth + 1, maxdepth) if depth < maxdepth else {}
            res[p.name + "/"] = {"_files": len(files), "_bytes": sum(f.stat().st_size for f in files),
                                 "_names": sorted(f.name for f in files)[:8], **sub}
        else:
            res[p.name] = p.stat().st_size
    return res


def region_dirs(base):
    """找出所有 region/entities/poi 資料夾，回傳 {dim_label: dir}。"""
    found = defaultdict(dict)
    for dp, dn, fn in os.walk(base):
        d = Path(dp)
        if d.name in ("region", "entities", "poi") and any(f.endswith(".mca") for f in fn):
            found[str(d.parent.relative_to(base))][d.name] = d
    return found


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("base"); ap.add_argument("-o")
    a = ap.parse_args()
    base = Path(a.base)
    out = {"base": str(base), "tree": tree(base, maxdepth=4)}
    out["level_dat"] = {}
    for p in base.rglob("level.dat"):
        d = mcnbt.read_file(p)
        out["level_dat"][str(p.relative_to(base))] = {
            "flat": flat(d, maxdepth=3),
            "DataVersion": int(d["Data"]["DataVersion"]) if "Data" in d and "DataVersion" in d["Data"] else None,
            "Version": d.get("Data", {}).get("Version"),
            "SpawnXYZ": [d["Data"].get(k) for k in ("SpawnX", "SpawnY", "SpawnZ")] if "Data" in d else None,
        }
    out["data_dat"] = {}
    for p in base.rglob("*.dat"):
        if p.name in ("level.dat", "level.dat_old"): continue
        try:
            out["data_dat"][str(p.relative_to(base))] = flat(mcnbt.read_file(p), maxdepth=2)
        except Exception as e:
            out["data_dat"][str(p.relative_to(base))] = f"ERR {e}"
    out["dims"] = {}
    for dim, dirs in sorted(region_dirs(base).items()):
        info = {"dirs": {k: str(v.relative_to(base)) for k, v in dirs.items()}}
        # chunk
        top, sec, bs, bio, hm, status, dv, be_fields, be_ids = Counter(), Counter(), Counter(), Counter(), Counter(), Counter(), Counter(), defaultdict(Counter), Counter()
        palette_blocks = defaultdict(set)
        n = 0; ts_sample = None; comp = Counter()
        sample_sec_shape = None
        empty_lists = Counter()
        for f in sorted(dirs.get("region", Path("/nonexistent")).glob("*.mca")) if "region" in dirs else []:
            for cx, cz, ts, c, ch in mcnbt.iter_mca(f):
                n += 1; comp[c] += 1
                for k, v in ch.items(): top[f"{k}:{tname(v)}"] += 1
                status[ch.get("Status")] += 1; dv[int(ch.get("DataVersion", -1))] += 1
                for k, v in (ch.get("Heightmaps") or {}).items(): hm[f"{k}:{tname(v)}"] += 1
                for s in ch.get("sections", []):
                    for k, v in s.items(): sec[f"{k}:{tname(v)}"] += 1
                    for k, v in s.get("block_states", {}).items(): bs[f"{k}:{tname(v)}"] += 1
                    for k, v in s.get("biomes", {}).items(): bio[f"{k}:{tname(v)}"] += 1
                    for e in s.get("block_states", {}).get("palette", []):
                        palette_blocks[e["Name"]].add(json.dumps(e.get("Properties", {}), sort_keys=True))
                for be in ch.get("block_entities", []):
                    be_ids[be.get("id")] += 1
                    for k, v in be.items(): be_fields[be.get("id")][f"{k}:{tname(v)}"] += 1
        info["chunks"] = n
        info["compression"] = dict(comp)
        info["chunk_top"] = dict(top); info["section_fields"] = dict(sec)
        info["block_states_fields"] = dict(bs); info["biomes_fields"] = dict(bio)
        info["heightmaps"] = dict(hm); info["status"] = dict(status); info["DataVersion"] = dict(dv)
        info["block_entity_fields"] = {k: dict(v) for k, v in be_fields.items()}
        info["palette_blocks"] = {k: sorted(v) for k, v in palette_blocks.items()}
        # entities 檔
        etop, efields = Counter(), defaultdict(Counter); ecount = Counter(); en = 0; edv = Counter()
        epos = []
        if "entities" in dirs:
            for f in sorted(dirs["entities"].glob("*.mca")):
                for cx, cz, ts, c, ch in mcnbt.iter_mca(f):
                    en += 1
                    for k, v in ch.items(): etop[f"{k}:{tname(v)}"] += 1
                    edv[int(ch.get("DataVersion", -1))] += 1
                    for e in ch.get("Entities", []):
                        ecount[e.get("id")] += 1
                        for k, v in e.items(): efields[e.get("id")][f"{k}:{tname(v)}"] += 1
        info["entity_chunks"] = en; info["entity_top"] = dict(etop); info["entity_DataVersion"] = dict(edv)
        info["entity_counts"] = dict(ecount)
        info["entity_fields"] = {k: dict(v) for k, v in efields.items()}
        # poi
        if "poi" in dirs:
            ptop, pn = Counter(), 0
            for f in sorted(dirs["poi"].glob("*.mca")):
                for cx, cz, ts, c, ch in mcnbt.iter_mca(f):
                    pn += 1
                    for k, v in ch.items(): ptop[f"{k}:{tname(v)}"] += 1
            info["poi_chunks"] = pn; info["poi_top"] = dict(ptop)
        # 範例：場景 chunk (0,0) 完整 dump（region r.0.0）
        if "region" in dirs and (dirs["region"] / "r.0.0.mca").exists():
            for cx, cz, ts, c, ch in mcnbt.iter_mca(dirs["region"] / "r.0.0.mca"):
                if (cx, cz) == (0, 0):
                    info["scene_chunk_nonsection"] = {k: v for k, v in ch.items() if k != "sections"}
                    info["scene_chunk_section_example"] = next((s for s in ch["sections"] if s["Y"] == 9), None)
        out["dims"][dim] = info
    s = json.dumps(out, indent=1, ensure_ascii=False, default=lambda o: int(o) if isinstance(o, int) else float(o) if isinstance(o, float) else str(o))
    if a.o:
        Path(a.o).write_text(s)
    else:
        print(s)


if __name__ == "__main__":
    main()
