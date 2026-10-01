package org.worldgit.core.store;

import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;

/** 跨程序及 JVM 的操作鎖，涵蓋 index、HEAD 及 repack。 */
public final class RepoLock implements AutoCloseable {
  private final FileChannel channel;
  private final FileLock lock;

  private RepoLock(FileChannel channel, FileLock lock) {
    this.channel = channel;
    this.lock = lock;
  }

  public static RepoLock acquire(Path directory) throws IOException {
    Files.createDirectories(directory);
    var ch =
        FileChannel.open(
            directory.resolve("worldgit-operation.lock"),
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE);
    try {
      FileLock lock = ch.tryLock();
      if (lock == null) throw new IOException("repo 正由另一個 WorldGit 操作使用：" + directory);
      return new RepoLock(ch, lock);
    } catch (IOException | OverlappingFileLockException e) {
      ch.close();
      throw new IOException("無法取得 repo 操作鎖：" + directory, e);
    }
  }

  @Override
  public void close() throws IOException {
    try {
      lock.release();
    } finally {
      channel.close();
    }
  }
}
