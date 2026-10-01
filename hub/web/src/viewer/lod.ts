// 遠景 LOD（doc 10 §5.1）：用 Hub 預先計算的 tile（4×4 高度圖 + 俯視色）直接畫成階梯狀地形，
// 近景已載入完整細節的 chunk 會從 LOD 挖掉。不做近景網格。
import { FLAG_FLAT, VertexBuilder } from './vertex.ts'

export const CELL = 4
export const CELLS = 128 // 一個 region 512 格 / 4
export const HEIGHT_BIAS = 2049

/** 解析 .height（big-endian uint16，128×128，0 = 無資料；值 = 最高方塊 y + 2049）。 */
export function parseHeights(buf: ArrayBuffer): Int16Array {
  const v = new DataView(buf)
  const out = new Int16Array(CELLS * CELLS)
  for (let i = 0; i < out.length; i++) { const h = v.getUint16(i * 2); out[i] = h === 0 ? -32768 : h - HEIGHT_BIAS }
  return out
}

/** 每個 cell 取 tile 圖 4×4 像素的平均色（RGBA 長度 512*512*4）。 */
export function cellColors(rgba: Uint8ClampedArray | Uint8Array): Uint8Array {
  const out = new Uint8Array(CELLS * CELLS * 3)
  for (let cz = 0; cz < CELLS; cz++) for (let cx = 0; cx < CELLS; cx++) {
    let r = 0, g = 0, b = 0, n = 0
    for (let dz = 0; dz < CELL; dz++) for (let dx = 0; dx < CELL; dx++) {
      const i = ((cz * CELL + dz) * 512 + cx * CELL + dx) * 4
      if (rgba[i + 3] === 0) continue
      r += rgba[i]; g += rgba[i + 1]; b += rgba[i + 2]; n++
    }
    const o = (cz * CELLS + cx) * 3
    if (n) { out[o] = r / n; out[o + 1] = g / n; out[o + 2] = b / n }
  }
  return out
}

/**
 * 產生一個 region 的 LOD 網格。位置為 region 區域座標（x、z 0..512，y 為世界座標），量化 1/16 格。
 * skip(chunkX, chunkZ) 為 true 的 chunk（已有完整細節）不輸出。
 */
export function buildLod(rx: number, rz: number, heights: Int16Array, colors: Uint8Array, skip: (cx: number, cz: number) => boolean): ArrayBuffer | null {
  const vb = new VertexBuilder(16)
  const shade = [1.0, 0.9, 0.9, 0.8, 0.8] // 頂 / 北 / 南 / 東 / 西
  const H = (i: number, j: number) => (i < 0 || j < 0 || i >= CELLS || j >= CELLS ? -32768 : heights[j * CELLS + i])
  const quad = (p: number[][], rgb: number[], s: number) => {
    for (const q of p) vb.push(q[0], q[1], q[2], 0, 0, 0xffff, rgb[0] * s, rgb[1] * s, rgb[2] * s, FLAG_FLAT)
  }
  for (let j = 0; j < CELLS; j++) for (let i = 0; i < CELLS; i++) {
    const h = heights[j * CELLS + i]
    if (h === -32768) continue
    const chunkX = rx * 32 + ((i * CELL) >> 4), chunkZ = rz * 32 + ((j * CELL) >> 4)
    if (skip(chunkX, chunkZ)) continue
    const o = (j * CELLS + i) * 3
    const rgb = [colors[o], colors[o + 1], colors[o + 2]]
    const x0 = i * CELL, x1 = x0 + CELL, z0 = j * CELL, z1 = z0 + CELL, y = h + 1
    quad([[x0, y, z0], [x0, y, z1], [x1, y, z1], [x1, y, z0]], rgb, shade[0])
    const sides: [number, number, number][] = [[0, -1, 1], [0, 1, 2], [1, 0, 3], [-1, 0, 4]]
    for (const [di, dj, s] of sides) {
      const nh = H(i + di, j + dj)
      const nSkip = nh === -32768 ? false : skip(rx * 32 + (((i + di) * CELL) >> 4), rz * 32 + (((j + dj) * CELL) >> 4))
      if (nh === -32768 && (i + di < 0 || j + dj < 0 || i + di >= CELLS || j + dj >= CELLS)) continue
      const yb = nSkip ? y - 8 : nh === -32768 ? y - 4 : nh + 1
      if (yb >= y) continue
      if (s === 3) quad([[x1, yb, z0], [x1, y, z0], [x1, y, z1], [x1, yb, z1]], rgb, shade[3])
      else if (s === 4) quad([[x0, yb, z1], [x0, y, z1], [x0, y, z0], [x0, yb, z0]], rgb, shade[4])
      else if (s === 2) quad([[x1, yb, z1], [x1, y, z1], [x0, y, z1], [x0, yb, z1]], rgb, shade[2])
      else quad([[x0, yb, z0], [x0, y, z0], [x1, y, z0], [x1, yb, z0]], rgb, shade[1])
    }
  }
  return vb.finish()
}
