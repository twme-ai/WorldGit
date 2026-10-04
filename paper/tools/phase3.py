"""Phase 3 Paper／Folia：線上 merge／工具／GUI／重啟／patch／1000 chunk，有 bot。自己持 bench.lock。"""
import glob, hashlib, json, os, re, shutil, statistics, subprocess, time, traceback
import harness
from harness import BenchLock, Server, ROOT, WORK, JAVA


def run(platform, version):
    evidence=os.path.join(WORK,'paper-phase3',f'{platform}-{version}-{int(time.time())}')
    os.makedirs(evidence)
    result={'platform':platform,'version':version,'steps':[],'started':time.strftime('%F %T'),'screenshots':[]}
    def save():
        with open(os.path.join(evidence,'results.json'),'w') as f: json.dump(result,f,ensure_ascii=False,indent=2)
    def check(name,ok,**data):
        result['steps'].append({'name':name,'ok':bool(ok),**data}); save()
        print(('PASS ' if ok else 'FAIL ')+name,str(data)[:350],flush=True)
        if not ok: raise AssertionError(name)
    def strip(t): return re.sub(r'\x1b\[[0-9;]*m','',t)
    cli=os.path.join(evidence,'wgit.jar')
    helper=os.path.join(evidence,'classes');os.makedirs(helper)
    def wgit(args,check=True):
        proc=subprocess.run([JAVA['1.21.11'],'-Xmx512m','-jar',cli,*args],text=True,capture_output=True,timeout=900)
        text=strip(proc.stdout)
        with open(os.path.join(evidence,'cli.log'),'a') as f:f.write('wgit '+str(args)+'\n'+text+'\n'+strip(proc.stderr)+'\n')
        if check and proc.returncode: raise RuntimeError(text+strip(proc.stderr))
        return text
    def inspect(action,*args):
        out=subprocess.check_output([JAVA['1.21.11'],'-Xmx512m','-cp',helper+':'+cli,'MergeEvidence',action,*map(str,args)],text=True,timeout=90)
        return json.loads(out) if action in ('state','wire') else out.strip()
    def git(*args):return subprocess.check_output(['git','--git-dir',os.path.join(s.dir,'.worldgit','world','minecraft.overworld'),*args],text=True).strip()
    def cmd(text,pattern=r'合併操作完成|Merge operation complete|錯誤|Error|PARTIAL',allow_error=False):
        out=strip(s.cmd(text,pattern,900));print(text,out[-450:],flush=True)
        if not allow_error and any(t in out for t in ('錯誤：','Error:','PARTIAL','失敗：')):raise RuntimeError(text+'\n'+out)
        # read-only UI refresh is queued after coordinator returns
        time.sleep(.25)
        return out
    def commit(message):return cmd('wg commit -m '+message,r'overworld [0-9a-f]{8}|世界存檔點|World snapshot|合併操作完成|Merge operation complete|錯誤|Error|失敗')
    def branch(name):return cmd('wg branch '+name,r'分支：|Branches|錯誤|Error')
    def sample(bot,cx,cz,sy=4,revision=None):
        server=strip(s.cmd(f'wg debug sample {cx} {cz} {sy}',r'WGSAMPLE',90))
        h=re.search(r'hash=([0-9a-f]{64})',server).group(1)
        client=bot.ask(f'sample {cx} {cz} {sy}','sample',60)
        target=inspect('section',s.world,revision,cx,cz,sy) if revision else h
        check(f'逐格 state／client {revision or "live"} {cx},{cz},{sy}',client['missing']==0 and client['hash']==h==target,server=h,client=client,target=target)
    def stored_chunks():
        count=0
        for path in glob.glob(os.path.join(s.dir,'world*','**','region','r.*.mca'),recursive=True):
            with open(path,'rb') as f:header=f.read(4096)
            count+=sum(header[i:i+4]!=bytes(4) for i in range(0,len(header),4))
        return count
    def state():return inspect('state',s.world)
    def wait_state(choice=None,resolved=None,timeout=180):
        end=time.time()+timeout
        while time.time()<end:
            v=state()
            if v:
                rows=[r for d in v['dimensions'].values() for r in d['report']['regions']]
                if rows and (choice is None or rows[0]['choice']==choice) and (resolved is None or rows[0]['resolved']==resolved):return v,rows
            time.sleep(.25)
        raise TimeoutError('MERGING state '+str(v))
    def tool(action='cycle',expected=None):
        mark=s.mark(); started=time.monotonic()
        # 模擬 Bukkit 事件；不是直接呼叫 selectRegion（驗收允許此方式）。
        s.send('wg debug merge-tool WgBot '+action)
        if action=='cycle':
            s.wait(r'WGREGIONDONE|錯誤|Error|PARTIAL',900,mark)
            wall=time.monotonic()-started
            prot=strip(s.cmd('wg debug protection WgBot',r'ENTITY_ATTACK'))
            check('區域切換三種保護',all(f'cause={k} cancelled=true' in prot for k in ('FALL','SUFFOCATION','DROWNING')),output=prot[-400:])
        if expected:wait_state(expected, action=='resolve')
        if action=='cycle':result.setdefault('region_switch_seconds',[]).append(wall)
        save();return wall if action=='cycle' else None
    def builds(bot,x,item):
        s.cmd(f'tp {bot.name} {x+5} 65 8');time.sleep(1)
        s.cmd('wg debug freeze off',r'WGFREEZE restored');time.sleep(.5)
        bot.ask('give '+item,'give');bot.ask(f'place {x} 63 8 0 1 0','placed',30);time.sleep(.5)
        s.cmd('wg debug freeze on',r'WGFREEZE frozen')
    def variant(material,fence,power):
        # 快照保存實際來源 state；完整 section hash 隨後取 repo，不靠方塊名稱斷言。
        for c in ('15 64 2','15 65 2','16 64 2','17 64 2'):s.cmd('setblock '+c+' air')
        s.cmd(f'setblock 15 64 2 {material}_door[facing=east,half=lower,hinge=left,open=false,powered=false]')
        s.cmd(f'setblock 15 65 2 {material}_door[facing=east,half=upper,hinge=left,open=false,powered=false]')
        s.cmd(f'setblock 17 64 2 repeater[delay={ {0:1,5:2,9:3}[power] },facing=north,locked=false,powered=false]')
        s.cmd(f'setblock 16 64 2 {fence}_fence[north=false,east=false,south=false,west=false,waterlogged=false]')
        time.sleep(.3)
    with BenchLock():
        shutil.copy(os.path.join(ROOT,'cli/build/libs/wgit.jar'),cli)
        frozen=os.path.join(evidence,'worldgit-paper.jar');shutil.copy(harness.plugin_jar(),frozen)
        result['plugin_sha256']=hashlib.sha256(open(frozen,'rb').read()).hexdigest();save()
        subprocess.run([JAVA['1.21.11'][:-4]+'javac','-cp',cli,'-d',helper,os.path.join(ROOT,'paper/tools/MergeEvidence.java')],check=True)
        harness.RUN=os.path.join(WORK,'paper-phase3','run')
        harness.PORTS.update({'paper-1.21.11':25701,'paper-26.2':25702,'folia-1.21.11':25703,'folia-26.2':25704})
        baseline=os.path.join(WORK,'paper-delivery','fixtures','acceptance-flat-'+version)
        if not os.path.isdir(baseline):subprocess.run([JAVA[version],'-Xmx512m','-cp',cli,os.path.join(ROOT,'paper/tools/ScaleFixture.java'),version,os.path.join(WORK,'worlds',version,'baseline'),baseline,'16'],check=True)
        s=Server(platform,version,baseline=baseline,run_label=f'phase3-{platform}-{version}',view=3,config={'auto-commit':{'enabled':False,'on-shutdown':False},'commit':{'timeout-seconds':900}})
        shutil.copy(frozen,os.path.join(s.dir,'plugins/worldgit-paper.jar'))
        try:
            dump=os.path.join(evidence,'wire.log');os.environ['WG_BOT_DUMP']=dump
            s.start();result['console']=[os.path.relpath(s.evidence_log,ROOT)];save()
            bots=[s.bot('WgBot',mod=True),s.bot('WgBot2')]
            for i,p in enumerate(bots):s.cmd(f'tp {p.name} {8+24*i} 65 8');s.cmd(f'gamemode creative {p.name}')
            time.sleep(4)
            s.cmd('wg debug freeze on',r'WGFREEZE frozen');s.cmd('gamerule natural_health_regeneration false');s.cmd('kill @e[type=!player]')
            variant('oak','oak',0)
            out=cmd('wg conflict-select 1 ours',r'No merge is in progress|沒有 MERGING',True)
            check('非 MERGING conflict-select 被擋',state() is None,output=out)
            s.cmd('wg init',r'init 完成|失敗',900);branch('base');branch('B')
            builds(bots[0],2,'gold_block')
            s.cmd('setblock 4 65 6 chest');s.cmd('data merge block 4 65 6 {Items:[{Slot:0b,id:"minecraft:diamond",count:4}]}')
            s.cmd('summon cow 5 66 10 {UUID:[I;286331153,572666675,1145328981,1431655765],NoAI:1b,NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b}')
            commit('A');branch('A-original');a=git('rev-parse','HEAD');cmd('wg switch B',r'已切換到|Switched|錯誤|Error|PARTIAL')
            builds(bots[1],34,'diamond_block')
            s.cmd('setblock 40 65 6 barrel');s.cmd('data merge block 40 65 6 {Items:[{Slot:0b,id:"minecraft:emerald",count:9}]}')
            s.cmd('summon cow 41 66 10 {UUID:[I;572662306,858997828,1431655765,1717986918],NoAI:1b,NoGravity:1b,Invulnerable:1b,PersistenceRequired:1b}')
            commit('B');branch('B-original');b=git('rev-parse','HEAD');cmd('wg switch A-original',r'已切換到|Switched|錯誤|Error|PARTIAL')
            out=cmd('wg merge B-original');clean=git('rev-parse','HEAD');branch('merged-clean')
            check('不同位置零介入、兩 parent',state() is None and git('rev-list','--parents','-n','1','HEAD').split()[1:]==[a,b],output=out,head=clean)
            for bot,cx in [(bots[0],0),(bots[1],2)]:sample(bot,cx,0,revision='merged-clean')
            s.stop();out=wgit(['--world',s.world,'verify','merged-clean']);check('clean merge 離線 verify=0','COMPLETE' in out and 'PARTIAL' not in out,output=out)
            # 离線完整 NBT：兩份 BE 與兩個 UUID（不只看 packet）。
            s.start();result['console'].append(os.path.relpath(s.evidence_log,ROOT));bots=[s.bot('WgBot',mod=True),s.bot('WgBot2')];time.sleep(4);s.cmd('wg debug freeze on',r'WGFREEZE frozen')
            cmd('wg switch base',r'已切換到|Switched|錯誤|Error|PARTIAL');branch('ours-work');cmd('wg switch ours-work',r'已切換到|Switched|錯誤|Error|PARTIAL')
            variant('spruce','spruce',5);commit('ours');branch('ours-original');ours=git('rev-parse','HEAD')
            cmd('wg switch base',r'已切換到|Switched|錯誤|Error|PARTIAL');branch('theirs-work');cmd('wg switch theirs-work',r'已切換到|Switched|錯誤|Error|PARTIAL')
            variant('birch','birch',9);commit('theirs');branch('theirs-original');theirs=git('rev-parse','HEAD');cmd('wg switch ours-work',r'已切換到|Switched|錯誤|Error|PARTIAL')
            cmd('wg merge theirs-original');v,regions=wait_state('OURS',False)
            result['regions']=regions;save();check('門／柵欄／紅石跨 chunk 衝突',len(regions)==1 and regions[0]['blockCount']==4 and regions[0]['redstone'] and regions[0]['bounds']=={'minX':15,'minY':64,'minZ':2,'maxX':17,'maxY':65,'maxZ':2},regions=regions)
            out=cmd('wg conflict-select 999 ours',r'找不到衝突區域|Unknown conflict region',True)
            check('conflict-select 未知區域不改狀態',state()['dimensions']['minecraft:overworld']['report']['regions'][0]['choice']=='OURS',output=out)
            for invalid in ('0 ours','-1 base','1 invalid','1 ours extra'):
                before=state()
                # Brigadier 型別／literal 錯誤由原生 dispatcher 標示位置，取代舊 parser 的整頁用法。
                out=cmd('wg conflict-select '+invalid,r'<--\[HERE\]',True)
                check('conflict-select 非法參數 '+invalid,
                      bool(re.search(r'Incorrect argument|Unknown or incomplete command|Expected integer|Integer must not be less than',out))
                      and '<--[HERE]' in out and state()==before,output=out)
            for choice,rev in [('theirs','theirs-original'),('base','base'),('ours','ours-original')]:
                cmd('wg conflict-select #1 '+choice)
                selected=state()['dimensions']['minecraft:overworld']['report']['regions'][0]
                check('conflict-select '+choice+' 保持 unresolved',selected['choice']==choice.upper() and not selected['resolved'])
                sample(bots[0],0,0,revision=rev);sample(bots[0],1,0,revision=rev)
            cmd('wg conflict-select all manual')
            check('conflict-select manual 保持 unresolved',all(not r['resolved'] and r['choice']=='MANUAL' for d in state()['dimensions'].values() for r in d['report']['regions']))
            cmd('wg conflict-select all ours')
            out=cmd('wg status',r'MERGING|錯誤|Error');check('status 顯示 MERGING','MERGING' in out)
            out=cmd('wg switch base --force',r'MERGING|錯誤|Error',True);check('MERGING force switch 被擋','MERGING' in out and git('rev-parse','HEAD')==ours)
            out=cmd('wg commit -m blocked',r'衝突區域未解決|unresolved|錯誤|Error',True);check('unresolved commit 被擋',git('rev-parse','HEAD')==ours)
            # 重啟：與 CLI 的清單逐欄一致，狀態／bar／outline／工具重新可用。
            s.stop();cli_regions=json.loads(wgit(['--world',s.world,'--format=json','conflicts']));norm=lambda rs:[{'id':r['id'],'dim':r['dimension']['value'] if isinstance(r['dimension'],dict) else r['dimension'],'bounds':r['bounds'],'count':r['blockCount'],'redstone':r['redstone'],'choice':r['choice'].upper(),'atoms':r['atoms']} for r in rs];check('區域清單／bbox 與 CLI 一致',norm(cli_regions)==norm(regions),cli=cli_regions)
            s.start();result['console'].append(os.path.relpath(s.evidence_log,ROOT));bots=[s.bot('WgBot',mod=True),s.bot('WgBot2')];time.sleep(4);s.cmd('wg debug freeze on',r'WGFREEZE frozen')
            for bot in bots:s.cmd(f'tp {bot.name} 17.5 65 2.5');s.cmd(f'gamemode creative {bot.name}')
            time.sleep(3);wait_state('OURS',False)
            displays=[p.ask('entities','entities')['displays'] for p in bots]
            check('重啟 MERGING 與全體授權玩家描邊恢復',all(x>=12 for x in displays),displays=displays)
            bots[0].ask('chat /wg tool','chat_sent');time.sleep(2)
            s.cmd('gamemode survival WgBot');s.cmd('effect give WgBot minecraft:instant_health 1 5')
            for choice,rev in [('THEIRS','theirs-original'),('BASE','base'),('OURS','ours-original')]*2:
                tool(expected=choice)
                for bot in bots:
                    sample(bot,0,0,revision=rev);sample(bot,1,0,revision=rev)
                check('區域內切換無傷害 '+choice,bots[0].ask('health','health_now')['health']==20)
            result['region_latency']={'stored_chunks':stored_chunks(),'samples_seconds':result['region_switch_seconds'],'median_seconds':statistics.median(result['region_switch_seconds']),'max_seconds':max(result['region_switch_seconds'])}
            check('小區域切換中位數 ≤ 2 秒',result['region_latency']['median_seconds']<=2,**result['region_latency'])
            s.cmd('gamemode creative WgBot')
            bots[0].ask('chat /wg conflicts','chat_sent');time.sleep(2);win=bots[0].ask('window','window');check('箱子 GUI 開啟含清單',win['opened'] and win['slots']>=3,window=win)
            # 真 client inventory click packet；Folia handler 使用 teleportAsync。
            bots[0].ask('click 0','clicked');time.sleep(2);pos=bots[0].ask('pos','pos')['pos'];check('GUI 點擊傳送',abs(pos['x']-16.5)<1 and abs(pos['z']-2.5)<1,position=pos)
            bots[0].ask('chat /wg conflict-preview 1 theirs','chat_sent');time.sleep(3)
            stats=bots[0].ask('stats','stats')['received'];check('merge-regions-v1 清單／預覽實際傳送',stats['conflicts']>0 and stats['conflictPreview']>0,stats=stats)
            wire=inspect('wire',dump);check('MergeProtocol 真 payload 收齊解碼',any(p['type']=='PREVIEW' and p['region']==1 and len(p['cells'])==4 for p in wire),batches=len(wire))
            # abort 丟棄 MERGING 時手動變更、還原逐格世界及 HEAD。
            s.cmd('setblock 20 65 5 gold_block');cmd('wg merge --abort');check('abort HEAD／狀態恢復',state() is None and git('rev-parse','HEAD')==ours)
            for bot in bots:sample(bot,0,0,revision='ours-original');sample(bot,1,0,revision='ours-original')
            s.stop();out=wgit(['--world',s.world,'verify','ours-original']);check('abort 全世界離線 verify=0','COMPLETE' in out and 'PARTIAL' not in out,output=out)
            s.start();result['console'].append(os.path.relpath(s.evidence_log,ROOT));bots=[s.bot('WgBot',mod=True),s.bot('WgBot2')];time.sleep(4);s.cmd('wg debug freeze on',r'WGFREEZE frozen')
            cmd('wg merge theirs-original');cmd('wg resolve all theirs');commit('resolved-merge');check('全部 resolve 後 commit 兩 parent',state() is None and git('rev-list','--parents','-n','1','HEAD').split()[1:]==[ours,theirs]);branch('resolved-result')
            cmd('wg switch ours-original',r'已切換到|Switched|錯誤|Error|PARTIAL');cmd('wg merge theirs-original');s.cmd('setblock 17 64 2 diamond_block');cmd('wg resolve all manual');cmd('wg merge --continue');check('manual 使用目前世界',bots[0].ask('block 17 64 2','block')['name']=='diamond_block');branch('manual-result')
            # patch 的乾淨例；單 parent 提交。
            cmd('wg switch base',r'已切換到|Switched|錯誤|Error|PARTIAL');cmd('wg cherry-pick '+b);picked=git('rev-parse','HEAD');check('cherry-pick 乾淨直接 commit',state() is None and len(git('rev-list','--parents','-n','1','HEAD').split())==2)
            cmd('wg revert '+b);check('revert 乾淨直接 commit',state() is None and git('rev-parse','HEAD')!=picked and len(git('rev-list','--parents','-n','1','HEAD').split())==2)
            # 有衝突的 patch 走同一工具／abort 流程。
            cmd('wg switch theirs-original',r'已切換到|Switched|錯誤|Error|PARTIAL');cmd('wg cherry-pick '+ours);check('cherry-pick 衝突進 MERGING',state() is not None);cmd('wg merge --abort')
            cmd('wg revert '+ours);check('revert 衝突進 MERGING',state() is not None);cmd('wg merge --abort')
            # 兩側各 1000 chunk：同 section 不同 local X，零衝突。
            cmd('wg switch merged-clean',r'已切換到|Switched|錯誤|Error|PARTIAL');branch('bench-base');branch('bench-B');branch('bench-A');cmd('wg switch bench-A',r'已切換到|Switched|錯誤|Error|PARTIAL')
            s.cmd('wg debug fill 32 gold_block 1000 8',r'debug fill 完成',900);commit('bench A');s.cmd('wg debug release',r'已釋放')
            cmd('wg switch bench-B',r'已切換到|Switched|錯誤|Error|PARTIAL');s.cmd('wg debug fill 32 diamond_block 1000 9',r'debug fill 完成',900);commit('bench B');s.cmd('wg debug release',r'已釋放')
            cmd('wg switch bench-A',r'已切換到|Switched|錯誤|Error|PARTIAL');s.cmd('wg debug probe start',r'probe 開始');started=time.monotonic();out=cmd('wg merge bench-B');wall=time.monotonic()-started
            probe=strip(s.cmd('wg debug probe stop',r'probe ticks='));result['benchmark']={'wall_seconds':wall,'probe':probe,'output':out[-1500:]};save()
            check('線上 1000 chunk merge、bot 在線、零衝突',state() is None and len(git('rev-list','--parents','-n','1','HEAD').split())==3,seconds=wall,probe=probe)
            # 大世界、200 個不相連區域：同一 chunk 內只改 local X=10，跨 chunk 距離 16。
            branch('conflict200-B');branch('conflict200-A');cmd('wg switch conflict200-A',r'已切換到|Switched|錯誤|Error|PARTIAL')
            s.cmd('wg debug fill 32 emerald_block 200 10',r'debug fill 完成',900);commit('200 ours');s.cmd('wg debug release',r'已釋放')
            cmd('wg switch conflict200-B',r'已切換到|Switched|錯誤|Error|PARTIAL')
            s.cmd('wg debug fill 32 lapis_block 200 10',r'debug fill 完成',900);commit('200 theirs');s.cmd('wg debug release',r'已釋放')
            cmd('wg switch conflict200-A',r'已切換到|Switched|錯誤|Error|PARTIAL');cmd('wg merge conflict200-B')
            many=state();rows=[r for d in many['dimensions'].values() for r in d['report']['regions']]
            check('大世界有 200 個衝突區域',len(rows)==200,regions=len(rows))
            latency=[]
            for choice in ('theirs','base','ours','theirs','base','ours'):
                mark=s.mark();started=time.monotonic();s.send('wg resolve 1 '+choice)
                s.wait(r'WGREGIONDONE|錯誤|Error|PARTIAL',900,mark);latency.append(time.monotonic()-started)
                many=state();selected=[r for d in many['dimensions'].values() for r in d['report']['regions'] if r['id']==1][0]
                check('200 區域切換 '+choice,selected['choice']==choice.upper(),seconds=latency[-1])
            result['region_latency_200']={'stored_chunks':stored_chunks(),'samples_seconds':latency,'median_seconds':statistics.median(latency),'max_seconds':max(latency),'regions':len(rows)}
            check('200 區域單區切換中位數 ≤ 2 秒',statistics.median(latency)<=2,**result['region_latency_200'])
            cmd('wg resolve all theirs');cmd('wg merge --continue')
            result['final_head']=git('rev-parse','HEAD');save()
        except BaseException:
            result['steps'].append({'name':'場景例外','ok':False,'trace':traceback.format_exc()});save();print(traceback.format_exc(),flush=True)
        finally:
            s.stop()
            try:
                out=wgit(['--world',s.world,'verify','HEAD']);check('最終完整離線 verify=0','COMPLETE' in out and 'PARTIAL' not in out,output=out)
                # all UUID including passengers; BE 在 verify 與正式快照比較範圍內。
                raw=subprocess.check_output([JAVA[version],'-cp',cli,os.path.join(ROOT,'paper/tools/ApplyEvidence.java'),s.world,'11111111-2222-3333-4444-555555555555'],text=True);items=json.loads(raw)
                check('實體無重複',items['duplicates']==0,inspection=items)
            except BaseException:
                result['steps'].append({'name':'離線驗證例外','ok':False,'trace':traceback.format_exc()});save()
            problems=[l for l in s.lines_since() if 'ERROR' in l or 'Exception' in l];result['problem_lines']=problems[-30:]
            result['profiles']=[]
            for line in s.lines_since():
                if 'WGPROFILE {' in line: result['profiles'].append(json.loads(line[line.index('WGPROFILE ')+10:]))
            result['finished']=time.strftime('%F %T');save()
            if not os.environ.get('WG_KEEP_SERVER'):shutil.rmtree(s.dir,ignore_errors=True)
            os.environ.pop('WG_BOT_DUMP',None)
    failed=[x['name'] for x in result['steps'] if not x['ok']]
    print('FAILED:',failed or 'none','evidence:',evidence,flush=True)
    return bool(failed or result.get('problem_lines'))
