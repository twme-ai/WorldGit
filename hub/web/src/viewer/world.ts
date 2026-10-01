// 主執行緒的世界資料：已解開的 section（Uint16 全域 state 編號）、biome、diff。DOM-free。
export const AIR = 0

/** 全域方塊狀態／biome 表（字串 ↔ 編號）。編號 0 固定是空氣。 */
export class StateTable {
  readonly states: string[] = []
  readonly biomes: string[] = []
  private readonly stateIds = new Map<string, number>()
  private readonly biomeIds = new Map<string, number>()
  constructor() { this.state('minecraft:air') }
  state(s: string): number {
    let id = this.stateIds.get(s)
    if (id === undefined) { id = this.states.length; this.states.push(s); this.stateIds.set(s, id) }
    return id
  }
  biome(s: string): number {
    let id = this.biomeIds.get(s)
    if (id === undefined) { id = this.biomes.length; this.biomes.push(s); this.biomeIds.set(s, id) }
    return id
  }
}

export interface SectionData { ids: Uint16Array | null; single: number }
export interface SectionDiff { kind: Uint8Array; before: Uint16Array; count: number }
export interface ChunkData {
  cx: number; cz: number
  sections: Map<number, SectionData>
  biomes: Map<number, Uint16Array>
  /** key = sy * 4096 + 區段內索引 → JSON 字串（橫幅圖樣、告示牌文字） */
  blockEntities: Map<number, string>
}

export class World {
  readonly chunks = new Map<number, ChunkData>()
  readonly diffs = new Map<string, SectionDiff>()
  constructor(readonly table: StateTable) {}
  static key(cx: number, cz: number) { return (cx + 32768) * 65536 + (cz + 32768) }
  static sectionKey(cx: number, sy: number, cz: number) { return `${cx},${sy},${cz}` }
  add(c: ChunkData) { this.chunks.set(World.key(c.cx, c.cz), c) }
  remove(cx: number, cz: number) {
    const c = this.chunks.get(World.key(cx, cz))
    if (!c) return
    this.chunks.delete(World.key(cx, cz))
    for (const sy of c.sections.keys()) this.diffs.delete(World.sectionKey(cx, sy, cz))
  }
  chunk(cx: number, cz: number) { return this.chunks.get(World.key(cx, cz)) }
  section(cx: number, sy: number, cz: number) { return this.chunk(cx, cz)?.sections.get(sy) }
  /** 該方塊目前（after）的 state；未載入回傳 -1。 */
  getState(x: number, y: number, z: number): number {
    const c = this.chunk(x >> 4, z >> 4)
    if (!c) return -1
    const s = c.sections.get(y >> 4)
    if (!s) return AIR
    return s.ids ? s.ids[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] : s.single
  }
  /** 方塊變動資訊（無 diff 回傳 null）。 */
  getDiff(x: number, y: number, z: number): { kind: number; before: number } | null {
    const d = this.diffs.get(World.sectionKey(x >> 4, y >> 4, z >> 4))
    if (!d) return null
    const i = ((y & 15) << 8) | ((z & 15) << 4) | (x & 15)
    return d.kind[i] ? { kind: d.kind[i], before: d.before[i] } : null
  }
  /**
   * 填入含 1 格外圍的 18³ 陣列（index = (y*18+z)*18+x）。after 為目前世界；before 為套用 diff 還原的舊世界
   * （方塊種類為移除／修改／新增的格子改回 before state）。未載入的鄰居填 -1。回傳該 section 本體是否有任何非空氣。
   */
  fillPadded(cx: number, sy: number, cz: number, after: Int16Array, before: Int16Array | null): boolean {
    const P = 18
    let any = false
    for (let dy = -1; dy <= 1; dy++) for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) {
      const ncx = cx + dx, ncz = cz + dz, nsy = sy + dy
      const chunk = this.chunk(ncx, ncz)
      const sec = chunk?.sections.get(nsy)
      const d = this.diffs.get(World.sectionKey(ncx, nsy, ncz))
      const xr = dx === 0 ? [0, 15] : dx < 0 ? [15, 15] : [0, 0]
      const yr = dy === 0 ? [0, 15] : dy < 0 ? [15, 15] : [0, 0]
      const zr = dz === 0 ? [0, 15] : dz < 0 ? [15, 15] : [0, 0]
      for (let y = yr[0]; y <= yr[1]; y++) for (let z = zr[0]; z <= zr[1]; z++) for (let x = xr[0]; x <= xr[1]; x++) {
        const i = (y << 8) | (z << 4) | x
        const px = dx * 16 + x + 1, py = dy * 16 + y + 1, pz = dz * 16 + z + 1
        const o = (py * P + pz) * P + px
        const v = !chunk ? -1 : !sec ? AIR : sec.ids ? sec.ids[i] : sec.single
        after[o] = v
        if (before) before[o] = d && d.kind[i] ? d.before[i] : v
        if (dx === 0 && dy === 0 && dz === 0 && !any && v > 0) any = true
      }
    }
    return any
  }
}
