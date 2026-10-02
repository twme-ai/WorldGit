package org.worldgit.paper;

import static org.junit.jupiter.api.Assertions.*;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.*;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.*;
import org.junit.jupiter.api.Test;

class PlatformTest {
  @SuppressWarnings("unchecked")
  private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
    return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
  }

  @Test void disabledAndShutdownNotificationsRetireWithoutScheduling() {
    var enabled = new AtomicBoolean(false);
    var retired = new AtomicInteger();
    var plugin = proxy(Plugin.class, (p, m, a) -> enabled.get());
    var entity = proxy(Entity.class, (p, m, a) -> { fail("disabled notification must not request the scheduler"); return null; });
    var platform = new Platform(plugin);
    platform.entity(entity, () -> fail("notification ran"), retired::incrementAndGet);
    enabled.set(true);
    platform.shuttingDown();
    platform.entity(entity, () -> fail("notification ran"), retired::incrementAndGet);
    assertEquals(2, retired.get());
  }

  @Test void disableBetweenCheckAndRegistrationRetiresButOtherRejectionsPropagate() {
    var enabled = new AtomicBoolean(true);
    var disableDuringRegistration = new AtomicBoolean(true);
    var retired = new AtomicInteger();
    var plugin = proxy(Plugin.class, (p, m, a) -> enabled.get());
    var scheduler = proxy(EntityScheduler.class, (p, m, a) -> {
      if (disableDuringRegistration.get()) enabled.set(false);
      throw new IllegalPluginAccessException("scheduler rejected registration");
    });
    var entity = proxy(Entity.class, (p, m, a) -> scheduler);
    var platform = new Platform(plugin);
    platform.entity(entity, () -> fail("notification ran"), retired::incrementAndGet);
    assertEquals(1, retired.get());
    enabled.set(true);
    disableDuringRegistration.set(false);
    assertThrows(IllegalPluginAccessException.class,
        () -> platform.entity(entity, () -> {}, retired::incrementAndGet));
    assertEquals(1, retired.get());
  }
}
