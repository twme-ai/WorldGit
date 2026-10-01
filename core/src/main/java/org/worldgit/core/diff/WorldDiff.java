package org.worldgit.core.diff;

import java.util.*;
import org.worldgit.core.model.*;

/** 四端共用 diff；方塊變化含 block entity 的原子差異；CONFLICT 預留。 */
public record WorldDiff(
    DimensionId dimension,
    List<SectionChange> sections,
    List<EntityChange> entities,
    List<BiomeChange> biomes,
    List<BlobChange> metadata) {
  public record BlockPos(int x, int y, int z) {}

  public record BlockChange(
      BlockPos pos,
      ChangeKind kind,
      BlockState before,
      BlockState after,
      String blockEntityBefore,
      String blockEntityAfter) {}

  public record Counts(long added, long removed, long modified, long conflict) {
    public static Counts of(Collection<BlockChange> changes) {
      long[] n = new long[4];
      for (var c : changes) n[c.kind.ordinal()]++;
      return new Counts(n[0], n[1], n[2], n[3]);
    }
  }

  public record SectionChange(
      ChunkPos chunk, int sectionY, ChangeKind kind, List<BlockChange> blocks, Counts counts) {
    public SectionChange {
      blocks = List.copyOf(blocks);
    }
  }

  public record EntityChange(
      UUID uuid,
      ChangeKind kind,
      ChunkPos beforeChunk,
      ChunkPos afterChunk,
      EntitySnapshot before,
      EntitySnapshot after) {}

  public record BiomeChange(
      ChunkPos chunk,
      int sectionY,
      int sampleIndex,
      String before,
      String after,
      ChangeKind kind,
      int count) {
    public BiomeChange(
        ChunkPos chunk,
        int sectionY,
        int sampleIndex,
        String before,
        String after,
        ChangeKind kind) {
      this(chunk, sectionY, sampleIndex, before, after, kind, 1);
    }
  }

  public record BlobChange(String path, ChangeKind kind, String beforeId, String afterId) {}

  public WorldDiff {
    sections = List.copyOf(sections);
    entities = List.copyOf(entities);
    biomes = List.copyOf(biomes);
    metadata = List.copyOf(metadata);
  }

  public boolean empty() {
    return sections.isEmpty() && entities.isEmpty() && biomes.isEmpty() && metadata.isEmpty();
  }

  public Counts counts() {
    long a = 0, r = 0, m = 0, c = 0;
    for (var s : sections) {
      a += s.counts.added;
      r += s.counts.removed;
      m += s.counts.modified;
      c += s.counts.conflict;
    }
    return new Counts(a, r, m, c);
  }

  public Set<ChunkPos> chunks() {
    var chunks = new TreeSet<ChunkPos>();
    sections.forEach(s -> chunks.add(s.chunk));
    biomes.forEach(b -> chunks.add(b.chunk));
    entities.forEach(
        e -> {
          if (e.beforeChunk != null) chunks.add(e.beforeChunk);
          if (e.afterChunk != null) chunks.add(e.afterChunk);
        });
    for (var b : metadata) {
      String[] p = b.path.split("/");
      if (p.length >= 3 && p[1].startsWith("c.")) {
        String[] coordinates = p[1].split("\\.");
        chunks.add(
            new ChunkPos(Integer.parseInt(coordinates[1]), Integer.parseInt(coordinates[2])));
      }
    }
    return Collections.unmodifiableSet(chunks);
  }
}
