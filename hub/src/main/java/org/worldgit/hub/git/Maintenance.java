package org.worldgit.hub.git;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.worldgit.core.store.JGitStore;
import org.worldgit.hub.config.HubProperties;
import org.worldgit.hub.storage.RepoStorage;
import org.worldgit.hub.storage.OwnerQuota;

/**
 * push 之後的維護（非同步）：依決定 #17 把超過上限的 pack 切成 &lt; 95 MB（呼叫 core 的 bounded repack），
 * 並通知儲存層同步（S3 實作）。
 */
@Component
public class Maintenance {
  private static final Logger log = LoggerFactory.getLogger(Maintenance.class);
  private final RepoStorage storage;
  private final HubProperties props;
  private final OwnerQuota quota;

  public Maintenance(RepoStorage storage, HubProperties props, OwnerQuota quota) {
    this.storage = storage;
    this.props = props;
    this.quota = quota;
  }

  @Async
  public void afterPush(RepoRef ref) {
    Path dir = storage.repoPath(ref.owner(), ref.world(), ref.dimension());
    ReentrantLock lock = quota.lock(ref.owner());
    lock.lock();
    try {
      long limit = props.git().packLimitBytes();
      if (largestPack(dir) > limit) {
        try (JGitStore store = new JGitStore(dir, false)) {
          List<Long> sizes = store.repack();
          log.info("repack {}：{} 個 pack，最大 {} bytes", dir.getFileName(), sizes.size(), sizes.stream().mapToLong(Long::longValue).max().orElse(0));
        }
      }
      storage.afterWrite(ref.owner(), ref.world(), ref.dimension());
    } catch (IOException | RuntimeException e) {
      log.error("維護失敗 {}：{}", dir, e.toString());
    } finally {
      lock.unlock();
    }
  }

  static long largestPack(Path repo) throws IOException {
    Path packs = repo.resolve("objects/pack");
    if (!Files.isDirectory(packs)) return 0;
    try (var s = Files.list(packs)) {
      return s.filter(p -> p.toString().endsWith(".pack")).mapToLong(p -> p.toFile().length()).max().orElse(0);
    }
  }
}
