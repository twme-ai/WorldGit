// Node 端基準：deepslate NBT 解析 + 我們的 section 轉換，量測 4x4 與 16x16 chunk。
import fs from 'node:fs'
import path from 'node:path'
import { StateTable, World, parseRegion } from '../src/world.ts'

const ver = process.argv[2] ?? '1.21.11'
const base = path.resolve(import.meta.dirname, '../../../.work/worlds', ver, 'baseline/world')
const regionDir = ver === '26.2' ? path.join(base, 'dimensions/minecraft/overworld/region') : path.join(base, 'region')
for (const [name, lo, hi] of [['4x4', -2, 1], ['16x16', -8, 7]] as const) {
  const table = new StateTable(); const world = new World(table)
  const t0 = performance.now(); let read = 0; let agg = { chunks: 0, skippedNotFull: 0, inflateAndNbtMs: 0, convertMs: 0 }
  for (let rx = lo >> 5; rx <= hi >> 5; rx++) for (let rz = lo >> 5; rz <= hi >> 5; rz++) {
    const f = path.join(regionDir, `r.${rx}.${rz}.mca`)
    const b = new Uint8Array(fs.readFileSync(f)); read += b.length
    const { chunks, stats } = parseRegion(b, rx, rz, table, (cx, cz) => cx >= lo && cx <= hi && cz >= lo && cz <= hi)
    chunks.forEach(c => world.add(c))
    agg.chunks += stats.chunks; agg.skippedNotFull += stats.skippedNotFull; agg.inflateAndNbtMs += stats.inflateAndNbtMs; agg.convertMs += stats.convertMs
  }
  let sections = 0; for (const c of world.chunks.values()) sections += c.sections.size
  console.log(ver, name, { totalMs: Math.round(performance.now() - t0), ...Object.fromEntries(Object.entries(agg).map(([k, v]) => [k, Math.round(v as number)])), chunksLoaded: world.chunks.size, sections, states: table.states.length, regionBytes: read })
}
