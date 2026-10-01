// section 網格生成（Worker 內執行，DOM-free）：
//  - 完整不透明方塊與同類的完整方塊面做 greedy 合併（同 state、同 diff 種類、同染色才合併）；
//  - 其他方塊（樓梯、柵欄、植物、水…）用 deepslate 的 BlockDefinition／SpecialRenderers 產生四邊形，
//    以（state, 剔除遮罩）為鍵快取；
//  - diff：before 世界與 after 世界逐格比較，分成 新增／移除（鬼影）／修改；
//  - 輸出 16 B 頂點（vertex.ts），分 opaque／trans／ghost 三個繪製批次。
import { BlockState, Direction, Mesh, NbtCompound, NbtList, NbtString, NbtType, SpecialRenderers, type Cull, type Quad } from 'deepslate'
import { PackResources, Tint, TINT_SENTINEL_B, TINT_SENTINEL_R } from './resources.ts'
import { FLAG_CONTEXT, FLAG_WATER, RECT_DIRECT, VertexBuilder, clamp8 } from './vertex.ts'

export const Kind = { Same: 0, Added: 1, Removed: 2, Modified: 3 } as const
export type Layer = 'opaque' | 'trans' | 'ghost'

export interface MeshRequest {
  id: number; cx: number; sy: number; cz: number
  /** 18³（含 1 格外圍）。-1 = 未載入。index = (y*18+z)*18+x */
  after: Int16Array
  /** 套用 diff 還原的舊世界；null 表示沒有 diff（與 after 相同）。 */
  before: Int16Array | null
  /** core diff 種類（包含 state 相同而 BE 改變的格子）。 */
  kinds?: Uint8Array | null
  /** 只看變動時仍顯示的周圍一格。 */
  context?: Uint8Array | null
  /** section 內 4×4×4 biome 格的全域 biome 編號（null = 全部 plains）。 */
  biomes: Uint16Array | null
  /** section 內索引 → JSON（橫幅圖樣等）。 */
  blockEntities: [number, string][]
}

export interface MeshResult {
  id: number; cx: number; sy: number; cz: number
  layers: Partial<Record<Layer, ArrayBuffer>>
  ms: number
  stats: { blocks: number; greedyQuads: number; unitFaces: number; modelQuads: number }
}

const P = 18
const OFF = [P * P, -P * P, -P, P, 1, -1] // up down north south east west
const DIR_NAMES = ['up', 'down', 'north', 'south', 'east', 'west'] as const
const FACE_LIGHT = [1.0, 0.6, 0.9, 0.9, 0.8, 0.8]
// 各方向的法線軸 與 face 平面內的兩軸 (a, b)：軸編號 0=x 1=y 2=z
const AXES: [number, number, number][] = [[1, 0, 2], [1, 0, 2], [2, 0, 1], [2, 0, 1], [0, 2, 1], [0, 2, 1]]

interface StateInfo {
  name: string; air: boolean; opaque: boolean; self: boolean; semi: boolean; water: boolean
  hasWater: boolean; banner: boolean; cube: CubeFace[] | null
}

/** 完整方塊某一面的模板（單位面），greedy 合併時依寬高拉伸。 */
interface CubeFace {
  /** 4 個頂點在方塊內的位置（0 或 1）與貼圖本地座標（0 或 1） */
  t: [number, number, number][]
  tu: number[]; tv: number[]
  rect: number; uDepA: boolean; vDepA: boolean; tint: Tint; color: [number, number, number]
}

/** 單一方塊的四邊形（浮點，方塊內座標）；每頂點 x y z u v r g b light。 */
interface Packed { data: Float32Array; tints: Uint8Array; nq: number }
interface Cached { solid: Packed | null; solidTrans: boolean; water: Packed | null }

export class Mesher {
  private infos: (StateInfo | undefined)[] = []
  private states: BlockState[] = []
  private biomeNames: string[] = []
  private biomeTint = new Map<number, number[]>()
  private cache = new Map<string, Cached>()
  private rects = new Map<string, number>()
  private masks: Int32Array[] = Array.from({ length: 6 * 16 }, () => new Int32Array(256))
  private keySid: number[] = []
  private keyKind: number[] = []
  private keyTint: number[] = []
  private keyIds = new Map<number, number>()

  constructor(readonly res: PackResources) {
    const [W] = res.pack.atlas.size
    let i = 0
    for (const uv of Object.values(res.pack.atlas.uv)) this.rects.set(rectKey(uv, W), i++)
    this.rectCount = i
  }
  rectCount: number

  /** 圖集矩形表（u0,v0,u1,v1 每個 rect 4 個 float），給 GPU 的 rect 紋理。順序與 rect 索引一致。 */
  static rectTable(pack: { atlas: { uv: Record<string, number[]> } }): Float32Array {
    const out = new Float32Array(Object.keys(pack.atlas.uv).length * 4)
    let i = 0
    for (const uv of Object.values(pack.atlas.uv)) { out.set(uv.slice(0, 4), i); i += 4 }
    return out
  }

  setStates(from: number, list: string[]) {
    for (let i = 0; i < list.length; i++) { this.states[from + i] = BlockState.parse(list[i]); this.infos[from + i] = undefined }
  }
  setBiomes(from: number, list: string[]) {
    for (let i = 0; i < list.length; i++) this.biomeNames[from + i] = list[i]
    this.biomeTint.clear()
  }

  info(id: number): StateInfo {
    let i = this.infos[id]
    if (i) return i
    const s = this.states[id]
    const name = s.getName().path
    const f = this.res.getBlockFlags(s.getName())
    i = this.infos[id] = {
      name, air: name === 'air' || name === 'cave_air' || name === 'void_air',
      opaque: !!f?.opaque, self: !!f?.self_culling, semi: !!f?.semi_transparent, water: name === 'water',
      banner: name.endsWith('_banner'), hasWater: s.isWaterlogged(), cube: null,
    }
    if (!i.air && !i.water && !i.hasWater && !i.banner) i.cube = this.cubeTemplate(id)
    return i
  }

  private tintRgb(biome: number, t: Tint): number[] {
    let c = this.biomeTint.get(biome)
    if (!c) {
      const b = this.res.pack.biomes[this.biomeNames[biome]] ?? this.res.pack.biomes['minecraft:plains'] ?? { grass: 0x7cbd6b, foliage: 0x77ab2f, dry: 0x8f7a46, water: 0x3f76e4 }
      const cols = [0xffffff, b.grass, b.foliage, b.water, b.dry]
      c = cols.flatMap((v) => [(v >> 16) & 255, (v >> 8) & 255, v & 255])
      this.biomeTint.set(biome, c)
    }
    return [c[t * 3], c[t * 3 + 1], c[t * 3 + 2]]
  }

  // ---- 單一方塊四邊形（deepslate）----

  private solidMesh(sid: number, cullMask: number, waterMask: number, nbt: NbtCompound | undefined) {
    const state = this.states[sid]
    const inf = this.info(sid)
    const cull: Cull = {}, wcull: Cull = {}
    DIR_NAMES.forEach((d, i) => { cull[d] = !!(cullMask & (1 << i)); wcull[d] = !!(waterMask & (1 << i)) })
    const name = state.getName()
    const mesh = new Mesh(), wmesh = new Mesh()
    try {
      const props = { ...state.getProperties() }
      const def = this.res.getBlockDefinition(name)
      if (def) mesh.merge(def.getMesh(name, props, this.res, this.res, cull))
      const plain = inf.water ? state : props.waterlogged === 'true' ? new BlockState(name, { ...props, waterlogged: 'false' }) : state
      const special = SpecialRenderers.getBlockMesh(plain, nbt, this.res, inf.water ? wcull : cull)
      if (inf.water) wmesh.merge(special); else if (!special.isEmpty()) mesh.merge(special)
      if (!inf.water && props.waterlogged === 'true') wmesh.merge(SpecialRenderers.getBlockMesh(BlockState.WATER, undefined, this.res, wcull))
    } catch (e) { console.error('mesh error', state.toString(), e) }
    return { mesh, wmesh }
  }

  private pack(mesh: Mesh): Packed | null {
    const nq = mesh.quads.length
    if (!nq) return null
    const data = new Float32Array(nq * 4 * 9), tints = new Uint8Array(nq)
    mesh.quads.forEach((q: Quad, qi) => {
      const n = q.normal()
      const light = n.y * 0.2 + Math.abs(n.z) * 0.1 + 0.8
      const vs = q.vertices()
      const c = vs[0].color
      let tint = 0
      if (Math.abs(c[0] - TINT_SENTINEL_R) < 1e-6 && Math.abs(c[2] - TINT_SENTINEL_B) < 1e-6) tint = Math.round(c[1] * 100)
      tints[qi] = tint
      for (let v = 0; v < 4; v++) {
        const vx = vs[v], o = (qi * 4 + v) * 9
        const col = tint ? [1, 1, 1] : vx.color
        data[o] = vx.pos.x; data[o + 1] = vx.pos.y; data[o + 2] = vx.pos.z
        data[o + 3] = vx.texture?.[0] ?? 0; data[o + 4] = vx.texture?.[1] ?? 0
        data[o + 5] = col[0]; data[o + 6] = col[1]; data[o + 7] = col[2]; data[o + 8] = light
      }
    })
    return { data, tints, nq }
  }

  private quadsFor(sid: number, mask: number, wmask: number, nbt: NbtCompound | undefined, nbtKey: string): Cached {
    const key = `${sid}|${mask}|${wmask}|${nbtKey}`
    let c = this.cache.get(key)
    if (c) return c
    const { mesh, wmesh } = this.solidMesh(sid, mask, wmask, nbt)
    c = { solid: this.pack(mesh), solidTrans: this.info(sid).semi, water: this.pack(wmesh) }
    this.cache.set(key, c)
    return c
  }

  // ---- 完整方塊模板 ----

  /** 解析 state 的完整方塊面模板；不是「6 個單位面、圖集矩形可定位、UV 為整張貼圖」就回傳 null。 */
  private cubeTemplate(sid: number): CubeFace[] | null {
    const { mesh } = this.solidMesh(sid, 0, 0, undefined)
    if (mesh.quads.length !== 6) return null
    const [W] = this.res.pack.atlas.size
    const faces: (CubeFace | undefined)[] = new Array(6)
    for (const q of mesh.quads) {
      const vs = q.vertices()
      const pos = vs.map((v) => [v.pos.x, v.pos.y, v.pos.z])
      let axis = -1
      for (let k = 0; k < 3; k++) if (pos.every((p) => Math.abs(p[k] - pos[0][k]) < 1e-4)) axis = k
      if (axis < 0) return null
      const val = pos[0][axis]
      if (Math.abs(val) > 1e-4 && Math.abs(val - 1) > 1e-4) return null
      const d = axis === 1 ? (val > 0.5 ? 0 : 1) : axis === 2 ? (val > 0.5 ? 3 : 2) : (val > 0.5 ? 4 : 5)
      if (faces[d]) return null
      const [, aAxis, bAxis] = AXES[d]
      const t: [number, number, number][] = []
      const corners = new Set<string>()
      for (const p of pos) {
        for (const k of [aAxis, bAxis]) if (Math.abs(p[k]) > 1e-4 && Math.abs(p[k] - 1) > 1e-4) return null
        t.push([Math.round(p[0]), Math.round(p[1]), Math.round(p[2])])
        corners.add(`${Math.round(p[aAxis])},${Math.round(p[bAxis])}`)
      }
      if (corners.size !== 4) return null
      const lim = vs[0].textureLimit
      if (!lim || vs.some((v) => !v.texture)) return null
      const rect = this.rects.get(rectKey([lim[0], lim[1], lim[2], lim[3]], W))
      if (rect === undefined) return null
      const rw = lim[2] - lim[0], rh = lim[3] - lim[1]
      if (rw <= 0 || rh <= 0) return null
      const tu: number[] = [], tv: number[] = []
      for (const v of vs) {
        const u = (v.texture![0] - lim[0]) / rw, w = (v.texture![1] - lim[1]) / rh
        if ((Math.abs(u) > 0.02 && Math.abs(u - 1) > 0.02) || (Math.abs(w) > 0.02 && Math.abs(w - 1) > 0.02)) return null
        tu.push(Math.round(u)); tv.push(Math.round(w))
      }
      const la = t.map((p) => p[aAxis]), lb = t.map((p) => p[bAxis])
      const dep = (arr: number[]): boolean | null => {
        if (arr.every((x, i) => x === la[i]) || arr.every((x, i) => x === 1 - la[i])) return true
        if (arr.every((x, i) => x === lb[i]) || arr.every((x, i) => x === 1 - lb[i])) return false
        return null
      }
      const uDepA = dep(tu), vDepA = dep(tv)
      if (uDepA === null || vDepA === null) return null
      const c0 = vs[0].color
      let tint = 0
      if (Math.abs(c0[0] - TINT_SENTINEL_R) < 1e-6 && Math.abs(c0[2] - TINT_SENTINEL_B) < 1e-6) tint = Math.round(c0[1] * 100)
      faces[d] = { t, tu, tv, rect, uDepA, vDepA, tint: tint as Tint, color: tint ? [1, 1, 1] : [c0[0], c0[1], c0[2]] }
    }
    return faces.every(Boolean) ? (faces as CubeFace[]) : null
  }

  // ---- 主流程 ----

  private culledFace(me: StateInfo, nid: number): boolean {
    if (nid < 0) return false
    const n = this.info(nid)
    if (me.self && n.name === me.name) return true
    return n.opaque
  }

  private opq(id: number) { return id >= 0 && this.info(id).opaque }

  private internKey(sid: number, kind: number, tint: number): number {
    const k = (sid * 64 + kind) * 16777216 + tint
    let id = this.keyIds.get(k)
    if (id === undefined) { id = this.keySid.length; this.keySid.push(sid); this.keyKind.push(kind); this.keyTint.push(tint); this.keyIds.set(k, id) }
    return id + 1
  }

  mesh(req: MeshRequest): MeshResult {
    const t0 = performance.now()
    const out: Record<Layer, VertexBuilder> = { opaque: new VertexBuilder(), trans: new VertexBuilder(), ghost: new VertexBuilder() }
    const stats = { blocks: 0, greedyQuads: 0, unitFaces: 0, modelQuads: 0 }
    const pb = req.after, pa = req.before ?? req.after
    const bes = new Map(req.blockEntities)
    for (const m of this.masks) m.fill(0)
    this.keySid.length = 0; this.keyKind.length = 0; this.keyTint.length = 0; this.keyIds.clear()
    let anyCube = false

    for (let y = 0; y < 16; y++) for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
      const idx = ((y + 1) * P + (z + 1)) * P + (x + 1)
      const b = pb[idx], a = pa[idx]
      let sid = b, ctx = pb, kind: number = Kind.Same
      if (a !== b) {
        const airA = this.isAir(a), airB = this.isAir(b)
        if (airA && airB) continue
        if (airA) kind = Kind.Added
        else if (airB) { kind = Kind.Removed; sid = a; ctx = pa }
        else kind = Kind.Modified
      } else if (this.isAir(b)) continue
      if (sid < 0) continue
      const cell = (y << 8) | (z << 4) | x
      if (req.kinds?.[cell]) kind = req.kinds[cell]
      if (kind === Kind.Same && req.context?.[cell]) kind |= FLAG_CONTEXT
      stats.blocks++
      const info = this.info(sid)
      const biomeId = req.biomes ? req.biomes[((y >> 2) << 4) | ((z >> 2) << 2) | (x >> 2)] : 0
      const beKey = (y << 8) | (z << 4) | x
      if (info.cube && !bes.has(beKey)) {
        // 完整方塊：每個露出的面記入 greedy mask
        for (let d = 0; d < 6; d++) {
          if (this.culledFace(info, ctx[idx + OFF[d]])) continue
          const face = info.cube[d]
          let tint = 0xffffff
          if (face.tint) { const c = this.tintRgb(biomeId, face.tint); tint = (c[0] << 16) | (c[1] << 8) | c[2] }
          const [n, a2, b2] = AXES[d]
          const pos = [x, y, z]
          this.masks[d * 16 + pos[n]][pos[b2] * 16 + pos[a2]] = this.internKey(sid, kind, tint)
          anyCube = true
          stats.unitFaces++
        }
        continue
      }
      if (info.opaque && this.opq(ctx[idx + 1]) && this.opq(ctx[idx - 1]) && this.opq(ctx[idx + P]) && this.opq(ctx[idx - P]) && this.opq(ctx[idx + P * P]) && this.opq(ctx[idx - P * P])) continue
      const [mask, wmask] = this.cullMasks(ctx, idx, info)
      let nbt: NbtCompound | undefined, nbtKey = ''
      const beJson = bes.get(beKey)
      if (info.banner && beJson) { nbt = bannerNbt(beJson); nbtKey = beJson }
      const c = this.quadsFor(sid, mask, wmask, nbt, nbtKey)
      if (c.solid) this.emitModel(out[kind === Kind.Removed ? 'ghost' : c.solidTrans ? 'trans' : 'opaque'], c.solid, kind, biomeId, x, y, z, 0)
      if (c.water) this.emitModel(out[kind === Kind.Removed ? 'ghost' : 'trans'], c.water, kind, biomeId, x, y, z, FLAG_WATER)
    }
    if (anyCube) this.greedy(out, stats)
    const layers: MeshResult['layers'] = {}
    for (const k of ['opaque', 'trans', 'ghost'] as Layer[]) { const buf = out[k].finish(); if (buf) layers[k] = buf }
    return { id: req.id, cx: req.cx, sy: req.sy, cz: req.cz, layers, ms: performance.now() - t0, stats }
  }

  private isAir(id: number) { return id < 0 ? true : this.info(id).air }

  private cullMasks(arr: Int16Array, idx: number, me: StateInfo): [number, number] {
    let mask = 0, wmask = 0
    for (let i = 0; i < 6; i++) {
      const nid = arr[idx + OFF[i]]
      if (nid < 0) continue
      const n = this.info(nid)
      let cull: boolean
      if (me.name === n.name && n.self) cull = true
      else if (n.opaque) cull = !(i === 0 && me.hasWater)
      else cull = me.hasWater && n.hasWater
      if (cull) mask |= 1 << i
      if (n.opaque || n.hasWater) wmask |= 1 << i
    }
    return [mask, wmask]
  }

  private emitModel(g: VertexBuilder, p: Packed, kind: number, biome: number, x: number, y: number, z: number, extra: number) {
    for (let q = 0; q < p.nq; q++) {
      const tint = p.tints[q]
      const tc = tint ? this.tintRgb(biome, tint as Tint) : null
      for (let v = 0; v < 4; v++) {
        const s = (q * 4 + v) * 9, d = p.data, l = d[s + 8]
        const r = tc ? tc[0] / 255 : d[s + 5], gg = tc ? tc[1] / 255 : d[s + 6], bb = tc ? tc[2] / 255 : d[s + 7]
        g.push(d[s] + x, d[s + 1] + y, d[s + 2] + z, Math.round(clampUnit(d[s + 3]) * 65535), Math.round(clampUnit(d[s + 4]) * 65535), RECT_DIRECT,
          clamp8(r * l * 255), clamp8(gg * l * 255), clamp8(bb * l * 255), kind | extra)
      }
    }
  }

  /** 對六個方向、每個 plane 的 mask 做 greedy 合併並輸出。 */
  private greedy(out: Record<Layer, VertexBuilder>, stats: MeshResult['stats']) {
    for (let d = 0; d < 6; d++) {
      const [nAxis, aAxis, bAxis] = AXES[d]
      for (let n = 0; n < 16; n++) {
        const mask = this.masks[d * 16 + n]
        for (let b = 0; b < 16; b++) {
          for (let a = 0; a < 16; ) {
            const k = mask[b * 16 + a]
            if (!k) { a++; continue }
            let w = 1
            while (a + w < 16 && mask[b * 16 + a + w] === k) w++
            let h = 1
            outer: while (b + h < 16) {
              for (let i = 0; i < w; i++) if (mask[(b + h) * 16 + a + i] !== k) break outer
              h++
            }
            for (let j = 0; j < h; j++) for (let i = 0; i < w; i++) mask[(b + j) * 16 + a + i] = 0
            this.emitFace(out, d, nAxis, aAxis, bAxis, n, a, b, w, h, k - 1)
            stats.greedyQuads++
            a += w
          }
        }
      }
    }
  }

  private emitFace(out: Record<Layer, VertexBuilder>, d: number, nAxis: number, aAxis: number, bAxis: number, n: number, a: number, b: number, w: number, h: number, key: number) {
    const sid = this.keySid[key], kind = this.keyKind[key], tint = this.keyTint[key]
    const info = this.info(sid), face = info.cube![d]
    const g = out[kind === Kind.Removed ? 'ghost' : info.semi ? 'trans' : 'opaque']
    const l = FACE_LIGHT[d] * 255
    const r = clamp8(((tint >> 16) & 255) * face.color[0] * l / 255), gg = clamp8(((tint >> 8) & 255) * face.color[1] * l / 255), bb = clamp8((tint & 255) * face.color[2] * l / 255)
    for (let v = 0; v < 4; v++) {
      const p = [0, 0, 0]
      p[nAxis] = n + face.t[v][nAxis]
      p[aAxis] = a + face.t[v][aAxis] * w
      p[bAxis] = b + face.t[v][bAxis] * h
      const u = face.tu[v] * (face.uDepA ? w : h), vv = face.tv[v] * (face.vDepA ? w : h)
      g.push(p[0], p[1], p[2], u * 256, vv * 256, face.rect, r, gg, bb, kind)
    }
  }
}

function clampUnit(v: number) { return v < 0 ? 0 : v > 1 ? 1 : v }

function rectKey(uv: ArrayLike<number>, W: number) {
  return `${Math.round(uv[0] * W)},${Math.round(uv[1] * W)},${Math.round(uv[2] * W)},${Math.round(uv[3] * W)}`
}

function bannerNbt(json: string): NbtCompound | undefined {
  try {
    const o = JSON.parse(json) as { patterns?: { pattern: string; color: string }[] }
    if (!o.patterns?.length) return undefined
    const list = new NbtList(o.patterns.map((p) => new NbtCompound(new Map([['pattern', new NbtString(p.pattern)], ['color', new NbtString(p.color)]]))), NbtType.Compound)
    return new NbtCompound(new Map([['patterns', list]]))
  } catch { return undefined }
}

export { Direction }
