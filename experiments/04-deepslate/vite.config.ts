import { defineConfig, type Plugin } from 'vite'
import fs from 'node:fs'
import path from 'node:path'

// 開發伺服器把 <repo>/.work 以 /work/ 提供給頁面（Mojang 資源與測試世界都不進 repo）。
const workDir = path.resolve(import.meta.dirname, '../../.work')
const types: Record<string, string> = { '.json': 'application/json', '.png': 'image/png', '.mca': 'application/octet-stream' }
function serveWork(): Plugin {
  return {
    name: 'serve-work',
    configureServer(server) {
      server.middlewares.use('/work', (req, res, next) => {
        const p = path.join(workDir, decodeURIComponent((req.url ?? '').split('?')[0]))
        if (!p.startsWith(workDir) || !fs.existsSync(p) || !fs.statSync(p).isFile()) { res.statusCode = 404; res.end('not found'); return }
        res.setHeader('Content-Type', types[path.extname(p)] ?? 'application/octet-stream')
        res.setHeader('Content-Length', fs.statSync(p).size)
        fs.createReadStream(p).pipe(res)
      })
    },
  }
}
export default defineConfig({
  plugins: [serveWork()],
  server: { host: '127.0.0.1', port: 5174, strictPort: true },
  worker: { format: 'es' },
  build: { target: 'es2022' },
})
