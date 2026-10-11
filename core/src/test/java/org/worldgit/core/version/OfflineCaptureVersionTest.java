package org.worldgit.core.version;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.worldgit.core.anvil.*;
import org.worldgit.core.capture.ScanIndex;
import org.worldgit.core.config.IgnoreRules;
import org.worldgit.core.model.*;

class OfflineCaptureVersionTest {
  @TempDir Path world;

  private void level(int version) throws IOException {
    try (var out = new GZIPOutputStream(Files.newOutputStream(world.resolve("level.dat")))) {
      out.write(Nbt.write(new Nbt.Compound().with("Data",
          new Nbt.Compound().with("DataVersion", version))));
    }
  }

  @Test void onlineDiskCaptureUsesServerDfuAndVersionBeforeLevelDatIsResaved() throws Exception {
    Files.createDirectories(world.resolve("region"));
    level(3465);
    RegionFile.update(world.resolve("region/r.0.0.mca"), Map.of(0,
        new Nbt.Compound().with("DataVersion", 3465).with("Status", "minecraft:full")), 1);
    var layout = WorldLayout.discover(world);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    NbtUpgrader fixer = (type, raw, from, to) -> {
      assertEquals("CHUNK", type);
      assertEquals(3465, from);
      assertEquals(3700, to);
      calls.incrementAndGet();
      return raw;
    };
    try (var source = new OfflineSnapshotSource(layout, layout.dimensions().get(DimensionId.OVERWORLD))
        .serverDataFixer(fixer, () -> 3700)) {
      source.scan(ScanIndex.empty(), true);
      assertEquals(3700, source.dataVersion());
      assertSame(fixer, source.dataFixer());
      var snapshot = source.snapshot(new ChunkPos(0, 0), IgnoreRules.none()).toCompletableFuture().join();
      assertEquals(3700, snapshot.orElseThrow().dataVersion());
      assertEquals(1, calls.get());
      assertEquals(3465, layout.dataVersion());
    }
  }

  @Test
  void untouchedSavedDataUsesItsOwnFileVersionAndPreservesContent() throws Exception {
    Files.createDirectories(world.resolve("region"));
    Files.createDirectories(world.resolve("data"));
    level(3700);
    try (var out = new GZIPOutputStream(Files.newOutputStream(world.resolve("data/chunks.dat")))) {
      out.write(Nbt.write(new Nbt.Compound().with("DataVersion", 3465)
          .with("data", new Nbt.Compound().with("Forced", new long[] {42L}))));
    }
    var layout = WorldLayout.discover(world);
    var calls = new java.util.concurrent.atomic.AtomicInteger();
    try (var source = new OfflineSnapshotSource(layout, layout.dimensions().get(DimensionId.OVERWORLD))
        .serverDataFixer((type, raw, from, to) -> {
          assertEquals("SAVED_DATA_TICKETS", type);
          assertEquals(3465, from); assertEquals(3700, to);
          calls.incrementAndGet(); return Nbt.copy(raw);
        }, () -> 3700)) {
      var metadata = source.worldMetadata();
      String key = metadata.keySet().stream().filter(name -> "data/chunks.dat".equals(SavedData.relativePath(name))).findFirst().orElseThrow();
      var captured = Nbt.read(metadata.get(key));
      assertEquals(3700, captured.integer("DataVersion", -1));
      assertArrayEquals(new long[] {42L}, (long[]) captured.compound("data").get("Forced"));
      assertEquals(1, calls.get());
      assertEquals(3465, WorldLayout.readGzip(world.resolve("data/chunks.dat")).integer("DataVersion", -1));
    }
  }

  @Test
  void captureUsesOneTargetVersionAndRescanningRefreshesIt() throws Exception {
    Files.createDirectories(world.resolve("region"));
    level(4671);
    var pos = new ChunkPos(0, 0);
    RegionFile.update(world.resolve("region/r.0.0.mca"), Map.of(0,
        new Nbt.Compound().with("DataVersion", 4671).with("Status", "minecraft:full")), 1);
    var layout = WorldLayout.discover(world);
    try (var source = new OfflineSnapshotSource(layout,
        layout.dimensions().get(DimensionId.OVERWORLD))) {
      source.scan(ScanIndex.empty(), true);
      assertTrue(source.snapshot(pos, IgnoreRules.none()).toCompletableFuture().join().isPresent());
      // Do not mix target schemas between chunks in one capture, or retain the target
      // when a later scan starts after the world's level.dat has changed.
      level(3700);
      assertTrue(source.snapshot(pos, IgnoreRules.none()).toCompletableFuture().join().isPresent());
      source.scan(ScanIndex.empty(), true);
      var error = assertThrows(CompletionException.class,
          () -> source.snapshot(pos, IgnoreRules.none()).toCompletableFuture().join());
      assertInstanceOf(IOException.class, error.getCause());
      assertTrue(error.getCause().getMessage().contains("比 level.dat 新"));
    }
  }
}
