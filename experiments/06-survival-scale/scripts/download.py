#!/usr/bin/env python3
from common import *
import urllib.request, hashlib
# 已查證官方版本資料，固定 1.5.3，避免重跑時默默更換測試插件。
API='https://api.modrinth.com/v2/version/MdY6JATr'
a=json.load(urllib.request.urlopen(API));f=next(x for x in a['files'] if x['primary'])
dest=WORK/'downloads';dest.mkdir(parents=True,exist_ok=True)
p=dest/f['filename']
if not p.exists():urllib.request.urlretrieve(f['url'],p)
assert hashlib.sha512(p.read_bytes()).hexdigest()==f['hashes']['sha512']
write_json(dest/'chunky-version.json',a)
print(f"Chunky {a['version_number']} verified sha512 {f['hashes']['sha512']}")
