// 四端共用的 diff 色票（來自 protocol 模組，經 /api/v1/diff-palettes）與使用者偏好（一般／色盲）。
import { api, type Palette, type Palettes } from './api.ts'

let cache: Promise<Palettes> | null = null
const KEY = 'worldgit.palette'

export function loadPalettes(): Promise<Palettes> {
  cache ??= api.palettes()
  return cache
}

export function paletteChoice(): 'default' | 'colorblind' {
  try { return localStorage.getItem(KEY) === 'colorblind' ? 'colorblind' : 'default' } catch { return 'default' }
}

export function setPaletteChoice(c: 'default' | 'colorblind') {
  try { localStorage.setItem(KEY, c) } catch { /* 沒有 localStorage 就只在本頁有效 */ }
}

/** 取得目前偏好的色票，並同步 CSS 變數（--add／--rem／--mod／--con）。 */
export async function activePalette(): Promise<Palette> {
  const all = await loadPalettes()
  const p = all[paletteChoice()]
  const s = document.documentElement.style
  s.setProperty('--add', p.added); s.setProperty('--rem', p.removed); s.setProperty('--mod', p.modified); s.setProperty('--con', p.conflict)
  return p
}
