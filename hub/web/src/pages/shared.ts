import { api, dimLabel, dimRepo, type CommitDetail, type Palette, type Palettes, type SnapshotRow } from '../api.ts'
import { append, clear, fmtFull, fmtNum, fmtTime, h, link, short } from '../ui.ts'
import type { PickInfo } from '../viewer/viewer.ts'

/** 限制同時進行的統計請求（伺服器端首次計算 diff 較重）。 */
const queue: (() => Promise<void>)[] = []
let running = 0
function enqueue(job: () => Promise<void>) {
  queue.push(job)
  const next = () => {
    while (running < 3 && queue.length) {
      const j = queue.shift()!
      running++
      void j().finally(() => { running--; next() })
    }
  }
  next()
}

export function statSpans(d: CommitDetail): HTMLElement {
  if (d.initial) return h('span', { class: 'stat muted' }, `初始快照 · ${fmtNum(d.chunkCount)} chunks`)
  const parts: HTMLElement[] = []
  if (d.added) parts.push(h('span', { class: 'stat add', title: '新增' }, `+${fmtNum(d.added)}`))
  if (d.removed) parts.push(h('span', { class: 'stat rem', title: '移除' }, `-${fmtNum(d.removed)}`))
  if (d.modified) parts.push(h('span', { class: 'stat mod', title: '修改' }, `~${fmtNum(d.modified)}`))
  const ent = d.entitiesAdded + d.entitiesRemoved + d.entitiesModified
  if (ent) parts.push(h('span', { class: 'stat muted', title: '實體變動' }, `⚑${fmtNum(ent)}`))
  if (!parts.length) parts.push(h('span', { class: 'stat muted' }, '無方塊變動'))
  return h('span', { class: 'row', style: 'gap:6px' }, ...parts)
}

export function snapshotRow(owner: string, world: string, snap: SnapshotRow, declared: string[], lazyStats = true): HTMLElement {
  const chips = h('div', { class: 'chips' })
  for (const dim of Object.keys(snap.commits)) {
    const c = snap.commits[dim]
    if (c) {
      const stats = h('span', { class: 'stat muted' }, '…')
      const chip = link(`/${owner}/${world}/commit/${dimRepo(dim)}/${short(c.id)}`, [dimLabel(dim), ' ', h('code', {}, short(c.id)), ' ', stats], 'chip')
      chips.append(chip)
      const load = () => enqueue(async () => {
        try { stats.replaceWith(statSpans(await api.commit(owner, world, dimRepo(dim), c.id))) } catch { stats.textContent = '' }
      })
      if (!lazyStats) load()
      else {
        const io = new IntersectionObserver((es) => { if (es.some((e) => e.isIntersecting)) { io.disconnect(); load() } })
        io.observe(chip)
      }
    } else if (snap.missingDimensions.includes(dim)) {
      chips.append(h('span', { class: 'chip missing', title: '這個維度的 repo 尚未推送到 Hub' }, `${dimLabel(dim)} 未推送`))
    }
  }
  return h('div', { class: 'snap' },
    h('div', { class: 'title' }, snap.message.split('\n')[0] || '(無訊息)',
      snap.auto ? h('span', { class: 'badge' }, '自動') : null,
      h('span', { class: 'badge' }, snap.source)),
    h('div', { class: 'small muted' }, `${snap.author.name} · `, h('span', { title: fmtFull(snap.time) }, fmtTime(snap.time))),
    chips)
}

/** diff 圖例：色票 + 記號 + 各自的呈現方式（色盲友善，doc 06 §1.1）。 */
export function renderLegend(legend: HTMLElement, pal: Palettes, p: Palette) {
  legend.replaceChildren(...(['added', 'removed', 'modified', 'conflict'] as const).map((k) => {
    const style = { added: 'solid', removed: 'ghost', modified: 'corner', conflict: 'pulse' }[k]
    const label = { added: '新增', removed: '移除（鬼影）', modified: '修改（角標）', conflict: '衝突（閃爍）' }[k]
    return h('span', { class: 'item', style: `color:${p[k]}` }, h('span', { class: `sw ${style}`, style: `background:${p[k]}` }), `${pal.symbols[k]} ${label}`)
  }))
}

/** 分段按鈕。 */
export function segmented<T extends string>(items: [T, string][], get: () => T, set: (v: T) => void): HTMLElement {
  const wrap = h('span', { class: 'seg' })
  const paint = () => wrap.replaceChildren(...items.map(([v, label]) => h('button', { class: get() === v ? 'active' : '', onClick: () => { set(v); paint() } }, label)))
  paint()
  return wrap
}

/** 點選方塊的資訊框。 */
export function renderPick(pick: HTMLElement, p: PickInfo | null) {
  if (!p) { pick.style.display = 'none'; return }
  pick.style.display = ''
  const kindText = { same: '無變動', added: '+ 新增', removed: '- 移除', modified: '~ 修改', conflict: '! 衝突' }[p.kind]
  clear(pick)
  append(pick, [h('div', {}, h('b', {}, p.state.replace('minecraft:', ''))),
    h('div', { class: 'mono' }, `${p.x}, ${p.y}, ${p.z}`), h('div', {}, kindText),
    p.before ? h('div', {}, '舊：', h('code', {}, p.before.replace('minecraft:', ''))) : null,
    p.kind === 'removed' ? h('div', {}, '（基準版本有這個方塊，現在已移除）') : null,
    p.biome ? h('div', { class: 'muted' }, p.biome) : null])
}

/** 比較的統計（a→b）。 */
export function compareStats(r: { added: number; removed: number; modified: number; chunkCount: number }): HTMLElement {
  const parts: HTMLElement[] = []
  if (r.added) parts.push(h('span', { class: 'stat add', title: '新增' }, `+${fmtNum(r.added)}`))
  if (r.removed) parts.push(h('span', { class: 'stat rem', title: '移除' }, `-${fmtNum(r.removed)}`))
  if (r.modified) parts.push(h('span', { class: 'stat mod', title: '修改' }, `~${fmtNum(r.modified)}`))
  if (!parts.length) parts.push(h('span', { class: 'stat muted' }, '無方塊變動'))
  if (r.chunkCount) parts.push(h('span', { class: 'stat muted' }, `${fmtNum(r.chunkCount)} chunks`))
  return h('span', { class: 'row', style: 'gap:6px' }, ...parts)
}
