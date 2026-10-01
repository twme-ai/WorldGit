// deepslate 模型層轉接層：把 Hub 預處理的資源包（blockstates／models／atlas uv／flags）包成 deepslate 的 Resources 介面。
// 全部 deepslate 型別只在這個檔案與 mesher.ts 出現（決定 #12：鎖定版本、以薄轉接層包起來）。DOM-free，可跑在 Worker 與 Node。
import { BlockColors, BlockDefinition, BlockModel, type BlockFlags, type Identifier, type UV } from 'deepslate'

export interface Pack {
  blockstates: Record<string, unknown>
  models: Record<string, unknown>
  flags: Record<string, BlockFlags>
  atlas: { size: [number, number]; uv: Record<string, UV> }
  biomes: Record<string, { grass: number; foliage: number; dry: number; water: number }>
}

export async function fetchPack(base: string, headers?: HeadersInit): Promise<Pack> {
  const get = async (f: string) => {
    const res = await fetch(`${base}/${f}`, { headers })
    if (!res.ok) throw new Error(`資源包 ${f} 載入失敗：HTTP ${res.status}`)
    return res.json()
  }
  const [blockstates, models, flags, atlas, biomes] = await Promise.all(
    ['blockstates.json', 'models.json', 'flags.json', 'atlas.json', 'biomes.json'].map(get))
  return { blockstates, models, flags, atlas, biomes }
}

// 生物群系染色：deepslate 的 BlockColors 是寫死常數，我們把「需要染色」的方塊改成回傳哨兵色，
// 輸出網格時依方塊所在 biome 換成 biomes.json 的顏色。
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
  const bc = BlockColors as Record<string, unknown>
  for (const n of grass) bc[n] = () => sentinel(Tint.Grass)
  for (const n of foliage) bc[n] = () => sentinel(Tint.Foliage)
  for (const n of water) bc[n] = () => sentinel(Tint.Water)
}

export class PackResources {
  private defs = new Map<string, BlockDefinition | null>()
  private models = new Map<string, BlockModel>()
  private fallbackUV: UV
  constructor(readonly pack: Pack) {
    for (const [id, json] of Object.entries(pack.models)) this.models.set(id, BlockModel.fromJson(json))
    for (const m of this.models.values()) m.flatten(this)
    const [w] = pack.atlas.size
    this.fallbackUV = [0, 0, 16 / w, 16 / w]
  }
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
  getTextureAtlas(): ImageData { throw new Error('圖集影像只在主執行緒使用') }
  getBlockFlags(id: Identifier) { return this.pack.flags[id.path] ?? null }
  getBlockProperties() { return null }
  getDefaultBlockProperties() { return null }
}
