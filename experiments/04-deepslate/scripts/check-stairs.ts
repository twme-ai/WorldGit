// 幾何驗證：樓梯 facing 的「高的那一半」是否落在對應方向（驗證 deepslate 的 blockstate y 旋轉方向）
import fs from 'node:fs'; import path from 'node:path'
import { BlockState, Cull } from 'deepslate'
import { PackResources, patchBlockColors, type Pack } from '../src/resources.ts'
patchBlockColors()
const ver = process.argv[2] ?? '1.21.11'
const dir = path.resolve(import.meta.dirname, '../../../.work/assets', ver, 'pack')
const rj = (f: string) => JSON.parse(fs.readFileSync(path.join(dir, f), 'utf8'))
const res = new PackResources({ blockstates: rj('blockstates.json'), models: rj('models.json'), flags: rj('flags.json'), atlas: rj('atlas.json'), biomes: rj('biomes.json') } as Pack)
const exp: Record<string, [number, number]> = { north: [0, -1], south: [0, 1], east: [1, 0], west: [-1, 0] } // 期望「高處」重心偏移方向 (x,z)
for (const half of ['bottom', 'top']) for (const facing of ['north', 'east', 'south', 'west']) {
  const st = new BlockState('minecraft:oak_stairs', { facing, half, shape: 'straight', waterlogged: 'false' })
  const def = res.getBlockDefinition(st.getName())!
  const mesh = def.getMesh(st.getName(), st.getProperties(), res, res, Cull.none())
  // 高處：half=bottom 時取 y=1 的頂面 quad；half=top 時取 y=0 的底面 quad（凹口在另一側）
  const yTarget = half === 'bottom' ? 1 : 0
  let sx = 0, sz = 0, n = 0
  for (const q of mesh.quads) { const vs = q.vertices(); if (vs.every(v => Math.abs(v.pos.y - yTarget) < 1e-6)) { for (const v of vs) { sx += v.pos.x - 0.5; sz += v.pos.z - 0.5; n++ } } }
  const [ex, ez] = exp[facing]; const gx = Math.sign(Math.round(sx / n * 100) / 100), gz = Math.sign(Math.round(sz / n * 100) / 100)
  console.log(ver, half.padEnd(6), facing.padEnd(5), 'tall-side centroid', (sx / n).toFixed(2), (sz / n).toFixed(2), gx === ex && gz === ez ? 'OK' : 'MISMATCH(exp ' + ex + ',' + ez + ')')
}
