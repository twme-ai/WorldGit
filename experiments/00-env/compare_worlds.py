#!/usr/bin/env python3
"""比較兩份 inspect_world.py 的輸出（A=舊版，B=新版），輸出差異（Markdown 文字）。
用法：python3 compare_worlds.py a.json b.json [-o out.md]"""
import json, re, sys
from pathlib import Path


def dim_of(d, name):
    for k, v in d["dims"].items():
        if k == name or k.endswith("/" + name) or k.endswith(name) or (name == "overworld" and k == "world"):
            return v


def strip_counts(m):
    """{'key:type': n} -> {key: {types}}"""
    r = {}
    for k in m:
        key, _, t = k.rpartition(":")
        r.setdefault(key, set()).add(t)
    return r


def diff_maps(a, b, title, out, la="A", lb="B"):
    A, B = strip_counts(a), strip_counts(b)
    only_a = sorted(set(A) - set(B)); only_b = sorted(set(B) - set(A))
    chg = sorted(k for k in set(A) & set(B) if A[k] != B[k])
    out.append(f"\n### {title}\n")
    out.append(f"- 共同且型別相同：{len([k for k in A if k in B and A[k]==B[k]])}")
    out.append(f"- 僅 {la}：{', '.join(f'`{k}`({'/'.join(A[k])})' for k in only_a) or '無'}")
    out.append(f"- 僅 {lb}：{', '.join(f'`{k}`({'/'.join(B[k])})' for k in only_b) or '無'}")
    out.append(f"- 型別不同：{', '.join(f'`{k}` {'/'.join(A[k])} -> {'/'.join(B[k])}' for k in chg) or '無'}")


def diff_flat(a, b, title, out, la, lb):
    out.append(f"\n### {title}\n")
    oa = sorted(set(a) - set(b)); ob = sorted(set(b) - set(a))
    chg = sorted(k for k in set(a) & set(b) if a[k] != b[k])
    out.append(f"- 僅 {la}：{', '.join(f'`{k}`' for k in oa) or '無'}")
    out.append(f"- 僅 {lb}：{', '.join(f'`{k}`' for k in ob) or '無'}")
    out.append(f"- 型別不同：{', '.join(f'`{k}` {a[k]}->{b[k]}' for k in chg) or '無'}")


def main():
    a = json.load(open(sys.argv[1])); b = json.load(open(sys.argv[2]))
    la, lb = "1.21.11", "26.2"
    out = []
    # level.dat
    la_ = a["level_dat"]["world/level.dat"]; lb_ = b["level_dat"]["world/level.dat"]
    out.append(f"## level.dat\n\nDataVersion：{la_['DataVersion']} vs {lb_['DataVersion']}；Version：{la_['Version']} vs {lb_['Version']}")
    diff_flat(la_["flat"], lb_["flat"], "level.dat 欄位", out, la, lb)
    # data/*.dat
    out.append("\n## data/*.dat\n")
    out.append(f"- {la}：{sorted(k for k in a['data_dat'])}")
    out.append(f"- {lb}：{sorted(k for k in b['data_dat'])}")
    oa, ob = dim_of(a, "overworld"), dim_of(b, "overworld")
    out.append("\n## Overworld chunk (region)\n")
    out.append(f"chunks：{oa['chunks']} vs {ob['chunks']}；DataVersion：{oa['DataVersion']} vs {ob['DataVersion']}；壓縮：{oa['compression']} vs {ob['compression']}")
    out.append(f"Status 分佈：{oa['status']} vs {ob['status']}")
    for key, t in (("chunk_top", "chunk 頂層"), ("section_fields", "section 內欄位"),
                   ("block_states_fields", "section.block_states 欄位"), ("biomes_fields", "section.biomes 欄位"),
                   ("heightmaps", "Heightmaps 內欄位")):
        diff_maps(oa[key], ob[key], t, out, la, lb)
    out.append("\n## entities/ region\n")
    diff_maps(oa["entity_top"], ob["entity_top"], "entities chunk 頂層", out, la, lb)
    out.append(f"\nentity chunks：{oa['entity_chunks']} vs {ob['entity_chunks']}；DataVersion {oa['entity_DataVersion']} vs {ob['entity_DataVersion']}")
    out.append("\n## poi\n")
    diff_maps(oa["poi_top"], ob["poi_top"], "poi chunk 頂層", out, la, lb)
    out.append("\n## Block entity（id 級差異）\n")
    ia, ib = oa["block_entity_fields"], ob["block_entity_fields"]
    out.append(f"- 僅 {la} 的 id：{sorted(set(ia)-set(ib))}\n- 僅 {lb} 的 id：{sorted(set(ib)-set(ia))}")
    for i in sorted(set(ia) & set(ib)):
        diff_maps(ia[i], ib[i], f"BE `{i}`", out, la, lb)
    out.append("\n## 實體（id 級差異，僅場景中固定實體）\n")
    ea, eb = oa["entity_fields"], ob["entity_fields"]
    out.append(f"- 僅 {la} 的 id：{sorted(set(ea)-set(eb))}\n- 僅 {lb} 的 id：{sorted(set(eb)-set(ea))}")
    for i in sorted(set(ea) & set(eb)):
        diff_maps(ea[i], eb[i], f"Entity `{i}`", out, la, lb)
    out.append("\n## 調色盤方塊\n")
    pa, pb = oa["palette_blocks"], ob["palette_blocks"]
    out.append(f"- 僅 {la}：{sorted(set(pa)-set(pb))}\n- 僅 {lb}：{sorted(set(pb)-set(pa))}")
    for n in sorted(set(pa) & set(pb)):
        ka = {k for s in pa[n] for k in json.loads(s)}; kb = {k for s in pb[n] for k in json.loads(s)}
        if ka != kb:
            out.append(f"- `{n}` 屬性鍵不同：{sorted(ka)} vs {sorted(kb)}")
    # 巢狀維度
    out.append("\n## 維度資料夾位置\n")
    out.append(f"- {la}：{ {k: v['dirs'] for k, v in a['dims'].items()} }")
    out.append(f"- {lb}：{ {k: v['dirs'] for k, v in b['dims'].items()} }")
    s = "\n".join(out)
    if "-o" in sys.argv:
        Path(sys.argv[sys.argv.index("-o") + 1]).write_text(s)
    else:
        print(s)


if __name__ == "__main__":
    main()
