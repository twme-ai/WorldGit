package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.core.model.*;

class AttributionTest {
  private static final DimensionId NETHER = new DimensionId("minecraft:the_nether");
  private final UUID alice = UUID.randomUUID(), bob = UUID.randomUUID();

  @Test
  void groupsByPlayerAndDimension() {
    var a = new Attribution();
    a.record(DimensionId.OVERWORLD, new ChunkPos(0, 0), alice, "Alice", "place");
    a.record(DimensionId.OVERWORLD, new ChunkPos(1, 0), alice, "Alice", "break");
    a.record(DimensionId.OVERWORLD, new ChunkPos(1, 0), bob, "Bob", "place");
    a.record(NETHER, new ChunkPos(5, 5), bob, "Bob", "place");
    var s = a.capture();
    assertEquals(3, s.chunkCount());
    assertEquals(Set.of(alice, bob), s.players());
    assertTrue(s.touchedBy(alice));
    var over = s.contributions(DimensionId.OVERWORLD, "d.local");
    assertEquals(2, over.size());
    var aliceC = over.stream().filter(c -> c.playerId().equals(alice)).findFirst().orElseThrow();
    assertEquals(Set.of(new ChunkPos(0, 0), new ChunkPos(1, 0)), aliceC.chunks());
    assertEquals("break,place", aliceC.cause());
    assertEquals(alice + "@d.local", aliceC.author().email());
    assertEquals(1, s.contributions(NETHER, "d.local").size());
    assertTrue(s.contributions(new DimensionId("minecraft:the_end"), "d.local").isEmpty());
    assertEquals(Set.of(DimensionId.OVERWORLD, NETHER), s.contributions(List.of(DimensionId.OVERWORLD, NETHER, new DimensionId("minecraft:the_end")), "d.local").keySet());
  }

  @Test
  void acknowledgeKeepsLaterTouches() {
    var a = new Attribution();
    var chunk = new ChunkPos(0, 0);
    a.record(DimensionId.OVERWORLD, chunk, alice, "Alice", "place");
    var snap = a.capture();
    a.record(DimensionId.OVERWORLD, chunk, bob, "Bob", "place");
    a.record(DimensionId.OVERWORLD, new ChunkPos(9, 9), alice, "Alice", "place");
    a.acknowledge(snap);
    assertEquals(2, a.pendingChunks());
    var rest = a.capture();
    assertTrue(rest.touchedBy(bob));
    a.acknowledge(rest);
    assertEquals(0, a.pendingChunks());
  }

  @Test
  void identitiesAreSafeForGit() {
    assertEquals("player", Identities.player("  <>\n", alice, "x.y").name());
    assertEquals("Steve", Identities.player("Steve", alice, "x.y").name());
  }

  @Test
  void autoCommitPolicy() {
    var cfg = ServerConfig.defaults().autoCommit();
    var p = new AutoCommitPolicy(cfg, 0);
    assertFalse(p.intervalDue(9 * 60_000L, 5));
    assertFalse(p.intervalDue(10 * 60_000L, 0));
    assertTrue(p.intervalDue(10 * 60_000L, 1));
    p.ran(10 * 60_000L);
    assertFalse(p.intervalDue(11 * 60_000L, 100));
    assertTrue(p.onLogout(false, true));
    assertFalse(p.onLogout(true, true));
    assertFalse(p.onLogout(false, false));
    assertTrue(p.onStop());
  }
}
