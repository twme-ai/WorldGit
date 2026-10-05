import { browserReport, resultMessage, type ErrorReport, type Result } from './outcome.ts'
// 極小的 DOM 輔助與格式化（不使用框架）。
type Child = Node | string | number | null | undefined | false
type Attrs = Record<string, string | number | boolean | EventListener | null | undefined>

export function h<K extends keyof HTMLElementTagNameMap>(tag: K, attrs: Attrs = {}, ...children: Child[]): HTMLElementTagNameMap[K] {
  const el = document.createElement(tag)
  for (const [k, v] of Object.entries(attrs)) {
    if (v === null || v === undefined || v === false) continue
    if (k.startsWith('on') && typeof v === 'function') el.addEventListener(k.slice(2).toLowerCase(), v as EventListener)
    else if (k === 'class') el.className = String(v)
    else if (v === true) el.setAttribute(k, '')
    else el.setAttribute(k, String(v))
  }
  append(el, children)
  return el
}

export function append(el: Element, children: Child[]) {
  for (const c of children) {
    if (c === null || c === undefined || c === false) continue
    el.append(typeof c === 'object' ? c : String(c))
  }
}

export function clear(el: Element) { while (el.firstChild) el.removeChild(el.firstChild) }

/** 站內連結：攔截點擊改用 History API。 */
export function link(href: string, text: Child | Child[], cls = ''): HTMLAnchorElement {
  const a = h('a', { href, class: cls, 'data-link': true })
  append(a, Array.isArray(text) ? text : [text])
  return a
}

export function fmtTime(ms: number): string {
  const d = new Date(ms), diff = Date.now() - ms
  const rtf = new Intl.RelativeTimeFormat('zh-TW', { numeric: 'auto' })
  const mins = Math.round(diff / 60000)
  if (mins < 1) return '剛剛'
  if (mins < 60) return rtf.format(-mins, 'minute')
  if (mins < 60 * 24) return rtf.format(-Math.round(mins / 60), 'hour')
  if (mins < 60 * 24 * 14) return rtf.format(-Math.round(mins / 60 / 24), 'day')
  return d.toLocaleDateString('zh-TW')
}
export const fmtFull = (ms: number) => new Date(ms).toLocaleString('zh-TW', { hour12: false })
export const fmtNum = (n: number) => n.toLocaleString('en-US')
export const short = (id: string) => id.slice(0, 7)

const shown = new Set<string>()
export function notifyResult(result: Result) {
  if(shown.has(result.operationId))return
  if(shown.size>256)shown.delete(shown.values().next().value!)
  shown.add(result.operationId)
  toast(resultMessage(result), ['FAILED','PARTIAL'].includes(result.status)?'error':'info', result.error?.text)
}
export function toast(msg: string, kind: 'info' | 'error' = 'info', report?: string) {
  const t = h('div', { class: `toast ${kind}`, role: kind==='error'?'alert':'status', 'aria-live':kind==='error'?'assertive':'polite', 'aria-atomic':'true' },h('span',{},msg))
  if(kind==='error')t.append(h('button',{onClick:()=>void copyText(report ?? browserReport(msg)), 'aria-label':'複製錯誤報告'},'複製'))
  t.append(h('button',{'aria-label':'關閉通知',onClick:()=>t.remove()},'關閉'))
  let host=document.querySelector<HTMLElement>('.toasts')
  if(!host){host=h('div',{class:'toasts'});document.body.append(host)}
  while(host.children.length>=6)host.firstElementChild?.remove()
  host.append(t)
  if(kind!=='error')setTimeout(()=>t.remove(),10000)
  return t
}
const errorsShown=new WeakSet<object>()
export function showError(error: unknown) {
  if(error && typeof error==='object'){if(errorsShown.has(error))return;errorsShown.add(error)}
  const reportId=(error as {report?:ErrorReport})?.report?.operationId
  if(reportId && shown.has(reportId))return
  if(reportId)shown.add(reportId)
  const value=error as {message?:string;report?:ErrorReport}
  toast(value?.message ?? String(error),'error',value?.report?.text)
}
export function errorBanner(error: unknown) {
  const value=error as {message?:string;report?:ErrorReport},message=value?.message ?? String(error)
  return h('div',{class:'err error-banner',role:'alert','aria-live':'assertive'},h('p',{},message),h('button',{onClick:()=>void copyText(value?.report?.text ?? browserReport(message))},'複製'),h('button',{onClick:(e:Event)=>(e.currentTarget as HTMLElement).parentElement?.remove()},'關閉'))
}
export async function copyText(text: string) {
  try { if(!navigator.clipboard)throw new Error('clipboard');await navigator.clipboard.writeText(text);toast('已複製') }
  catch {
    const area=h('textarea',{readonly:true,'aria-label':'請選取並複製文字'},text),box=h('div',{class:'copy-fallback',role:'dialog','aria-label':'手動複製'},h('p',{},'請使用 Ctrl/Cmd+C 複製選取文字。'),area,h('button',{onClick:()=>box.remove()},'關閉'))
    document.body.append(box);area.focus();area.select()
  }
}

/** diff 符號與色：色票來自 /api/v1/diff-palettes（protocol 模組），不在前端寫死。 */
export const SYMBOL = { added: '+', removed: '-', modified: '~', conflict: '!' } as const
