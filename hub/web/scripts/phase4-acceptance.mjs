import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import { chromium } from 'playwright-core'

// 設定檔為腳本即時產生的私有測試資料；內容不印 log。
const config = JSON.parse(fs.readFileSync(process.argv[2], 'utf8'))
const result = { operation: config.operation, assertions: [], violations: [], errors: [], screenshots: [], failedResponses: [] }
const fontConfig = process.env.FONTCONFIG_FILE ?? path.resolve(import.meta.dirname, '../../../.work/fonts/fonts.conf')
const browser = await chromium.launch({ env: { ...process.env, ...(fs.existsSync(fontConfig) ? { FONTCONFIG_FILE: fontConfig } : {}) }, executablePath: process.env.CHROME ?? '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox', '--use-gl=angle', '--use-angle=swiftshader', '--enable-unsafe-swiftshader'] })
const context = await browser.newContext({ viewport: { width: 1440, height: 1050 }, acceptDownloads: true })
// headless 主機缺 CJK 字型；沿用 Phase 3 的同源 font/CSS route，保留產品 CSP。
const font=process.env.CJK_FONT ?? path.resolve('.work/viewer-scale/fonts/NotoSansCJKtc-Regular.otf')
if(fs.existsSync(font)) {
  await context.route('**/__shot-font.otf',r=>r.fulfill({body:fs.readFileSync(font),contentType:'font/otf'}))
  await context.route('**/assets/*.css',async r=> {
    const original=await r.fetch();const css=await original.text()
    await r.fulfill({response:original,body:css+'\n@font-face{font-family:ShotCJK;src:url(/__shot-font.otf)}html,body,button,input,textarea,select{font-family:ShotCJK,system-ui,sans-serif}'})
  })
}
await context.addInitScript(() => { window.__cspViolations = []; window.addEventListener('securitypolicyviolation', e => window.__cspViolations.push({ directive: e.violatedDirective, blocked: e.blockedURI })) })
const page = await context.newPage()
page.on('pageerror', e => result.errors.push(String(e)))
page.on('response', r => { if(r.status()>=400 && result.failedResponses.length<50){const row={status:r.status(),url:r.url()};result.failedResponses.push(row);void r.json().then(body=>{if(body.errorReport)row.errorReport=body.errorReport}).catch(()=>{})} })
page.on('console', m => { if(m.type()==='error') result.errors.push(m.text()+' · '+m.location().url) })
const goto = async p => { result.violations.push(...await page.evaluate(()=>window.__cspViolations ?? []).catch(()=>[])); const response=await page.goto(config.hub+p,{waitUntil:'domcontentloaded'});assert.equal(response.status(),200);assert.match(response.headers()['content-security-policy'],/script-src 'self'/) }
const login = async user => { await goto('/login');await page.locator('input[name=username]').fill(user.username);await page.locator('input[name=password]').fill(user.password);await page.getByRole('button',{name:'登入',exact:true}).click();await page.waitForURL(config.hub+'/');assert.equal(await page.evaluate(()=>localStorage.getItem('worldgit.token')),null);const cookies=await context.cookies();assert.ok(cookies.find(c=>c.name==='JSESSIONID'&&c.httpOnly&&c.sameSite==='Lax'));result.assertions.push('cookie login, no localStorage token') }
const shot = async label => { await page.evaluate(()=>document.fonts.ready);await page.evaluate(()=>window.scrollTo(0,0));await page.screenshot({ path:path.join(config.out,label+'.jpg'),type:'jpeg',quality:78,fullPage:true });result.screenshots.push(label+'.jpg') }
try {
  if(config.operation==='pr') {
    await login(config.author);await goto(`/${config.owner}/${config.world}/pulls?source=${encodeURIComponent(config.source)}`)
    await page.getByLabel('來源分支',{exact:true}).selectOption(config.source);await page.getByLabel('目標分支',{exact:true}).selectOption('main');await page.getByLabel('PR 標題').fill(config.title);await page.getByLabel('PR 描述').fill('由真 CLI 雙 clone 分支建立，經網頁審核與合併。');await page.getByRole('button',{name:'建立 PR',exact:true}).click()
    await page.waitForURL(/\/pulls\/[^/?]+$/);const prId=new URL(page.url()).pathname.split('/').at(-1)
    await page.waitForSelector('.merge-layout');await page.waitForFunction(()=>window.__worldgit?.viewer?.stats.ready,{},{timeout:120000})
    if(config.conflict) {
      const selects=page.locator('select[aria-label^="區域 "]');assert.ok(await selects.count()>0)
      for(let i=0;i<await selects.count();i++) { await selects.nth(i).selectOption('theirs');await page.waitForFunction(()=>[...document.querySelectorAll('select[aria-label^="區域 "]')].every(e=>!e.disabled)) }
      await page.waitForFunction(()=>window.__worldgit?.viewer?.stats.ready,{},{timeout:120000});result.assertions.push('conflict choices saved on PR')
      const view=await context.request.get(`${config.hub}/api/v1/worlds/${config.owner}/${config.world}/pulls/${prId}`);const detail=await view.json();assert.ok(Object.values(detail.choices).every(c=>c==='theirs'));assert.equal(Object.keys(detail.choices).length,detail.preview.regions.length)
      await page.reload();await page.waitForFunction(()=>window.__worldgit?.viewer?.stats.ready,{},{timeout:120000});assert.equal(await page.locator('select[aria-label^="區域 "]').first().inputValue(),'theirs');result.assertions.push('choices persisted after reload')
    }
    await page.getByLabel('留言內容',{exact:true}).fill('<img src=x onerror=alert(1)> 屋頂請檢查')
    await page.getByLabel('釘選 X',{exact:true}).fill('3');await page.getByLabel('釘選 Y',{exact:true}).fill('65');await page.getByLabel('釘選 Z',{exact:true}).fill('3');await page.getByRole('button',{name:'送出留言',exact:true}).click();await page.waitForSelector('.comment-body')
    assert.match(await page.locator('.comment-body').first().textContent(),/<img src=x onerror=alert\(1\)>/);assert.equal(await page.locator('.comment-body img').count(),0)
    await page.getByRole('button',{name:/📍/}).first().click();const target=await page.evaluate(()=>window.__worldgit.viewer.getViewState());assert.equal(target.x,3.5);assert.equal(target.y,65.5);result.assertions.push('coordinate pin, camera focus, XSS text')
    await shot(config.label+'-pr')
    await page.getByRole('button',{name:'登出',exact:true}).click();await page.waitForURL(config.hub+'/');await login(config.reviewer);await goto(`/${config.owner}/${config.world}/pulls/${prId}`)
    await page.getByRole('button',{name:'核准',exact:true}).click();await page.getByRole('button',{name:'合併 PR',exact:true}).waitFor({state:'visible'});await page.waitForFunction(()=>[...document.querySelectorAll('button')].find(b=>b.textContent==='合併 PR')?.disabled===false)
    await page.getByRole('button',{name:'合併 PR',exact:true}).click();await page.getByText(/已合併 · 審核/).waitFor();await shot(config.label+'-merged');result.assertions.push('review gate and browser merge')
    result.prId=prId
    const response=await context.request.get(`${config.hub}/api/v1/worlds/${config.owner}/${config.world}/pulls/${prId}`);result.detail=await response.json();assert.equal(result.detail.pr.status,'merged')
  } else if(config.operation==='release') {
    await login(config.author);await goto(`/${config.owner}/${config.world}/releases`);await page.getByLabel('Release tag',{exact:true}).fill(config.tag);await page.getByLabel('Release 標題',{exact:true}).fill(config.title);await page.getByLabel('Release 說明',{exact:true}).fill('兩位使用者經 PR 合併後的全維度世界。');await page.getByRole('button',{name:'發布 Release',exact:true}).click();await page.waitForURL(/\/releases\/[^/?]+$/)
    const downloadPromise=page.waitForEvent('download',{timeout:120000});await page.getByRole('link',{name:'下載世界 ZIP',exact:true}).click();const download=await downloadPromise;assert.equal(await download.failure(),null);await download.saveAs(config.zip);result.zipBytes=fs.statSync(config.zip).size;assert.ok(result.zipBytes>100);await shot(config.label+'-release');result.assertions.push('browser release and streamed ZIP download')
  }
  result.violations.push(...await page.evaluate(()=>window.__cspViolations));assert.deepEqual(result.violations,[]);assert.deepEqual(result.errors,[])
} finally {
  await browser.close();fs.writeFileSync(config.result,JSON.stringify(result,null,2))
}
