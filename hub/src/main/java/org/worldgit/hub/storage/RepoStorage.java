package org.worldgit.hub.storage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import org.worldgit.core.model.DimensionId;

/**
 * 儲存層介面：每個世界一組維度 bare repo。Phase 1 只有本機磁碟實作；S3 相容實作預留（公開服務），
 * 做法是把 repo 目錄同步到物件儲存，而不是讓 JGit 直接讀 S3。
 */
public interface RepoStorage {
  /** repo 在本機的實際目錄（JGit 需要檔案系統）。 */
  Path repoPath(String owner, String world, DimensionId dimension);

  boolean exists(String owner, String world, DimensionId dimension);

  /** 建立空的 bare repo，HEAD 指向 refs/heads/main。 */
  Path create(String owner, String world, DimensionId dimension) throws IOException;

  List<DimensionId> dimensions(String owner, String world) throws IOException;

  void deleteWorld(String owner, String world) throws IOException;

  /** push 完成後呼叫，讓遠端儲存（S3 實作）有機會同步；本機實作不做事。 */
  default void afterWrite(String owner, String world, DimensionId dimension) {}

  /** 快取目錄（tile、統計、資源包）；可丟棄。 */
  Path cacheDir();
}
