"""Fabric 專用伺服器驗收驅動；複製 loader／世界，只由呼叫者持 bench.lock。"""
import json
import os
from pathlib import Path
import shutil
import subprocess
import threading
import time
import harness

ROOT=Path(harness.ROOT)

class Server(harness.Server):
    def __init__(self,version,evidence,baseline,mod,fixture,config=None,jvm=(),env=None,ops=()):
        self.name='fabric-'+version;self.version=version;self.platform='fabric'
        self.dir=str(evidence/'server');self.port=25701 if version=='1.21.11' else 25702
        self.log_lines=[];self.lock=threading.Lock();self.proc=None;self.bots=[];self.evidence=evidence
        self.baseline=str(baseline)
        target=Path(self.dir);target.mkdir()
        loader=ROOT/'.work/fabric-srv'/version
        for name in ['libraries','versions']:
            shutil.copytree(loader/name,target/name)
        # Installer 的原始 Minecraft jar／loader bootstrap 不在 versions/ 裡。
        # 沿用已暖機的專案快取，測試副本不必再次連線 Mojang 下載相同 jar。
        cached_server=loader/'.fabric/server'
        if cached_server.is_dir():
            shutil.copytree(cached_server,target/'.fabric/server')
        shutil.copy2(loader/'fabric-server-launch.jar',target/'fabric-server-launch.jar')
        (target/'eula.txt').write_text('eula=true\n')
        shutil.copytree(baseline/'world',target/'world')
        if version=='26.2':
            # Paper 將這些 shared saved-data 放在維度目錄；vanilla 26.2 從世界根目錄讀取。
            # 僅調整測試副本：生成設定用已驗收的 Fabric 世界，安靜規則用平坦 fixture。
            shared=target/'world/data/minecraft';shared.mkdir(parents=True,exist_ok=True)
            shutil.copy2(loader/'world/data/minecraft/world_gen_settings.dat',shared/'world_gen_settings.dat')
            shutil.copy2(baseline/'world/dimensions/minecraft/overworld/data/minecraft/game_rules.dat',shared/'game_rules.dat')
        (target/'mods').mkdir()
        for source,name in [(loader/'fabric-api.jar','fabric-api.jar'),(mod,'worldgit.jar'),(fixture,'worldgit-fixture.jar')]:shutil.copy2(source,target/'mods'/name)
        (target/'config').mkdir()
        (target/'config/worldgit-server.yml').write_text(config if config is not None else 'locale: en_us\npermission-level: 2\nread-permission-level: 0\nauto-commit:\n  on-logout: true\n  on-stop: true\n  interval-minutes: 0\n')
        self.jvm=list(jvm);self.env=env;self.fixed_ops=list(ops)
        if ops:(target/'ops.json').write_text(json.dumps([{'uuid':harness.offline_uuid(n),'name':n,'level':4,'bypassesPlayerLimit':False} for n in ops]))
        props={'server-ip':'127.0.0.1','server-port':self.port,'online-mode':'false','view-distance':3,'simulation-distance':3,'spawn-protection':0,'enable-command-block':'false','enforce-secure-profile':'false','max-tick-time':-1,'level-name':'world','gamemode':'creative','op-permission-level':2}
        (target/'server.properties').write_text(''.join(f'{k}={v}\n' for k,v in props.items()))
    def start(self,timeout=300):
        self.evidence_log=str(self.evidence/('server-'+time.strftime('%H%M%S')+'.log'))
        self.logf=open(self.evidence_log,'a');mark=self.mark()
        self.proc=subprocess.Popen([harness.JAVA[self.version],'-Xmx1500M','-Xms512M','-XX:ActiveProcessorCount=3','-Dworldgit.acceptance=true','-Dworldgit.profile=true',*self.jvm,'-jar','fabric-server-launch.jar','nogui'],cwd=self.dir,env=({**os.environ,**self.env} if self.env else None),stdin=subprocess.PIPE,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,text=True,bufsize=1,start_new_session=True)
        self.reader=threading.Thread(target=self._reader,daemon=True);self.reader.start()
        self.wait(r'Done \(',timeout,mark)
