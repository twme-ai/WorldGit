import { api, dimLabel, dimRepo } from '../api.ts'
import { aheadBehindText, branchWarnings, comparePath } from '../compare.ts'
import { mergePath } from '../merge.ts'
import { navigate } from '../router.ts'
import { fmtFull, h, link, short } from '../ui.ts'
import { branchSelect } from './commits.ts'

export async function branchesPage(root: HTMLElement, owner: string, world: string) {
  const body = h('div', { class: 'card' }, h('p', { class: 'empty' }, '載入中…'))
  root.append(h('div', { class: 'crumbs' }, link('/', '世界'), ' / ', link(`/${owner}/${world}`, `${owner}/${world}`), ' / 分支'),
    h('h1', {}, '分支'), body)
  try {
    const page = await api.branches(owner, world, new URLSearchParams(location.search).get('base'))
    body.replaceChildren()
    const basePicker = branchSelect(page, page.comparedTo, b => navigate(`/${owner}/${world}/branches?base=${encodeURIComponent(b)}`))
    body.append(h('div', { class: 'row branch-base' }, '比較基準：', basePicker,
      h('span', { class: 'small muted' }, '領先／落後以各維度可達存檔合併後計算。')))
    for (const b of page.branches) {
      const chips = h('div', { class: 'chips' })
      for (const dim of page.declaredDimensions) {
        const c = b.heads[dim]
        chips.append(c ? link(`/${owner}/${world}/commit/${dimRepo(dim)}/${c.id}`, [dimLabel(dim), ' ', h('code', {}, short(c.id)), ` · ${c.author.name}`], 'chip')
          : h('span', { class: 'chip missing' }, `${dimLabel(dim)} 缺少分支`))
      }
      body.append(h('article', { class: 'snap branch-row', 'data-branch': b.name },
        h('div', { class: 'row' }, link(`/${owner}/${world}/commits?branch=${encodeURIComponent(b.name)}`, b.name, 'branch-name'),
          b.isDefault ? h('span', { class: 'badge' }, '預設') : null,
          h('span', { class: 'spacer' }), h('span', { class: 'small muted' }, aheadBehindText(b)),
          b.name !== page.comparedTo ? link(comparePath(owner, world, page.comparedTo, b.name), '比較 →', 'btn small') : null,
          b.name !== page.comparedTo ? link(mergePath(owner, world, page.comparedTo, b.name), '預覽合併', 'btn small') : null),
        h('p', { class: 'branch-message' }, b.message.split('\n')[0] || '(無訊息)'),
        h('div', { class: 'small muted' }, `${b.author?.name ?? ''} · ${fmtFull(b.time)}`), chips,
        ...branchWarnings(b).map(m => h('p', { class: 'small muted branch-warning' }, m))))
    }
    if (!page.branches.length) body.append(h('p', { class: 'empty' }, '還沒有分支；推送第一個 commit 後會顯示在這裡。'))
  } catch (e) { body.replaceChildren(h('p', { class: 'empty' }, String(e instanceof Error ? e.message : e))) }
}
