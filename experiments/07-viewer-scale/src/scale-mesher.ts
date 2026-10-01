import { Mesher, NF, type SectionMesh, type Layer } from './base-mesher.ts'
import { PackResources } from './resources.ts'
import { World } from './world.ts'
import { STRIDE, PackedBuilder, type PackedSection } from './packed.ts'
export { STRIDE, PackedBuilder, type PackedSection } from './packed.ts'
export function compress(m:SectionMesh):PackedSection{const layers:PackedSection['layers']={};for(const[k,l]of Object.entries(m.layers)){const b=new PackedBuilder();for(let i=0;i<l!.data.length;i+=NF){const a=l!.data;b.vertex(Array.from(a.subarray(i,i+3)),Array.from(a.subarray(i+3,i+5)),Array.from(a.subarray(i+5,i+9)),Array.from(a.subarray(i+9,i+12)),a[i+12],a[i+13],a[i+14])}layers[k as Layer]=b.finish()}return{cx:m.cx,sy:m.sy,cz:m.cz,layers,ms:m.ms,unitFaces:0,greedyQuads:0}}
// dir: up/down/north/south/east/west; horizontal axes within that face.
const AXES=[[1,0,2,1],[1,0,2,-1],[2,0,1,-1],[2,0,1,1],[0,2,1,1],[0,2,1,-1]]
const OFF=[324,-324,-18,18,1,-1]
interface Face {d:Float32Array;tint:number;texAxis:number}
export class ScaleMesher {
 base:Mesher;opaque:Uint8Array;cube:Uint8Array;faces:(Face[]|null)[]=[];pa=new Int16Array(5832);mask=new Int32Array(256)
 constructor(readonly res:PackResources,readonly world:World){
 const uvKey=(uv:number[])=>uv.map(v=>Math.round(v*res.pack.atlas.size[0])).join(',');const opaqueUV=new Set((res.pack.opaqueUV??[]).map(uvKey))
 this.base=new Mesher(res,world);const n=world.table.states.length;this.opaque=new Uint8Array(n);this.cube=new Uint8Array(n)
 for(let id=0;id<n;id++){
 const inf=this.base.st(id);this.opaque[id]=+inf.opaque
 if(inf.air||inf.water||inf.hasWater||inf.banner)continue
 const p=this.base.quadsFor(id,0,0,undefined,'').solid
 if(!p||p.nq!==6)continue
 const faces:Face[]=[];let ok=true,opaque=true
 for(let q=0;q<6;q++){
 const d=p.data.slice(q*52,q*52+52),pos=[0,1,2].map(k=>[d[k],d[13+k],d[26+k],d[39+k]])
 let axis=pos.findIndex(v=>v.every(x=>Math.abs(x-v[0])<1e-5));if(axis<0){ok=false;break}
 const plane=pos[axis][0];if(plane!==0&&plane!==1||pos.some((v,k)=>k!==axis&&(Math.min(...v)!==0||Math.max(...v)!==1))){ok=false;break}
 const dir=axis===1?(plane?0:1):axis===2?(plane?3:2):(plane?4:5)
 const ua=AXES[dir][1];let texAxis=ua
 for(let v=1;v<4;v++)if(Math.abs(d[v*13+3]-d[3])>1e-7&&Math.abs(d[v*13+4]-d[4])<1e-7){texAxis=[0,1,2].find(k=>Math.abs(d[v*13+k]-d[k])>0.5)??ua;break}
 if(res.pack.opaqueUV&&!opaqueUV.has(uvKey(Array.from(d.subarray(5,9)))))opaque=false
 faces[dir]={d,tint:p.tints[q],texAxis}
 }
 if(ok&&faces.filter(Boolean).length===6){this.faces[id]=faces;this.cube[id]=1;if(res.pack.opaqueUV){this.opaque[id]=+opaque;inf.opaque=opaque};this.base.skipStates.add(id)}
 }
 }
 mesh(cx:number,sy:number,cz:number,greedy=true):PackedSection{
 const t=performance.now(),s=this.world.section(cx,sy,cz)!;if(!s)return{cx,sy,cz,layers:{},ms:0,unitFaces:0,greedyQuads:0}
 this.base.fill(this.world,cx,sy,cz,this.pa)
 // 非完整方塊沿用 04 模型快取；沒有此類方塊時跳過 fallback 第二次走訪。
 const fallback=s.ids?s.ids.some(id=>id>0&&!this.cube[id]&&!this.base.st(id).air):s.single>0&&!this.cube[s.single]&&!this.base.st(s.single).air
 const out=fallback?compress(this.base.meshSection(cx,sy,cz)):{cx,sy,cz,layers:{},ms:0,unitFaces:0,greedyQuads:0}as PackedSection
 const builds={opaque:new PackedBuilder(),trans:new PackedBuilder()},a=this.pa;let unitFaces=0,greedyQuads=0
 for(let dir=0;dir<6;dir++){
 const[axis,ua,va,sign]=AXES[dir]
 for(let plane=0;plane<16;plane++){
 const mask=this.mask;mask.fill(0)
 for(let v=0;v<16;v++)for(let u=0;u<16;u++){
 const x=axis===0?plane:ua===0?u:v,y=axis===1?plane:ua===1?u:v,z=axis===2?plane:ua===2?u:v
 const pi=((y+1)*18+z+1)*18+x+1,id=a[pi]
 if(!this.cube[id])continue
 const nid=a[pi+OFF[dir]];if(this.opaque[nid]||(nid>=0&&this.base.st(id).self&&this.base.st(id).name===this.base.st(nid).name))continue
 const bio=s.biomes[(y>>2)*16+(z>>2)*4+(x>>2)];mask[v*16+u]=(id+1)*256+bio;unitFaces++
 }
 for(let v=0;v<16;v++)for(let u=0;u<16;){const key=mask[v*16+u];if(!key){u++;continue}let w=1,h=1
 if(greedy){while(u+w<16&&mask[v*16+u+w]===key)w++;outer:while(v+h<16){for(let i=0;i<w;i++)if(mask[(v+h)*16+u+i]!==key)break outer;h++}}
 for(let j=0;j<h;j++)mask.fill(0,(v+j)*16+u,(v+j)*16+u+w)
 const id=(key>>8)-1,bio=key&255,f=this.faces[id]![dir],d=f.d,lim=Array.from(d.subarray(5,9)),tint=f.tint?this.base.tintOf(bio,f.tint):null
 for(let k=0;k<4;k++){
 const o=k*13,p=[d[o],d[o+1],d[o+2]];p[axis]=plane+(sign>0?1:0);p[ua]=u+p[ua]*w;p[va]=v+p[va]*h
 const uv=[(d[o+3]-lim[0])/(lim[2]-lim[0])*(f.texAxis===ua?w:h),(d[o+4]-lim[1])/(lim[3]-lim[1])*(f.texAxis===ua?h:w)]
 builds[this.base.st(id).semi?'trans':'opaque'].vertex(p,uv,lim,tint??Array.from(d.subarray(o+9,o+12)),d[o+12],0,1,true)
 }greedyQuads++;u+=w
 }
 }
 }
 for(const layer of ['opaque','trans']as const){const b=builds[layer].finish();if(b.quads){const prev=out.layers[layer];if(prev){const data=new Uint8Array(prev.data.length+b.data.length);data.set(prev.data);data.set(b.data,prev.data.length);out.layers[layer]={data,quads:prev.quads+b.quads}}else out.layers[layer]=b}}
 return{...out,ms:performance.now()-t,unitFaces,greedyQuads}
 }
}
