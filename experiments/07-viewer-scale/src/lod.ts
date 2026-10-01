import { PackedBuilder, type PackedSection } from './packed.ts'
export interface Entry {cx:number;cz:number;src:number}
export interface Top {h:number[];state:number[];biome:number[]}
export function lodMesh(e:Entry,top:Top,colors:number[][],step=4):PackedSection{
 const b=new PackedBuilder()
 for(let z=0;z<16;z+=step)for(let x=0;x<16;x+=step){let h=0;for(let dz=0;dz<step;dz++)for(let dx=0;dx<step;dx++)h+=top.h[(z+dz)*16+x+dx];h/=step*step;const i=(z+step/2|0)*16+(x+step/2|0),c=colors[top.state[i]]??[0.4,0.5,0.3]
 for(const p of [[x,h+64,z],[x,h+64,z+step],[x+step,h+64,z+step],[x+step,h+64,z]])b.vertex(p,[0,0],[0,0,0,0],c,1)
 }return{cx:e.cx,sy:-4,cz:e.cz,layers:{opaque:b.finish()},ms:0,unitFaces:0,greedyQuads:0}
}
