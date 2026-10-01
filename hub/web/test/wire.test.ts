import { describe, expect, it } from 'vitest'
import { decodeChunks, decodeDiff, unpackBits, KIND_ADDED, KIND_MODIFIED } from '../src/viewer/wire.ts'
import { StateTable, World } from '../src/viewer/world.ts'

/** 小工具：big-endian 寫入器（對應後端 DataOutputStream）。 */
class W {
  private a: number[] = []
  u8(x: number) { this.a.push(x & 255); return this }
  u16(x: number) { this.a.push((x >> 8) & 255, x & 255); return this }
  i32(x: number) { this.a.push((x >>> 24) & 255, (x >> 16) & 255, (x >> 8) & 255, x & 255); return this }
  raw(b: ArrayLike<number>) { for (let i = 0; i < b.length; i++) this.a.push(b[i]); return this }
  utf(s: string) { const b = new TextEncoder().encode(s); this.u16(b.length); return this.raw(b) }
  magic(s: string) { return this.raw(new TextEncoder().encode(s)).u8(1) }
  buf(): ArrayBuffer { return new Uint8Array(this.a).buffer }
}

function pack(values: number[], bits: number): Uint8Array {
  const out = new Uint8Array(Math.ceil((4096 * bits) / 8) + 4)
  values.forEach((v, i) => {
    for (let b = 0; b < bits; b++) if ((v >> b) & 1) { const p = i * bits + b; out[p >> 3] |= 1 << (p & 7) }
  })
  return out.subarray(0, Math.ceil((4096 * bits) / 8))
}

describe('unpackBits', () => {
  it('還原 LSB 優先的位元流（含跨位元組）', () => {
    for (const bits of [1, 2, 4, 5, 8, 12]) {
      const values = Array.from({ length: 4096 }, (_, i) => (i * 7 + 3) % (1 << bits))
      // unpackBits 會讀超過尾端 3 byte，呼叫端以 Uint8Array 越界讀到 undefined → 0；這裡補齊以驗證邏輯
      const packed = new Uint8Array(Math.ceil((4096 * bits) / 8) + 4)
      packed.set(pack(values, bits))
      const out = new Uint16Array(4096)
      unpackBits(packed, bits, out)
      expect(Array.from(out)).toEqual(values)
    }
  })
  it('bits=0 全部為 0', () => {
    const out = new Uint16Array(4096).fill(9)
    unpackBits(new Uint8Array(0), 0, out)
    expect(out.every((x) => x === 0)).toBe(true)
  })
})

describe('decodeChunks (WGCK)', () => {
  it('解出 section、biome 並把 state 轉成全域編號', () => {
    const idx = Array.from({ length: 4096 }, (_, i) => (i % 2 === 0 ? 0 : 1))
    const w = new W().magic('WGCK')
      .u16(2).utf('minecraft:stone').utf('minecraft:gold_block')
      .u16(1).utf('minecraft:plains')
      .i32(1)
      .i32(-3).i32(7).u8(2)
      // section sy=-1：調色盤 [1,0]（全域 state 編號）、1 bit
      .u8(0xff).u16(2).u16(1).u16(0).u8(1).raw(pack(idx, 1)).u16(0)
      // section sy=0：單一方塊
      .u8(0).u16(1).u16(0).u8(0).u16(0)
      .u8(1).u8(0xff).u8(1).u16(0)
    const world = new World(new StateTable())
    const { chunks, stats } = decodeChunks(w.buf(), world)
    expect(stats).toEqual({ chunks: 1, sections: 2 })
    const c = chunks[0]
    expect([c.cx, c.cz]).toEqual([-3, 7])
    const stone = world.table.state('minecraft:stone'), gold = world.table.state('minecraft:gold_block')
    const s = c.sections.get(-1)!
    expect(s.ids![0]).toBe(gold)  // idx 0 → 調色盤[0] = 全域 1 = gold
    expect(s.ids![1]).toBe(stone)
    expect(c.sections.get(0)!.ids).toBeNull()
    expect(c.sections.get(0)!.single).toBe(stone)
    expect(c.biomes.get(-1)!.every((b) => b === 0)).toBe(true)
  })
  it('拒絕錯誤 magic 與版本', () => {
    const world = new World(new StateTable())
    expect(() => decodeChunks(new W().magic('NOPE').buf(), world)).toThrow(/格式錯誤/)
    expect(() => decodeChunks(new W().raw(new TextEncoder().encode('WGCK')).u8(9).buf(), world)).toThrow(/版本/)
  })
})

describe('decodeDiff (WGDF)', () => {
  it('解出 section 內每格的種類與舊 state', () => {
    const w = new W().magic('WGDF').u16(1).utf('minecraft:oak_fence')
      .i32(1)
      .i32(2).i32(-5).u8(4).u16(2)
      .u16(10).u8(KIND_ADDED).u16(0)
      .u16(4095).u8(KIND_MODIFIED).u16(0)
    const table = new StateTable()
    const m = decodeDiff(w.buf(), table)
    const d = m.get(World.sectionKey(2, 4, -5))!
    expect(d.count).toBe(2)
    expect(d.kind[10]).toBe(KIND_ADDED)
    expect(d.kind[4095]).toBe(KIND_MODIFIED)
    expect(table.states[d.before[4095]]).toBe('minecraft:oak_fence')
  })
})
