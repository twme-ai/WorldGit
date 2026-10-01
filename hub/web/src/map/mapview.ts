// 俯視地圖：Hub 預先算好的 region tile（512×512 PNG）+ 變動 chunk 疊圖；拖曳平移、滾輪縮放、點選變動 chunk。
import { api, getBlob, type ChangedChunk, type Palette, type TileRef } from '../api.ts'

export interface MapOptions {
  owner: string; world: string; dimRepo: string; rev: string
  palette: Palette
  changed?: ChangedChunk[]
  onChunk?: (cx: number, cz: number) => void
}

export class MapView {
  private ctx: CanvasRenderingContext2D
  private tiles = new Map<string, ImageBitmap>()
  private refs: TileRef[] = []
  private scale = 0.6 // px / 格
  private ox = 0 // 畫面中心對應的世界座標
  private oz = 0
  private hover: [number, number] | null = null
  private disposed = false
  private raf = 0
  private dirty = true
  private opts!: MapOptions

  constructor(readonly canvas: HTMLCanvasElement, readonly hud: HTMLElement) {
    this.ctx = canvas.getContext('2d')!
    let drag = false, lx = 0, ly = 0, moved = 0
    canvas.addEventListener('pointerdown', (e) => { drag = true; lx = e.clientX; ly = e.clientY; moved = 0; canvas.setPointerCapture(e.pointerId); canvas.style.cursor = 'grabbing' })
    canvas.addEventListener('pointerup', (e) => {
      drag = false; canvas.style.cursor = 'grab'
      if (moved < 4) { const [cx, cz] = this.toChunk(e); if (this.isChanged(cx, cz)) this.opts.onChunk?.(cx, cz) }
    })
    canvas.addEventListener('pointermove', (e) => {
      if (drag) { this.ox -= (e.clientX - lx) / this.scale; this.oz -= (e.clientY - ly) / this.scale; moved += Math.abs(e.clientX - lx) + Math.abs(e.clientY - ly); lx = e.clientX; ly = e.clientY }
      this.hover = this.toChunk(e)
      this.updateHud()
      this.dirty = true
    })
    canvas.addEventListener('pointerleave', () => { this.hover = null; this.dirty = true; this.updateHud() })
    canvas.addEventListener('wheel', (e) => {
      e.preventDefault()
      const before = this.toWorld(e)
      this.scale = Math.max(0.05, Math.min(8, this.scale * Math.exp(-e.deltaY * 0.0015)))
      const after = this.toWorld(e)
      this.ox += before[0] - after[0]; this.oz += before[1] - after[1]
      this.dirty = true
    }, { passive: false })
    new ResizeObserver(() => { this.resize(); this.dirty = true }).observe(canvas)
    this.resize()
    const loop = () => { if (this.disposed) return; this.raf = requestAnimationFrame(loop); if (this.dirty) { this.dirty = false; this.draw() } }
    loop()
  }

  private resize() {
    const dpr = Math.min(window.devicePixelRatio || 1, 2)
    this.canvas.width = Math.max(2, Math.round(this.canvas.clientWidth * dpr))
    this.canvas.height = Math.max(2, Math.round(this.canvas.clientHeight * dpr))
  }

  private toWorld(e: { clientX: number; clientY: number }): [number, number] {
    const r = this.canvas.getBoundingClientRect()
    return [this.ox + (e.clientX - r.left - r.width / 2) / this.scale, this.oz + (e.clientY - r.top - r.height / 2) / this.scale]
  }
  private toChunk(e: { clientX: number; clientY: number }): [number, number] {
    const [x, z] = this.toWorld(e)
    return [Math.floor(x / 16), Math.floor(z / 16)]
  }
  private changedMap = new Map<string, ChangedChunk>()
  private isChanged(cx: number, cz: number) { return this.changedMap.has(`${cx},${cz}`) }

  private updateHud() {
    if (!this.hover) { this.hud.textContent = ''; return }
    const [cx, cz] = this.hover
    const c = this.changedMap.get(`${cx},${cz}`)
    this.hud.textContent = `chunk ${cx}, ${cz}　方塊 ${cx * 16}, ${cz * 16}` + (c ? `　+${c[2]} -${c[3]} ~${c[4]}` : '')
  }

  async load(opts: MapOptions) {
    this.opts = opts
    this.changedMap = new Map((opts.changed ?? []).map((c) => [`${c[0]},${c[1]}`, c]))
    this.refs = await api.tiles(opts.owner, opts.world, opts.dimRepo, opts.rev)
    if (this.refs.length) {
      const x0 = Math.min(...this.refs.map((t) => t.rx)) * 512, x1 = (Math.max(...this.refs.map((t) => t.rx)) + 1) * 512
      const z0 = Math.min(...this.refs.map((t) => t.rz)) * 512, z1 = (Math.max(...this.refs.map((t) => t.rz)) + 1) * 512
      this.ox = (x0 + x1) / 2; this.oz = (z0 + z1) / 2
      this.scale = Math.min(this.canvas.clientWidth / (x1 - x0), this.canvas.clientHeight / (z1 - z0)) * 0.95
      if (opts.changed?.length) {
        const b = [Math.min(...opts.changed.map((c) => c[0])), Math.min(...opts.changed.map((c) => c[1])), Math.max(...opts.changed.map((c) => c[0])), Math.max(...opts.changed.map((c) => c[1]))]
        if (b[2] - b[0] < 80 && b[3] - b[1] < 80) { this.ox = ((b[0] + b[2] + 1) * 16) / 2; this.oz = ((b[1] + b[3] + 1) * 16) / 2; this.scale = Math.max(this.scale, Math.min(3, Math.min(this.canvas.clientWidth / ((b[2] - b[0] + 6) * 16), this.canvas.clientHeight / ((b[3] - b[1] + 6) * 16)))) }
      }
    }
    this.dirty = true
    await Promise.all(this.refs.map(async (t) => {
      try {
        const bmp = await createImageBitmap(await getBlob(`/api/v1/worlds/${opts.owner}/${opts.world}/dims/${opts.dimRepo}/commits/${opts.rev}/tiles/${t.rx}/${t.rz}.png`))
        this.tiles.set(`${t.rx},${t.rz}`, bmp)
        this.dirty = true
      } catch (e) { console.warn('tile 載入失敗', t, e) }
    }))
  }

  private draw() {
    const { ctx, canvas } = this
    const w = canvas.width, h = canvas.height, dpr = w / Math.max(1, canvas.clientWidth)
    ctx.fillStyle = '#0a0e14'
    ctx.fillRect(0, 0, w, h)
    const s = this.scale * dpr
    const toX = (x: number) => (x - this.ox) * s + w / 2, toY = (z: number) => (z - this.oz) * s + h / 2
    ctx.imageSmoothingEnabled = s < 1
    for (const t of this.refs) {
      const bmp = this.tiles.get(`${t.rx},${t.rz}`)
      const x = toX(t.rx * 512), y = toY(t.rz * 512)
      if (bmp) ctx.drawImage(bmp, x, y, 512 * s, 512 * s)
      ctx.strokeStyle = '#ffffff1a'; ctx.lineWidth = 1
      ctx.strokeRect(x + 0.5, y + 0.5, 512 * s, 512 * s)
    }
    if (this.opts) {
      const p = this.opts.palette
      for (const [, c] of this.changedMap) {
        const kind = c[4] > 0 ? p.modified : c[3] > 0 ? p.removed : c[2] > 0 ? p.added : p.modified
        const x = toX(c[0] * 16), y = toY(c[1] * 16), sz = 16 * s
        ctx.fillStyle = kind + '55'; ctx.fillRect(x, y, sz, sz)
        ctx.strokeStyle = kind; ctx.lineWidth = Math.max(1, 1.5 * dpr)
        if (c[3] > 0 && c[2] === 0 && c[4] === 0) ctx.setLineDash([4 * dpr, 3 * dpr]); else ctx.setLineDash([])
        ctx.strokeRect(x + 0.5, y + 0.5, sz, sz)
      }
      ctx.setLineDash([])
      if (this.hover) {
        ctx.strokeStyle = '#ffffff'; ctx.lineWidth = dpr
        ctx.strokeRect(toX(this.hover[0] * 16) + 0.5, toY(this.hover[1] * 16) + 0.5, 16 * s, 16 * s)
      }
    }
    if (!this.refs.length) { ctx.fillStyle = '#8b98a9'; ctx.font = `${14 * dpr}px system-ui`; ctx.textAlign = 'center'; ctx.fillText('這個維度還沒有資料', w / 2, h / 2) }
  }

  dispose() { this.disposed = true; cancelAnimationFrame(this.raf); for (const b of this.tiles.values()) b.close() }
}
