// 16 B 頂點格式（doc 10 §5.1 的目標格式）。每個 quad 4 個頂點，index buffer 由所有 mesh 共用（0,1,2,0,2,3）。
//
//  offset size  欄位
//   0     6     x y z       int16 ×3，區段內位置，1/256 格（純色 LOD 頂點改用 1/16 格，範圍 ±2048）
//   6     4     u v         uint16 ×2。rect 模式：重複座標（1/256 張貼圖）；direct 模式：整張圖集的 0..65535 座標
//  10     2     rect        uint16。圖集矩形表索引；0xFFFF = direct 模式（直接用 u/v）
//  12     3     r g b       uint8，染色 × 面向明暗
//  15     1     flags       bits 0-2 kind（0 無變動 1 新增 2 移除 3 修改 4 衝突）、bit 3 水（半透明度較高）、bit 4 純色（LOD，忽略貼圖）、bit 5 變動周圍一格
export const VERTEX_BYTES = 16
export const RECT_DIRECT = 0xffff
export const FLAG_WATER = 8
export const FLAG_FLAT = 16
export const FLAG_CONTEXT = 32

export class VertexBuilder {
  private buf = new ArrayBuffer(VERTEX_BYTES * 4 * 512)
  private view = new DataView(this.buf)
  private u8 = new Uint8Array(this.buf)
  vertices = 0
  /** 位置量化倍率：一般 section 256，LOD（FLAG_FLAT）16。 */
  constructor(private readonly posScale = 256) {}

  private grow() {
    const nb = new ArrayBuffer(this.buf.byteLength * 2)
    new Uint8Array(nb).set(this.u8)
    this.buf = nb; this.view = new DataView(nb); this.u8 = new Uint8Array(nb)
  }

  /** pos 為區段內座標（格），u/v 已量化成 uint16，rgb 為 0..255。 */
  push(x: number, y: number, z: number, u: number, v: number, rect: number, r: number, g: number, b: number, flags: number) {
    if ((this.vertices + 1) * VERTEX_BYTES > this.buf.byteLength) this.grow()
    const o = this.vertices * VERTEX_BYTES, d = this.view
    const k = this.posScale
    d.setInt16(o, Math.round(x * k), true); d.setInt16(o + 2, Math.round(y * k), true); d.setInt16(o + 4, Math.round(z * k), true)
    d.setUint16(o + 6, u, true); d.setUint16(o + 8, v, true); d.setUint16(o + 10, rect, true)
    this.u8[o + 12] = r; this.u8[o + 13] = g; this.u8[o + 14] = b; this.u8[o + 15] = flags
    this.vertices++
  }

  get quads() { return this.vertices >> 2 }

  finish(): ArrayBuffer | null {
    return this.vertices ? this.buf.slice(0, this.vertices * VERTEX_BYTES) : null
  }
}

export const clamp8 = (v: number) => (v < 0 ? 0 : v > 255 ? 255 : Math.round(v))
