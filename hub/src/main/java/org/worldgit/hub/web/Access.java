package org.worldgit.hub.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.worldgit.core.model.DimensionId;
import org.worldgit.hub.account.AccountService;
import org.worldgit.hub.account.Models.*;
import org.worldgit.hub.account.RequestUser;
import org.worldgit.hub.storage.NameRules;

/** 控制器共用的權限檢查：找不到或無權讀取的私人世界一律回 404（不洩漏存在）。 */
@Component
public class Access {
  private final AccountService accounts;
  private final RequestUser users;

  public Access(AccountService accounts, RequestUser users) {
    this.accounts = accounts;
    this.users = users;
  }

  public User requireUser(HttpServletRequest req) {
    var r = users.resolve(req);
    if (r.user() == null) throw new ApiError.Unauthorized(r.credentialsPresent() ? "憑證無效或已過期" : "需要登入");
    return r.user();
  }

  public void scope(HttpServletRequest req, String needed) {
    var r = users.resolve(req);
    if (!r.permits(needed)) throw new SecurityException("PAT 需要 " + needed + " scope");
  }

  public User optionalUser(HttpServletRequest req) {
    var r = users.resolve(req);
    if (r.invalid()) throw new ApiError.Unauthorized("憑證無效或已過期");
    return r.user();
  }

  public WorldRow world(HttpServletRequest req, String owner, String slug, Role needed) {
    User user = optionalUser(req);
    WorldRow w = accounts.findWorld(owner, slug).orElseThrow(() -> new ApiError.NotFound("找不到世界 " + owner + "/" + slug));
    Role role = accounts.roleOn(user, w);
    if (role == Role.NONE) {
      throw new ApiError.NotFound("找不到世界 " + owner + "/" + slug);
    }
    if (!role.atLeast(needed)) throw new SecurityException("權限不足（需要 " + needed + "）");
    scope(req, needed.atLeast(Role.ADMIN) ? "admin" : needed.atLeast(Role.WRITER) ? "write" : "read");
    req.setAttribute("worldgit.world",w);req.setAttribute("worldgit.actor",user);
    return w;
  }

  public static DimensionId dimension(String dir) {
    DimensionId d = NameRules.dimensionFromDirectory(dir);
    if (d == null) throw new IllegalArgumentException("維度名稱無效：" + dir);
    return d;
  }
}
