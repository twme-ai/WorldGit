// 3D 檢視器的協調者：串流載入 chunk 視窗、worker 網格生成、LOD、diff 上色、選取。
// 近景用 deepslate 模型層 + 自寫網格（mesher.ts），遠景用伺服器預先計算的 tile 高度圖（lod.ts）。
import { api, getBlob, getBuffer, getJson, type ChangedChunk, type CommitDetail, type Palette, type TileRef } from '../api.ts'
import { Camera, type CameraMode } from './camera.ts'
import { CELLS, buildLod, cellColors, parseHeights } from './lod.ts'
import type { MeshRequest, MeshResult } from './mesher.ts'
import { Renderer, type RenderMode } from './renderer.ts'
import { decodeChunks, decodeDiff } from './wire.ts'
import { StateTable, World } from './world.ts'
import type { WorkerIn, WorkerOut } from './worker.ts'

export interface ViewerConfig {
  canvas: HTMLCanvasElement
  owner: string
  world: string
  dimRepo: string
  detail: CommitDetail
  palette: Palette
  /** 近景完整細節半徑（chunk）。預設 6 → 13×13。 */
  radius?: number
  workers?: number
  /** 沒有 diff 時（initial commit 或要看純世界）不載入 diff。 */
  showDiff?: boolean
  /** diff 的比較基準（commit id）；省略＝commit 的第一個 parent。比較檢視用它顯示任意兩個 commit 的差異。 */
  base?: string
  /** 合併候選的唯讀資料端點，query 已帶來源／指紋／選擇。 */
  preview?: { base: string; query: string }
}

export interface PickInfo {
  x: number; y: number; z: number
  state: string
  kind: 'same' | 'added' | 'removed' | 'modified' | 'conflict'
  before?: string
  biome?: string
  blockEntity?: string
}

export interface ViewerStats {
  chunks: number; sectionsMeshed: number; pendingMeshes: number; gpuMB: number
  drawnSections: number; drawnQuads: number; windowsLoading: number; lodRegions: number
  meshMsAvg: number; fps: number; renderer: string; firstMeshMs: number | null; ready: boolean
}

export interface ViewState { x: number; y: number; z: number; yaw: number; pitch: number; dist: number; mode: CameraMode }

const WINDOW = 8
const KIND_NAMES = ['same', 'added', 'removed', 'modified', 'conflict'] as const
const ENTITY_SIZE: Record<string, [number, number]> = {
  'minecraft:item': [0.25, 0.25], 'minecraft:armor_stand': [0.5, 1.98], 'minecraft:villager': [0.6, 1.95], 'minecraft:cow': [0.9, 1.4],
  'minecraft:pig': [0.9, 0.9], 'minecraft:sheep': [0.9, 1.3], 'minecraft:chicken': [0.4, 0.7], 'minecraft:item_frame': [0.75, 0.75],
  'minecraft:glow_item_frame': [0.75, 0.75], 'minecraft:painting': [0.5, 0.5], 'minecraft:experience_orb': [0.5, 0.5],
}

export class Viewer {
  readonly camera = new Camera()
  readonly table = new StateTable()
  readonly world = new World(this.table)
  readonly renderer: Renderer
  stats: ViewerStats = { chunks: 0, sectionsMeshed: 0, pendingMeshes: 0, gpuMB: 0, drawnSections: 0, drawnQuads: 0, windowsLoading: 0, lodRegions: 0, meshMsAvg: 0, fps: 0, renderer: '', firstMeshMs: null, ready: false }
  onStats: (s: ViewerStats) => void = () => {}
  onPick: (p: PickInfo | null) => void = () => {}
  onViewChange: (v: ViewState) => void = () => {}
  onError: (m: string) => void = (m) => console.error(m)

  private workers: Worker[] = []
  private ready = 0
  private sentStates: number[] = []
  private sentBiomes: number[] = []
  private nextJob = 1
  private inflight = new Map<number, { key: string; version: number; worker: number }>()
  private busy: number[] = []
  private dirty = new Set<string>()
  private versions = new Map<string, number>()
  private meshed = new Set<string>()
  private windows = new Map<string, 'loading' | 'loaded'>()
  private windowEntities = new Map<string, EntityRec[]>()
  private tiles = new Map<string, TileRef>()
  private lodData = new Map<string, { heights: Int16Array; colors: Uint8Array }>()
  private lodLoading = new Set<string>()
  private lodDirty = new Set<string>()
  private selected: PickInfo | null = null
  private needsRender = true
  private disposed = false
  private raf = 0
  private t0 = performance.now()
  private lastFrame = performance.now()
  private fpsAcc: number[] = []
  private meshMs: number[] = []
  private lodTimer = 0
  private streamTimer = 0
  private lastStreamKey = ''
  private detach: () => void = () => {}
  private ro: ResizeObserver
  private mcBase: string
  private diffEnabled: boolean
  private radius: number
  private focusDone = false
  private viewTimer = 0
  private commentPins: { id: string; bounds: [number, number, number, number, number, number] }[] = []
  onPin: (id: string) => void = () => {}
  setCommentPins(pins: { id: string; bounds: [number, number, number, number, number, number] }[]) { this.commentPins = pins; this.rebuildLines() }
  private conflictBoxes: { bounds: [number, number, number, number, number, number]; selected: boolean }[] = []

  constructor(readonly cfg: ViewerConfig) {
    this.renderer = new Renderer(cfg.canvas)
    this.stats.renderer = this.renderer.rendererName
    this.renderer.setPalette(cfg.palette)
    this.mcBase = `/api/v1/assets/${cfg.detail.mcVersion}`
    this.diffEnabled = cfg.showDiff !== false && !cfg.detail.initial
    this.radius = cfg.radius ?? 6
    this.camera.onChange = () => { this.needsRender = true; this.scheduleStream(); this.scheduleViewEvent() }
    this.detach = this.camera.attach(cfg.canvas)
    let downAt = [0, 0]
    cfg.canvas.addEventListener('pointerdown', (e) => { downAt = [e.clientX, e.clientY] })
    cfg.canvas.addEventListener('pointerup', (e) => { if (Math.hypot(e.clientX - downAt[0], e.clientY - downAt[1]) < 4 && e.button === 0) this.pickAt(e) })
    this.ro = new ResizeObserver(() => { this.resize(); this.needsRender = true })
    this.ro.observe(cfg.canvas)
    this.resize()
  }

  private resize() {
    const c = this.cfg.canvas
    const dpr = Math.min(window.devicePixelRatio || 1, 1.5)
    this.renderer.resize(Math.max(2, Math.round(c.clientWidth * dpr)), Math.max(2, Math.round(c.clientHeight * dpr)))
  }

  setPalette(p: Palette) { this.cfg.palette = p; this.renderer.setPalette(p); this.rebuildLines(); this.needsRender = true }
  setMode(m: RenderMode) { this.renderer.mode = m; this.rebuildLines(); this.needsRender = true }
  setCameraMode(m: CameraMode) { this.camera.setMode(m) }
  setRadius(r: number) { this.radius = r; this.scheduleStream(true) }
  getViewState(): ViewState {
    const c = this.camera
    const p = c.mode === 'fly' ? c.pos : c.target
    return { x: p[0], y: p[1], z: p[2], yaw: c.yaw, pitch: c.pitch, dist: c.dist, mode: c.mode }
  }
  applyViewState(v: Partial<ViewState>) {
    const c = this.camera
    if (v.yaw !== undefined) c.yaw = v.yaw
    if (v.pitch !== undefined) c.pitch = v.pitch
    if (v.dist !== undefined) c.dist = v.dist
    if (v.x !== undefined && v.y !== undefined && v.z !== undefined) {
      if (v.mode === 'fly') { c.mode = 'fly'; c.pos = [v.x, v.y, v.z] } else { c.mode = 'orbit'; c.target = [v.x, v.y, v.z] }
    }
    this.focusDone = true
    this.needsRender = true
    this.scheduleStream(true)
  }

  setConflictBoxes(boxes: { bounds: [number, number, number, number, number, number]; selected: boolean }[]) { this.conflictBoxes = boxes; this.rebuildLines() }
  focusBox(b: [number, number, number, number, number, number]) {
    this.focusDone = true
    this.camera.setMode('orbit')
    this.camera.lookAt([(b[0] + b[3] + 1) / 2, (b[1] + b[4] + 1) / 2, (b[2] + b[5] + 1) / 2], Math.max(14, Math.max(b[3]-b[0], b[4]-b[1], b[5]-b[2]) * 2.5))
    this.scheduleStream(true)
  }

  // ---- 啟動 ----

  async start() {
    const n = this.cfg.workers ?? 2
    const ready = new Promise<Float32Array>((resolve, reject) => {
      for (let i = 0; i < n; i++) {
        const w = new Worker(new URL('./worker.ts', import.meta.url), { type: 'module' })
        this.workers.push(w); this.busy.push(0); this.sentStates.push(0); this.sentBiomes.push(0)
        w.onmessage = (e: MessageEvent<WorkerOut>) => {
          const m = e.data
          if (m.type === 'ready') { this.ready++; if (i === 0) resolve(m.rects); this.pump() }
          else if (m.type === 'mesh') this.onMesh(m.result, i)
          else { this.onError(m.message); if (m.id !== undefined) this.failJob(m.id, i); if (i === 0 && this.ready === 0) reject(new Error(m.message)) }
        }
        w.postMessage({ type: 'init', base: this.mcBase } satisfies WorkerIn)
      }
    })
    const [rects, atlas] = await Promise.all([ready, getBlob(`${this.mcBase}/atlas.png`).then((b) => createImageBitmap(b, { premultiplyAlpha: 'none', colorSpaceConversion: 'none' }))])
    if (this.disposed) { atlas.close(); return }
    this.renderer.setAtlas(atlas, rects)
    atlas.close()
    if (!this.cfg.preview && this.cfg.detail.chunkCount > 0) {
      for (const t of await api.tiles(this.cfg.owner, this.cfg.world, this.cfg.dimRepo, this.cfg.detail.commit.id).catch(() => [] as TileRef[])) this.tiles.set(`${t.rx},${t.rz}`, t)
    }
    if (this.disposed) return
    if (!this.focusDone) this.initialFocus()
    this.loop()
    this.scheduleStream(true)
  }

  private initialFocus() {
    const chunks = this.cfg.detail.changedChunks
    let cx = 0, cz = 0
    if (chunks.length && this.cfg.detail.initial) {
      // 初始快照：從最接近世界原點的 chunk 開始看
      let best: ChangedChunk = chunks[0]
      for (const c of chunks) if (c[0] ** 2 + c[1] ** 2 < best[0] ** 2 + best[1] ** 2) best = c
      cx = best[0]; cz = best[1]
    } else if (chunks.length) {
      let best: ChangedChunk = chunks[0]
      for (const c of chunks) if (c[2] + c[3] + c[4] > best[2] + best[3] + best[4]) best = c
      // 變動集中時用變動的中心，分散時用變動最多的 chunk
      const [bx0, bz0, bx1, bz1] = this.cfg.detail.bounds
      if (bx1 - bx0 < 12 && bz1 - bz0 < 12) { cx = (bx0 + bx1) / 2; cz = (bz0 + bz1) / 2 } else { cx = best[0]; cz = best[1] }
    }
    this.camera.target = [cx * 16 + 8, 64, cz * 16 + 8]
    this.camera.dist = chunks.length && !this.cfg.detail.initial ? 40 : 90
    this.camera.onChange()
  }

  /** 第一批資料載入後，把鏡頭目標的高度對到地面或變動方塊。 */
  private settleHeight() {
    if (this.focusDone) return
    const [tx, , tz] = this.camera.target
    let ys = 0, n = 0
    for (const [k, d] of this.world.diffs) {
      const sy = Number(k.split(',')[1])
      void d
      ys += sy * 16 + 8; n++
    }
    let y = n ? ys / n : -1
    if (y < 0) {
      for (let yy = 320; yy > -64; yy--) { const s = this.world.getState(Math.floor(tx), yy, Math.floor(tz)); if (s > 0) { y = yy; break } }
    }
    if (y >= 0) { this.camera.target[1] = y; this.focusDone = true; this.camera.onChange() }
    else if (this.world.chunks.size) this.focusDone = true
  }

  // ---- 串流 ----

  private focusChunk(): [number, number] {
    const p = this.camera.mode === 'fly' ? this.camera.pos : this.camera.target
    return [Math.floor(p[0] / 16), Math.floor(p[2] / 16)]
  }

  private scheduleStream(force = false) {
    if (force) { this.lastStreamKey = ''; void this.stream(); return }
    if (this.streamTimer) return
    this.streamTimer = window.setTimeout(() => { this.streamTimer = 0; void this.stream() }, 200)
  }

  private async stream() {
    if (this.disposed || !this.renderer.gl) return
    const [fx, fz] = this.focusChunk()
    const key = `${fx},${fz},${this.radius}`
    if (key === this.lastStreamKey) return
    this.lastStreamKey = key
    const R = this.radius
    const want: { wx: number; wz: number; d: number }[] = []
    for (let wx = Math.floor((fx - R) / WINDOW); wx <= Math.floor((fx + R) / WINDOW); wx++) {
      for (let wz = Math.floor((fz - R) / WINDOW); wz <= Math.floor((fz + R) / WINDOW); wz++) {
        const dx = Math.max(wx * WINDOW - fx, 0, fx - (wx * WINDOW + WINDOW - 1)), dz = Math.max(wz * WINDOW - fz, 0, fz - (wz * WINDOW + WINDOW - 1))
        want.push({ wx, wz, d: Math.max(dx, dz) })
      }
    }
    want.sort((a, b) => a.d - b.d)
    // 卸載太遠的視窗
    for (const k of [...this.windows.keys()]) {
      const [wx, wz] = k.split(',').map(Number)
      const dx = Math.max(wx * WINDOW - fx, 0, fx - (wx * WINDOW + WINDOW - 1)), dz = Math.max(wz * WINDOW - fz, 0, fz - (wz * WINDOW + WINDOW - 1))
      if (Math.max(dx, dz) > R + 6 && this.windows.get(k) === 'loaded') this.unloadWindow(wx, wz)
    }
    this.scheduleLod()
    let loading = [...this.windows.values()].filter((s) => s === 'loading').length
    for (const w of want) {
      if (loading >= 3) break
      const k = `${w.wx},${w.wz}`
      if (this.windows.has(k)) continue
      this.windows.set(k, 'loading'); loading++
      void this.loadWindow(w.wx, w.wz).catch((e) => { this.windows.delete(k); this.onError(`載入視窗失敗：${e}`) }).finally(() => { this.lastStreamKey = ''; this.scheduleStream() })
    }
    this.publishStats()
  }

  private async loadWindow(wx: number, wz: number) {
    const { owner, world, dimRepo, detail } = this.cfg
    const q = `x0=${wx * WINDOW}&z0=${wz * WINDOW}&x1=${wx * WINDOW + WINDOW - 1}&z1=${wz * WINDOW + WINDOW - 1}`
    const base = `/api/v1/worlds/${owner}/${world}/dims/${dimRepo}/commits/${detail.commit.id}`
    const against = this.cfg.base ? `&base=${encodeURIComponent(this.cfg.base)}` : ''
    const url = (kind: string) => this.cfg.preview ? `${this.cfg.preview.base}/${kind}?${this.cfg.preview.query}&${q}` : `${base}/${kind}?${q}${against}${kind === 'entities' && !this.diffEnabled ? '&plain=true' : ''}`
    const [chunkBuf, diffBuf, ents] = await Promise.all([
      getBuffer(url('chunks')),
      this.diffEnabled ? getBuffer(url('diff')) : Promise.resolve(null),
      getJson<EntityRaw[]>(url('entities')),
    ])
    if (this.disposed) return
    const k = `${wx},${wz}`
    if (!this.windows.has(k)) return
    const { chunks } = decodeChunks(chunkBuf, this.world)
    const diffs = diffBuf ? decodeDiff(diffBuf, this.table) : new Map()
    for (const c of chunks) this.world.add(c)
    for (const [dk, d] of diffs) this.world.diffs.set(dk, d)
    // 只有 diff 沒有 after 內容的 section（方塊全被移除）也要有鬼影
    const touched = new Set<string>()
    for (const c of chunks) {
      for (const sy of c.sections.keys()) touched.add(World.sectionKey(c.cx, sy, c.cz))
      this.markNeighbors(c.cx, c.cz)
    }
    for (const dk of diffs.keys()) {
      touched.add(dk)
      const [cx, sy, cz] = dk.split(',').map(Number)
      for (let dx = -1; dx <= 1; dx++) for (let dy = -1; dy <= 1; dy++) for (let dz = -1; dz <= 1; dz++) {
        const key = World.sectionKey(cx + dx, sy + dy, cz + dz)
        if (this.world.section(cx + dx, sy + dy, cz + dz) || this.world.diffs.has(key)) touched.add(key)
      }
    }
    for (const t of touched) this.markDirty(t)
    this.windows.set(k, 'loaded')
    this.windowEntities.set(k, ents.map((e) => ({ ...e })))
    this.rebuildLines()
    this.settleHeight()
    this.lodDirtyAll()
    this.publishStats()
  }

  private unloadWindow(wx: number, wz: number) {
    const k = `${wx},${wz}`
    this.windows.delete(k)
    this.windowEntities.delete(k)
    for (let cx = wx * WINDOW; cx < wx * WINDOW + WINDOW; cx++) {
      for (let cz = wz * WINDOW; cz < wz * WINDOW + WINDOW; cz++) {
        const c = this.world.chunk(cx, cz)
        for (const sy of c?.sections.keys() ?? []) this.dropSection(World.sectionKey(cx, sy, cz))
        for (const dk of [...this.world.diffs.keys()]) { const [x, , z] = dk.split(',').map(Number); if (x === cx && z === cz) { this.dropSection(dk); this.world.diffs.delete(dk) } }
        this.world.remove(cx, cz)
      }
    }
    this.lodDirtyAll()
    this.rebuildLines()
  }

  private dropSection(key: string) {
    this.renderer.removeSection(key)
    this.meshed.delete(key); this.dirty.delete(key)
    this.versions.set(key, (this.versions.get(key) ?? 0) + 1)
  }

  /** 新 chunk 載入後，鄰近已網格化的 section 的邊界面需要重算。 */
  private markNeighbors(cx: number, cz: number) {
    for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) {
      if (!dx && !dz) continue
      const c = this.world.chunk(cx + dx, cz + dz)
      if (!c) continue
      for (const sy of c.sections.keys()) { const k = World.sectionKey(cx + dx, sy, cz + dz); if (this.meshed.has(k)) this.markDirty(k) }
    }
  }

  // ---- 網格工作 ----

  private markDirty(key: string) {
    this.dirty.add(key)
    this.versions.set(key, (this.versions.get(key) ?? 0) + 1)
    this.pump()
  }

  private pump() {
    if (this.ready === 0 || this.disposed) return
    const [fx, fz] = this.focusChunk()
    while (this.dirty.size) {
      const w = this.busy.findIndex((b) => b < 2)
      if (w < 0) break
      let best = '', bd = Infinity
      for (const k of this.dirty) {
        const [cx, , cz] = k.split(',').map(Number)
        const d = (cx - fx) ** 2 + (cz - fz) ** 2
        if (d < bd) { bd = d; best = k }
      }
      this.dirty.delete(best)
      this.dispatch(best, w)
    }
    this.publishStats()
  }

  private dispatch(key: string, w: number) {
    const [cx, sy, cz] = key.split(',').map(Number)
    const hasDiff = this.world.diffs.has(key)
    const after = new Int16Array(18 * 18 * 18), before = hasDiff ? new Int16Array(18 * 18 * 18) : null
    const any = this.world.fillPadded(cx, sy, cz, after, before)
    if (!any && !hasDiff) { this.renderer.removeSection(key); this.meshed.delete(key); this.needsRender = true; return }
    const chunk = this.world.chunk(cx, cz)
    const bes: [number, string][] = []
    if (chunk) for (const [k, v] of chunk.blockEntities) if (Math.floor(k / 4096) === sy) bes.push([k - sy * 4096, v])
    const id = this.nextJob++
    const req: MeshRequest = { id, cx, sy, cz, after, before, kinds: this.world.diffs.get(key)?.kind.slice() ?? null, context: this.world.changedContext(cx, sy, cz), biomes: chunk?.biomes.get(sy) ?? null, blockEntities: bes }
    this.syncTables(w)
    this.inflight.set(id, { key, version: this.versions.get(key) ?? 0, worker: w })
    this.busy[w]++
    const transfer: ArrayBuffer[] = [after.buffer as ArrayBuffer]
    if (before) transfer.push(before.buffer as ArrayBuffer)
    this.workers[w].postMessage({ type: 'mesh', req } satisfies WorkerIn, transfer)
  }

  private syncTables(w: number) {
    if (this.sentStates[w] < this.table.states.length) {
      this.workers[w].postMessage({ type: 'states', from: this.sentStates[w], list: this.table.states.slice(this.sentStates[w]) } satisfies WorkerIn)
      this.sentStates[w] = this.table.states.length
    }
    if (this.sentBiomes[w] < this.table.biomes.length) {
      this.workers[w].postMessage({ type: 'biomes', from: this.sentBiomes[w], list: this.table.biomes.slice(this.sentBiomes[w]) } satisfies WorkerIn)
      this.sentBiomes[w] = this.table.biomes.length
    }
  }

  private failJob(id: number, w: number) {
    if (this.inflight.delete(id)) this.busy[w]--
    this.pump()
  }

  private onMesh(r: MeshResult, w: number) {
    const job = this.inflight.get(r.id)
    this.inflight.delete(r.id)
    this.busy[w]--
    this.meshMs.push(r.ms)
    if (this.meshMs.length > 200) this.meshMs.shift()
    if (job && job.version === (this.versions.get(job.key) ?? 0) && (this.world.chunk(r.cx, r.cz) || this.world.diffs.has(job.key))) {
      this.renderer.setSection(job.key, [r.cx * 16, r.sy * 16, r.cz * 16], r.layers)
      this.meshed.add(job.key)
      if (this.stats.firstMeshMs === null) this.stats.firstMeshMs = performance.now() - this.t0
      this.needsRender = true
    }
    this.pump()
  }

  // ---- LOD ----

  private lodDirtyAll() {
    for (const k of this.lodData.keys()) this.lodDirty.add(k)
    this.scheduleLod()
  }

  private scheduleLod() {
    // 載入鏡頭附近 3×3 region 的 tile
    const [fx, fz] = this.focusChunk()
    const rx = Math.floor(fx / 32), rz = Math.floor(fz / 32)
    for (let dx = -1; dx <= 1; dx++) for (let dz = -1; dz <= 1; dz++) {
      const k = `${rx + dx},${rz + dz}`
      if (this.tiles.has(k) && !this.lodData.has(k) && !this.lodLoading.has(k)) void this.loadLod(rx + dx, rz + dz)
    }
    if (this.lodTimer) return
    this.lodTimer = window.setTimeout(() => { this.lodTimer = 0; this.flushLod() }, 250)
  }

  private async loadLod(rx: number, rz: number) {
    const k = `${rx},${rz}`
    this.lodLoading.add(k)
    try {
      const { owner, world, dimRepo, detail } = this.cfg
      const base = `/api/v1/worlds/${owner}/${world}/dims/${dimRepo}/commits/${detail.commit.id}/tiles/${rx}/${rz}`
      const [png, hb] = await Promise.all([getBlob(`${base}.png`), getBuffer(`${base}.height`)])
      const bmp = await createImageBitmap(png)
      const cv = new OffscreenCanvas(512, 512)
      const ctx = cv.getContext('2d', { willReadFrequently: true })!
      ctx.drawImage(bmp, 0, 0)
      bmp.close()
      this.lodData.set(k, { heights: parseHeights(hb), colors: cellColors(ctx.getImageData(0, 0, 512, 512).data) })
      this.lodDirty.add(k)
      this.rebuildLines()
      this.scheduleLod()
    } catch (e) { this.onError(`LOD tile ${k} 失敗：${e}`) } finally { this.lodLoading.delete(k) }
  }

  private flushLod() {
    for (const k of this.lodDirty) {
      const d = this.lodData.get(k)
      if (!d) continue
      const [rx, rz] = k.split(',').map(Number)
      const buf = buildLod(rx, rz, d.heights, d.colors, (cx, cz) => !!this.world.chunk(cx, cz))
      this.renderer.setLod(k, [rx * 512, 0, rz * 512], buf, 400)
    }
    this.lodDirty.clear()
    this.needsRender = true
    this.publishStats()
  }

  /** 某個 chunk 的 LOD 地表高度（尚未載入回傳 null）。 */
  private lodHeight(cx: number, cz: number): number | null {
    const rx = Math.floor(cx / 32), rz = Math.floor(cz / 32)
    const d = this.lodData.get(`${rx},${rz}`)
    if (!d) return null
    const i = ((cx - rx * 32) * 4 + 2), j = ((cz - rz * 32) * 4 + 2)
    const h = d.heights[j * CELLS + i]
    return h === -32768 ? null : h + 1
  }

  // ---- 線框（實體、變動 chunk 標記、選取）----

  private rebuildLines() {
    const lines: number[] = []
    const col = (k?: string): [number, number, number, number] => {
      const p = this.cfg.palette
      const hex = k === 'added' ? p.added : k === 'removed' ? p.removed : k === 'modified' ? p.modified : k === 'conflict' ? p.conflict : '#8fb0d8'
      return [parseInt(hex.slice(1, 3), 16) / 255, parseInt(hex.slice(3, 5), 16) / 255, parseInt(hex.slice(5, 7), 16) / 255, 0.95]
    }
    for (const list of this.windowEntities.values()) {
      for (const e of list) {
        if (this.renderer.mode === 'changed' && !e.kind) continue
        const [w, h] = ENTITY_SIZE[e.id] ?? [0.6, 1.8]
        addBox(lines, e.x - w / 2, e.y, e.z - w / 2, e.x + w / 2, e.y + h, e.z + w / 2, col(e.kind))
      }
    }
    // 遠景的變動 chunk：在 LOD 地表上畫外框與短柱
    const palette = this.cfg.palette
    for (const c of this.cfg.detail.initial ? [] : this.cfg.detail.changedChunks) {
      if (this.world.chunk(c[0], c[1])) continue
      const y = this.lodHeight(c[0], c[1])
      if (y === null) continue
      const kind = c[4] > 0 ? 'modified' : c[3] > 0 ? 'removed' : c[2] > 0 ? 'added' : 'modified'
      void palette
      addBox(lines, c[0] * 16, y, c[1] * 16, c[0] * 16 + 16, y + 20, c[1] * 16 + 16, col(kind))
    }
    for (const pin of this.commentPins) { const b = pin.bounds; addBox(lines, b[0], b[1], b[2], b[3]+1, b[4]+1, b[5]+1, [1, 0.8, 0.2, 1]); addBox(lines, b[0]+0.25, b[1]+1, b[2]+0.25, b[0]+0.75, b[1]+2, b[2]+0.75, [1, 0.8, 0.2, 1]) }
    for (const box of this.conflictBoxes) {
      const b = box.bounds
      addBox(lines, b[0] - 0.03, b[1] - 0.03, b[2] - 0.03, b[3] + 1.03, b[4] + 1.03, b[5] + 1.03, col('conflict'))
      if (box.selected) addBox(lines, b[0] - 0.08, b[1] - 0.08, b[2] - 0.08, b[3] + 1.08, b[4] + 1.08, b[5] + 1.08, col('conflict'))
    }
    if (this.selected) {
      const s = this.selected
      addBox(lines, s.x - 0.01, s.y - 0.01, s.z - 0.01, s.x + 1.01, s.y + 1.01, s.z + 1.01, [1, 1, 1, 1])
    }
    this.renderer.setLines(new Float32Array(lines))
    this.needsRender = true
  }

  // ---- 選取 ----

  private pickAt(e: PointerEvent) {
    const r = this.cfg.canvas.getBoundingClientRect()
    const nx = (e.clientX - r.left) / r.width, ny = (e.clientY - r.top) / r.height
    const { o, d } = this.camera.ray(nx, ny, r.width / r.height)
    let nearest = 400, pinned: string | null = null
    for (const pin of this.commentPins) {
      const b = pin.bounds, lo = [b[0]+0.25, b[1]+1, b[2]+0.25], hi = [b[0]+0.75, b[1]+2, b[2]+0.75]
      let near = 0, far = 400
      for (let i = 0; i < 3; i++) {
        if (Math.abs(d[i]) < 1e-10) { if (o[i] < lo[i] || o[i] > hi[i]) far = -1 }
        else { const a = (lo[i]-o[i])/d[i], z = (hi[i]-o[i])/d[i]; near = Math.max(near, Math.min(a,z)); far = Math.min(far, Math.max(a,z)) }
      }
      if (near <= far && near < nearest) { nearest = near; pinned = pin.id }
    }
    if (pinned) { this.onPin(pinned); return }
    const hit = this.raycast(o, d, 400)
    this.selected = hit
    this.rebuildLines()
    this.onPick(hit)
  }

  private raycast(o: number[], d: number[], maxDist: number): PickInfo | null {
    let x = Math.floor(o[0]), y = Math.floor(o[1]), z = Math.floor(o[2])
    const step = d.map((v) => (v > 0 ? 1 : -1))
    const tDelta = d.map((v) => (v === 0 ? Infinity : Math.abs(1 / v)))
    const frac = (v: number, dv: number) => (dv > 0 ? Math.floor(v) + 1 - v : v - Math.floor(v))
    const tMax = [0, 1, 2].map((i) => (d[i] === 0 ? Infinity : frac(o[i], d[i]) * tDelta[i]))
    let t = 0
    while (t < maxDist) {
      const s = this.world.getState(x, y, z)
      const df = this.world.getDiff(x, y, z)
      const removed = df?.kind === 2
      if (s > 0 || removed) {
        const name = removed ? this.table.states[df!.before] : this.table.states[s]
        if (removed || !/(^|:)(air|cave_air|void_air|water)(\[|$)/.test(name)) {
          const chunk = this.world.chunk(x >> 4, z >> 4)
          const bi = chunk?.biomes.get(y >> 4)
          return {
            x, y, z, state: name, kind: df ? KIND_NAMES[df.kind] : 'same',
            before: df && df.kind !== 2 ? this.table.states[df.before] : undefined,
            biome: bi ? this.table.biomes[bi[(((y & 15) >> 2) << 4) | (((z & 15) >> 2) << 2) | ((x & 15) >> 2)]] : undefined,
            blockEntity: chunk?.blockEntities.get((y >> 4) * 4096 + (((y & 15) << 8) | ((z & 15) << 4) | (x & 15))),
          }
        }
      }
      const a = tMax[0] < tMax[1] ? (tMax[0] < tMax[2] ? 0 : 2) : tMax[1] < tMax[2] ? 1 : 2
      t = tMax[a]; tMax[a] += tDelta[a]
      if (a === 0) x += step[0]; else if (a === 1) y += step[1]; else z += step[2]
    }
    return null
  }

  // ---- 迴圈與狀態 ----

  private loop = () => {
    if (this.disposed) return
    this.raf = requestAnimationFrame(this.loop)
    const now = performance.now(), dt = Math.min(0.1, (now - this.lastFrame) / 1000)
    this.lastFrame = now
    if (this.camera.update(dt)) this.needsRender = true
    if (!this.needsRender) return
    this.needsRender = false
    const c = this.cfg.canvas
    const cam = { eye: this.camera.eye(), viewProj: this.camera.viewProj(c.width / Math.max(1, c.height)) }
    const t = performance.now()
    this.renderer.render(cam, (now - this.t0) / 1000)
    this.fpsAcc.push(performance.now() - t)
    if (this.fpsAcc.length > 30) this.fpsAcc.shift()
    this.publishStats()
  }

  private statsTimer = 0
  private publishStats() {
    if (this.statsTimer) return
    this.statsTimer = window.setTimeout(() => {
      this.statsTimer = 0
      const ms = this.fpsAcc.length ? this.fpsAcc.reduce((a, b) => a + b, 0) / this.fpsAcc.length : 0
      this.stats = {
        ...this.stats,
        chunks: this.world.chunks.size, sectionsMeshed: this.meshed.size, pendingMeshes: this.dirty.size + this.inflight.size,
        gpuMB: this.renderer.gpuBytes / (1024 * 1024), drawnSections: this.renderer.drawn.sections, drawnQuads: this.renderer.drawn.quads,
        windowsLoading: [...this.windows.values()].filter((s) => s === 'loading').length, lodRegions: this.renderer.lod.size,
        meshMsAvg: this.meshMs.length ? this.meshMs.reduce((a, b) => a + b, 0) / this.meshMs.length : 0,
        fps: ms ? 1000 / ms : 0,
        ready: this.windows.size > 0 && ![...this.windows.values()].includes('loading') && this.dirty.size + this.inflight.size === 0 && this.lodLoading.size === 0 && !this.lodDirty.size,
      }
      this.onStats(this.stats)
    }, 120)
  }

  private scheduleViewEvent() {
    if (this.viewTimer) return
    this.viewTimer = window.setTimeout(() => { this.viewTimer = 0; this.onViewChange(this.getViewState()) }, 400)
  }

  /** 跳到某個 chunk（變動 chunk 清單用）。 */
  flyToChunk(cx: number, cz: number) {
    let y = this.lodHeight(cx, cz)
    if (y === null) y = 64
    this.camera.setMode('orbit')
    this.camera.lookAt([cx * 16 + 8, y, cz * 16 + 8], 40)
    this.scheduleStream(true)
  }

  dispose() {
    this.disposed = true
    cancelAnimationFrame(this.raf)
    for (const t of [this.lodTimer, this.streamTimer, this.viewTimer, this.statsTimer]) clearTimeout(t)
    this.detach()
    this.ro.disconnect()
    for (const w of this.workers) w.terminate()
    this.renderer.clearAll()
    this.renderer.gl.getExtension('WEBGL_lose_context')?.loseContext()
  }

  /** 測試／截圖用：等到串流與網格都完成。 */
  async whenIdle(timeoutMs = 60000): Promise<void> {
    const t = performance.now()
    while (performance.now() - t < timeoutMs) {
      this.publishStats()
      await new Promise((r) => setTimeout(r, 150))
      if (this.stats.ready) return
    }
  }
}

interface EntityRaw { uuid: string; id: string; x: number; y: number; z: number; kind?: string; name?: string }
type EntityRec = EntityRaw

function addBox(out: number[], x0: number, y0: number, z0: number, x1: number, y1: number, z1: number, c: number[]) {
  const p = [[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1], [x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]]
  const e = [[0, 1], [1, 2], [2, 3], [3, 0], [4, 5], [5, 6], [6, 7], [7, 4], [0, 4], [1, 5], [2, 6], [3, 7]]
  for (const [a, b] of e) out.push(...p[a], ...c, ...p[b], ...c)
}
