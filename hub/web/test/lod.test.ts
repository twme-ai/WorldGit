import { describe, expect, it } from 'vitest'
import { buildLod, cellColors, CELLS, HEIGHT_BIAS, parseHeights } from '../src/viewer/lod.ts'
import { VERTEX_BYTES } from '../src/viewer/vertex.ts'

function heightsBuf(fill: (i: number, j: number) => number | null): ArrayBuffer {
  const v = new DataView(new ArrayBuffer(CELLS * CELLS * 2))
  for (let j = 0; j < CELLS; j++) for (let i = 0; i < CELLS; i++) {
    const h = fill(i, j)
    v.setUint16((j * CELLS + i) * 2, h === null ? 0 : h + HEIGHT_BIAS)
  }
  return v.buffer as ArrayBuffer
}

describe('lod', () => {
  it('parseHeights：0 代表沒有資料，其餘減去偏移', () => {
    const h = parseHeights(heightsBuf((i) => (i === 0 ? null : 64)))
    expect(h[0]).toBe(-32768)
    expect(h[1]).toBe(64)
  })

  it('cellColors：取 4x4 像素平均並略過透明像素', () => {
    const rgba = new Uint8Array(512 * 512 * 4)
    for (let dz = 0; dz < 4; dz++) for (let dx = 0; dx < 4; dx++) {
      const o = (dz * 512 + dx) * 4
      rgba[o] = 100; rgba[o + 1] = 50; rgba[o + 2] = 10; rgba[o + 3] = dx < 2 ? 255 : 0
    }
    const c = cellColors(rgba)
    expect([c[0], c[1], c[2]]).toEqual([100, 50, 10])
    expect([c[3], c[4], c[5]]).toEqual([0, 0, 0])
  })

  it('buildLod：平地只輸出頂面；skip 的 chunk 被挖掉；空資料回傳 null', () => {
    const heights = parseHeights(heightsBuf((i, j) => (i < 8 && j < 8 ? 70 : null)))
    const colors = new Uint8Array(CELLS * CELLS * 3).fill(120)
    const all = buildLod(0, 0, heights, colors, () => false)!
    // 8×8 個 cell（2×2 chunk），頂面 64 quads；邊緣側面：每個外圍邊在「鄰格無資料」時 yb = y-4 → 有側面
    const quads = all.byteLength / VERTEX_BYTES / 4
    expect(quads).toBeGreaterThanOrEqual(64)
    const dug = buildLod(0, 0, heights, colors, (cx, cz) => cx === 0 && cz === 0)!
    expect(dug.byteLength).toBeLessThan(all.byteLength)
    expect(buildLod(0, 0, heights, colors, () => true)).toBeNull()
    expect(buildLod(0, 0, parseHeights(heightsBuf(() => null)), colors, () => false)).toBeNull()
  })
})
