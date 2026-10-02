import { describe, it, expect } from 'vitest'
import { encodeChoices, mergePath, parseMergeSpec, readChoices, sortedRegions, selectionSummary, type MergeReport, type Region } from '../src/merge.ts'
const regions: Region[] = [
  { id: 1, dimension: 'minecraft:overworld', bounds: null, blockCount: 3, oursAuthors: [], theirsAuthors: [], redstone: false, boundaryHints: 0, kinds: {} },
  { id: 2, dimension: 'minecraft:the_nether', bounds: null, blockCount: 9, oursAuthors: [], theirsAuthors: [], redstone: true, boundaryHints: 0, kinds: {} },
]
const report = { regions, fingerprint: 'fixed-tips' } as MergeReport
describe('唯讀合併選擇', () => {
  it('斜線分支保留於深連結且正確解析', () => {
    expect(mergePath('alice', 'world', 'build/ours', 'build/theirs')).toBe('/alice/world/merge-preview/build/ours...build/theirs')
    expect(parseMergeSpec('build/ours...build/theirs')).toEqual({ a: 'build/ours', b: 'build/theirs' })
    expect(parseMergeSpec('a...')).toBeNull()
  })
  it('選擇以區域 id 排序且 URL 重載可復原', () => {
    const choices = new Map([[2, 'base' as const], [1, 'theirs' as const]])
    expect(encodeChoices(choices)).toBe('1:theirs,2:base')
    expect(readChoices(encodeChoices(choices), 'fixed-tips', report)).toEqual(new Map([[1, 'theirs'], [2, 'base']]))
  })
  it('tip 變動不會把舊選擇套到新區域', () => {
    expect(readChoices('1:theirs', 'old-tips', report).size).toBe(0)
    expect(readChoices('1:theirs', null, report).size).toBe(0)
  })
  it('未知 id 與選項不會當成已解決', () => {
    expect([...readChoices('999:ours,1:hacked,2:manual', 'fixed-tips', report)]).toEqual([[2, 'manual']])
    expect(selectionSummary(regions, new Map([[1, 'theirs']]))).toEqual({ ours: 0, theirs: 1, base: 0, manual: 1 })
  })
  it('排序與維度篩選不修改來源清單', () => {
    expect(sortedRegions(regions, '', 'size').map(r => r.id)).toEqual([2, 1])
    expect(sortedRegions(regions, '', 'redstone').map(r => r.id)).toEqual([2, 1])
    expect(sortedRegions(regions, 'minecraft:overworld', 'id').map(r => r.id)).toEqual([1])
    expect(regions.map(r => r.id)).toEqual([1, 2])
  })
})
