package org.worldgit.core.anvil;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;
import org.worldgit.core.model.DimensionId;

/** Paper 三資料夾、原版 DIM-1/DIM1、自訂維度，以及 26.2 dimensions 目錄轉接。 */
public final class WorldLayout {
  public record Dimension(DimensionId id, Path directory) {
    public Path region() {
      return directory.resolve("region");
    }

    public Path entities() {
      return directory.resolve("entities");
    }

    public Path poi() {
      return directory.resolve("poi");
    }
  }

  private final Path world, server;
  private final SortedMap<DimensionId, Dimension> dimensions;

  private WorldLayout(Path world, Path server, SortedMap<DimensionId, Dimension> dimensions) {
    this.world = world;
    this.server = server;
    this.dimensions = Collections.unmodifiableSortedMap(dimensions);
  }

  public static WorldLayout discover(Path supplied) throws IOException {
    Path p = supplied.toAbsolutePath().normalize();
    Path w = Files.isRegularFile(p.resolve("level.dat")) ? p : p.resolve("world");
    if (!Files.isRegularFile(w.resolve("level.dat"))) throw new IOException("找不到世界 level.dat：" + p);
    Path server = w.getParent();
    var dims = new TreeMap<DimensionId, Dimension>();
    add(dims, DimensionId.OVERWORLD, w);
    add(
        dims,
        new DimensionId("minecraft:the_nether"),
        Files.isDirectory(server.resolve(w.getFileName() + "_nether/DIM-1"))
            ? server.resolve(w.getFileName() + "_nether/DIM-1")
            : w.resolve("DIM-1"));
    add(
        dims,
        new DimensionId("minecraft:the_end"),
        Files.isDirectory(server.resolve(w.getFileName() + "_the_end/DIM1"))
            ? server.resolve(w.getFileName() + "_the_end/DIM1")
            : w.resolve("DIM1"));
    Path dimensions = w.resolve("dimensions");
    if (Files.isDirectory(dimensions))
      try (var stream = Files.walk(dimensions)) {
        for (Path dir :
            stream
                .filter(Files::isDirectory)
                .filter(
                    d ->
                        Files.isDirectory(d.resolve("region"))
                            || Files.isRegularFile(
                                d.resolve("data/minecraft/world_gen_settings.dat")))
                .toList()) {
          Path relative = dimensions.relativize(dir);
          if (relative.getNameCount() < 2) continue;
          String id =
              relative.getName(0)
                  + ":"
                  + relative
                      .subpath(1, relative.getNameCount())
                      .toString()
                      .replace(java.io.File.separatorChar, '/');
          add(dims, new DimensionId(id), dir);
        }
      }
    if (dims.isEmpty()) throw new IOException("世界沒有 region 目錄：" + w);
    return new WorldLayout(w, server, dims);
  }

  private static void add(Map<DimensionId, Dimension> dims, DimensionId id, Path dir) {
    if (Files.isDirectory(dir.resolve("region"))
        || Files.isRegularFile(dir.resolve("data/minecraft/world_gen_settings.dat")))
      dims.put(id, new Dimension(id, dir));
  }

  public Path world() {
    return world;
  }

  public Path server() {
    return server;
  }

  public Path repositoryRoot() {
    if (Files.isDirectory(world.resolve(".worldgit"))) return world.resolve(".worldgit");
    return server.resolve(".worldgit").resolve(world.getFileName().toString());
  }

  public SortedMap<DimensionId, Dimension> dimensions() {
    return dimensions;
  }

  public int dataVersion() throws IOException {
    return readGzip(world.resolve("level.dat")).compound("Data").integer("DataVersion", 0);
  }

  public static Nbt.Compound readGzip(Path path) throws IOException {
    try (var in = new GZIPInputStream(Files.newInputStream(path))) {
      return Nbt.read(in.readNBytes(Nbt.MAX_BYTES + 1));
    }
  }

  /** 世界級 metadata，移除時鐘/天氣等暫態欄位；地圖/記分板原始 NBT 另存。 */
  public Map<String, byte[]> worldMetadata() throws IOException {
    var result = new TreeMap<String, byte[]>();
    var data = readGzip(world.resolve("level.dat")).compound("Data");
    var tracked = new Nbt.Compound();
    for (String k :
        List.of(
            "DataVersion",
            "DataPacks",
            "WorldGenSettings",
            "DragonFight",
            "CustomBossEvents",
            "GameType",
            "allowCommands",
            "SpawnX",
            "SpawnY",
            "SpawnZ",
            "SpawnAngle",
            "spawn",
            "GameRules",
            "game_rules",
            "difficulty_settings",
            "hardcore",
            "Difficulty",
            "DifficultyLocked",
            "BorderCenterX",
            "BorderCenterZ",
            "BorderSize",
            "BorderSafeZone",
            "BorderWarningBlocks",
            "BorderWarningTime",
            "BorderDamagePerBlock",
            "LevelName")) if (data.containsKey(k)) tracked.put(k, Nbt.copy(data.get(k)));
    var end = dimensions.get(new DimensionId("minecraft:the_end"));
    if (dataVersion() < 4903
        && end != null
        && end.directory().getFileName().toString().equals("DIM1")
        && Files.isRegularFile(end.directory().getParent().resolve("level.dat"))) {
      var endData = readGzip(end.directory().getParent().resolve("level.dat")).compound("Data");
      if (endData.containsKey("DragonFight"))
        tracked.put("DragonFight", Nbt.copy(endData.get("DragonFight")));
    }
    result.put("level.nbt", Nbt.write(tracked));
    Path packs = world.resolve("datapacks");
    long assets = 0;
    if (Files.isDirectory(packs))
      try (var files = Files.walk(packs)) {
        for (Path f : files.filter(Files::isRegularFile).sorted().toList()) {
          if (Files.isSymbolicLink(f) || !f.toRealPath().startsWith(packs.toRealPath()))
            throw new IOException("資料包包含符號連結");
          long n = Files.size(f);
          assets += n;
          if (n > Nbt.MAX_BYTES || assets > 64L * 1024 * 1024)
            throw new IOException("資料包超過快照預算（單檔 32 MiB／全部 64 MiB）");
          String relative = world.relativize(f).toString().replace(java.io.File.separatorChar, '/');
          result.put(
              "asset."
                  + Base64.getUrlEncoder()
                      .withoutPadding()
                      .encodeToString(relative.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              Files.readAllBytes(f));
        }
      }
    for (Path dir : List.of(world.resolve("data"), world.resolve("data/minecraft"))) {
      if (!Files.isDirectory(dir)) continue;
      try (var stream = Files.list(dir)) {
        for (Path f : stream.filter(Files::isRegularFile).sorted().toList()) {
          String n = f.getFileName().toString();
          if (n.matches("map_\\d+\\.dat")
              || Set.of(
                      "scoreboard.dat",
                      "game_rules.dat",
                      "world_border.dat",
                      "world_gen_settings.dat",
                      "custom_boss_events.dat")
                  .contains(n)) result.put(n + ".nbt", Nbt.write(readGzip(f)));
        }
      }
    }
    // 26.2 的 gamerule/邊界/生成設定在每個維度自己的 data/minecraft。
    for (var dimension : dimensions.values())
      for (String n : List.of("game_rules.dat", "world_border.dat", "world_gen_settings.dat")) {
        Path file = dimension.directory().resolve("data/minecraft/" + n);
        if (Files.isRegularFile(file))
          result.put(dimension.id().directoryName() + "." + n + ".nbt", Nbt.write(readGzip(file)));
      }
    if (dataVersion() >= 4903)
      for (String name : List.of("game_rules.dat", "world_border.dat", "world_gen_settings.dat")) {
        String dimensionKey = DimensionId.OVERWORLD.directoryName() + "." + name + ".nbt";
        if (result.containsKey(dimensionKey)
            && (data.containsKey("Bukkit.Version") || !result.containsKey(name + ".nbt")))
          result.put(name + ".nbt", result.get(dimensionKey));
      }
    Path dataDir = world.resolve("data");
    if (Files.isDirectory(dataDir))
      try (var files = Files.walk(dataDir)) {
        for (Path f :
            files
                .filter(Files::isRegularFile)
                .filter(p -> p.getFileName().toString().matches("map_\\d+\\.dat"))
                .toList()) result.put(f.getFileName() + ".nbt", Nbt.write(readGzip(f)));
      }
    return result;
  }
}
