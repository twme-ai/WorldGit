package org.worldgit.hub.account;

import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.worldgit.hub.account.Models.User;

/**
 * 從請求解出使用者：Authorization: Bearer token，或 Basic（git 用；密碼欄可放 token 或帳號密碼）。
 * 帶了憑證但無效時回傳 {@link Result#invalid()}，呼叫端應回 401 而不是當匿名。
 */
@Component
public class RequestUser {
  public static final String SESSION_USER = "worldgit.user";
  public record Result(User user, boolean credentialsPresent, boolean anonymous, String scope, String kind) {
    public Result(User user, boolean credentialsPresent, boolean anonymous) { this(user,credentialsPresent,anonymous,"admin","PASSWORD"); }
    public boolean permits(String needed) {
      return switch (needed) { case "read" -> true; case "write" -> !scope.equals("read"); case "admin" -> scope.equals("admin"); default -> false; };
    }
    public boolean invalid() {
      return user == null && credentialsPresent && !anonymous;
    }
  }

  private static final String ATTR = RequestUser.class.getName();
  private final AccountService accounts;
  private final AuthThrottle throttle;

  public RequestUser(AccountService accounts, AuthThrottle throttle) {
    this.accounts = accounts;
    this.throttle = throttle;
  }

  public Result resolve(HttpServletRequest req) {
    Object cached = req.getAttribute(ATTR);
    if (cached instanceof Result r) return r;
    Result r = compute(req);
    req.setAttribute(ATTR, r);
    return r;
  }

  public User user(HttpServletRequest req) {
    return resolve(req).user();
  }

  private Result compute(HttpServletRequest req) {
    String h = req.getHeader("Authorization");
    if (h != null && h.length() > 8192) {
      throttle.authenticate("invalid-header", req, false, Optional::empty);
      return new Result(null, true, false);
    }
    if (h == null || h.isBlank()) {
      var session = req.getSession(false);
      Object id = session == null ? null : session.getAttribute(SESSION_USER);
      User user = id instanceof String s ? accounts.userById(s).orElse(null) : null;
      if (user != null) throttle.successful(user.id(), req);
      return new Result(user, user != null, user == null, "admin", "SESSION");
    }
    if (h.regionMatches(true, 0, "Bearer ", 0, 7)) {
      var c = throttle.authenticate("bearer", req, true, () -> accounts.credential(h.substring(7).trim())).orElse(null);
      if (c != null) throttle.successful(c.user().id(), req);
      return c == null ? new Result(null,true,false) : new Result(c.user(),true,false,c.scope(),c.kind());
    }
    if (h.regionMatches(true, 0, "Basic ", 0, 6)) {
      String decoded;
      try {
        decoded = new String(Base64.getDecoder().decode(h.substring(6).trim()), StandardCharsets.UTF_8);
      } catch (IllegalArgumentException e) {
        throttle.authenticate("invalid-basic", req, false, Optional::empty);
        return new Result(null, true, false);
      }
      int colon = decoded.indexOf(':');
      String name = colon < 0 ? decoded : decoded.substring(0, colon);
      String secret = colon < 0 ? "" : decoded.substring(colon + 1);
      // 明確選擇匿名讀取；對所有 world 的授權規則相同，絕不提供寫入權。
      if (req.getRequestURI().startsWith("/git/") && name.equals("anonymous") && secret.isEmpty()) return new Result(null, true, true);
      var pat = accounts.credential(secret);
      if (pat.isPresent()) {
        var c = pat.get(); throttle.successful(c.user().id(),req);
        return new Result(c.user(),true,false,c.scope(),c.kind());
      }
      var result = throttle.authenticate(name, req, false, () -> accounts.authenticatePassword(name, secret));
      result.ifPresent(u -> throttle.successful(u.id(),req));
      return new Result(result.orElse(null), true, false);
    }
    throttle.authenticate("invalid-header",req,false,Optional::empty);
    return new Result(null, true, false);
  }
}
