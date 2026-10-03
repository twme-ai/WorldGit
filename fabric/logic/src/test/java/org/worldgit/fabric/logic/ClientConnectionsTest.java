package org.worldgit.fabric.logic;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class ClientConnectionsTest {
  @Test void networkThreadDisconnectIsConsumedOnceOnClientTick() {
    var state = new ClientConnections<Object>();
    var handler = new Object();
    state.join(handler);
    assertFalse(state.shouldReset(handler));
    CompletableFuture.runAsync(() -> state.disconnected(handler)).join();
    assertTrue(state.shouldReset(handler));
    assertFalse(state.shouldReset(null));
  }

  @Test void lateOldDisconnectDoesNotClearReconnectedSession() {
    var state = new ClientConnections<Object>();
    var old = new Object();
    var next = new Object();
    state.join(old);
    state.disconnected(old);
    state.join(next);
    assertFalse(state.shouldReset(next));
    state.disconnected(old);
    assertFalse(state.shouldReset(next));
    state.disconnected(next);
    assertTrue(state.shouldReset(null));
  }

  @Test void worldDepartureResetsEvenBeforeNetworkCallback() {
    var state = new ClientConnections<Object>();
    var handler = new Object();
    assertFalse(state.shouldReset(null));
    state.join(handler);
    assertTrue(state.shouldReset(null));
    state.disconnected(handler);
    assertFalse(state.shouldReset(null));
    state.join(new Object());
    assertTrue(state.shouldReset(new Object()));
  }
}
