package org.worldgit.hub.git;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.worldgit.hub.storage.OwnerQuota;
import org.worldgit.hub.storage.RepoStorage;
import org.springframework.web.filter.OncePerRequestFilter;
import org.worldgit.hub.account.AccountService;
import org.worldgit.hub.account.Models.Role;
import org.worldgit.hub.account.Models.User;
import org.worldgit.hub.account.RequestUser;

/** /git/* 的存取控制：push 需要 WRITER，讀取需要 READER（公開世界匿名可讀）；憑證無效一律 401。 */
public class GitAuthFilter extends OncePerRequestFilter {
  static final String ATTR_REF = GitAuthFilter.class.getName() + ".ref";
  static final String ATTR_USER = GitAuthFilter.class.getName() + ".user";
  private final AccountService accounts;
  private final RequestUser requestUser;
  private final boolean autoCreate;
  private final OwnerQuota quota;
  private final RepoStorage storage;
  private final org.worldgit.hub.collaboration.PullRequests prs;
  static final String ATTR_ACCEPTED = GitAuthFilter.class.getName() + ".accepted";

  public GitAuthFilter(AccountService accounts, RequestUser requestUser, boolean autoCreate, OwnerQuota quota, RepoStorage storage, org.worldgit.hub.collaboration.PullRequests prs) {
    this.accounts = accounts;
    this.requestUser = requestUser;
    this.autoCreate = autoCreate;
    this.quota = quota;
    this.storage = storage;
    this.prs = prs;
  }

  static boolean isPush(HttpServletRequest req) {
    String path = req.getPathInfo() == null ? "" : req.getPathInfo();
    return "git-receive-pack".equals(req.getParameter("service")) || path.endsWith("/git-receive-pack");
  }

  @Override
  protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    String path = req.getPathInfo() == null ? "" : req.getPathInfo();
    // 去掉 /info/refs、/git-upload-pack 等尾巴再解析 repo 名稱
    String name = path.replaceAll("/(info/refs|git-upload-pack|git-receive-pack|HEAD|objects/.*)$", "");
    RepoRef ref = RepoRef.parse(name);
    if (ref == null) {
      reject(res, 404, "unknown repository");
      return;
    }
    RequestUser.Result auth;
    try { auth = requestUser.resolve(req); }
    catch (org.worldgit.hub.account.AuthThrottle.Limited e) {
      res.setHeader("Retry-After", Long.toString(e.retryAfter()));
      reject(res, 429, e.getMessage());
      return;
    }
    // 在任何世界查詢前統一 challenge，避免以 401/404 列舉私人世界。
    if (!auth.credentialsPresent() || auth.invalid()) {
      challenge(res);
      return;
    }
    User user = auth.user();
    if (auth.kind().equals("SESSION")) { challenge(res); return; }
    boolean push = isPush(req);
    var world = accounts.findWorld(ref.owner(), ref.world());
    if (world.isEmpty()) {
      if (!push || !autoCreate) {
        reject(res, 404, "repository not found");
        return;
      }
      if (user == null) {
        reject(res, 404, "repository not found");
        return;
      }
      var owner = accounts.findOwnerId(ref.owner());
      if (owner.isEmpty() || !accounts.canCreateIn(user, owner.get())) {
        reject(res, 404, "repository not found");
        return;
      }
    } else {
      Role role = accounts.roleOn(user, world.get());
      Role needed = push ? Role.WRITER : Role.READER;
      if (!role.atLeast(needed)) {
        reject(res, role == Role.NONE ? 404 : 403, role == Role.NONE ? "repository not found" : "write access required");
        return;
      }
    }
    if (!auth.permits(push ? "write" : "read")) {
      reject(res,403,"PAT scope 不足，需要 write"); return;
    }
    req.setAttribute(ATTR_REF, ref);
    req.setAttribute(ATTR_USER, user);
    if (!push) { chain.doFilter(req, res); return; }
    var lock = quota.lock(ref.owner());
    // 不讓慢速 push 堆積執行緒與待處理 request。
    if (!lock.tryLock()) {
      res.setHeader("Retry-After", "2");
      reject(res, 429, "owner 正在推送或維護，請稍後重試");
      return;
    }
    var repoPath = storage.repoPath(ref.owner(), ref.world(), ref.dimension());
    try {
      if(world.isPresent()) prs.reconcile(world.get());
      long remaining = quota.remaining(ref.owner());
      if (remaining == 0 || !storage.exists(ref.owner(), ref.world(), ref.dimension()) && remaining < 4096) {
        reject(res, 413, quota.message()); return;
      }
      var before = quota.packFiles(repoPath);
      try { chain.doFilter(req, res); }
      finally {
        if (!Boolean.TRUE.equals(req.getAttribute(ATTR_ACCEPTED))) quota.discardNewPacks(repoPath, before);
      }
    } finally { lock.unlock(); }
  }

  private static void reject(HttpServletResponse res, int status, String message) throws IOException {
    res.setStatus(status);
    res.setContentType("text/plain;charset=UTF-8");
    res.getWriter().println(message);
  }

  private static void challenge(HttpServletResponse res) throws IOException {
    res.setHeader("WWW-Authenticate", "Basic realm=\"WorldGit Hub\", charset=\"UTF-8\"");
    reject(res, 401, "authentication required");
  }
}
