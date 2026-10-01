package org.worldgit.hub.git;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import org.eclipse.jgit.errors.RepositoryNotFoundException;
import org.eclipse.jgit.transport.resolver.ServiceNotAuthorizedException;
import org.eclipse.jgit.transport.resolver.ServiceNotEnabledException;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.resolver.RepositoryResolver;
import org.worldgit.hub.account.AccountService;
import org.worldgit.hub.account.Models.User;
import org.worldgit.hub.storage.RepoStorage;

/** 把 /git/{owner}/{world}/{維度}.git 對應到儲存層的 bare repo；push 到不存在的 repo 時依設定自動建立。 */
public class HubRepositoryResolver implements RepositoryResolver<HttpServletRequest> {
  private final RepoStorage storage;
  private final RepoCache cache;
  private final AccountService accounts;
  private final boolean autoCreate;

  public HubRepositoryResolver(RepoStorage storage, RepoCache cache, AccountService accounts, boolean autoCreate) {
    this.storage = storage;
    this.cache = cache;
    this.accounts = accounts;
    this.autoCreate = autoCreate;
  }

  @Override
  public Repository open(HttpServletRequest req, String name)
      throws RepositoryNotFoundException, ServiceNotAuthorizedException, ServiceNotEnabledException {
    RepoRef ref = (RepoRef) req.getAttribute(GitAuthFilter.ATTR_REF);
    if (ref == null) ref = RepoRef.parse(name);
    if (ref == null) throw new RepositoryNotFoundException(name);
    try {
      if (!storage.exists(ref.owner(), ref.world(), ref.dimension())) {
        User user = (User) req.getAttribute(GitAuthFilter.ATTR_USER);
        if (!autoCreate || user == null || !GitAuthFilter.isPush(req)) throw new RepositoryNotFoundException(name);
        if (accounts.findWorld(ref.owner(), ref.world()).isEmpty()) {
          // 自動建立的世界預設為私人；之後可在 API 改成公開。
          accounts.createWorld(user, ref.owner(), ref.world(), ref.world(), "", false);
        }
        storage.create(ref.owner(), ref.world(), ref.dimension());
      }
      return cache.openRepository(storage.repoPath(ref.owner(), ref.world(), ref.dimension()));
    } catch (RepositoryNotFoundException e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      var ex = new RepositoryNotFoundException(name, e);
      throw ex;
    }
  }
}
