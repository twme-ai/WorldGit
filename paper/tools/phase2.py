"""Phase 2 線上驗收；由 acceptance.py 的 phase2 場景呼叫，自己持有 bench.lock。"""
import json, os, re, shutil, subprocess, time, traceback
import harness
from cli_compat import cli_data, verify_all
from harness import BenchLock, Server, ROOT, WORK, JAVA, wgit


def run(platform, version):
    evidence=os.path.join(WORK,'paper-phase2',f'{platform}-{version}-{int(time.time())}')
    os.makedirs(evidence)
    result={'platform':platform,'version':version,'steps':[],'started':time.strftime('%F %T')}
    def save(): json.dump(result,open(os.path.join(evidence,'results.json'),'w'),ensure_ascii=False,indent=2)
    def check(name,ok,**data):
        result['steps'].append({'name':name,'ok':bool(ok),**data}); save()
        print(('PASS ' if ok else 'FAIL ')+name, str(data)[:300],flush=True)
    def strip(text): return re.sub(r'\x1b\[[0-9;]*m','',text)
    def git(s,*args,dim='minecraft.overworld'):
        return subprocess.check_output(['git','--git-dir',os.path.join(s.world,'.worldgit') if dim == 'minecraft.overworld' else (os.path.join(s.world,'dimensions',*dim.split('.',1),'.worldgit') if version=='26.2' else os.path.join(s.dir,'world_nether' if dim=='minecraft.the_nether' else 'world_the_end','DIM-1' if dim=='minecraft.the_nether' else 'DIM1','.worldgit')),*args],text=True).strip()
    def cmd(s,text,pattern=r'完成|失敗|PARTIAL|錯誤|已切換到|預估|Switched|complete|Plan',allow_partial=False):
        out=strip(s.cmd(text,pattern+'|PARTIAL|失敗|未提交變動|需離線|不支援|尚不支援',900)); print(text,out[-700:],flush=True)
        if not allow_partial and any(word in out for word in ('PARTIAL','失敗','未提交變動','需離線','不支援','尚不支援')): raise RuntimeError(text+' failed: '+out[-1500:]+'\n'+strip(s.cmd('wg status --full',r'world-meta|section|PARTIAL|失敗',120)))
        return out
    def sample(s,bot,cx,cz,sy):
        text=s.cmd(f'wg debug sample {cx} {cz} {sy}',r'WGSAMPLE',60)
        server=re.search(r'hash=([0-9a-f]{64})',text).group(1)
        client=bot.ask(f'sample {cx} {cz} {sy}','sample')
        check(f'逐格 state 封包一致 {cx},{cz},{sy}',client['missing']==0 and server==client['hash'],server=server,client=client,text=strip(text))
    with BenchLock():
        harness.RUN=os.path.join(WORK,'paper-phase2','run')
        baseline=os.path.join(WORK,'paper-delivery','fixtures','acceptance-flat-'+version)
        if not os.path.isdir(baseline):
            subprocess.run([JAVA[version],'-Xmx512m','-cp',os.path.join(ROOT,'cli/build/libs/wgit.jar'),os.path.join(ROOT,'paper/tools/ScaleFixture.java'),version,os.path.join(WORK,'worlds',version,'baseline'),baseline,'16'],check=True)
        s=Server(platform,version,baseline=baseline,run_label=f'phase2-{platform}-{version}',view=3,
                 config={'auto-commit':{'enabled':False,'on-shutdown':False},'commit':{'timeout-seconds':900}})
        try:
            s.start(); result['console']=os.path.relpath(s.evidence_log,ROOT)
            bots=[s.bot('WgBot',mod=True),s.bot('WgBot2'),s.bot('WgBot3')]
            for i,b in enumerate(bots):
                s.cmd(f'tp {b.name} {8+i*2} 65 8'); s.cmd(f'gamemode creative {b.name}')
            time.sleep(5)
            s.cmd('kill @e[type=!player]'); s.cmd('gamerule natural_health_regeneration false'); time.sleep(2)
            s.cmd('wg init',r'init 完成|失敗',900)
            # 兩版在真正伺服器建完 NBT 預設欄位後 commit；無物理 tick 雜訊。
            s.cmd('fill 7 84 7 10 84 9 stone'); s.cmd('fill 11 64 7 13 64 9 stone'); s.cmd('setblock 4 68 4 sea_lantern'); s.cmd('setblock 6 65 6 chest'); s.cmd('setblock 3 65 3 lectern')
            s.cmd('data merge block 6 65 6 {Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}')
            uid='11111111-2222-3333-4444-555555555555'
            s.cmd('summon cow 8 66 10 {UUID:[I;286331153,572666675,1145328981,1431655765],NoAI:1b,NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b}')
            time.sleep(2)
            cmd(s,'wg commit -m A',r'overworld [0-9a-f]{8}|沒有變動|失敗')
            cmd(s,'wg branch A',r'分支：|Branches|錯誤')
            a=git(s,'rev-parse','HEAD')
            # B 的腳下空氣、頭部實心，兩次切換期間玩家存活。
            s.cmd('fill 7 84 7 10 84 9 air'); s.cmd('fill 11 64 7 13 64 9 air'); s.cmd('fill 11 65 7 13 67 9 stone')
            s.cmd('setblock 4 68 4 glowstone'); s.cmd('setblock 6 65 6 barrel'); s.cmd('setblock 3 65 3 air'); s.cmd('setblock 4 65 3 lectern')
            s.cmd('data merge block 6 65 6 {Items:[{Slot:0b,id:"minecraft:emerald",count:5}]}')
            s.cmd('data merge entity @e[type=cow,limit=1] {CustomName:\'{"text":"B"}\'}')
            s.cmd('setblock 24 68 4 gold_block'); time.sleep(2)
            cmd(s,'wg commit -m B',r'overworld [0-9a-f]{8}|沒有變動|失敗')
            cmd(s,'wg branch B',r'分支：|Branches|錯誤')
            b=git(s,'rev-parse','HEAD')
            check('兩個分支內容不同',a!=b,a=a,b=b)
            # 建立現有 mod ghost，再完整 reset 觸發 clear 舊 preview。
            s.cmd('setblock 3 69 3 gold_block')
            bots[0].ask('chat /wg diff --show','chat_sent'); time.sleep(3)
            before=bots[0].ask('stats','stats')['received']
            cmd(s,'wg reset --hard')
            time.sleep(1)
            after=bots[0].ask('stats','stats')['received']
            check('套用清除 mod 舊鬼影',after['clear']>before['clear'] and not after['previews'],before=before,after=after)
            # 先以 A 準備玩家位置，然後生存模式站在 B 會改變的範圍。
            cmd(s,'wg switch A --force')
            for i,p in enumerate(bots):
                x,y=(8,85) if i<2 else (12,65)
                s.cmd(f'tp {p.name} {x} {y} 8'); s.cmd(f'gamemode survival {p.name}'); s.cmd(f'effect give {p.name} minecraft:instant_health 1 5')
            for rev in ('B','A'):
                mark=s.mark(); start=time.monotonic(); output=cmd(s,'wg switch '+rev); wall=time.monotonic()-start
                check('switch '+rev+' 完成',('已切換到' in output or 'Switched to' in output),wall_seconds=wall,output=output[-1800:])
                time.sleep(2)
                for bot in bots:
                    sample(s,bot,0,0,4)
                    sample(s,bot,0,0,5)
                health=[p.ask('health','health_now')['health'] for p in bots]
                check('玩家無窒息／摔落傷害 '+rev,all(h==20 for h in health),health=health)
                protection=s.cmd('wg debug protection WgBot',r'ENTITY_ATTACK',30)
                check('三種保護＋攻擊對照 '+rev,all(f'cause={cause} cancelled=true' in protection for cause in ('FALL','SUFFOCATION','DROWNING')) and 'cause=ENTITY_ATTACK cancelled=false' in protection,text=strip(protection))
                check('switch log 無 ERROR '+rev,not any('ERROR' in l or 'Exception' in l for l in s.lines_since(mark)),lines=s.lines_since(mark)[-15:])
            for p in bots: s.cmd(f'gamemode creative {p.name}'); s.cmd(f'tp {p.name} 8 70 8')
            head=git(s,'rev-parse','HEAD')
            cmd(s,'wg branch restored',r'分支：|Branches|錯誤')
            cmd(s,'wg switch restored')
            cmd(s,'wg restore B --box 24 68 4 24 68 4 --dry-run')
            check('dry-run 不寫入與 HEAD 不動',git(s,'rev-parse','HEAD')==head and bots[0].ask('block 24 68 4','block')['name']=='air')
            cmd(s,'wg restore B --box 24 68 4 24 68 4')
            check('box 只改指定格且 HEAD 不動',git(s,'rev-parse','HEAD')==head and bots[0].ask('block 24 68 4','block')['name']=='gold_block' and bots[0].ask('block 4 68 4','block')['name']=='sea_lantern')
            cmd(s,'wg commit -m restore-box',r'overworld [0-9a-f]{8}|沒有變動|失敗')
            check('restore 之後 commit 正常',git(s,'rev-parse','HEAD')!=head)
            cmd(s,'wg switch A --force')
            cmd(s,'wg restore B --chunks 0')
            check('半徑只改中心 chunk 且 HEAD 不動',git(s,'rev-parse','HEAD')==a and bots[0].ask('block 4 68 4','block')['name']=='glowstone' and bots[0].ask('block 24 68 4','block')['name']=='air')
            cmd(s,'wg reset --hard')
            s.cmd('setblock 3 69 3 diamond_block'); time.sleep(1)
            out=cmd(s,'wg switch B --stash'); cmd(s,'wg switch A --force'); cmd(s,'wg stash pop')
            check('switch --stash→切回→pop',bots[0].ask('block 3 69 3','block')['name']=='diamond_block')
            cmd(s,'wg reset --hard')
            # 1000 chunk switch，有 bot；在真正寫入後取消，journal 與 HEAD 皆可恢復。
            cmd(s,'wg branch benchwork',r'分支：|Branches|錯誤')
            cmd(s,'wg switch benchwork')
            s.cmd('wg debug fill 32 gold_block 1000',r'debug fill 完成',900)
            cmd(s,'wg commit -m large',r'overworld [0-9a-f]{8}|沒有變動|失敗'); cmd(s,'wg branch large',r'分支：|Branches|錯誤')
            s.cmd('wg debug release',r'已釋放',30)
            for i,bot in enumerate(bots):
                x,z=((-184,-184),(184,-184),(8,184))[i]
                s.cmd(f'tp {bot.name} {x} 70 {z}')
            time.sleep(3)
            result['benchmark']=[]
            for revision in ('A','large','A'):
                s.cmd('wg debug probe start',r'probe 開始')
                started=time.monotonic(); output=cmd(s,'wg switch '+revision+' --force')
                wall=time.monotonic()-started
                probe=strip(s.cmd('wg debug probe stop',r'probe ticks='))
                metrics=re.search(r'WorldGit apply sections=(\d+) ticketPeak=(\d+) ticketRemaining=(\d+)',output)
                check('1000 chunk switch '+revision,('已切換到' in output or 'Switched to' in output) and metrics is not None and int(metrics[1])==1000 and int(metrics[2])<=16 and int(metrics[3])==0,wall_seconds=wall,probe=probe,output=output[-1200:])
                result['benchmark'].append({'revision':revision,'wall_seconds':wall,'probe':probe}); save()
            m=s.mark(); s.send('wg switch large --force')
            journal=os.path.join(s.world,'.worldgit','apply-state.yml')
            end=time.time()+180
            written=False
            while time.time()<end:
                if os.path.isfile(journal) and 'state: APPLYING' in open(journal).read():
                    status=strip(s.cmd('wg debug apply',r'WGAPPLY',30))
                    if re.search(r'sections=[1-9]',status): written=True; break
                    if 'idle' in status and 'state: COMPLETE' in open(journal).read(): raise RuntimeError('取消測試尚未寫入就結束，測試快照應有1000個變動chunk')
                time.sleep(.1)
            if not written: raise RuntimeError('取消測試沒有進入實際 section 寫入')
            s.send('wg cancel'); s.wait(r'世界為 PARTIAL|已切換到',900,m)
            check('取消留下 PARTIAL', 'state: PARTIAL' in open(journal).read(),journal=open(journal).read())
            out=cmd(s,'wg commit -m blocked',r'PARTIAL|overworld [0-9a-f]{8}',allow_partial=True)
            check('PARTIAL 阻擋 commit', 'PARTIAL' in out)
            cmd(s,'wg switch A --force')
            check('完整重套恢復 COMPLETE','state: COMPLETE' in open(journal).read() and git(s,'rev-parse','HEAD')==a)
            result['final_head']=a
            # 等尾段保護結束後同事件必須不再取消。
            time.sleep(11)
            protection=s.cmd('wg debug protection WgBot',r'ENTITY_ATTACK',30)
            check('十秒後恢復原本傷害',all(f'cause={cause} cancelled=false' in protection for cause in ('FALL','SUFFOCATION','DROWNING')),text=strip(protection))
        except BaseException:
            check('場景例外',False,trace=traceback.format_exc())
        finally:
            s.stop()
            try:
                output=verify_all(lambda world,*words:cli_data(wgit(['--world',world,'--format=json',*words])),s.world,{'minecraft:overworld':'A'})
                check('存檔後離線 verify 差異 0',len(output)==3,output=output)
                raw=subprocess.check_output([JAVA[version],'-cp',os.path.join(ROOT,'cli/build/libs/wgit.jar'),os.path.join(ROOT,'paper/tools/ApplyEvidence.java'),s.world,uid],text=True)
                inspection=json.loads(raw)
                check('全維度實體 UUID 無重複無遺失',inspection['duplicates']==0 and inspection['controlledUuidCount']==1,inspection=inspection)
                check('存檔光照存在且光源及鄰格正確',inspection['blockLightSections']>0 and inspection['skyLightSections']>0 and (inspection['source'],inspection['adjacent'],inspection['sun'])==(15,14,15),inspection=inspection)
                check('講台 POI 恢復舊位置', '[3, 65, 3]' in inspection['poi'] and '[4, 65, 3]' not in inspection['poi'],inspection=inspection)
            except Exception: check('verify 例外',False,trace=traceback.format_exc())
            problems=[l for l in s.lines_since() if 'ERROR' in l or 'Exception' in l]
            check('完整 log 無 ERROR／Exception',not problems,problems=problems[-30:])
            result['finished']=time.strftime('%F %T'); save()
            shutil.rmtree(s.dir,ignore_errors=True)
    return 0 if all(s['ok'] for s in result['steps']) else 1
