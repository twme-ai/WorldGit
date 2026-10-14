"""跨維度 UUID barrier、移除舊乘客及遠離原點的巢狀乘客 LOAD 回歸。"""
import hashlib, json, os, re, shutil, subprocess, time, traceback, zipfile
from pathlib import Path
import harness
from cli_compat import cli_data, verify_all, player_command, repository
from harness import BenchLock, Server, ROOT, WORK, wgit

IDS=('aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee','bbbbbbbb-cccc-dddd-eeee-ffffffffffff','cccccccc-dddd-eeee-ffff-111111111111')

def fixture(server,version):
    base=Path(WORK)/'gradle-home/caches/modules-2/files-2.1'
    jars=[Path(harness.plugin_jar()),next((base/'io.papermc.paper/paper-api'/(version+'-R0.1-SNAPSHOT' if not version.startswith('26.') else {'26.1.2':'26.1.2.build.74-stable','26.2':'26.2.build.129-stable','26.3':'26.3.build.177-stable'}[version])).rglob('*.jar'))]
    for group in ['com.google.guava/guava','net.kyori/adventure-api','net.kyori/adventure-key','org.jetbrains/annotations','net.md-5/bungeecord-chat','net.kyori/examination-api','net.kyori/examination-string']:
        jars+=list((base/group).rglob('*.jar'))
    classes=Path(server.dir)/'entity-fixture-classes';classes.mkdir()
    subprocess.run([str(Path(harness.JAVA[version]).with_name('javac')),'--release','25' if version.startswith('26.') else '17' if version in ('1.20.1','1.20.4') else '21','-cp',os.pathsep.join(map(str,jars)),
                    '-d',str(classes),str(Path(ROOT)/'paper/tools/components-fixture/EntitiesFixturePlugin.java')],check=True)
    with zipfile.ZipFile(Path(server.dir)/'plugins/entities-fixture.jar','w') as jar:
        for path in classes.rglob('*.class'):jar.write(path,str(path.relative_to(classes)))
        jar.writestr('plugin.yml','name: EntitiesFixture\nversion: 1\nmain: org.worldgit.fixture.EntitiesFixturePlugin\napi-version: "1.20"\nfolia-supported: true\ncommands:\n  wgentityfixture:\n    description: Controlled passenger fixture\n')
    shutil.rmtree(classes)

def run(platform,version):
    evidence=os.path.join(WORK,'paper-phase2',f'entities-{platform}-{version}-{int(time.time())}')
    os.makedirs(evidence)
    result={'platform':platform,'version':version,'steps':[]}
    def check(name,ok,**data):
        result['steps'].append({'name':name,'ok':bool(ok),**data})
        with open(os.path.join(evidence,'results.json'),'w') as f: json.dump(result,f,ensure_ascii=False,indent=2)
        print(('PASS ' if ok else 'FAIL ')+name,str(data)[:350],flush=True)
    def cmd(s,text,pattern=r'完成|已切換到|Switched|分支：|Branches|存檔點：|Snapshot:|WGENTITY complete'):
        output=s.cmd(text,pattern+'|失敗|PARTIAL|WGENTITY failed|WGCHUNKGUARD failed',900)
        if any(x in output for x in ('失敗','PARTIAL','WGENTITY failed','WGCHUNKGUARD failed')): raise RuntimeError(text+': '+output[-1600:])
        return output
    def inspect(s,dim,x,z):
        return cmd(s,f'wg debug entities {dim} {x} {z} inspect')
    def head(dimension):
        return subprocess.check_output(['git','--git-dir',str(repository(s.world,dimension,version,paper=True)),'rev-parse','HEAD'],text=True).strip()
    def present(output,ids):
        return all(output.count('uuid='+uid)==1 for uid in ids) and all('uuid='+uid not in output for uid in IDS if uid not in ids)
    with BenchLock():
        harness.RUN=os.path.join(WORK,'paper-phase2','run')
        baseline=os.path.join(WORK,'paper-delivery','fixtures','acceptance-flat-'+version)
        stable=os.path.join(evidence,'fixture')
        shutil.copytree(baseline,stable)
        subprocess.run([harness.JAVA[version],'-Xmx512m','-cp',os.path.join(ROOT,'cli/build/libs/wgit.jar'),
                        os.path.join(ROOT,'paper/tools/EntitiesFixture.java'),os.path.join(stable,'world')]+(['--disk-uuid'] if platform=='folia' and version in ('1.20.1','26.1.2') else []),check=True)
        baseline=stable
        s=Server(platform,version,baseline=baseline,run_label=f'entities-{platform}-{version}',view=2,
                 config={'auto-commit':{'enabled':False,'on-shutdown':False},'commit':{'timeout-seconds':900}})
        result['plugin_sha256']=hashlib.sha256(Path(s.dir,'plugins/worldgit-paper.jar').read_bytes()).hexdigest()
        try:
            fixture(s,version)
            s.start(); result['console']=os.path.relpath(s.evidence_log,ROOT)
            cmd(s,'wg debug guard on',r'WGCHUNKGUARD locked')
            s.bot('WgBot'); nether_bot=s.bot('WgBot2')
            s.cmd('tp WgBot 200 70 -184')
            s.cmd('execute in minecraft:the_nether run tp WgBot2 -184 70 200')
            cmd(s,'wg debug entities overworld 12 -12 seed')
            # 以原版 jockey flag 安定 fixture，所有版本均完整保留 timer 欄位。
            output=s.cmd('wgentityfixture',r'WGENTITYFIXTURE (success|failed)')
            check('原版 jockey flag 固定蛋計時且不忽略欄位','WGENTITYFIXTURE success jockey=true' in output,output=output)
            if 'WGENTITYFIXTURE success jockey=true' not in output: raise RuntimeError(output)
            time.sleep(2)
            initial=inspect(s,'overworld',12,-12)
            check('A fixture 包含三個巢狀 UUID',present(initial,IDS),output=initial)
            if not present(initial,IDS): raise RuntimeError('A fixture 未建立三個實體')
            cmd(s,'wg init --world world --all',r'init 完成')
            cmd(s,'wg branch A');player_command(nether_bot,'wg branch A',r'分支：|Branches')
            # 目標只留同 UUID 的 cow，移到另一維度；舊乘客不得殘留。
            cmd(s,'wg debug entities overworld 12 -12 clear')
            cmd(s,'wg debug entities nether -12 12 solo')
            time.sleep(2)
            before=inspect(s,'nether',-12,12)
            check('B fixture 只含跨維度載具 UUID',present(before,IDS[:1]),output=before)
            if not present(before,IDS[:1]): raise RuntimeError('B fixture 未建立載具')
            cmd(s,'wg commit -m entity-B')
            nether_before=head('minecraft:the_nether')
            player_command(nether_bot,'wg commit -m entity-B',r'存檔點：|Snapshot:')
            check('地獄 B 獨立提交實體變更',head('minecraft:the_nether')!=nether_before)
            cmd(s,'wg branch B');player_command(nether_bot,'wg branch B',r'分支：|Branches')
            if platform=='folia' and version in ('1.20.1','26.1.2'):
                before=head('minecraft:overworld')
                rejected=s.cmd('wg switch A --force',r'已存在另一維度|Switched|已切換到|PARTIAL',900)
                check('載入中跨維度 UUID 寫入前拒絕且 HEAD 不動','已存在另一維度' in rejected and head('minecraft:overworld')==before,output=rejected)
                # 先清除已載入的地獄副本；遠端終界的同 UUID 從未載入。
                player_command(nether_bot,'wg switch A --force',r'已切換到|Switched')
                census=s.cmd('wgentityfixture census',r'WGENTITYCENSUS',30)
                check('遠端終界 chunk 未載入且 UUID 不在 manager 普查','loaded=false known=false bukkitLoaded=false' in census,output=census)
                if 'loaded=false known=false bukkitLoaded=false' not in census:raise RuntimeError(census)
                rejected=s.cmd('wg switch A --force',r'已存在另一維度|Switched|已切換到|PARTIAL',900)
                check('未載入磁碟 UUID 普查寫入前拒絕且 HEAD 不動','已存在另一維度' in rejected and 'minecraft:the_end' in rejected and '96' in rejected and head('minecraft:overworld')==before,output=rejected)
                check('拒絕後主世界仍無受測 UUID',present(inspect(s,'overworld',12,-12),()))
                cleared=s.cmd('wgentityfixture clear-disk',r'WGENTITYDISK (success|failed)',180)
                check('owner 清除唯一磁碟衝突實體','WGENTITYDISK success removed=1' in cleared,output=cleared)
                if 'WGENTITYDISK success removed=1' not in cleared:raise RuntimeError(cleared)
                cmd(s,'wg commit --dimension minecraft:the_end -m disk-collision-cleared',r'存檔點：|Snapshot:')
            for rev in ('A','B','A'):
                # 先移除目前佔有 UUID 的維度，再還原目的維度；不再依賴全組原子 switch。
                if rev=='A':
                    before=head('minecraft:overworld');player_command(nether_bot,'wg switch '+rev+' --force',r'已切換到|Switched')
                    check('地獄 switch '+rev+' 保持主世界 HEAD',head('minecraft:overworld')==before)
                    cmd(s,'wg switch '+rev+' --force')
                else:
                    cmd(s,'wg switch '+rev+' --force');before=head('minecraft:overworld')
                    player_command(nether_bot,'wg switch '+rev+' --force',r'已切換到|Switched')
                    check('地獄 switch '+rev+' 保持主世界 HEAD',head('minecraft:overworld')==before)
                ow=inspect(s,'overworld',12,-12); nether=inspect(s,'nether',-12,12)
                if rev=='A':
                    ok=present(ow,IDS) and present(nether,()) and 'depth=1' in ow and 'depth=2' in ow
                    # 乘客只調整 ride 高度；不能在原點或其他 region。
                    positions=re.findall(r'pos=\[([^]]+)\]',ow)
                    ok=ok and len(positions)==3 and all(abs(float(p.split(',')[0])-200)<1 and abs(float(p.split(',')[2])+184)<1 for p in positions)
                else: ok=present(ow,()) and present(nether,IDS[:1])
                check('跨維度／巢狀乘客 switch '+rev,ok,overworld=ow,nether=nether)
        except BaseException: check('實體場景例外',False,trace=traceback.format_exc())
        finally:
            s.stop()
            try:
                output=verify_all(lambda world,*words:cli_data(wgit(['--world',world,'--format=json',*words])),s.world,{'minecraft:overworld':'A', 'minecraft:the_nether':'A'})
                check('乘客恢復後全維度離線 verify 差異 0',len(output)==3,output=output)
            except Exception: check('verify 例外',False,trace=traceback.format_exc())
            errors=[l for l in s.lines_since() if 'ERROR' in l or 'Exception' in l]
            check('實體場景 log 無 ERROR／Exception',not errors,errors=errors[-25:])
            if not all(step['ok'] for step in result['steps']):
                forensic=os.path.join(evidence,'failure-world')
                os.makedirs(forensic)
                for name in ('world','world_nether','world_the_end'):
                    source=os.path.join(s.dir,name)
                    if os.path.isdir(source):shutil.copytree(source,os.path.join(forensic,name))
            shutil.rmtree(s.dir,ignore_errors=True)
            shutil.rmtree(baseline,ignore_errors=True)
    return 0 if all(step['ok'] for step in result['steps']) else 1
