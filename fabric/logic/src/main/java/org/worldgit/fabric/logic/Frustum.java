package org.worldgit.fabric.logic;

/**
 * 視錐（相機相對座標）。由「旋轉後的 view 矩陣」與自己建的透視投影相乘取六個平面（Gribb–Hartmann）。
 * 只用來略過整個區塊，所以投影用比實際 FOV 稍寬的邊界，寧可多畫不可少畫。
 */
public final class Frustum {
  private final float[][] planes = new float[6][4];

  private Frustum() {}

  /** m 為 column-major 的 clip 矩陣（projection × view 的旋轉部分）。 */
  public static Frustum of(float[] m) {
    var f = new Frustum();
    for (int i = 0; i < 6; i++) {
      int row = i / 2;
      float sign = (i % 2 == 0) ? 1 : -1;
      for (int j = 0; j < 4; j++) f.planes[i][j] = m[j * 4 + 3] + sign * m[j * 4 + row];
      float len = (float) Math.sqrt(f.planes[i][0] * f.planes[i][0] + f.planes[i][1] * f.planes[i][1] + f.planes[i][2] * f.planes[i][2]);
      if (len > 0) for (int j = 0; j < 4; j++) f.planes[i][j] /= len;
    }
    return f;
  }

  /** 以視角旋轉矩陣（column-major float[16]，OpenGL 慣例）與 FOV/長寬比建立。 */
  public static Frustum perspective(float[] viewRotation, double fovYDegrees, double aspect, double near, double far) {
    double f = 1.0 / Math.tan(Math.toRadians(fovYDegrees) / 2);
    float[] p = new float[16];
    p[0] = (float) (f / aspect);
    p[5] = (float) f;
    p[10] = (float) ((far + near) / (near - far));
    p[11] = -1;
    p[14] = (float) (2 * far * near / (near - far));
    return of(multiply(p, viewRotation));
  }

  static float[] multiply(float[] a, float[] b) {
    float[] r = new float[16];
    for (int c = 0; c < 4; c++)
      for (int row = 0; row < 4; row++) {
        float s = 0;
        for (int k = 0; k < 4; k++) s += a[k * 4 + row] * b[c * 4 + k];
        r[c * 4 + row] = s;
      }
    return r;
  }

  /** 相機相對座標下的 AABB 是否可能可見。 */
  public boolean intersects(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
    for (float[] p : planes) {
      double x = p[0] >= 0 ? maxX : minX, y = p[1] >= 0 ? maxY : minY, z = p[2] >= 0 ? maxZ : minZ;
      if (p[0] * x + p[1] * y + p[2] * z + p[3] < 0) return false;
    }
    return true;
  }
}
