import { Viewer, type GpuMesh } from './viewer.ts'
import type { SectionMesh } from './mesher.ts'
import { CAMS } from './cams.ts'

const q = new URLSearchParams(location.search)
const $ = <T extends HTMLElement>(id: string) => document.getElementById(id) as T

async function run() {
  const canvas = $<HTMLCanvasElement>('gl'), ov = $<HTMLCanvasElement>('ov')
  const W = Number(q.get('w') ?? 1280), H = Number(q.get('h') ?? 720)
  for (const c of [canvas, ov]) { c.width = W; c.height = H; c.style.width = W + 'px'; c.style.height = H + 'px' }
  const viewer = new Viewer(canvas)
  const octx = ov.getContext('2d')!
  const stats: Record<string, any> = { ver: q.get('ver') ?? '1.21.11' }
  ;(window as any).__stats = stats
  const ver = $<HTMLSelectElement>('ver'), size = $<HTMLSelectElement>('size'), view = $<HTMLSelectElement>('view')
  const only = $<HTMLInputElement>('only'), labels = $<HTMLInputElement>('labels')
  ver.value = q.get('ver') ?? '1.21.11'; size.value = q.get('size') ?? 'scene'; view.value = q.get('view') ?? 'B'
  only.checked = q.get('only') === '1'; labels.checked = q.get('labels') !== '0'
  const camsDiv = $('cams')
  for (const name of Object.keys(CAMS)) { const b = document.createElement('button'); b.textContent = name; b.onclick = () => setCam(name); camsDiv.appendChild(b) }

  let worker: Worker | null = null
  let entities: any[] = [], signs: any[] = []
  const sets: Record<string, Map<string, GpuMesh[]>> = { A: new Map(), B: new Map(), D: new Map() } // affected sets
  let baseKeys = new Set<string>()
  const setCam = (n: string) => { const c = CAMS[n]; viewer.lookAt([...c.pos] as any, c.at) }
  const affectedKeys = new Set<string>()

  const pick = (cx: number, sy: number, cz: number): GpuMesh[] | undefined => {
    const k = `${cx},${sy},${cz}`
    if (affectedKeys.has(k)) return viewer.meshes.get(`${view.value}:${k}`)
    return viewer.meshes.get(`base:${k}`)
  }
  function refreshKeys() {
    const ks = new Set<string>()
    for (const key of viewer.meshes.keys()) ks.add(key.slice(key.indexOf(':') + 1))
    viewer.sectionKeys = [...ks].map(k => k.split(',').map(Number) as [number, number, number])
  }

  const rpc = (msg: any) => new Promise<any>((resolve, reject) => {
    const id = Math.random()
    const h = (ev: MessageEvent) => {
      const d = ev.data
      if (d.id !== id) return
      worker!.removeEventListener('message', h)
      d.type === 'error' ? reject(new Error(d.error)) : resolve(d)
    }
    worker!.addEventListener('message', h)
    worker!.postMessage({ ...msg, id })
  })

  async function loadAll() {
    document.body.dataset.ready = '0'
    const t0 = performance.now()
    worker?.terminate(); viewer.clear(); affectedKeys.clear(); viewer.sectionKeys = []
    worker = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' })
    const v = ver.value
    const box = { scene: [-2, 4, -2, 3], '4x4': [-2, 1, -2, 1], '16x16': [-8, 7, -8, 7] }[size.value]!
    // 主執行緒同時載入圖集圖片
    const atlasP = (async () => { const t = performance.now(); const info = await (await fetch(`/work/assets/${v}/pack/atlas.json`)).json(); const img = await createImageBitmap(await (await fetch(`/work/assets/${v}/pack/atlas.png`)).blob()); viewer.setAtlas(img, info.size[0]); return performance.now() - t })()
    const init = await rpc({ type: 'init', ver: v })
    const atlasMs = await atlasP
    const ld = await rpc({ type: 'load', ver: v, x0: box[0], x1: box[1], z0: box[2], z1: box[3], forceBiome: q.get('biome') ?? undefined })
    entities = ld.entities; signs = ld.signs
    let firstMeshAt = 0, uploadMs = 0
    const onMsg = (ev: MessageEvent) => {
      const d = ev.data
      if (d.type !== 'meshes') return
      const t = performance.now()
      if (!firstMeshAt) firstMeshAt = t - t0
      for (const m of d.list as SectionMesh[]) { viewer.upload(d.set, m); if (d.set !== 'base') affectedKeys.add(`${m.cx},${m.sy},${m.cz}`) }
      uploadMs += performance.now() - t
      refreshKeys()
    }
    worker.addEventListener('message', onMsg)
    const ms = await rpc({ type: 'mesh' })
    worker.removeEventListener('message', onMsg)
    refreshKeys()
    const total = performance.now() - t0
    Object.assign(stats, { ver: v, size: size.value, box, init, atlasMs, load: { ...ld.timings, ...ld.stats }, mesh: ms, mainUploadMs: uploadMs, firstMeshVisibleMs: firstMeshAt, totalToInteractiveMs: total, entities: entities.length, signs: signs.length, gpuMeshes: [...viewer.meshes.values()].reduce((a, l) => a + l.length, 0) })
    document.body.dataset.ready = '1'
  }

  function frame() {
    const opts = { diff: view.value === 'D', only: only.checked && view.value === 'D', hideTrans: q.get('trans') === '0' }
    $('legend').style.display = opts.diff ? 'block' : 'none'
    viewer.draw(pick, opts)
    drawOverlay()
  }
  const BOX: Record<string, [number, number]> = { armor_stand: [0.5, 1.98], zombie: [0.6, 1.95], villager: [0.6, 1.95], cow: [0.9, 1.4], pig: [0.9, 0.9], item_frame: [0.75, 0.75], painting: [1, 1], block_display: [1, 1], item_display: [0.5, 0.5], text_display: [0.3, 0.3], minecart: [0.98, 0.7], chest_minecart: [0.98, 0.7], item: [0.25, 0.25], experience_orb: [0.5, 0.5], drowned: [0.6, 1.95] }
  function drawOverlay() {
    octx.clearRect(0, 0, ov.width, ov.height)
    if (!labels.checked) return
    octx.font = '13px system-ui'; octx.textBaseline = 'bottom'
    const txt = (s: string, x: number, y: number, col: string) => { octx.fillStyle = '#000a'; const w = octx.measureText(s).width; octx.fillRect(x - 2, y - 15, w + 4, 16); octx.fillStyle = col; octx.fillText(s, x, y) }
    for (const e of entities) {
      const [w, h] = BOX[e.id] ?? [0.6, 1]
      const [x, y, z] = e.pos
      const c: [number, number, number][] = []
      for (const dx of [-w / 2, w / 2]) for (const dy of [0, h]) for (const dz of [-w / 2, w / 2]) c.push([x + dx, y + dy, z + dz])
      const pr = c.map(p => viewer.project(...p))
      octx.strokeStyle = '#ff0'; octx.lineWidth = 1.5; octx.beginPath()
      for (const [a, b] of [[0, 1], [2, 3], [4, 5], [6, 7], [0, 2], [1, 3], [4, 6], [5, 7], [0, 4], [1, 5], [2, 6], [3, 7]]) { const pa = pr[a], pb = pr[b]; if (pa && pb) { octx.moveTo(pa[0], pa[1]); octx.lineTo(pb[0], pb[1]) } }
      octx.stroke()
      const top = viewer.project(x, y + h + 0.2, z)
      if (top && top[2] < 60) txt(e.id + (e.name ? ` "${e.name}"` : '') + (e.extra ? ` "${e.extra}"` : ''), top[0] - 20, top[1], '#ff0')
    }
    for (const s of signs) {
      const p = viewer.project(s.x + 0.5, s.y + 1.4, s.z + 0.5)
      if (p && p[2] < 40) txt('[sign] ' + s.lines.filter(Boolean).join(' | '), p[0] - 30, p[1], '#8ff')
    }
  }

  // 自由視角：拖曳轉向、WASD 移動
  let drag = false, lx = 0, ly = 0
  ov.addEventListener('mousedown', e => { drag = true; lx = e.clientX; ly = e.clientY })
  addEventListener('mouseup', () => drag = false)
  window.addEventListener('mousemove', (e: MouseEvent) => { if (!drag) return; viewer.cam.yaw += (e.clientX - lx) * 0.005; viewer.cam.pitch = Math.max(-1.55, Math.min(1.55, viewer.cam.pitch - (e.clientY - ly) * 0.005)); lx = e.clientX; ly = e.clientY })
  const keys = new Set<string>()
  window.addEventListener('keydown', (e: KeyboardEvent) => keys.add(e.key.toLowerCase())); window.addEventListener('keyup', (e: KeyboardEvent) => keys.delete(e.key.toLowerCase()))
  let last = performance.now(), frames = 0, fpsT = last
  function loop() {
    const now = performance.now(), dt = (now - last) / 1000; last = now
    const f = viewer.forward(), sp = (keys.has('shift') ? 40 : 12) * dt
    const p = viewer.cam.pos
    if (keys.has('w')) { p[0] += f[0] * sp; p[1] += f[1] * sp; p[2] += f[2] * sp }
    if (keys.has('s')) { p[0] -= f[0] * sp; p[1] -= f[1] * sp; p[2] -= f[2] * sp }
    if (keys.has('a')) { p[0] += f[2] * sp; p[2] -= f[0] * sp }
    if (keys.has('d')) { p[0] -= f[2] * sp; p[2] += f[0] * sp }
    frame(); frames++
    if (now - fpsT > 1000) { stats.fps = +(frames * 1000 / (now - fpsT)).toFixed(1); frames = 0; fpsT = now; renderStats() }
    requestAnimationFrame(loop)
  }
  function renderStats() {
    const m = stats.mesh, l = stats.load
    if (!m) { $('stats').textContent = 'loading...'; return }
    $('stats').textContent = [
      `fps ${stats.fps ?? '-'}  (drawn meshes ${viewer.drawn.meshes}, quads ${viewer.drawn.quads})`,
      `to interactive ${stats.totalToInteractiveMs?.toFixed(0)} ms  first mesh ${stats.firstMeshVisibleMs?.toFixed(0)} ms`,
      `region fetch ${l.fetchRegionsMs?.toFixed(0)} + NBT+parse ${l.parseRegionsMs?.toFixed(0)} ms, chunks ${l.chunks}`,
      `mesh(worker) ${m.baseWallMs?.toFixed(0)} ms, diff-affected sections ${m.affectedSections}`,
      `diff +${m.diffCounts.added} -${m.diffCounts.removed} ~${m.diffCounts.modified}`,
    ].join('\n')
  }
  for (const s of [ver, size]) s.onchange = () => loadAll().then(() => { setCam(q.get('cam') ?? 'overview') })
  ;(window as any).__api = {
    setCam, viewer,
    async bench(n = 30) {
      const opts = { diff: view.value === 'D', only: only.checked }
      const px = new Uint8Array(4)
      const t = performance.now()
      // readPixels 強制同步等待 GPU（SwiftShader 下 gl.finish 不會真的阻塞）
      for (let i = 0; i < n; i++) { viewer.draw(pick, opts); viewer.gl.readPixels(0, 0, 1, 1, viewer.gl.RGBA, viewer.gl.UNSIGNED_BYTE, px) }
      return (performance.now() - t) / n
    },
    async edit(pos: number[], state: string) {
      const t = performance.now()
      const r = await rpc({ type: 'edit', pos, state })
      const tw = performance.now() - t
      const t2 = performance.now()
      for (const m of r.list as SectionMesh[]) viewer.upload('base', m)
      refreshKeys()
      return { roundTripMs: tw, workerMs: r.ms, uploadMs: performance.now() - t2, rebuiltSections: r.rebuiltSections }
    },
    rpc,
  }
  await loadAll()
  const cam = q.get('cam') ?? 'overview'
  if (cam.includes(',')) { const n = cam.split(',').map(Number); viewer.cam.pos = [n[0], n[1], n[2]]; viewer.cam.yaw = n[3]; viewer.cam.pitch = n[4] } else setCam(cam)
  view.onchange = () => { /* 立即生效 */ }
  requestAnimationFrame(loop)
}

if (q.get('mode') === 'stock') { import('./stock.ts').then(m => m.run(q)) } else run()
