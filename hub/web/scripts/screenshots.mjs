// Playwright 截圖／冒煙：node scripts/screenshots.mjs <hubUrl> <token> <outDir> <jobsJson>
// jobs：[{ name, path, wait: 'viewer'|'map'|'none', width, height, theme, palette, cam, mode, action }]
// 需要 PLAYWRIGHT_BROWSERS_PATH 或系統 Chrome（預設 /usr/bin/google-chrome）。重負載：請用 flock 包住。
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'

const [hub, token, outDir, jobsFile] = process.argv.slice(2)
const jobs = JSON.parse(fs.readFileSync(jobsFile, 'utf8'))
const fontPath = process.env.CJK_FONT ?? '/root/projects/ProjectCollection/WorldGit/.work/viewer-scale/fonts/NotoSansCJKtc-Regular.otf'
fs.mkdirSync(outDir, { recursive: true })

const browser = await chromium.launch({
  executablePath: process.env.CHROME ?? '/usr/bin/google-chrome', headless: true,
  args: ['--no-sandbox', '--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--disable-dev-shm-usage'],
})
const results = []
try {
  for (const job of jobs) {
    const ctx = await browser.newContext({ viewport: { width: job.width ?? 1360, height: job.height ?? 900 }, colorScheme: job.theme ?? 'dark', deviceScaleFactor: 1 })
    if (token && !job.anonymous) await ctx.addInitScript((t) => { try { localStorage.setItem('worldgit.token', t) } catch {} }, token)
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
    page.on('console', (m) => { if (m.type() === 'error' || m.type() === 'warning') logs.push(`${m.type()}: ${m.text()}`) })
    page.on('pageerror', (e) => logs.push(`pageerror: ${e.message}`))
    const t0 = Date.now()
    await page.goto(hub + job.path, { waitUntil: 'domcontentloaded' })
    let info = {}
    if (job.wait === 'viewer') {
      await page.waitForFunction(() => window.__worldgit?.viewer, null, { timeout: 30000 })
      if (job.cam) await page.evaluate((c) => window.__worldgit.viewer.applyViewState(c), job.cam)
      if (job.mode) await page.evaluate((m) => window.__worldgit.viewer.setMode(m), job.mode)
      await page.waitForFunction(() => window.__worldgit.viewer.stats.ready, null, { timeout: job.timeout ?? 240000, polling: 500 })
      info.readyMs = Date.now() - t0
      info.stats = await page.evaluate(() => window.__worldgit.viewer.stats)
      await page.waitForTimeout(400)
    } else if (job.wait === 'map') {
      await page.waitForSelector('.map canvas', { timeout: 30000 })
      await page.waitForTimeout(job.settle ?? 2500)
    } else await page.waitForTimeout(job.settle ?? 800)
    if (job.action) { for (const a of job.action) { await page.evaluate(a); await page.waitForTimeout(600) } }
    if (job.settleAfter) await page.waitForTimeout(job.settleAfter)
    const file = path.join(outDir, job.name + '.png')
    await page.screenshot({ path: file, fullPage: !!job.fullPage })
    results.push({ name: job.name, file, bytes: fs.statSync(file).size, ...info, logs: logs.slice(0, 20) })
    console.log(JSON.stringify(results.at(-1)))
    await ctx.close()
  }
} finally {
  await browser.close()
}
fs.writeFileSync(path.join(outDir, 'results.json'), JSON.stringify(results, null, 1))
