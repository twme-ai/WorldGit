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
  private final org.worldgit.hub.operation.Operations operations;
  private final org.worldgit.hub.account.AccountService accounts;
  private final org.worldgit.hub.collaboration.EventService events;

  public Maintenance(RepoStorage storage, HubProperties props, OwnerQuota quota,org.worldgit.hub.operation.Operations operations,org.worldgit.hub.account.AccountService accounts,org.worldgit.hub.collaboration.EventService events) {
    this.storage = storage;
    this.props = props;
    this.quota = quota;this.operations=operations;this.accounts=accounts;this.events=events;
  }

  public java.util.UUID afterPush(RepoRef ref,org.worldgit.hub.account.Models.User user,java.util.List<java.util.Map<String,String>> changes) {
    var world=accounts.findWorld(ref.owner(),ref.world()).orElseThrow();
    org.worldgit.hub.operation.Operations.Entry job;
    try{job=operations.submit(world,user,"push-processing",ref.dimension(),false,false,operations.reports().secrets(null),entry->{
      process(ref,world,changes);return java.util.Map.of("dimension",ref.dimension().value(),"refs",changes.size());
    });}catch(org.worldgit.hub.web.ApiError.Unavailable e){job=operations.partial(world,user,"push-processing",ref.dimension(),"push 已接受；索引／webhook 未排程："+e.getMessage());}
    return job.id;
  }
  private void process(RepoRef ref,org.worldgit.hub.account.Models.WorldRow world,java.util.List<java.util.Map<String,String>> changes)throws IOException {
    Path dir = storage.repoPath(ref.owner(), ref.world(), ref.dimension());
    ReentrantLock lock = quota.lock(ref.owner());
    lock.lock();
    try {
      org.worldgit.core.operation.OperationProgress.report(ref.dimension(),"index",0L,null,org.worldgit.core.operation.OperationProgress.Unit.OBJECT);
      long limit = props.git().packLimitBytes();
      if (largestPack(dir) > limit) {
        try (JGitStore store = new JGitStore(dir, false)) {
          List<Long> sizes = store.repack();
          log.info("repack {}：{} 個 pack，最大 {} bytes", dir.getFileName(), sizes.size(), sizes.stream().mapToLong(Long::longValue).max().orElse(0));
        }
      }
      storage.afterWrite(ref.owner(), ref.world(), ref.dimension());
      org.worldgit.core.operation.OperationProgress.report(ref.dimension(),"webhook",0L,(long)changes.size(),org.worldgit.core.operation.OperationProgress.Unit.OBJECT);
      int count=0;for(var change:changes){events.emit(world,"push",change,java.util.Set.of());org.worldgit.core.operation.OperationProgress.report(ref.dimension(),"webhook",++count,(long)changes.size(),org.worldgit.core.operation.OperationProgress.Unit.OBJECT);}
    } catch (IOException | RuntimeException e) {
      throw e;
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
