// 在無頭 Chrome（SwiftShader 軟體 WebGL）量測載入/網格/繪製/增量更新，輸出 bench-results.json
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'

const browser = await chromium.launch({
  executablePath: process.env.CHROME ?? '/usr/bin/google-chrome',
  args: ['--no-sandbox', '--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
})
const results = { date: new Date().toISOString(), runs: [] }
const combos = [['1.21.11', 'scene'], ['1.21.11', '4x4'], ['1.21.11', '16x16'], ['26.2', 'scene'], ['26.2', '16x16']]
for (const [ver, size] of combos) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } })
  page.on('pageerror', e => console.log('pageerror', e.message))
  await page.goto(`http://127.0.0.1:5174/?ver=${ver}&size=${size}&cam=overview&labels=0`)
  await page.waitForFunction(() => document.body.dataset.ready === '1', null, { timeout: 120000 })
  await page.waitForTimeout(500)
  const r = await page.evaluate(async () => {
    const api = window.__api, v = api.viewer
    let bytes = 0, quads = 0, n = 0
    for (const l of v.meshes.values()) for (const m of l) { bytes += m.quads * 4 * 15 * 4; quads += m.quads; n++ }
    const gl = v.gl, ext = gl.getExtension('WEBGL_debug_renderer_info')
    const frameMs = await api.bench(6)
    const edits = []
    for (const [pos, st] of [[[1, 150, 1], 'minecraft:diamond_block'], [[15, 150, 1], 'minecraft:gold_block'], [[16, 151, 16], 'minecraft:stone']]) edits.push(await api.edit(pos, st))
    return { stats: window.__stats, gpuBufferMB: +(bytes / 1e6).toFixed(1), quads, gpuMeshes: n, frameMsSoftware: +frameMs.toFixed(1), edits, renderer: ext ? gl.getParameter(ext.UNMASKED_RENDERER_WEBGL) : '?', jsHeapMB: performance.memory ? +(performance.memory.usedJSHeapSize / 1e6).toFixed(0) : null }
  })
  results.runs.push({ ver, size, ...r })
  console.log(ver, size, JSON.stringify({ interactive: Math.round(r.stats.totalToInteractiveMs), meshWorker: Math.round(r.stats.mesh.baseWallMs), quads: r.quads, gpuMB: r.gpuBufferMB, frameMs: r.frameMsSoftware, edit: r.edits.map(e => Math.round(e.workerMs * 10) / 10) }))
  await page.close()
}
for (const cam of ['overview', 'blockentities']) {
  const page = await browser.newPage({ viewport: { width: 1280, height: 720 } })
  await page.goto(`http://127.0.0.1:5174/?mode=stock&ver=1.21.11&cam=${cam}`)
  await page.waitForFunction(() => document.body.dataset.ready === '1', null, { timeout: 120000 })
  const s = await page.evaluate(() => window.__stats)
  results.runs.push({ mode: 'stock', cam, stats: s })
  console.log('stock', cam, JSON.stringify(s))
  await page.close()
}
await browser.close()
fs.writeFileSync(path.resolve(import.meta.dirname, '../bench-results.json'), JSON.stringify(results, null, 1))
