package org.worldgit.hub;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.*;
import java.util.*;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.*;
import org.worldgit.hub.account.*;
import org.worldgit.hub.config.*;
import org.worldgit.hub.storage.*;
import org.worldgit.hub.data.DataService;

class SecurityBoundaryTest {
  @TempDir Path dir;

  HubProperties props(List<String> proxies) {
    return new HubProperties(dir, null, null, new HubProperties.Git(null, null, 100L), null,
        new HubProperties.Security(false, proxies), null, new HubProperties.Auth(3, 2, 60L, 120L, 30), null, null);
  }

  static class MutableClock extends Clock {
    long now = 1_000_000;
    @Override public ZoneId getZone() { return ZoneOffset.UTC; }
    @Override public Clock withZone(ZoneId zone) { return this; }
    @Override public Instant instant() { return Instant.ofEpochMilli(now); }
  }

  @Test void accountAndIpLockExpiresAndRejectsBeforeCrypto() {
    var clock = new MutableClock();
    var throttle = new AuthThrottle(props(List.of()), clock);
    var req = new MockHttpServletRequest(); req.setRemoteAddr("192.0.2.1");
    assertTrue(throttle.authenticate("alice", req, false, Optional::empty).isEmpty());
    assertTrue(throttle.authenticate("alice", req, false, Optional::empty).isEmpty());
    var blocked = assertThrows(AuthThrottle.Limited.class,
        () -> throttle.authenticate("alice", req, false, () -> { fail("locked before credential check"); return Optional.empty(); }));
    assertEquals(120, blocked.retryAfter());
    req.setRemoteAddr("192.0.2.2");
    assertThrows(AuthThrottle.Limited.class, () -> throttle.authenticate("alice", req, false, Optional::empty));
    clock.now += 121_000;
    assertEquals("ok", throttle.authenticate("alice", req, false, () -> Optional.of("ok")).orElseThrow());
  }

  @Test void rateLimitCountsSuccessfulBasicAndDoesNotThrottleValidBearerAssets() {
    var throttle = new AuthThrottle(props(List.of()));
    var req = new MockHttpServletRequest(); req.setRemoteAddr("192.0.2.1");
    for (int i = 0; i < 3; i++) throttle.authenticate("alice", req, false, () -> Optional.of("ok"));
    assertThrows(AuthThrottle.Limited.class, () -> throttle.authenticate("bob", req, false, Optional::empty));
    req.setRemoteAddr("192.0.2.2");
    for (int i = 0; i < 100; i++) assertTrue(throttle.authenticate("bearer", req, true, () -> Optional.of("ok")).isPresent());
  }

  @Test void forwardedIpOnlyUsesTrustedProxyAndRightmostUntrustedHop() {
    var req = new MockHttpServletRequest(); req.setRemoteAddr("192.0.2.1");
    req.addHeader("X-Forwarded-For", "198.51.100.7, 203.0.113.8");
    assertEquals("192.0.2.1", new AuthThrottle(props(List.of())).sourceIp(req));
    assertEquals("203.0.113.8", new AuthThrottle(props(List.of("192.0.2.1"))).sourceIp(req));
    assertEquals("198.51.100.7", new AuthThrottle(props(List.of("192.0.2.1", "203.0.113.8"))).sourceIp(req));
  }

  @Test void quotaIncludesAllWorldsAndCleansOnlyNewPackFiles() throws Exception {
    var quota = new OwnerQuota(props(List.of()));
    Path pack = dir.resolve("repos/alice/one/minecraft.overworld.git/objects/pack");
    Files.createDirectories(pack);
    Files.write(pack.resolve("old.pack"), new byte[30]);
    var before = quota.packFiles(pack.getParent().getParent());
    Files.write(pack.resolve("new.pack"), new byte[71]);
    assertEquals(101, quota.used("alice"));
    assertThrows(java.io.IOException.class, () -> quota.check("alice"));
    quota.discardNewPacks(pack.getParent().getParent(), before);
    assertEquals(30, quota.used("alice"));
    Path another = dir.resolve("repos/alice/two/nether.git/file");
    Files.createDirectories(another.getParent()); Files.write(another, new byte[71]);
    assertThrows(java.io.IOException.class, () -> quota.check("alice"));
    assertEquals(0, quota.used("bob"));
  }

  @Test void windowCannotOverflowOrLoopAtMaxInt() {
    assertThrows(IllegalArgumentException.class, () -> new DataService.Window(Integer.MIN_VALUE, 0, Integer.MAX_VALUE, 0));
    assertDoesNotThrow(() -> new DataService.Window(Integer.MAX_VALUE, 0, Integer.MAX_VALUE, 0));
  }
}
