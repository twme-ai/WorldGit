package org.worldgit.hub.git;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.RepositoryCache;
import org.eclipse.jgit.lib.RepositoryCache.FileKey;
import org.eclipse.jgit.util.FS;
import org.springframework.stereotype.Component;
import org.worldgit.core.model.DimensionId;
import org.worldgit.core.store.JGitStore;
import org.worldgit.hub.storage.RepoStorage;

/** 共享的 JGit Repository（JGit RepositoryCache，執行緒安全）；每次讀取用自己的 ObjectReader。 */
@Component
public class RepoCache {
  /** 讀取握把：close() 釋放 reader 並遞減 repository 使用計數。 */
  public static final class Handle implements AutoCloseable {
    private final Repository repo;
    private final JGitStore store;

    Handle(Repository repo) {
      this.repo = repo;
      this.store = JGitStore.readOnly(repo);
    }

    public JGitStore store() {
      return store;
    }

    public Repository repository() {
      return repo;
    }

    @Override
    public void close() {
      store.close();
      repo.close();
    }
  }

  private final RepoStorage storage;

  public RepoCache(RepoStorage storage) {
    this.storage = storage;
  }

  /** 開啟 Git 傳輸用的 Repository（呼叫端負責 close）。 */
  public Repository openRepository(Path dir) throws IOException {
    if (!Files.isRegularFile(dir.resolve("HEAD"))) throw new RepositoryNotFoundException(dir.toFile());
    return RepositoryCache.open(FileKey.exact(dir.toFile(), FS.DETECTED), true);
  }

  public Handle open(String owner, String world, DimensionId dimension) throws IOException {
    return new Handle(openRepository(storage.repoPath(owner, world, dimension)));
  }

  public boolean exists(String owner, String world, DimensionId dimension) {
    return storage.exists(owner, world, dimension);
  }
}
