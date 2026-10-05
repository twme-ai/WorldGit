import { dimensionPicker } from './dimensions.ts'
import { ApiError, api, dimRepo, type BranchPage, type SnapshotRow } from '../api.ts'
import { branchOptions } from '../compare.ts'
import { navigate } from '../router.ts'
import { fmtFull, h, link, errorBanner } from '../ui.ts'
import { snapshotRow } from './shared.ts'

/** commit 列表：每個存檔一列（維度 commit 各自一列，不依 snapshot 配對）；連續的自動存檔折疊。 */
export async function commitsPage(root: HTMLElement, owner: string, world: string) {
  const body = h('div', { class: 'card' }, h('p', { class: 'empty' }, '載入中…'))
  const moreBox = h('div', { class: 'row', style: 'justify-content:center;margin-top:10px' })
  const branch = new URLSearchParams(location.search).get('branch'),dimension=new URLSearchParams(location.search).get('dim') ?? 'minecraft:overworld'
  const info=await api.world(owner,world)
  root.append(dimensionPicker(info.dimensions ?? [],dimension,d=>navigate(`/${owner}/${world}/commits?dim=${encodeURIComponent(d)}`)),link(`/${owner}/${world}/graph/${dimRepo(dimension)}`,'互動分支圖','btn'))
  const picker = h('span', { class: 'row small', style: 'gap:6px' })
  root.append(h('div', { class: 'crumbs' }, link('/', '世界'), ' / ', link(`/${owner}/${world}`, `${owner}/${world}`), ' / ', h('b', {}, 'commits')),
    h('div', { class: 'row' }, h('h1', {}, 'Commit 歷史'), picker, link(`/${owner}/${world}/branches`, '所有分支 →', 'small')), body, moreBox)
  void api.branches(owner, world,null,dimension).then((page) => picker.replaceChildren(branchSelect(page, branch, (b) =>
    navigate(`/${owner}/${world}/commits?dim=${encodeURIComponent(dimension)}${b && b !== page.defaultBranch ? `&branch=${encodeURIComponent(b)}` : ''}`)))).catch(() => {})
  let declared: string[] = []
  let before: number | null = null
  let first = true
  const load = async () => {
    try {
      const page = await api.snapshots(owner, world, 40, before, true, branch,dimension)
      declared = page.declaredDimensions
      if (first) { body.replaceChildren(); first = false }
      renderRows(body, page.snapshots, owner, world, declared)
      before = page.nextBefore
      moreBox.replaceChildren(before ? h('button', { onClick: () => void load() }, '載入更多') : '')
      if (first === false && !body.children.length) body.append(h('p', { class: 'empty' }, '還沒有 commit。'))
    } catch (e) { body.replaceChildren(errorBanner(e)) }
  }
  await load()
}

function renderRows(box: HTMLElement, rows: SnapshotRow[], owner: string, world: string, declared: string[]) {
  let i = 0
  while (i < rows.length) {
    if (!rows[i].auto) { box.append(snapshotRow(owner, world, rows[i], declared)); i++; continue }
    let j = i
    while (j < rows.length && rows[j].auto) j++
    const group = rows.slice(i, j)
    if (group.length === 1) box.append(snapshotRow(owner, world, group[0], declared))
    else {
      const det = h('details', { class: 'auto-group' },
        h('summary', {}, `${group.length} 個自動存檔（${fmtFull(group[group.length - 1].time)} – ${fmtFull(group[0].time)}）`))
      det.addEventListener('toggle', () => { if (det.open && det.children.length === 1) for (const g of group) det.append(snapshotRow(owner, world, g, declared)) }, { once: true })
      box.append(det)
    }
    i = j
  }
}

/** 分支下拉：預設分支排最前；選擇後由呼叫端決定要做什麼。 */
export function branchSelect(page: BranchPage, current: string | null, onPick: (branch: string) => void): HTMLElement {
  const selected = current ?? page.defaultBranch
  return h('label', { style: 'display:inline-flex;gap:6px;align-items:center;margin:0' }, '分支',
    h('select', { 'aria-label': '分支', onChange: (e: Event) => onPick((e.target as HTMLSelectElement).value) },
      ...branchOptions(page.branches).map((n) => h('option', { value: n, selected: n === selected }, n === page.defaultBranch ? `${n}（預設）` : n))))
}
