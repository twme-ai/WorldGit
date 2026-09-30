// Node 端基準：載入資源 → 解析區域 → 網格生成（不含 GL），量測 4x4 與 16x16 chunk。
import fs from 'node:fs'
import path from 'node:path'
import { PackResources, patchBlockColors, type Pack } from '../src/resources.ts'
import { StateTable, World, parseRegion } from '../src/world.ts'
import { Mesher } from '../src/mesher.ts'

const ver = process.argv[2] ?? '1.21.11'
const packDir = path.resolve(import.meta.dirname, '../../../.work/assets', ver, 'pack')
const rj = (f: string) => JSON.parse(fs.readFileSync(path.join(packDir, f), 'utf8'))
patchBlockColors()
let t = performance.now()
const pack: Pack = { blockstates: rj('blockstates.json'), models: rj('models.json'), flags: rj('flags.json'), atlas: rj('atlas.json'), biomes: rj('biomes.json') }
const res = new PackResources(pack)
console.log('resources', Math.round(performance.now() - t), 'ms (models flatten', Math.round(res.loadMs), 'ms)')
const base = path.resolve(import.meta.dirname, '../../../.work/worlds', ver, 'baseline/world')
const regionDir = ver === '26.2' ? path.join(base, 'dimensions/minecraft/overworld/region') : path.join(base, 'region')
for (const [name, lo, hi] of [['4x4', -2, 1], ['scene(5x4)', -1, 3], ['16x16', -8, 7]] as const) {
  const table = new StateTable(); const world = new World(table)
  t = performance.now()
  for (let rx = lo >> 5; rx <= hi >> 5; rx++) for (let rz = lo >> 5; rz <= hi >> 5; rz++) {
    const { chunks } = parseRegion(new Uint8Array(fs.readFileSync(path.join(regionDir, `r.${rx}.${rz}.mca`))), rx, rz, table, (cx, cz) => cx >= lo && cx <= hi && (name.startsWith('scene') ? cz >= -1 && cz <= 2 : cz >= lo && cz <= hi))
    chunks.forEach(c => world.add(c))
  }
  const tLoad = performance.now() - t
  const mesher = new Mesher(res, world)
  t = performance.now(); let quads = 0, meshes = 0, maxSec = 0
  for (const c of world.chunks.values()) for (const sy of c.sections.keys()) {
    const m = mesher.meshSection(c.cx, sy, c.cz)
    maxSec = Math.max(maxSec, m.ms)
    for (const l of Object.values(m.layers)) { quads += l.quads; meshes++ }
  }
  const tMesh = performance.now() - t
  console.log(ver, name, { loadParseMs: Math.round(tLoad), meshMs: Math.round(tMesh), maxSectionMs: +maxSec.toFixed(1), quads, meshes, vertexMB: +(quads * 4 * 15 * 4 / 1e6).toFixed(1), ...mesher.stats })
}
