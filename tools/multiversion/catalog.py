#!/usr/bin/env python3
"""從原版 data generator 的 registry／block 報告產生資料檔與 tag 表。"""
import argparse,io,json,pathlib,zipfile,yaml
ROOT=pathlib.Path(__file__).resolve().parents[2];dest=ROOT/'core/src/main/resources/org/worldgit/versions'
versions=yaml.safe_load((dest/'catalog.yml').read_text())['versions']
p=argparse.ArgumentParser(description=__doc__);p.add_argument('versions',nargs='*');a=p.parse_args()
if a.versions:
 unknown=set(a.versions)-{v['id'] for v in versions}
 if unknown:p.error('unknown versions: '+', '.join(sorted(unknown)))
 versions=[v for v in versions if v['id'] in a.versions]
component_items=json.loads((ROOT/'.work/versions/1.20.6/generated/reports/items.json').read_text())
damageable={id for id,item in component_items.items()
           if any(c['type']=='minecraft:max_damage' for c in item.get('components',[]))}
for v in versions:
 d=ROOT/'.work/versions'/v['id'];r=json.loads((d/'generated/reports/registries.json').read_text());blocks=json.loads((d/'generated/reports/blocks.json').read_text())
 data={'blocks':{k:{'properties':x.get('properties',{}),'default':next((s.get('properties',{}) for s in x['states'] if s.get('default')), {})} for k,x in blocks.items()}}
 data['effect-ids']={k:x['protocol_id'] for k,x in r.get('minecraft:mob_effect',{}).get('entries',{}).items()}
 data['gamerules']=sorted(json.loads((d/'generated/reports/commands.json').read_text())['children']['gamerule']['children'])
 for kind,reg in [('items','item'),('entities','entity_type'),('block-entities','block_entity_type'),('components','data_component_type'),('attributes','attribute'),('effects','mob_effect'),('enchantments','enchantment'),('custom-stats','custom_stat')]:data[kind]=sorted(r.get('minecraft:'+reg,{}).get('entries',{}))
 with zipfile.ZipFile(d/'server.jar') as bundle:
  inner=next(n for n in bundle.namelist() if n.startswith('META-INF/versions/') and n.endswith('.jar'))
  content=bundle.read(inner)
 with zipfile.ZipFile(io.BytesIO(content)) as z:
  data['advancements']=sorted(set('minecraft:'+n[len(prefix):-5] for prefix in ['data/minecraft/advancements/','data/minecraft/advancement/'] for n in z.namelist() if n.startswith(prefix) and n.endswith('.json')))
  data['structures']=sorted('minecraft:'+n[len('data/minecraft/worldgen/structure/'):-5] for n in z.namelist() if n.startswith('data/minecraft/worldgen/structure/') and n.endswith('.json'))
  data['biomes']=sorted('minecraft:'+n[len('data/minecraft/worldgen/biome/'):-5] for n in z.namelist() if n.startswith('data/minecraft/worldgen/biome/') and n.endswith('.json'))
  for kind,folder in [('enchantments','enchantment')]:
   dynamic=['minecraft:'+n[len('data/minecraft/'+folder+'/'):-5] for n in z.namelist() if n.startswith('data/minecraft/'+folder+'/') and n.endswith('.json')]
   if dynamic:data[kind]=sorted(dynamic)
  tags={}
  for name in z.namelist():
   for prefix in ['data/minecraft/tags/entity_types/','data/minecraft/tags/entity_type/']:
    if name.startswith(prefix) and name.endswith('.json'):tags['minecraft:'+name[len(prefix):-5]]=json.loads(z.read(name)).get('values',[])
 # Data generator 包含未啟用的實驗內容；正式支援只接受非實驗 vanilla registry。
 if v['id'] in ['1.20.1','1.20.4','1.20.6','1.21.1']:data['items']=[x for x in data['items'] if x!='minecraft:bundle']
 if v['id'] in ['1.20.4','1.20.6']:
  old=json.loads((ROOT/'.work/versions/1.20.1/generated/reports/registries.json').read_text())
  stableblocks=set(old['minecraft:block']['entries'])-{'minecraft:grass'}|{'minecraft:short_grass'}
  data['blocks']={k:x for k,x in data['blocks'].items() if k in stableblocks}
  stableitems=set(old['minecraft:item']['entries'])-{'minecraft:grass'}|{'minecraft:short_grass'}
  if v['id']=='1.20.6':stableitems=stableitems-{'minecraft:scute'}|{'minecraft:turtle_scute','minecraft:armadillo_scute','minecraft:wolf_armor','minecraft:armadillo_spawn_egg'}
  data['items']=sorted(set(data['items'])&stableitems)
  data['entities']=sorted(set(data['entities'])&(set(old['minecraft:entity_type']['entries'])|({'minecraft:armadillo'} if v['id']=='1.20.6' else set())))
  data['block-entities']=sorted(set(data['block-entities'])&set(old['minecraft:block_entity_type']['entries']))
 if v['data-version']<=3700:data['damageable-items']=sorted(damageable&set(data['items']))
 (dest/(str(v['data-version'])+'.yml')).write_text(yaml.safe_dump(data,sort_keys=True,allow_unicode=True))
 tagdest=ROOT/'core/src/main/resources/org/worldgit/entity-tags'/(str(v['data-version'])+'.yml')
 # 既有兩份不重寫，避免無關格式變動。
 if not tagdest.exists():tagdest.write_text(yaml.safe_dump(tags,sort_keys=True))
 print(v['id'],len(blocks),len(data['items']),len(data['biomes']))
