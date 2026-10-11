package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.anvil.SavedData;
import org.worldgit.core.anvil.WorldLayout;
import org.worldgit.core.apply.*;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.model.*;
import org.worldgit.core.normalize.MetadataNormalizer;

class MetadataConversionTest {
  @TempDir Path temp;
  private static String saved(String path) {
    return "saved." + Base64.getUrlEncoder().withoutPadding()
        .encodeToString(path.getBytes(StandardCharsets.UTF_8)) + ".nbt";
  }

  @Test
  void legacyDimensionDataMovesToItsModernOwnerAndKeepsPhysicalRaidTick() throws Exception {
    String old = saved("data/raids.dat"), modern =
        DimensionId.OVERWORLD.directoryName() + "." + saved("data/minecraft/raids.dat");
    var files = Map.of("level.nbt", Nbt.write(new Nbt.Compound().with("DataVersion", 4671)),
        old, Nbt.write(new Nbt.Compound().with("DataVersion", 4671)
            .with("data", new Nbt.Compound().with("next_id", 1).with("tick", 5678))));
    NbtUpgrader dfu = (type, input, from, to) -> Nbt.copy(input);
    var loss = new ConversionReport.Collector();
    var reverse = new ReverseNbt(new ReverseRules(4671, 5023, null), loss, 4671, 5023);
    var physical = MetadataConversion.convert(files, DimensionId.OVERWORLD, 4671, 5023,
        dfu, reverse, loss, true);
    assertTrue(physical.containsKey(modern));
    assertFalse(physical.containsKey(saved("data/minecraft/raids.dat")));
    assertEquals(5678, Nbt.read(physical.get(modern)).compound("data").integer("tick", -1));
    var history = MetadataConversion.convert(files, DimensionId.OVERWORLD, 4671, 5023,
        dfu, reverse, loss);
    assertFalse(Nbt.read(history.get(modern)).compound("data").containsKey("tick"));
  }

  @Test
  void historicalClockAndWeatherNeverBecomeLegacyTrackedFieldsOrLosses() throws Exception {
    var files = new TreeMap<String, byte[]>();
    files.put("level.nbt", Nbt.write(new Nbt.Compound().with("DataVersion", 5023)));
    files.put(saved("data/minecraft/world_clocks.dat"), Nbt.write(new Nbt.Compound()
        .with("data", new Nbt.Compound().with("minecraft:overworld",
            new Nbt.Compound().with("total_ticks", 6789L)))));
    files.put(saved("data/minecraft/weather.dat"), Nbt.write(new Nbt.Compound()
        .with("data", new Nbt.Compound().with("rain_time", 123).with("raining", (byte) 1))));
    files.put(saved("data/minecraft/wandering_trader.dat"), Nbt.write(new Nbt.Compound()
        .with("data", new Nbt.Compound().with("spawn_delay", 12000))));
    var loss = new ConversionReport.Collector();
    var reverse = new ReverseNbt(new ReverseRules(5023, 3465, null), loss, 5023, 3465);
    var history = MetadataConversion.convert(files, DimensionId.OVERWORLD, 5023, 3465,
        null, reverse, loss);
    assertEquals(Set.of("level.nbt"), history.keySet());
    var tracked = Nbt.read(history.get("level.nbt"));
    assertFalse(tracked.containsKey("DayTime"));
    assertFalse(tracked.containsKey("rainTime"));
    assertFalse(tracked.containsKey("WanderingTraderSpawnDelay"));
    assertFalse(loss.report(5023, 3465, "test").lossy());
    var physical = MetadataConversion.convert(files, DimensionId.OVERWORLD, 5023, 3465,
        null, reverse, loss, true);
    var current = Nbt.read(physical.get("level.nbt"));
    assertEquals(6789L, current.get("DayTime"));
    assertEquals(123, current.get("rainTime"));
    assertEquals(12000, current.get("WanderingTraderSpawnDelay"));
  }

  @Test
  void dfuEmittedRuntimeFilesStayOutsideUpgradedHistory() throws Exception {
    NbtUpgrader dfu = (type, input, from, to) -> Nbt.copy(input)
        .with("world_clocks", new Nbt.Compound().with("minecraft:overworld",
            new Nbt.Compound().with("total_ticks", 100L)))
        .with("weather_data", new Nbt.Compound().with("rain_time", 42))
        .with("wandering_trader_migration_data", new Nbt.Compound().with("spawn_delay", 10));
    var files = Map.of("level.nbt", Nbt.write(new Nbt.Compound().with("DataVersion", 3465)
        .with("Time", 500L).with("DayTime", 100L).with("raining", (byte) 1)));
    var loss = new ConversionReport.Collector();
    var reverse = new ReverseNbt(new ReverseRules(3465, 5023, null), loss, 3465, 5023);
    var history = MetadataConversion.convert(files, DimensionId.OVERWORLD, 3465, 5023,
        dfu, reverse, loss);
    assertEquals(Set.of("level.nbt"), history.keySet());
    assertFalse(Nbt.read(history.get("level.nbt")).containsKey("Time"));
    var physical = MetadataConversion.convert(files, DimensionId.OVERWORLD, 3465, 5023,
        dfu, reverse, loss, true);
    assertEquals(100L, Nbt.read(physical.get(saved("data/minecraft/world_clocks.dat")))
        .compound("data").compound("minecraft:overworld").get("total_ticks"));
    assertEquals(500L, Nbt.read(physical.get("level.nbt")).get("Time"));
  }

  @Test
  void legacyAndModernRandomSequencesShareTransientPolicy() throws Exception {
    for (String path : List.of("data/random_sequences.dat", "data/minecraft/random_sequences.dat")) {
      var root = new Nbt.Compound().with("DataVersion", path.contains("minecraft") ? 5023 : 4189)
          .with("data", new Nbt.Compound().with("salt", 7)
              .with("sequences", new Nbt.Compound().with("minecraft:foo", new long[] {1L, 2L})));
      var normalized = Nbt.read(SavedData.normalize(saved(path), Nbt.write(root))).compound("data");
      assertEquals(7, normalized.integer("salt", 0));
      assertFalse(normalized.containsKey("sequences"));
    }
  }

  @Test
  void downgradedOverworldSavedDataAndScheduledFunctionsApplyAndRecaptureExactly() throws Exception {
    var events = new Nbt.ListTag(10, List.of(new Nbt.Compound().with("Name", "demo:task")
        .with("TriggerTime", 42L).with("Callback", new Nbt.Compound()
            .with("Type", "function").with("Name", "demo:task"))));
    var files = Map.of(
        "level.nbt", Nbt.write(new Nbt.Compound().with("DataVersion", 5023)),
        "minecraft.overworld." + saved("data/minecraft/raids.dat"),
        Nbt.write(new Nbt.Compound().with("DataVersion", 5023).with("data",
            new Nbt.Compound().with("next_id", 5).with("raids", new Nbt.ListTag(10, List.of())))),
        "minecraft.overworld." + saved("data/minecraft/chunk_tickets.dat"),
        Nbt.write(new Nbt.Compound().with("DataVersion", 5023).with("data", new Nbt.Compound()
            .with("tickets", new Nbt.ListTag(10, List.of(new Nbt.Compound()
                .with("type", "minecraft:forced").with("chunk_pos", new int[] {1, 2})))))),
        saved("data/minecraft/scheduled_events.dat"),
        Nbt.write(new Nbt.Compound().with("DataVersion", 5023)
            .with("data", new Nbt.Compound().with("events", events))));
    var loss = new ConversionReport.Collector();
    var target = MetadataConversion.convert(files, DimensionId.OVERWORLD, 5023, 3465, null,
        new ReverseNbt(new ReverseRules(5023, 3465, null), loss, 5023, 3465), loss);
    assertEquals(Set.of("level.nbt", saved("data/raids.dat"), saved("data/chunks.dat")), target.keySet());
    Path world = temp.resolve("world");
    Files.createDirectories(world.resolve("region"));
    try (var out = new GZIPOutputStream(Files.newOutputStream(world.resolve("level.dat")))) {
      out.write(Nbt.write(new Nbt.Compound().with("Data", new Nbt.Compound().with("DataVersion", 3465))));
    }
    var layout = WorldLayout.discover(world);
    new OfflineApplier(layout).apply(new ApplyPlan(DimensionId.OVERWORLD, null, null, 3465,
        Scope.all(), List.of(), List.of(), target, List.of()));
    var actual = MetadataNormalizer.normalize(layout.worldMetadata(), IgnoreRules.none());
    assertEquals(target.keySet(), actual.keySet());
    for (String key : target.keySet()) assertArrayEquals(target.get(key), actual.get(key), key);
    assertEquals(42L, ((Nbt.Compound) WorldLayout.readGzip(world.resolve("level.dat"))
        .compound("Data").list("ScheduledEvents").values().getFirst()).get("TriggerTime"));
    assertArrayEquals(new long[] {(2L << 32) | 1},
        (long[]) WorldLayout.readGzip(world.resolve("data/chunks.dat")).compound("data").get("Forced"));
    assertFalse(loss.report(5023, 3465, "test").lossy());
  }

  @Test
  void downgradeRetainsNonOverworldSavedDataOwnership() throws Exception {
    var dimension = new DimensionId("minecraft:the_nether");
    String prefix = dimension.directoryName() + ".";
    var loss = new ConversionReport.Collector();
    var target = MetadataConversion.convert(Map.of(prefix + saved("data/minecraft/raids.dat"),
        Nbt.write(new Nbt.Compound().with("DataVersion", 5023)
            .with("data", new Nbt.Compound().with("next_id", 4)))), dimension, 5023, 3465, null,
        new ReverseNbt(new ReverseRules(5023, 3465, null), loss, 5023, 3465), loss);
    assertEquals(Set.of(prefix + saved("data/raids.dat")), target.keySet());
    assertEquals(4, Nbt.read(target.values().iterator().next()).compound("data")
        .integer("NextAvailableID", 0));
  }
}
