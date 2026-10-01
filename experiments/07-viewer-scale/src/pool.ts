import type { Entry } from './lod.ts'
export class Pool {
 workers:Worker[]=[];idle:number[]=[];queue:any[]=[];next=0;pending=new Map<number,{resolve:(v:any)=>void;reject:(e:any)=>void}>()
 constructor(n:number){for(let i=0;i<n;i++){const w=new Worker(new URL('./worker.ts',import.meta.url),{type:'module'});w.onmessage=({data})=>{const p=this.pending.get(data.id)!;this.pending.delete(data.id);if(data.error)p.reject(new Error(data.error+'\n'+data.stack));else p.resolve(data.result);this.idle.push(i);this.pump()};this.workers.push(w);this.idle.push(i)}}
 private pump(){while(this.idle.length&&this.queue.length){const i=this.idle.shift()!,msg=this.queue.shift();this.workers[i].postMessage(msg)}}
 call(msg:any){const id=++this.next;return new Promise<any>((resolve,reject)=>{this.pending.set(id,{resolve,reject});this.queue.push({...msg,id});this.pump()})}
 init(){return Promise.all(this.workers.map(()=>this.call({op:'init'})))}
 terminate(){this.workers.forEach(w=>w.terminate())}
}
export function adjacent(entries:Entry[],lookup:Map<string,Entry>){const a=new Map<string,Entry>();for(const e of entries)for(const[dx,dz]of [[1,0],[-1,0],[0,1],[0,-1]]){const c=lookup.get(`${e.cx+dx},${e.cz+dz}`);if(c)a.set(`${c.cx},${c.cz}`,c)}return[...a.values()]}
