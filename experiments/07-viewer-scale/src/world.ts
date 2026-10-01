// 以 deepslate 的 NBT/Region 讀取器解析 .mca，並轉成便於網格生成的緊湊格式（DOM-free）。
import { BlockState, NbtRegion, NbtType, type NbtCompound, type NbtList } from 'deepslate'

export const AIR = 0
export interface SectionData {
  ids: Uint16Array | null // 4096 個全域狀態 id（index = y*256+z*16+x）；null 表示整段都是 single
  single: number
  biomes: Uint8Array // 64 個全域 biome id（4x4x4 格）
}
export interface ChunkData {
  cx: number; cz: number
  sections: Map<number, SectionData> // key = section Y（-4..19）
  blockEntities: Map<number, NbtCompound> // key = (y+64)<<8 | (z&15)<<4 | (x&15)
  status: string
}

/** 全域方塊狀態表：字串 -> id。id 0 固定為 air。 */
export class StateTable {
  states: BlockState[] = []
  keys = new Map<string, number>()
  biomeNames: string[] = []
  biomeIds = new Map<string, number>()
  constructor() { this.intern(new BlockState('minecraft:air')) }
  intern(s: BlockState) {
    s = new BlockState(s.getName(), Object.fromEntries(Object.entries(s.getProperties()).sort(([a],[b]) => a.localeCompare(b))))
    const k = s.toString()
    let id = this.keys.get(k)
    if (id === undefined) { id = this.states.length; this.states.push(s); this.keys.set(k, id) }
    return id
  }
  biome(name: string) {
    let id = this.biomeIds.get(name)
    if (id === undefined) { id = this.biomeNames.length; this.biomeNames.push(name); this.biomeIds.set(name, id) }
    return id
  }
}

/** 解 Minecraft 1.16+ 的緊密長整數陣列（每個 long 內不跨界）。deepslate 的 NbtLong 以 [hi, lo] 對表示。 */
function unpack(longs: NbtLong[], bits: number, count: number, out: ArrayLike<number> & { [i: number]: number }) {
  const per = Math.floor(64 / bits)
  const mask = (1 << bits) - 1
  let i = 0
  for (let li = 0; li < longs.length && i < count; li++) {
    const [hi, lo] = longs[li].getAsPair()
    for (let j = 0; j < per && i < count; j++, i++) {
      const o = j * bits
      let v: number
      if (o + bits <= 32) v = (lo >>> o) & mask
      else if (o >= 32) v = (hi >>> (o - 32)) & mask
      else v = ((lo >>> o) | (hi << (32 - o))) & mask
      out[i] = v
    }
  }
}
import type { NbtLong } from 'deepslate'

export function parseChunk(root: NbtCompound, table: StateTable): ChunkData {
  const cx = root.getNumber('xPos'), cz = root.getNumber('zPos')
  const chunk: ChunkData = { cx, cz, sections: new Map(), blockEntities: new Map(), status: root.getString('Status') }
  const secs = root.getList('sections', NbtType.Compound)
  for (const s of secs.getItems()) {
    const y = s.getNumber('Y')
    const bs = s.getCompound('block_states')
    const palNbt = bs.getList('palette', NbtType.Compound)
    const pal = palNbt.getItems().map(p => table.intern(BlockState.fromNbt(p)))
    let ids: Uint16Array | null = null
    let single = pal[0] ?? AIR
    if (pal.length > 1 && bs.has('data')) {
      const bits = Math.max(4, Math.ceil(Math.log2(pal.length)))
      const raw = new Uint16Array(4096)
      unpack(bs.getLongArray('data').getItems(), bits, 4096, raw)
      ids = new Uint16Array(4096)
      for (let i = 0; i < 4096; i++) ids[i] = pal[raw[i]]
    }
    const bio = s.getCompound('biomes')
    const bpal = bio.getList('palette', NbtType.String).getItems().map(t => table.biome(t.getAsString()))
    const biomes = new Uint8Array(64)
    if (bpal.length === 1 || !bio.has('data')) biomes.fill(bpal[0] ?? 0)
    else {
      const bits = Math.max(1, Math.ceil(Math.log2(bpal.length)))
      const raw = new Uint8Array(64)
      unpack(bio.getLongArray('data').getItems(), bits, 64, raw)
      for (let i = 0; i < 64; i++) biomes[i] = bpal[raw[i]]
    }
    chunk.sections.set(y, { ids, single, biomes })
  }
  for (const be of root.getList('block_entities', NbtType.Compound).getItems()) {
    const x = be.getNumber('x'), y = be.getNumber('y'), z = be.getNumber('z')
    chunk.blockEntities.set(((y + 64) << 8) | ((z & 15) << 4) | (x & 15), be)
  }
  return chunk
}

/** 讀一個 region 檔（Uint8Array），回傳其中 Status=full 的 chunk。 */
export function parseRegion(bytes: Uint8Array, rx: number, rz: number, table: StateTable, filter?: (cx: number, cz: number) => boolean) {
  const region = NbtRegion.read(bytes)
  const out: ChunkData[] = []
  const stats = { chunks: 0, skippedNotFull: 0, inflateAndNbtMs: 0, convertMs: 0 }
  for (const [lx, lz] of region.getChunkPositions()) {
    const c = region.findChunk(lx, lz)
    if (!c) continue
    if (filter && !filter(rx * 32 + lx, rz * 32 + lz)) { continue } // 先用區域座標過濾，避免解壓不需要的 chunk
    let t = performance.now()
    const root = c.getRoot() // 這裡才做 zlib inflate + NBT 解析（惰性）
    stats.inflateAndNbtMs += performance.now() - t
    if (root.getString('Status') !== 'minecraft:full') { stats.skippedNotFull++; continue }
    t = performance.now()
    out.push(parseChunk(root, table))
    stats.convertMs += performance.now() - t
    stats.chunks++
  }
  return { chunks: out, stats }
}

export class World {
  chunks = new Map<number, ChunkData>()
  constructor(readonly table: StateTable) {}
  static key(cx: number, cz: number) { return (cx + 32768) * 65536 + (cz + 32768) }
  add(c: ChunkData) { this.chunks.set(World.key(c.cx, c.cz), c) }
  chunk(cx: number, cz: number) { return this.chunks.get(World.key(cx, cz)) }
  section(cx: number, sy: number, cz: number) { return this.chunk(cx, cz)?.sections.get(sy) }
  /** 未載入回傳 -1 */
  getState(x: number, y: number, z: number) {
    const s = this.section(x >> 4, y >> 4, z >> 4)
    if (!s) return -1
    return s.ids ? s.ids[((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] : s.single
  }
}
