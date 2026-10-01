package org.worldgit.hub.git;

import jakarta.servlet.http.HttpServletRequest;
import org.eclipse.jgit.http.server.GitServlet;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.transport.ReceivePack;
import org.eclipse.jgit.transport.resolver.ReceivePackFactory;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.worldgit.hub.account.AccountService;
import org.worldgit.hub.account.Models.User;
import org.worldgit.hub.account.RequestUser;
import org.worldgit.hub.config.HubProperties;
import org.worldgit.hub.storage.RepoStorage;
import org.worldgit.hub.storage.OwnerQuota;

/** 把 JGit 的 GitServlet（smart HTTP clone/fetch/push）掛在 /git/*。 */
@Configuration
public class GitConfiguration {
  @Bean
  public FilterRegistrationBean<GitAuthFilter> gitAuthFilter(AccountService accounts, RequestUser users, HubProperties props, OwnerQuota quota, RepoStorage storage) {
    var bean = new FilterRegistrationBean<>(new GitAuthFilter(accounts, users, props.autoCreateWorlds(), quota, storage));
    bean.addUrlPatterns("/git/*");
    bean.setOrder(1);
    return bean;
  }

  @Bean
  public ServletRegistrationBean<GitServlet> gitServlet(
      RepoStorage storage, RepoCache cache, AccountService accounts, Maintenance maintenance, HubProperties props, OwnerQuota quota) {
    GitServlet servlet = new GitServlet();
    servlet.setRepositoryResolver(new HubRepositoryResolver(storage, cache, accounts, props.autoCreateWorlds()));
    servlet.setReceivePackFactory(receivePackFactory(accounts, maintenance, props, quota));
    var bean = new ServletRegistrationBean<>(servlet, "/git/*");
    bean.setName("git");
    bean.setLoadOnStartup(1);
    return bean;
  }

  private static ReceivePackFactory<HttpServletRequest> receivePackFactory(
      AccountService accounts, Maintenance maintenance, HubProperties props, OwnerQuota quota) {
    return (req, repo) -> {
      RepoRef ref = (RepoRef) req.getAttribute(GitAuthFilter.ATTR_REF);
      User user = (User) req.getAttribute(GitAuthFilter.ATTR_USER);
      if (ref == null) throw new IllegalStateException("缺少 repo 資訊");
      String worldId = accounts.findWorld(ref.owner(), ref.world()).map(w -> w.id()).orElseThrow();
      ReceivePack rp = new ReceivePack(repo);
      rp.setAllowNonFastForwards(false); // main 只能快轉；改寫歷史留給日後的權限模型
      rp.setAllowDeletes(false);
      rp.setCheckReceivedObjects(true);
      try {
        rp.setMaxPackSizeLimit(Math.max(1, Math.min(props.git().maxPackBytes(), quota.remaining(ref.owner()))));
      } catch (java.io.IOException e) { throw new org.eclipse.jgit.transport.resolver.ServiceNotEnabledException(e.getMessage()); }
      rp.setMaxObjectSizeLimit(32L << 20);
      rp.setAllowPushOptions(true);
      rp.setPreReceiveHook(new PushHooks.Pre(ref, accounts, worldId, user, quota));
      rp.setPostReceiveHook(new PushHooks.Post(ref, accounts, worldId, user, maintenance, req));
      return rp;
    };
  }
}
