// 用無頭 Chrome（軟體 WebGL）截圖。用法：
//   node scripts/screenshot.mjs                 # 依 SHOTS 全部截
//   node scripts/screenshot.mjs name1 name2     # 只截指定
// 需先啟動 dev server（npm run dev，port 5174）。
import { chromium } from 'playwright-core'
import fs from 'node:fs'
import path from 'node:path'

const out = path.resolve(import.meta.dirname, '../screenshots')
fs.mkdirSync(out, { recursive: true })
const SHOTS = {
  '01-overview-1.21.11': 'ver=1.21.11&view=B&cam=overview&labels=0',
  '02-stairs-slabs': 'ver=1.21.11&view=A&trans=0&cam=stairs&labels=0',
  '03-fences-walls-panes': 'ver=1.21.11&view=A&trans=0&cam=fences&labels=0',
  '04-doors-bed': 'ver=1.21.11&view=A&trans=0&cam=doors&labels=0',
  '05-redstone': 'ver=1.21.11&view=A&trans=0&cam=redstone&labels=0',
  '06-plants-snow': 'ver=1.21.11&view=A&trans=0&cam=plants&labels=0',
  '07-fluids': 'ver=1.21.11&view=B&cam=fluids&labels=0',
  '08-block-entities': 'ver=1.21.11&view=A&trans=0&cam=blockentities&labels=0',
  '09-sign-banner-labels': 'ver=1.21.11&view=B&cam=signbanner',
  '10-entities-overlay': 'ver=1.21.11&view=B&cam=entities',
  '11-diff-overview': 'ver=1.21.11&view=D&cam=diff',
  '12-diff-only-changed': 'ver=1.21.11&view=D&only=1&cam=diff&labels=0',
  '13-diff-chunk-border': 'ver=1.21.11&view=D&cam=diff2&labels=0',
  '14-overview-26.2': 'ver=26.2&view=B&cam=overview&labels=0',
  '15-terrain-16x16': 'ver=1.21.11&size=16x16&view=B&cam=terrain&labels=0&w=640&h=360',
  '16-biome-swamp-override': 'ver=1.21.11&view=A&trans=0&cam=plants&labels=0&biome=swamp',
  '17-stock-deepslate-overview': 'mode=stock&ver=1.21.11&cam=overview',
  '18-stock-deepslate-blockentities': 'mode=stock&ver=1.21.11&cam=blockentities',
}
const want = process.argv.slice(2)
const browser = await chromium.launch({
  executablePath: process.env.CHROME ?? '/usr/bin/google-chrome',
  args: ['--no-sandbox', '--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist'],
})
for (const [name, qs] of Object.entries(SHOTS)) {
  if (want.length && !want.includes(name)) continue
  const sp = new URLSearchParams(qs)
  const page = await browser.newPage({ viewport: { width: Number(sp.get('w') ?? 1280), height: Number(sp.get('h') ?? 720) } })
  const logs = []
  page.on('console', m => logs.push(`[${m.type()}] ${m.text()}`))
  page.on('pageerror', e => logs.push('[pageerror] ' + e.message))
  const t0 = Date.now()
  await page.goto(`http://127.0.0.1:5174/?${qs}`)
  try { await page.waitForFunction(() => document.body.dataset.ready === '1', null, { timeout: 90000 }) } catch (e) { logs.push('TIMEOUT waiting for ready') }
  await page.waitForTimeout(1500) // 讓 fps 統計與最後一幀穩定
  const file = path.join(out, name + '.png')
  await page.screenshot({ path: file })
  const stats = await page.evaluate(() => window.__stats)
  console.log(name, Date.now() - t0, 'ms', fs.statSync(file).size, 'B', JSON.stringify({ fps: stats?.fps, toInteractive: stats?.totalToInteractiveMs && Math.round(stats.totalToInteractiveMs) }))
  if (logs.length) console.log(logs.slice(0, 8).join('\n'))
  await page.close()
}
await browser.close()
