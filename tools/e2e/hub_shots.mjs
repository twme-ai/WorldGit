// Hub commit 3D 頁面截圖與頁內 diff 格子擷取。
// 用法：node hub_shots.mjs <hubUrl> <token> <outDir> <jobsJson>
// jobs：[{ name, path, cam?: ViewState, extract?: bool, width?, height?, palette? }]
// extract=true 時回傳 viewer.world.diffs 內所有變動格（世界座標 + kind），供與 CLI／封包比對。
import { pathToFileURL } from 'node:url'
import fs from 'node:fs'
import path from 'node:path'

const root = path.resolve(path.dirname(new URL(import.meta.url).pathname), '../..')
const { chromium } = await import(pathToFileURL(path.join(root, 'hub/web/node_modules/playwright-core/index.mjs')).href)
const [hub, token, outDir, jobsFile] = process.argv.slice(2)
const jobs = JSON.parse(fs.readFileSync(jobsFile, 'utf8'))
fs.mkdirSync(outDir, { recursive: true })
const browser = await chromium.launch({
  executablePath: process.env.CHROME ?? '/usr/bin/google-chrome', headless: true,
  args: ['--no-sandbox', '--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--disable-dev-shm-usage'],
})
const fontPath = process.env.CJK_FONT ?? path.join(root, '.work/viewer-scale/fonts/NotoSansCJKtc-Regular.otf')
const results = []
try {
  for (const job of jobs) {
    const ctx = await browser.newContext({ viewport: { width: job.width ?? 1280, height: job.height ?? 800 }, colorScheme: 'dark', deviceScaleFactor: 1, bypassCSP: true })
    await ctx.addInitScript((t) => { try { localStorage.setItem('worldgit.token', t) } catch {} }, token)
    if (job.palette) await ctx.addInitScript((p) => { try { localStorage.setItem('worldgit.palette', p) } catch {} }, job.palette)
    // headless Chrome 沒有 CJK 字型：只在截圖時由本機檔案提供（產品本身用系統字型）
    if (fs.existsSync(fontPath)) {
      await ctx.route('**/__font.otf', (r) => r.fulfill({ body: fs.readFileSync(fontPath), contentType: 'font/otf' }))
      await ctx.addInitScript(() => {
        const css = '@font-face{font-family:"ShotCJK";src:url("/__font.otf")}  *{font-family:"ShotCJK",system-ui,sans-serif !important}'
        const add = () => { const s = document.createElement('style'); s.textContent = css; document.head.append(s) }
        if (document.head) add(); else document.addEventListener('DOMContentLoaded', add)
      })
    }
    const page = await ctx.newPage()
    const logs = []
    page.on('console', (m) => { if (m.type() === 'error') logs.push(m.text()) })
    page.on('pageerror', (e) => logs.push('pageerror: ' + e.message))
    const t0 = Date.now()
    await page.goto(hub + job.path, { waitUntil: 'domcontentloaded' })
    await page.waitForFunction(() => window.__worldgit?.viewer, null, { timeout: 60000 })
    if (job.cam) await page.evaluate((c) => window.__worldgit.viewer.applyViewState(c), job.cam)
    await page.waitForFunction(() => window.__worldgit.viewer.stats.ready, null, { timeout: 300000, polling: 500 })
    await page.waitForTimeout(1500)
    const info = { name: job.name, readyMs: Date.now() - t0 }
    info.stats = await page.evaluate(() => window.__worldgit.viewer.stats)
    if (job.extract) {
      info.cells = await page.evaluate(() => {
        const out = []
        for (const d of window.__worldgit.viewer.world.diffs.values()) {
          for (let i = 0; i < 4096; i++) if (d.kind[i]) out.push([d.cx * 16 + (i & 15), d.sy * 16 + (i >> 8), d.cz * 16 + ((i >> 4) & 15), d.kind[i]])
        }
        return out
      })
    }
    const file = path.join(outDir, job.name + '.png')
    await page.screenshot({ path: file })
    info.file = file; info.bytes = fs.statSync(file).size; info.logs = logs.slice(0, 20)
    results.push(info)
    console.log(JSON.stringify({ ...info, cells: info.cells ? info.cells.length : undefined }))
    await ctx.close()
  }
} finally {
  await browser.close()
}
fs.writeFileSync(path.join(outDir, 'shots.json'), JSON.stringify(results, null, 1))
