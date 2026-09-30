"""測試伺服器/機器人驅動：複製 baseline 世界到 .work/paper-poc/run/<platform>-<ver>/，啟動、下指令、讀 [WGPOC] 結果行。"""
import json, os, re, shutil, subprocess, sys, threading, time, signal

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '../../..'))
WORK = os.path.join(ROOT, '.work')
RUN = os.path.join(WORK, 'paper-poc', 'run')
JARS = os.path.join(WORK, 'jars')
PLUGIN_JAR = os.path.join(ROOT, 'experiments/03-paper-poc/build/libs/worldgit-paper-poc.jar')
PORTS = {'paper-1.21.11': 25621, 'paper-26.2': 25622, 'folia-1.21.11': 25623, 'folia-26.2': 25624}
JAVA = {'1.21.11': '/usr/lib/jvm/java-21-openjdk-amd64/bin/java', '26.2': '/usr/lib/jvm/java-25-openjdk-amd64/bin/java'}
EXTRA = {  # 額外插件
    'pe': 'packetevents-spigot-2.14.0.jar',
    'fawe-1.21.11': 'FAWE-1.21.11-2.15.0.jar',
    'fawe-26.2': 'FAWE-26.2-2.15.4.jar',
    'we-7.4.5': 'worldedit-bukkit-7.4.5.jar',
    'we-7.4.2': 'worldedit-bukkit-7.4.2.jar',  # 7.4.5 要 Java 25（class 69），1.21.11 (Java 21) 要用 7.4.2
    'via': 'ViaVersion-5.12.0.jar',
}


class Server:
    def __init__(self, platform, version, plugins=('pe',), fresh=True, autosave_ticks=None, view=6, extra_props=None):
        self.name = f'{platform}-{version}'
        self.platform, self.version = platform, version
        self.dir = os.path.join(RUN, self.name)
        self.port = PORTS[self.name]
        self.log_lines = []
        self.lock = threading.Lock()
        self.proc = None
        self.bot = None
        self._prepare(plugins, fresh, autosave_ticks, view, extra_props or {})

    def _prepare(self, plugins, fresh, autosave_ticks, view, extra_props):
        src = os.path.join(WORK, 'servers', self.name)
        if fresh and os.path.exists(self.dir):
            shutil.rmtree(self.dir)
        if not os.path.exists(self.dir):
            os.makedirs(self.dir)
            for f in ('server.jar', 'eula.txt', 'bukkit.yml', 'spigot.yml', 'commands.yml', 'help.yml', 'permissions.yml', 'version_history.json'):
                if os.path.exists(os.path.join(src, f)):
                    shutil.copy(os.path.join(src, f), self.dir)
            for d in ('libraries', 'cache', 'versions', 'config'):
                if os.path.exists(os.path.join(src, d)):
                    shutil.copytree(os.path.join(src, d), os.path.join(self.dir, d))
            base = os.path.join(WORK, 'worlds', self.version, 'baseline')
            for d in os.listdir(base):
                shutil.copytree(os.path.join(base, d), os.path.join(self.dir, d))
        props = {
            'server-port': str(self.port), 'server-ip': '127.0.0.1', 'online-mode': 'false', 'view-distance': str(view),
            'simulation-distance': str(view), 'motd': 'wgpoc ' + self.name, 'level-seed': 'worldgit', 'gamemode': 'creative',
            'spawn-protection': '0', 'enable-command-block': 'true', 'difficulty': 'easy', 'spawn-monsters': 'false',
            'level-name': 'world', 'enforce-secure-profile': 'false',
        }
        props.update(extra_props)
        with open(os.path.join(self.dir, 'server.properties'), 'w') as f:
            for k, v in props.items():
                f.write(f'{k}={v}\n')
        # 插件
        pd = os.path.join(self.dir, 'plugins')
        shutil.rmtree(pd, ignore_errors=True)
        os.makedirs(pd)
        shutil.copy(PLUGIN_JAR, pd)
        for p in plugins:
            shutil.copy(os.path.join(JARS, EXTRA[p]), pd)
        if any(p.startswith('fawe') for p in plugins):
            # FAWE 預設會擋下非白名單的第三方 Extent（'Potentially unsafe extent blocked'），要把我們的 package 加進 extent.allowed-plugins
            fd = os.path.join(pd, 'FastAsyncWorldEdit'); os.makedirs(fd, exist_ok=True)
            open(os.path.join(fd, 'config.yml'), 'w').write('extent:\n  allowed-plugins:\n  - "wg.poc"\n  debug: true\n')
        if autosave_ticks:
            fp = os.path.join(self.dir, 'config', 'paper-world-defaults.yml')
            s = open(fp).read().replace('auto-save-interval: default', f'auto-save-interval: {autosave_ticks}')
            open(fp, 'w').write(s)
        # 把 bot 設為 op：離線 UUID
        import hashlib, uuid
        h = bytearray(hashlib.md5(b'OfflinePlayer:WgBot').digest()); h[6] = (h[6] & 0x0f) | 0x30; h[8] = (h[8] & 0x3f) | 0x80
        u = str(uuid.UUID(bytes=bytes(h)))
        json.dump([{'uuid': u, 'name': 'WgBot', 'level': 4, 'bypassesPlayerLimit': False}], open(os.path.join(self.dir, 'ops.json'), 'w'))

    # ---- 伺服器 ----
    def start(self, timeout=240, xmx='2G'):
        cmd = [JAVA[self.version], f'-Xmx{xmx}', '-Xms512M', '-jar', 'server.jar', 'nogui']
        self.logf = open(os.path.join(self.dir, 'console.log'), 'w')
        self.proc = subprocess.Popen(cmd, cwd=self.dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1, preexec_fn=os.setsid)
        threading.Thread(target=self._reader, daemon=True).start()
        self.wait(r'Done \(', timeout)

    def _reader(self):
        for line in self.proc.stdout:
            with self.lock:
                self.log_lines.append(line.rstrip('\n'))
            self.logf.write(line)
            self.logf.flush()

    def send(self, cmd):
        self.proc.stdin.write(cmd + '\n')
        self.proc.stdin.flush()

    def mark(self):
        with self.lock:
            return len(self.log_lines)

    def wait(self, pattern, timeout=60, since=0):
        rx = re.compile(pattern)
        end = time.time() + timeout
        i = since
        while time.time() < end:
            with self.lock:
                n = len(self.log_lines)
            while i < n:
                with self.lock:
                    line = self.log_lines[i]
                i += 1
                if rx.search(line):
                    return line
            if self.proc.poll() is not None:
                raise RuntimeError('server exited: ' + pattern)
            time.sleep(0.1)
        raise TimeoutError(pattern)

    def results(self, since=0):
        out = []
        with self.lock:
            lines = self.log_lines[since:]
        for l in lines:
            m = re.search(r'\[WGPOC\] (\{.*\})\s*$', l)
            if m:
                try:
                    out.append(json.loads(m.group(1)))
                except Exception:
                    pass
        return out

    def cmd(self, c, wait_kind=None, timeout=60):
        """送 /wgpoc 指令並等待某個 kind 的結果行；回傳該結果。"""
        m = self.mark()
        self.send('wgpoc ' + c)
        if wait_kind:
            end = time.time() + timeout
            while time.time() < end:
                for r in self.results(m):
                    if r.get('kind') == wait_kind or r.get('kind') == 'error':
                        return r
                time.sleep(0.2)
            raise TimeoutError(c)
        return None

    def errors_in_log(self, since=0):
        with self.lock:
            lines = self.log_lines[since:]
        return [l for l in lines if re.search(r'\[.*(ERROR|WARN)\]|Exception|thread check|Thread .* failed', l) and 'WGPOC' not in l]

    def stop(self):
        try:
            for ob in getattr(self, 'others', []):
                ob.stop()
            if self.bot:
                self.bot.stop()
            if self.proc and self.proc.poll() is None:
                self.send('stop')
                try:
                    self.proc.wait(60)
                except subprocess.TimeoutExpired:
                    os.killpg(os.getpgid(self.proc.pid), signal.SIGKILL)
        finally:
            if self.proc and self.proc.poll() is None:
                os.killpg(os.getpgid(self.proc.pid), signal.SIGKILL)


class Bot:
    def __init__(self, port, version, name='WgBot'):
        self.lines = []
        self.lock = threading.Lock()
        self.p = subprocess.Popen(['node', os.path.join(os.path.dirname(__file__), 'bot.js'), str(port), version, name], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1, preexec_fn=os.setsid)
        threading.Thread(target=self._rd, daemon=True).start()

    def _rd(self):
        for l in self.p.stdout:
            l = l.rstrip('\n')
            if l.startswith('BOT '):
                try:
                    with self.lock:
                        self.lines.append(json.loads(l[4:]))
                except Exception:
                    pass
            else:
                with self.lock:
                    self.lines.append({'ev': 'stdout', 'l': l})

    def wait_ev(self, ev, timeout=30, since=0):
        end = time.time() + timeout
        i = since
        while time.time() < end:
            with self.lock:
                n = len(self.lines)
            while i < n:
                with self.lock:
                    e = self.lines[i]
                i += 1
                if e.get('ev') == ev:
                    return e
                if e.get('ev') == 'cmd_error' and ev != 'cmd_error':
                    raise RuntimeError('bot cmd_error: ' + json.dumps(e))
                if e.get('ev') in ('kicked', 'error', 'end') and ev not in ('kicked', 'error', 'end'):
                    raise RuntimeError('bot: ' + json.dumps(e))
            time.sleep(0.05)
        raise TimeoutError('bot ev ' + ev)

    def ask(self, cmd, ev, timeout=30):
        with self.lock:
            m = len(self.lines)
        self.p.stdin.write(cmd + '\n')
        self.p.stdin.flush()
        return self.wait_ev(ev, timeout, m)

    def stop(self):
        try:
            if self.p.poll() is None:
                self.p.stdin.write('quit\n'); self.p.stdin.flush()
                try:
                    self.p.wait(5)
                except subprocess.TimeoutExpired:
                    pass
        finally:
            if self.p.poll() is None:
                os.killpg(os.getpgid(self.p.pid), signal.SIGKILL)


BOT_VER = {'1.21.11': '1.21.11', '26.2': '26.2'}  # mineflayer 4.39 只內建到 26.1（協定 775）；26.2 用「複製 26.1 資料並把協定號改成 776」的 hack（見 REPORT）


def connect_bot(srv, timeout=60):
    b = Bot(srv.port, BOT_VER[srv.version])
    srv.bot = b
    b.wait_ev('spawn', timeout)
    return b


def default_plugins(ver, *more):
    pl = ['pe'] + list(more)
    return pl


def connect_extra_bots(srv, n):
    """再連 n 個 bot（WgBot2..），給「同一變動對 N 個玩家重複」統計用。"""
    srv.others = []
    for i in range(2, n + 2):
        b = Bot(srv.port, BOT_VER[srv.version], f'WgBot{i}')
        b.wait_ev('spawn', 60)
        srv.others.append(b)
    return srv.others
