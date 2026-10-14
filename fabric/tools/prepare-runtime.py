#!/usr/bin/env python3
"""只準備 Fabric dedicated 的官方 launcher／libraries／API，不啟動 Minecraft。"""
import argparse,hashlib,json,os,subprocess,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
API={'1.20.1':'0.92.12+1.20.1','1.20.4':'0.97.3+1.20.4','1.21.11':'0.141.6+1.21.11','26.2':'0.161.0+26.2'}
def download(url,path):
    path.parent.mkdir(parents=True,exist_ok=True)
    if not path.exists():
        with urllib.request.urlopen(url,timeout=120) as response:path.write_bytes(response.read())
    return {'url':url,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'bytes':path.stat().st_size}
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('version',choices=API);args=parser.parse_args()
    version=args.version;target=ROOT/'.work/fabric-srv'/version;target.mkdir(parents=True,exist_ok=True)
    installer=ROOT/'.work/versions/fabric-installer-1.1.2.jar'
    proof={'installer':download('https://maven.fabricmc.net/net/fabricmc/fabric-installer/1.1.2/fabric-installer-1.1.2.jar',installer)}
    if not (target/'fabric-server-launch.jar').exists():
        subprocess.run(['/usr/lib/jvm/java-25-openjdk-amd64/bin/java','-jar',str(installer),'server','-mcversion',version,'-loader','0.19.5','-dir',str(target),'-downloadMinecraft'],check=True)
    v=API[version];proof['api']=download(f'https://maven.fabricmc.net/net/fabricmc/fabric-api/fabric-api/{v}/fabric-api-{v}.jar',target/'fabric-api.jar')
    proof['launcher_sha256']=hashlib.sha256((target/'fabric-server-launch.jar').read_bytes()).hexdigest()
    (target/'runtime-proof.json').write_text(json.dumps(proof,indent=2)+'\n')
if __name__=='__main__':main()
