// Hub → 瀏覽器二進位格式的解碼（格式定義見後端 ChunkWire.java）。DOM-free，可在 Node 測試。
import { World, type ChunkData, type SectionData, type SectionDiff, StateTable } from './world.ts'

const dec = new TextDecoder()

class Reader {
  p = 0
  readonly v: DataView
  constructor(readonly buf: ArrayBuffer) { this.v = new DataView(buf) }
  u8() { return this.v.getUint8(this.p++) }
  i8() { return this.v.getInt8(this.p++) }
  u16() { const x = this.v.getUint16(this.p); this.p += 2; return x }
  i32() { const x = this.v.getInt32(this.p); this.p += 4; return x }
  u32() { const x = this.v.getUint32(this.p); this.p += 4; return x }
  bytes(n: number) { const b = new Uint8Array(this.buf, this.p, n); this.p += n; return b }
  utf() { const n = this.u16(); return dec.decode(this.bytes(n)) }
  magic(m: string) {
    const s = dec.decode(this.bytes(4))
    if (s !== m) throw new Error(`格式錯誤：預期 ${m}，收到 ${s}`)
    const ver = this.u8()
    if (ver !== 1) throw new Error(`不支援的 ${m} 版本 ${ver}`)
  }
}

/** 解 LSB 優先的緊密位元流（core 的 section 格式）。bits=0 代表只有一個調色盤項目。 */
export function unpackBits(packed: Uint8Array, bits: number, out: Uint16Array) {
  if (bits === 0) { out.fill(0); return }
  const mask = (1 << bits) - 1
  for (let i = 0; i < 4096; i++) {
    const bitPos = i * bits, b = bitPos >> 3
    const w = (packed[b] | (packed[b + 1] << 8) | (packed[b + 2] << 16) | (packed[b + 3] << 24)) >>> (bitPos & 7)
    out[i] = w & mask
  }
}

export interface DecodeStats { chunks: number; sections: number }

/** WGCK：把一個視窗的 chunk 併入 world（state／biome 轉成 table 內的全域編號）。 */
export function decodeChunks(buf: ArrayBuffer, world: World): { chunks: ChunkData[]; stats: DecodeStats } {
  const r = new Reader(buf)
  r.magic('WGCK')
  const nStates = r.u16()
  const stateMap = new Uint16Array(nStates)
  for (let i = 0; i < nStates; i++) stateMap[i] = world.table.state(r.utf())
  const nBiomes = r.u16()
  const biomeMap = new Uint16Array(nBiomes)
  for (let i = 0; i < nBiomes; i++) biomeMap[i] = world.table.biome(r.utf())
  const n = r.u32()
  const chunks: ChunkData[] = []
  let sections = 0
  const scratch = new Uint16Array(4096)
  for (let c = 0; c < n; c++) {
    const cx = r.i32(), cz = r.i32()
    const chunk: ChunkData = { cx, cz, sections: new Map(), biomes: new Map(), blockEntities: new Map() }
    const ns = r.u8()
    for (let s = 0; s < ns; s++) {
      const sy = r.i8()
      const pc = r.u16()
      const palette = new Uint16Array(pc)
      for (let i = 0; i < pc; i++) palette[i] = stateMap[r.u16()]
      const bits = r.u8()
      const packed = r.bytes(Math.ceil((4096 * bits) / 8))
      let sec: SectionData
      if (bits === 0 || pc === 1) sec = { ids: null, single: palette[0] }
      else {
        unpackBits(packed, bits, scratch)
        const ids = new Uint16Array(4096)
        for (let i = 0; i < 4096; i++) ids[i] = palette[scratch[i]]
        sec = { ids, single: palette[0] }
      }
      chunk.sections.set(sy, sec)
      const nbe = r.u16()
      for (let b = 0; b < nbe; b++) { const pos = r.u16(); chunk.blockEntities.set(sy * 4096 + pos, r.utf()) }
      sections++
    }
    const nb = r.u8()
    for (let b = 0; b < nb; b++) {
      const sy = r.i8()
      const uniform = r.u8() === 1
      const arr = new Uint16Array(64)
      if (uniform) arr.fill(biomeMap[r.u16()])
      else for (let i = 0; i < 64; i++) arr[i] = biomeMap[r.u16()]
      chunk.biomes.set(sy, arr)
    }
    chunks.push(chunk)
  }
  return { chunks, stats: { chunks: n, sections } }
}

export const KIND_ADDED = 1, KIND_REMOVED = 2, KIND_MODIFIED = 3, KIND_CONFLICT = 4

/** WGDF：變動的方塊（kind 與 before state），回傳以 section 為單位的 diff。 */
export function decodeDiff(buf: ArrayBuffer, table: StateTable): Map<string, SectionDiff & { cx: number; cz: number; sy: number }> {
  const r = new Reader(buf)
  r.magic('WGDF')
  const nStates = r.u16()
  const stateMap = new Uint16Array(nStates)
  for (let i = 0; i < nStates; i++) stateMap[i] = table.state(r.utf())
  const n = r.u32()
  const out = new Map<string, SectionDiff & { cx: number; cz: number; sy: number }>()
  for (let s = 0; s < n; s++) {
    const cx = r.i32(), cz = r.i32(), sy = r.i8(), count = r.u16()
    const kind = new Uint8Array(4096), before = new Uint16Array(4096)
    for (let i = 0; i < count; i++) {
      const idx = r.u16(), k = r.u8(), b = r.u16()
      kind[idx] = k
      before[idx] = stateMap[b]
    }
    out.set(World.sectionKey(cx, sy, cz), { cx, cz, sy, kind, before, count })
  }
  return out
}
