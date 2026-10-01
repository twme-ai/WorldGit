package org.worldgit.hub.web;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.web.bind.annotation.*;
import org.worldgit.hub.account.AccountService;
import org.worldgit.hub.account.Models.*;

@RestController
@RequestMapping("/api/v1")
public class AccountController {
  private final AccountService accounts;
  private final Access access;
  private final org.worldgit.hub.account.AuthThrottle throttle;

  public AccountController(AccountService accounts, Access access, org.worldgit.hub.account.AuthThrottle throttle) {
    this.accounts = accounts;
    this.access = access;
    this.throttle = throttle;
  }

  record Login(String username, String password) {}

  @PostMapping("/auth/login")
  Map<String, Object> login(HttpServletRequest req, @RequestBody Login body) {
    User u = throttle.authenticate(body.username(), req, false, () -> accounts.authenticatePassword(body.username(), body.password()))
        .orElseThrow(() -> new ApiError.Unauthorized("帳號或密碼錯誤"));
    return Map.of("token", accounts.createToken(u, "web session", true), "user", userJson(u));
  }

  @GetMapping("/me")
  Map<String, Object> me(HttpServletRequest req) {
    User u = access.optionalUser(req);
    return u == null ? Map.of("user", Map.of()) : Map.of("user", userJson(u));
  }

  static Map<String, Object> userJson(User u) {
    return Map.of("id", u.id(), "username", u.username(), "admin", u.admin());
  }

  record NewUser(String username, String password, Boolean admin) {}

  @PostMapping("/users")
  Map<String, Object> createUser(HttpServletRequest req, @RequestBody NewUser body) {
    User actor = access.requireUser(req);
    if (!actor.admin()) throw new SecurityException("只有管理員可以建立帳號");
    return userJson(accounts.createUser(body.username(), body.password(), Boolean.TRUE.equals(body.admin())));
  }

  record NewToken(String name, java.time.Instant expiresAt) {}

  @PostMapping("/tokens")
  Map<String, Object> createToken(HttpServletRequest req, @RequestBody(required = false) NewToken body) {
    User u = access.requireUser(req);
    return Map.of("token", accounts.createToken(u, body == null ? null : body.name(), false, body == null ? null : body.expiresAt()));
  }

  @GetMapping("/tokens")
  List<Map<String, Object>> tokens(HttpServletRequest req) {
    return accounts.listTokens(access.requireUser(req));
  }

  @DeleteMapping("/tokens/{id}")
  Map<String, Object> deleteToken(HttpServletRequest req, @PathVariable String id) {
    return Map.of("deleted", accounts.deleteToken(access.requireUser(req), id));
  }

  record NewOrg(String slug, String displayName) {}

  @PostMapping("/orgs")
  Map<String, Object> createOrg(HttpServletRequest req, @RequestBody NewOrg body) {
    accounts.createOrganization(access.requireUser(req), body.slug(), body.displayName());
    return Map.of("slug", body.slug());
  }

  record Member(String role) {}

  @PutMapping("/orgs/{slug}/members/{username}")
  Map<String, Object> member(HttpServletRequest req, @PathVariable String slug, @PathVariable String username, @RequestBody Member body) {
    accounts.setMember(access.requireUser(req), slug, username, Role.valueOf(body.role().toUpperCase(Locale.ROOT)));
    return Map.of("ok", true);
  }
}
