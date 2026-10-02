import type { CommitInfo } from './api.ts'
import { comparePath, parseCompareSpec } from './compare.ts'
export type Choice = 'ours' | 'theirs' | 'base' | 'manual'
export type MergeView = 'auto' | 'ours' | 'theirs' | 'base' | 'selected'
export interface Box { minX: number; minY: number; minZ: number; maxX: number; maxY: number; maxZ: number }
export interface Region { id: number; dimension: string; bounds: Box | null; blockCount: number; oursAuthors: string[]; theirsAuthors: string[]; redstone: boolean; boundaryHints: number; kinds: Record<string, number> }
export interface MergeDimension { dimension: string; repo: string; ours: string; theirs: string | null; base: string | null; status: string; mcVersion: string; automaticallyMergedSections: number; boundaryHints: number; bounds: number[] }
export interface MergeReport { fingerprint: string; canMerge: boolean; zeroIntervention: boolean; automaticallyMergedSections: number; regions: Region[]; dimensions: MergeDimension[]; ruleDifferences: { dimension: { value: string }; base: string; ours: string; theirs: string; merged: string | null }[]; problems: { dimension: string; code: string; reason: string }[]; warnings: string[] }
export interface MergeSummary { tree: string; counts: { added: number; removed: number; modified: number; conflict: number }; entities: number; biomes: number; metadata: number; updateShapes: { x: number; y: number; z: number }[] }
export const parseMergeSpec = parseCompareSpec
export const mergePath = (o: string, w: string, ours: string, theirs: string) => comparePath(o, w, ours, theirs).replace('/compare/', '/merge-preview/')
export function encodeChoices(choices: Map<number, Choice>): string { return [...choices].sort((a, b) => a[0] - b[0]).map(([id, c]) => `${id}:${c}`).join(',') }
/** URL 選擇只能套到原 tip 指紋；未知 id／選項丟棄，不默認已解決。 */
export function readChoices(text: string | null, fingerprint: string | null, report: MergeReport): Map<number, Choice> {
  const choices = new Map<number, Choice>()
  if (!text || fingerprint !== report.fingerprint || text.length > 32000) return choices
  const ids = new Set(report.regions.map(r => r.id))
  for (const item of text.split(',')) {
    const [id, choice] = item.split(':')
    if (ids.has(Number(id)) && ['ours', 'theirs', 'base', 'manual'].includes(choice)) choices.set(Number(id), choice as Choice)
  }
  return choices
}
export function selectionSummary(regions: Region[], choices: Map<number, Choice>) {
  const counts = { ours: 0, theirs: 0, base: 0, manual: 0 }
  regions.forEach(r => counts[choices.get(r.id) ?? 'manual']++)
  return counts
}
export function sortedRegions(regions: Region[], dim: string, sort: string): Region[] {
  return regions.filter(r => !dim || r.dimension === dim).sort((a, b) => sort === 'size' ? b.blockCount - a.blockCount || a.id - b.id : sort === 'redstone' ? Number(b.redstone) - Number(a.redstone) || a.id - b.id : a.id - b.id)
}
/** Viewer 的 commit 只提供版本／初始焦點；資料由唯讀合併串流載入。 */
export function previewCommit(d: MergeDimension): CommitInfo { return { id: d.ours, tree: '', parents: [], time: 0, author: { name: '', email: '' }, committer: { name: '', email: '' }, message: '', auto: false, source: 'HUB', dataVersion: 0, dimension: d.dimension, snapshot: '', coAuthors: [] } }
