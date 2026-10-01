// 02 的未壓縮 section v1；chunk 封套與 biome sidecar 是本實驗的 transport。
import { BlockState, NbtCompound, NbtFile, NbtList, NbtType, type NbtTag } from 'deepslate'
import { StateTable, type ChunkData, type SectionData } from './world.ts'
export class Writer {
  a: number[] = []
  b(v: number) { this.a.push(v & 255) }
  vi(v: number) { while(v > 127) { this.b((v & 127) | 128); v >>>= 7 }; this.b(v) }
  str(s: string) { this.bytes(new TextEncoder().encode(s)) }
  bytes(a: Uint8Array) { this.vi(a.length); this.raw(a) }
  raw(a: Uint8Array) { for(const b of a) this.b(b) }
  done() { return Uint8Array.from(this.a) }
}
export class Reader {
  p = 0
  constructor(readonly a: Uint8Array) {}
  b() { if(this.p >= this.a.length) throw new Error('truncated codec'); return this.a[this.p++] }
  vi() { let v=0,s=0,b; do {b=this.b(); v|=(b&127)<<s;s+=7;if(s>35)throw new Error('bad varint')}while(b&128);return v>>>0 }
  raw(n: number) { const a=this.a.subarray(this.p,this.p+n);if(a.length!==n)throw new Error('truncated');this.p+=n;return a }
  bytes() { return this.raw(this.vi()) }
  str() { return new TextDecoder().decode(this.bytes()) }
}
const ignored: Record<string,string[]> = {furnace:['lit_time_remaining','cooking_time_spent'],blast_furnace:['lit_time_remaining','cooking_time_spent'],smoker:['lit_time_remaining','cooking_time_spent'],mob_spawner:['Delay'],jukebox:['ticks_since_song_started'],command_block:['LastExecution','SuccessCount'],brewing_stand:['BrewTime'],campfire:['CookingTimes'],hopper:['TransferCooldown']}
function sorted(tag: NbtTag): NbtTag {
  if(tag instanceof NbtCompound) return new NbtCompound(new Map([...tag.keys()].sort().map(k=>[k,sorted(tag.get(k)!)])))
  if(tag instanceof NbtList) return new NbtList(tag.getItems().map(sorted),tag.getType())
  return tag
}
export function encodeSection(s: SectionData, sy: number, c: ChunkData, table: StateTable) {
  const w = new Writer();w.b(1)
  const pal: number[] = [], seen = new Map<number,number>(), ids=new Uint16Array(4096)
  for(let i=0;i<4096;i++){const id=s.ids?s.ids[i]:s.single;let p=seen.get(id);if(p===undefined){p=pal.length;pal.push(id);seen.set(id,p)};ids[i]=p}
  w.vi(pal.length)
  for(const id of pal){const st=table.states[id];w.str(st.getName().toString());const props=Object.entries(st.getProperties()).sort(([a],[b])=>a.localeCompare(b));w.vi(props.length);for(const[k,v]of props){w.str(k);w.str(v)}}
  const bits=Math.ceil(Math.log2(pal.length));w.b(bits);const raw=new Uint8Array(512*bits)
  for(let i=0;i<4096;i++){const bp=i*bits,b=bp>>3,sh=bp&7,v=ids[i]<<sh;raw[b]|=v&255;if(sh+bits>8)raw[b+1]|=(v>>8)&255;if(sh+bits>16)raw[b+2]|=v>>16}
  w.raw(raw)
  const bes=[...c.blockEntities.entries()].filter(([p])=>((p>>8)-64)>>4===sy).sort(([a],[b])=>a-b)
  w.vi(bes.length)
  for(const[pos,be]of bes){const ignore=ignored[be.getString('id').replace('minecraft:','')]??[];const n=new NbtCompound();for(const k of be.keys())if(!['x','y','z',...ignore].includes(k)&&! /^(Paper|Bukkit|Spigot)\./.test(k))n.set(k,be.get(k)!);const file=NbtFile.create({compression:'none'});file.root=sorted(n) as NbtCompound;w.vi(pos&4095);w.bytes(file.write())}
  return w.done()
}
export function decodeSection(raw: Uint8Array, sy: number, c: ChunkData, table: StateTable) {
  const r=new Reader(raw);if(r.b()!==1)throw new Error('section version');const n=r.vi(),pal:number[]=[]
  for(let i=0;i<n;i++){const name=r.str(),np=r.vi(),p:Record<string,string>={};for(let j=0;j<np;j++){const k=r.str();p[k]=r.str()};pal.push(table.intern(new BlockState(name,p)))}
  const bits=r.b(),data=r.raw(512*bits),ids=bits?new Uint16Array(4096):null,mask=(1<<bits)-1
  if(ids)for(let i=0;i<4096;i++){const bp=i*bits,b=bp>>3;ids[i]=pal[((data[b]|(data[b+1]??0)<<8|(data[b+2]??0)<<16) >>>(bp&7))&mask]}
  const nb=r.vi();for(let i=0;i<nb;i++){const pos=r.vi(),be=NbtFile.read(r.bytes(),{compression:'none'}).root;c.blockEntities.set(((sy*16+64)<<8)|pos,be)}
  if(r.p!==raw.length)throw new Error('trailing section bytes')
  return {ids,single:pal[0],biomes:new Uint8Array(64)} as SectionData
}
export function encodeChunk(c:ChunkData,table:StateTable){const w=new Writer();w.raw(new Uint8Array([87,71,67,55]));const sections=[...c.sections].filter(([,s])=>s.ids||s.single!==0);w.vi(sections.length);for(const[sy,s]of sections){w.vi(sy+64);w.bytes(encodeSection(s,sy,c,table));w.raw(s.biomes)}return w.done()}
export function decodeChunk(a:Uint8Array,cx:number,cz:number,table:StateTable){const r=new Reader(a);if(new TextDecoder().decode(r.raw(4))!=='WGC7')throw new Error('chunk magic');const c:ChunkData={cx,cz,sections:new Map(),blockEntities:new Map(),status:'minecraft:full'},n=r.vi();for(let i=0;i<n;i++){const sy=r.vi()-64,s=decodeSection(r.bytes(),sy,c,table);s.biomes=r.raw(64).slice();c.sections.set(sy,s)}if(r.p!==a.length)throw new Error('trailing chunk');return c}
