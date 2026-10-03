import { dimLabel, getJson } from '../api.ts'
import { encodeChoices, mergePath, parseMergeSpec, previewCommit, readChoices, selectionSummary, sortedRegions, type Choice, type MergeDimension, type MergeReport, type MergeSummary, type MergeView, type Region } from '../merge.ts'
import { activePalette, loadPalettes, paletteChoice, setPaletteChoice } from '../palette.ts'
import { fmtNum, h, link, toast } from '../ui.ts'
import { Viewer, type ViewState } from '../viewer/viewer.ts'
import { renderLegend, renderPick, segmented } from './shared.ts'

export interface PullPreview {
  report: MergeReport
  choices: Record<number, Choice>
  onChoices: (choices: Record<number, Choice>) => Promise<void>
  onPick: (p: { x: number; y: number; z: number } | null, dimension: string) => void
  onViewer: (viewer: Viewer, dimension: string) => void
}
export async function mergePage(root: HTMLElement, owner: string, world: string, spec: string, pull?: PullPreview): Promise<() => void> {
  let viewer: Viewer | null = null, disposed = false, generation = 0
  const dispose = () => { disposed = true; generation++; viewer?.dispose(); viewer = null; if (window.__worldgit) window.__worldgit.viewer = null }
  const loading = h('p', { class: 'empty' }, '計算合併預覽…'); root.append(loading)
  const pair = parseMergeSpec(spec)
  if (!pair) { loading.textContent = '網址需為 ours...theirs。'; return dispose }
  const endpoint = `/api/v1/worlds/${owner}/${world}/merge-preview`
  const source = new URLSearchParams({ ours: pair.a, theirs: pair.b })
  try {
    const report = pull?.report ?? await getJson<MergeReport>(`${endpoint}?${source}`)
    const palettes = await loadPalettes(); let palette = await activePalette()
    if (disposed) return dispose
    const params = new URLSearchParams(location.search)
    const choices = pull ? new Map<number, Choice>(Object.entries(pull.choices).map(([id, value]) => [Number(id), value])) : readChoices(params.get('choices'), params.get('tips'), report)
    let mode: MergeView = pull ? 'selected' : ['auto', 'ours', 'theirs', 'base', 'selected'].includes(params.get('view') ?? '') ? params.get('view') as MergeView : 'auto'
    let dim = report.dimensions.find(d => d.dimension === params.get('dim')) ?? report.dimensions.find(d => d.dimension === 'minecraft:overworld') ?? report.dimensions[0]
    let selected = report.regions.find(r => r.id === Number(params.get('region'))) ?? report.regions.find(r => r.dimension === dim?.dimension)
    let filter = params.get('filter') ?? '', sort = params.get('sort') ?? 'id'
    let camera: ViewState | undefined
    const cam = params.get('cam')?.split(',').map(Number)
    if (cam?.length === 6 && cam.every(Number.isFinite)) camera = { x: cam[0], y: cam[1], z: cam[2], yaw: cam[3], pitch: cam[4], dist: cam[5], mode: 'orbit' }
    const selection = h('p', { class: 'notice selection-summary', role: 'status' })
    const summary = h('p', { class: 'small preview-summary', role: 'status' })
    const list = h('div', { class: 'conflict-list' })
    const viewerHost = h('div', { class: 'merge-view' })
    const rules = h('details', { class: 'card merge-rules' }, h('summary', {}, `規則差異（${report.ruleDifferences.length}）`))
    for (const r of report.ruleDifferences) rules.append(h('h3', {}, dimLabel(r.dimension.value)),
      h('div', { class: 'cols' }, ...(['base', 'ours', 'theirs', 'merged'] as const).map(c => h('div', {}, h('b', {}, c), h('pre', {}, r[c] ?? '規則衝突，無合併結果')))))
    const stale = !pull && params.has('choices') && params.get('tips') !== report.fingerprint
    root.replaceChildren(h('div', {}, h('div', { class: 'crumbs' }, link('/', '世界'), ' / ', link(`/${owner}/${world}`, `${owner}/${world}`), ' / ', link(`/${owner}/${world}/branches`, '分支'), ' / 合併預覽'),
      h('h1', {}, `${pair.a} ← ${pair.b}`), h('p', { class: 'notice' }, pull ? '區域選擇儲存在此 PR；來源或目標 tip 變動後選擇與審核作廢。' : '唯讀預覽：選擇保存在此網址。要發布結果，請建立 Pull Request。'),
      h('p', { class: 'merge-report' }, report.canMerge ? report.zeroIntervention ? `可零介入合併 · 自動合併 ${fmtNum(report.automaticallyMergedSections)} sections` : `${report.regions.length} 個衝突區域 · 自動合併 ${fmtNum(report.automaticallyMergedSections)} sections` : '無法合併，請先處理下列原因。'),
      ...report.problems.map(p => h('p', { class: 'notice' }, `${dimLabel(p.dimension)} · ${p.reason}`)),
      stale ? h('p', { class: 'notice' }, '來源 tip 已變動，舊選擇已清除，請重新檢查。') : null,
      ...report.warnings.map(w => h('p', { class: 'small muted' }, w)), selection,
      h('div', { class: 'merge-layout' }, h('aside', { class: 'card' }, h('h2', {}, '衝突區域'),
        h('label', {}, '篩選維度', h('select', { 'aria-label': '篩選維度', onChange: (e: Event) => { filter = (e.target as HTMLSelectElement).value; renderList(); save() } },
          h('option', { value: '', selected: !filter }, '所有維度'), ...report.dimensions.map(d => h('option', { value: d.dimension, selected: d.dimension === filter }, dimLabel(d.dimension))))),
        h('label', {}, '排序', h('select', { 'aria-label': '區域排序', onChange: (e: Event) => { sort = (e.target as HTMLSelectElement).value; renderList(); save() } },
          ...[['id', '區域編號'], ['size', '格數最多'], ['redstone', '紅石優先']].map(([v, label]) => h('option', { value: v, selected: v === sort }, label)))), list), viewerHost), rules))
    const updateSelection = () => {
      const count = selectionSummary(report.regions, choices)
      selection.textContent = `選擇摘要：ours ${count.ours} · theirs ${count.theirs} · base ${count.base} · 待遊戲內處理 ${count.manual}。待處理區域暫顯示 ours，尚未解決。`
    }
    const save = () => {
      if (disposed || pull) return
      const q = new URLSearchParams({ tips: report.fingerprint, view: mode, sort })
      if (dim) q.set('dim', dim.dimension)
      if (selected) q.set('region', String(selected.id))
      if (filter) q.set('filter', filter)
      if (choices.size) q.set('choices', encodeChoices(choices))
      if (camera) q.set('cam', [camera.x, camera.y, camera.z, camera.yaw, camera.pitch, camera.dist].map(n => +n.toFixed(2)).join(','))
      history.replaceState(null, '', `${mergePath(owner, world, pair.a, pair.b)}?${q}`)
    }
    const box = (r: Region): [number, number, number, number, number, number] => { const b = r.bounds!; return [b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ] }
    const boxes = () => viewer?.setConflictBoxes(report.regions.filter(r => r.dimension === dim?.dimension && r.bounds).map(r => ({ bounds: box(r), selected: selected?.id === r.id })))
    const chooseRegion = (r: Region) => {
      selected = r
      const next = report.dimensions.find(d => d.dimension === r.dimension)
      if (next && next !== dim) { dim = next; camera = undefined; show(true) }
      else { boxes(); if (r.bounds) viewer?.focusBox(box(r)) }
      renderList(); save()
    }
    const renderList = () => {
      const rows = sortedRegions(report.regions, filter, sort)
      list.replaceChildren(...rows.map(r => h('article', { class: `conflict-row ${r.id === selected?.id ? 'active' : ''}`, 'data-region': r.id },
        h('button', { class: 'link conflict-focus', 'aria-pressed': r.id === selected?.id, onClick: () => chooseRegion(r) }, `! #${r.id} · ${dimLabel(r.dimension)} · ${fmtNum(r.blockCount)} 格`),
        h('p', { class: 'small' }, r.bounds ? `(${r.bounds.minX}, ${r.bounds.minY}, ${r.bounds.minZ}) ～ (${r.bounds.maxX}, ${r.bounds.maxY}, ${r.bounds.maxZ})` : '設定／metadata（無空間位置）'),
        h('p', { class: 'small muted' }, `ours：${r.oursAuthors.join('、')}\ntheirs：${r.theirsAuthors.join('、')}`),
        h('p', { class: 'small muted' }, `${Object.entries(r.kinds).map(([k, v]) => `${k} ${v}`).join(' · ')} · 交界提示 ${r.boundaryHints}${r.redstone ? ' · 含紅石，建議測試' : ''}`),
        h('label', {}, '區域選擇', h('select', { 'aria-label': `區域 ${r.id} 選擇`, onChange: async (e: Event) => {
          const select = e.target as HTMLSelectElement, previous = choices.get(r.id)
          select.disabled = true; choices.set(r.id, select.value as Choice)
          try { if (pull) await pull.onChoices(Object.fromEntries(choices)); mode = 'selected'; updateSelection(); show(); save() }
          catch (error) { if (previous) choices.set(r.id, previous); else choices.delete(r.id); select.value = previous ?? 'manual'; toast(String(error), 'error') }
          finally { select.disabled = false }
        } }, ...[['manual', '待遊戲內處理'], ['ours', 'ours'], ['theirs', 'theirs'], ['base', 'base']].map(([v, label]) => h('option', { value: v, selected: (choices.get(r.id) ?? 'manual') === v }, label)))))))
      if (!rows.length) list.append(h('p', { class: 'empty' }, report.regions.length ? '此維度沒有衝突區域。' : '沒有衝突，可零介入合併。'))
    }
    const show = (focus = false) => {
      camera = focus ? undefined : viewer?.getViewState() ?? camera
      viewer?.dispose(); viewer = null; window.__worldgit = { viewer: null }; const current = ++generation
      if (!dim || !report.canMerge || disposed) { viewerHost.replaceChildren(h('p', { class: 'empty' }, '前提衝突處理完畢後才可預覽。')); return }
      const d: MergeDimension = dim
      const canvas = h('canvas', { 'aria-label': '合併衝突區域 3D 預覽' })
      const hud = h('div', { class: 'overlay hud', role: 'status' }, '載入中…')
      const pick = h('div', { class: 'overlay pick', style: 'display:none' })
      const legend = h('div', { class: 'overlay legend' }); renderLegend(legend, palettes, palette)
      const toolbar = h('div', { class: 'toolbar' },
        segmented<MergeView>([['auto', '自動合併結果'], ['ours', 'ours'], ['theirs', 'theirs'], ['base', 'base'], ['selected', '依目前選擇的合併結果']], () => mode, m => { mode = m; show(); save() }),
        h('label', {}, '檢視維度', h('select', { 'aria-label': '檢視維度', onChange: (e: Event) => { dim = report.dimensions.find(d => d.dimension === (e.target as HTMLSelectElement).value)!; camera = undefined; selected = report.regions.find(r => r.dimension === dim.dimension); show(true); renderList() } },
          ...report.dimensions.map(x => h('option', { value: x.dimension, selected: x === d }, dimLabel(x.dimension))))),
        segmented([['default', '一般色票'], ['colorblind', '色盲色票']], () => paletteChoice(), v => { setPaletteChoice(v); void activePalette().then(p => { palette = p; viewer?.setPalette(p); renderLegend(legend, palettes, p) }) }))
      viewerHost.replaceChildren(toolbar, h('p', { class: 'small muted' }, '切換區域的來源版本，區域外保留自動合併的雙方變動。紫框為衝突；+/−/~ 顯示相對 base 的差異。'),
        h('div', { class: 'viewer' }, canvas, hud, pick, legend), summary)
      const q = new URLSearchParams(source); q.set('fingerprint', report.fingerprint); q.set('dim', d.dimension); q.set('view', mode); q.set('choices', encodeChoices(choices))
      viewer = new Viewer({ canvas, owner, world, dimRepo: d.repo, palette, radius: 3, workers: 1, showDiff: true,
        preview: { base: `${endpoint}/view`, query: q.toString() },
        detail: { commit: previewCommit(d), parent: d.base, initial: false, added: 0, removed: 0, modified: 0, chunkCount: 1, sectionCount: 0, entitiesAdded: 0, entitiesRemoved: 0, entitiesModified: 0,
          entityChanges: [], changedChunks: [[d.bounds[0], d.bounds[1], 0, 0, 0, 0]], bounds: d.bounds, metadataChanges: [], mcVersion: d.mcVersion } })
      window.__worldgit = { viewer }; boxes()
      viewer.onPick = p => { renderPick(pick, p); pull?.onPick(p, d.dimension) }
      pull?.onViewer(viewer, d.dimension)
      viewer.onStats = s => { hud.textContent = `${s.ready ? '就緒' : '載入中…'} · ${dimLabel(d.dimension)} · ${mode}\nchunks ${s.chunks} · sections ${s.sectionsMeshed} · GPU ${s.gpuMB.toFixed(1)} MB` }
      viewer.onViewChange = v => { camera = v; save() }
      viewer.onError = message => { if (current === generation && !disposed) hud.textContent = message }
      if (camera) viewer.applyViewState(camera)
      else if (selected?.dimension === d.dimension && selected.bounds) viewer.focusBox(box(selected))
      void viewer.start().catch(e => { if (current === generation && !disposed) hud.textContent = `預覽失敗：${e}` })
      summary.textContent = '計算目前預覽摘要…'
      void getJson<MergeSummary>(`${endpoint}/view/summary?${q}&x0=0&z0=0&x1=0&z1=0`).then(s => {
        if (current !== generation || disposed) return
        summary.textContent = `此維度結果：+${fmtNum(s.counts.added)} / −${fmtNum(s.counts.removed)} / ~${fmtNum(s.counts.modified)} 格 · 實體變動 ${s.entities} · biome ${s.biomes} · metadata ${s.metadata} · 交界提示 ${s.updateShapes.length} 格（僅供檢查）`
        if (s.updateShapes.length) summary.append(h('details', {}, h('summary', {}, '交界提示座標'), h('pre', {}, s.updateShapes.slice(0, 200).map(c => `(${c.x}, ${c.y}, ${c.z})`).join('\n')), s.updateShapes.length > 200 ? '僅顯示前 200 筆' : ''))
      }).catch(e => { if (current === generation && !disposed) summary.textContent = String(e) })
      save()
    }
    updateSelection(); renderList(); show(); save()
  } catch (e) { if (!disposed) loading.textContent = e instanceof Error ? e.message : String(e) }
  return dispose
}
