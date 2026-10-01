import { ApiError, api, dimLabel, dimRepo, type CommitDetail, type Palettes } from '../api.ts'
import { activePalette, loadPalettes, paletteChoice, setPaletteChoice } from '../palette.ts'
import { navigate } from '../router.ts'
import { append, clear, fmtFull, fmtNum, h, link, short } from '../ui.ts'
import { Viewer, type PickInfo, type ViewerStats } from '../viewer/viewer.ts'
import { statSpans } from './shared.ts'

const put = (el: Element, ...c: Parameters<typeof append>[1]) => append(el, c)

declare global { interface Window { __worldgit?: { viewer: Viewer | null } } }

/** 單一 commit：統計、3D 檢視與 diff 上色（新增綠、移除紅鬼影、修改黃；衝突紫預留）。 */
export async function commitPage(root: HTMLElement, owner: string, world: string, dimRepoName: string, rev: string): Promise<() => void> {
  let viewer: Viewer | null = null
  const dispose = () => { viewer?.dispose(); viewer = null; if (window.__worldgit) window.__worldgit.viewer = null }
  let detail: CommitDetail
  try { detail = await api.commit(owner, world, dimRepoName, rev) } catch (e) {
    root.append(h('p', { class: 'empty' }, String(e instanceof ApiError ? e.message : e)))
    return dispose
  }
  const palettes = await loadPalettes()
  let palette = await activePalette()
  const c = detail.commit
  const params = new URLSearchParams(location.search)

  const hud = h('div', { class: 'overlay hud' }, '載入中…')
  const pick = h('div', { class: 'overlay pick', style: 'display:none' })
  const legend = h('div', { class: 'overlay legend' })
  const canvas = h('canvas', {})
  const box = h('div', { class: 'viewer' }, canvas, hud, pick, legend)

  const renderLegend = (pal: Palettes, p = palette) => {
    legend.replaceChildren(...(['added', 'removed', 'modified', 'conflict'] as const).map((k) => {
      const style = { added: 'solid', removed: 'ghost', modified: 'corner', conflict: 'pulse' }[k]
      const label = { added: '新增', removed: '移除（鬼影）', modified: '修改（角標）', conflict: '衝突（閃爍）' }[k]
      return h('span', { class: 'item', style: `color:${p[k]}` }, h('span', { class: `sw ${style}`, style: `background:${p[k]}` }), `${pal.symbols[k]} ${label}`)
    }))
  }
  renderLegend(palettes)

  const mode = { value: 'color' as 'color' | 'changed' | 'dim' }
  const seg = <T extends string>(items: [T, string][], get: () => T, set: (v: T) => void) => {
    const wrap = h('span', { class: 'seg' })
    const paint = () => wrap.replaceChildren(...items.map(([v, label]) => h('button', { class: get() === v ? 'active' : '', onClick: () => { set(v); paint() } }, label)))
    paint()
    return wrap
  }

  const toolbar = h('div', { class: 'toolbar' },
    seg([['color', '上色'], ['changed', '只看變動'], ['dim', '淡化未變動']], () => mode.value, (v) => { mode.value = v; viewer?.setMode(v) }),
    seg([['orbit', '環繞'], ['fly', '飛行']], () => viewer?.camera.mode ?? 'orbit', (v) => viewer?.setCameraMode(v)),
    seg([['default', '一般色票'], ['colorblind', '色盲色票']], () => paletteChoice(), (v) => { setPaletteChoice(v); void activePalette().then((p) => { palette = p; viewer?.setPalette(p); renderLegend(palettes, p) }) }),
    h('label', { style: 'display:inline-flex;gap:6px;align-items:center;margin:0' }, '細節半徑',
      h('select', { onChange: (e: Event) => viewer?.setRadius(Number((e.target as HTMLSelectElement).value)) },
        ...[3, 4, 6, 8, 10].map((r) => h('option', { value: r, selected: r === 6 }, `${r} chunk`)))),
    h('span', { class: 'small muted' }, 'WASD / QE 移動 · 左鍵旋轉 · 右鍵或 Shift 平移 · 滾輪縮放 · 點方塊看資訊'))

  const chunkRows = [...detail.changedChunks].sort((a, b) => (b[2] + b[3] + b[4]) - (a[2] + a[3] + a[4])).slice(0, 300)
  const chunkList = h('div', { class: 'chunklist' }, ...chunkRows.map((k) => h('div', { onClick: () => viewer?.flyToChunk(k[0], k[1]) },
    h('span', {}, `(${k[0]}, ${k[1]})`), h('span', { class: 'stat add' }, `+${k[2]}`), h('span', { class: 'stat rem' }, `-${k[3]}`), h('span', { class: 'stat mod' }, `~${k[4]}`), k[5] & 1 ? h('span', {}, '⚑') : null)))

  const dimName = dimLabel(c.dimension)
  put(root,
    h('div', { class: 'crumbs' }, link('/', '世界'), ' / ', link(`/${owner}/${world}`, `${owner}/${world}`), ' / ', link(`/${owner}/${world}/commits`, 'commits'), ' / ', h('b', {}, `${dimName} ${short(c.id)}`)),
    h('h1', {}, c.message.split('\n')[0] || '(無訊息)', c.auto ? h('span', { class: 'badge' }, '自動') : null),
    h('div', { class: 'row small muted' },
      `${c.author.name} · ${fmtFull(c.time)} · ${c.source} · ${dimName}`, h('code', {}, c.id),
      detail.parent ? link(`/${owner}/${world}/commit/${dimRepoName}/${short(detail.parent)}`, `← 上一個 ${short(detail.parent)}`) : h('span', {}, '（初始快照）'),
      h('span', {}, `DataVersion ${c.dataVersion}（資源 ${detail.mcVersion}）`)),
    h('div', { class: 'row', style: 'margin:10px 0' }, statSpans(detail),
      detail.initial ? null : h('span', { class: 'stat muted' }, `${fmtNum(detail.chunkCount)} 個 chunk · ${fmtNum(detail.sectionCount)} 個 section`)),
    detail.initial ? h('p', { class: 'small muted' }, '初始快照沒有上一版可比較，直接顯示完整世界；之後的 commit 會顯示與上一版的差異。') : null,
    toolbar, box,
    h('div', { class: 'cols', style: 'margin-top:16px' },
      h('div', { class: 'card' }, h('h2', {}, detail.initial ? `chunks（${fmtNum(detail.chunkCount)}）` : `變動的 chunk（${fmtNum(detail.chunkCount)}）`),
        chunkRows.length ? chunkList : h('p', { class: 'muted' }, '沒有方塊變動。'),
        detail.changedChunks.length > 300 ? h('p', { class: 'small muted' }, '只列出變動最多的 300 個。') : null),
      h('div', { class: 'card' }, h('h2', {}, '實體與其他'),
        detail.entityChanges.length ? h('div', { class: 'chunklist' }, ...detail.entityChanges.slice(0, 100).map((e) => h('div', {},
          h('span', { class: e.kind === 'added' ? 'stat add' : e.kind === 'removed' ? 'stat rem' : 'stat mod' }, e.kind === 'added' ? '+' : e.kind === 'removed' ? '-' : '~'),
          h('span', {}, e.type.replace('minecraft:', '')), h('span', { class: 'muted' }, e.after ? e.after.map((v) => v.toFixed(1)).join(', ') : e.before ? e.before.map((v) => v.toFixed(1)).join(', ') : ''))))
          : h('p', { class: 'muted' }, '沒有實體變動。'),
        detail.metadataChanges.length ? h('p', { class: 'small muted' }, `世界資料變動：${detail.metadataChanges.join('、')}`) : null)))

  // ---- 啟動檢視器 ----
  try {
    viewer = new Viewer({ canvas, owner, world, dimRepo: dimRepoName, detail, palette })
  } catch (e) {
    hud.textContent = String(e)
    return dispose
  }
  window.__worldgit = { viewer }
  viewer.onStats = (s: ViewerStats) => {
    hud.textContent = `${s.ready ? '就緒' : '載入中…'}  chunks ${s.chunks} · sections ${s.sectionsMeshed} · LOD ${s.lodRegions}\n` +
      `GPU ${s.gpuMB.toFixed(1)} MB · 繪製 ${s.drawnSections} sec / ${fmtNum(s.drawnQuads)} quads · mesh ${s.meshMsAvg.toFixed(1)} ms\n${s.renderer}`
  }
  viewer.onPick = (p: PickInfo | null) => {
    if (!p) { pick.style.display = 'none'; return }
    pick.style.display = ''
    const kindText = { same: '無變動', added: '+ 新增', removed: '- 移除', modified: '~ 修改', conflict: '! 衝突' }[p.kind]
    clear(pick)
    put(pick, h('div', {}, h('b', {}, p.state.replace('minecraft:', ''))),
      h('div', { class: 'mono' }, `${p.x}, ${p.y}, ${p.z}`), h('div', {}, kindText),
      p.before ? h('div', {}, '舊：', h('code', {}, p.before.replace('minecraft:', ''))) : null,
      p.kind === 'removed' ? h('div', {}, '（上一版有這個方塊，現在已移除）') : null,
      p.biome ? h('div', { class: 'muted' }, p.biome) : null, p.blockEntity ? h('pre', { class: 'mono', style: 'white-space:pre-wrap;margin:4px 0 0' }, p.blockEntity) : null)
  }
  viewer.onViewChange = (v) => {
    const q = new URLSearchParams(location.search)
    q.set('cam', [v.x, v.y, v.z, v.yaw, v.pitch, v.dist].map((n) => +n.toFixed(2)).join(',') + (v.mode === 'fly' ? ',f' : ',o'))
    q.delete('x'); q.delete('z')
    history.replaceState(null, '', `${location.pathname}?${q}`)
  }
  viewer.onError = (m) => { hud.textContent = `錯誤：${m}`; console.error(m) }
  const cam = params.get('cam')?.split(',')
  if (cam && cam.length >= 6) {
    const n = cam.slice(0, 6).map(Number)
    viewer.applyViewState({ x: n[0], y: n[1], z: n[2], yaw: n[3], pitch: n[4], dist: n[5], mode: cam[6] === 'f' ? 'fly' : 'orbit' })
  } else if (params.has('x') && params.has('z')) {
    viewer.camera.target = [Number(params.get('x')), 64, Number(params.get('z'))]
    viewer.camera.dist = 50
  }
  void viewer.start().catch((e) => { hud.textContent = `無法啟動檢視器：${e}` })
  void navigate
  return dispose
}
