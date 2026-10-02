"""在已寫入的 switch 中關服、重開並 force 恢復；acceptance.py 自取 bench.lock。"""
import json, os, re, shutil, time, traceback
import harness
from harness import BenchLock, Server, ROOT, WORK, wgit


def run(platform,version,shutdown=True):
    scenario='shutdown' if shutdown else 'cancel'
    evidence=os.path.join(WORK,'paper-phase2',f'{scenario}-{platform}-{version}-{int(time.time())}')
    os.makedirs(evidence)
    result={'platform':platform,'version':version,'scenario':scenario,'steps':[],'console_logs':[]}
    def check(name,ok,**data):
        result['steps'].append({'name':name,'ok':bool(ok),**data})
        with open(os.path.join(evidence,'results.json'),'w') as f: json.dump(result,f,ensure_ascii=False,indent=2)
        print(('PASS ' if ok else 'FAIL ')+name,str(data)[:350],flush=True)
    def cmd(s,text,pattern,timeout=900):
        out=s.cmd(text,pattern+'|失敗|PARTIAL',timeout)
        if '失敗' in out or 'PARTIAL' in out: raise RuntimeError(text+': '+out[-1500:])
        return out
    def head(s):
        import subprocess
        return subprocess.check_output(['git','--git-dir',os.path.join(s.dir,'.worldgit','world','minecraft.overworld'),'rev-parse','HEAD'],text=True).strip()
    with BenchLock():
        harness.RUN=os.path.join(WORK,'paper-phase2','run')
        baseline=os.path.join(WORK,'paper-delivery','fixtures','acceptance-flat-'+version)
        s=Server(platform,version,baseline=baseline,run_label=f'{scenario}-{platform}-{version}',view=2,
                 config={'auto-commit':{'enabled':False,'on-shutdown':False},'commit':{'timeout-seconds':900}})
        journal=os.path.join(s.dir,'.worldgit','world','apply-state.yml')
        try:
            s.start(); result['console_logs'].append(os.path.relpath(s.evidence_log,ROOT))
            bots=[s.bot('WgBot',mod=True),s.bot('WgBot2',mod=True)]
            cmd(s,'wg init',r'init 完成')
            cmd(s,'wg branch A',r'分支：|Branches')
            cmd(s,'wg debug fill 32 gold_block 1000',r'debug fill 完成')
            # 各 bot 保留一種預覽，status／diff 都必須在下一次套用清掉。
            for bot,text in zip(bots,('/wg status --show','/wg diff --show --radius 1')):
                bot.ask('chat '+text,'chat_sent')
            deadline=time.monotonic()+60
            previews=[]
            while time.monotonic()<deadline:
                previews=[bot.ask('stats','stats')['received'] for bot in bots]
                if previews[0]['status']>0 and previews[1]['diff']>0: break
                time.sleep(.2)
            check('mod status／diff 預覽均已建立',previews[0]['status']>0 and previews[1]['diff']>0 and all(p['previews'] for p in previews),before=previews)
            cmd(s,'wg commit -m shutdown-B',r'overworld [0-9a-f]{8}|沒有變動')
            before=head(s); s.cmd('wg debug release',r'已釋放')
            cmd(s,'wg reset --hard',r'完成|Complete')
            time.sleep(1)
            cleared=[bot.ask('stats','stats')['received'] for bot in bots]
            check('套用清除 mod status／diff 舊分包狀態',all(a['clear']>b['clear'] and not a['previews'] for a,b in zip(cleared,previews)),before=previews,after=cleared)
            mark=s.mark(); s.send('wg switch A --force')
            deadline=time.monotonic()+180
            written=False
            while time.monotonic()<deadline:
                if os.path.isfile(journal) and 'state: APPLYING' in open(journal).read():
                    status=s.cmd('wg debug apply',r'WGAPPLY',30)
                    if re.search(r'sections=[1-9]',status): written=True; break
                time.sleep(.1)
            if not written: raise RuntimeError('關服測試沒有進入實際 section 寫入')
            if shutdown:
                s.stop()
                state=open(journal).read()
                check('進行中關服保留 PARTIAL 且 HEAD 不動','state: PARTIAL' in state and head(s)==before,journal=state)
                mark=s.mark(); s.start(); result['console_logs'].append(os.path.relpath(s.evidence_log,ROOT))
                warning=s.wait(r'世界為 PARTIAL',60,mark)
                check('重開提示 PARTIAL','PARTIAL' in warning,warning=warning)
            else:
                s.send('wg cancel'); output=s.wait(r'世界為 PARTIAL',900,mark)
                state=open(journal).read()
                check('實際寫入後取消保留 PARTIAL 且 HEAD 不動','state: PARTIAL' in state and head(s)==before,journal=state)
                cleanup='\n'.join(s.lines_since(mark))
                check('取消後釋放所有 ticket',bool(re.search(r'WorldGit apply sections=[1-9][0-9]* ticketPeak=[0-9]+ ticketRemaining=0',cleanup)),output=cleanup[-1800:])
            blocked=s.cmd('wg commit -m blocked',r'PARTIAL',60)
            check('PARTIAL 阻擋 commit','PARTIAL' in blocked,output=blocked)
            if shutdown: s.bot('WgBot'); s.bot('WgBot2')
            output=cmd(s,'wg switch A --force',r'已切換到|Switched')
            check('重新 switch 完整恢復','state: COMPLETE' in open(journal).read(),output=output)
            if not shutdown:
                time.sleep(11)
                output=s.cmd('wg debug protection WgBot',r'ENTITY_ATTACK',30)
                check('恢復後十秒保護到期',all(f'cause={cause} cancelled=false' in output for cause in ('FALL','SUFFOCATION','DROWNING')),output=output)
        except BaseException:
            check(scenario+' 場景例外',False,trace=traceback.format_exc())
        finally:
            s.stop()
            try:
                output=wgit(['--world',s.world,'verify','A'],check=False)
                check('恢復後離線 verify 差異 0','COMPLETE' in output and 'PARTIAL' not in output,output=output)
            except Exception: check('verify 例外',False,trace=traceback.format_exc())
            errors=[l for l in s.lines_since() if 'ERROR' in l or 'Exception' in l]
            check(scenario+' log 無 ERROR／Exception',not errors,errors=errors[-25:])
            shutil.rmtree(s.dir,ignore_errors=True)
    return 0 if all(step['ok'] for step in result['steps']) else 1
