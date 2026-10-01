package org.worldgit.core;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.*;

public final class TestWorlds {
  private TestWorlds() {}

  public static Path fixture(String version) throws Exception {
    return Path.of(TestWorlds.class.getResource("/fixtures/" + version).toURI());
  }

  public static void copy(Path source, Path target) throws IOException {
    try (var paths = Files.walk(source)) {
      for (Path p : paths.toList()) {
        Path dest = target.resolve(source.relativize(p));
        if (Files.isDirectory(p)) Files.createDirectories(dest);
        else Files.copy(p, dest, StandardCopyOption.REPLACE_EXISTING);
      }
    }
  }

  public static void oneBlock(WorldLayout layout, DimensionId id, ChunkPos pos, int y, int index)
      throws IOException {
    Path path = layout.dimensions().get(id).region().resolve(pos.regionName() + ".mca");
    Nbt.Compound raw;
    try (var r = new RegionFile(path)) {
      raw = r.read(pos.regionIndex());
    }
    for (Object o : raw.list("sections").values()) {
      var section = (Nbt.Compound) o;
      if (section.integer("Y", 0) != y) continue;
      var bs = section.compound("block_states");
      var palette = new ArrayList<>(bs.list("palette").values());
      int[] indices = ChunkNormalizer.unpack(bs, palette.size(), 4, 4096);
      String old = ((Nbt.Compound) palette.get(indices[index])).string("Name");
      palette.add(
          new Nbt.Compound()
              .with(
                  "Name",
                  old.equals("minecraft:gold_block")
                      ? "minecraft:diamond_block"
                      : "minecraft:gold_block"));
      indices[index] = palette.size() - 1;
      bs.put("palette", new Nbt.ListTag(10, palette));
      bs.put(
          "data",
          ChunkNormalizer.pack(indices, Math.max(4, ChunkNormalizer.ceilLog2(palette.size()))));
      RegionFile.update(
          path, Map.of(pos.regionIndex(), raw), (int) (System.currentTimeMillis() / 1000));
      return;
    }
    throw new IOException("找不到測試 section " + y);
  }

  public static Nbt.Compound entity(UUID uuid, double x, boolean noAi) {
    return new Nbt.Compound()
        .with("id", "minecraft:zombie")
        .with(
            "UUID",
            new int[] {
              (int) (uuid.getMostSignificantBits() >>> 32),
              (int) uuid.getMostSignificantBits(),
              (int) (uuid.getLeastSignificantBits() >>> 32),
              (int) uuid.getLeastSignificantBits()
            })
        .with("Pos", new Nbt.ListTag(6, List.of(x, 70.0, 0.0)))
        .with("NoAI", (byte) (noAi ? 1 : 0));
  }
}
