import java.nio.file.*;
import java.io.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.worldgit.core.anvil.*;
import org.worldgit.core.model.ChunkPos;

/** 離線產生沒有實體/流體/隨機刻的合成 commit 負載；只寫 baseline 的副本。 */
class ScaleFixture {
  public static void main(String[] args) throws Exception {
    String version = args[0];
    Path original = Path.of(args[1]), target = Path.of(args[2]);
    int side = Integer.parseInt(args[3]);
    if (Files.exists(target)) throw new IOException("fixture already exists: " + target);
    try (var stream = Files.walk(original)) {
      for (Path from : stream.toList()) {
        Path to = target.resolve(original.relativize(from));
        if (Files.isDirectory(from)) Files.createDirectories(to);
        else Files.copy(from, to);
      }
    }
    // Vanilla fixtures may have saved data but no generated Nether/End chunks.
    // Make their empty storage visible before the server's first save.
    if (!version.startsWith("26.")) for (String dimension : List.of("DIM-1", "DIM1"))
      if (Files.isDirectory(target.resolve("world/" + dimension)))
        Files.createDirectories(target.resolve("world/" + dimension + "/region"));
    var layout = WorldLayout.discover(target.resolve("world"));
    var overworld = layout.dimensions().get(org.worldgit.core.model.DimensionId.OVERWORLD);
    for (String directory : List.of("region", "entities", "poi")) {
      Path folder = overworld.directory().resolve(directory);
      if (Files.exists(folder)) try (var files = Files.walk(folder)) {
        for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(file);
      }
      Files.createDirectories(folder);
    }
    Path levelPath = target.resolve("world/level.dat");
    var level = WorldLayout.readGzip(levelPath);
    var data = level.compound("Data");
    if (layout.dataVersion() >= 4671) data.put("spawn", new Nbt.Compound().with("pos", new int[] {8, 64, 8}).with("dimension", "minecraft:overworld").with("yaw", 0f).with("pitch", 0f));
    if (data.containsKey("SpawnX")) { data.put("SpawnX", 8); data.put("SpawnY", 64); data.put("SpawnZ", 8); }
    if (data.containsKey("game_rules")) quiet(data.compound("game_rules"));
    if (data.containsKey("GameRules")) {
      var rules = data.compound("GameRules");
      rules.put("randomTickSpeed", "0");
      for (String name : List.of("doMobSpawning", "doWeatherCycle", "doDaylightCycle")) rules.put(name, "false");
    }
    gzip(levelPath, level);
    if (layout.dataVersion() >= org.worldgit.core.version.MinecraftVersion.NAMESPACED_STORAGE) {
      for (var dimension : layout.dimensions().values()) {
        Path path = dimension.directory().resolve("data/minecraft/game_rules.dat");
        if (Files.exists(path)) {
          var rules = WorldLayout.readGzip(path);
          quiet(rules.compound("data"));
          gzip(path, rules);
        }
      }
    }
    var regions = new TreeMap<String, Map<Integer, Nbt.Compound>>();
    int dataVersion = layout.dataVersion();
    var sections = new ArrayList<Nbt.Compound>();
    for (int y = -4; y < 20; y++) sections.add(new Nbt.Compound().with("Y", (byte) y)
        .with("block_states", new Nbt.Compound().with("palette", new Nbt.ListTag(10, List.<Object>of(org.worldgit.core.version.BlockStateNbt.write(new org.worldgit.core.model.BlockState(y < 4 ? "minecraft:stone" : "minecraft:air"), layout.dataVersion())))))
        .with("biomes", new Nbt.Compound().with("palette", new Nbt.ListTag(8, List.<Object>of("minecraft:plains")))));
    // RegionFile.update 只讀取 NBT；共享不變的 section 模板降低 10000 chunk fixture 的記憶體量。
    var sectionTag = new Nbt.ListTag(10, new ArrayList<Object>(sections));
    int halo = 10, min = -side / 2 - halo, max = min + side + 2 * halo;
    for (int x = min; x < max; x++) for (int z = min; z < max; z++) {
      var pos = new ChunkPos(x, z);
      var chunk = new Nbt.Compound().with("DataVersion", dataVersion).with("xPos", x).with("zPos", z).with("yPos", -4)
          .with("Status", "minecraft:full").with("sections", sectionTag)
          .with("block_entities", new Nbt.ListTag(10, List.<Object>of())).with("block_ticks", new Nbt.ListTag(10, List.<Object>of())).with("fluid_ticks", new Nbt.ListTag(10, List.<Object>of()))
          .with("structures", new Nbt.Compound().with("starts", new Nbt.Compound()).with("References", new Nbt.Compound()))
          .with("isLightOn", (byte) 0).with("LastUpdate", 0L).with("InhabitedTime", 0L);
      regions.computeIfAbsent(pos.regionName(), k -> new TreeMap<>()).put(pos.regionIndex(), chunk);
    }
    for (var entry : regions.entrySet()) RegionFile.update(overworld.region().resolve(entry.getKey() + ".mca"), entry.getValue(), (int) (System.currentTimeMillis() / 1000));
    System.out.println("synthetic full chunks=" + (side + 2 * halo) * (side + 2 * halo) + " targetSide=" + side + " path=" + target);
  }

  private static void quiet(Nbt.Compound rules) {
    rules.put("minecraft:random_tick_speed", 0);
    for (String name : List.of("spawn_mobs", "advance_weather", "advance_time")) rules.put("minecraft:" + name, (byte) 0);
  }

  private static void gzip(Path path, Nbt.Compound compound) throws IOException {
    try (var out = new GZIPOutputStream(Files.newOutputStream(path))) { out.write(Nbt.write(compound)); }
  }
}
