"""WorldGit Paper/Folia 插件驗收用的伺服器／機器人驅動（Phase 1）。

- 重負載一律持有 .work/bench.lock（fcntl.flock）；伺服器綁 127.0.0.1、online-mode=false；port 25651–25654。
- 伺服器與世界只複製使用：.work/servers/<平台>-<版本>、.work/worlds/<版本>/baseline → .work/paper-delivery/run/<名稱>。
- 插件 jar 在啟動時複製一份，驗收期間重新建置不會影響執行中的伺服器。
- 用完一定要 stop()（含 bot）；呼叫端用 try/finally。
"""
import fcntl, hashlib, json, os, re, shutil, signal, subprocess, sys, threading, time, uuid

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), '../..'))
WORK = os.path.join(ROOT, '.work')
RUN = os.path.join(WORK, 'paper-delivery', 'run')
JARS = os.path.join(WORK, 'jars')
PLUGIN_JAR_GLOB = os.path.join(ROOT, 'paper/plugin/build/libs')
PORTS = {'paper-1.21.11': 25651, 'paper-26.2': 25652, 'folia-1.21.11': 25653, 'folia-26.2': 25654}
JAVA = {'1.21.11': '/usr/lib/jvm/java-21-openjdk-amd64/bin/java', '26.2': '/usr/lib/jvm/java-25-openjdk-amd64/bin/java'}
EXTRA = {
    'fawe-1.21.11': 'FAWE-1.21.11-2.15.0.jar',
    'fawe-26.2': 'FAWE-26.2-2.15.4.jar',
    'we-7.4.5': 'worldedit-bukkit-7.4.5.jar',
    'we-7.4.2': 'worldedit-bukkit-7.4.2.jar',  # 7.4.5 需要 Java 25；1.21.11（Java 21）用 7.4.2
}
BOT_JS = os.path.join(os.path.dirname(__file__), 'wgbot.js')


class BenchLock:
    """跨任務的重負載鎖（三個並行任務共用同一把）。"""
    def __init__(self):
        self.f = None
    def __enter__(self):
        self.f = open(os.path.join(WORK, 'bench.lock'), 'a')
        t0 = time.time()
        # 不用阻塞式 flock：外層若已用 `flock bench.lock` 包住本程式會自己鎖死自己；
        # 這裡輪詢並印進度，且有上限，避免卡死時佔住/等待過久。
        limit = float(os.environ.get('WG_LOCK_TIMEOUT', '5400'))
        if os.environ.get('WG_LOCK_HELD'):
            limit = 0  # 呼叫端已持鎖（flock bench.lock env WG_LOCK_HELD=1 ...）
        while limit:
            try:
                fcntl.flock(self.f, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except BlockingIOError:
                if time.time() - t0 > limit:
                    raise TimeoutError('bench.lock 等待逾時')
                time.sleep(5)
                if int(time.time() - t0) % 120 < 5:
                    print(f'[bench.lock] 仍在等待 {time.time() - t0:.0f}s', flush=True)
        waited = time.time() - t0
        if waited > 1:
            print(f'[bench.lock] 等待 {waited:.0f}s 後取得', flush=True)
        return self
    def __exit__(self, *a):
        if not os.environ.get('WG_LOCK_HELD'):
            fcntl.flock(self.f, fcntl.LOCK_UN)
        self.f.close()


def plugin_jar():
    jars = sorted(f for f in os.listdir(PLUGIN_JAR_GLOB) if f.startswith('worldgit-paper') and f.endswith('.jar'))
    if not jars:
        raise RuntimeError('先執行 ./gradlew :paper:plugin:jar')
    return os.path.join(PLUGIN_JAR_GLOB, jars[-1])


def offline_uuid(name):
    h = bytearray(hashlib.md5(('OfflinePlayer:' + name).encode()).digest())
    h[6] = (h[6] & 0x0f) | 0x30
    h[8] = (h[8] & 0x3f) | 0x80
    return str(uuid.UUID(bytes=bytes(h)))


class Server:
    def __init__(self, platform, version, plugins=(), config=None, view=4, fresh=True, ops=('WgBot', 'WgBot2', 'WgBot3', 'WgBot4', 'WgAdmin'), extra_props=None, xmx='2G', fawe_allow=True, baseline=None, run_label=None):
        self.name = f'{platform}-{version}'
        self.platform, self.version, self.xmx = platform, version, xmx
        self.dir = os.path.join(RUN, run_label or self.name)
        self.baseline = baseline or os.path.join(WORK, 'worlds', version, 'baseline')
        self.port = PORTS[self.name]
        self.log_lines, self.lock, self.proc, self.bots = [], threading.Lock(), None, []
        self._prepare(plugins, config or {}, view, fresh, ops, extra_props or {}, fawe_allow)

    @property
    def world(self):
        return os.path.join(self.dir, 'world')

    def _prepare(self, plugins, config, view, fresh, ops, extra_props, fawe_allow):
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
            base = self.baseline
            for d in os.listdir(base):
                shutil.copytree(os.path.join(base, d), os.path.join(self.dir, d))
        props = {
            'server-port': str(self.port), 'server-ip': '127.0.0.1', 'online-mode': 'false', 'view-distance': str(view),
            'simulation-distance': str(view), 'motd': 'worldgit ' + self.name, 'level-seed': 'worldgit', 'gamemode': 'creative',
            'spawn-protection': '0', 'enable-command-block': 'true', 'difficulty': 'easy', 'spawn-monsters': 'false',
            'level-name': 'world', 'enforce-secure-profile': 'false', 'max-tick-time': '-1',
        }
        props.update(extra_props)
        with open(os.path.join(self.dir, 'server.properties'), 'w') as f:
            for k, v in props.items():
                f.write(f'{k}={v}\n')
        pd = os.path.join(self.dir, 'plugins')
        shutil.rmtree(pd, ignore_errors=True)
        os.makedirs(pd)
        shutil.copy(plugin_jar(), os.path.join(pd, 'worldgit-paper.jar'))
        for p in plugins:
            shutil.copy(os.path.join(JARS, EXTRA[p]), pd)
        if any(p.startswith('fawe') for p in plugins) and fawe_allow:
            fd = os.path.join(pd, 'FastAsyncWorldEdit')
            os.makedirs(fd, exist_ok=True)
            open(os.path.join(fd, 'config.yml'), 'w').write('extent:\n  allowed-plugins:\n  - "org.worldgit.paper"\n  debug: true\n')
        if config:
            wd = os.path.join(pd, 'WorldGit')
            os.makedirs(wd, exist_ok=True)
            open(os.path.join(wd, 'config.yml'), 'w').write(to_yaml(config))
        json.dump([{'uuid': offline_uuid(n), 'name': n, 'level': 4, 'bypassesPlayerLimit': False} for n in ops], open(os.path.join(self.dir, 'ops.json'), 'w'))

    # ---- 伺服器 ----
    def start(self, timeout=300):
        cmd = [JAVA[self.version], f'-Xmx{self.xmx}', '-Xms512M', '-jar', 'server.jar', 'nogui']
        logdir = os.path.join(WORK, 'paper-delivery', 'logs')
        os.makedirs(logdir, exist_ok=True)
        self.evidence_log = os.path.join(logdir, self.name + '-' + time.strftime('%Y%m%d-%H%M%S') + '-console.log')
        self.logf = open(self.evidence_log, 'a')
        self.proc = subprocess.Popen(cmd, cwd=self.dir, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1, preexec_fn=os.setsid)
        self.reader = threading.Thread(target=self._reader, daemon=True)
        self.reader.start()
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

    def lines_since(self, since=0):
        with self.lock:
            return list(self.log_lines[since:])

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
                raise RuntimeError('server exited while waiting for: ' + pattern)
            time.sleep(0.1)
        raise TimeoutError(pattern + '\n' + '\n'.join(self.lines_since(since)[-30:]))

    def cmd(self, c, until=None, timeout=120):
        """送一行 console 指令；until 為 regex 時等到出現並回傳「指令後的所有輸出行」。"""
        m = self.mark()
        self.send(c)
        if until:
            self.wait(until, timeout, m)
            time.sleep(0.3)
        return '\n'.join(self.lines_since(m))

    def problems(self, since=0):
        return [l for l in self.lines_since(since) if re.search(r'\[.*(ERROR|WARN)\]|Exception|thread check|Thread .* failed|Watchdog', l) and 'ProjectCollection' not in l]

    def stop(self):
        try:
            for b in self.bots:
                b.stop()
            if self.proc and self.proc.poll() is None:
                try:
                    self.send('stop')
                    self.proc.wait(90)
                except Exception:
                    pass
        finally:
            if self.proc and self.proc.poll() is None:
                os.killpg(os.getpgid(self.proc.pid), signal.SIGKILL)
                self.proc.wait(10)
            if hasattr(self, 'reader'):
                self.reader.join(5)
                self.logf.close()

    def bot(self, name, mod=False, timeout=90):
        b = Bot(self.port, self.version, name, mod)
        self.bots.append(b)
        b.wait_ev('spawn', timeout)
        return b


class Bot:
    def __init__(self, port, version, name, mod=False):
        self.name, self.lines, self.lock = name, [], threading.Lock()
        args = ['node', BOT_JS, str(port), version, name] + (['mod'] if mod else [])
        self.p = subprocess.Popen(args, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1, preexec_fn=os.setsid)
        threading.Thread(target=self._rd, daemon=True).start()

    def _rd(self):
        for l in self.p.stdout:
            l = l.rstrip('\n')
            try:
                e = json.loads(l[4:]) if l.startswith('BOT ') else {'ev': 'stdout', 'l': l}
            except Exception:
                e = {'ev': 'stdout', 'l': l}
            with self.lock:
                self.lines.append(e)

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

    def events(self, ev):
        with self.lock:
            return [e for e in self.lines if e.get('ev') == ev]

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


def to_yaml(d, indent=0):
    out = ''
    for k, v in d.items():
        if isinstance(v, dict):
            out += ' ' * indent + f'{k}:\n' + to_yaml(v, indent + 2)
        else:
            out += ' ' * indent + f'{k}: {str(v).lower() if isinstance(v, bool) else v}\n'
    return out


def wgit(args, cwd=None, check=True):
    """呼叫 CLI（fat jar）；CLI 讀插件建立的 repo，用來驗證「四端看到同一份歷史」。"""
    jar = os.path.join(ROOT, 'cli/build/libs/wgit.jar')
    r = subprocess.run(['/usr/lib/jvm/java-21-openjdk-amd64/bin/java', '-Xmx512m', '-jar', jar] + args, capture_output=True, text=True, cwd=cwd)
    if check and r.returncode != 0:
        raise RuntimeError(f'wgit {args} failed: {r.stdout}\n{r.stderr}')
    return r.stdout + r.stderr
