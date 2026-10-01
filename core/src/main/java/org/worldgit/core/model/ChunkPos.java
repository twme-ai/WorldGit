package org.worldgit.core.model;

public record ChunkPos(int x, int z) implements Comparable<ChunkPos> {
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
