import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.worldgit.core.anvil.*;
import org.worldgit.core.version.MinecraftVersion;

/** Verify current Time/DayTime/weather survives migration without entering history. */
class RuntimeProbe {
  static void write(Path path, Nbt.Compound data) throws Exception {
    Files.createDirectories(path.getParent());
    try (var output = new GZIPOutputStream(Files.newOutputStream(path))) {
      output.write(Nbt.write(data));
    }
  }
  public static void main(String[] args) throws Exception {
    Path world = Path.of(args[0]);
    var root = WorldLayout.readGzip(world.resolve("level.dat"));
    var data = root.compound("Data");
    int version = data.integer("DataVersion", 0);
    boolean modern = version >= MinecraftVersion.NAMESPACED_STORAGE;
    var layout = WorldLayout.discover(world);
    Path raidPath = modern
        ? world.resolve("dimensions/minecraft/overworld/data/minecraft/raids.dat")
        : world.resolve("data/raids.dat");
    var raids = WorldLayout.readGzip(raidPath);
    String tick = version <= 4189 ? "Tick" : "tick";
    String nextId = version <= 4189 ? "NextAvailableID" : "next_id";
    if (args.length > 1 && args[1].equals("seed")) {
      raids.compound("data").put(tick, 77);
      raids.compound("data").put(nextId, 5);
      write(raidPath, raids);
      data.put("Time", 12345L);
      if (!modern) {
        data.put("DayTime", 6789L);
        data.put("rainTime", 3456);
        data.put("raining", (byte) 1);
      } else {
        write(world.resolve("data/minecraft/world_clocks.dat"), new Nbt.Compound()
            .with("DataVersion", version).with("data", new Nbt.Compound()
                .with("minecraft:overworld", new Nbt.Compound().with("total_ticks", 6789L).with("paused", (byte) 0))));
        write(world.resolve("data/minecraft/weather.dat"), new Nbt.Compound()
            .with("DataVersion", version).with("data", new Nbt.Compound()
                .with("rain_time", 3456).with("raining", (byte) 1)
                .with("clear_weather_time", 0).with("thunder_time", 4567).with("thundering", (byte) 0)));
      }
      write(world.resolve("level.dat"), root);
      for (var dimension : layout.dimensions().entrySet()) {
        Path path = dimension.getValue().directory().resolve(modern ? "data/minecraft/raids.dat" : "data/raids.dat");
        if (!Files.isRegularFile(path)) continue;
        var raid = WorldLayout.readGzip(path);
        raid.compound("data").put(version <= 4189 ? "Tick" : "tick", 5678);
        write(path, raid);
      }
    }
    // Read the final seeded file: the dimension loop may have updated the same overworld path.
    raids = WorldLayout.readGzip(raidPath);
    var result = new TreeMap<String,Object>();
    result.put("Time", data.getOrDefault("Time", 0L));
    result.put("raidTick", raids.compound("data").integer(tick, -1));
    result.put("raidNextId", raids.compound("data").integer(nextId, -1));
    if (modern) {
      var clock = WorldLayout.readGzip(world.resolve("data/minecraft/world_clocks.dat"))
          .compound("data").compound("minecraft:overworld");
      var weather = WorldLayout.readGzip(world.resolve("data/minecraft/weather.dat")).compound("data");
      result.put("DayTime", clock.getOrDefault("total_ticks", 0L));
      result.put("rainTime", weather.getOrDefault("rain_time", 0));
      result.put("raining", weather.getOrDefault("raining", (byte) 0));
    } else for (String key : List.of("DayTime", "rainTime", "raining"))
      result.put(key, data.getOrDefault(key, 0));
    for (var dimension : layout.dimensions().entrySet()) {
      Path path = dimension.getValue().directory().resolve(modern ? "data/minecraft/raids.dat" : "data/raids.dat");
      if (Files.isRegularFile(path)) result.put(dimension.getKey().value() + "/raidTick",
          WorldLayout.readGzip(path).compound("data").integer(version <= 4189 ? "Tick" : "tick", -1));
    }
    new ObjectMapper().writeValue(System.out, result);
  }
}
