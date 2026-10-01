package org.worldgit.core.model;

import java.util.*;

/** 不可變快照，4096 格採 YZX 索引 x | z<<4 | y<<8。 */
public final class Section {
  private final List<BlockState> blocks;
  private final SortedMap<Integer, byte[]> blockEntities;

  public Section(List<BlockState> blocks, Map<Integer, byte[]> blockEntities) {
    if (blocks.size() != 4096) throw new IllegalArgumentException("section 必須有 4096 格");
    this.blocks = List.copyOf(blocks);
    var copy = new TreeMap<Integer, byte[]>();
    blockEntities.forEach(
        (p, b) -> {
          if (p < 0 || p >= 4096) throw new IllegalArgumentException("BE 座標");
          copy.put(p, b.clone());
        });
    this.blockEntities = Collections.unmodifiableSortedMap(copy);
  }

  public static Section air() {
    return new Section(Collections.nCopies(4096, BlockState.AIR), Map.of());
  }

  public BlockState block(int index) {
    return blocks.get(index);
  }

  public List<BlockState> blocks() {
    return blocks;
  }

  public SortedMap<Integer, byte[]> blockEntities() {
    var c = new TreeMap<Integer, byte[]>();
    blockEntities.forEach((k, v) -> c.put(k, v.clone()));
    return c;
  }

  public boolean empty() {
    return blockEntities.isEmpty() && blocks.stream().allMatch(BlockState::air);
  }
}
