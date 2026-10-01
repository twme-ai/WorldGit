package org.worldgit.core.model;

import java.util.*;

/** 正規化後的 chunk；blob arrays 在建構及讀取時複製。 */
public record ChunkSnapshot(
    ChunkPos pos,
    int dataVersion,
    SortedMap<Integer, Section> sections,
    SortedMap<Integer, List<String>> biomes,
    List<EntitySnapshot> entities,
    byte[] ticks,
    byte[] structures) {
  public ChunkSnapshot {
    sections = Collections.unmodifiableSortedMap(new TreeMap<>(sections));
    var b = new TreeMap<Integer, List<String>>();
    biomes.forEach((y, v) -> b.put(y, List.copyOf(v)));
    biomes = Collections.unmodifiableSortedMap(b);
    entities = List.copyOf(entities);
    ticks = ticks == null ? null : ticks.clone();
    structures = structures == null ? null : structures.clone();
  }

  @Override
  public byte[] ticks() {
    return ticks == null ? null : ticks.clone();
  }

  @Override
  public byte[] structures() {
    return structures == null ? null : structures.clone();
  }
}
