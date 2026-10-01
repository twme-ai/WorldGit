package org.worldgit.core;

import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.ChunkNormalizer;

/** 開發時擷取真實 baseline；產物提交後 CI 不需要 .work。 */
public final class FixtureExtractor {
  public static void main(String[] args) throws Exception {
    Path root = Path.of(args[0]);
    for (String version : List.of("1.21.11", "26.2")) {
      WorldLayout layout =
          WorldLayout.discover(root.resolve(".work/worlds/" + version + "/baseline"));
      Path target = root.resolve("core/src/test/resources/fixtures/" + version);
      if (Files.exists(target))
        try (var files = Files.walk(target)) {
          for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
        }
      Files.createDirectories(target.resolve("world"));
      Files.copy(
          layout.world().resolve("level.dat"),
          target.resolve("world/level.dat"),
          StandardCopyOption.REPLACE_EXISTING);
      Path datapacks = layout.world().resolve("datapacks");
      if (Files.isDirectory(datapacks))
        TestWorlds.copy(datapacks, target.resolve("world/datapacks"));
      for (var d : layout.dimensions().values()) {
        Path base = target.resolve(layout.server().relativize(d.directory()));
        int count = 0;
        if (d.id().equals(DimensionId.OVERWORLD))
          for (var pos : List.of(new ChunkPos(0, 0), new ChunkPos(1, 1))) {
            Path file = d.region().resolve(pos.regionName() + ".mca");
            try (var r = new RegionFile(file)) {
              var raw = r.read(pos.regionIndex());
              RegionFile.update(
                  base.resolve("region/" + pos.regionName() + ".mca"),
                  Map.of(pos.regionIndex(), raw),
                  r.timestamp(pos.regionIndex()));
            }
            Path ep = d.entities().resolve(pos.regionName() + ".mca");
            if (Files.exists(ep))
              try (var r = new RegionFile(ep)) {
                if (r.has(pos.regionIndex()))
                  RegionFile.update(
                      base.resolve("entities/" + pos.regionName() + ".mca"),
                      Map.of(pos.regionIndex(), r.read(pos.regionIndex())),
                      r.timestamp(pos.regionIndex()));
              }
            count++;
          }
        for (String name :
            List.of("game_rules.dat", "world_border.dat", "world_gen_settings.dat")) {
          Path source = d.directory().resolve("data/minecraft/" + name);
          if (Files.exists(source)) {
            Path dest = base.resolve("data/minecraft/" + name);
            Files.createDirectories(dest.getParent());
            Files.copy(source, dest, StandardCopyOption.REPLACE_EXISTING);
          }
        }
        for (Path file : RegionFile.list(d.region())) {
          try (var region = new RegionFile(file)) {
            for (int i = 0; i < 1024 && count < 3; i++)
              if (region.has(i)) {
                var raw = region.read(i);
                if (!ChunkNormalizer.full(raw)) continue;
                var pos = region.pos(i);
                RegionFile.update(
                    base.resolve("region/" + pos.regionName() + ".mca"),
                    Map.of(i, raw),
                    region.timestamp(i));
                Path entities = d.entities().resolve(pos.regionName() + ".mca");
                if (Files.exists(entities))
                  try (var er = new RegionFile(entities)) {
                    if (er.has(i))
                      RegionFile.update(
                          base.resolve("entities/" + pos.regionName() + ".mca"),
                          Map.of(i, er.read(i)),
                          er.timestamp(i));
                  }
                System.out.println(version + " " + d.id() + " " + pos);
                count++;
              }
          }
          if (count >= 3) break;
        }
      }
    }
    try (var files = Files.walk(root.resolve("core/src/test/resources/fixtures"))) {
      long bytes = 0;
      for (Path path : files.filter(Files::isRegularFile).toList()) bytes += Files.size(path);
      if (bytes >= 2_000_000) throw new java.io.IOException("fixture 超過 2 MB，請減少擷取內容：" + bytes);
    }
  }
}
