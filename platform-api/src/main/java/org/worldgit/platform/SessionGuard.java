package org.worldgit.platform;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import org.worldgit.core.anvil.WorldLayout;

/** 檢查真正的 OS lock，不以 session.lock 存在與否判斷；操作期間持有鎖避免伺服器啟動。 */
public final class SessionGuard implements AutoCloseable {
  private final List<FileChannel> channels = new ArrayList<>();
  private final List<FileLock> locks = new ArrayList<>();

  private SessionGuard() {}

  public static SessionGuard acquire(WorldLayout layout) throws IOException {
    var paths = new TreeSet<Path>();
    paths.add(layout.world().resolve("session.lock"));
    for (var dim : layout.dimensions().values()) {
      Path dir = dim.directory();
      String n = dir.getFileName().toString();
      if (n.equals("DIM-1") || n.equals("DIM1")) paths.add(dir.getParent().resolve("session.lock"));
    }
    return acquire(paths);
  }

  public static SessionGuard acquire(Collection<Path> paths) throws IOException {
    var guard = new SessionGuard();
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
