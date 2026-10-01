import { api, dimLabel, dimRepo, type CommitDetail, type SnapshotRow } from '../api.ts'
import { fmtFull, fmtNum, fmtTime, h, link, short } from '../ui.ts'

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
  for (const dim of declared) {
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
      h('span', { class: 'badge' }, snap.source),
      snap.partial ? h('span', { class: 'badge warn', title: snap.partialReason ?? '' }, '部分推送') : null),
    h('div', { class: 'small muted' }, `${snap.author.name} · `, h('span', { title: fmtFull(snap.time) }, fmtTime(snap.time)), snap.partialReason ? ` · ${snap.partialReason}` : ''),
    chips)
}
