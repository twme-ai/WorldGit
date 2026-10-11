#!/usr/bin/env python3
"""Native DFU comparison, round trip, external upgrade and cross-version CLI. Owns bench.lock."""
import argparse,fcntl,hashlib,json,pathlib,shutil,subprocess,time,traceback
from fixtures import ROOT,Server,VERSIONS
from acceptance import cli,opened,WORK,JAR

def head(world):
    return subprocess.check_output(['git','--git-dir',str(world/'.worldgit'),'rev-parse','HEAD'],text=True).strip()
def hashes(world):
    return json.loads(subprocess.check_output(['java','-Xmx768m','-cp',str(JAR),str(ROOT/'tools/multiversion/SemanticProbe.java'),str(world)],cwd=ROOT,text=True,timeout=300))
def align_fixture_packs(world):
    subprocess.run(['java','-cp',str(JAR),str(ROOT/'tools/multiversion/FixturePacks.java'),str(world)],cwd=ROOT,check=True,timeout=60)
def copy(source,name):
    base=WORK/'extra'/name;shutil.rmtree(base,ignore_errors=True)
    shutil.copytree(ROOT/'.work/worlds'/source/'multiversion/world',base/'world');return base,base/'world'

def pure_roundtrip(source, world, original, check):
    """不開服的嚴格往返，避免原版重存欄位時替反向 codec 修復錯誤。"""
    base=WORK/'extra'/('pure-'+source);shutil.rmtree(base,ignore_errors=True)
    target=base/'world';shutil.copytree(world,target)
    try:
        cli(target,'init','--with-dimensions=all','--template=survival')
        cli(target,'upgrade','--to','26.3')
        status = cli(target, 'status')
        dirty = [row['value']['diff'] for row in status['data']['dimensions'].values()
            if any(row['value']['diff'][key] for key in ['sections', 'entities', 'biomes', 'metadata'])]
        if dirty:
            raise AssertionError(source + ' upgrade must produce a clean world: ' + json.dumps(dirty))
        cli(target,'downgrade','--to',source,'--accept-loss')
        after=hashes(target)
        diff=[k for k in sorted(set(original)|set(after)) if original.get(k)!=after.get(k)]
        check(source+' pure conversion roundtrip preserves every normalized component',
            not diff,differences=diff,original=original,roundtrip=after)
    finally:shutil.rmtree(base,ignore_errors=True)

def main():
    WORK.mkdir(parents=True,exist_ok=True)
    parser=argparse.ArgumentParser(description=__doc__)
    selection=parser.add_mutually_exclusive_group()
    selection.add_argument('--resume-from', choices=VERSIONS[:-1]+['operations'])
    selection.add_argument('--operations-only', action='store_true', help='只執行跨版 CLI 操作情境；不作完整來源矩陣證據')
    args=parser.parse_args()
    artifact=hashlib.sha256(JAR.read_bytes()).hexdigest()
    result={'success':False,'checks':[], 'artifactSHA256':artifact,
        'scope':'operations' if args.operations_only else 'full'};bases=[]
    start=len(VERSIONS)-1 if args.operations_only or args.resume_from=='operations' else VERSIONS.index(args.resume_from) if args.resume_from else 0
    if start and not args.operations_only:
        prior=json.loads((WORK/'extra-results.json').read_text())
        if prior.get('artifactSHA256')!=artifact:
            raise RuntimeError('Resume requires the same verified artifact SHA-256')
        checks=prior['checks'][:4*start]
        if len(checks)!=4*start or not all(row['passed'] for row in checks):
            raise RuntimeError('Missing passing source-version checkpoint')
        result['checks']=checks
    def check(name,condition,**details):
        result['checks'].append(dict(name=name,passed=bool(condition),**details));save()
        if not condition:raise RuntimeError(name)
    def save():(WORK/'extra-results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
    with (ROOT/'.work/bench.lock').open('w') as lock:
        fcntl.flock(lock,fcntl.LOCK_EX)
        save()
        try:
            if start == 0:
                native,nw=copy('1.20.1','native');bases.append(native)
                converted,cw=copy('1.20.1','converted');bases.append(converted)
                original=hashes(cw);pure_roundtrip('1.20.1',cw,original,check)
                cli(cw,'init','--with-dimensions=all','--template=survival')
                cli(cw,'upgrade','--to','26.3');opened('26.3',converted,'extra-converted')
                with Server('26.3',native,WORK/'native-force-upgrade.log',['--forceUpgrade','--eraseCache']) as server:
                    server.send('forceload add 0 0 31 31');time.sleep(3);server.send('save-all flush');time.sleep(2)
                errors=[s for s in server.lines if '/ERROR]' in s and not any(x in s for x in ['public key','yggdrasil'])]
                check('native forceUpgrade no decode errors',not errors,errors=errors)
                a,b=hashes(nw),hashes(cw);diff=[k for k in sorted(set(a)|set(b)) if a.get(k)!=b.get(k)]
                check('native DFU matches WorldGit normalized fixture chunks',not diff,differences=diff,native=a,worldgit=b)
                cli(cw,'commit','-m','native defaults');cli(cw,'downgrade','--to','1.20.1','--accept-loss');opened('1.20.1',converted,'extra-roundtrip');back=hashes(cw)
                diff=[k for k in sorted(set(original)|set(back)) if original.get(k)!=back.get(k)]
                check('1.20.1 -> 26.3 -> 1.20.1 representable chunks unchanged',not diff,differences=diff,original=original,roundtrip=back)
            for source in VERSIONS[max(1,start):-1]:
                freeze=source in ['1.21.11','26.1.2','26.2']
                native_case,native_world=copy(source,'native-'+source)
                converted_case,converted_world=copy(source,'converted-'+source)
                try:
                    # 原版控制組的到期 tick 會重存，未凍結時還會受 save 時序影響。
                    # 測試區域離開出生點，停服前解除強制載入；下次凍結後才載入。
                    # 經真服重存建立共同基準；
                    # 保留全部 ticks 的嚴格雜湊，不以忽略倒數值掩蓋 codec 缺漏。
                    commands=['execute as @e[type=minecraft:sulfur_cube] run data merge entity @s {NoAI:1b,NoGravity:1b,Motion:[0.0d,0.0d,0.0d]}'] if source=='26.2' else []
                    opened(source,native_case,'extra-stable-input-'+source,commands,freeze=freeze)
                    if freeze:
                        opened(source,native_case,'extra-stable-input-repeat-'+source,commands,freeze=True)
                    shutil.rmtree(converted_world)
                    shutil.copytree(native_world,converted_world)
                    original=hashes(converted_world)
                    pure_roundtrip(source,converted_world,original,check)
                    cli(converted_world,'init','--with-dimensions=all','--template=survival')
                    cli(converted_world,'upgrade','--to','26.3')
                    opened('26.3',converted_case,'extra-converted-'+source,freeze=freeze)
                    with Server('26.3',native_case,WORK/('native-force-upgrade-'+source+'.log'),['--forceUpgrade','--eraseCache'],freeze=freeze) as server:
                        server.send('forceload add 0 0 31 31');time.sleep(3);server.send('save-all flush');time.sleep(2)
                    errors=[s for s in server.lines if '/ERROR]' in s and not any(x in s for x in ['public key','yggdrasil'])]
                    check(source+' native forceUpgrade no decode errors',not errors,errors=errors)
                    a,b=hashes(native_world),hashes(converted_world)
                    diff=[k for k in sorted(set(a)|set(b)) if a.get(k)!=b.get(k)]
                    check(source+' native DFU matches WorldGit normalized fixture chunks',not diff,differences=diff,native=a,worldgit=b)
                    cli(converted_world,'commit','-m','native defaults')
                    cli(converted_world,'downgrade','--to',source,'--accept-loss')
                    opened(source,converted_case,'extra-roundtrip-'+source,freeze=freeze)
                    back=hashes(converted_world)
                    diff=[k for k in sorted(set(original)|set(back)) if original.get(k)!=back.get(k)]
                    check(source+' -> 26.3 -> '+source+' representable chunks unchanged',
                        not diff,differences=diff,original=original,roundtrip=back)
                finally:
                    shutil.rmtree(native_case,ignore_errors=True)
                    shutil.rmtree(converted_case,ignore_errors=True)
            external,ew=copy('1.20.4','external');bases.append(external)
            align_fixture_packs(ew)
            cli(ew,'init','--with-dimensions=all','--template=survival');cli(ew,'branch','old')
            cli(ew,'switch','old')
            opened('1.20.4',external,'extra-old-build',['setblock 16 64 1 minecraft:emerald_block'])
            align_fixture_packs(ew)
            cli(ew,'commit','-m','old version building');old=head(ew)
            cli(ew,'switch','main')
            # 26.1+ 原版 FileFixer 掃描全世界並拒絕 Git 的唯讀 object。
            # 外部升級須先把各 repo 移出世界，再依新版 layout 放回；不改 object 權限。
            parking=external/'parked-repos';parking.mkdir()
            repositories=[(ew/'.worldgit',ew/'.worldgit'),
                (ew/'DIM-1/.worldgit',ew/'dimensions/minecraft/the_nether/.worldgit'),
                (ew/'DIM1/.worldgit',ew/'dimensions/minecraft/the_end/.worldgit')]
            parked=[]
            for index,(source,target) in enumerate(repositories):
                if source.exists():
                    saved=parking/str(index);shutil.move(source,saved);parked.append((saved,source,target))
            try:opened('26.3',external,'extra-external',['setblock 17 64 1 minecraft:gold_block'])
            finally:
                for saved,source,target in parked:
                    destination=target if target.parent.exists() else source
                    if destination.exists():raise RuntimeError('external upgrade repo destination already exists')
                    destination.parent.mkdir(parents=True,exist_ok=True);shutil.move(saved,destination)
            rejected=cli(ew,'restore',old,'--dry-run',expected=1)
            check('different DataPacks still reject cross-version restore','DataPacks' in json.dumps(rejected,ensure_ascii=False))
            align_fixture_packs(ew)
            status=cli(ew,'status');check('external upgrade is explicit', '世界已由 1.20.4 升級到 26.3' in json.dumps(status,ensure_ascii=False))
            cli(ew,'commit','-m','external upgrade');new=head(ew);show=cli(ew,'show');check('external upgrade commit records source and target','external-minecraft' in json.dumps(show))
            diff=cli(ew,'diff',old,new);check('cross version diff succeeds',diff['result']['status']=='SUCCESS')
            cli(ew,'restore',old,'--dry-run');cli(ew,'switch','old');cli(ew,'switch','main');cli(ew,'merge','old');check('older commit switch restore and merge use DFU',True)
            with Server('26.3',external,WORK/'cross-version-merged-world.log') as server:
                server.send('forceload add 0 0 31 31');time.sleep(2)
                server.send('execute if block 16 64 1 minecraft:emerald_block run say WG_MERGE_EMERALD')
                server.send('execute if block 17 64 1 minecraft:gold_block run say WG_MERGE_GOLD')
                time.sleep(2)
            check('diverged cross-version merge preserves both independent builds',
                all(any(marker in line for line in server.lines) for marker in ['WG_MERGE_EMERALD','WG_MERGE_GOLD']))
            lossy,lw=copy('26.3','lossy-switch');bases.append(lossy)
            (lw/'.worldgit').mkdir(exist_ok=True)
            (lw/'.worldgit/worldgit.yml').write_text('conversion:\n  replacements:\n    block:minecraft:black_concrete_slab: minecraft:gold_block\n')
            cli(lw,'init','--with-dimensions=all','--template=survival');cli(lw,'branch','newer')
            cli(lw,'downgrade','--to','1.20.1','--accept-loss');old=head(lw)
            before=hashes(lw);preview=cli(lw,'switch','newer','--dry-run')
            check('lossy switch dry-run lists losses without changing head or chunks',
                'minecraft:black_cushion' in json.dumps(preview) and old==head(lw) and before==hashes(lw))
            check('loss preview uses the configured block replacement',
                'minecraft:gold_block' in json.dumps(preview))
            cli(lw,'switch','newer',expected=1)
            check('lossy switch rejects unconfirmed writes',old==head(lw) and before==hashes(lw))
            applied=cli(lw,'switch','newer','--accept-loss');status=cli(lw,'status')
            diffs=[row['value']['diff'] for row in status['data']['dimensions'].values()]
            check('status compares newer head to its accepted downgrade projection',
                all(not diff[key] for diff in diffs for key in ['sections','entities','biomes','metadata']),diffs=diffs)
            cli(lw,'switch','main');check('switch back from newer head uses projected dirty check',True)
            operation=applied['result']['operationId'];audit=cli(lw,'show','--operation',operation)
            check('conversion loss audit survives subsequent switch','minecraft:black_cushion' in json.dumps(audit))
            preview=cli(lw,'export',str(WORK/'extra-preview.zip'),'--rev','minecraft:overworld=newer','--to','1.20.1','--dry-run')
            check('lossy export dry-run lists report and creates no zip','minecraft:black_cushion' in json.dumps(preview) and not (WORK/'extra-preview.zip').exists())
            result['success']=True;save()
        except Exception as e:
            result['error']=str(e);save();traceback.print_exc();raise
        finally:
            if result['success']:
                for base in bases:shutil.rmtree(base,ignore_errors=True)
            else:
                result['retainedCases']=[str(base) for base in bases if base.exists()]
                save()

if __name__=='__main__':main()
