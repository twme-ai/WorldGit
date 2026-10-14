#!/usr/bin/env python3
"""Fabric 1.20.1／1.20.4 原生 tick、setPosRaw、terrain／entity／POI barrier；自行取 bench.lock。"""
import argparse, hashlib, json, shutil, time, traceback
from pathlib import Path
from multiversion import Environment, ROOT, phase5, harness

def run(version, mode):
    output=ROOT/'.work/fabric-native'/f'{mode}-{version}-{time.time_ns()}'
    output.mkdir(parents=True)
    result={'version':version,'mode':mode,'success':False,'checks':[]}
    environment=None
    def save(): (output/'result.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
    try:
        with harness.BenchLock():
            artifact=output/version;artifact.mkdir();phase5.build(version,artifact)
            result['artifacts']={p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in artifact.glob('*.jar')}
            environment=Environment(mode,version,output/'environment',artifact)
            for command,expected in [('locks','WGLOCKS'),('probe-pending','WGPENDING passed'),
                    ('probe-adversary','WGPROBE adversary passed'),('probe-storage','WGPROBE storage passed')]:
                source=environment.client.process.lines if environment.client else environment.server.log_lines
                mark=len(source)
                environment.native('wg test '+command)
                deadline=time.monotonic()+180
                while time.monotonic()<deadline:
                    rows=source[mark:]
                    if any('WGTESTFAIL' in line or 'Exception' in line for line in rows):
                        raise AssertionError(command+' '+str(rows)[-2500:])
                    found=next((line for line in rows if expected in line),None)
                    if found: break
                    time.sleep(.1)
                else: raise TimeoutError(command+' '+str(source[mark:])[-2500:])
                result['checks'].append({'name':command,'ok':True,'evidence':found});save()
                print('PASS '+command+' '+found,flush=True)
            result['success']=True
    except BaseException:
        result['error']=traceback.format_exc();print(result['error'],flush=True)
    finally:
        if environment:environment.cleanup()
        for file in output.rglob('*.jar'):file.unlink()
        save()
    print(output/'result.json',flush=True)
    return result['success']

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version',choices=['1.20.1','1.20.4'])
    parser.add_argument('--mode',choices=['dedicated','single','all'],default='all')
    args=parser.parse_args()
    for mode in (['dedicated','single'] if args.mode=='all' else [args.mode]):
        if not run(args.version,mode):raise SystemExit(1)
