// 模擬 Hub 後端的「資源管線」：從解出的 client jar assets 產生瀏覽器用的預處理產物。
// 用法：node scripts/prep-assets.mjs <version>   （讀 .work/assets/<version>/assets，輸出 .work/assets/<version>/pack/）
import fs from 'node:fs'
import path from 'node:path'
import { PNG } from 'pngjs'

const version = process.argv[2]
if (!version) { console.error('usage: prep-assets.mjs <version>'); process.exit(1) }
const root = path.resolve(import.meta.dirname, '../../../.work/assets', version)
const mc = path.join(root, 'assets/minecraft')
const out = path.resolve(import.meta.dirname, '../../../.work/viewer-scale/pack')
fs.mkdirSync(out, { recursive: true })
const t0 = performance.now()
const rj = (p) => JSON.parse(fs.readFileSync(p, 'utf8'))
const norm = (id) => id.includes(':') ? id : 'minecraft:' + id
const strip = (id) => id.replace(/^minecraft:/, '')

// ---- blockstates ----
const blockstates = {}
for (const f of fs.readdirSync(path.join(mc, 'blockstates'))) {
  if (f.endsWith('.json')) blockstates[f.slice(0, -5)] = rj(path.join(mc, 'blockstates', f))
}
const modelRefs = new Set()
const addRef = (m) => { if (m) modelRefs.add(norm(m.model ?? m)) }
for (const bs of Object.values(blockstates)) {
  for (const v of Object.values(bs.variants ?? {})) (Array.isArray(v) ? v : [v]).forEach(addRef)
  for (const p of bs.multipart ?? []) (Array.isArray(p.apply) ? p.apply : [p.apply]).forEach(addRef)
}
// ---- models（含 parent 閉包）----
const models = {}
const queue = [...modelRefs]
while (queue.length) {
  const id = queue.pop()
  if (models[id] || id.startsWith('minecraft:builtin/')) continue
  const file = path.join(mc, 'models', strip(id) + '.json')
  if (!fs.existsSync(file)) { console.warn('missing model', id); continue }
  const m = rj(file); models[id] = m
  if (m.parent) queue.push(norm(m.parent))
}
// ---- 貼圖 ----
const texIds = new Set()
const spriteOf = (t) => typeof t === 'string' ? t : t?.sprite
for (const m of Object.values(models)) for (const t of Object.values(m.textures ?? {})) {
  const s = spriteOf(t); if (s && !s.startsWith('#')) texIds.add(norm(s))
}
for (const t of ['water_still', 'water_flow', 'lava_still', 'lava_flow']) texIds.add('minecraft:block/' + t)
function walk(dir, acc = []) {
  if (!fs.existsSync(dir)) return acc
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    const p = path.join(dir, e.name)
    if (e.isDirectory()) walk(p, acc); else if (e.name.endsWith('.png')) acc.push(p)
  }
  return acc
}
// deepslate 的 SpecialRenderers（箱子/床/旗幟/告示牌/頭顱…）要的實體貼圖
const entityDirs = ['chest', 'bed', 'banner', 'signs', 'shulker', 'bell', 'conduit', 'decorated_pot', 'skeleton', 'zombie', 'creeper', 'player', 'piglin', 'enderdragon', 'copper_golem']
for (const d of entityDirs) for (const p of walk(path.join(mc, 'textures/entity', d))) {
  texIds.add('minecraft:entity/' + path.relative(path.join(mc, 'textures/entity'), p).replace(/\.png$/, '').split(path.sep).join('/'))
}
const loaded = []
const missing = []
for (const id of [...texIds].sort()) {
  const file = path.join(mc, 'textures', strip(id) + '.png')
  if (!fs.existsSync(file)) { missing.push(id); continue }
  let png = PNG.sync.read(fs.readFileSync(file))
  let h = png.height
  const metaFile = file + '.mcmeta'
  if (fs.existsSync(metaFile)) {
    const meta = rj(metaFile)
    if (meta.animation) h = meta.animation.height ?? (meta.animation.width ?? png.width) // 只取第一格
  }
  h = Math.min(h, png.height)
  // alpha 統計（給 flags 用）
  let translucent = 0, transparent = 0
  const n = png.width * h
  for (let i = 0; i < n; i++) { const a = png.data[i * 4 + 3]; if (a === 0) transparent++; else if (a < 255) translucent++ }
  loaded.push({ id, w: png.width, h, data: png.data, translucent: translucent / n, transparent: transparent / n })
}
// shelf packing
loaded.sort((a, b) => b.h - a.h || b.w - a.w)
function pack(W) {
  let x = 0, y = 0, rowH = 0; const pos = []
  for (const t of loaded) {
    if (x + t.w > W) { x = 0; y += rowH; rowH = 0 }
    pos.push([x, y]); x += t.w; rowH = Math.max(rowH, t.h)
  }
  return { pos, H: y + rowH }
}
let W = 1024, res
for (;; W *= 2) { res = pack(W); if (res.H <= W) break }
const H = W // deepslate 要求寬高皆為 2 的冪，且 getPixelSize 假設方形
const atlas = new PNG({ width: W, height: H })
// 索引 0 位置保留給 invalid（洋紅黑格）：放在最後一格外，這裡直接把 (W-16..W, H-16..H) 畫成 invalid，若被佔用再擴大
const idMap = {}
loaded.forEach((t, i) => {
  const [px, py] = res.pos[i]
  for (let y = 0; y < t.h; y++) for (let x = 0; x < t.w; x++) {
    const s = (y * t.w + x) * 4, d = ((py + y) * W + px + x) * 4
    atlas.data[d] = t.data[s]; atlas.data[d + 1] = t.data[s + 1]; atlas.data[d + 2] = t.data[s + 2]; atlas.data[d + 3] = t.data[s + 3]
  }
  idMap[t.id] = [px / W, py / H, (px + t.w) / W, (py + t.h) / H]
})
fs.writeFileSync(path.join(out, 'atlas.png'), PNG.sync.write(atlas))
fs.writeFileSync(path.join(out, 'atlas.json'), JSON.stringify({ size: [W, H], uv: idMap }))
const texInfo = Object.fromEntries(loaded.map(t => [t.id, t]))

// ---- flags（opaque / semi_transparent / self_culling）----
function resolveModel(id, depth = 0) {
  const m = models[id]; if (!m) return { elements: undefined, textures: {} }
  const parent = m.parent ? resolveModel(norm(m.parent), depth + 1) : { elements: undefined, textures: {} }
  return { elements: m.elements ?? parent.elements, textures: { ...parent.textures, ...(m.textures ?? {}) } }
}
const texOf = (m, ref) => { let r = ref, g = 0; while (r?.startsWith('#') && g++ < 10) r = spriteOf(m.textures[r.slice(1)]); return r ? norm(r) : undefined }
const isFullCube = (e) => e.from.every(v => v === 0) && e.to.every(v => v === 16) && !e.rotation
const flags = {}
for (const [name, bs] of Object.entries(blockstates)) {
  const refs = []
  for (const v of Object.values(bs.variants ?? {})) (Array.isArray(v) ? v : [v]).forEach(x => refs.push(norm(x.model)))
  for (const p of bs.multipart ?? []) (Array.isArray(p.apply) ? p.apply : [p.apply]).forEach(x => refs.push(norm(x.model)))
  let opaque = refs.length > 0, semi = false
  for (const ref of new Set(refs)) {
    const m = resolveModel(ref)
    const els = m.elements
    if (!els?.length) { opaque = false; continue }
    const first = els[0]
    const faces = first.faces ?? {}
    const allFaces = ['up', 'down', 'north', 'south', 'east', 'west'].every(f => faces[f]?.cullface)
    if (!isFullCube(first) || !allFaces || !els.every(e => e.from.every(v => v >= 0) && e.to.every(v => v <= 16))) opaque = false
    for (const f of Object.values(faces)) {
      const info = texInfo[texOf(m, f.texture)]
      if (!info) continue
      if (info.transparent > 0 || info.translucent > 0) opaque = false
      if (info.translucent > 0.3) semi = true
    }
  }
  const self = /glass$|_glass$|^ice$|slime_block|honey_block|^frosted_ice$/.test(name)
  if (opaque || semi || self) flags[name] = { ...(opaque && { opaque: true }), ...(semi && { semi_transparent: true }), ...(self && { self_culling: true }) }
}
flags.water = { semi_transparent: true, self_culling: true }
flags.lava = { self_culling: true }
fs.writeFileSync(path.join(out, 'flags.json'), JSON.stringify(flags))

// ---- blockstates / models 輸出（同一檔，瀏覽器一次抓）----
fs.writeFileSync(path.join(out, 'blockstates.json'), JSON.stringify(blockstates))
fs.writeFileSync(path.join(out, 'models.json'), JSON.stringify(models))

// ---- 生物群系顏色表 ----
const readPng = (p) => PNG.sync.read(fs.readFileSync(p))
const grassMap = readPng(path.join(mc, 'textures/colormap/grass.png'))
const foliageMap = readPng(path.join(mc, 'textures/colormap/foliage.png'))
const dryMap = fs.existsSync(path.join(mc, 'textures/colormap/dry_foliage.png')) ? readPng(path.join(mc, 'textures/colormap/dry_foliage.png')) : null
const sample = (png, temp, down) => {
  temp = Math.min(1, Math.max(0, temp)); down = Math.min(1, Math.max(0, down)) * temp
  const x = Math.floor((1 - temp) * 255), y = Math.floor((1 - down) * 255)
  const i = (y * png.width + x) * 4
  return (png.data[i] << 16) | (png.data[i + 1] << 8) | png.data[i + 2]
}
const hex = (s) => typeof s === 'string' ? parseInt(s.replace('#', ''), 16) : s
const biomes = {}
const bdir = path.join(root, 'data/minecraft/worldgen/biome')
for (const f of fs.readdirSync(bdir)) {
  if (!f.endsWith('.json')) continue
  const b = rj(path.join(bdir, f)); const e = b.effects ?? {}
  let grass = e.grass_color != null ? hex(e.grass_color) : sample(grassMap, b.temperature, b.downfall)
  const mod = e.grass_color_modifier
  if (mod === 'dark_forest') grass = ((grass & 0xFEFEFE) + 0x28340A) >> 1
  if (mod === 'swamp') grass = 0x6A7039 // 原版依噪音在 0x4C763C / 0x6A7039 之間，這裡取固定值
  const foliage = e.foliage_color != null ? hex(e.foliage_color) : sample(foliageMap, b.temperature, b.downfall)
  const dry = e.dry_foliage_color != null ? hex(e.dry_foliage_color) : (dryMap ? sample(dryMap, b.temperature, b.downfall) : foliage)
  biomes['minecraft:' + f.slice(0, -5)] = { grass, foliage, dry, water: hex(e.water_color ?? '#3f76e4') }
}
fs.writeFileSync(path.join(out, 'biomes.json'), JSON.stringify(biomes))
const kb = (f) => (fs.statSync(path.join(out, f)).size / 1024).toFixed(0) + ' KB'
console.log(JSON.stringify({
  version, ms: Math.round(performance.now() - t0), textures: loaded.length, missingTextures: missing, atlas: [W, H],
  blockstates: Object.keys(blockstates).length, models: Object.keys(models).length, biomes: Object.keys(biomes).length,
  sizes: Object.fromEntries(['atlas.png', 'atlas.json', 'blockstates.json', 'models.json', 'flags.json', 'biomes.json'].map(f => [f, kb(f)])),
}, null, 1))
