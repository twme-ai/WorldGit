import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { createRequire } from 'node:module'
const require = createRequire(new URL('../../hub/web/package.json', import.meta.url))
const { chromium } = require('playwright-core')
const config = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
const result = { success: false, views: [], errors: [], requests: [] }
const browser = await chromium.launch({ executablePath: process.env.CHROME ?? '/usr/bin/google-chrome', headless: true,
  args: ['--no-sandbox', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] })
try {
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } })
  const page = await context.newPage()
  page.on('pageerror', e => result.errors.push(String(e)))
  page.on('request', r => { if (/\/(chunks|diff|entities|tiles)(\?|\/)/.test(r.url())) result.requests.push(r.url()) })
  await page.goto(config.hub+'/login')
  await page.locator('input[name=username]').fill('admin')
  await page.locator('input[name=password]').fill(config.password)
  await page.getByRole('button', { name: '登入', exact: true }).click()
  await page.waitForURL(config.hub+'/')
  for (const [view, target] of [['color', '26.3'], ['before', '26.3'], ['after', '1.20.4']]) {
    const start = result.requests.length
    await page.goto(config.hub+'/admin/versions/compare/new...main?view='+view)
    await page.locator('.hud').filter({ hasText: '就緒' }).waitFor({ timeout: 180000 })
    assert.ok(result.requests.slice(start).some(url => url.includes('/chunks?') && url.includes('target='+target)))
    if (view === 'color') {
      assert.ok(result.requests.slice(start).some(url => url.includes('/diff?') && url.includes('target=26.3')))
      assert.ok(await page.evaluate(() => window.__worldgit?.viewer))
      await page.screenshot({ path: path.join(config.out, 'cross-version-color.png'), fullPage: true })
    }
    result.views.push({ view, target, ready: true })
  }
  assert.deepEqual(result.errors, [])
  result.success = true
} finally {
  await browser.close()
  fs.writeFileSync(config.result, JSON.stringify(result, null, 2))
}
