#!/usr/bin/env python3
"""持有 bench.lock、複製 baseline、跑真實 Paper。沒有本機資源時略過；finally 關閉伺服器。"""
import collections
import fcntl
import json
import sys
import math
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'paper/tools'))
from cli_compat import cli_data, repository, DIMENSIONS, verify_all, prepare_all_entities
WORK = Path(os.environ.get('WGIT_VERIFY_DIR', str(ROOT / '.work/phase1/paper')))
JAVA = {'1.21.11': '/usr/lib/jvm/java-21-openjdk-amd64/bin/java', '26.2': '/usr/lib/jvm/java-25-openjdk-amd64/bin/java'}

class Server:
    def __init__(self, version, directory, log):
        self.log = open(log, 'w')
        self.lines = []
        self.condition = threading.Condition()
        self.process = subprocess.Popen([JAVA[version], '-Xms512M', '-Xmx2G', '-XX:ActiveProcessorCount=3', '-jar', 'server.jar', '--nogui'], cwd=directory, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, bufsize=1)
        self.thread = threading.Thread(target=self.pump, daemon=True)
        self.thread.start()
    def pump(self):
        for line in self.process.stdout:
            self.log.write(line)
            self.log.flush()
            with self.condition:
                self.lines.append(line)
                self.condition.notify_all()
    def wait(self, text, start=0, timeout=180):
        deadline = time.monotonic() + timeout
        with self.condition:
            while time.monotonic() < deadline:
                if any(text in line for line in self.lines[start:]): return
                if self.process.poll() is not None: raise RuntimeError('Paper 提前停止')
                self.condition.wait(min(1, deadline-time.monotonic()))
        raise RuntimeError('Paper timeout: '+text)
    def command(self, command, response=None):
        start = len(self.lines)
        self.process.stdin.write(command+'\n')
        self.process.stdin.flush()
        if response: self.wait(response, start)
    def stop(self):
        if self.process.poll() is None:
            self.command('stop')
            try: self.process.wait(timeout=120)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.thread.join(timeout=10)
        self.log.close()

def cli(directory, *args):
    if args and args[0]=='init':prepare_all_entities(directory)
    result = subprocess.run([JAVA['1.21.11'], '-jar', str(WORK/'wgit.jar'), '--world', str(directory), '--format=json', *args], text=True, capture_output=True)
    if result.returncode: raise RuntimeError(result.stderr)
    return cli_data(result.stdout)

def tool(command, directory):
    cp = str(WORK/'acceptance-tools.jar')
    return subprocess.check_output([JAVA['1.21.11'], '-cp', cp, 'org.worldgit.core.AcceptanceTool', command, str(directory)], text=True)

def summarize(diff):
    result={}
    for dim,d in diff.items():
        distances=[math.dist(e['before']['position'],e['after']['position']) for e in d['entities'] if e['before'] and e['after']]
        result[dim]={'sections':len(d['sections']), 'blocks':sum(sum(s['counts'].values()) for s in d['sections']),
            'entities':len(d['entities']), 'entity_kinds':dict(collections.Counter(e['kind'] for e in d['entities'])),
            'modified_positions_over_2':sum(x>2 for x in distances), 'modified_positions_le_2':sum(x<=2 for x in distances),
            'minimum_modified_movement':min(distances,default=None),
            'biomes':sum(b['count'] for b in d['biomes']), 'metadata':[m['path'] for m in d['metadata']]}
    return result

def run(version):
    stable_root = os.environ.get('WGIT_VERIFY_STABLE_SOURCE')
    baseline = Path(stable_root)/version if stable_root else ROOT/'.work/worlds'/version/'baseline'
    source = ROOT/'.work/servers'/('paper-'+version)
    if not baseline.is_dir() or not (source/'server.jar').is_file(): return {'skipped': '本機 baseline 或 Paper 不存在'}
    directory = WORK/version
    if directory.exists(): raise RuntimeError('驗證資料夾已存在：'+str(directory))
    if stable_root:
        directory.mkdir(parents=True)
        for p in baseline.iterdir():
            if p.name in ['world','world_nether','world_the_end'] and p.is_dir(): shutil.copytree(p,directory/p.name)
    else: shutil.copytree(baseline, directory)
    for name in ['server.jar', 'cache', 'libraries', 'versions']:
        if (source/name).exists(): (directory/name).symlink_to(source/name)
    for name in ['eula.txt', 'bukkit.yml', 'spigot.yml', 'commands.yml', 'config']:
        p = source/name
        if p.is_dir(): shutil.copytree(p, directory/name)
        elif p.exists(): shutil.copy2(p, directory/name)
    properties = {}
    for line in (source/'server.properties').read_text().splitlines():
        if '=' in line and not line.startswith('#'):
            key,value=line.split('=',1);properties[key]=value
    properties.update({'server-ip':'127.0.0.1', 'server-port':'25641' if version=='1.21.11' else '25642', 'online-mode':'false', 'view-distance':'2', 'simulation-distance':'2', 'pause-when-empty-seconds':'-1', 'enable-rcon':'false', 'management-server-enabled':'false', 'management-server-secret':''})
    (directory/'server.properties').write_text('\n'.join(k+'='+v for k,v in properties.items())+'\n')
    initial_chunks = tool('chunks', ROOT/'.work/worlds'/version/'baseline' if stable_root else directory).splitlines()
    before_headers = {tuple(line.split()[:3]):line.split()[3] for line in tool('chunks',directory).splitlines()}
    cli(directory,'init','--with-dimensions','all')
    report = {'initial_chunks':len(initial_chunks), 'source':'已穩定的 baseline 複本' if stable_root else '原始 baseline 複本', 'passes':[]}
    for pass_number in range(2 if stable_root else 4):
        server=Server(version,directory,directory/('verify-'+str(pass_number)+'.log'))
        try:
            server.wait('Done (',timeout=300)
            # gamerule 與真實流體/作物演化會改世界，第一輪明確記錄，不能算假 diff。
            if pass_number==0:
                server.command('gamerule minecraft:random_tick_speed 0')
                server.command('gamerule minecraft:spawn_mobs false')
            server.command('tick freeze')
            # 固定最初的 full chunk 清單；重新把新生成邊緣加入會讓每輪範圍持續擴張。
            chunks=initial_chunks
            for line in chunks:
                dim,x,z,_=line.split()
                server.command(f'execute in {dim} run forceload add {int(x)*16} {int(z)*16}')
            time.sleep(20)
            server.command('tick unfreeze')
            time.sleep(20 if stable_root else 60)
            server.command('tick freeze')
            server.command('save-all flush', 'Saved the game')
        finally: server.stop()
        diff=cli(directory,'diff')
        summary=summarize(diff)
        report['passes'].append(summary)
        print(version,'pass',pass_number,{d:{**s,'metadata':len(s['metadata'])} for d,s in summary.items()},flush=True)
        cli(directory,'commit','-m','Paper rewrite '+str(pass_number))
    # 最終一次重寫允許實體真正移動；檢查方塊/BE/biome/ticks/structures 是否為零。
    final=report['passes'][-1]
    report['stable_rewrite'] = all(d['sections']==0 and d['biomes']==0 and not any(p.endswith(('ticks.bin','structures.bin')) for p in d['metadata']) for d in final.values())
    server=Server(version,directory,directory/'one-block.log')
    try:
        server.wait('Done (',timeout=300)
        server.command('tick freeze')
        # 明確測試 active session.lock 會拒絕離線操作。
        active=subprocess.run([JAVA['1.21.11'],'-jar',str(WORK/'wgit.jar'),'--world',str(directory),'status'],text=True,capture_output=True)
        report['active_session_rejected']=active.returncode!=0 and 'session.lock' in active.stderr
        target_block='minecraft:diamond_block' if stable_root else 'minecraft:gold_block'
        server.command('setblock 0 235 0 '+target_block)
        server.command('save-all flush','Saved the game')
    finally: server.stop()
    one=cli(directory,'diff','--blocks')
    report['one_block']=summarize(one)
    blocks=[b for d in one.values() for s in d['sections'] for b in s['blocks']]
    report['one_block_exact']=len(blocks)==1 and blocks[0]['pos']=={'x':0,'y':235,'z':0} and blocks[0]['after']['name']==target_block
    after_lines=tool('chunks',directory).splitlines()
    after_headers={tuple(line.split()[:3]):line.split()[3] for line in after_lines}
    report['full_chunk_headers_after']=len(after_lines)
    report['initial_chunks_rewritten']=sum(before_headers.get(tuple(line.split()[:3]))!=after_headers.get(tuple(line.split()[:3])) for line in initial_chunks)
    report['all_initial_chunks_rewritten']=report['initial_chunks_rewritten']==len(initial_chunks)
    return report

def main():
    WORK.mkdir(parents=True,exist_ok=True)
    results={}
    for name in ['wgit.jar','acceptance-tools.jar']:
        shutil.copy2(ROOT/'cli/build/libs'/name, WORK/name)
    with open(ROOT/'.work/bench.lock','a') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        for version in JAVA:
            try: results[version]=run(version)
            except Exception as error:
                results[version]={'error':str(error)}
                print(version,'FAILED',error,flush=True)
            (WORK/'results.json').write_text(json.dumps(results,indent=2,ensure_ascii=False))
    if any('error' in r or r.get('stable_rewrite') is False or r.get('one_block_exact') is False or r.get('active_session_rejected') is False or r.get('all_initial_chunks_rewritten') is False for r in results.values()): raise SystemExit(1)
if __name__=='__main__': main()
