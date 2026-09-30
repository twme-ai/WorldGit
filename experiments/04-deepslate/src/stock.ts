// 「原封不動用 deepslate」對照組：用它自己的 Structure 介面 + StructureRenderer + ChunkBuilder 畫場景方塊。
// 不打補丁 BlockColors（固定色、非生物群系），單執行緒、無 worker。用來量測「直接當依賴」的實際表現。
import { mat4 } from 'gl-matrix'
import { BlockPos, BlockState, StructureRenderer, TextureAtlas, type NbtCompound, type StructureProvider } from 'deepslate'
import { PackResources, type Pack } from './resources.ts'
import { StateTable, World, parseRegion } from './world.ts'
import { CAMS } from './cams.ts'

export async function run(q: URLSearchParams) {
  const ver = q.get('ver') ?? '1.21.11'
  const big = q.get('area') === '4x4' // 4x4 chunk 的實際地形（含海底，y -64..69），用來看原生 deepslate 在真實規模下的表現
    const canvas = document.getElementById('gl') as HTMLCanvasElement
  canvas.width = 1280; canvas.height = 720; canvas.style.width = '1280px'; canvas.style.height = '720px'
  document.getElementById('ov')!.remove(); document.getElementById('panel')!.remove()
  const stats: Record<string, any> = { mode: 'stock', ver }; (window as any).__stats = stats
  const b = `/work/assets/${ver}/pack/`
  const [blockstates, models, flags, atlas, biomes] = await Promise.all(['blockstates', 'models', 'flags', 'atlas', 'biomes'].map(async n => (await fetch(b + n + '.json')).json()))
  const t0 = performance.now()
  const res = new PackResources({ blockstates, models, flags, atlas, biomes } as Pack)
  const img = await createImageBitmap(await (await fetch(b + 'atlas.png')).blob())
  const c2 = document.createElement('canvas'); c2.width = img.width; c2.height = img.height
  const cx2 = c2.getContext('2d')!; cx2.drawImage(img, 0, 0)
  const ta = new TextureAtlas(cx2.getImageData(0, 0, img.width, img.height), atlas.uv)
  ;(res as any).getTextureAtlas = () => ta.getTextureAtlas()
  // 載入 chunk（主執行緒）
  const table = new StateTable(); const world = new World(table)
  const dir = ver === '26.2' ? `/work/worlds/${ver}/baseline/world/dimensions/minecraft/overworld/region` : `/work/worlds/${ver}/baseline/world/region`
  for (const [rx, rz] of [[-1, -1], [0, -1], [-1, 0], [0, 0]] as [number, number][]) {
    const buf = new Uint8Array(await (await fetch(`${dir}/r.${rx}.${rz}.mca`)).arrayBuffer())
    parseRegion(buf, rx, rz, table, (cx, cz) => big ? (cx >= -2 && cx <= 1 && cz >= -2 && cz <= 1) : (cx >= -1 && cx <= 3 && cz >= -1 && cz <= 2)).chunks.forEach(c => world.add(c))
  }
  stats.loadMs = performance.now() - t0
  const O = big ? [-32, -64, -32] : [-8, 148, -8], S: [number, number, number] = big ? [64, 134, 64] : [65, 29, 49]
  const blocks = new Map<number, { pos: BlockPos; state: BlockState; nbt?: NbtCompound }>()
  const key = (x: number, y: number, z: number) => (x * 512 + y) * 512 + z
  for (let x = 0; x < S[0]; x++) for (let y = 0; y < S[1]; y++) for (let z = 0; z < S[2]; z++) {
    const wx = x + O[0], wy = y + O[1], wz = z + O[2]
    const id = world.getState(wx, wy, wz)
    if (id <= 0) continue
    const st = table.states[id]
    if (st.getName().path.endsWith('air')) continue
    const ch = world.chunk(wx >> 4, wz >> 4)
    blocks.set(key(x, y, z), { pos: [x, y, z], state: st, nbt: ch?.blockEntities.get(((wy + 64) << 8) | ((wz & 15) << 4) | (wx & 15)) })
  }
  const structure: StructureProvider = {
    getSize: () => S,
    getBlocks: () => [...blocks.values()],
    getBlock: (p) => blocks.get(key(p[0], p[1], p[2])) ?? null,
  }
  stats.structureBlocks = blocks.size
  const gl = canvas.getContext('webgl', { preserveDrawingBuffer: true })!
  const t1 = performance.now()
  const renderer = new StructureRenderer(gl, structure, res as any, { useInvisibleBlockBuffer: false })
  stats.stockRendererBuildMs = performance.now() - t1 // 含 ChunkBuilder 的整份網格生成 + GL buffer 上傳（主執行緒同步）
  { const t = performance.now(); renderer.updateStructureBuffers([[0, 0, 0] as any]); stats.stockIncrementalUpdateMs = performance.now() - t } // 只更新 1 個 16^3 區塊，但內部仍掃過整個 structure
  const cam = CAMS[q.get('cam') ?? 'overview']
  const view = mat4.create()
  const eye = [cam.pos[0] - O[0], cam.pos[1] - O[1], cam.pos[2] - O[2]] as [number, number, number]
  const at = [cam.at[0] - O[0], cam.at[1] - O[1], cam.at[2] - O[2]] as [number, number, number]
  mat4.lookAt(view, eye, at, [0, 1, 0])
  const frame = () => { gl.clearColor(0.47, 0.65, 1, 1); gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT); renderer.drawStructure(view) }
  const t2 = performance.now(); let n = 0
  frame(); n++; gl.finish(); stats.firstFrameMs = performance.now() - t2
  const t3 = performance.now(); for (let i = 0; i < 10; i++) { frame(); n++ } gl.finish(); stats.msPerFrame = (performance.now() - t3) / 10
  stats.totalToInteractiveMs = performance.now() - t0
  document.body.dataset.ready = '1'
}
