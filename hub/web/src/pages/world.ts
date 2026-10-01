import { ApiError, api, dimLabel, dimRepo, type CommitDetail, type DimensionState, type SnapshotPage } from '../api.ts'
import { MapView } from '../map/mapview.ts'
import { activePalette } from '../palette.ts'
import { navigate } from '../router.ts'
import { copyText, fmtTime, h, link } from '../ui.ts'
import { branchSelect } from './commits.ts'
import { snapshotRow, statSpans } from './shared.ts'

export async function worldPage(root: HTMLElement, owner: string, world: string): Promise<() => void> {
  let map: MapView | null = null
  const dispose = () => map?.dispose()
  let info, page: SnapshotPage
  try {
    info = await api.world(owner, world)
    page = await api.snapshots(owner, world, 12)
  } catch (e) {
    root.append(h('p', { class: 'empty' }, e instanceof ApiError && e.status === 401 ? h('span', {}, '這是私人世界，請先 ', link('/login', '登入'), '。') : String(e instanceof ApiError ? e.message : e)))
    return dispose
  }
  const palette = await activePalette()
  const dims: DimensionState[] = info.dimensions ?? []
  let selected = dims.find((d) => d.id === 'minecraft:overworld' && d.head) ?? dims.find((d) => d.head) ?? dims[0]

  const mapBox = h('div', { class: 'map' })
  const canvas = h('canvas', {})
  const hud = h('div', { class: 'hud' })
  mapBox.append(canvas, hud, h('div', { class: 'hint' }, '拖曳平移 · 滾輪縮放 · 點選變動的 chunk 開啟 3D'))
  const tabs = h('div', { class: 'tabs' })
  const mapInfo = h('div', { class: 'small muted' })

  async function showDim(d: DimensionState) {
    selected = d
    renderTabs()
    map?.dispose()
    map = null
    mapInfo.textContent = ''
    if (!d.head) { canvas.getContext('2d')?.clearRect(0, 0, canvas.width, canvas.height); mapInfo.textContent = '這個維度還沒有推送任何 commit。'; return }
    let detail: CommitDetail | null = null
    try { detail = await api.commit(owner, world, dimRepo(d.id), d.head) } catch { /* 沒有統計也能看地圖 */ }
    map = new MapView(canvas, hud)
    await map.load({ owner, world, dimRepo: dimRepo(d.id), rev: d.head, palette, changed: detail && !detail.initial ? detail.changedChunks : [],
      onChunk: (cx, cz) => navigate(`/${owner}/${world}/commit/${dimRepo(d.id)}/${d.head!.slice(0, 7)}?x=${cx * 16 + 8}&z=${cz * 16 + 8}`) })
    mapInfo.replaceChildren(`${dimLabel(d.id)} 的最新 commit `, h('code', {}, d.head!.slice(0, 7)), ' · ', detail ? statSpans(detail) : '')
  }
  function renderTabs() {
    tabs.replaceChildren(...dims.map((d) => h('button', { class: `tab ${d === selected ? 'active' : ''}`, onClick: () => void showDim(d) },
      dimLabel(d.id), h('span', { class: 'badge' }, d.head ? `${d.commits} commits` : '未推送'))))
  }

  const clone = h('div', { class: 'card' }, h('h2', {}, '複製 / 推送'),
    ...dims.map((d) => {
      const origin = info.public ? location.origin.replace("://", "://anonymous:@") : location.origin
      const url = `${origin}/git/${owner}/${world}/${d.repo}.git`
      return h('div', {}, h('div', { class: 'small muted' }, dimLabel(d.id)), h('div', { class: 'code' }, h('code', {}, `git clone ${url}`), h('button', { class: 'small', onClick: () => copyText(`git clone ${url}`) }, '複製')))
    }),
    h('p', { class: 'small muted' }, '每個維度是獨立的 repo（決定 #18）；push 需要 token 當密碼。'))

  const branchPicker = h('span', { class: 'row' })
  void api.branches(owner, world).then(p => branchPicker.append(branchSelect(p, null, b => navigate(`/${owner}/${world}/commits?branch=${encodeURIComponent(b)}`)))).catch(() => {})

  const recent = h('div', { class: 'card' }, h('div', { class: 'row' }, h('h2', {}, '最近的存檔'), h('span', { class: 'spacer' }), link(`/${owner}/${world}/commits`, '全部 commit →')),
    ...(page.snapshots.length ? page.snapshots.slice(0, 8).map((s) => snapshotRow(owner, world, s, page.declaredDimensions)) : [h('p', { class: 'empty' }, '還沒有 commit。')]))

  root.append(
    h('div', { class: 'crumbs' }, link('/', '世界'), ' / ', link(`/${owner}/${world}`, owner), ' / ', h('b', {}, world)),
    h('div', { class: 'row' }, h('h1', {}, info.displayName), h('span', { class: `badge ${info.public ? 'public' : ''}` }, info.public ? '公開' : '私人'),
      info.latest ? h('span', { class: 'muted small' }, `最後更新 ${fmtTime(info.latest.time)}`) : null,
      info.latest?.partial ? h('span', { class: 'badge warn', title: info.latest.partialReason ?? '' }, '最新存檔為部分推送') : null),
    h('div', { class: 'row branch-base' }, branchPicker, link(`/${owner}/${world}/branches`, '瀏覽分支 →'), link(`/${owner}/${world}/compare/HEAD...HEAD`, '比較版本 →')),
    h('div', { class: 'cols' }, h('div', {}, tabs, mapBox, mapInfo, h('div', { style: 'height:14px' }), clone), recent))
  renderTabs()
  if (selected) void showDim(selected)
  return dispose
}
