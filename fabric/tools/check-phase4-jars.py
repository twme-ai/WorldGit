#!/usr/bin/env python3
"""檢查正式 jar-in-jar 的 Jackson、remote classes 與測試 fixture 排除。"""
import io,json,zipfile,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
def check(path):
    with zipfile.ZipFile(path) as z:
        manifest=json.loads(z.read('fabric.mod.json'));nested=[v['file'] for v in manifest['jars']]
        classes=set();jackson=[]
        for n in nested:
            with zipfile.ZipFile(io.BytesIO(z.read(n))) as jar:
                classes.update(jar.namelist())
                if 'jackson-' in n:jackson.append(n)
        for cls in ['com/fasterxml/jackson/core/JsonFactory.class','com/fasterxml/jackson/databind/ObjectMapper.class','com/fasterxml/jackson/annotation/JsonProperty.class','org/worldgit/platform/remote/HubClient.class','org/worldgit/platform/remote/WebhookReceiver.class']:
            assert cls in classes,(path,cls)
        assert len(jackson)==3,(path,jackson)
        assert not any('gametest/' in n or 'DedicatedServerFixture' in n for n in z.namelist()),path
        return {'jar':str(path.relative_to(ROOT)),'jackson':jackson,'ok':True}
if __name__=='__main__':
    paths=[Path(p) for p in sys.argv[1:]] if len(sys.argv)>1 else [ROOT/f'fabric/{p}/build/libs/worldgit-fabric-{v}-0.1.0-SNAPSHOT.jar' for p,v in [('mc1_21_11','1.21.11'),('mc26_2','26.2')]]
    print(json.dumps([check(p) for p in paths],indent=2))
