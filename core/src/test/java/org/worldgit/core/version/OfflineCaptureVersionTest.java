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
