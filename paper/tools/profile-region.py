"""受控 4 格跨 chunk 區域切換 profile（不取代 phase3）；自行取得 bench.lock。"""
import json, os, re, shutil, statistics, subprocess, sys, time
import harness
from harness import BenchLock, Server, WORK

platform, version = sys.argv[1:3]
label=sys.argv[3] if len(sys.argv)>3 else 'baseline'
evidence = os.path.join(WORK, 'region-latency', f'{platform}-{version}-{label}-{int(time.time())}')
os.makedirs(evidence)
result = {'platform': platform, 'version': version, 'switch_seconds': [], 'profiles': []}
with BenchLock():
    harness.RUN = os.path.join(WORK, 'region-latency', 'run')
    harness.PORTS.update({'paper-1.21.11':25701, 'paper-26.2':25702, 'folia-1.21.11':25703, 'folia-26.2':25704})
    side=int(sys.argv[4]) if len(sys.argv)>4 else 16
    baseline = os.path.join(WORK, 'paper-delivery', 'fixtures', 'acceptance-flat-'+version) if side==16 else os.path.join(WORK,'region-latency','fixtures',version+'-'+str(side))
    if not os.path.isdir(baseline):
        subprocess.run([harness.JAVA[version],'-Xmx512m','-cp',os.path.join(harness.ROOT,'cli/build/libs/wgit.jar'),os.path.join(harness.ROOT,'paper/tools/ScaleFixture.java'),version,os.path.join(WORK,'worlds',version,'baseline'),baseline,str(side)],check=True)
    result['fixture_overworld_chunks']=(side+20)**2
    s = Server(platform, version, baseline=baseline, view=3, config={'auto-commit':{'enabled':False,'on-shutdown':False}, 'commit':{'timeout-seconds':900}})
    def cmd(text, pattern=r'合併操作完成|Merge operation complete|錯誤|Error|PARTIAL'):
        out=s.cmd(text,pattern,900)
        if any(t in out for t in ('錯誤：','Error:','PARTIAL','失敗：')): raise RuntimeError(out)
        return out
    def variant(material, fence, delay):
        for pos in ('15 64 2','15 65 2','16 64 2','17 64 2'):s.cmd('setblock '+pos+' air')
        for half,y in [('lower',64),('upper',65)]: s.cmd(f'setblock 15 {y} 2 {material}_door[facing=east,half={half},hinge=left,open=false,powered=false]')
        s.cmd(f'setblock 17 64 2 repeater[delay={delay},facing=north,locked=false,powered=false]')
        s.cmd(f'setblock 16 64 2 {fence}_fence[north=false,east=false,south=false,west=false,waterlogged=false]')
    try:
        s.start(); result['console']=s.evidence_log
        b=s.bot('WgBot',mod=True);s.cmd('tp WgBot 17.5 66 2.5');time.sleep(4)
        s.cmd('wg debug freeze on',r'WGFREEZE frozen');s.cmd('kill @e[type=!player]')
        variant('oak','oak',1);s.cmd('wg init --world world --all',r'init 完成|失敗',900)
        cmd('wg branch base',r'分支：|Branches|錯誤|Error');cmd('wg branch theirs',r'分支：|Branches|錯誤|Error')
        variant('spruce','spruce',2);cmd('wg commit -m ours',r'世界存檔點|World snapshot|overworld [0-9a-f]{8}|錯誤|Error')
        cmd('wg switch theirs',r'已切換到|Switched|錯誤|Error|PARTIAL')
        variant('birch','birch',3);cmd('wg commit -m theirs',r'世界存檔點|World snapshot|overworld [0-9a-f]{8}|錯誤|Error')
        cmd('wg switch main',r'已切換到|Switched|錯誤|Error|PARTIAL');cmd('wg merge theirs')
        for choice in ('theirs','base','ours','theirs','base','ours'):
            t=time.monotonic();cmd('wg resolve 1 '+choice);elapsed=time.monotonic()-t
            result['switch_seconds'].append(elapsed);print(f'{choice}: {elapsed:.3f}s',flush=True)
        result['median_seconds']=statistics.median(result['switch_seconds']);result['max_seconds']=max(result['switch_seconds'])
    finally:
        s.stop()
        for line in s.lines_since():
            if 'WGPROFILE {' in line:
                result['profiles'].append(json.loads(line[line.index('WGPROFILE ')+10:]))
        json.dump(result,open(os.path.join(evidence,'results.json'),'w'),ensure_ascii=False,indent=2)
        shutil.rmtree(s.dir,ignore_errors=True)
print(evidence,flush=True)
