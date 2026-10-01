#!/usr/bin/env python3
"""兩版依序用真正 Fabric runClient + Xvfb + Paper 複本測試；finally 關閉所有子程序。"""
import argparse, hashlib, json, os, re, shutil, signal, socket, subprocess, threading, time, uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3]
POC = ROOT / 'experiments/05-fabric-poc'
WORK = ROOT / '.work/fabric-poc'
JAVA = {'1.21.11': '/usr/lib/jvm/java-21-openjdk-amd64/bin/java', '26.2': '/usr/lib/jvm/java-25-openjdk-amd64/bin/java'}
PORT = {'1.21.11': 25631, '26.2': 25632}

class Process:
    def __init__(self, cmd, cwd, logfile, env=None):
        self.lines = []
        self.log = open(logfile, 'w')
        self.proc = subprocess.Popen(cmd, cwd=cwd, env=env, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, bufsize=1, start_new_session=True)
        self.reader = threading.Thread(target=self.read, daemon=True); self.reader.start()
    def read(self):
        for line in self.proc.stdout:
            self.lines.append(line.rstrip()); self.log.write(line); self.log.flush()
    def send(self, line):
        self.proc.stdin.write(line + '\n'); self.proc.stdin.flush()
    def wait(self, pattern, timeout=180, start=0):
        until = time.monotonic() + timeout
        while time.monotonic() < until:
            for line in self.lines[start:]:
                if re.search(pattern, line): return line
            if self.proc.poll() is not None: raise RuntimeError(f'process exited {self.proc.returncode}: {self.lines[-10:]}')
            time.sleep(.25)
        raise TimeoutError(f'waiting for {pattern}; last lines: {self.lines[-8:]}')
    def stop(self, command=None):
        if self.proc.poll() is None and command:
            try: self.send(command); self.proc.wait(timeout=40)
            except (BrokenPipeError, subprocess.TimeoutExpired): pass
        if self.proc.poll() is None:
            os.killpg(self.proc.pid, signal.SIGTERM)
            try: self.proc.wait(timeout=15)
            except subprocess.TimeoutExpired: os.killpg(self.proc.pid, signal.SIGKILL); self.proc.wait()
        self.reader.join(timeout=2); self.log.close()

def prepare(version):
    server = WORK / 'servers' / ('paper-' + version)
    if server.exists(): shutil.rmtree(server)
    source = ROOT / '.work/servers' / ('paper-' + version)
    server.mkdir(parents=True)
    for filename in ['server.jar', 'eula.txt', 'bukkit.yml', 'spigot.yml', 'commands.yml', 'help.yml', 'permissions.yml']:
        if (source / filename).exists(): shutil.copy2(source / filename, server / filename)
    for folder in ['libraries', 'cache', 'versions', 'config']:
        if (source / folder).exists(): shutil.copytree(source / folder, server / folder)
    baseline = ROOT / '.work/worlds' / version / 'baseline'
    for path in baseline.iterdir(): shutil.copytree(path, server / path.name)
    props = {'server-port': PORT[version], 'server-ip': '127.0.0.1', 'online-mode': 'false', 'view-distance': 6,
             'simulation-distance': 3, 'gamemode': 'spectator', 'spawn-protection': 0, 'level-name': 'world',
             'enforce-secure-profile': 'false', 'motd': 'WorldGit Fabric PoC', 'difficulty': 'peaceful'}
    (server / 'server.properties').write_text(''.join(f'{k}={v}\n' for k, v in props.items()))
    (server / 'plugins').mkdir(); shutil.copy2(WORK / 'build/paper/libs/worldgit-fabric-poc-paper.jar', server / 'plugins')
    h = bytearray(hashlib.md5(b'OfflinePlayer:WgFabric').digest()); h[6] = h[6] & 15 | 48; h[8] = h[8] & 63 | 128
    (server / 'ops.json').write_text(json.dumps([{'uuid': str(uuid.UUID(bytes=bytes(h))), 'name': 'WgFabric', 'level': 4, 'bypassesPlayerLimit': False}]))
    client = WORK / ('client-' + version); client.mkdir(exist_ok=True)
    (client / 'options.txt').write_text('onboardAccessibility:false\nrenderDistance:6\nsimulationDistance:5\nmaxFps:260\nenableVsync:false\nguiScale:2\nfov:0.0\nsoundCategory_master:0.0\n')
    return server

def run(version):
    resultdir = WORK / 'results' / version; resultdir.mkdir(parents=True, exist_ok=True)
    (POC / 'screenshots').mkdir(exist_ok=True)
    serverdir = prepare(version)
    controlfile = WORK / ('control-' + version + '.txt'); controlfile.unlink(missing_ok=True)
    env = os.environ.copy()
    env.update({'JAVA_HOME': '/usr/lib/jvm/java-25-openjdk-amd64', 'GRADLE_USER_HOME': str(ROOT / '.work/gradle-home'),
                'npm_config_cache': str(ROOT / '.work/npm-cache'), 'LIBGL_ALWAYS_SOFTWARE': '1', 'GALLIUM_DRIVER': 'llvmpipe',
                'LP_NUM_THREADS': '3', 'XDG_CACHE_HOME': str(WORK / 'cache'), 'XDG_CONFIG_HOME': str(WORK / 'config'),
                'TMPDIR': str(WORK / 'tmp')})
    for name in ['tmp', 'cache', 'config']: (WORK / name).mkdir(exist_ok=True)
    server = client = bot = None; seq = 0; result = {'version': version, 'screenshots': [], 'measurements': []}
    def control(action):
        nonlocal seq
        seq += 1; temp = controlfile.with_suffix('.tmp'); temp.write_text(str(seq) + '\n' + action); temp.replace(controlfile)
        client.wait('WGPOC CONTROL_ACK ' + str(seq) + r'\b', 30)
    def screenshot(name):
        filename = version + '-' + name + '.png'; path = POC / 'screenshots' / filename
        path.unlink(missing_ok=True); control('screenshot ' + filename)
        client.wait(r'WGPOC SCREENSHOT .*' + re.escape(filename), 30)
        if not path.exists(): raise RuntimeError('Screenshot callback reported success but file absent')
        result['screenshots'].append(filename)
    def tp(x,y,z,yaw,pitch):
        server.send(f'tp WgFabric {x} {y} {z} {yaw} {pitch}'); time.sleep(3)
    def clear(n):
        start = len(server.lines); control('command wgpoc clear'); server.wait('WGPOC CLEAR player=WgFabric restored='+str(n), 60, start)
        client.wait('WGPOC CLEARED',30); time.sleep(2)
    try:
        print('Starting Paper',version,flush=True)
        server=Process([JAVA[version],'-XX:-UsePerfData','-XX:ActiveProcessorCount=3','-Xms512M','-Xmx2G','-jar','server.jar','nogui'],serverdir,resultdir/'server.log')
        server.wait(r'Done \(',240);server.send('time set day');server.send('weather clear')
        print('Starting Fabric/Xvfb',version,flush=True)
        project='client121' if version=='1.21.11' else 'client262'
        client=Process(['/usr/bin/xvfb-run','-a','-s','-screen 0 1280x720x24 -ac','gradle','-p',str(POC),
                        '--project-cache-dir',str(WORK/'project-cache'),'--max-workers=1','-Pautotest',f':{project}:runClient'],ROOT,resultdir/'client.log',env)
        client.wait('WGPOC AUTO_JOINED',600);server.wait('WGPOC HANDSHAKE_OK player=WgFabric',60)
        result['handshake']=True
        print('Checking no-mod mineflayer',version,flush=True)
        bot=Process(['node',str(ROOT/'experiments/03-paper-poc/tools/bot.js'),str(PORT[version]),version,'WgNoMod'],ROOT,resultdir/'bot.log',env)
        bot.wait('"ev":"spawn"',60);server.wait('WGPOC NO_MOD player=WgNoMod',30);result['no_mod_timeout']=True
        bot.stop('quit');bot=None
        tp(0,190,0,0,0);control('command wgpoc diffdemo 64')
        server.wait('WGPOC DIFF_SENT player=WgFabric n=64',90);client.wait('WGPOC BUFFER_READY n=64 ',90)
        tp(29,198,-3,0,16);time.sleep(5);screenshot('64-front');control('measure')
        tp(49,197,20,90,22);screenshot('64-side')
        tp(28,195,10,0,22);screenshot('64-ghost-close')
        time.sleep(.65);screenshot('64-pulse-later')
        result['measurements'].append(client.wait('WGPOC FRAME_METRIC n=64 ',180))
        clear(64);screenshot('clear')
        for n in [10000,100000]:
            print('Measuring',version,n,flush=True)
            tp(0,190,0,0,0);start=len(client.lines);control('command wgpoc diffdemo '+str(n))
            sent=server.wait('WGPOC DIFF_SENT player=WgFabric n='+str(n)+r'\b',120)
            built=client.wait('WGPOC BUFFER_READY n='+str(n)+r'\b',120,start)
            tp(46,230,-12,0,28);screenshot(str(n)+'-overview')
            time.sleep(8);control('measure')
            frame=client.wait('WGPOC FRAME_METRIC n='+str(n)+r'\b',300,start)
            result['measurements'].extend([sent,built,frame]);clear(n)
        control('quit');client.proc.wait(timeout=60)
        result['client_exit']=client.proc.returncode;result['success']=True
    except Exception as error:
        result['success']=False;result['error']=repr(error);print('FAILED',version,repr(error),flush=True)
    finally:
        if bot:bot.stop('quit')
        if client:client.stop()
        if server:server.stop('stop')
        if client:result['client_exit']=client.proc.returncode
        if server:result['server_exit']=server.proc.returncode
        with socket.socket() as probe:
            result['server_port_closed']=probe.connect_ex(('127.0.0.1',PORT[version])) != 0
        result['client_evidence']=[line for line in (resultdir/'client.log').read_text().splitlines() if 'WGPOC ' in line] if (resultdir/'client.log').exists() else []
        result['server_evidence']=[line for line in (resultdir/'server.log').read_text().splitlines() if 'WGPOC ' in line] if (resultdir/'server.log').exists() else []
        (resultdir/'summary.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        (POC/'results').mkdir(exist_ok=True);shutil.copy2(resultdir/'summary.json',POC/'results'/(version+'.json'))
    return result['success']

if __name__=='__main__':
    def interrupted(signum, frame):
        raise RuntimeError('external signal '+str(signum))
    signal.signal(signal.SIGTERM, interrupted)
    parser=argparse.ArgumentParser();parser.add_argument('versions',nargs='*',default=['1.21.11','26.2']);args=parser.parse_args()
    ok=True
    for version in args.versions: ok=run(version) and ok
    raise SystemExit(0 if ok else 1)
