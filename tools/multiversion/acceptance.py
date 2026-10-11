#!/usr/bin/env python3
"""真正原版世界的升降級矩陣。自行持鎖；證據與暫存世界在 .work。"""
import argparse, fcntl, json, os, pathlib, shutil, subprocess, time, traceback, threading
from fixtures import ROOT, VERSIONS, Server

WORK = pathlib.Path(os.environ.get('WORLDGIT_MV_RESULTS', ROOT / '.work/multiversion')).resolve()
JAR = pathlib.Path(os.environ.get('WORLDGIT_MV_JAR', ROOT / 'cli/build/libs/wgit.jar')).resolve()

def cli(world, *args, expected=0):
    command = ['java', '-jar', str(JAR), '-w', str(world), '--format=json', *args]
    p = subprocess.run(command, cwd=ROOT, text=True, capture_output=True, timeout=600)
    with (WORK / 'cli-acceptance.log').open('a') as out:
        out.write(json.dumps(command) + '\n' + p.stdout + p.stderr + '\n')
    if p.returncode != expected:
        raise RuntimeError(str(args) + ': exit ' + str(p.returncode) + '\n' + p.stdout + p.stderr)
    return json.loads(p.stdout) if p.stdout.strip() else {}

def opened(version, base, name, commands=(), freeze=False):
    log = WORK / ('open-' + name + '.log')
    with Server(version, base, log, freeze=freeze) as server:
        if freeze:server.send('setworldspawn 128 64 128')
        server.send('forceload add 0 0 31 31')
        time.sleep(2)
        for command in commands:
            server.send(command)
        for cmd in ['data get block 0 64 0 Items', 'data get entity @e[type=minecraft:armor_stand,limit=1] CustomName', 'save-all flush']:
            server.send(cmd)
        time.sleep(3)
        if version in ['1.20.1','1.20.4','1.20.6','1.21.4','1.21.11']:
            play(version,server,name)
    failures = [x for x in server.lines if any(s in x for s in ['/ERROR]', '[ERROR]', 'Exception', 'Failed to load', 'Failed to read', 'Unknown item', 'Unable to load', 'Missing data fixer'])]
    # 離線測試不需要 Mojang public key；網路驗證失敗與存檔解碼分開辨識。
    failures = [x for x in failures if not any(s in x for s in ['public key', 'yggdrasil', 'Authentication', 'Failed to request yggdrasil'])]
    if failures:
        raise RuntimeError('server errors: ' + ''.join(failures))
    warnings=[x for x in server.lines if 'WARN]' in x and any(s in x.lower() for s in ['data fixer','datafix','dfu','schema'])]
    if warnings:raise RuntimeError('DFU warnings: '+''.join(warnings))
    output = ''.join(server.lines)
    for value in ['WorldGit sword', 'sharpness', 'WorldGitFixture', '42', 'WorldGit stand']:
        if value not in output:
            raise RuntimeError('chest did not preserve ' + value + ': ' + str(log))

def play(version,server,name):
    """A real protocol client enters both representative chunks and reads the restored chest."""
    bot=subprocess.Popen(['node',str(ROOT/'paper/tools/wgbot.js'),str(server.port),version,'WGFixture'],stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1)
    lines=[]
    def read():
        with (WORK/('bot-'+name+'.log')).open('w') as out:
            for line in bot.stdout:lines.append(line);out.write(line);out.flush()
    thread=threading.Thread(target=read);thread.start()
    try:
        start=time.monotonic()
        while not any('"ev":"spawn"' in x for x in lines):
            if bot.poll() is not None or time.monotonic()-start>45:raise RuntimeError('client did not spawn: '+''.join(lines))
            time.sleep(.2)
        server.send('gamemode creative WGFixture');time.sleep(1)
        for x in [8,24,8]:
            server.send('tp WGFixture '+str(x)+' 65 8');time.sleep(2)
            bot.stdin.write('pos\nblock 0 64 0\n');bot.stdin.flush();time.sleep(1)
        if not any('"name":"chest"' in x for x in lines):raise RuntimeError('client could not read restored chest: '+''.join(lines))
        if any('"ev":"error"' in x or '"ev":"kicked"' in x for x in lines):raise RuntimeError('client error: '+''.join(lines))
    finally:
        if bot.poll() is None:
            bot.stdin.write('quit\n');bot.stdin.flush()
            try:bot.wait(timeout=10)
            except subprocess.TimeoutExpired:bot.terminate();bot.wait(timeout=10)
        thread.join();bot.stdin.close();bot.stdout.close()

def migration(source, target, dry=False):
    name = source + '-to-' + target
    base = WORK / 'cases' / name
    shutil.rmtree(base, ignore_errors=True)
    shutil.copytree(ROOT / '.work/worlds' / source / 'multiversion/world', base / 'world')
    world = base / 'world'
    try:
        def runtime(*args):
            return json.loads(subprocess.check_output(['java', '-Xmx256m', '-cp', str(JAR),
                str(ROOT/'tools/multiversion/RuntimeProbe.java'), str(world), *args],
                cwd=ROOT, text=True, timeout=60))
        before = runtime('seed')
        cli(world, 'init', '--with-dimensions=all', '--template=survival')
        command = 'upgrade' if VERSIONS.index(target) > VERSIONS.index(source) else 'downgrade'
        preview = cli(world, command, '--to', target, '--dry-run')
        if command=='downgrade':
            text=json.dumps(preview,ensure_ascii=False)
            for value in ['minecraft:black_cushion','minecraft:dappled_forest','minecraft:cushion']:
                if value not in text:raise RuntimeError('missing expected downgrade loss: '+value)
        if dry:
            return preview
        if command == 'downgrade':
            p = subprocess.run(['java', '-jar', str(JAR), '-w', str(world), command, '--to', target], cwd=ROOT, capture_output=True, text=True, timeout=600)
            if p.returncode == 0:
                raise RuntimeError('lossy downgrade proceeded without confirmation')
            if '--accept-loss' not in p.stdout + p.stderr:
                raise RuntimeError('downgrade did not expose loss confirmation: ' + p.stdout + p.stderr)
        cli(world, command, '--to', target, '--accept-loss')
        after = runtime()
        if before != after:
            raise RuntimeError('runtime state changed during migration: ' + str(before) + ' -> ' + str(after))
        status = cli(world, 'status')
        for outcome in status['data']['dimensions'].values():
            diff = outcome['value']['diff']
            if any(diff[key] for key in ['sections', 'entities', 'biomes', 'metadata']):
                raise RuntimeError('migrated world differs from version commit: ' + str(diff))
        cli(world, 'show')
        opened(target, base, name)
        return preview
    finally:
        shutil.rmtree(base, ignore_errors=True)

def main():
    p = argparse.ArgumentParser()
    p.add_argument('--case', nargs=2)
    a = p.parse_args()
    cases = [a.case] if a.case else [(v, '26.3') for v in VERSIONS[:-1]] + [('26.3', v) for v in ['1.21.11', '1.21.4', '1.20.6', '1.20.4', '1.20.1']]
    WORK.mkdir(parents=True, exist_ok=True)
    results = []
    with (ROOT / '.work/bench.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX)
        for source, target in cases:
            started = time.monotonic()
            row = dict(source=source, target=target)
            try:
                row['preview'] = migration(source, target)
                row['passed'] = True
            except Exception as e:
                row['passed'] = False
                row['error'] = str(e)
                traceback.print_exc()
            row['seconds'] = round(time.monotonic() - started, 2)
            results.append(row)
            (WORK / 'acceptance-results.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
            print(source, '->', target, row['passed'], flush=True)
            if not row['passed']:
                raise SystemExit(1)

if __name__ == '__main__':
    main()
