"""檢查插件 jar 引用的 NMS/CraftBukkit 成員在指定伺服器 jar 裡是否存在（方法/欄位 + descriptor）。python3 check_binary_compat.py <server-jar>"""
import subprocess, sys, re, os, collections
ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
plugin = os.path.join(ROOT, 'experiments/08-folia-switch/build/libs/worldgit-folia-switch.jar')
server = sys.argv[1]
classes = subprocess.check_output(['unzip', '-Z1', plugin], text=True).split()
classes = [c[:-6].replace('/', '.') for c in classes if c.endswith('.class') and any(c == 'wg/poc/'+n+'.class' or c.startswith('wg/poc/'+n+'$') for n in ('Bench','Sect','Nms','PocPlugin'))]
refs = set()
for c in classes:
    out = subprocess.check_output(['javap', '-c', '-p', '-cp', plugin, c], text=True, stderr=subprocess.DEVNULL)
    for m in re.finditer(r'// (?:Interface)?(Method|Field) ([\w/$]+)\.([\w$<>]+):(\S+)', out):
        kind, owner, name, desc = m.groups()
        if owner.startswith(('net/minecraft', 'ca/spottedleaf', 'org/bukkit/craftbukkit')):
            refs.add((kind, owner, name, desc))
cache = {}
def members(owner):
    if owner not in cache:
        r = subprocess.run(['javap', '-s', '-p', '-cp', server, owner.replace('/', '.')], capture_output=True, text=True)
        cache[owner] = r.stdout if r.returncode == 0 else None
    return cache[owner]
def supers(owner):
    t = members(owner)
    if not t: return []
    head = t.splitlines()[1] if len(t.splitlines()) > 1 else ''
    return [x.replace('.', '/') for x in re.findall(r'(?:extends|implements) ([\w.$<>,\s]+)', head.split('{')[0]) for x in re.split(r'[,\s]+', re.sub(r'<[^>]*>', '', x)) if x]
def has(owner, name, desc, seen=None):
    seen = seen or set()
    if owner in seen: return False
    seen.add(owner)
    t = members(owner)
    if t is None: return None
    lines = t.splitlines()
    for i, l in enumerate(lines):
        if ('public' in l or 'protected' in l) and re.search(r'[ .]' + re.escape(name) + r'\(|[ ]' + re.escape(name) + r';', l) and i + 1 < len(lines) and 'descriptor: ' + desc in lines[i + 1]:
            return True
    for sup in supers(owner):
        r = has(sup, name, desc, seen)
        if r: return True
    return False
missing = []
for kind, owner, name, desc in sorted(refs):
    if name == '<init>':
        ok = has(owner, owner.split('/')[-1].split('$')[-1], desc)
        if not ok:
            t = members(owner)
            ok = t is not None and any(('descriptor: ' + desc) in l for l in t.splitlines()) 
    else:
        ok = has(owner, name, desc)
    if ok is None: missing.append((kind, owner, name, desc, 'CLASS MISSING'))
    elif not ok: missing.append((kind, owner, name, desc, 'MEMBER MISSING'))
print(f'checked {len(refs)} refs against {os.path.basename(server)}; problems: {len(missing)}')
for m in missing: print('  ', m)

if any(x[-1] == "MEMBER MISSING" for x in missing): sys.exit(2)
