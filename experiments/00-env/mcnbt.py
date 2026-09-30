#!/usr/bin/env python3
"""零相依的 NBT / .mca 讀取器（供 Phase 0 盤點與之後實驗共用）。

API：
  read_nbt(bytes, gz=False) -> (name, value)   # 值：dict/list/int(帶型別標籤見下)/str/bytes...
  iter_mca(path) -> yield (cx, cz, timestamp, compression, nbt_dict)   # cx,cz 為 region 內 0..31
型別：TAG_Byte/Short/Int/Long 皆回傳 Python int（用 typed=True 可得 (tag, value) 供型別比較）。
"""
import gzip, io, struct, zlib
from pathlib import Path

NAMES = {1: "byte", 2: "short", 3: "int", 4: "long", 5: "float", 6: "double", 7: "byte[]", 8: "string",
         9: "list", 10: "compound", 11: "int[]", 12: "long[]"}


class TInt(int):
    """帶 tag 型別的整數/浮點，用來比較 byte vs int 差異。"""
    tag = 3


def _mk(tag, v):
    if tag in (1, 2, 3, 4):
        o = TInt(v); o.tag = tag; return o
    if tag in (5, 6):
        class TF(float):
            pass
        o = TF(v); o.tag = tag; return o
    return v


def _read(b, pos, tag, typed):
    if tag == 1: v = struct.unpack_from(">b", b, pos)[0]; pos += 1
    elif tag == 2: v = struct.unpack_from(">h", b, pos)[0]; pos += 2
    elif tag == 3: v = struct.unpack_from(">i", b, pos)[0]; pos += 4
    elif tag == 4: v = struct.unpack_from(">q", b, pos)[0]; pos += 8
    elif tag == 5: v = struct.unpack_from(">f", b, pos)[0]; pos += 4
    elif tag == 6: v = struct.unpack_from(">d", b, pos)[0]; pos += 8
    elif tag == 7:
        n = struct.unpack_from(">i", b, pos)[0]; pos += 4; v = bytes(b[pos:pos + n]); pos += n
    elif tag == 8:
        n = struct.unpack_from(">H", b, pos)[0]; pos += 2; v = b[pos:pos + n].decode("utf8", "replace"); pos += n
    elif tag == 9:
        et = b[pos]; n = struct.unpack_from(">i", b, pos + 1)[0]; pos += 5; v = []
        for _ in range(n):
            x, pos = _read(b, pos, et, typed); v.append(x)
        if typed:
            v = _ListT(v); v.etag = et
    elif tag == 10:
        v = {}
        while True:
            t = b[pos]; pos += 1
            if t == 0: break
            n = struct.unpack_from(">H", b, pos)[0]; pos += 2
            k = b[pos:pos + n].decode("utf8", "replace"); pos += n
            v[k], pos = _read(b, pos, t, typed)
    elif tag == 11:
        n = struct.unpack_from(">i", b, pos)[0]; pos += 4
        v = list(struct.unpack_from(f">{n}i", b, pos)); pos += 4 * n
    elif tag == 12:
        n = struct.unpack_from(">i", b, pos)[0]; pos += 4
        v = list(struct.unpack_from(f">{n}q", b, pos)); pos += 8 * n
    else:
        raise ValueError(f"bad tag {tag} at {pos}")
    if typed and tag in (1, 2, 3, 4, 5, 6):
        v = _mk(tag, v)
    return v, pos


class _ListT(list):
    etag = 0


def read_nbt(data, gz=False, typed=True):
    if gz:
        data = gzip.decompress(data)
    tag = data[0]
    n = struct.unpack_from(">H", data, 1)[0]
    name = data[3:3 + n].decode()
    v, _ = _read(data, 3 + n, tag, typed)
    return name, v


def read_file(path, typed=True):
    raw = Path(path).read_bytes()
    return read_nbt(raw, gz=raw[:2] == b"\x1f\x8b", typed=typed)[1]


def iter_mca(path, typed=True):
    raw = Path(path).read_bytes()
    if len(raw) < 8192:
        return
    for i in range(1024):
        loc = struct.unpack_from(">I", raw, i * 4)[0]
        off, cnt = loc >> 8, loc & 0xFF
        if off == 0:
            continue
        ts = struct.unpack_from(">I", raw, 4096 + i * 4)[0]
        p = off * 4096
        length, comp = struct.unpack_from(">IB", raw, p)
        body = raw[p + 5:p + 4 + length]
        if comp == 1: body = gzip.decompress(body)
        elif comp == 2: body = zlib.decompress(body)
        elif comp == 3: pass
        elif comp == 4:
            import lz4.block  # 不常用，若遇到再裝
            body = lz4.block.decompress(body[4:], uncompressed_size=struct.unpack(">I", body[:4])[0])
        else:
            raise ValueError(f"compression {comp}")
        yield i & 31, i >> 5, ts, comp, read_nbt(body, typed=typed)[1]


def tname(v):
    """回傳值的 NBT 型別名稱字串。"""
    if isinstance(v, dict): return "compound"
    if isinstance(v, list): return f"list<{NAMES.get(getattr(v, 'etag', 0), '?')}>"
    if isinstance(v, str): return "string"
    if isinstance(v, bytes): return "byte[]"
    if hasattr(v, "tag"): return NAMES[v.tag]
    return type(v).__name__


def to_typed_json(v):
    """轉成可 json 化且保留型別後綴的結構（1b, 2s, 3, 4L, 1.0f, 1.0d）。"""
    if isinstance(v, dict):
        return {k: to_typed_json(x) for k, x in sorted(v.items())}
    if isinstance(v, list):
        return [to_typed_json(x) for x in v]
    t = getattr(v, "tag", None)
    if t == 1: return f"{int(v)}b"
    if t == 2: return f"{int(v)}s"
    if t == 4: return f"{int(v)}L"
    if t == 5: return f"{float(v)}f"
    if t == 6: return f"{float(v)}d"
    if t == 3: return int(v)
    return v
