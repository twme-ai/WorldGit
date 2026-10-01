import { clear } from './ui.ts'

export type Dispose = void | (() => void)
export interface Route {
  pattern: RegExp
  render: (params: string[], root: HTMLElement) => Dispose | Promise<Dispose>
}

const routes: Route[] = []
let current: (() => void) | null = null
let root: HTMLElement
let navToken = 0

export function addRoute(pattern: RegExp, render: Route['render']) { routes.push({ pattern, render }) }

export function startRouter(el: HTMLElement) {
  root = el
  window.addEventListener('popstate', () => void show())
  document.addEventListener('click', (e) => {
    const a = (e.target as HTMLElement).closest?.('a[data-link]') as HTMLAnchorElement | null
    if (!a || e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey) return
    e.preventDefault()
    navigate(a.getAttribute('href')!)
  })
  void show()
}

export function navigate(path: string, replace = false) {
  if (replace) history.replaceState(null, '', path); else history.pushState(null, '', path)
  void show()
}

async function show() {
  const token = ++navToken
  if (current) { try { current() } catch (e) { console.error(e) } current = null }
  clear(root)
  const path = location.pathname
  for (const r of routes) {
    const m = r.pattern.exec(path)
    if (!m) continue
    const d = await r.render(m.slice(1).map(decodeURIComponent), root)
    if (token !== navToken) { if (typeof d === 'function') d(); return }
    current = typeof d === 'function' ? d : null
    window.scrollTo(0, 0)
    return
  }
  root.append(Object.assign(document.createElement('p'), { className: 'empty', textContent: '找不到這個頁面。' }))
}
