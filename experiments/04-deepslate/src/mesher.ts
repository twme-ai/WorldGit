// 自己的 section 網格生成器：用 deepslate 的 BlockDefinition / SpecialRenderers 產生單一方塊的 quads，
// 其餘（剔除、生物群系染色、diff 分類、輸出型別化陣列）都是我們自己寫的。DOM-free，可跑在 Worker。
import { BlockState, Direction, Mesh, NbtType, SpecialRenderers, type Cull, type NbtCompound, type Quad } from 'deepslate'
import { PackResources, Tint, TINT_SENTINEL_B, TINT_SENTINEL_R } from './resources.ts'
import { World, type ChunkData } from './world.ts'

export const NF = 15 // x y z u v l0 l1 l2 l3 r g b light kind alpha
export const Kind = { Same: 0, Added: 1, Removed: 2, Modified: 3 } as const
export type Layer = 'opaque' | 'trans' | 'ghost'
export interface LayerMesh { data: Float32Array; quads: number }
export interface SectionMesh { cx: number; sy: number; cz: number; layers: Partial<Record<Layer, LayerMesh>>; ms: number }

interface Packed { data: Float32Array; tints: Uint8Array; nq: number }
interface Cached { solid: Packed | null; solidTrans: boolean; water: Packed | null }

const DIRS: [Direction, number, number, number][] = [
  [Direction.UP, 0, 1, 0], [Direction.DOWN, 0, -1, 0], [Direction.NORTH, 0, 0, -1],
  [Direction.SOUTH, 0, 0, 1], [Direction.EAST, 1, 0, 0], [Direction.WEST, -1, 0, 0],
]
const DIR_NAMES = ['up', 'down', 'north', 'south', 'east', 'west'] as const
const P = 18 // 含 1 格邊界的 padded 邊長

class Growable {
  a = new Float32Array(NF * 4 * 256); n = 0 // n = floats used
  ensure(extra: number) {
    if (this.n + extra > this.a.length) { const b = new Float32Array(Math.max(this.a.length * 2, this.n + extra)); b.set(this.a.subarray(0, this.n)); this.a = b }
  }
  finish(): LayerMesh | undefined { return this.n ? { data: this.a.slice(0, this.n), quads: this.n / (NF * 4) } : undefined }
}

export class Mesher {
  private cache = new Map<string, Cached>()
  private flagsById: Uint8Array[] = [] // 每個 state id 的旗標，延遲計算
  private info: { air: boolean; opaque: boolean; self: boolean; water: boolean; name: string; banner: boolean; hasWater: boolean; semi: boolean }[] = []
  private biomeTint: Float32Array = new Float32Array(0)
  private pa = new Int16Array(P * P * P)
  private pb = new Int16Array(P * P * P)
  stats = { cacheHits: 0, cacheMisses: 0, blocksVisited: 0, blocksEmitted: 0 }
  constructor(readonly res: PackResources, readonly world: World) {}

  private st(id: number) {
    let i = this.info[id]
    if (!i) {
      const s = this.world.table.states[id]
      const name = s.getName().path
      const f = this.res.getBlockFlags(s.getName())
      i = this.info[id] = {
        name, air: name === 'air' || name === 'cave_air' || name === 'void_air',
        opaque: !!f?.opaque, self: !!f?.self_culling, semi: !!f?.semi_transparent, water: name === 'water',
        banner: name.endsWith('_banner'), hasWater: s.isWaterlogged(),
      }
    }
    return i
  }

  /** 生物群系顏色表（來自預處理的 biomes.json）；在 biomeNames 增加時擴充。 */
  private tintOf(biomeId: number, t: Tint): [number, number, number] {
    const names = this.world.table.biomeNames
    if (this.biomeTint.length < names.length * 15) {
      const nt = new Float32Array(names.length * 15)
      nt.set(this.biomeTint)
      for (let b = this.biomeTint.length / 15; b < names.length; b++) {
        const c = this.res.pack.biomes[names[b]] ?? this.res.pack.biomes['minecraft:plains']
        const cols = [0, c.grass, c.foliage, c.water, c.dry]
        for (let k = 0; k < 5; k++) { const v = cols[k]; nt[b * 15 + k * 3] = k ? ((v >> 16) & 255) / 255 : 1; nt[b * 15 + k * 3 + 1] = k ? ((v >> 8) & 255) / 255 : 1; nt[b * 15 + k * 3 + 2] = k ? (v & 255) / 255 : 1 }
      }
      this.biomeTint = nt
    }
    const o = biomeId * 15 + t * 3
    return [this.biomeTint[o], this.biomeTint[o + 1], this.biomeTint[o + 2]]
  }

  private pack(mesh: Mesh): Packed | null {
    const nq = mesh.quads.length
    if (!nq) return null
    const data = new Float32Array(nq * 4 * 13), tints = new Uint8Array(nq)
    mesh.quads.forEach((q: Quad, qi) => {
      const n = q.normal()
      const light = n.y * 0.2 + Math.abs(n.z) * 0.1 + 0.8 // 與 deepslate 內建 shader 相同的假光照
      const vs = q.vertices()
      const c = vs[0].color
      let tint = 0
      if (Math.abs(c[0] - TINT_SENTINEL_R) < 1e-6 && Math.abs(c[2] - TINT_SENTINEL_B) < 1e-6) tint = Math.round(c[1] * 100)
      tints[qi] = tint
      for (let v = 0; v < 4; v++) {
        const vx = vs[v], o = (qi * 4 + v) * 13
        const col = tint ? [1, 1, 1] : vx.color
        data[o] = vx.pos.x; data[o + 1] = vx.pos.y; data[o + 2] = vx.pos.z
        data[o + 3] = vx.texture?.[0] ?? 0; data[o + 4] = vx.texture?.[1] ?? 0
        const tl = vx.textureLimit ?? [0, 0, 0, 0]
        data[o + 5] = tl[0]; data[o + 6] = tl[1]; data[o + 7] = tl[2]; data[o + 8] = tl[3]
        data[o + 9] = col[0]; data[o + 10] = col[1]; data[o + 11] = col[2]; data[o + 12] = light
      }
    })
    return { data, tints, nq }
  }

  private quadsFor(sid: number, mask: number, waterMask: number, nbt: NbtCompound | undefined, nbtKey: string): Cached {
    const key = `${sid}|${mask}|${waterMask}|${nbtKey}`
    let c = this.cache.get(key)
    if (c) { this.stats.cacheHits++; return c }
    this.stats.cacheMisses++
    const state = this.world.table.states[sid]
    const inf = this.st(sid)
    const cull: Cull = {}; const wcull: Cull = {}
    DIR_NAMES.forEach((d, i) => { cull[d] = !!(mask & (1 << i)); wcull[d] = !!(waterMask & (1 << i)) })
    const name = state.getName()
    const mesh = new Mesh(), wmesh = new Mesh()
    try {
      const props = { ...state.getProperties() }
      const def = this.res.getBlockDefinition(name)
      if (def) mesh.merge(def.getMesh(name, props, this.res, this.res, cull))
      const plain = inf.water ? state : (props.waterlogged === 'true' ? new BlockState(name, { ...props, waterlogged: 'false' }) : state)
      const special = SpecialRenderers.getBlockMesh(plain, nbt, this.res, inf.water ? wcull : cull)
      if (inf.water) wmesh.merge(special); else if (!special.isEmpty()) mesh.merge(special)
      if (!inf.water && props.waterlogged === 'true') wmesh.merge(SpecialRenderers.getBlockMesh(BlockState.WATER, undefined, this.res, wcull))
    } catch (e) { console.error('mesh error', state.toString(), e) }
    c = { solid: this.pack(mesh), solidTrans: inf.semi, water: this.pack(wmesh) }
    this.cache.set(key, c)
    return c
  }

  /** 把 (cx,sy,cz) 及其 1 格外圍的方塊狀態 id 填進 padded 陣列。回傳是否有任何非空氣。 */
  private fill(w: World, cx: number, sy: number, cz: number, out: Int16Array) {
    let any = false
    const ox = cx * 16 - 1, oy = sy * 16 - 1, oz = cz * 16 - 1
    // 依 3x3x3 個鄰近 section 逐一複製，比逐格 Map 查詢快
    for (let dy = -1; dy <= 1; dy++) for (let dz = -1; dz <= 1; dz++) for (let dx = -1; dx <= 1; dx++) {
      const s = w.section(cx + dx, sy + dy, cz + dz)
      const x0 = dx < 0 ? 15 : 0, x1 = dx > 0 ? 0 : dx < 0 ? 15 : 15
      const y0 = dy < 0 ? 15 : 0, y1 = dy > 0 ? 0 : 15
      const z0 = dz < 0 ? 15 : 0, z1 = dz > 0 ? 0 : 15
      const xr = dx === 0 ? [0, 15] : [x0, x0], yr = dy === 0 ? [0, 15] : [y0, y0], zr = dz === 0 ? [0, 15] : [z0, z0]
      void x1; void y1; void z1
      for (let y = yr[0]; y <= yr[1]; y++) for (let z = zr[0]; z <= zr[1]; z++) for (let x = xr[0]; x <= xr[1]; x++) {
        const px = cx * 16 + dx * 16 + x - ox, py = sy * 16 + dy * 16 + y - oy, pz = cz * 16 + dz * 16 + z - oz
        const v = !s ? -1 : s.ids ? s.ids[(y << 8) | (z << 4) | x] : s.single
        out[(py * P + pz) * P + px] = v
        if (dx === 0 && dy === 0 && dz === 0 && v > 0 && !this.st(v).air) any = true
      }
    }
    return any
  }

  private neighborsHidden(arr: Int16Array, idx: number) {
    return this.opq(arr[idx + 1]) && this.opq(arr[idx - 1]) && this.opq(arr[idx + P]) && this.opq(arr[idx - P]) && this.opq(arr[idx + P * P]) && this.opq(arr[idx - P * P])
  }
  private opq(id: number) { return id >= 0 && this.st(id).opaque }

  private cullMasks(arr: Int16Array, idx: number, sid: number) {
    const me = this.st(sid)
    let mask = 0, wmask = 0
    const offs = [P * P, -P * P, -P, P, 1, -1] // up down north south east west（y 上、z 南、x 東）
    for (let i = 0; i < 6; i++) {
      const nid = arr[idx + offs[i]]
      if (nid < 0) continue
      const n = this.st(nid)
      // 與 deepslate ChunkBuilder.needsCull 相同的規則
      let cull = false
      if (me.name === n.name && n.self) cull = true
      else if (n.opaque) cull = !(i === 0 && me.hasWater)
      else cull = me.hasWater && n.hasWater
      if (cull) mask |= 1 << i
      // 水面剔除：鄰居是不透明或含水
      if (n.opaque || n.hasWater) wmask |= 1 << i
    }
    return [mask, wmask]
  }

  /**
   * 生成一個 section 的網格。
   * diff 模式：A = 舊、B = 新（兩個 World），依格分類 Same / Added / Removed / Modified。
   */
  meshSection(cx: number, sy: number, cz: number, B: World = this.world, A?: World): SectionMesh {
    const t0 = performance.now()
    const out: Record<Layer, Growable> = { opaque: new Growable(), trans: new Growable(), ghost: new Growable() }
    const anyB = this.fill(B, cx, sy, cz, this.pb)
    const anyA = A ? this.fill(A, cx, sy, cz, this.pa) : anyB
    if (!anyB && !anyA) return { cx, sy, cz, layers: {}, ms: performance.now() - t0 }
    const chunk: ChunkData | undefined = B.chunk(cx, cz)
    const chunkA: ChunkData | undefined = A?.chunk(cx, cz)
    const sec = chunk?.sections.get(sy)
    const pa = this.pa, pb = this.pb
    for (let y = 0; y < 16; y++) for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
      const idx = ((y + 1) * P + (z + 1)) * P + (x + 1)
      const b = pb[idx]
      const a = A ? pa[idx] : b
      let sid = b, ctx = pb, kind: number = Kind.Same, ch = chunk
      if (a !== b) {
        const airA = this.st(a).air, airB = this.st(b).air
        if (airA && airB) continue
        if (airA) kind = Kind.Added
        else if (airB) { kind = Kind.Removed; sid = a; ctx = pa; ch = chunkA }
        else kind = Kind.Modified
      } else if (this.st(b).air) continue
      this.stats.blocksVisited++
      const info = this.st(sid)
      if (info.opaque && this.neighborsHidden(ctx, idx)) continue
      const [mask, wmask] = this.cullMasks(ctx, idx, sid)
      let nbt: NbtCompound | undefined, nbtKey = ''
      if (info.banner) {
        nbt = ch?.blockEntities.get(((sy * 16 + y + 64) << 8) | (z << 4) | x)
        nbtKey = nbt ? nbt.getList('patterns', NbtType.Compound).toString() : ''
      }
      const c = this.quadsFor(sid, mask, wmask, nbt, nbtKey)
      const biomeId = sec ? sec.biomes[((y >> 2) << 4) | ((z >> 2) << 2) | (x >> 2)] : 0
      const emit = (p: Packed, layer: Layer, alpha: number) => {
        const g = out[layer]; g.ensure(p.nq * 4 * NF)
        const arr = g.a
        let o = g.n
        for (let q = 0; q < p.nq; q++) {
          const tint = p.tints[q]
          const tc = tint ? this.tintOf(biomeId, tint as Tint) : null
          for (let v = 0; v < 4; v++) {
            const s = (q * 4 + v) * 13
            arr[o] = p.data[s] + x; arr[o + 1] = p.data[s + 1] + y; arr[o + 2] = p.data[s + 2] + z
            for (let k = 3; k < 9; k++) arr[o + k] = p.data[s + k]
            arr[o + 9] = tc ? tc[0] : p.data[s + 9]; arr[o + 10] = tc ? tc[1] : p.data[s + 10]; arr[o + 11] = tc ? tc[2] : p.data[s + 11]
            arr[o + 12] = p.data[s + 12]; arr[o + 13] = kind; arr[o + 14] = alpha
            o += NF
          }
        }
        g.n = o
      }
      this.stats.blocksEmitted++
      if (c.solid) emit(c.solid, kind === Kind.Removed ? 'ghost' : c.solidTrans ? 'trans' : 'opaque', 1)
      if (c.water) emit(c.water, kind === Kind.Removed ? 'ghost' : 'trans', 0.72)
    }
    const layers: SectionMesh['layers'] = {}
    for (const k of ['opaque', 'trans', 'ghost'] as Layer[]) { const m = out[k].finish(); if (m) layers[k] = m }
    return { cx, sy, cz, layers, ms: performance.now() - t0 }
  }
}
