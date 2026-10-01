package org.worldgit.platform;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import org.worldgit.core.anvil.*;

/** 相容 Phase 1；實際 OS lock 現在由 core 共用。 */
public final class SessionGuard implements AutoCloseable {
  private final WorldSessionLock lock;
  private SessionGuard(WorldSessionLock lock) { this.lock = lock; }
  public static SessionGuard acquire(WorldLayout layout) throws IOException {
    return new SessionGuard(WorldSessionLock.acquire(layout));
  }
  public static SessionGuard acquire(Collection<Path> paths) throws IOException {
    return new SessionGuard(WorldSessionLock.acquire(paths));
  }
  public WorldSessionLock coreLock() { return lock; }
  @Override public void close() throws IOException { lock.close(); }
}
