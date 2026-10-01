package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.*;
import org.bukkit.World;
import org.junit.jupiter.api.Test;
import org.worldgit.core.anvil.Nbt;
import org.worldgit.core.model.ChunkPos;

class LiveCopierTest {
  private static final class Scheduler implements ChunkScheduler {
    final Deque<Runnable> tasks = new ArrayDeque<>();
    public void region(World w, int x, int z, Runnable task) { tasks.add(task); }
    public void regionDelayed(World w, int x, int z, long ticks, Runnable task) { tasks.add(task); }
    void runUntilIdle() {
      while (!tasks.isEmpty()) tasks.removeFirst().run();
    }
  }

  private static NmsBridge bridge(boolean fail) {
    return new NmsBridge() {
      public String minecraftVersion() { return "test"; }
      public void census(World world, CensusSink sink) {}
      public Nbt.Compound copyGameRules(World world) { return new Nbt.Compound(); }
      public RawChunk copy(World world, int x, int z) {
        if (fail) throw new IllegalStateException("copy failed");
        return new RawChunk() {
          public int x() { return x; }
          public int z() { return z; }
          public Nbt.Compound terrain() { return new Nbt.Compound(); }
          public List<Nbt.Compound> entities() { return List.of(); }
        };
      }
    };
  }

  @Test
  void fullWindowYieldsAndConsumptionResumesAllChunks() {
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> {
      var scheduler = new Scheduler();
      var stats = new LiveCopier.Stats();
      var copier = new LiveCopier(scheduler, bridge(false), null, 8, 16, false, stats);
      var chunks = new ArrayList<ChunkPos>();
      for (int i = 0; i < 1000; i++) chunks.add(new ChunkPos(i, 0));
      copier.start(chunks);
      scheduler.runUntilIdle();
      assertEquals(16, stats.chunks.sum());
      for (var pos : chunks) {
        assertTrue(copier.has(pos));
        var raw = copier.take(pos, 1).orElseThrow();
        assertEquals(pos.x(), raw.x());
        scheduler.runUntilIdle();
        assertFalse(copier.has(pos));
      }
      assertEquals(1000, stats.chunks.sum());
      assertEquals(0, stats.failures.sum());
    });
  }

  @Test
  void failuresAndCancellationReleaseTheConsumer() throws Exception {
    var scheduler = new Scheduler();
    var stats = new LiveCopier.Stats();
    var copier = new LiveCopier(scheduler, bridge(true), null, 1, 16, false, stats);
    var pos = new ChunkPos(1, 1);
    copier.start(List.of(pos));
    scheduler.runUntilIdle();
    var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> copier.take(pos, 1));
    assertEquals("copy failed", error.getCause().getMessage());
    assertEquals(1, stats.failures.sum());
    copier.start(List.of(pos));
    copier.cancel();
    scheduler.runUntilIdle();
    assertFalse(copier.has(pos));
    assertNull(copier.take(pos, 1));
  }

  @Test
  void shutdownCopyRunsInlineWithoutScheduling() throws Exception {
    var scheduler = new Scheduler();
    var stats = new LiveCopier.Stats();
    var copier = new LiveCopier(scheduler, bridge(false), null, 8, 16, true, stats);
    var pos = new ChunkPos(-1, -2);
    copier.start(List.of(pos));
    assertTrue(scheduler.tasks.isEmpty());
    assertEquals(-2, copier.take(pos, 1).orElseThrow().z());
    assertEquals(1, stats.chunks.sum());
  }
}
