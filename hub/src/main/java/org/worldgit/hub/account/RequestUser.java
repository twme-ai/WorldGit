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
  public record Result(User user, boolean credentialsPresent, boolean anonymous) {
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
    if (h == null || h.isBlank()) return new Result(null, false, true);
    if (h.regionMatches(true, 0, "Bearer ", 0, 7))
      return new Result(throttle.authenticate("bearer", req, true, () -> accounts.authenticateToken(h.substring(7).trim())).orElse(null), true, false);
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
      var result = throttle.authenticate(name, req, false, () -> {
        Optional<User> byToken = accounts.authenticateToken(secret);
        return byToken.isPresent() ? byToken : accounts.authenticatePassword(name, secret);
      });
      return new Result(result.orElse(null), true, false);
    }
    return new Result(null, true, false);
  }
}
