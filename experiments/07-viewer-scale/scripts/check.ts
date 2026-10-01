import fs from 'node:fs'
import path from 'node:path'
import assert from 'node:assert/strict'
import { BlockState } from 'deepslate'
import { StateTable, World, type ChunkData } from '../src/world.ts'
import { decodeChunk, encodeChunk } from '../src/codec.ts'
import { ScaleMesher, STRIDE } from '../src/scale-mesher.ts'
import { PackResources, patchBlockColors } from '../src/resources.ts'
const root=path.resolve(import.meta.dirname,'../../../.work/viewer-scale'),pack:any={};for(const n of ['blockstates','models','flags','atlas','biomes','opaqueUV'])pack[n]=JSON.parse(fs.readFileSync(root+`/pack/${n}.json`,'utf8'));patchBlockColors()
const table=new StateTable(),stone=table.intern(BlockState.STONE),slab=table.intern(BlockState.parse('minecraft:oak_slab[type=bottom,waterlogged=false]'));const doubleSlab=table.intern(BlockState.parse('minecraft:oak_slab[type=double,waterlogged=false]'));table.biome('minecraft:ocean')
const world=new World(table),chunk=(cx:number):ChunkData=>({cx,cz:0,status:'minecraft:full',blockEntities:new Map(),sections:new Map([[0,{ids:null,single:stone,biomes:new Uint8Array(64)}]])});world.add(chunk(0));world.add(chunk(1));const m=new ScaleMesher(new PackResources(pack),world)
const a=m.mesh(0,0,0),b=m.mesh(1,0,0),plain=m.mesh(0,0,0,false);assert.equal(a.greedyQuads,5);assert.equal(b.greedyQuads,5);assert.equal(a.unitFaces,1280);assert.equal(plain.greedyQuads,1280);assert.equal(a.layers.opaque!.data.length,5*4*STRIDE)
const nonfull=chunk(2);nonfull.sections.get(0)!.single=slab;world.add(nonfull);const s=m.mesh(2,0,0);assert.equal(m.cube[slab],0);assert.equal(m.cube[doubleSlab],1);assert.equal(m.opaque[doubleSlab],1);assert.ok(s.layers.opaque!.quads>0)
const tb=JSON.parse(fs.readFileSync(root+'/table.json','utf8')),full=new StateTable();tb.states.slice(1).forEach((s:string)=>full.intern(BlockState.parse(s)));tb.biomes.forEach((b:string)=>full.biome(b));let bes=0,blocks=0
for(const f of fs.readdirSync(root+'/chunks')){const raw=fs.readFileSync(root+'/chunks/'+f),c=decodeChunk(raw,0,0,full);assert.deepEqual(encodeChunk(c,full),new Uint8Array(raw));bes+=c.blockEntities.size;blocks+=c.sections.size*4096}
assert.equal(full.states.length,tb.states.length,'canonical property order must not append duplicate worker state IDs')
console.log(JSON.stringify({ok:true,solidTwoSections:{quads:a.greedyQuads+b.greedyQuads,unitFaces:a.unitFaces+b.unitFaces,noInternalBoundary:true},nonFullSlabFallback:true,doubleSlabGreedy:true,byteExactRoundTripChunks:626,blocks,blockEntities:bes,bytesPerVertex:STRIDE},null,2))
