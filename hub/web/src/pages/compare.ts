import { api, dimLabel, type CompareDimension } from '../api.ts'
import { comparePath, dimensionDetail, parseCompareSpec, pickDimension, viewRenderMode, type CompareView } from '../compare.ts'
import { activePalette, loadPalettes, paletteChoice, setPaletteChoice } from '../palette.ts'
import { navigate } from '../router.ts'
import { fmtFull, fmtNum, h, link, short } from '../ui.ts'
import { Viewer, type ViewState } from '../viewer/viewer.ts'
import { compareStats, renderLegend, renderPick, segmented } from './shared.ts'

/** a→b 比較；前／後用實際 commit 的 chunk、實體及 LOD，保持同一鏡頭。 */
export async function comparePage(root: HTMLElement, owner: string, world: string, spec: string): Promise<() => void> {
  let viewer: Viewer | null = null, disposed = false, generation = 0
  const dispose = () => { disposed = true; generation++; viewer?.dispose(); viewer = null; if (window.__worldgit) window.__worldgit.viewer = null }
  const pair = parseCompareSpec(spec)
  if (!pair) { root.append(h('p', { class: 'empty' }, '比較網址格式應為 a...b。')); return dispose }
  const loading = h('p', { class: 'empty' }, '載入比較…')
  root.append(loading)
  try {
    const result = await api.compare(owner, world, pair.a, pair.b)
    const palettes = await loadPalettes()
    let palette = await activePalette()
    const params = new URLSearchParams(location.search)
    let selected = pickDimension(result, params.get('dim'))
    let view: CompareView = ['color', 'changed', 'before', 'after'].includes(params.get('view') ?? '') ? params.get('view') as CompareView : 'color'
    const camParts = params.get('cam')?.split(',')
    const nums = camParts?.slice(0, 6).map(Number)
    let camera: ViewState | undefined = nums?.length === 6 && nums.every(Number.isFinite)
      ? { x: nums[0], y: nums[1], z: nums[2], yaw: nums[3], pitch: nums[4], dist: nums[5], mode: camParts?.[6] === 'f' ? 'fly' : 'orbit' } : undefined
    let radius = 6
    const tabs = h('div', { class: 'tabs' })
    const content = h('div', {})
    const choices = h('datalist', { id: 'compare-branches' })
    void api.branches(owner, world).then(p => choices.replaceChildren(...p.branches.map(b => h('option', { value: b.name })))).catch(() => {})
    const from = h('input', { value: pair.a, list: 'compare-branches', 'aria-label': '比較起點', required: true, maxlength: 100 })
    const to = h('input', { value: pair.b, list: 'compare-branches', 'aria-label': '比較終點', required: true, maxlength: 100 })
    const form = h('form', { class: 'row compare-form', onSubmit: (e: Event) => { e.preventDefault(); navigate(comparePath(owner, world, from.value.trim(), to.value.trim(), selected?.dimension)) } },
      h('label', {}, '起點 a', from), h('span', {}, '→'), h('label', {}, '終點 b', to), h('button', { type: 'submit' }, '比較'), choices,
      h('button', { type: 'button', onClick: () => navigate(comparePath(owner, world, pair.b, pair.a, selected?.dimension)) }, '交換前後'))
    root.replaceChildren(h('div', {}, h('div', { class: 'crumbs' }, link('/', '世界'), ' / ', link(`/${owner}/${world}`, `${owner}/${world}`), ' / ', link(`/${owner}/${world}/branches`, '分支'), ' / 比較'),
      h('h1', {}, `${pair.a} → ${pair.b}`), form,
      h('div', { class: 'row small muted' }, `${result.a.author.name} · ${fmtFull(result.a.time)} → ${result.b.author.name} · ${fmtFull(result.b.time)}`),
      h('div', { class: 'row compare-summary' }, compareStats(result), result.identical ? h('span', { class: 'badge' }, '內容相同') : null),
      result.dimensions.some(d => !d.a || !d.b) ? h('p', { class: 'notice' }, '部分維度缺少配對 commit，未計入總計；選擇該維度可查看可用版本。') : null,
      tabs, content))

    const saveURL = () => {
      const q = new URLSearchParams()
      if (selected) q.set('dim', selected.dimension)
      q.set('view', view)
      if (camera) q.set('cam', [camera.x, camera.y, camera.z, camera.yaw, camera.pitch, camera.dist].map(n => +n.toFixed(2)).join(',') + (camera.mode === 'fly' ? ',f' : ',o'))
      history.replaceState(null, '', `${comparePath(owner, world, pair.a, pair.b)}?${q}`)
    }
    const show = (d: CompareDimension) => {
      camera = viewer?.getViewState() ?? camera
      viewer?.dispose(); viewer = null; window.__worldgit = { viewer: null }
      const current = ++generation
      selected = d
      tabs.replaceChildren(...result.dimensions.map(x => h('button', { class: `tab ${x === d ? 'active' : ''}`, onClick: () => { camera = undefined; show(x) } },
        dimLabel(x.dimension), h('span', { class: 'badge' }, x.status === 'same' ? '相同' : !x.a || !x.b ? '缺少端點' : `${fmtNum(x.chunkCount)} chunks`))))
      const canvas = h('canvas', { 'aria-label': '兩個版本的 3D 比較' })
      const hud = h('div', { class: 'overlay hud', role: 'status' }, '載入中…')
      const pick = h('div', { class: 'overlay pick', style: 'display:none' })
      const legend = h('div', { class: 'overlay legend' })
      renderLegend(legend, palettes, palette)
      legend.hidden = view === 'before' || view === 'after'
      const toolbar = h('div', { class: 'toolbar' },
        segmented<CompareView>([['color', '上色疊圖'], ['changed', '只看變動'], ['before', '前（a）'], ['after', '後（b）']], () => view, v => {
          const old = view; view = v
          if ((old === 'color' || old === 'changed') && (v === 'color' || v === 'changed')) { viewer?.setMode(viewRenderMode(v)); saveURL() }
          else show(d)
        }),
        segmented([['orbit', '環繞'], ['fly', '飛行']], () => viewer?.camera.mode ?? 'orbit', v => viewer?.setCameraMode(v)),
        segmented([['default', '一般色票'], ['colorblind', '色盲色票']], () => paletteChoice(), v => {
          setPaletteChoice(v); void activePalette().then(p => { palette = p; viewer?.setPalette(p); renderLegend(legend, palettes, p) })
        }),
        h('label', { class: 'radius-select' }, '細節半徑', h('select', { 'aria-label': '細節半徑', onChange: (e: Event) => { radius = Number((e.target as HTMLSelectElement).value); viewer?.setRadius(radius) } },
          ...[3, 4, 6, 8, 10].map(r => h('option', { value: r, selected: r === radius }, `${r} chunk`)))),
        h('span', { class: 'small muted' }, 'WASD / QE · 左鍵旋轉 · 右鍵平移 · 點方塊看資訊'))
      const list = h('div', { class: 'chunklist' })
      const chunkRows = d.changedChunks.slice(0, 300)
      for (const c of chunkRows) list.append(h('button', { class: 'chunk-row', onClick: () => viewer?.flyToChunk(c[0], c[1]) },
        `(${c[0]}, ${c[1]})`, h('span', { class: 'stat add' }, `+${fmtNum(c[2])}`), h('span', { class: 'stat rem' }, `-${fmtNum(c[3])}`), h('span', { class: 'stat mod' }, `~${fmtNum(c[4])}`), c[5] & 1 ? '實體' : '', c[5] & 2 ? 'biome' : ''))
      const sectionList = h('details', {}, h('summary', {}, `Section 統計（${fmtNum(d.sectionCount)}）`),
        h('div', { class: 'chunklist' }, ...d.changedSections.slice(0, 300).map(s => h('div', {}, `(${s[0]}, ${s[1]}) · Y=${s[2]}`, h('span', { class: 'stat add' }, `+${s[3]}`), h('span', { class: 'stat rem' }, `-${s[4]}`), h('span', { class: 'stat mod' }, `~${s[5]}`)))))
      content.replaceChildren(h('div', {}, h('div', { class: 'row small muted compare-summary' },
        d.a ? link(`/${owner}/${world}/commit/${d.repo}/${d.a.id}`, `a ${short(d.a.id)}`) : 'a 缺少 commit', ' → ',
        d.b ? link(`/${owner}/${world}/commit/${d.repo}/${d.b.id}`, `b ${short(d.b.id)}`) : 'b 缺少 commit', compareStats(d), `${fmtNum(d.sectionCount)} sections`),
        !d.a || !d.b ? h('p', { class: 'notice' }, '缺少另一端的 commit；此維度無法計算差異。使用前／後按鈕查看可用版本。') : null,
        d.status === 'same' ? h('p', { class: 'small muted' }, '兩端內容相同。前／後切換仍可瀏覽世界。') : null,
        toolbar, h('div', { class: 'viewer' }, canvas, hud, pick, legend),
        h('div', { class: 'cols compare-summary' }, h('div', { class: 'card' }, h('h2', {}, `變動的 chunk（${fmtNum(d.chunkCount)}）`), list,
          d.chunksTruncated || d.changedChunks.length > 300 ? h('p', { class: 'small muted' }, '此頁最多顯示 300 個 chunk；API 清單全維度合計最多 2000 個，統計與範圍仍涵蓋全部變動。') : null, sectionList,
          d.sectionsTruncated || d.changedSections.length > 300 ? h('p', { class: 'small muted' }, '此頁最多顯示 300 個 section；API 合計上限 6000 個。') : null),
          h('div', { class: 'card' }, h('h2', {}, '實體與其他'),
            h('p', {}, `實體 +${d.entitiesAdded} / -${d.entitiesRemoved} / ~${d.entitiesModified}`),
            ...d.entityChanges.slice(0, 100).map(e => h('div', { class: 'small' }, `${e.kind} · ${e.type} · ${(e.after ?? e.before ?? []).map(n => n.toFixed(1)).join(', ')}`)),
            ...d.metadataChanges.map(m => h('p', { class: 'small muted' }, m))))))
      saveURL()
      const detail = dimensionDetail(d)
      const commit = view === 'before' ? d.a : d.b
      const mc = view === 'before' ? d.beforeMcVersion : d.mcVersion
      if (!commit || !mc) { hud.textContent = '這一端沒有 commit。'; return }
      // 純世界模式保留比較的焦點，使用各自資源與內容；上色才載入 a→b diff。
      const display = detail ?? { commit, parent: null, initial: false, added: 0, removed: 0, modified: 0, chunkCount: 1, sectionCount: 0,
        entitiesAdded: 0, entitiesRemoved: 0, entitiesModified: 0, entityChanges: [], changedChunks: [], bounds: [0, 0, 0, 0], metadataChanges: [], mcVersion: mc }
      try {
        viewer = new Viewer({ canvas, owner, world, dimRepo: d.repo, detail: { ...display, commit, mcVersion: mc, chunkCount: Math.max(1, d.chunkCount) },
          palette, radius, base: d.a?.id, showDiff: !!detail && (view === 'color' || view === 'changed') })
        window.__worldgit = { viewer }
        viewer.setMode(viewRenderMode(view))
        viewer.onPick = p => renderPick(pick, p)
        viewer.onStats = s => { hud.textContent = `${s.ready ? '就緒' : '載入中…'} · ${dimLabel(d.dimension)} · ${view === 'before' ? '前 a' : view === 'after' ? '後 b' : 'a → b'}\nchunks ${s.chunks} · sections ${s.sectionsMeshed} · LOD ${s.lodRegions} · GPU ${s.gpuMB.toFixed(1)} MB` }
        viewer.onViewChange = v => { camera = v; saveURL() }
        viewer.onError = m => { if (current === generation && !disposed) hud.textContent = `錯誤：${m}`; console.error(m) }
        if (camera) viewer.applyViewState(camera)
        void viewer.start().catch(e => { if (current === generation && !disposed) hud.textContent = `無法啟動檢視器：${e}` })
      } catch (e) { hud.textContent = String(e) }
    }
    if (selected) show(selected)
  } catch (e) { loading.textContent = String(e instanceof Error ? e.message : e) }
  return dispose
}
