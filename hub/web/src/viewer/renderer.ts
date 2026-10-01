// WebGL2 繪製器：section 網格（opaque／trans／ghost 三批）、LOD 網格、線框（選取、實體、chunk 包圍盒）。
// 座標一律「相機相對」：每個 mesh 以 (區段原點 − 相機位置) 當 uniform，避免遠處的浮點抖動。
import { mat4 } from 'gl-matrix'
import { FRAG, LINE_FRAG, LINE_VERT, VERT } from './shaders.ts'
import { VERTEX_BYTES } from './vertex.ts'
import type { Layer } from './mesher.ts'

export interface GpuLayer { vao: WebGLVertexArrayObject; vbo: WebGLBuffer; quads: number; bytes: number }
export interface GpuMesh { origin: [number, number, number]; layers: Partial<Record<Layer, GpuLayer>>; radius: number }
export type RenderMode = 'color' | 'changed' | 'dim'

export interface CameraState {
  eye: [number, number, number]
  /** 相機相對的 view（僅旋轉）× projection */
  viewProj: mat4
}

export class Renderer {
  readonly gl: WebGL2RenderingContext
  private prog!: WebGLProgram
  private lineProg!: WebGLProgram
  private u: Record<string, WebGLUniformLocation | null> = {}
  private lu: Record<string, WebGLUniformLocation | null> = {}
  private atlas: WebGLTexture | null = null
  private rectTex: WebGLTexture | null = null
  private indexBuf: WebGLBuffer
  private indexQuads = 0
  readonly meshes = new Map<string, GpuMesh>()
  readonly lod = new Map<string, GpuMesh>()
  private lineVao: WebGLVertexArrayObject
  private lineVbo: WebGLBuffer
  private lineVertexCount = 0
  mode: RenderMode = 'color'
  palette: Float32Array = new Float32Array(15)
  fog: [number, number, number] = [0.05, 0.07, 0.11]
  fogRange: [number, number] = [220, 520]
  gpuBytes = 0
  drawn = { sections: 0, quads: 0 }
  private planes = new Float32Array(24)

  constructor(readonly canvas: HTMLCanvasElement) {
    const gl = canvas.getContext('webgl2', { antialias: true, alpha: false, powerPreference: 'high-performance' })
    if (!gl) throw new Error('此瀏覽器不支援 WebGL2')
    this.gl = gl
    this.prog = this.link(VERT, FRAG)
    this.lineProg = this.link(LINE_VERT, LINE_FRAG)
    for (const n of ['uVP', 'uOffset', 'uAtlas', 'uRects', 'uPal', 'uMode', 'uPass', 'uTime', 'uFog', 'uFogRange']) this.u[n] = gl.getUniformLocation(this.prog, n)
    for (const n of ['uVP', 'uOffset']) this.lu[n] = gl.getUniformLocation(this.lineProg, n)
    this.indexBuf = gl.createBuffer()!
    this.lineVao = gl.createVertexArray()!
    this.lineVbo = gl.createBuffer()!
    gl.bindVertexArray(this.lineVao)
    gl.bindBuffer(gl.ARRAY_BUFFER, this.lineVbo)
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.FLOAT, false, 28, 0)
    gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 4, gl.FLOAT, false, 28, 12)
    gl.bindVertexArray(null)
    this.ensureIndex(4096)
  }

  private link(vs: string, fs: string) {
    const gl = this.gl
    const mk = (t: number, src: string) => {
      const s = gl.createShader(t)!
      gl.shaderSource(s, src); gl.compileShader(s)
      if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error('shader：' + gl.getShaderInfoLog(s))
      return s
    }
    const p = gl.createProgram()!
    gl.attachShader(p, mk(gl.VERTEX_SHADER, vs)); gl.attachShader(p, mk(gl.FRAGMENT_SHADER, fs)); gl.linkProgram(p)
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error('program：' + gl.getProgramInfoLog(p))
    return p
  }

  get rendererName(): string {
    const ext = this.gl.getExtension('WEBGL_debug_renderer_info')
    return ext ? String(this.gl.getParameter(ext.UNMASKED_RENDERER_WEBGL)) : 'unknown'
  }

  setAtlas(img: ImageBitmap, rects: Float32Array) {
    const gl = this.gl
    this.atlas = gl.createTexture()
    gl.bindTexture(gl.TEXTURE_2D, this.atlas)
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, gl.RGBA, gl.UNSIGNED_BYTE, img)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE)
    // rect 表：每個矩形 1 個 RGBA32F texel，寬 2048
    const n = rects.length / 4, rows = Math.max(1, Math.ceil(n / 2048))
    const data = new Float32Array(2048 * rows * 4)
    data.set(rects)
    this.rectTex = gl.createTexture()
    gl.bindTexture(gl.TEXTURE_2D, this.rectTex)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA32F, 2048, rows, 0, gl.RGBA, gl.FLOAT, data)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
    this.gpuBytes += img.width * img.height * 4 + data.byteLength
  }

  setPalette(p: { added: string; removed: string; modified: string; conflict: string }) {
    const rgb = (h: string) => [parseInt(h.slice(1, 3), 16) / 255, parseInt(h.slice(3, 5), 16) / 255, parseInt(h.slice(5, 7), 16) / 255]
    this.palette.set([0, 0, 0, ...rgb(p.added), ...rgb(p.removed), ...rgb(p.modified), ...rgb(p.conflict)])
  }

  /** 共用 index buffer：每個 quad 6 個 uint32（0,1,2,0,2,3）。需要更大時以同一個 buffer 物件重新配置。 */
  private ensureIndex(quads: number) {
    if (quads <= this.indexQuads) return
    const n = Math.max(quads, this.indexQuads * 2, 4096)
    const idx = new Uint32Array(n * 6)
    for (let q = 0; q < n; q++) idx.set([q * 4, q * 4 + 1, q * 4 + 2, q * 4, q * 4 + 2, q * 4 + 3], q * 6)
    const gl = this.gl
    gl.bindVertexArray(null)
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.indexBuf)
    gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, idx, gl.STATIC_DRAW)
    this.gpuBytes += (n - this.indexQuads) * 24
    this.indexQuads = n
  }

  private upload(data: ArrayBuffer): GpuLayer {
    const gl = this.gl
    const quads = data.byteLength / (VERTEX_BYTES * 4)
    this.ensureIndex(quads)
    const vao = gl.createVertexArray()!, vbo = gl.createBuffer()!
    gl.bindVertexArray(vao)
    gl.bindBuffer(gl.ARRAY_BUFFER, vbo)
    gl.bufferData(gl.ARRAY_BUFFER, data, gl.STATIC_DRAW)
    gl.enableVertexAttribArray(0); gl.vertexAttribPointer(0, 3, gl.SHORT, false, VERTEX_BYTES, 0)
    gl.enableVertexAttribArray(1); gl.vertexAttribPointer(1, 2, gl.UNSIGNED_SHORT, false, VERTEX_BYTES, 6)
    gl.enableVertexAttribArray(2); gl.vertexAttribIPointer(2, 1, gl.UNSIGNED_SHORT, VERTEX_BYTES, 10)
    gl.enableVertexAttribArray(3); gl.vertexAttribIPointer(3, 4, gl.UNSIGNED_BYTE, VERTEX_BYTES, 12)
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.indexBuf)
    gl.bindVertexArray(null)
    this.gpuBytes += data.byteLength
    return { vao, vbo, quads, bytes: data.byteLength }
  }

  private free(m: GpuMesh) {
    const gl = this.gl
    for (const l of Object.values(m.layers)) {
      if (!l) continue
      gl.deleteVertexArray(l.vao); gl.deleteBuffer(l.vbo)
      this.gpuBytes -= l.bytes
    }
  }

  setSection(key: string, origin: [number, number, number], layers: Partial<Record<Layer, ArrayBuffer>>) {
    this.removeSection(key)
    if (!Object.keys(layers).length) return
    const m: GpuMesh = { origin, layers: {}, radius: 14 }
    for (const [k, v] of Object.entries(layers)) m.layers[k as Layer] = this.upload(v as ArrayBuffer)
    this.meshes.set(key, m)
  }

  removeSection(key: string) {
    const m = this.meshes.get(key)
    if (m) { this.free(m); this.meshes.delete(key) }
  }

  setLod(key: string, origin: [number, number, number], data: ArrayBuffer | null, radius: number) {
    const old = this.lod.get(key)
    if (old) { this.free(old); this.lod.delete(key) }
    if (!data) return
    const m: GpuMesh = { origin, layers: { opaque: this.upload(data) }, radius }
    this.lod.set(key, m)
  }

  clearAll() {
    for (const k of [...this.meshes.keys()]) this.removeSection(k)
    for (const m of this.lod.values()) this.free(m)
    this.lod.clear()
  }

  /** 線框：每條線 2 個頂點，每頂點 x y z r g b a（世界座標，內部轉為相機相對）。 */
  setLines(vertices: Float32Array) {
    const gl = this.gl
    gl.bindBuffer(gl.ARRAY_BUFFER, this.lineVbo)
    gl.bufferData(gl.ARRAY_BUFFER, vertices, gl.DYNAMIC_DRAW)
    this.lineVertexCount = vertices.length / 7
  }

  resize(w: number, h: number) {
    if (this.canvas.width !== w || this.canvas.height !== h) { this.canvas.width = w; this.canvas.height = h }
    this.gl.viewport(0, 0, w, h)
  }

  private extractPlanes(m: mat4) {
    const p = this.planes
    const r = (i: number, j: number) => m[j * 4 + i]
    const set = (k: number, a: number, b: number, c: number, d: number) => {
      const l = Math.hypot(a, b, c) || 1
      p[k * 4] = a / l; p[k * 4 + 1] = b / l; p[k * 4 + 2] = c / l; p[k * 4 + 3] = d / l
    }
    set(0, r(3, 0) + r(0, 0), r(3, 1) + r(0, 1), r(3, 2) + r(0, 2), r(3, 3) + r(0, 3))
    set(1, r(3, 0) - r(0, 0), r(3, 1) - r(0, 1), r(3, 2) - r(0, 2), r(3, 3) - r(0, 3))
    set(2, r(3, 0) + r(1, 0), r(3, 1) + r(1, 1), r(3, 2) + r(1, 2), r(3, 3) + r(1, 3))
    set(3, r(3, 0) - r(1, 0), r(3, 1) - r(1, 1), r(3, 2) - r(1, 2), r(3, 3) - r(1, 3))
    set(4, r(3, 0) + r(2, 0), r(3, 1) + r(2, 1), r(3, 2) + r(2, 2), r(3, 3) + r(2, 3))
    set(5, r(3, 0) - r(2, 0), r(3, 1) - r(2, 1), r(3, 2) - r(2, 2), r(3, 3) - r(2, 3))
  }

  private visible(cx: number, cy: number, cz: number, radius: number) {
    const p = this.planes
    for (let k = 0; k < 6; k++) if (p[k * 4] * cx + p[k * 4 + 1] * cy + p[k * 4 + 2] * cz + p[k * 4 + 3] < -radius) return false
    return true
  }

  render(cam: CameraState, time: number) {
    const gl = this.gl
    gl.clearColor(this.fog[0], this.fog[1], this.fog[2], 1)
    gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
    if (!this.atlas) return
    this.extractPlanes(cam.viewProj)
    gl.enable(gl.DEPTH_TEST); gl.depthFunc(gl.LEQUAL)
    gl.enable(gl.CULL_FACE); gl.cullFace(gl.BACK); gl.frontFace(gl.CCW)
    gl.useProgram(this.prog)
    gl.uniformMatrix4fv(this.u.uVP, false, cam.viewProj)
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, this.atlas); gl.uniform1i(this.u.uAtlas, 0)
    gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, this.rectTex); gl.uniform1i(this.u.uRects, 1)
    gl.uniform3fv(this.u.uPal, this.palette)
    gl.uniform1i(this.u.uMode, this.mode === 'color' ? 0 : this.mode === 'changed' ? 1 : 2)
    gl.uniform1f(this.u.uTime, time)
    gl.uniform3fv(this.u.uFog, this.fog)
    gl.uniform2fv(this.u.uFogRange, this.fogRange)
    this.drawn = { sections: 0, quads: 0 }
    const [ex, ey, ez] = cam.eye
    const vis: { m: GpuMesh; d: number; ox: number; oy: number; oz: number; lod: boolean }[] = []
    for (const group of [this.lod, this.meshes]) {
      for (const m of group.values()) {
        const ox = m.origin[0] - ex, oy = m.origin[1] - ey, oz = m.origin[2] - ez
        const c = group === this.lod ? 0 : 8
        if (!this.visible(ox + c, oy + c, oz + c, m.radius)) continue
        vis.push({ m, d: (ox + c) ** 2 + (oy + c) ** 2 + (oz + c) ** 2, ox, oy, oz, lod: group === this.lod })
      }
    }
    // pass 0：不透明（LOD 與近景），LOD 往後推一點避免與近景 z-fighting
    gl.disable(gl.BLEND); gl.depthMask(true)
    gl.uniform1i(this.u.uPass, 0)
    gl.polygonOffset(6, 6)
    for (const v of vis) {
      if (v.lod) gl.enable(gl.POLYGON_OFFSET_FILL); else gl.disable(gl.POLYGON_OFFSET_FILL)
      this.draw(v, 'opaque')
    }
    gl.disable(gl.POLYGON_OFFSET_FILL)
    // pass 1/2：半透明與鬼影，由遠到近
    gl.enable(gl.BLEND); gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA); gl.depthMask(false)
    vis.sort((a, b) => b.d - a.d)
    gl.uniform1i(this.u.uPass, 1)
    for (const v of vis) if (!v.lod) this.draw(v, 'trans')
    gl.disable(gl.CULL_FACE)
    gl.uniform1i(this.u.uPass, 2)
    for (const v of vis) if (!v.lod) this.draw(v, 'ghost')
    gl.depthMask(true)
    // 線框
    if (this.lineVertexCount) {
      gl.useProgram(this.lineProg)
      gl.uniformMatrix4fv(this.lu.uVP, false, cam.viewProj)
      gl.uniform3f(this.lu.uOffset, -ex, -ey, -ez)
      gl.bindVertexArray(this.lineVao)
      gl.drawArrays(gl.LINES, 0, this.lineVertexCount)
      gl.bindVertexArray(null)
    }
    gl.disable(gl.BLEND)
  }

  private draw(v: { m: GpuMesh; ox: number; oy: number; oz: number }, layer: Layer) {
    const l = v.m.layers[layer]
    if (!l) return
    const gl = this.gl
    gl.uniform3f(this.u.uOffset, v.ox, v.oy, v.oz)
    gl.bindVertexArray(l.vao)
    gl.drawElements(gl.TRIANGLES, l.quads * 6, gl.UNSIGNED_INT, 0)
    if (layer === 'opaque') { this.drawn.sections++; this.drawn.quads += l.quads }
  }
}

