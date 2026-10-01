// 兩種鏡頭：環繞（像模型檢視器）與飛行（WASD + 滑鼠，像旁觀模式）。座標單位為方塊。
import { mat4 } from 'gl-matrix'

export type CameraMode = 'orbit' | 'fly'

export class Camera {
  mode: CameraMode = 'orbit'
  target: [number, number, number] = [0, 64, 0]
  dist = 48
  yaw = 0.6
  pitch = -0.55
  pos: [number, number, number] = [0, 100, 0]
  fov = (60 * Math.PI) / 180
  near = 0.2
  far = 3000
  speed = 24
  private keys = new Set<string>()
  onChange: () => void = () => {}

  forward(): [number, number, number] {
    const cp = Math.cos(this.pitch)
    return [cp * Math.sin(this.yaw), Math.sin(this.pitch), -cp * Math.cos(this.yaw)]
  }

  eye(): [number, number, number] {
    if (this.mode === 'fly') return [...this.pos]
    const f = this.forward()
    return [this.target[0] - f[0] * this.dist, this.target[1] - f[1] * this.dist, this.target[2] - f[2] * this.dist]
  }

  setMode(mode: CameraMode) {
    if (mode === this.mode) return
    if (mode === 'fly') this.pos = this.eye()
    else { const f = this.forward(); this.target = [this.pos[0] + f[0] * this.dist, this.pos[1] + f[1] * this.dist, this.pos[2] + f[2] * this.dist] }
    this.mode = mode
    this.onChange()
  }

  lookAt(target: [number, number, number], dist = this.dist) {
    this.target = target; this.dist = dist
    if (this.mode === 'fly') this.pos = this.eye()
    this.onChange()
  }

  viewProj(aspect: number): mat4 {
    const f = this.forward()
    const view = mat4.lookAt(mat4.create(), [0, 0, 0], f, [0, 1, 0])
    const proj = mat4.perspective(mat4.create(), this.fov, aspect, this.near, this.far)
    return mat4.multiply(mat4.create(), proj, view)
  }

  /** 螢幕座標（0..1，左上原點）→ 世界射線。 */
  ray(nx: number, ny: number, aspect: number): { o: [number, number, number]; d: [number, number, number] } {
    const f = this.forward()
    const right = normalize(cross(f, [0, 1, 0]))
    const up = cross(right, f)
    const t = Math.tan(this.fov / 2)
    const x = (nx * 2 - 1) * t * aspect, y = (1 - ny * 2) * t
    return { o: this.eye(), d: normalize([f[0] + right[0] * x + up[0] * y, f[1] + right[1] * x + up[1] * y, f[2] + right[2] * x + up[2] * y]) }
  }

  attach(el: HTMLElement): () => void {
    let dragging = false, panning = false, lx = 0, ly = 0
    const down = (e: PointerEvent) => {
      if (e.button > 2) return
      dragging = true; panning = e.button === 2 || e.shiftKey; lx = e.clientX; ly = e.clientY
      el.setPointerCapture(e.pointerId)
    }
    const move = (e: PointerEvent) => {
      if (!dragging) return
      const dx = e.clientX - lx, dy = e.clientY - ly
      lx = e.clientX; ly = e.clientY
      if (panning && this.mode === 'orbit') {
        const f = this.forward(), right = normalize(cross(f, [0, 1, 0])), up = cross(right, f)
        const k = this.dist * 0.0016
        for (let i = 0; i < 3; i++) this.target[i] += (-right[i] * dx + up[i] * dy) * k
      } else {
        const s = this.mode === 'fly' ? 0.0035 : 0.006
        this.yaw += dx * s
        this.pitch = Math.max(-1.55, Math.min(1.55, this.pitch - dy * s))
      }
      this.onChange()
    }
    const up = (e: PointerEvent) => { dragging = false; try { el.releasePointerCapture(e.pointerId) } catch { /* 已釋放 */ } }
    const wheel = (e: WheelEvent) => {
      e.preventDefault()
      if (this.mode === 'orbit') this.dist = Math.max(2, Math.min(1500, this.dist * Math.exp(e.deltaY * 0.0012)))
      else this.speed = Math.max(2, Math.min(400, this.speed * Math.exp(-e.deltaY * 0.001)))
      this.onChange()
    }
    const key = (e: KeyboardEvent, on: boolean) => {
      if ((e.target as HTMLElement)?.tagName === 'INPUT' || (e.target as HTMLElement)?.tagName === 'SELECT') return
      const k = e.key.toLowerCase()
      if ('wasdqe '.includes(k) || k === 'shift') { if (on) this.keys.add(k); else this.keys.delete(k) }
    }
    const kd = (e: KeyboardEvent) => key(e, true), ku = (e: KeyboardEvent) => key(e, false)
    el.addEventListener('pointerdown', down); el.addEventListener('pointermove', move); el.addEventListener('pointerup', up)
    el.addEventListener('wheel', wheel, { passive: false }); el.addEventListener('contextmenu', (e) => e.preventDefault())
    window.addEventListener('keydown', kd); window.addEventListener('keyup', ku)
    return () => {
      el.removeEventListener('pointerdown', down); el.removeEventListener('pointermove', move); el.removeEventListener('pointerup', up)
      el.removeEventListener('wheel', wheel); window.removeEventListener('keydown', kd); window.removeEventListener('keyup', ku)
    }
  }

  /** 每幀移動（WASD 在飛行與環繞模式都可用：環繞時平移 target）。 */
  update(dt: number): boolean {
    if (!this.keys.size) return false
    const f = this.forward(), right = normalize(cross(f, [0, 1, 0]))
    const v = (this.keys.has('shift') ? 3 : 1) * (this.mode === 'fly' ? this.speed : this.dist * 0.8) * dt
    const p = this.mode === 'fly' ? this.pos : this.target
    const move = (dir: number[], s: number) => { for (let i = 0; i < 3; i++) p[i] += dir[i] * s }
    if (this.keys.has('w')) move(f, v)
    if (this.keys.has('s')) move(f, -v)
    if (this.keys.has('d')) move(right, v)
    if (this.keys.has('a')) move(right, -v)
    if (this.keys.has('e') || this.keys.has(' ')) p[1] += v
    if (this.keys.has('q')) p[1] -= v
    this.onChange()
    return true
  }
}

function cross(a: number[], b: number[]): [number, number, number] {
  return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
}
function normalize(a: number[]): [number, number, number] {
  const l = Math.hypot(a[0], a[1], a[2]) || 1
  return [a[0] / l, a[1] / l, a[2] / l]
}
