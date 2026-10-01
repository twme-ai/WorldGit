import fs from 'node:fs'
import { BlockState } from 'deepslate'
import { StateTable, World } from '../src/world.ts'
import { decodeChunk } from '../src/codec.ts'
import { ScaleMesher } from '../src/scale-mesher.ts'
import { Rects } from '../src/packed.ts'
import { PackResources, patchBlockColors } from '../src/resources.ts'
const root='/root/projects/ProjectCollection/WorldGit/.work/viewer-scale',pack:any={};for(const n of ['blockstates','models','flags','atlas','biomes','opaqueUV'])pack[n]=JSON.parse(fs.readFileSync(root+`/pack/${n}.json`,'utf8'));patchBlockColors();Rects.init(pack.atlas.uv,pack.atlas.size[0])
const tb=JSON.parse(fs.readFileSync(root+'/table.json','utf8')),t=new StateTable();tb.states.slice(1).forEach((s:string)=>t.intern(BlockState.parse(s)));tb.biomes.forEach((b:string)=>t.biome(b))
const ds=JSON.parse(fs.readFileSync(root+'/dataset-baseline.json','utf8')),world=new World(t),m=new ScaleMesher(new PackResources(pack),world)
const es=ds.chunks.slice(0,60);for(const e of es)world.add({...decodeChunk(fs.readFileSync(root+`/chunks/${e.src}.bin`),0,0,t),cx:e.cx,cz:e.cz})
for(const e of es)for(const sy of world.chunk(e.cx,e.cz)!.sections.keys())m.mesh(e.cx,sy,e.cz)
console.log('subRects',Rects.sub.size,'unresolved',Rects.unresolved)
