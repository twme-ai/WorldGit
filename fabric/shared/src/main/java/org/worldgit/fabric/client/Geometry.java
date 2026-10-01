package org.worldgit.fabric.client;

/**
 * 外框與鬼影的幾何（doc 06 §1.1）：新增＝綠實心外框、移除＝紅鬼影（原版模型）、修改＝黃虛線外框＋舊模型淡黃鬼影、
 * 衝突＝紫外框（閃爍由 uniform alpha 控制）。除了「虛線」之外還有不同線型，不只靠顏色辨識。
 */
final class Geometry {
  private Geometry() {}

  /** 一個模型 quad：4 個頂點的 x y z u v（方塊本地座標 0..1）。 */
  record Quad(float[] xyzuv) {}

  private static final float LO = -.008f, HI = 1.008f;
  /** 虛線：每條邊 3 段（0–.22、.39–.61、.78–1）。 */
  private static final float[][] DASHES = {{0f, .22f}, {.39f, .61f}, {.78f, 1f}};

  private static void segment(VertexBuilder b, float ax, float ay, float az, float bx, float by, float bz, int rgb) {
    b.line(ax, ay, az, rgb);
    b.line(bx, by, bz, rgb);
  }

  private static void edge(VertexBuilder b, float ax, float ay, float az, float bx, float by, float bz, int rgb, boolean dashed) {
    if (!dashed) {
      segment(b, ax, ay, az, bx, by, bz, rgb);
      return;
    }
    for (float[] d : DASHES)
      segment(b, ax + (bx - ax) * d[0], ay + (by - ay) * d[0], az + (bz - az) * d[0], ax + (bx - ax) * d[1], ay + (by - ay) * d[1], az + (bz - az) * d[1], rgb);
  }

  /** 單格外框（略大於方塊以避免 z-fighting）。 */
  static void blockOutline(VertexBuilder b, float x, float y, float z, int rgb, boolean dashed) {
    box(b, x + LO, y + LO, z + LO, x + HI, y + HI, z + HI, rgb, dashed);
  }

  /** 任意長方體的 12 條邊；dashed 時每條邊畫成 3 段。 */
  static void box(VertexBuilder b, float x1, float y1, float z1, float x2, float y2, float z2, int rgb, boolean dashed) {
    for (int a = 0; a < 2; a++)
      for (int c = 0; c < 2; c++) {
        float xa = a == 0 ? x1 : x2, ya = a == 0 ? y1 : y2;
        float yc = c == 0 ? y1 : y2, zc = c == 0 ? z1 : z2;
        edge(b, x1, ya, zc, x2, ya, zc, rgb, dashed); // 沿 x
        edge(b, xa, y1, zc, xa, y2, zc, rgb, dashed); // 沿 y
        edge(b, xa, yc, z1, xa, yc, z2, rgb, dashed); // 沿 z
      }
  }

  /** 方塊的舊／已移除模型，略放大 0.3% 蓋在原位。 */
  static void ghost(VertexBuilder b, java.util.List<Quad> quads, float x, float y, float z, int rgb, int alpha) {
    for (Quad q : quads) {
      float[] a = q.xyzuv();
      for (int v = 0; v < 4; v++) {
        int k = v * 5;
        b.textured(
            x + (a[k] - .5f) * 1.003f + .5f,
            y + (a[k + 1] - .5f) * 1.003f + .5f,
            z + (a[k + 2] - .5f) * 1.003f + .5f,
            a[k + 3],
            a[k + 4],
            rgb,
            alpha);
      }
    }
  }
}
