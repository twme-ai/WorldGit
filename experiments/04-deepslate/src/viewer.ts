// 極簡 WebGL2 檢視器：吃 worker 回傳的 section 網格（interleaved Float32），支援 diff 上色/鬼影。
import { mat4, vec3 } from 'gl-matrix'
import { NF, type Layer } from './mesher.ts'

const VS = `#version 300 es
precision highp float;
in vec3 a_pos; in vec2 a_uv; in vec4 a_lim; in vec3 a_col; in float a_light; in float a_kind; in float a_alpha;
uniform mat4 u_vp; uniform vec3 u_origin;
out vec2 v_uv; out vec4 v_lim; out vec3 v_col; out float v_kind; out float v_alpha; out float v_dist;
void main(){
  vec4 p = vec4(a_pos + u_origin, 1.0);
  gl_Position = u_vp * p;
  v_uv=a_uv; v_lim=a_lim; v_col=a_col*a_light; v_kind=a_kind; v_alpha=a_alpha; v_dist=gl_Position.w;
}`
const FS = `#version 300 es
precision highp float;
in vec2 v_uv; in vec4 v_lim; in vec3 v_col; in float v_kind; in float v_alpha; in float v_dist;
uniform sampler2D u_atlas; uniform float u_pixel; uniform int u_diff; uniform int u_only; uniform vec3 u_sky; uniform float u_fog;
out vec4 o;
void main(){
  vec2 uv = clamp(v_uv, v_lim.xy + 0.5*u_pixel, v_lim.zw - 0.5*u_pixel);
  vec4 t = texture(u_atlas, uv);
  if (t.a < 0.01) discard;
  vec3 c = t.rgb * v_col; float a = t.a * v_alpha;
  int k = int(v_kind + 0.5);
  if (u_diff == 1) {
    if (k == 1) c = mix(c, vec3(0.15,1.0,0.15), 0.55);
    else if (k == 3) c = mix(c, vec3(1.0,0.85,0.1), 0.55);
    else if (k == 2) { c = mix(c, vec3(1.0,0.1,0.1), 0.65); a *= 0.5; }
    else if (u_only == 1) discard;
  }
  float f = clamp((v_dist - u_fog*0.6) / (u_fog*0.4), 0.0, 1.0);
  o = vec4(mix(c, u_sky, f), a);
}`

interface GpuMesh { vao: WebGLVertexArrayObject; buf: WebGLBuffer; quads: number; origin: [number, number, number]; layer: Layer }

export class Viewer {
  gl: WebGL2RenderingContext
  prog: WebGLProgram
  idxBuf: WebGLBuffer
  atlas!: WebGLTexture
  pixel = 1 / 2048
  meshes = new Map<string, GpuMesh[]>() // key = `${set}:${cx},${sy},${cz}`
  cam = { pos: [0, 100, 0] as [number, number, number], yaw: 0, pitch: 0 }
  view = mat4.create(); proj = mat4.create(); vp = mat4.create()
  uniforms: Record<string, WebGLUniformLocation | null> = {}
  drawn = { meshes: 0, quads: 0 }
  static MAXQ = 65536
  constructor(readonly canvas: HTMLCanvasElement) {
    const gl = canvas.getContext('webgl2', { antialias: false, preserveDrawingBuffer: true })
    if (!gl) throw new Error('WebGL2 not available')
    this.gl = gl
    const sh = (type: number, src: string) => { const s = gl.createShader(type)!; gl.shaderSource(s, src); gl.compileShader(s); if (!gl.getShaderParameter(s, gl.COMPILE_STATUS)) throw new Error(gl.getShaderInfoLog(s) ?? 'shader'); return s }
    const p = gl.createProgram()!
    gl.attachShader(p, sh(gl.VERTEX_SHADER, VS)); gl.attachShader(p, sh(gl.FRAGMENT_SHADER, FS)); gl.linkProgram(p)
    if (!gl.getProgramParameter(p, gl.LINK_STATUS)) throw new Error(gl.getProgramInfoLog(p) ?? 'link')
    this.prog = p
    for (const n of ['u_vp', 'u_origin', 'u_atlas', 'u_pixel', 'u_diff', 'u_only', 'u_sky', 'u_fog']) this.uniforms[n] = gl.getUniformLocation(p, n)
    // 共用 index buffer：quad i 的頂點是 4i..4i+3，兩個三角形 (0,1,2)(0,2,3)
    const idx = new Uint32Array(Viewer.MAXQ * 6)
    for (let i = 0; i < Viewer.MAXQ; i++) idx.set([4 * i, 4 * i + 1, 4 * i + 2, 4 * i, 4 * i + 2, 4 * i + 3], i * 6)
    this.idxBuf = gl.createBuffer()!
    gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.idxBuf); gl.bufferData(gl.ELEMENT_ARRAY_BUFFER, idx, gl.STATIC_DRAW)
  }
  setAtlas(img: ImageBitmap, size: number) {
    const gl = this.gl
    this.atlas = gl.createTexture()!
    gl.bindTexture(gl.TEXTURE_2D, this.atlas)
    gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, false)
    gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, img)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.NEAREST)
    gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.NEAREST)
    this.pixel = 1 / size
  }
  clear() { for (const l of this.meshes.values()) l.forEach(m => this.free(m)); this.meshes.clear() }
  private free(m: GpuMesh) { this.gl.deleteBuffer(m.buf); this.gl.deleteVertexArray(m.vao) }
  upload(set: string, m: { cx: number; sy: number; cz: number; layers: Partial<Record<Layer, { data: Float32Array; quads: number }>> }) {
    const gl = this.gl, key = `${set}:${m.cx},${m.sy},${m.cz}`
    this.meshes.get(key)?.forEach(x => this.free(x))
    const out: GpuMesh[] = []
    for (const [layer, lm] of Object.entries(m.layers) as [Layer, { data: Float32Array; quads: number }][]) {
      const vao = gl.createVertexArray()!; gl.bindVertexArray(vao)
      const buf = gl.createBuffer()!; gl.bindBuffer(gl.ARRAY_BUFFER, buf); gl.bufferData(gl.ARRAY_BUFFER, lm.data, gl.STATIC_DRAW)
      const stride = NF * 4
      const attr = (name: string, size: number, off: number) => { const l = gl.getAttribLocation(this.prog, name); gl.enableVertexAttribArray(l); gl.vertexAttribPointer(l, size, gl.FLOAT, false, stride, off * 4) }
      attr('a_pos', 3, 0); attr('a_uv', 2, 3); attr('a_lim', 4, 5); attr('a_col', 3, 9); attr('a_light', 1, 12); attr('a_kind', 1, 13); attr('a_alpha', 1, 14)
      gl.bindBuffer(gl.ELEMENT_ARRAY_BUFFER, this.idxBuf)
      out.push({ vao, buf, quads: Math.min(lm.quads, Viewer.MAXQ), origin: [m.cx * 16, m.sy * 16, m.cz * 16], layer })
    }
    gl.bindVertexArray(null)
    if (out.length) this.meshes.set(key, out); else this.meshes.delete(key)
  }
  forward() {
    const { yaw, pitch } = this.cam
    return vec3.fromValues(Math.cos(pitch) * Math.sin(yaw), Math.sin(pitch), -Math.cos(pitch) * Math.cos(yaw))
  }
  lookAt(pos: [number, number, number], target: [number, number, number]) {
    const d = vec3.sub(vec3.create(), target, pos)
    this.cam.pos = pos
    this.cam.yaw = Math.atan2(d[0], -d[2])
    this.cam.pitch = Math.atan2(d[1], Math.hypot(d[0], d[2]))
  }
  updateMatrices() {
    const c = this.canvas
    mat4.perspective(this.proj, 70 * Math.PI / 180, c.width / c.height, 0.1, 3000)
    const eye = vec3.fromValues(...this.cam.pos)
    const at = vec3.add(vec3.create(), eye, this.forward())
    mat4.lookAt(this.view, eye, at, [0, 1, 0])
    mat4.multiply(this.vp, this.proj, this.view)
  }
  /** views: 每個 section key 決定用哪一組網格（由 main 提供選擇函式） */
  draw(pick: (cx: number, sy: number, cz: number) => GpuMesh[] | undefined, opts: { diff: boolean; only: boolean; hideTrans?: boolean }) {
    const gl = this.gl, u = this.uniforms
    gl.viewport(0, 0, this.canvas.width, this.canvas.height)
    gl.clearColor(0.47, 0.65, 1, 1); gl.clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
    this.updateMatrices()
    gl.useProgram(this.prog)
    gl.uniformMatrix4fv(u.u_vp, false, this.vp)
    gl.uniform1i(u.u_atlas, 0); gl.uniform1f(u.u_pixel, this.pixel); gl.uniform1i(u.u_diff, opts.diff ? 1 : 0); gl.uniform1i(u.u_only, opts.only ? 1 : 0)
    gl.uniform3f(u.u_sky, 0.47, 0.65, 1); gl.uniform1f(u.u_fog, 3000)
    gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, this.atlas)
    gl.enable(gl.DEPTH_TEST); gl.depthFunc(gl.LEQUAL); gl.enable(gl.CULL_FACE); gl.cullFace(gl.BACK)
    const list: GpuMesh[] = []
    for (const key of this.sectionKeys) { const [cx, sy, cz] = key; const ms = pick(cx, sy, cz); if (ms) list.push(...ms) }
    this.drawn = { meshes: 0, quads: 0 }
    const drawLayer = (layer: Layer, blend: boolean, sort: boolean) => {
      const ls = list.filter(m => m.layer === layer)
      if (sort) { const p = this.cam.pos; const d = (m: GpuMesh) => (m.origin[0] + 8 - p[0]) ** 2 + (m.origin[1] + 8 - p[1]) ** 2 + (m.origin[2] + 8 - p[2]) ** 2; ls.sort((a, b) => d(b) - d(a)) }
      gl.enable(gl.BLEND); gl.blendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA); if (blend) gl.depthMask(false)
      for (const m of ls) {
        gl.uniform3f(u.u_origin, m.origin[0], m.origin[1], m.origin[2])
        gl.bindVertexArray(m.vao); gl.drawElements(gl.TRIANGLES, m.quads * 6, gl.UNSIGNED_INT, 0)
        this.drawn.meshes++; this.drawn.quads += m.quads
      }
      gl.depthMask(true)
    }
    drawLayer('opaque', false, false)
    gl.disable(gl.CULL_FACE)
    if (!opts.hideTrans) drawLayer('trans', true, true)
    drawLayer('ghost', true, true)
    gl.bindVertexArray(null)
  }
  sectionKeys: [number, number, number][] = []
  project(x: number, y: number, z: number): [number, number, number] | null {
    const v = [x, y, z, 1] as unknown as Float32Array
    const o = new Float32Array(4)
    o[0] = this.vp[0] * x + this.vp[4] * y + this.vp[8] * z + this.vp[12]
    o[1] = this.vp[1] * x + this.vp[5] * y + this.vp[9] * z + this.vp[13]
    o[2] = this.vp[2] * x + this.vp[6] * y + this.vp[10] * z + this.vp[14]
    o[3] = this.vp[3] * x + this.vp[7] * y + this.vp[11] * z + this.vp[15]
    void v
    if (o[3] <= 0.01) return null
    return [(o[0] / o[3] * 0.5 + 0.5) * this.canvas.width, (1 - (o[1] / o[3] * 0.5 + 0.5)) * this.canvas.height, o[3]]
  }
}
export type { GpuMesh }
