package org.worldgit.core.anvil;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.WorldLayout;

/** 檢查真正的 OS lock，不以 session.lock 存在與否判斷；操作期間持有鎖避免伺服器啟動。 */
public final class WorldSessionLock implements AutoCloseable {
  private final List<FileChannel> channels = new ArrayList<>();
  private final List<FileLock> locks = new ArrayList<>();

  private WorldSessionLock() {}
  private Path world;
  private boolean closed;

  public void requireActive(WorldLayout layout) throws IOException {
    if (closed || !layout.world().equals(world) || locks.stream().anyMatch(l -> !l.isValid()))
      throw new IOException("沒有持有此世界 session.lock");
  }

  public static WorldSessionLock acquire(WorldLayout layout) throws IOException {
    var paths = new TreeSet<Path>();
    paths.add(layout.world().resolve("session.lock"));
    for (var dim : layout.dimensions().values()) {
      Path dir = dim.directory();
      String n = dir.getFileName().toString();
      if (n.equals("DIM-1") || n.equals("DIM1")) paths.add(dir.getParent().resolve("session.lock"));
    }
    var lock = acquire(paths);
    lock.world = layout.world();
    return lock;
  }

  public static WorldSessionLock acquire(Collection<Path> paths) throws IOException {
    var guard = new WorldSessionLock();
    try {
      for (Path path : paths) {
        Files.createDirectories(path.toAbsolutePath().getParent());
        FileChannel channel =
            FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        guard.channels.add(channel);
        FileLock lock;
        try {
          lock = channel.tryLock();
        } catch (OverlappingFileLockException e) {
          throw new IOException("警告：世界正由伺服器使用（session.lock）：" + path, e);
        }
        if (lock == null) throw new IOException("警告：世界正由伺服器使用（session.lock）：" + path);
        guard.locks.add(lock);
      }
      return guard;
    } catch (IOException | RuntimeException e) {
      guard.close();
      throw e;
    }
  }

  @Override
  public void close() throws IOException {
    closed = true;
    IOException error = null;
    for (FileLock l : locks)
      try {
        l.release();
      } catch (IOException e) {
        error = e;
      }
    for (FileChannel c : channels)
      try {
        c.close();
      } catch (IOException e) {
        error = e;
      }
    if (error != null) throw error;
  }
}
