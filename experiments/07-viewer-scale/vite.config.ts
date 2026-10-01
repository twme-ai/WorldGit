import { defineConfig, type Plugin } from 'vite'
import fs from 'node:fs'
import path from 'node:path'
const workDir=path.resolve(import.meta.dirname,'../../.work/viewer-scale')
const middleware=(req:any,res:any,next:any)=>{
  const p=path.resolve(workDir,'.'+decodeURIComponent((req.url??'').split('?')[0]))
  if(!p.startsWith(workDir+path.sep)||!fs.existsSync(p)||!fs.statSync(p).isFile()){res.statusCode=404;res.end('not found');return}
  const types:Record<string,string>={'.json':'application/json','.png':'image/png','.bin':'application/octet-stream'}
  res.setHeader('Content-Type',types[path.extname(p)]??'application/octet-stream');res.setHeader('Content-Length',fs.statSync(p).size);fs.createReadStream(p).pipe(res)
}
const work:Plugin={name:'serve-viewer-scale-work',configureServer(s){s.middlewares.use('/work',middleware)},configurePreviewServer(s){s.middlewares.use('/work',middleware)}}
export default defineConfig({plugins:[work],cacheDir:workDir+'/vite-cache',server:{host:'127.0.0.1',port:5187,strictPort:true},preview:{host:'127.0.0.1',port:5187,strictPort:true},worker:{format:'es'},build:{target:'es2022',outDir:workDir+'/dist',emptyOutDir:true}})
