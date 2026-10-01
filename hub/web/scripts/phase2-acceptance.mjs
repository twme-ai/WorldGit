// 正式 CSP 下驗證分支、任意 a→b 3D、四個模式、URL 分享／斜線分支、404 與清理。
import { chromium } from 'playwright-core'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
const [hub, token, out] = process.argv.slice(2)
assert.match(hub, /^http:\/\/127\.0\.0\.1:18\d{3}$/)
const shots = path.resolve('hub/docs/screenshots/phase2')
fs.mkdirSync(shots, { recursive: true })
const headers = { Authorization: `Bearer ${token}` }
const get = async p => { const r = await fetch(hub + p, { headers }); assert.equal(r.status, 200, await r.clone().text()); return r.json() }
const compare = await get('/api/v1/worlds/admin/branches/compare?a=main&b=feature')
assert.equal(compare.added, 64); assert.equal(compare.removed, 5); assert.equal(compare.modified, 4)
const ow = compare.dimensions.find(d => d.dimension === 'minecraft:overworld')
assert.ok(ow.a.id !== ow.b.parents[0], 'a 是另一分支，必須使用明確 base')
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
  await goto('/admin/branches/branches')
  await page.waitForSelector('[data-branch="side"]')
  assert.equal(await page.locator('[data-branch="main"] .badge').first().textContent(), '預設')
  assert.match(await page.locator('[data-branch="feature"]').textContent(), /領先 1.*落後 1/)
  assert.match(await page.locator('[data-branch="side"]').textContent(), /缺少維度/)
  await capture('branches')
  await goto('/admin/branches/commits?branch=feature')
  await page.waitForSelector('.snap')
  assert.match(await page.locator('main').textContent(), /金塊建築/)
  assert.doesNotMatch(await page.locator('main').textContent(), /新增綠寶石/)
  result.checks.push('分支列表、缺少維度、分支歷史')
  const camera = '8,66,8,0.75,-0.65,24,o'
  await goto(`/admin/branches/compare/main...feature?cam=${camera}`)
  await idle()
  result.modes.color = await modeData()
  assert.deepEqual(result.modes.color.counts.slice(1, 4), [64, 5, 4])
  assert.equal(result.modes.color.base, ow.a.id)
  await capture('compare-color')
  await page.getByRole('button', { name: '只看變動', exact: true }).click()
  await idle()
  result.modes.changed = await modeData()
  assert.equal(result.modes.changed.mode, 'changed')
  await capture('compare-changed')
  await page.getByRole('button', { name: '前（a）', exact: true }).click()
  await idle()
  result.modes.before = await modeData()
  assert.equal(result.modes.before.id, ow.a.id)
  assert.equal(result.modes.before.gold, 'minecraft:air')
  assert.equal(result.modes.before.emerald, 'minecraft:emerald_block')
  assert.equal(result.modes.before.diff, false)
  await page.getByRole('button', { name: '後（b）', exact: true }).click()
  await idle()
  result.modes.after = await modeData()
  assert.equal(result.modes.after.id, ow.b.id)
  assert.equal(result.modes.after.gold, 'minecraft:gold_block')
  assert.equal(result.modes.after.emerald, 'minecraft:air')
  assert.equal(result.modes.after.diff, false)
  assert.deepEqual(result.modes.before.camera, result.modes.after.camera)
  await capture('compare-after')
  assert.equal(new URL(page.url()).searchParams.get('view'), 'after')
  await page.reload({ waitUntil: 'domcontentloaded' }); await idle()
  assert.equal((await modeData()).diff, false)
  result.checks.push('四種呈現、實際版本內容、鏡頭保留、URL 重載')
  // 真正經 Tomcat 路由的含 / 分支；不可被誤解為另一個路徑或父 commit。
  await goto('/admin/branches/compare/main...build/castle?cam=' + camera)
  await idle()
  assert.deepEqual((await modeData()).counts.slice(1, 4), [64, 5, 4])
  result.checks.push('斜線分支深連結')
  // b 沒有任何 chunk：移除的 section 仍需生成、上傳與繪製鬼影。
  await goto('/admin/branches/compare/main...clear?cam=' + camera)
  await idle()
  const removed = await modeData()
  assert.equal(removed.stats.chunks, 0)
  assert.equal(removed.stats.sectionsMeshed, 1)
  assert.equal(removed.counts[2], 265)
  assert.ok(await page.evaluate(() => !!window.__worldgit.viewer.renderer.meshes.get('0,4,0')?.layers.ghost))
  result.checks.push('整個 chunk 移除仍有鬼影網格')
  await goto('/admin/branches/branches')
  await page.waitForSelector('[data-branch="main"]')
  assert.equal(await page.evaluate(() => window.__worldgit?.viewer ?? null), null)
  result.checks.push('離開比較頁釋放 viewer')
  result.violations.push(...await page.evaluate(() => window.__cspViolations))
  const anonymous = await browser.newContext()
  const anon = await anonymous.request.get(hub + '/api/v1/worlds/admin/branches/compare?a=main&b=feature')
  assert.equal(anon.status(), 404)
  await anonymous.close()
  result.checks.push('私人世界匿名 404')
  assert.equal(result.errors.length, 0, JSON.stringify(result.errors))
  assert.equal(result.failedResponses.length, 0, JSON.stringify(result.failedResponses))
  assert.equal(result.violations.length, 0, JSON.stringify(result.violations))
  assert.ok(result.workers.length >= 2)
  assert.ok(result.screenshots.reduce((n, s) => n + s.bytes, 0) < 1_500_000)
  await ctx.close()
} finally {
  fs.writeFileSync(path.join(out, 'acceptance.json'), JSON.stringify(result, null, 2))
  await browser.close()
}
console.log(JSON.stringify(result, null, 2))
