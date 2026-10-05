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
  private DimensionId current = DimensionId.OVERWORLD;
  private int standaloneVersion;
  private final SortedMap<DimensionId, Dimension> dimensions;

  private WorldLayout(Path world, Path server, SortedMap<DimensionId, Dimension> dimensions) {
    this.world = world;
    this.server = server;
    this.dimensions = Collections.unmodifiableSortedMap(dimensions);
  }

  public static WorldLayout discover(Path supplied) throws IOException {
    Path p = supplied.toAbsolutePath().normalize();
    Path w = Files.isRegularFile(p.resolve("level.dat")) ? p : p.resolve("world");
    if (!Files.isRegularFile(w.resolve("level.dat"))) {
      for (Path ancestor = p; ancestor != null; ancestor = ancestor.getParent()) {
        if (Files.isRegularFile(ancestor.resolve("level.dat"))) { w = ancestor; break; }
      }
    }
    // Paper 的分離維度資料夾可能另有 level.dat；世界級資料仍由主世界提供。
    for (Path candidate : List.of(p, p.getParent() == null ? p : p.getParent())) {
      String name = candidate.getFileName() == null ? "" : candidate.getFileName().toString();
      String suffix = name.endsWith("_nether") ? "_nether" : name.endsWith("_the_end") ? "_the_end" : null;
      if (suffix != null) {
        Path main = candidate.resolveSibling(name.substring(0, name.length() - suffix.length()));
        if (Files.isRegularFile(main.resolve("level.dat"))) { w = main; break; }
      }
    }
    if (!Files.isRegularFile(w.resolve("level.dat"))) {
      // 單獨壓縮的非主世界維度可從自己的已初始化 repo 得知 id／版本，不需要主世界資料。
      Path own = p.resolve(".worldgit");
      if (Files.isRegularFile(own.resolve("HEAD"))) try (var repo = new org.worldgit.core.store.JGitStore(own,false)) {
        String head = repo.head();
        if (head != null) {
          var metadata=repo.readCommit(head).metadata();
          if (!metadata.dimension().equals(DimensionId.OVERWORLD)) {
            var only=new TreeMap<DimensionId,Dimension>();add(only,metadata.dimension(),p);
            if (!only.isEmpty()) {
              var standalone=new WorldLayout(p,p.getParent(),only);standalone.current=metadata.dimension();standalone.standaloneVersion=metadata.mcDataVersion();return standalone;
            }
          }
        }
      }
      throw new IOException("找不到世界 level.dat，且沒有可辨識的獨立維度 repo："+p);
    }
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
                .filter(d -> !excluded(d))
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
    var layout = new WorldLayout(w, server, dims);
    int depth = -1;
    for (var dim : dims.values()) {
      if (p.startsWith(dim.directory()) && dim.directory().getNameCount() > depth) {
        layout.current = dim.id(); depth = dim.directory().getNameCount();
      }
      if (!dim.id().equals(DimensionId.OVERWORLD) && p.equals(dim.directory().getParent())
          && !p.equals(w) && Set.of("DIM-1", "DIM1").contains(dim.directory().getFileName().toString())) layout.current = dim.id();
    }
    return layout;
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

  public Path repositoryRoot() { return world.resolve(".worldgit"); }

  public DimensionId currentDimension() { return current; }

  public Path repository(DimensionId id) {
    var dimension = dimensions.get(id);
    if (dimension == null) throw new IllegalArgumentException("找不到維度：" + id);
    return (id.equals(DimensionId.OVERWORLD) ? world : dimension.directory()).resolve(".worldgit");
  }

  public List<Path> legacyRepositories(DimensionId id) {
    return List.of(world.resolve(".worldgit").resolve(id.directoryName()),
        world.resolve(".worldgit-legacy").resolve(id.directoryName()),
        server.resolve(".worldgit").resolve(world.getFileName().toString()).resolve(id.directoryName()));
  }

  public static boolean excluded(Path path) {
    for (Path part : path) if (part.toString().equals(".worldgit")) return true;
    return false;
  }

  public SortedMap<DimensionId, Dimension> dimensions() {
    return dimensions;
  }

  public int dataVersion() throws IOException {
    if (standaloneVersion != 0) return standaloneVersion;
    return readGzip(world.resolve("level.dat")).compound("Data").integer("DataVersion", 0);
  }

  public static Nbt.Compound readGzip(Path path) throws IOException {
    try (var in = new GZIPInputStream(Files.newInputStream(path))) {
      return Nbt.read(in.readNBytes(Nbt.MAX_BYTES + 1));
    }
  }

  public Map<String, byte[]> dimensionMetadata(DimensionId id) throws IOException {
    var result = new TreeMap<String, byte[]>();
    var dimension = dimensions.get(id);
    if (dimension == null) throw new IOException("找不到維度：" + id);
    for (String name : List.of("game_rules.dat", "world_border.dat", "world_gen_settings.dat")) {
      Path file = dimension.directory().resolve("data/minecraft/" + name);
      if (Files.isRegularFile(file)) result.put(id.directoryName() + "." + name + ".nbt", Nbt.write(readGzip(file)));
    }
    if (dataVersion() >= 4903) result.putAll(SavedData.capture(dimension.directory(), id.directoryName() + "."));
    return result;
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
    // 未進入過終界的存檔會省略 Gateways；遊戲第一次開啟會依種子填入相同的預設順序。
    // 將缺省值具體化，避免 clone 首次開服產生假的差異；已消耗的 gateway 清單完整保留。
    if (dataVersion() < 4903 && tracked.containsKey("DragonFight")
        && !tracked.compound("DragonFight").containsKey("Gateways")
        && tracked.compound("WorldGenSettings").get("seed") instanceof Number seed) {
      var gateways = new ArrayList<Object>(); for (int i=0; i<20; i++) gateways.add(i);
      Collections.shuffle(gateways, new Random(seed.longValue()));
      tracked.compound("DragonFight").put("Gateways", new Nbt.ListTag(3, gateways));
    }
    result.put("level.nbt", Nbt.write(tracked));
    Path packs = world.resolve("datapacks");
    long assets = 0;
    if (Files.isDirectory(packs))
      try (var files = Files.walk(packs)) {
        for (Path f : files.filter(Files::isRegularFile).filter(f -> !excluded(f)).sorted().toList()) {
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
      if (dimension.id().equals(DimensionId.OVERWORLD)) for (String n : List.of("game_rules.dat", "world_border.dat", "world_gen_settings.dat")) {
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
        // 新的 vanilla/Fabric 26.2 存檔只在世界根目錄保存 shared data。
        // clone 契約使用主世界名稱；補缺少的別名，不改寫既有歷史或既有維度資料。
        if (!result.containsKey(dimensionKey) && result.containsKey(name + ".nbt"))
          result.put(dimensionKey, result.get(name + ".nbt"));
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
    if (dataVersion() >= 4903) {
      result.putAll(SavedData.capture(world, ""));
      var main = dimensions.get(DimensionId.OVERWORLD);
      if (!main.directory().equals(world)) result.putAll(SavedData.capture(main.directory(), DimensionId.OVERWORLD.directoryName() + "."));
    }
    return result;
  }
}
