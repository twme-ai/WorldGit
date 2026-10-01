package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.worldgit.protocol.DiffPalette;
import org.worldgit.protocol.Protocol;

class HandshakeTrackerTest {
  private final UUID player = UUID.randomUUID();

  private static Protocol.Hello hello(int version, long nonce, List<String> caps) {
    return new Protocol.Hello(version, nonce, caps, DiffPalette.DEFAULT);
  }

  @Test
  void retriesThenSucceedsWithMatchingNonce() {
    var t = new HandshakeTracker(() -> 42L);
    t.join(player, 100);
    var first = t.tick(100);
    assertEquals(List.of(new HandshakeTracker.Send(player, 42L, 0)), first);
    assertTrue(t.tick(110).isEmpty());
    assertEquals(1, t.tick(120).get(0).attempt());
    assertEquals(2, t.tick(160).get(0).attempt());
    assertTrue(t.reply(player, hello(Protocol.VERSION, 42L, Protocol.CAPABILITIES)));
    assertTrue(t.ready(player));
    assertTrue(t.supports(player, "ghost-render"));
    assertTrue(t.tick(500).isEmpty());
  }

  @Test
  void timesOutToNoModAndLateReplyStillWorks() {
    var t = new HandshakeTracker(() -> 7L);
    t.join(player, 0);
    for (int tick = 0; tick <= 130; tick++) t.tick(tick);
    assertEquals(HandshakeTracker.State.NO_MOD, t.state(player));
    assertFalse(t.supports(player, "outline"));
    assertTrue(t.reply(player, hello(Protocol.VERSION, 7L, List.of("outline"))));
    assertEquals(HandshakeTracker.State.READY, t.state(player));
    assertTrue(t.supports(player, "outline"));
    assertFalse(t.supports(player, "ghost-render"));
  }

  @Test
  void rejectsWrongNonceVersionAndMissingCapabilities() {
    var t = new HandshakeTracker(() -> 9L);
    t.join(player, 0);
    assertFalse(t.reply(player, hello(Protocol.VERSION, 8L, Protocol.CAPABILITIES)));
    assertEquals(HandshakeTracker.State.PENDING, t.state(player));
    assertFalse(t.reply(player, hello(1, 9L, Protocol.CAPABILITIES)));
    assertEquals(HandshakeTracker.State.REJECTED, t.state(player));
    var u = UUID.randomUUID();
    t.join(u, 0);
    assertFalse(t.reply(u, hello(Protocol.VERSION, 9L, List.of("ghost-render"))));
    assertEquals(HandshakeTracker.State.REJECTED, t.state(u));
    assertFalse(t.reply(UUID.randomUUID(), hello(Protocol.VERSION, 9L, Protocol.CAPABILITIES)));
    t.leave(player);
    assertEquals(HandshakeTracker.State.NO_MOD, t.state(player));
  }
}
