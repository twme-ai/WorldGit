import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import {chromium} from 'playwright-core'
const config=JSON.parse(fs.readFileSync(process.argv[2],'utf8'))
fs.mkdirSync(config.out,{recursive:true})
const result={assertions:[],screenshots:[],errors:[],violations:[],operationTransports:[]}
const fontConfig=path.resolve('.work/fonts/fonts.conf')
const browser=await chromium.launch({executablePath:process.env.CHROME??'/usr/bin/google-chrome',headless:true,args:['--no-sandbox','--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader'],env:{...process.env,...(fs.existsSync(fontConfig)?{FONTCONFIG_FILE:fontConfig}:{})}})
try {
  const context=await browser.newContext({viewport:{width:1440,height:1000},acceptDownloads:true,colorScheme:'light'})
  const font=path.resolve('.work/viewer-scale/fonts/NotoSansCJKtc-Regular.otf')
  if(fs.existsSync(font)){
    await context.route('**/__shot-font.otf',r=>r.fulfill({body:fs.readFileSync(font),contentType:'font/otf'}))
    await context.route('**/assets/*.css',async r=>{const original=await r.fetch();await r.fulfill({response:original,body:await original.text()+'\n@font-face{font-family:ShotCJK;src:url(/__shot-font.otf)}html,body,button,input,textarea,select{font-family:ShotCJK,system-ui,sans-serif}'})})
  }
  await context.addInitScript(()=>{window.__violations=[];window.addEventListener('securitypolicyviolation',e=>window.__violations.push(e.violatedDirective));Object.defineProperty(navigator,'clipboard',{value:undefined,configurable:true})})
  const page=await context.newPage();page.on('pageerror',e=>result.errors.push(String(e)))
  page.on('request',r=>{if(/\/operations\/.+\/events$/.test(r.url()))result.operationTransports.push('SSE');else if(/\/operations\/[\w-]+$/.test(r.url()) && r.method()==='GET')result.operationTransports.push('poll')})
  const goto=async p=>{const response=await page.goto(config.hub+p);assert.equal(response.status(),200);assert.match(response.headers()['content-security-policy'],/script-src 'self'/)}
  const shot=async name=>{await page.evaluate(()=>document.fonts.ready);await page.screenshot({path:path.join(config.out,name+'.png'),fullPage:true});result.screenshots.push(name+'.png')}
  await goto('/login');await page.locator('input[name=username]').fill(config.username);await page.locator('input[name=password]').fill(config.password);await page.getByRole('button',{name:'登入',exact:true}).click();await page.waitForURL(config.hub+'/')
  const root=`/${config.owner}/${config.world}`,nether='minecraft.the_nether'
  await goto(`${root}/graph/${nether}`);await page.locator('.graph-list li').first().waitFor();assert.ok(await page.getByRole('link',{name:/PR #/}).count());assert.ok(await page.locator('svg circle').count());assert.ok(await page.locator('.graph-label.tag').count())
  await page.evaluate(()=>document.documentElement.dataset.theme='light');await shot('graph-light')
  await page.getByRole('button',{name:'切換亮／暗色'}).click();await shot('graph-dark')
  await page.setViewportSize({width:390,height:844});await shot('graph-mobile');assert.ok(await page.locator('.graph-scroll').evaluate(e=>e.scrollWidth>e.clientWidth));await page.locator('.graph-scroll').focus();await page.keyboard.press('ArrowRight');assert.equal(await page.locator('.graph-scroll').getAttribute('tabindex'),'0')
  await page.setViewportSize({width:1440,height:1000});await page.getByLabel('預設分支',{exact:true}).selectOption('cavern');await page.getByRole('button',{name:'設定此維度預設分支'}).click();await page.locator('.toast.info').filter({hasText:'成功'}).first().waitFor();await shot('success-notification')
  result.assertions.push('dimension graph, core lanes, PR link, keyboard, mobile scroll, light/dark, success notification')
  await goto(`${root}/pulls?dim=minecraft%3Athe_nether`);  await page.getByLabel('維度',{exact:true}).waitFor();assert.equal(await page.getByLabel('維度',{exact:true}).inputValue(),'minecraft:the_nether');await shot('pr-dimension-selector')
  const sources=await page.getByLabel('來源分支',{exact:true}).locator('option').allTextContents();assert.ok(!sources.includes('surface'));result.assertions.push('PR dimension selector separates branches')
  await goto(`${root}/releases/${config.release}`);const download=page.waitForEvent('download',{timeout:90000});await page.getByRole('link',{name:'下載世界 ZIP',exact:true}).click();await page.locator('.operation-panel').first().waitFor();await page.locator('.operation-panel p').filter({hasText:/minecraft:/}).first().waitFor();assert.ok(await page.locator('.operation-panel progress').count());await shot('operation-progress');const completed=await download;assert.equal(await completed.failure(),null);assert.ok(result.operationTransports.includes('SSE'))
  result.assertions.push('release ZIP real operation, progress, SSE, browser download')
  await context.route('**/operations/*/events',r=>r.abort());await goto(`${root}/releases/${config.release}`);const fallback=page.waitForEvent('download',{timeout:90000});await page.getByRole('link',{name:'下載世界 ZIP',exact:true}).click();assert.equal(await(await fallback).failure(),null);assert.ok(result.operationTransports.includes('poll'));result.assertions.push('SSE failure falls back to bounded polling')
  await goto(`${root}/graph/${nether}?limit=10001`);await page.locator('.error-banner').waitFor();await shot('error-notification-copy')
  await page.locator('.error-banner').getByRole('button',{name:'複製',exact:true}).click();await page.locator('.copy-fallback textarea').waitFor();const text=await page.locator('.copy-fallback textarea').inputValue();assert.match(text,/code=bad-request/);assert.match(text,/UTC=/);assert.match(text,/WorldGit=/);assert.ok(await page.locator('.copy-fallback textarea').evaluate(e=>e.selectionEnd-e.selectionStart>0));await shot('copy-fallback')
  result.assertions.push('real API error, copy button, selected-text clipboard fallback, aria-live closeable notifications')
  assert.ok(await page.locator('.toast[aria-live]').count());assert.ok(await page.locator('.toast button[aria-label="關閉通知"]').count())
  result.violations.push(...await page.evaluate(()=>window.__violations));assert.deepEqual(result.violations,[]);assert.deepEqual(result.errors,[])
  console.log('Phase 5 網頁／SSE／輪詢／可複製通知／截圖 PASS')
}finally {await browser.close();fs.writeFileSync(config.result,JSON.stringify(result,null,2))}
