import { defineConfig } from 'vitest/config'

// 開發時把 /api 與 /git 轉給本機 Hub（預設 8091）；正式建置輸出到 dist/，由 Hub jar 的 static/ 一起提供。
const hub = process.env.HUB_URL ?? 'http://127.0.0.1:8091'
export default defineConfig({
  cacheDir: process.env.VITE_CACHE_DIR ?? 'node_modules/.vite',
  server: { host: '127.0.0.1', port: 5191, proxy: { '/api': hub, '/git': hub } },
  preview: { host: '127.0.0.1', port: 5192, proxy: { '/api': hub, '/git': hub } },
  worker: { format: 'es' },
  build: { target: 'es2022', outDir: 'dist', emptyOutDir: true, chunkSizeWarningLimit: 900 },
  test: { include: ['test/**/*.test.ts'], environment: 'node' },
})
