// 用產品 CSP 驗證真正的 3D／diff、同源 Worker 與貼圖；不注入 style 或放寬 CSP。
// node scripts/security-csp.mjs http://127.0.0.1:8093 <token> <outDir>
import { chromium } from 'playwright-core'
import fs from 'node:fs'
const [hub, token, out] = process.argv.slice(2)
if (!/^http:\/\/127\.0\.0\.1:809[34]$/.test(hub)) throw new Error('安全驗證只允許本機 8093/8094')
fs.mkdirSync(out, { recursive: true })
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? '/usr/bin/google-chrome', headless: true,
  args: ['--no-sandbox', '--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--disable-dev-shm-usage'] })
try {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 900 } })
  await ctx.addInitScript((t) => {
    localStorage.setItem('worldgit.token', t)
    window.__cspViolations = []
    document.addEventListener('securitypolicyviolation', (e) => window.__cspViolations.push({ directive: e.effectiveDirective, blocked: e.blockedURI }))
  }, token)
  const page = await ctx.newPage(), errors = [], workers = [], responses = []
  page.on('console', (m) => { if (m.type() === 'error') errors.push(m.text()) })
  page.on('pageerror', (e) => errors.push(e.message))
  page.on('worker', (w) => workers.push(w.url()))
  page.on('response', (r) => { if (r.status() >= 400) responses.push({ url: r.url(), status: r.status() }) })
  await page.goto(`${hub}/admin/viewer/commit/minecraft.overworld/HEAD`, { waitUntil: 'domcontentloaded' })
  await page.waitForFunction(() => window.__worldgit?.viewer?.stats.ready, null, { timeout: 180000 })
  const result = await page.evaluate(() => ({ stats: window.__worldgit.viewer.stats, violations: window.__cspViolations,
    canvases: document.querySelectorAll('canvas').length }))
  result.errors = errors; result.workers = workers; result.failedResponses = responses
  await page.screenshot({ path: `${out}/csp-viewer.png` })
  fs.writeFileSync(`${out}/csp-results.json`, JSON.stringify(result, null, 2))
  if (result.violations.length || errors.length || responses.length || !workers.length || !result.stats.ready || !result.stats.chunks)
    throw new Error(JSON.stringify(result))
  console.log(JSON.stringify(result))
  await ctx.close()
} finally { await browser.close() }
