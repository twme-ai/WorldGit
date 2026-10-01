import { describe, expect, it } from 'vitest'
import { aheadBehindText, branchOptions, branchWarnings, comparePath, dimensionDetail, parseCompareSpec, pickDimension, viewRenderMode } from '../src/compare.ts'
import type { CompareDimension, CompareResult, CommitInfo } from '../src/api.ts'
import { StateTable, World } from '../src/viewer/world.ts'

const c = { id: 'b'.repeat(40), parents: ['p'], tree: 'tree', dataVersion: 4903 } as CommitInfo
const d = { repo: 'minecraft.overworld', chunksTruncated: false, sectionsTruncated: false, changedSections: [], beforeMcVersion: '26.2', dimension: 'minecraft:overworld', status: 'changed', a: { ...c, id: 'a'.repeat(40) }, b: c, mcVersion: '26.2',
  added: 64, removed: 5, modified: 4, chunkCount: 1, sectionCount: 1, entitiesAdded: 0, entitiesRemoved: 0, entitiesModified: 0,
  entityChanges: [], changedChunks: [[0, 0, 64, 5, 4, 0]], bounds: [0, 0, 0, 0], metadataChanges: [] } as CompareDimension

describe('比較網址與維度', () => {
  it('接受分支／commit，含斜線與 Unicode，URL 不產生 encoded solidus', () => {
    const path = comparePath('alice', 'world', 'feature/城堡', 'deadbeef', 'minecraft:overworld')
    expect(path).toBe('/alice/world/compare/feature/%E5%9F%8E%E5%A0%A1...deadbeef?dim=minecraft%3Aoverworld')
    const spec = decodeURIComponent(path.split('/compare/')[1].split('?')[0])
    expect(parseCompareSpec(spec)).toEqual({ a: 'feature/城堡', b: 'deadbeef' })
  })
  it.each(['', 'main', '...main', 'main...', 'a...b...c'])('拒絕不完整／重複分隔符 %s', spec => expect(parseCompareSpec(spec)).toBeNull())
  it('檢視器以 a 為基準、b 為目標，涵蓋相同內容', () => {
    expect(dimensionDetail(d)).toMatchObject({ commit: c, parent: d.a!.id, added: 64 })
    expect(dimensionDetail({ ...d, status: 'same' })?.parent).toBe(d.a!.id)
    expect(dimensionDetail({ ...d, a: null, status: 'only-b' })).toBeNull()
  })
  it('尊重指定維度，否則優先主世界的變動', () => {
    const nether = { ...d, dimension: 'minecraft:the_nether', status: 'same' } as CompareDimension
    const r = { dimensions: [nether, d] } as CompareResult
    expect(pickDimension(r)).toBe(d)
    expect(pickDimension(r, nether.dimension)).toBe(nether)
    expect(pickDimension({ dimensions: [] } as unknown as CompareResult)).toBeNull()
  })
  it('前後切換不套只看變動的 shader', () => {
    expect(viewRenderMode('changed')).toBe('changed')
    for (const v of ['color', 'before', 'after'] as const) expect(viewRenderMode(v)).toBe('color')
  })
})

describe('分支合併呈現', () => {
  it('預設分支最前，其他依名稱排序', () => expect(branchOptions([{ name: 'z', isDefault: false }, { name: 'a', isDefault: false }, { name: 'main', isDefault: true }])).toEqual(['main', 'a', 'z']))
  it('snapshot head 不同與缺少 repo 分別提醒', () => {
    expect(branchWarnings({ consistent: false, aligned: true, missingDimensions: ['minecraft:the_end'] }).join()).toContain('minecraft:the_end')
    expect(branchWarnings({ consistent: true, aligned: false, missingDimensions: [] }).join()).toContain('不同存檔')
  })
  it('歷史截斷不能誤報精確值或下限', () => {
    expect(aheadBehindText({ isDefault: false, ahead: 2, behind: 3, countsTruncated: true })).toContain('估算')
    expect(aheadBehindText({ isDefault: true, ahead: 2, behind: 3, countsTruncated: false })).toContain('領先 2')
  })
})

describe('只看變動的一格上下文', () => {
  it('包含跨 chunk／section 邊界的相鄰格，不擴及兩格之外', () => {
    const world = new World(new StateTable())
    const kind = new Uint8Array(4096), before = new Uint16Array(4096)
    kind[(15 << 8) | (15 << 4) | 15] = 3
    world.diffs.set(World.sectionKey(0, 4, 0), { kind, before, count: 1 })
    const own = world.changedContext(0, 4, 0)!
    expect(own[(14 << 8) | (14 << 4) | 14]).toBe(1)
    expect(own[(13 << 8) | (15 << 4) | 15]).toBe(0)
    expect(world.changedContext(1, 5, 1)![0]).toBe(1)
    expect(world.changedContext(2, 5, 1)).toBeNull()
  })
})
