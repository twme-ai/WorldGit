// 讀既有圖集的小型預處理（不解世界），用 alpha 判定雙半磚等 state 的完整遮擋。
import fs from 'node:fs'
import path from 'node:path'
import { PNG } from 'pngjs'
const dir=path.resolve(import.meta.dirname,'../../../.work/viewer-scale/pack')
const atlas=JSON.parse(fs.readFileSync(dir+'/atlas.json','utf8')),png=PNG.sync.read(fs.readFileSync(dir+'/atlas.png')),uvs=[]
for(const uv of Object.values(atlas.uv)){let opaque=true;outer:for(let y=Math.round(uv[1]*png.height);y<Math.round(uv[3]*png.height);y++)for(let x=Math.round(uv[0]*png.width);x<Math.round(uv[2]*png.width);x++)if(png.data[(y*png.width+x)*4+3]!==255){opaque=false;break outer}if(opaque)uvs.push(uv)}
fs.writeFileSync(dir+'/opaqueUV.json',JSON.stringify(uvs));console.log('opaque texture UVs',uvs.length)
