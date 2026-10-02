#!/usr/bin/env python3
"""Check direct NMS references, nested adapter classes and the reflective unsaved field.

Usage: check_binary_compat.py <plugin-jar> <server-jar> <adapter-class>
Use Java 25's java/javap when inspecting the 26.2 adapter. Paperclip is patched
in an isolated temporary directory, so two versions in one CI folder cannot
accidentally share the wrong patched jar.
"""
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile


def javap(cp, cls, code=False):
    result = subprocess.run(['javap', '-p', '-c' if code else '-s', '-cp', str(cp), cls.replace('/', '.')], capture_output=True, text=True, timeout=60)
    return result.stdout if result.returncode == 0 else None


def unbundle(server, temporary):
    server = Path(server).resolve()
    with zipfile.ZipFile(server) as jar:
        if 'net/minecraft/server/level/ServerLevel.class' in jar.namelist():
            return server
        try:
            digest, version, name = jar.read('META-INF/versions.list').decode().strip().split('\t')
        except (KeyError, ValueError) as error:
            raise RuntimeError('not a patched server jar or supported Paperclip bundle: ' + str(server)) from error
    cached = server.parent / 'versions' / name
    if cached.is_file() and hashlib.sha256(cached.read_bytes()).hexdigest() == digest:
        return cached
    result = subprocess.run(['java', '-Dpaperclip.patchonly=true', '-jar', str(server)], cwd=temporary, capture_output=True, text=True, timeout=900)
    patched = Path(temporary) / 'versions' / name
    if result.returncode or not patched.is_file():
        raise RuntimeError('cannot patch server: ' + result.stdout[-1500:] + result.stderr[-1500:])
    if hashlib.sha256(patched.read_bytes()).hexdigest() != digest:
        raise RuntimeError('patched server SHA-256 mismatch: ' + version)
    return patched


def runtime_libraries(server, temporary):
    """Paperclip embeds Paper libraries; patched jars reuse their nearby runtime cache."""
    server = Path(server).resolve()
    with zipfile.ZipFile(server) as jar:
        if 'META-INF/libraries.list' in jar.namelist():
            libraries = []
            for line in jar.read('META-INF/libraries.list').decode().splitlines():
                digest, _, name = line.split('\t')
                relative = Path(name)
                if relative.is_absolute() or '..' in relative.parts:
                    raise RuntimeError('invalid library path: ' + name)
                cached = server.parent / 'libraries' / relative
                if cached.is_file() and hashlib.sha256(cached.read_bytes()).hexdigest() == digest:
                    libraries.append(cached)
                    continue
                embedded = 'META-INF/libraries/' + name
                if embedded not in jar.namelist():
                    continue  # Vanilla libraries are not direct adapter references.
                data = jar.read(embedded)
                if hashlib.sha256(data).hexdigest() != digest:
                    raise RuntimeError('embedded library SHA-256 mismatch: ' + name)
                target = Path(temporary) / 'libraries' / relative
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                libraries.append(target)
            return libraries
    for parent in server.parents:
        folder = parent / 'libraries'
        if folder.is_dir():
            return sorted(folder.rglob('*.jar'))
    return []


def check(plugin, server, adapter, libraries=()):
    prefix = adapter.replace('.', '/')
    with zipfile.ZipFile(plugin) as jar:
        classes = sorted(name[:-6] for name in jar.namelist() if name.endswith('.class') and (name == prefix + '.class' or name.startswith(prefix + '$')))
    if not classes:
        raise RuntimeError('adapter class missing: ' + adapter)
    refs = set()
    for cls in classes:
        code = javap(plugin, cls, True)
        if code is None:
            raise RuntimeError('cannot inspect adapter class: ' + cls)
        for kind, owner, name, desc in re.findall(r'// (?:Interface)?(Method|Field) ([\w/$]+)\.([\w$<>"\-]+):(\S+)', code):
            if owner.startswith(('net/minecraft/', 'ca/spottedleaf/', 'org/bukkit/craftbukkit/')):
                refs.add((kind, owner, name.strip('"'), desc))
    cache = {}
    server_classpath = os.pathsep.join(str(p) for p in (server, *libraries))

    def definition(owner):
        if owner not in cache:
            output = javap(server_classpath, owner)
            members, parents = {}, []
            if output:
                lines = output.splitlines()
                declaration = next((line for line in lines if re.search(r'\b(class|interface)\b.*\{', line)), '')
                # Remove generic type arguments before reading superclass / interface names.
                while re.search(r'<[^<>]*>', declaration):
                    declaration = re.sub(r'<[^<>]*>', '', declaration)
                for group in re.findall(r'(?:extends|implements)\s+([\w.$, ]+?)(?= implements|\s*\{|$)', declaration):
                    parents.extend(p.strip().replace('.', '/') for p in group.split(','))
                for i, line in enumerate(lines[:-1]):
                    if 'descriptor:' not in lines[i + 1]:
                        continue
                    desc = lines[i + 1].split('descriptor:', 1)[1].strip()
                    match = re.search(r'([\w.$]+)\([^)]*\)(?: throws [\w.$, ]+)?;', line)
                    if match:
                        name = '<init>' if match.group(1) == owner.replace('/', '.') else match.group(1).split('.')[-1]
                        kind = 'Method'
                    else:
                        match = re.search(r'([\w$]+);$', line.strip())
                        if not match:
                            continue
                        name, kind = match.group(1), 'Field'
                    members[(kind, name, desc)] = line.strip()
            cache[owner] = (output is not None, members, parents)
        return cache[owner]

    def member(owner, key, seen=None):
        seen = set() if seen is None else seen
        if owner in seen:
            return None
        seen.add(owner)
        exists, members, parents = definition(owner)
        if key in members:
            return members[key]
        if key[1] != '<init>':
            for parent in parents:
                found = member(parent, key, seen)
                if found:
                    return found
        return None

    problems = []
    for kind, owner, name, desc in sorted(refs):
        exists, _, _ = definition(owner)
        declaration = member(owner, (kind, name, desc))
        if not exists:
            problems.append((owner, name, desc, 'CLASS MISSING'))
        elif declaration is None:
            problems.append((owner, name, desc, 'MEMBER MISSING'))
        elif not declaration.startswith('public '):
            problems.append((owner, name, desc, 'MEMBER NOT PUBLIC'))
    unsaved = member('net/minecraft/world/level/chunk/ChunkAccess', ('Field', 'unsaved', 'Z'))
    if unsaved is None or 'volatile ' not in unsaved or 'static ' in unsaved:
        problems.append(('ChunkAccess', 'unsaved', 'Z', 'REFLECTIVE FIELD MISSING / NOT VOLATILE'))
    print(f'checked {len(refs)} direct refs in {len(classes)} adapter classes + reflective unsaved for {adapter} against {server}; problems: {len(problems)}')
    for problem in problems:
        print(' ', problem)
    return bool(problems)


def main():
    if len(sys.argv) != 4:
        raise SystemExit(__doc__.strip())
    plugin, server, adapter = sys.argv[1:]
    with tempfile.TemporaryDirectory(prefix='wg-compat-') as temporary:
        libraries = runtime_libraries(server, temporary)
        return check(plugin, unbundle(server, temporary), adapter, libraries)


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, RuntimeError, subprocess.TimeoutExpired, zipfile.BadZipFile) as error:
        raise SystemExit('binary compatibility: ' + str(error))
