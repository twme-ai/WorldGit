import { BlockState } from 'deepslate'
import { StateTable, World } from './world.ts'
import { decodeChunk } from './codec.ts'
import { PackResources, patchBlockColors } from './resources.ts'
import { Rects } from './packed.ts'
import { ScaleMesher, type PackedSection } from './scale-mesher.ts'
import type { Entry } from './lod.ts'
let world:World,mesher:ScaleMesher;const cache=new Map<number,Uint8Array>();const decoded=new Map<number,ReturnType<typeof decodeChunk>>();let inflight=new Map<number,Promise<Uint8Array>>();let maxDecoded=0
async function payload(src:number){let a=cache.get(src);if(a)return a;let p=inflight.get(src);if(!p){p=fetch(`/work/chunks/${src}.bin`).then(async r=>{if(!r.ok)throw new Error('chunk fetch '+r.status);const a=new Uint8Array(await r.arrayBuffer());cache.set(src,a);while(cache.size>64)cache.delete(cache.keys().next().value!);inflight.delete(src);return a});inflight.set(src,p)}return p}
async function chunk(e:Entry){let c=decoded.get(e.src);if(!c){c=decodeChunk(await payload(e.src),0,0,world.table);decoded.set(e.src,c);while(decoded.size>64)decoded.delete(decoded.keys().next().value!)}return{...c,cx:e.cx,cz:e.cz}}
async function init(){const t=performance.now();const names=['blockstates','models','flags','atlas','biomes','opaqueUV'];const [tb,...parts]=await Promise.all([fetch('/work/table.json').then(r=>r.json()),...names.map(n=>fetch(`/work/pack/${n}.json`).then(r=>r.json()))]);const table=new StateTable();tb.states.slice(1).forEach((s:string)=>table.intern(BlockState.parse(s)));tb.biomes.forEach((b:string)=>table.biome(b));world=new World(table);Rects.init(parts[names.indexOf('atlas')].uv,parts[names.indexOf('atlas')].size[0]);patchBlockColors();mesher=new ScaleMesher(new PackResources(Object.fromEntries(names.map((n,i)=>[n,parts[i]]))as any),world);return{ms:performance.now()-t,cubes:mesher.cube.reduce((a,b)=>a+b,0)}}
async function process(entries:Entry[],neighbors:Entry[],render:boolean,greedy:boolean){
 const t=performance.now();const needed=new Map<string,Entry>();for(const e of [...entries,...neighbors])needed.set(`${e.cx},${e.cz}`,e)
 world.chunks.clear();await Promise.all([...needed.values()].map(async e=>world.add(await chunk(e))));maxDecoded=Math.max(maxDecoded,decoded.size)
 let bytes=0,quads=0,unitFaces=0,greedyQuads=0,meshMs=0;const meshes:PackedSection[]=[],times:number[]=[]
 for(const e of entries){const start=performance.now(),c=world.chunk(e.cx,e.cz)!;for(const sy of c.sections.keys()){
 const m=mesher.mesh(e.cx,sy,e.cz,greedy);meshMs+=m.ms;unitFaces+=m.unitFaces;greedyQuads+=m.greedyQuads;for(const l of Object.values(m.layers)){bytes+=l!.data.byteLength;quads+=l!.quads};if(render&&Object.keys(m.layers).length)meshes.push(m)
 }times.push(performance.now()-start)}
 world.chunks.clear();const decodedBytes=[...decoded.values()].reduce((a,c)=>a+[...c.sections.values()].reduce((a,s)=>a+(s.ids?.byteLength??0)+s.biomes.byteLength,0),0)
 return{meshes,stats:{bytes,triangles:quads*2,unitFaces,greedyQuads,meshMs,times,wallMs:performance.now()-t,payloadBytes:[...cache.values()].reduce((n,a)=>n+a.length,0),decodedBytes,cacheEntries:decoded.size,maxDecoded,cacheHits:mesher.base.stats.cacheHits,cacheMisses:mesher.base.stats.cacheMisses,rectSub:Rects.sub.size,rectUnresolved:Rects.unresolved}}
}
self.onmessage=async({data})=>{try{const result=data.op==='init'?await init():await process(data.entries,data.neighbors,data.render,data.greedy!==false);const transfer:Transferable[]=[];if('meshes'in result)for(const m of result.meshes)for(const l of Object.values(m.layers))transfer.push(l!.data.buffer);self.postMessage({id:data.id,result},transfer)}catch(e){self.postMessage({id:data.id,error:String(e),stack:(e as Error).stack})}}
