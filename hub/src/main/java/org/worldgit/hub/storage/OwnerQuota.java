package org.worldgit.hub.storage;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.stereotype.Component;
import org.worldgit.hub.config.HubProperties;

/** 單實例的 owner 配額與固定大小鎖池；收包、驗證及 repack 使用同一把 owner 鎖。 */
@Component
public class OwnerQuota {
  private final Path repos;
  private final long limit;
  private final ReentrantLock[] locks = new ReentrantLock[128];

  public OwnerQuota(HubProperties props) {
    repos = props.dataDir().toAbsolutePath().normalize().resolve("repos");
    limit = props.git().ownerQuotaBytes();
    Arrays.setAll(locks, i -> new ReentrantLock());
  }

  public ReentrantLock lock(String owner) { return locks[Math.floorMod(owner.hashCode(), locks.length)]; }

  public long used(String owner) throws IOException {
    NameRules.requireSlug(owner);
    Path dir = repos.resolve(owner);
    if (!Files.exists(dir)) return 0;
    long total = 0;
    try (var paths = Files.walk(dir)) {
      for (Path p : (Iterable<Path>) paths::iterator)
        if (Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) total = Math.addExact(total, Files.size(p));
    }
    return total;
  }

  public long remaining(String owner) throws IOException { return Math.max(0, limit - used(owner)); }

  public void check(String owner) throws IOException {
    if (used(owner) > limit) throw new IOException(message());
  }

  public String message() { return "owner 儲存配額已超過 " + limit + " bytes；請刪除世界或聯絡管理員調整配額"; }

  /** 只移除本次收包新增而且未更新任何 ref 的 pack；不碰既有物件。呼叫端必須持有 owner 鎖。 */
  public Set<Path> packFiles(Path repo) throws IOException {
    Path dir = repo.resolve("objects/pack");
    if (!Files.isDirectory(dir)) return Set.of();
    try (var paths = Files.list(dir)) { return new HashSet<>(paths.toList()); }
  }

  public void discardNewPacks(Path repo, Set<Path> before) throws IOException {
    for (Path p : packFiles(repo)) if (!before.contains(p)) Files.deleteIfExists(p);
  }
}
