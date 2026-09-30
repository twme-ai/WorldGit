#!/usr/bin/env python3
"""傾印場景區（x 0..63, z 0..47 內）的 block entity 與實體 NBT（保留型別後綴）為 JSON，供兩版逐欄比較。
用法：python3 dump_scene.py <baseline> -o out.json"""
import argparse, json, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).parent))
import mcnbt


def overworld(base):
    for c in ("world/region", "world/dimensions/minecraft/overworld/region"):
        if (base / c).is_dir():
            return base / c.rsplit("/", 1)[0]


def main():
    ap = argparse.ArgumentParser(); ap.add_argument("base"); ap.add_argument("-o", required=True)
    a = ap.parse_args(); base = Path(a.base); ow = overworld(base)
    res = {"block_entities": {}, "entities": {}, "blocks": {}}
    for cx in range(0, 4):
        for cz in range(0, 3):
            rf = ow / "region" / "r.0.0.mca"
            break
    want = {(x, z) for x in range(0, 4) for z in range(0, 3)}
    for cx, cz, ts, c, ch in mcnbt.iter_mca(ow / "region" / "r.0.0.mca", typed=True):
        if (cx, cz) not in want: continue
        for be in ch.get("block_entities", []):
            if be["y"] == 150 or be["y"] == 151:
                res["block_entities"][f"{be['x']},{be['y']},{be['z']} {be['id']}"] = mcnbt.to_typed_json(be)
        # 場景方塊（y 150..152 的 palette 名稱+屬性），解出 section 9 (y=144..159)
        for s in ch["sections"]:
            if s["Y"] != 9: continue
            pal = s["block_states"]["palette"]; data = s["block_states"].get("data")
            if data is None: continue
            bits = max(4, (len(pal) - 1).bit_length()); per = 64 // bits
            def idx(i):
                w = data[i // per] & 0xFFFFFFFFFFFFFFFF
                return (w >> ((i % per) * bits)) & ((1 << bits) - 1)
            for y in (6, 7):  # y=150,151
                for z in range(16):
                    for x in range(16):
                        e = pal[idx(y * 256 + z * 16 + x)]
                        if e["Name"] not in ("minecraft:air",):
                            res["blocks"][f"{cx*16+x},{144+y},{cz*16+z}"] = mcnbt.to_typed_json(e)
    for cx, cz, ts, c, ch in mcnbt.iter_mca(ow / "entities" / "r.0.0.mca", typed=True):
        for e in ch.get("Entities", []):
            if "wg" in e.get("Tags", []):
                res["entities"][e["id"]] = mcnbt.to_typed_json(e)
    Path(a.o).write_text(json.dumps(res, indent=1, ensure_ascii=False))
    print({k: len(v) for k, v in res.items()})


if __name__ == "__main__":
    main()
