package org.worldgit.core.merge;

import java.util.*;
import org.worldgit.core.apply.BlockBox;
import org.worldgit.core.model.*;

/** CLI／Hub／平台共用；region 的 cells 是精確寫入集合，bounds 只供顯示。 */
public record MergeReport(
    int automaticallyMergedSections,
    List<Region> regions,
    List<RuleDifference> ruleDifferences,
    List<Cell> updateShapes,
    List<String> warnings) {
  public enum Choice {
    OURS,
    THEIRS,
    BASE,
    MANUAL
  }

  public record PreviewBlock(Cell position, BlockState state, byte[] blockEntity) {
    public PreviewBlock {
      blockEntity = blockEntity == null ? null : blockEntity.clone();
    }

    @Override
    public byte[] blockEntity() {
      return blockEntity == null ? null : blockEntity.clone();
    }
  }

  public enum Kind {
    BLOCK,
    BIOME,
    ENTITY,
    TICKS,
    STRUCTURES,
    METADATA,
    FILE
  }

  public record Cell(int x, int y, int z) implements Comparable<Cell> {
    public Cell offset(int x, int y, int z) {
      return new Cell(this.x + x, this.y + y, this.z + z);
    }

    public ChunkPos chunk() {
      return new ChunkPos(Math.floorDiv(x, 16), Math.floorDiv(z, 16));
    }

    public int sectionY() {
      return Math.floorDiv(y, 16);
    }

    public int index() {
      return (x & 15) | ((z & 15) << 4) | ((y & 15) << 8);
    }

    @Override
    public int compareTo(Cell b) {
      int c = Integer.compare(x, b.x);
      if (c == 0) c = Integer.compare(y, b.y);
      return c == 0 ? Integer.compare(z, b.z) : c;
    }
  }

  /** key 是 metadata 的 NBT key path（不是字串拆分），或 biome 的 section/sample。 */
  public record Atom(Kind kind, Cell position, String path, List<String> key, UUID uuid) {
    public Atom {
      key = List.copyOf(key);
    }
  }

  public record Region(
      int id,
      DimensionId dimension,
      BlockBox bounds,
      int blockCount,
      List<String> oursAuthors,
      List<String> theirsAuthors,
      boolean redstone,
      Choice choice,
      boolean resolved,
      List<Atom> atoms) {
    public Region {
      oursAuthors = List.copyOf(oursAuthors);
      theirsAuthors = List.copyOf(theirsAuthors);
      atoms = List.copyOf(atoms);
    }

    public Region selected(Choice choice, boolean resolved) {
      return new Region(
          id,
          dimension,
          bounds,
          blockCount,
          oursAuthors,
          theirsAuthors,
          redstone,
          choice,
          resolved,
          atoms);
    }
  }

  public record RuleDifference(
      DimensionId dimension, String base, String ours, String theirs, String merged) {}

  public MergeReport {
    regions = List.copyOf(regions);
    ruleDifferences = List.copyOf(ruleDifferences);
    updateShapes = List.copyOf(updateShapes);
    warnings = List.copyOf(warnings);
  }
}
