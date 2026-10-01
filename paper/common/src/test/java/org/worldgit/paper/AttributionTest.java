package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.model.*;

class AttributionTest {
  @Test
  void drainAndRestorePreserveNewEditsAndDimensions() {
    var attribution = new Attribution();
    UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    var oldChunk = new ChunkPos(-1, 2);
    var newChunk = new ChunkPos(3, 4);
    attribution.record(DimensionId.OVERWORLD, alice, "Alice", oldChunk, "block-place");
    var nether = new DimensionId("minecraft:the_nether");
    attribution.record(nether, bob, "Bob", oldChunk, "worldedit");
    var drained = attribution.drain();
    assertTrue(attribution.peek().empty());
    attribution.record(DimensionId.OVERWORLD, alice, "Alice", oldChunk, "block-break");
    attribution.record(DimensionId.OVERWORLD, alice, "Alice", newChunk, "worldedit");
    attribution.restore(drained);
    var merged = attribution.drain();
    var contributor = merged.of(DimensionId.OVERWORLD).getFirst();
    assertEquals(Set.of("block-place", "block-break"), contributor.chunks().get(oldChunk));
    assertEquals(Set.of(oldChunk, newChunk), contributor.toContribution().chunks());
    assertEquals("block-break+block-place+worldedit", contributor.cause());
    assertEquals(bob, merged.of(nether).getFirst().player());
    assertEquals(Set.of("block-place"), drained.of(DimensionId.OVERWORLD).getFirst().chunks().get(oldChunk));
    assertThrows(UnsupportedOperationException.class, () -> contributor.chunks().clear());
  }

  @Test
  void primaryAuthorIsDeterministicAndCountsDistinctChunks() {
    var attribution = new Attribution();
    UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();
    for (int i = 0; i < 3; i++) {
      attribution.record(DimensionId.OVERWORLD, alice, "Alice", new ChunkPos(i, 0), "block-place");
      attribution.record(DimensionId.OVERWORLD, bob, "Bob", new ChunkPos(i, 1), "worldedit");
    }
    attribution.record(DimensionId.OVERWORLD, bob, "Bob", new ChunkPos(0, 1), "worldedit");
    assertEquals("Alice", attribution.peek().primary(DimensionId.OVERWORLD).orElseThrow().name());
    attribution.record(DimensionId.OVERWORLD, bob, "Bob", new ChunkPos(4, 1), "worldedit");
    assertEquals("Bob", attribution.peek().primary(DimensionId.OVERWORLD).orElseThrow().name());
    assertTrue(attribution.peek().primary(new DimensionId("minecraft:the_end")).isEmpty());
  }

  @Test
  void simultaneousRegionsAndDrainDoNotLoseContributions() throws Exception {
    var attribution = new Attribution();
    UUID player = UUID.randomUUID();
    try (var pool = Executors.newFixedThreadPool(3)) {
      var jobs = new ArrayList<Future<?>>();
      for (int region = 0; region < 3; region++) {
        final int x = region;
        jobs.add(pool.submit(() -> {
          for (int z = 0; z < 200; z++) attribution.record(DimensionId.OVERWORLD, player, "Builder", new ChunkPos(x, z), "block-place");
        }));
      }
      var chunks = new HashSet<ChunkPos>();
      while (jobs.stream().anyMatch(job -> !job.isDone())) {
        attribution.drain().of(DimensionId.OVERWORLD).forEach(c -> chunks.addAll(c.chunks().keySet()));
        attribution.peek();
      }
      for (var job : jobs) job.get();
      attribution.drain().of(DimensionId.OVERWORLD).forEach(c -> chunks.addAll(c.chunks().keySet()));
      assertEquals(600, chunks.size());
    }
  }
}
