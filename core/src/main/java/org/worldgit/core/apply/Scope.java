package org.worldgit.core.apply;

import java.util.*;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.model.ChunkPos;

/**
 * 套用範圍：全部、chunk 集合（含 chunk 半徑）或方塊包圍盒。方塊盒的邊界落在 section 中間時做方塊級裁切，範圍外保持現狀。
 * 範圍不限制 y 以外的資訊：chunk 範圍涵蓋整根柱子。
 */
public final class Scope {
  public enum Kind {
    ALL,
    CHUNKS,
    BOX
  }

  /** 包圍盒要視為「涵蓋整個 chunk」所需的 y 範圍（主世界高度）；ticks/structures 只在整個 chunk 被涵蓋時才套用。 */
  public static final int FULL_MIN_Y = -64, FULL_MAX_Y = 319;

  private static final Scope ALL = new Scope(Kind.ALL, Set.of(), null);
  private final Kind kind;
  private final Set<ChunkPos> chunks;
  private final BlockBox box;

  private Scope(Kind kind, Set<ChunkPos> chunks, BlockBox box) {
    this.kind = kind;
    this.chunks = Collections.unmodifiableSet(new TreeSet<>(chunks));
    this.box = box;
  }

  public static Scope all() {
    return ALL;
  }

  /** 以 (cx,cz) 為中心、半徑 r（含）的正方形 chunk 範圍。 */
  public static Scope chunkRadius(int cx, int cz, int radius) {
    if (radius < 0 || radius > 256) throw new IllegalArgumentException("chunk 半徑必須介於 0–256");
    if (Math.abs((long)cx) + radius > 1875000 || Math.abs((long)cz) + radius > 1875000)
      throw new IllegalArgumentException("chunk 座標超出世界邊界");
    var set = new TreeSet<ChunkPos>();
    for (int x = cx - radius; x <= cx + radius; x++)
      for (int z = cz - radius; z <= cz + radius; z++) set.add(new ChunkPos(x, z));
    return new Scope(Kind.CHUNKS, set, null);
  }

  public static Scope chunkSet(Collection<ChunkPos> chunks) {
    return new Scope(Kind.CHUNKS, new TreeSet<>(chunks), null);
  }

  public static Scope box(int x1, int y1, int z1, int x2, int y2, int z2) {
    return new Scope(Kind.BOX, Set.of(), BlockBox.of(x1, y1, z1, x2, y2, z2));
  }

  public Kind kind() {
    return kind;
  }

  public Set<ChunkPos> chunks() {
    return chunks;
  }

  public BlockBox box() {
    return box;
  }

  public boolean touchesChunk(ChunkPos c) {
    return switch (kind) {
      case ALL -> true;
      case CHUNKS -> chunks.contains(c);
      case BOX -> box.intersectsChunk(c.x(), c.z());
    };
  }

  /** 整個 chunk 都在範圍內（才套用 ticks、structures 這類 chunk 級資料）。 */
  public boolean coversChunk(ChunkPos c) {
    return switch (kind) {
      case ALL -> true;
      case CHUNKS -> chunks.contains(c);
      case BOX ->
          box.coversChunkColumn(c.x(), c.z()) && box.minY() <= FULL_MIN_Y && box.maxY() >= FULL_MAX_Y;
    };
  }

  public boolean containsBlock(int x, int y, int z) {
    return switch (kind) {
      case ALL -> true;
      case CHUNKS -> chunks.contains(new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16)));
      case BOX -> box.containsBlock(x, y, z);
    };
  }

  /** 實體位置是否在範圍內（chunk 範圍用位置所在 chunk 判斷）。 */
  public boolean containsPoint(double x, double y, double z) {
    return switch (kind) {
      case ALL -> true;
      case CHUNKS ->
          chunks.contains(new ChunkPos((int) Math.floor(x / 16), (int) Math.floor(z / 16)));
      case BOX -> box.contains(x, y, z);
    };
  }

  /** 此 section 是否需要逐格裁切（範圍只涵蓋部分方塊）。 */
  public boolean needsMask(ChunkPos c, int sectionY) {
    if (kind != Kind.BOX) return false;
    return !(box.coversChunkColumn(c.x(), c.z())
        && box.minY() <= sectionY * 16
        && box.maxY() >= sectionY * 16 + 15);
  }

  public boolean touchesSection(ChunkPos c, int sectionY) {
    return switch (kind) {
      case ALL -> true;
      case CHUNKS -> chunks.contains(c);
      case BOX -> box.intersectsChunk(c.x(), c.z()) && box.minY() <= sectionY * 16 + 15 && box.maxY() >= sectionY * 16;
    };
  }

  public Nbt.Compound toNbt() {
    var c = new Nbt.Compound().with("kind", kind.name());
    if (kind == Kind.CHUNKS) {
      int[] a = new int[chunks.size() * 2];
      int i = 0;
      for (var p : chunks) {
        a[i++] = p.x();
        a[i++] = p.z();
      }
      c.put("chunks", a);
    }
    if (kind == Kind.BOX)
      c.put("box", new int[] {box.minX(), box.minY(), box.minZ(), box.maxX(), box.maxY(), box.maxZ()});
    return c;
  }

  public static Scope fromNbt(Nbt.Compound c) {
    Kind kind = Kind.valueOf(c.string("kind"));
    return switch (kind) {
      case ALL -> ALL;
      case CHUNKS -> {
        int[] a = (int[]) c.get("chunks");
        var set = new TreeSet<ChunkPos>();
        for (int i = 0; i + 1 < a.length; i += 2) set.add(new ChunkPos(a[i], a[i + 1]));
        yield chunkSet(set);
      }
      case BOX -> {
        int[] b = (int[]) c.get("box");
        yield box(b[0], b[1], b[2], b[3], b[4], b[5]);
      }
    };
  }

  @Override
  public String toString() {
    return switch (kind) {
      case ALL -> "all";
      case CHUNKS -> chunks.size() + " chunks";
      case BOX -> box.toString();
    };
  }
}
