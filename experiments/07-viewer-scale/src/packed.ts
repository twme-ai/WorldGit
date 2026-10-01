import type { Layer } from './base-mesher.ts'
// 16 B／頂點：int16×3 位置(1/64)、u16×2 UV、u16 材質矩形索引、u8×3 色、u8 [light:4|repeat:1|kind:3]。
// 原 24 B 版本多帶 4×u16 atlas 矩形與 alpha；矩形改由索引查表（主執行緒以資料紋理提供）。
export const STRIDE=16
export const NO_RECT=65535
export const Rects={map:new Map<string,number>(),list:[] as number[][],miss:0,size:2048,
 unresolved:0,
 // 部分矩形（動畫貼圖的首格、植物裁切）不在 atlas 表內：改用完全包含它的最小 atlas 矩形；NEAREST 取樣下 clamp 範圍差異不影響取樣結果。
 last:[NaN,NaN,NaN,NaN],lastIdx:0,
 resolve(lim:number[]){const l=this.last;if(l[0]===lim[0]&&l[1]===lim[1]&&l[2]===lim[2]&&l[3]===lim[3])return this.lastIdx;const r=this.resolve2(lim);l[0]=lim[0];l[1]=lim[1];l[2]=lim[2];l[3]=lim[3];this.lastIdx=r;return r},
 resolve2(lim:number[]){const k=lim.join(',');let i=this.map.get(this.key(lim));if(i!==undefined)return i;i=this.sub.get(k);if(i!==undefined)return i;const e=1e-4;let best=-1,area=Infinity;this.list.forEach((r,j)=>{if(r[0]<=lim[0]+e&&r[1]<=lim[1]+e&&r[2]>=lim[2]-e&&r[3]>=lim[3]-e){const a=(r[2]-r[0])*(r[3]-r[1]);if(a<area){area=a;best=j}}});if(best<0){this.unresolved++;best=NO_RECT}this.sub.set(k,best);this.miss++;return best},
 sub:new Map<string,number>(),
 key(r:number[]){return r.map(v=>Math.round(v*this.size)).join(',')},
 init(uv:Record<string,number[]>,size:number){if(this.list.length)return;this.size=size;for(const r of Object.values(uv)){const k=this.key(r);if(!this.map.has(k)){this.map.set(k,this.list.length);this.list.push(r)}}}}
export interface PackedSection {cx:number;sy:number;cz:number;layers:Partial<Record<Layer,{data:Uint8Array;quads:number}>>;ms:number;unitFaces:number;greedyQuads:number}
export class PackedBuilder {
 a=new Uint8Array(STRIDE*4*64);n=0
 vertex(p:number[],uv:number[],lim:number[],color:number[],light:number,kind=0,alpha=1,repeat=false){
 if(this.n+STRIDE>this.a.length){const b=new Uint8Array(this.a.length*2);b.set(this.a);this.a=b}
 const d=new DataView(this.a.buffer);let o=this.n
 for(let k=0;k<3;k++)d.setInt16(o+k*2,Math.round(p[k]*64),true)
 for(let k=0;k<2;k++)d.setUint16(o+6+k*2,Math.round(uv[k]*(repeat?256:65535)),true)
 let t=NO_RECT;if(lim[2]>lim[0]){t=Rects.resolve(lim)}
 d.setUint16(o+10,t,true)
 for(let k=0;k<3;k++)this.a[o+12+k]=Math.round(color[k]*255)
 // alpha 只剩水面 0.72：以 kind 5 表示（diff 的 ghost 水面 alpha 不保留）。
 const kd=kind===0&&alpha<0.9?5:kind
 this.a[o+15]=(Math.round(Math.max(0,Math.min(1,light))*15)<<4)|(repeat?8:0)|kd;this.n+=STRIDE
 }
 finish(){return{data:this.a.slice(0,this.n),quads:this.n/(STRIDE*4)}}
}
