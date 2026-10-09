package org.worldgit.core.model;

public record ChunkPos(int x, int z) implements Comparable<ChunkPos> {
  @Override
  public int hashCode() {
    // record 預設 31*x+z 在方形世界形成密集 cluster，讓 Set.copyOf／Map.copyOf 線性探測退化。
    // 混合完整座標；equals、排序與 Git 路徑／bytes 都不變。
    long hash = ((long) x << 32) ^ (z & 0xffffffffL);
    hash ^= hash >>> 33;
    hash *= 0xff51afd7ed558ccdL;
    hash ^= hash >>> 33;
    hash *= 0xc4ceb9fe1a85ec53L;
    hash ^= hash >>> 33;
    return (int) (hash ^ (hash >>> 32));
  }

  public String regionName() {
    return "r." + Math.floorDiv(x, 32) + "." + Math.floorDiv(z, 32);
  }

  public String chunkName() {
    return "c." + x + "." + z;
  }

  public int regionIndex() {
    return (z & 31) * 32 + (x & 31);
  }

  public String treePath() {
    return regionName() + "/" + chunkName();
  }

  @Override
  public int compareTo(ChunkPos other) {
    int c = Integer.compare(x, other.x);
    return c != 0 ? c : Integer.compare(z, other.z);
  }
}
