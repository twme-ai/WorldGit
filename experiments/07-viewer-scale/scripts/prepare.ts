import fs from 'node:fs'
import path from 'node:path'
import { createHash } from 'node:crypto'
import { PNG } from 'pngjs'
import { parseRegion, StateTable, World } from '../src/world.ts'
import { encodeChunk, encodeSection } from '../src/codec.ts'
const root=path.resolve(import.meta.dirname,'../../..'),out=path.join(root,'.work/viewer-scale'),source=process.argv[2]??path.join(root,'.work/worlds/26.2/baseline/world/dimensions/minecraft/overworld/region')
fs.mkdirSync(out+'/chunks',{recursive:true})
const t=performance.now(),table=new StateTable(),chunks=[],sectionHashes=new Set<string>();let nsec=0,sectionBytes=0,skipped=0
for(const f of fs.readdirSync(source).filter(f=>/^r\.-?\d+\.-?\d+\.mca$/.test(f)).sort()){const[,x,z]=f.split('.');const r=parseRegion(fs.readFileSync(path.join(source,f)),+x,+z,table);chunks.push(...r.chunks);skipped+=r.stats.skippedNotFull}
chunks.sort((a,b)=>a.cz-b.cz||a.cx-b.cx)
const world=new World(table);chunks.forEach(c=>world.add(c))
const minx=Math.min(...chunks.map(c=>c.cx)),minz=Math.min(...chunks.map(c=>c.cz)),width=Math.max(...chunks.map(c=>c.cx))-minx+1,depth=Math.max(...chunks.map(c=>c.cz))-minz+1
const tops:any[]=[];let chunkBytes=0
for(const [i,c]of chunks.entries()){
 const raw=encodeChunk(c,table);fs.writeFileSync(out+`/chunks/${i}.bin`,raw);chunkBytes+=raw.length
 for(const[sy,s]of c.sections)if(s.ids||s.single!==0){const raw=encodeSection(s,sy,c,table);nsec++;sectionBytes+=raw.length;sectionHashes.add(createHash('sha256').update(raw).digest('hex'))}
 const h:number[]=[],state:number[]=[],biome:number[]=[]
 for(let z=0;z<16;z++)for(let x=0;x<16;x++){let y=319;for(;y>=-64;y--){const id=world.getState(c.cx*16+x,y,c.cz*16+z);if(id>=0&&!['air','cave_air','void_air'].includes(table.states[id].getName().path))break};h.push(y+1);const id=world.getState(c.cx*16+x,y,c.cz*16+z);state.push(Math.max(0,id));biome.push(c.sections.get(y>>4)?.biomes[((y&15)>>2)*16+(z>>2)*4+(x>>2)]??0)}
 tops.push({h,state,biome})
}
const atlas=JSON.parse(fs.readFileSync(out+'/pack/atlas.json','utf8')),png=PNG.sync.read(fs.readFileSync(out+'/pack/atlas.png'));const colors:Record<string,number[]>={}
for(const[k,uv]of Object.entries(atlas.uv)as[string,number[]][]){let rr=0,g=0,b=0,n=0;for(let y=Math.round(uv[1]*png.height);y<Math.round(uv[3]*png.height);y++)for(let x=Math.round(uv[0]*png.width);x<Math.round(uv[2]*png.width);x++){const p=(y*png.width+x)*4;if(png.data[p+3]<128)continue;rr+=png.data[p];g+=png.data[p+1];b+=png.data[p+2];n++}colors[k]=n?[rr/n,g/n,b/n]:[110,140,90]}
fs.writeFileSync(out+'/colors.json',JSON.stringify(colors));fs.writeFileSync(out+'/tops.json',JSON.stringify(tops));fs.writeFileSync(out+'/table.json',JSON.stringify({states:table.states.map(s=>s.toString()),biomes:table.biomeNames}))
const base=chunks.map((c,src)=>({cx:c.cx,cz:c.cz,src}))
const datasets:any={baseline:base};const tileCols=4
for(const n of [2000,5000,10000]){const a=[];for(let i=0;i<n;i++){const src=i%base.length,tile=Math.floor(i/base.length),tx=tile%tileCols,tz=Math.floor(tile/tileCols);a.push({cx:base[src].cx+tx*width,cz:base[src].cz+tz*depth,src})};datasets[n]=a}
const window=base.filter(c=>c.cx>=-8&&c.cx<8&&c.cz>=-8&&c.cz<8);if(window.length!==256)throw new Error(`16x16 window only ${window.length}`);datasets.window256=window
for(const[k,ch]of Object.entries(datasets))fs.writeFileSync(out+`/dataset-${k}.json`,JSON.stringify({name:k,chunks:ch}))
const stats={date:new Date().toISOString(),source,fullChunks:chunks.length,skippedNotFull:skipped,states:table.states.length,biomes:table.biomeNames,nsec,uniqueSections:sectionHashes.size,sectionDedup:1-sectionHashes.size/nsec,sectionBytes,chunkBytes,bounds:{minx,minz,width,depth},transport:'WGC7 chunk envelope + raw core section v1 + 64 biome IDs; no zstd',conversionMs:performance.now()-t,datasets:Object.fromEntries(Object.entries(datasets).map(([k,a]:any)=>[k,{chunks:a.length,uniqueChunkPayloads:new Set(a.map((c:any)=>c.src)).size,payloadDedup:1-new Set(a.map((c:any)=>c.src)).size/a.length}]))}
fs.writeFileSync(out+'/data-stats.json',JSON.stringify(stats,null,2));console.log(JSON.stringify(stats,null,2))
