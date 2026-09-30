// 把預處理產物（blockstates/models/atlas uv/flags）包成 deepslate 需要的 Resources 介面。
// 不依賴 DOM，可在 Web Worker 與 Node 中使用。
import { BlockColors, BlockDefinition, BlockModel, Identifier, type BlockFlags } from 'deepslate'
import type { UV } from 'deepslate'

export interface Pack {
  blockstates: Record<string, any>
  models: Record<string, any>
  flags: Record<string, BlockFlags>
  atlas: { size: [number, number]; uv: Record<string, UV> }
  biomes: Record<string, { grass: number; foliage: number; dry: number; water: number }>
}

// 生物群系染色：deepslate 的 BlockColors 是固定色，我們把「需要染色」的方塊改成回傳哨兵色，
// 之後在網格輸出時依方塊所在生物群系替換（見 mesher.ts）。
export const TINT_SENTINEL_R = 0.9001
export const TINT_SENTINEL_B = 0.9002
export enum Tint { None = 0, Grass = 1, Foliage = 2, Water = 3, Dry = 4 }
const sentinel = (t: Tint): [number, number, number] => [TINT_SENTINEL_R, t / 100, TINT_SENTINEL_B]
let patched = false
export function patchBlockColors() {
  if (patched) return
  patched = true
  const grass = ['large_fern', 'tall_grass', 'grass_block', 'fern', 'grass', 'short_grass', 'potted_fern', 'pink_petals', 'wildflowers', 'bush', 'sugar_cane']
  const foliage = ['oak_leaves', 'jungle_leaves', 'acacia_leaves', 'dark_oak_leaves', 'vine', 'mangrove_leaves']
  const water = ['water', 'bubble_column', 'cauldron', 'water_cauldron']
  const bc = BlockColors as Record<string, any>
  for (const n of grass) bc[n] = () => sentinel(Tint.Grass)
  for (const n of foliage) bc[n] = () => sentinel(Tint.Foliage)
  for (const n of water) bc[n] = () => sentinel(Tint.Water)
}

export class PackResources {
  private defs = new Map<string, BlockDefinition | null>()
  private models = new Map<string, BlockModel>()
  private fallbackUV: UV
  constructor(readonly pack: Pack) {
    const t0 = performance.now()
    for (const [id, json] of Object.entries(pack.models)) this.models.set(id, BlockModel.fromJson(json))
    for (const m of this.models.values()) m.flatten(this)
    this.loadMs = performance.now() - t0
    const [w] = pack.atlas.size
    this.fallbackUV = [0, 0, 16 / w, 16 / w]
  }
  loadMs = 0
  getBlockDefinition(id: Identifier) {
    const k = id.path
    let d = this.defs.get(k)
    if (d === undefined) {
      const j = this.pack.blockstates[k]
      d = j ? BlockDefinition.fromJson(j) : null
      this.defs.set(k, d)
    }
    return d
  }
  getBlockModel(id: Identifier) { return this.models.get(id.toString()) ?? null }
  getTextureUV(id: Identifier): UV { return this.pack.atlas.uv[id.toString()] ?? this.fallbackUV }
  getPixelSize() { return 1 / this.pack.atlas.size[0] }
  getTextureAtlas(): ImageData { throw new Error('atlas image is only needed on the main thread') }
  getBlockFlags(id: Identifier) { return this.pack.flags[id.path] ?? null }
  getBlockProperties() { return null }
  getDefaultBlockProperties() { return null }
}
