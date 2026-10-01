package org.worldgit.core.model;

import java.util.*;
import org.worldgit.core.anvil.Nbt;

public final class EntitySnapshot {
  private final UUID uuid;
  private final byte[] nbt;

  public EntitySnapshot(UUID uuid, Nbt.Compound data) {
    this.uuid = Objects.requireNonNull(uuid);
    this.nbt = Nbt.write(data);
  }

  public UUID uuid() {
    return uuid;
  }

  public byte[] bytes() {
    return nbt.clone();
  }

  public Nbt.Compound data() {
    try {
      return Nbt.read(nbt);
    } catch (java.io.IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  public double[] position() {
    var p = data().list("Pos").values();
    if (p.size() != 3) throw new IllegalArgumentException("實體缺少 Pos");
    return p.stream().mapToDouble(v -> ((Number) v).doubleValue()).toArray();
  }

  public ChunkPos chunk() {
    double[] p = position();
    return new ChunkPos((int) Math.floor(p[0] / 16), (int) Math.floor(p[2] / 16));
  }
}
