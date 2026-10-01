import { ApiError, api, type SnapshotRow } from '../api.ts'
import { fmtFull, h, link } from '../ui.ts'
import { snapshotRow } from './shared.ts'

/** commit 列表：每個存檔一列（依 WorldGit-Snapshot trailer 合併各維度）；連續的自動存檔折疊。 */
export async function commitsPage(root: HTMLElement, owner: string, world: string) {
  const body = h('div', { class: 'card' }, h('p', { class: 'empty' }, '載入中…'))
  const moreBox = h('div', { class: 'row', style: 'justify-content:center;margin-top:10px' })
  root.append(h('div', { class: 'crumbs' }, link('/', '世界'), ' / ', link(`/${owner}/${world}`, `${owner}/${world}`), ' / ', h('b', {}, 'commits')),
    h('h1', {}, 'Commit 歷史'), body, moreBox)
  let declared: string[] = []
  let before: number | null = null
  let first = true
  const load = async () => {
    try {
      const page = await api.snapshots(owner, world, 40, before, true)
      declared = page.declaredDimensions
      if (first) { body.replaceChildren(); first = false }
      renderRows(body, page.snapshots, owner, world, declared)
      before = page.nextBefore
      moreBox.replaceChildren(before ? h('button', { onClick: () => void load() }, '載入更多') : '')
      if (first === false && !body.children.length) body.append(h('p', { class: 'empty' }, '還沒有 commit。'))
    } catch (e) { body.replaceChildren(h('p', { class: 'empty' }, String(e instanceof ApiError ? e.message : e))) }
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
