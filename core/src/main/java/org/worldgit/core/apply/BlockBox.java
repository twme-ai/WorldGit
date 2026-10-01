package org.worldgit.core.apply;

/** 含端點的方塊座標包圍盒（世界座標）。建構時自動排序最小/最大。 */
public record BlockBox(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
  public static BlockBox of(int x1, int y1, int z1, int x2, int y2, int z2) {
    return new BlockBox(
        Math.min(x1, x2),
        Math.min(y1, y2),
        Math.min(z1, z2),
        Math.max(x1, x2),
        Math.max(y1, y2),
        Math.max(z1, z2));
  }

  public boolean contains(double x, double y, double z) {
    return x >= minX && x < maxX + 1.0 && y >= minY && y < maxY + 1.0 && z >= minZ && z < maxZ + 1.0;
  }

  public boolean containsBlock(int x, int y, int z) {
    return x >= minX && x <= maxX && y >= minY && y <= maxY && z >= minZ && z <= maxZ;
  }

  public boolean intersectsChunk(int cx, int cz) {
    return cx * 16 <= maxX && cx * 16 + 15 >= minX && cz * 16 <= maxZ && cz * 16 + 15 >= minZ;
  }

  public boolean coversChunkColumn(int cx, int cz) {
    return minX <= cx * 16 && maxX >= cx * 16 + 15 && minZ <= cz * 16 && maxZ >= cz * 16 + 15;
  }
}
