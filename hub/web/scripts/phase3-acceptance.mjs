// 正式 CSP 下驗證合併預覽：衝突區域、ours／theirs／base 切換、選擇結果、URL 重載、#46 逐格 state。
import { chromium } from 'playwright-core'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
const [hub, token, out] = process.argv.slice(2)
assert.match(hub, /^http:\/\/127\.0\.0\.1:18\d{3}$/)
const shots = path.resolve('hub/docs/screenshots/phase3')
fs.mkdirSync(shots, { recursive: true })
const headers = { Authorization: `Bearer ${token}` }
const get = async p => { const r = await fetch(hub + p, { headers }); assert.equal(r.status, 200, await r.clone().text()); return r.json() }
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? '/usr/bin/google-chrome', headless: true,
  args: ['--no-sandbox', '--use-angle=swiftshader', '--use-gl=angle', '--enable-unsafe-swiftshader', '--ignore-gpu-blocklist', '--disable-dev-shm-usage'] })
const result = { screenshots: [], modes: {}, errors: [], failedResponses: [], workers: [], violations: [], checks: [] }
try {
  const ctx = await browser.newContext({ viewport: { width: 1280, height: 1000 }, colorScheme: 'dark' })
  await ctx.addInitScript(t => {
    localStorage.setItem('worldgit.token', t)
    window.__cspViolations = []
    document.addEventListener('securitypolicyviolation', e => window.__cspViolations.push({ directive: e.effectiveDirective, blocked: e.blockedURI }))
  }, token)
  // 測試環境沒有 CJK 字型：只擴充同源樣式表與字型回應，保留產品 CSP。
  const font = process.env.CJK_FONT ?? path.resolve('.work/viewer-scale/fonts/NotoSansCJKtc-Regular.otf')
  if (fs.existsSync(font)) {
    await ctx.route('**/__shot-font.otf', r => r.fulfill({ body: fs.readFileSync(font), contentType: 'font/otf' }))
    await ctx.route('**/assets/*.css', async r => {
      const original = await r.fetch()
      const css = await original.text()
      await r.fulfill({ response: original, body: css + '\n@font-face{font-family:ShotCJK;src:url(/__shot-font.otf)}:root{font-family:ShotCJK,system-ui,sans-serif;--mono:ui-monospace,ShotCJK,monospace}' })
    })
  }
  const page = await ctx.newPage()
  page.on('pageerror', e => result.errors.push(e.message))
  page.on('console', m => { if (m.type() === 'error') result.errors.push(m.text()) })
  page.on('worker', w => result.workers.push(w.url()))
  page.on('response', r => { if (r.status() >= 400) result.failedResponses.push({ url: r.url(), status: r.status() }) })
  const capture = async name => {
    await page.evaluate(() => document.fonts.ready)
    const file = path.join(shots, name + '.jpg')
    await page.screenshot({ path: file, type: 'jpeg', quality: 82, fullPage: true })
    result.screenshots.push({ file: path.relative(process.cwd(), file), bytes: fs.statSync(file).size })
    result.violations.push(...await page.evaluate(() => window.__cspViolations))
  }
  const goto = async p => {
    result.violations.push(...await page.evaluate(() => window.__cspViolations ?? []).catch(() => []))
    const r = await page.goto(hub + p, { waitUntil: 'domcontentloaded' })
    assert.equal(r.status(), 200)
    assert.ok(r.headers()['content-security-policy']?.includes("script-src 'self'"))
  }
  const idle = async () => {
    await page.waitForFunction(() => window.__worldgit?.viewer?.stats.ready && window.__worldgit.viewer.stats.sectionsMeshed > 0, null, { timeout: 120000 })
    await page.waitForTimeout(250)
  }
  const modeData = async () => page.evaluate(() => {
    const v = window.__worldgit.viewer
    const counts = [0, 0, 0, 0, 0]
    for (const d of v.world.diffs.values()) for (const k of d.kind) if (k) counts[k]++
    return { id: v.cfg.detail.commit.id, base: v.cfg.base, diff: v.cfg.showDiff, mode: v.renderer.mode, camera: v.getViewState(), stats: v.stats,
      counts, gold: v.table.states[v.world.getState(8, 65, 8)], emerald: v.table.states[v.world.getState(3, 65, 3)] }
  })
  const pv = '/admin/branches/merge-preview/ours...theirs'
  const api = '/api/v1/worlds/admin/branches/merge-preview?ours=ours&theirs=theirs'
  const report = await get(api)
  assert.equal(report.canMerge, true); assert.equal(report.zeroIntervention, false)
  const ows = report.regions.filter(r => r.dimension === 'minecraft:overworld')
  assert.ok(ows.length >= 2, 'overworld 至少兩個衝突區域')
  const fenceRegion = ows.find(r => r.bounds.minX === 8 && r.bounds.minY === 65 && r.bounds.minZ === 8) ?? ows[0]
  result.checks.push(`報告：${report.regions.length} 區域，overworld ${ows.length}`)
  const at = (x, y, z) => page.evaluate(([x, y, z]) => { const v = window.__worldgit.viewer; return v.table.states[v.world.getState(x, y, z)] }, [x, y, z])
  const info = () => page.evaluate(() => { const v = window.__worldgit.viewer; return { camera: v.getViewState(), stats: v.stats } })
  await goto(pv)
  await page.waitForSelector(`[data-region="${fenceRegion.id}"]`)
  assert.match(await page.locator('main').textContent(), /Phase 4 才會在網頁產生 merge commit/)
  await idle()
  result.modes.auto = { fence: await at(8, 65, 8), cam: (await info()).camera }
  await capture('merge-conflicts')
  // 區域清單：點選區域後鏡頭移動
  const other = ows.find(r => r.id !== fenceRegion.id)
  await page.locator(`[data-region="${other.id}"] .conflict-focus`).click(); await idle()
  const camA = (await info()).camera
  await page.locator(`[data-region="${fenceRegion.id}"] .conflict-focus`).click(); await idle()
  const camB = (await info()).camera
  assert.notDeepEqual(camA, camB, '選取不同區域後鏡頭應移到該區域')
  // ours／theirs／base 切換：逐格 state 與快照一致（#46：保留完整 fence state）
  const fenceOurs = 'minecraft:oak_fence[east=false,north=false,south=false,waterlogged=false,west=false]'
  for (const [label, expect] of [['ours', fenceOurs], ['theirs', null], ['base', 'minecraft:air']]) {
    await page.getByRole('button', { name: label, exact: true }).first().click(); await idle()
    const st = await at(8, 65, 8)
    result.modes[label] = { fence: st, redstoneOrGold: await at(9, 65, 8), emeraldOrDiamond: await at(8, 66, 8) }
    if (expect) assert.equal(st, expect, label + ' 的 state 必須逐格相同')
    else assert.match(st, /^minecraft:nether_brick_fence\[east=false,north=false,south=false,waterlogged=false,west=false\]$/)
  }
  assert.equal(result.modes.ours.redstoneOrGold, 'minecraft:redstone_block'); assert.equal(result.modes.theirs.redstoneOrGold, 'minecraft:gold_block')
  assert.equal(result.modes.ours.emeraldOrDiamond, 'minecraft:emerald_block'); assert.equal(result.modes.theirs.emeraldOrDiamond, 'minecraft:diamond_block')
  await page.getByRole('button', { name: 'theirs', exact: true }).first().click(); await idle()
  await capture('merge-theirs')
  // 選擇：全部選 theirs，另一區域選 base；依選擇的結果預覽
  await page.getByRole('button', { name: '自動合併結果', exact: true }).click(); await idle()
  const autoFence = await at(8, 65, 8)
  await page.locator(`[data-region="${fenceRegion.id}"] select`).selectOption('theirs')
  await page.locator(`[data-region="${other.id}"] select`).selectOption('base'); await idle()
  const sel = new URL(page.url()).searchParams
  assert.equal(sel.get('view'), 'selected'); assert.ok(sel.get('choices'), '選擇存在網址')
  assert.match(await at(8, 65, 8), /^minecraft:nether_brick_fence\[/)
  // 區域外的自動合併（ours 的 obsidian）必須保留，不被區域切換覆蓋
  assert.equal(await at(9, 66, 8), 'minecraft:obsidian')
  assert.match(await page.locator('.selection-summary').textContent(), /theirs 1.*base 1/)
  await capture('merge-selected')
  const url = page.url()
  await page.reload({ waitUntil: 'domcontentloaded' }); await idle()
  assert.equal(page.url(), url)
  assert.equal(await page.locator(`[data-region="${fenceRegion.id}"] select`).inputValue(), 'theirs')
  assert.equal(await page.locator(`[data-region="${other.id}"] select`).inputValue(), 'base')
  assert.match(await at(8, 65, 8), /^minecraft:nether_brick_fence\[/)
  result.checks.push('ours／theirs／base 切換、依選擇預覽、URL 重載保留選擇、#46 state')
  // 過期 tip 的選擇不套用
  await goto(pv + '?choices=' + sel.get('choices') + '&tips=deadbeef')
  await page.waitForSelector('.selection-summary')
  assert.match(await page.locator('main').textContent(), /來源 tip 已變動/)
  // 維度篩選與衝突前提
  await goto(pv)
  await page.locator('select[aria-label="篩選維度"]').selectOption('minecraft:the_nether'); await page.waitForTimeout(200)
  assert.ok((await page.locator('.conflict-row').count()) >= 1)
  await goto('/admin/branches/merge-preview/ours...version'); await page.waitForSelector('.merge-report')
  assert.match(await page.locator('main').textContent(), /DataVersion/)
  await capture('merge-data-version')
  await goto('/admin/branches/branches'); await page.waitForSelector('[data-branch="main"]')
  assert.ok(await page.locator('a', { hasText: '預覽合併' }).count() >= 1)
  assert.equal(await page.evaluate(() => window.__worldgit?.viewer ?? null), null)
  result.checks.push('篩選維度、DataVersion 前提、分支頁入口、離頁釋放 viewer')
  result.violations.push(...await page.evaluate(() => window.__cspViolations))
  const anonymous = await browser.newContext()
  const anon = await anonymous.request.get(hub + api)
  assert.equal(anon.status(), 404)
  await anonymous.close()
  result.checks.push('私人世界匿名 404')
  assert.equal(result.errors.length, 0, JSON.stringify(result.errors))
  assert.equal(result.failedResponses.length, 0, JSON.stringify(result.failedResponses))
  assert.equal(result.violations.length, 0, JSON.stringify(result.violations))
  assert.ok(result.screenshots.reduce((n, s) => n + s.bytes, 0) < 1_500_000)
  await ctx.close()
} finally {
  fs.writeFileSync(path.join(out, 'acceptance.json'), JSON.stringify(result, null, 2))
  await browser.close()
}
console.log(JSON.stringify(result, null, 2))
