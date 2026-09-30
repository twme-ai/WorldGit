// Web Worker：抓 region → deepslate NBT 解析 → section 資料 → 網格生成 → 以 Transferable 回傳。
import { BlockState, NbtRegion, NbtType, type NbtCompound } from 'deepslate'
import { PackResources, patchBlockColors, type Pack } from './resources.ts'
import { AIR, StateTable, World, parseRegion } from './world.ts'
import { Mesher, type SectionMesh } from './mesher.ts'

patchBlockColors()
let res: PackResources
let A: World, B: World, mesher: Mesher, table: StateTable
let loadedVer = ''
const post = (msg: any, transfer: Transferable[] = []) => (self as any).postMessage(msg, transfer)
const transferOf = (list: SectionMesh[]) => list.flatMap(m => Object.values(m.layers).map(l => l!.data.buffer as ArrayBuffer))
const fetchBuf = async (url: string) => { const r = await fetch(url); if (!r.ok) throw new Error(`${r.status} ${url}`); return new Uint8Array(await r.arrayBuffer()) }
const fetchJson = async (url: string) => (await fetch(url)).json()

async function init(ver: string) {
  const t0 = performance.now()
  const b = `/work/assets/${ver}/pack/`
  const [blockstates, models, flags, atlas, biomes] = await Promise.all(['blockstates', 'models', 'flags', 'atlas', 'biomes'].map(n => fetchJson(b + n + '.json')))
  const tFetch = performance.now() - t0
  res = new PackResources({ blockstates, models, flags, atlas, biomes } as Pack)
  return { fetchMs: tFetch, buildResourcesMs: performance.now() - t0 - tFetch, modelFlattenMs: res.loadMs, models: Object.keys(models).length }
}

const layoutDir = (ver: string, kind: 'region' | 'entities') =>
  ver === '26.2' ? `/work/worlds/${ver}/baseline/world/dimensions/minecraft/overworld/${kind}` : `/work/worlds/${ver}/baseline/world/${kind}`

function parseEdits(world: World, edits: any) {
  // 寫時複製：B 與 A 共用未修改的 section（同一個物件參考），diff 時可 O(1) 跳過
  const cow = new Map<string, boolean>()
  const setBlock = (x: number, y: number, z: number, st: BlockState) => {
    const cx = x >> 4, cz = z >> 4, sy = y >> 4
    const ch = world.chunk(cx, cz); if (!ch) { console.warn('edit outside loaded chunks', x, y, z); return }
    let s = ch.sections.get(sy); if (!s) return
    const k = `${cx},${sy},${cz}`
    if (!cow.has(k)) {
      const ids = new Uint16Array(4096); if (s.ids) ids.set(s.ids); else ids.fill(s.single)
      s = { ids, single: s.single, biomes: s.biomes }; ch.sections.set(sy, s); cow.set(k, true)
    }
    s.ids![((y & 15) << 8) | ((z & 15) << 4) | (x & 15)] = table.intern(st)
  }
  let n = 0
  for (const op of edits.ops) {
    const st = BlockState.parse(op.state)
    if (op.op === 'set') { setBlock(op.pos[0], op.pos[1], op.pos[2], st); n++ }
    else for (let x = op.from[0]; x <= op.to[0]; x++) for (let y = op.from[1]; y <= op.to[1]; y++) for (let z = op.from[2]; z <= op.to[2]; z++) { setBlock(x, y, z, st); n++ }
  }
  return n
}

function forkWorld(a: World): World {
  const b = new World(a.table)
  for (const [k, c] of a.chunks) b.chunks.set(k, { ...c, sections: new Map(c.sections) })
  return b
}

const text = (t: any): string => { try { const v = t?.getAsString?.() ?? ''; if (v.startsWith('{') || v.startsWith('"')) { try { const j = JSON.parse(v); return typeof j === 'string' ? j : j.text ?? v } catch { return v } } return v } catch { return '' } }

async function load(ver: string, x0: number, x1: number, z0: number, z1: number, forceBiome?: string) {
  const timings: Record<string, number> = {}
  let t = performance.now()
  table = new StateTable(); A = new World(table)
  let bytes = 0
  const rin = (cx: number, cz: number) => cx >= x0 && cx <= x1 && cz >= z0 && cz <= z1
  const regions: [number, number][] = []
  for (let rx = x0 >> 5; rx <= x1 >> 5; rx++) for (let rz = z0 >> 5; rz <= z1 >> 5; rz++) regions.push([rx, rz])
  const bufs = await Promise.all(regions.map(([rx, rz]) => fetchBuf(`${layoutDir(ver, 'region')}/r.${rx}.${rz}.mca`)))
  timings.fetchRegionsMs = performance.now() - t; t = performance.now()
  const stats = { chunks: 0, inflateAndNbtMs: 0, convertMs: 0 }
  regions.forEach(([rx, rz], i) => {
    bytes += bufs[i].length
    const r = parseRegion(bufs[i], rx, rz, table, rin)
    r.chunks.forEach(c => A.add(c)); stats.chunks += r.stats.chunks; stats.inflateAndNbtMs += r.stats.inflateAndNbtMs; stats.convertMs += r.stats.convertMs
  })
  timings.parseRegionsMs = performance.now() - t
  if (forceBiome) { const bid = table.biome('minecraft:' + forceBiome); for (const c of A.chunks.values()) for (const sec of c.sections.values()) sec.biomes.fill(bid) } // 測試用：強制全部 biome
  // 實體（entities/*.mca）——同樣用 deepslate 的 NbtRegion 讀
  t = performance.now()
  const entities: any[] = []
  for (const [rx, rz] of regions) {
    try {
      const reg = NbtRegion.read(await fetchBuf(`${layoutDir(ver, 'entities')}/r.${rx}.${rz}.mca`))
      for (const [lx, lz] of reg.getChunkPositions()) {
        if (!rin(rx * 32 + lx, rz * 32 + lz)) continue
        const root = reg.findChunk(lx, lz)!.getRoot()
        for (const e of root.getList('Entities', NbtType.Compound).getItems()) {
          const pos = e.getList('Pos', NbtType.Double).getItems().map(d => d.getAsNumber())
          entities.push({ id: e.getString('id').replace('minecraft:', ''), pos, name: e.has('CustomName') ? text(e.get('CustomName')) : '', extra: e.has('text') ? text(e.get('text')) : '' })
        }
      }
    } catch (err) { console.warn('entities', rx, rz, String(err)) }
  }
  timings.entitiesMs = performance.now() - t
  // 告示牌文字（block entity）
  const signs: any[] = []
  for (const c of A.chunks.values()) for (const be of c.blockEntities.values()) {
    if (be.getString('id').endsWith('sign')) {
      const ft = be.getCompound('front_text')
      signs.push({ x: be.getNumber('x'), y: be.getNumber('y'), z: be.getNumber('z'), lines: ft.getList('messages', NbtType.String).getItems().map(text) })
    }
  }
  // B = A + 編輯（模擬另一個 commit）
  t = performance.now()
  B = forkWorld(A)
  const editN = parseEdits(B, await fetchJson('/diff-edits.json'))
  timings.applyEditsMs = performance.now() - t
  mesher = new Mesher(res, A)
  loadedVer = ver
  let sections = 0; for (const c of A.chunks.values()) sections += c.sections.size
  return { timings, stats: { ...stats, regionBytes: bytes, sections, statesInPalette: table.states.length, editedBlocks: editN }, entities, signs }
}

/** 找出 A、B 之間內容不同的 section（以物件參考比較 → O(section 數)；不同時再逐格比對） */
function diffSections() {
  const changed: { cx: number; sy: number; cz: number }[] = []
  const counts = { added: 0, removed: 0, modified: 0 }
  const samples: string[] = []
  for (const [k, ca] of A.chunks) {
    const cb = B.chunks.get(k)!
    for (const [sy, sa] of ca.sections) {
      const sb = cb.sections.get(sy)!
      if (sa === sb) continue
      changed.push({ cx: ca.cx, sy, cz: ca.cz })
      for (let i = 0; i < 4096; i++) {
        const a = sa.ids ? sa.ids[i] : sa.single, b = sb.ids ? sb.ids[i] : sb.single
        if (a === b) continue
        const aa = table.states[a].getName().path.endsWith('air'), ba = table.states[b].getName().path.endsWith('air')
        if (aa && !ba) counts.added++; else if (!aa && ba) counts.removed++; else if (!aa && !ba) counts.modified++
      }
    }
  }
  return { changed, counts, samples }
}

self.onmessage = async (ev: MessageEvent) => {
  const m = ev.data
  try {
    if (m.type === 'init') {
      post({ type: 'init', id: m.id, ...await init(m.ver) })
    } else if (m.type === 'load') {
      post({ type: 'load', id: m.id, ...await load(m.ver, m.x0, m.x1, m.z0, m.z1, m.forceBiome) })
    } else if (m.type === 'mesh') {
      // 1) base：整個 A（未變）；2) affected：有變動的 section + 其 26 鄰居 各出 A / B / diff 三版
      const t0 = performance.now()
      const { changed, counts } = diffSections()
      const affected = new Set<string>()
      for (const c of changed) for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) for (let dz = -1; dz <= 1; dz++) affected.add(`${c.cx + dx},${c.sy + dy},${c.cz + dz}`)
      const base: SectionMesh[] = []
      let flush = performance.now()
      const secs = [...A.chunks.values()].flatMap(c => [...c.sections.keys()].map(sy => [c.cx, sy, c.cz] as const))
      let baseMs = 0
      for (const [cx, sy, cz] of secs) {
        const sm = mesher.meshSection(cx, sy, cz, A)
        baseMs += sm.ms
        if (Object.keys(sm.layers).length) base.push(sm)
        if (base.length >= 200 || performance.now() - flush > 200) { const l = base.splice(0); post({ type: 'meshes', set: 'base', list: l }, transferOf(l)); flush = performance.now() }
      }
      if (base.length) { post({ type: 'meshes', set: 'base', list: base }, transferOf(base)) }
      const tBase = performance.now() - t0
      const sets: Record<string, SectionMesh[]> = { A: [], B: [], D: [] }
      let affMs = 0
      for (const key of affected) {
        const [cx, sy, cz] = key.split(',').map(Number)
        if (!A.section(cx, sy, cz)) continue
        const a = mesher.meshSection(cx, sy, cz, A); const b = mesher.meshSection(cx, sy, cz, B); const d = mesher.meshSection(cx, sy, cz, B, A)
        affMs += a.ms + b.ms + d.ms
        sets.A.push(a); sets.B.push(b); sets.D.push(d)
      }
      for (const k of ['A', 'B', 'D']) post({ type: 'meshes', set: k, list: sets[k] }, transferOf(sets[k]))
      post({ type: 'mesh', id: m.id, baseWallMs: tBase, baseMeshMs: baseMs, affectedSections: affected.size, changedSections: changed.length, affectedMeshMs: affMs, diffCounts: counts, mesherStats: mesher.stats, totalMs: performance.now() - t0 })
    } else if (m.type === 'edit') {
      // 增量更新示範：改 B 的一格 → 只重建該 section 與相鄰 section
      const t0 = performance.now()
      const [x, y, z] = m.pos as number[]
      const st = BlockState.parse(m.state)
      parseEdits(B, { ops: [{ op: 'set', pos: [x, y, z], state: m.state }] })
      const cx = x >> 4, sy = y >> 4, cz = z >> 4
      const list: SectionMesh[] = []
      const touch = new Set<string>([`${cx},${sy},${cz}`])
      if ((x & 15) === 0) touch.add(`${cx - 1},${sy},${cz}`); if ((x & 15) === 15) touch.add(`${cx + 1},${sy},${cz}`)
      if ((y & 15) === 0) touch.add(`${cx},${sy - 1},${cz}`); if ((y & 15) === 15) touch.add(`${cx},${sy + 1},${cz}`)
      if ((z & 15) === 0) touch.add(`${cx},${sy},${cz - 1}`); if ((z & 15) === 15) touch.add(`${cx},${sy},${cz + 1}`)
      for (const k of touch) { const [a, b, c] = k.split(',').map(Number); list.push(mesher.meshSection(a, b, c, B)) }
      void st
      post({ type: 'edit', id: m.id, list, rebuiltSections: list.length, ms: performance.now() - t0 }, transferOf(list))
    }
  } catch (e: any) {
    post({ type: 'error', id: m.id, error: String(e?.stack ?? e) })
  }
}
void AIR; void loadedVer
