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

export function toast(msg: string, kind: 'info' | 'error' = 'info') {
  const t = h('div', { class: `toast ${kind}`, role: 'status' }, msg)
  document.body.append(t)
  setTimeout(() => t.remove(), kind === 'error' ? 6000 : 3000)
}

export async function copyText(text: string) {
  try { await navigator.clipboard.writeText(text); toast('已複製') } catch { toast('無法複製，請手動選取', 'error') }
}

/** diff 符號與色：色票來自 /api/v1/diff-palettes（protocol 模組），不在前端寫死。 */
export const SYMBOL = { added: '+', removed: '-', modified: '~', conflict: '!' } as const
