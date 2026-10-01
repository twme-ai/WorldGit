// 分支與比較檢視的純邏輯（無 DOM，可在 Node 單元測試）。
import type { BranchRow, CommitDetail, CompareDimension, CompareResult } from './api.ts'

/** /owner/world/compare/<a>...<b>：兩端是分支名（可含 /）或 commit 前綴。git 的 ref 名稱不能含 ".."，所以第一個 "..." 一定是分隔符。 */
export function parseCompareSpec(segment: string): { a: string; b: string } | null {
  const i = segment.indexOf('...')
  if (i <= 0 || i + 3 >= segment.length) return null
  const a = segment.slice(0, i), b = segment.slice(i + 3)
  if (!a || !b || b.includes('...')) return null
  return { a, b }
}

export function comparePath(owner: string, world: string, a: string, b: string, dim?: string | null): string {
  const q = dim ? `?dim=${encodeURIComponent(dim)}` : ''
  return `/${owner}/${world}/compare/${encodeURIComponent(a).replaceAll('%2F', '/')}...${encodeURIComponent(b).replaceAll('%2F', '/')}${q}`
}

/** 比較結果的單一維度 → 檢視器用的 CommitDetail（commit = b、parent = a；相同內容也可瀏覽）。 */
export function dimensionDetail(d: CompareDimension): CommitDetail | null {
  if (!d.a || !d.b || !d.mcVersion) return null
  return {
    commit: d.b, parent: d.a.id, initial: false,
    added: d.added, removed: d.removed, modified: d.modified, chunkCount: d.chunkCount, sectionCount: d.sectionCount,
    entitiesAdded: d.entitiesAdded, entitiesRemoved: d.entitiesRemoved, entitiesModified: d.entitiesModified,
    entityChanges: d.entityChanges, changedChunks: d.changedChunks, bounds: d.bounds, metadataChanges: d.metadataChanges, mcVersion: d.mcVersion,
  }
}

/** 預設要看的維度：URL 指定的、否則第一個有差異的（主世界優先）。 */
export function pickDimension(r: CompareResult, wanted?: string | null): CompareDimension | null {
  const changed = r.dimensions.filter((d) => d.status === 'changed')
  return r.dimensions.find((d) => d.dimension === wanted)
    ?? changed.find((d) => d.dimension === 'minecraft:overworld') ?? changed[0] ?? r.dimensions[0] ?? null
}

export function aheadBehindText(b: Pick<BranchRow, 'isDefault' | 'ahead' | 'behind' | 'countsTruncated'>): string {
  if (b.ahead === null || b.behind === null) return ''
  return `領先 ${b.ahead} · 落後 ${b.behind}${b.countsTruncated ? '（歷史截斷，計數為估算）' : ''}`
}

/** 分支列的維度狀態文字：缺少的維度與 head 不同存檔的提醒。 */
export function branchWarnings(b: Pick<BranchRow, 'consistent' | 'aligned' | 'missingDimensions'>): string[] {
  const out: string[] = []
  if (!b.consistent) out.push(`缺少維度：${b.missingDimensions.join('、')}`)
  else if (!b.aligned) out.push('各維度的 head 屬於不同存檔（沒有變動的維度停在較舊的存檔）')
  return out
}

/** 分支下拉選單的選項：預設分支排最前。 */
export function branchOptions(rows: Pick<BranchRow, 'name' | 'isDefault'>[]): string[] {
  return [...rows].sort((x, y) => Number(y.isDefault) - Number(x.isDefault) || x.name.localeCompare(y.name)).map((r) => r.name)
}

export type CompareView = 'color' | 'changed' | 'before' | 'after'
/** 前／後切換：before 顯示 a 的世界、after 顯示 b 的世界，color／changed 是 b 的世界加上差異上色。 */

export function viewRenderMode(v: CompareView): 'color' | 'changed' | 'dim' { return v === 'changed' ? 'changed' : 'color' }
