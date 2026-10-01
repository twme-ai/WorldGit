package org.worldgit.core.model;

import java.util.*;

public record BlockState(String name, SortedMap<String, String> properties) {
  public static final BlockState AIR = new BlockState("minecraft:air", new TreeMap<>());

  public BlockState {
    Objects.requireNonNull(name);
    properties = Collections.unmodifiableSortedMap(new TreeMap<>(properties));
  }

  public BlockState(String name) {
    this(name, new TreeMap<>());
  }

  public boolean air() {
    return Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air").contains(name);
  }

  public boolean fluid() {
    return name.equals("minecraft:water") || name.equals("minecraft:lava");
  }

  public String canonical() {
    if (properties.isEmpty()) return name;
    var j = new StringJoiner(",", name + "[", "]");
    properties.forEach((k, v) -> j.add(k + "=" + v));
    return j.toString();
  }
}
